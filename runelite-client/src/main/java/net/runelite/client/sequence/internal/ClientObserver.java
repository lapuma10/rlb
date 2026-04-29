/*
 * Copyright (c) 2024, RuneLite
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.sequence.internal;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.sequence.PlayerView;
import net.runelite.client.sequence.WorldSnapshot;
import net.runelite.client.sequence.views.BankView;
import net.runelite.client.sequence.views.EventFacts;
import net.runelite.client.sequence.views.GrandExchangeView;
import net.runelite.client.sequence.views.ImmutableWorldSnapshot;
import net.runelite.client.sequence.views.InteractionView;
import net.runelite.client.sequence.views.InventoryView;
import net.runelite.client.sequence.views.WidgetView;

/**
 * Production {@link Observer} composing the per-domain observers
 * (player / inventory / bank / widget / interaction / grand-exchange).
 *
 * <p><b>Threading:</b> {@link #snapshot} marshals client reads from the
 * caller's worker thread onto the client thread via
 * {@link ClientThread#invokeLater} + {@link CountDownLatch}, with a short-
 * circuit when the caller is already on the client thread (unit tests /
 * misuse). This is the C1 fix — running {@code snapshot()} on the client
 * thread itself would deadlock {@link net.runelite.client.plugins.recorder.farm.BankInteraction#onClient}
 * since BankInteraction also waits on a latch for the same client thread.
 */
@Slf4j
public final class ClientObserver implements Observer {

    /** How long to wait for the client thread to service a snapshot read.
     *  Matches BankInteraction.onClient — long enough to absorb a busy game
     *  tick, short enough that a wedged client surfaces as a null snapshot
     *  rather than hanging the daemon worker thread indefinitely. */
    private static final long CLIENT_HOP_TIMEOUT_MS = 2000L;

    private final Client client;
    private final ClientThread clientThread;   // optional; null in tests
    private final InventoryObserver inventoryObserver;
    private final WidgetObserver widgetObserver;
    private final BankObserver bankObserver;
    private final InteractionObserver interactionObserver;
    private final GrandExchangeObserver grandExchangeObserver;

    /** Test/legacy ctor — no ClientThread, snapshot reads run on the caller thread. */
    public ClientObserver(Client client) {
        this(client, null);
    }

    /**
     * Production constructor: pass a non-null {@link ClientThread} so
     * {@link #snapshot} can marshal client reads from a worker thread onto
     * the client thread.
     *
     * <p><b>Threading invariant:</b> {@link #snapshot} MUST be called from a
     * worker thread (e.g. the engine's daemon ticker). Calling it from the
     * client thread is supported (short-circuit), but the engine itself must
     * never block the client thread on a {@code latch.await} — that would
     * deadlock the entire game thread.
     */
    public ClientObserver(Client client, ClientThread clientThread) {
        this.client = client;
        this.clientThread = clientThread;
        this.inventoryObserver = new InventoryObserver(client);
        this.widgetObserver    = new WidgetObserver(client);
        this.bankObserver      = new BankObserver(client, widgetObserver);
        this.interactionObserver  = new InteractionObserver(client, bankObserver, widgetObserver);
        this.grandExchangeObserver = new GrandExchangeObserver(client);
    }

    /**
     * Build a snapshot. <b>Must be called from a worker thread.</b> All
     * client reads are marshalled to the client thread; if the caller is
     * already on the client thread the read runs synchronously (short-
     * circuit).
     *
     * <p>Returns a stub snapshot (no inventory / bank / widgets / interaction
     * / GE) when the client-thread hop times out — the engine's tick can
     * complete without NPE-ing on missing views, and the next tick retries.
     */
    @Override
    public WorldSnapshot snapshot(int currentTick) {
        WorldSnapshot result = onClient(() -> readSnapshotOnClientThread(currentTick));
        if (result != null) return result;
        return ImmutableWorldSnapshot.builder()
            .tick(currentTick)
            .player(null)
            .build();
    }

    private WorldSnapshot readSnapshotOnClientThread(int currentTick) {
        PlayerView pv = readPlayer();

        InventoryView   inv     = inventoryObserver.snapshot(currentTick);
        BankView        bank    = bankObserver.snapshot(currentTick);
        WidgetView      wid     = widgetObserver.snapshot();
        InteractionView interaction = interactionObserver.snapshot();
        GrandExchangeView ge    = grandExchangeObserver.read(currentTick);

        EventFacts events = buildEventFacts(currentTick);

        return ImmutableWorldSnapshot.builder()
            .tick(currentTick)
            .player(pv)
            .inventory(inv)
            .bank(bank)
            .widgets(wid)
            .interaction(interaction)
            .grandExchange(ge)
            .events(events)
            .build();
    }

    /**
     * Run {@code s} on the client thread. Short-circuits when the caller is
     * already on the client thread. Otherwise queues via
     * {@link ClientThread#invokeLater} and blocks the calling (worker) thread
     * on a latch up to {@link #CLIENT_HOP_TIMEOUT_MS}.
     *
     * <p>Returns null on timeout or interrupt — the snapshot path treats null
     * as a stub-snapshot fallback rather than an exception.
     */
    private <T> T onClient(Supplier<T> s) {
        // Short-circuit when already on the client thread (unit tests with no
        // ClientThread, or a misuse where snapshot() was called on-thread).
        // Avoids the latch deadlock that would occur if we queued + awaited.
        if (clientThread == null || client.isClientThread()) {
            try {
                return s.get();
            } catch (Throwable th) {
                log.warn("ClientObserver: synchronous snapshot read threw", th);
                return null;
            }
        }
        AtomicReference<T> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        clientThread.invokeLater(() -> {
            try { ref.set(s.get()); }
            catch (Throwable th) { log.warn("ClientObserver: client-thread snapshot read threw", th); }
            finally { latch.countDown(); }
        });
        try {
            if (!latch.await(CLIENT_HOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                log.warn("ClientObserver: snapshot read timed out after {}ms", CLIENT_HOP_TIMEOUT_MS);
                return null;
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return null;
        }
        return ref.get();
    }

    // ---- player -----------------------------------------------------------------

    /** Reads the local player into a {@link PlayerView}.
     *  Preserves existing semantics (LoginRunner depends on it). */
    private PlayerView readPlayer() {
        Player p = client.getLocalPlayer();
        return (p == null) ? null : new ClientPlayerView(
            p.getWorldLocation(), p.getAnimation(),
            client.getBoostedSkillLevel(net.runelite.api.Skill.HITPOINTS),
            client.getRealSkillLevel(net.runelite.api.Skill.HITPOINTS));
    }

    // ---- EventFacts -------------------------------------------------------------

    private EventFacts buildEventFacts(int currentTick) {
        final int invChangeTick  = inventoryObserver.lastChangeTick();
        final int bankChangeTick = bankObserver.lastChangeTick();
        return new EventFacts() {
            @Override public int lastInventoryChangeTick()         { return invChangeTick; }
            @Override public int lastBankContainerChangeTick()     { return bankChangeTick; }
            @Override public int lastBlockingInterfaceChangeTick() { return -1; }
            @Override public int lastPlayerAnimationChangeTick()   { return -1; }
        };
    }

    // ---- inner classes ----------------------------------------------------------

    private static final class ClientPlayerView implements PlayerView {
        private final WorldPoint worldLocation;
        private final int animation;
        private final int health;
        private final int maxHealth;

        ClientPlayerView(WorldPoint worldLocation, int animation, int health, int maxHealth) {
            this.worldLocation = worldLocation;
            this.animation = animation;
            this.health = health;
            this.maxHealth = maxHealth;
        }

        @Override public WorldPoint worldLocation() { return worldLocation; }
        @Override public int animation()            { return animation; }
        @Override public boolean isIdle()           { return animation == -1; }
        @Override public int health()               { return health; }
        @Override public int maxHealth()            { return maxHealth; }
    }
}
