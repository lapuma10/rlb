/*
 * Copyright (c) 2026, RuneLite
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
package net.runelite.client.plugins.recorder.mining;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.recorder.transport.TransportResolver;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import net.runelite.client.sequence.internal.ActionRequest;
import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Orchestrates a mining loop. Mirrors the chicken combat loop's structure:
 * lock onto one rock, swing until depleted, never click while the engine is
 * driving the swing, drop or bank when the inventory fills.
 *
 * <p>State machine — see {@code 2026-04-26-mining-routine.md}:
 * <pre>
 * IDLE → SELECTING → SWINGING → (DEPLETED | INVENTORY_FULL) → SELECTING ...
 *                              ↘ animation-dropped → SELECTING (re-click)
 * </pre>
 *
 * <p>The class owns a daemon thread; {@link #start} and {@link #stop} are
 * the only mutators visible to the panel. All scene reads through
 * {@link HumanizedInputDispatcher#runOnClient(java.util.function.Supplier)}.
 */
@Slf4j
public final class MiningLoop
{
    public enum State { IDLE, SELECTING, SWINGING, DEPLETED, INVENTORY_FULL, ABORTED }

    /** Polling cadence for state transitions. ~120ms is fast enough that
     *  we usually catch the animation drop within one or two ticks
     *  (~600ms / tick) without over-reading. */
    private static final long POLL_MS = 120L;
    /** Max ticks of "no animation while rock is alive" before we give up
     *  and re-click the rock. Two ticks tolerate the engine's "click → walk
     *  one tile → swing" startup; more would be visibly slow. */
    private static final int ANIMATION_DROP_THRESHOLD_TICKS = 2;
    /** Hard cap on a single SWINGING phase: if we never see depletion or
     *  inventory progress for this many polls, abort. ~150 polls × 120ms
     *  = 18s, which covers the slowest pickaxe + rock combo with margin. */
    private static final int SWING_TIMEOUT_POLLS = 150;
    /** How many recent depletion-tile entries we remember; long enough to
     *  outlast the engine's compose-swap propagation (a few ticks). */
    private static final int RECENT_DEPLETED_HISTORY = 3;

    private final HumanizedInputDispatcher dispatcher;
    private final Client client;
    private final ClientThread clientThread;
    private final RockSelector selector = new RockSelector();
    private final BankingStrategy strategy;
    private final Consumer<String> statusCallback;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<Thread> worker = new AtomicReference<>();
    private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);
    private final AtomicReference<MiningTarget> currentTarget = new AtomicReference<>();
    private final AtomicReference<Integer> oresMined = new AtomicReference<>(0);

    private final List<RockSelector.Candidate> candidates = new ArrayList<>();

    public MiningLoop(HumanizedInputDispatcher dispatcher, Client client,
                      ClientThread clientThread, BankingStrategy strategy,
                      @Nullable Consumer<String> statusCallback)
    {
        this.dispatcher = dispatcher;
        this.client = client;
        this.clientThread = clientThread;
        this.strategy = strategy == null ? new PowerMineStrategy() : strategy;
        this.statusCallback = statusCallback == null ? s -> { } : statusCallback;
    }

    public State state() { return state.get(); }
    public int oresMined() { return oresMined.get(); }
    public BankingStrategy strategy() { return strategy; }

    public synchronized void addCandidate(WorldPoint tile, @Nullable OreType type)
    {
        if (tile == null) return;
        candidates.add(new RockSelector.Candidate(tile, type));
    }

    public synchronized void clearCandidates() { candidates.clear(); }

    public synchronized List<RockSelector.Candidate> candidatesSnapshot()
    {
        return new ArrayList<>(candidates);
    }

    /** Start the loop on a daemon thread. No-op if already running. */
    public synchronized void start()
    {
        if (!running.compareAndSet(false, true))
        {
            log.info("MiningLoop start: already running");
            return;
        }
        oresMined.set(0);
        state.set(State.SELECTING);
        Thread t = new Thread(this::run, "mining-loop");
        t.setDaemon(true);
        worker.set(t);
        t.start();
        statusCallback.accept("mining started");
    }

    /** Request stop. The loop exits at its next poll boundary; not blocking. */
    public synchronized void stop()
    {
        if (!running.compareAndSet(true, false))
        {
            return;
        }
        Thread t = worker.getAndSet(null);
        if (t != null) t.interrupt();
        state.set(State.IDLE);
        statusCallback.accept("mining stopped");
    }

    /** True if the worker thread is alive. */
    public boolean isRunning() { return running.get(); }

    // ------------------------------------------------------------------------
    // Main loop body
    // ------------------------------------------------------------------------

    private void run()
    {
        Deque<WorldPoint> recentDepleted = new ArrayDeque<>();
        try
        {
            while (running.get() && !Thread.currentThread().isInterrupted())
            {
                State s = state.get();
                switch (s)
                {
                    case SELECTING -> doSelecting(recentDepleted);
                    case SWINGING -> doSwinging();
                    case DEPLETED -> {
                        // Release lock; the depleted set is updated in
                        // doSwinging when we transition into DEPLETED.
                        currentTarget.set(null);
                        state.set(State.SELECTING);
                    }
                    case INVENTORY_FULL -> doInventoryFull();
                    case ABORTED, IDLE -> {
                        running.set(false);
                        return;
                    }
                    default -> running.set(false);
                }
            }
        }
        catch (InterruptedException ie)
        {
            Thread.currentThread().interrupt();
        }
        catch (Throwable th)
        {
            log.warn("MiningLoop crashed", th);
            statusCallback.accept("crashed: " + th.getClass().getSimpleName());
        }
        finally
        {
            running.set(false);
            currentTarget.set(null);
            if (state.get() != State.ABORTED) state.set(State.IDLE);
        }
    }

    private void doSelecting(Deque<WorldPoint> recentDepleted) throws InterruptedException
    {
        // Drain any depleted-tile note left by the last SWINGING so we
        // don't immediately re-pick the corpse.
        WorldPoint just = lastDepleted.getAndSet(null);
        if (just != null) recordDepleted(recentDepleted, just);
        if (inventoryFull())
        {
            state.set(State.INVENTORY_FULL);
            return;
        }
        WorldPoint here = playerPosition();
        if (here == null) { Thread.sleep(POLL_MS); return; }
        Set<WorldPoint> depletedSet = new HashSet<>(recentDepleted);
        RockSelector.Candidate candidate;
        synchronized (this) { candidate = selector.pick(candidates, here, depletedSet); }
        if (candidate == null)
        {
            statusCallback.accept("no live rock in candidates");
            state.set(State.ABORTED);
            return;
        }
        // Resolve the live object id on the client thread via TransportResolver.
        TransportResolver tr = new TransportResolver(client);
        TransportResolver.Match match = onClient(() -> tr.findTransport(candidate.tile(), "Mine"));
        if (match == null || !match.isSuccess())
        {
            // No "Mine" verb on this tile right now — treat as depleted from
            // selecting's POV and move on.
            recordDepleted(recentDepleted, candidate.tile());
            Thread.sleep(POLL_MS);
            return;
        }
        int objectId = match.matchedObjectId();
        Integer tick = onClient(client::getTickCount);
        MiningTarget target = new MiningTarget(objectId, candidate.tile(),
            candidate.oreType(), tick == null ? 0 : tick);
        currentTarget.set(target);
        // Click via CLICK_GAME_OBJECT. This is the only place in the loop
        // that issues an attack click — see the design doc.
        ActionRequest req = ActionRequest.builder()
            .kind(ActionRequest.Kind.CLICK_GAME_OBJECT)
            .channel(ActionRequest.Channel.MOUSE)
            .tile(candidate.tile())
            .verb("Mine")
            .build();
        statusCallback.accept("mining → " + candidate.tile().getX() + "," + candidate.tile().getY());
        dispatcher.dispatch(req);
        // Wait for the dispatcher to release its busy flag (cursor path
        // completed). After that the engine is driving the action.
        while (dispatcher.isBusy())
        {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("stop");
            Thread.sleep(POLL_MS / 2);
        }
        String err = dispatcher.lastErrorMessage();
        if (err != null)
        {
            log.info("MiningLoop SELECTING dispatch error: {}", err);
            statusCallback.accept("dispatch err: " + err);
            recordDepleted(recentDepleted, candidate.tile());
            return;
        }
        state.set(State.SWINGING);
    }

    private void doSwinging() throws InterruptedException
    {
        MiningTarget target = currentTarget.get();
        if (target == null) { state.set(State.SELECTING); return; }
        MiningStateTracker tracker = new MiningStateTracker(target.gameObjectId());
        TransportResolver tr = new TransportResolver(client);
        int polls = 0;
        int prevOreCount = inventoryItemCount(target.oreType());
        while (running.get() && state.get() == State.SWINGING)
        {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("stop");
            polls++;
            if (polls > SWING_TIMEOUT_POLLS)
            {
                statusCallback.accept("swing timeout — reselect");
                state.set(State.SELECTING);
                return;
            }
            // Snapshot per-poll state on the client thread.
            SwingSnapshot snap = onClient(() -> {
                Player local = client.getLocalPlayer();
                int anim = local == null ? -1 : local.getAnimation();
                TransportResolver.Match m = tr.findTransport(target.tile(), "Mine");
                int liveId = (m != null && m.isSuccess()) ? m.matchedObjectId() : -1;
                boolean verbOk = (m != null && m.isSuccess());
                ItemContainer inv = client.getItemContainer(InventoryID.INV);
                boolean full = inv != null && inv.count() >= 28;
                return new SwingSnapshot(anim, liveId, verbOk, full);
            });
            if (snap == null) { Thread.sleep(POLL_MS); continue; }
            tracker.observe(snap.anim(), snap.liveObjectId(), snap.verbOk(), snap.invFull());

            // Inventory progress — bump kill counter if a new ore landed.
            int curOreCount = inventoryItemCount(target.oreType());
            if (curOreCount > prevOreCount)
            {
                final int delta = curOreCount - prevOreCount;
                oresMined.updateAndGet(n -> n + delta);
                prevOreCount = curOreCount;
            }

            State next = nextSwingState(tracker, ANIMATION_DROP_THRESHOLD_TICKS);
            if (next != State.SWINGING)
            {
                if (next == State.DEPLETED) recordDepletedTile(target.tile());
                else if (next == State.SELECTING)
                    statusCallback.accept("animation dropped — reselect");
                state.set(next);
                return;
            }
            Thread.sleep(POLL_MS);
        }
    }

    private void doInventoryFull() throws InterruptedException
    {
        statusCallback.accept(strategy.label() + " emptying…");
        MiningLoopContext ctx = new MiningLoopContext(dispatcher, client, clientThread);
        try
        {
            strategy.empty(ctx);
        }
        catch (InterruptedException ie) { throw ie; }
        catch (Throwable th)
        {
            log.warn("strategy crashed", th);
            statusCallback.accept("strategy crashed: " + th.getClass().getSimpleName());
            state.set(State.ABORTED);
            return;
        }
        // Re-evaluate inventory; if the strategy didn't drop everything we
        // bail rather than spinning forever.
        if (inventoryFull())
        {
            statusCallback.accept(strategy.label() + " did not free space — abort");
            state.set(State.ABORTED);
            return;
        }
        state.set(State.SELECTING);
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    /** Snapshot record used by doSwinging — pulled into a record so the
     *  client-thread closure stays simple. */
    private record SwingSnapshot(int anim, int liveObjectId, boolean verbOk, boolean invFull) { }

    /**
     * Pure decision function for the SWINGING state.
     * Exposed package-private so {@code MiningLoopTest} can verify the
     * state-transition rules without needing a real dispatcher.
     *
     * @return the next {@link State}; {@link State#SWINGING} means "stay".
     */
    static State nextSwingState(MiningStateTracker tracker, int animationDropThresholdTicks)
    {
        if (tracker.isDepleted()) return State.DEPLETED;
        if (tracker.isInventoryFull()) return State.INVENTORY_FULL;
        if (tracker.isAnimationDropped(animationDropThresholdTicks)) return State.SELECTING;
        return State.SWINGING;
    }

    @Nullable
    private WorldPoint playerPosition() throws InterruptedException
    {
        return onClient(() -> {
            Player p = client.getLocalPlayer();
            return p == null ? null : p.getWorldLocation();
        });
    }

    private boolean inventoryFull() throws InterruptedException
    {
        Boolean b = onClient(() -> {
            ItemContainer c = client.getItemContainer(InventoryID.INV);
            return c != null && c.count() >= 28;
        });
        return Boolean.TRUE.equals(b);
    }

    private int inventoryItemCount(@Nullable OreType type) throws InterruptedException
    {
        if (type == null) return 0;
        Integer n = onClient(() -> {
            ItemContainer c = client.getItemContainer(InventoryID.INV);
            return c == null ? 0 : c.count(type.oreItemId());
        });
        return n == null ? 0 : n;
    }

    private void recordDepleted(Deque<WorldPoint> recent, WorldPoint tile)
    {
        recent.addLast(tile);
        while (recent.size() > RECENT_DEPLETED_HISTORY) recent.pollFirst();
    }

    /** Record-depleted entry in the loop-private deque kept inside run().
     *  Exposed via this slim wrapper for doSwinging which doesn't see the
     *  deque directly — we just push to the deque on the next SELECTING. */
    private void recordDepletedTile(WorldPoint tile)
    {
        // Stored as the most-recent target tile so the next SELECTING can
        // skip it; done via state.set(DEPLETED) which the run() loop reads
        // and then transitions to SELECTING with the deque updated.
        // The actual deque push happens in doSelecting on next entry —
        // keeping the per-thread state localised. We pass the tile through
        // a transient atomic so the next iteration sees it.
        lastDepleted.set(tile);
    }

    private final AtomicReference<WorldPoint> lastDepleted = new AtomicReference<>();

    /** Drains lastDepleted into the per-run deque. Called by doSelecting
     *  before picking. */
    @SuppressWarnings("unused")
    private void drainLastDepleted(Deque<WorldPoint> recentDepleted)
    {
        WorldPoint t = lastDepleted.getAndSet(null);
        if (t != null) recordDepleted(recentDepleted, t);
    }

    private <T> T onClient(java.util.function.Supplier<T> task) throws InterruptedException
    {
        return dispatcher.runOnClient(task);
    }
}
