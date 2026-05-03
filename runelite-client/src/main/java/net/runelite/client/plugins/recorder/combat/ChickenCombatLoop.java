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
package net.runelite.client.plugins.recorder.combat;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import net.runelite.client.sequence.dispatch.SequenceSleep;
import net.runelite.client.sequence.internal.ActionRequest;
import net.runelite.client.util.Text;
import javax.annotation.Nullable;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Combat orchestrator: walks the state machine
 * {@code IDLE → SELECTING → ENGAGING → IN_COMBAT → KILLED → SELECTING}
 * for the chicken-coop activity.
 *
 * <p><b>Hard rule.</b> While a {@link CombatTarget} is set, the loop dispatches
 * NO new clicks except a re-attack on the locked NPC if engagement is broken.
 * It never clicks other chickens, never path-walks, and never stacks attack
 * clicks. The lock is released only on KILLED or ABORTED.
 *
 * <p>All scene/NPC reads happen on the client thread via
 * {@link #onClient(Supplier)}. The state-machine loop runs on a daemon worker
 * thread so it can poll without freezing the EDT.
 */
@Slf4j
public final class ChickenCombatLoop
{
    /**
     * The five visible loop states. Surface them via {@link #state()} for the
     * panel; ABORTED is terminal and only reachable when there are no more
     * chickens.
     */
    public enum State
    {
        IDLE,
        SELECTING,
        ENGAGING,
        IN_COMBAT,
        KILLED,
        LOOTING,
        ABORTED
    }

    /** Default chicken name. Public so tests / panels can reuse the literal. */
    public static final String CHICKEN_NAME = "Chicken";
    /** Tick budget for SELECTING — bail to ABORTED if no chicken found. */
    private static final int SELECT_TIMEOUT_TICKS = 60;
    /** Cap on engaging — re-attempt or fall back to SELECTING after this. */
    private static final long ENGAGE_TIMEOUT_MS = 5_000L;
    /** Combat poll cadence — one OSRS tick is ~600ms. */
    private static final long POLL_INTERVAL_MS = 600L;
    /** Hard cap on the LOOTING state. Humanized take-clicks routinely take
     *  2-3s end-to-end (cursor path + hover + right-click + menu pick), so
     *  collecting 2-3 drops needs ~6-9s; 10s covers spawn + picks with
     *  margin, while still bailing out promptly if loot was griefed. */
    private static final long LOOT_TIMEOUT_MS = 10_000L;
    /** Inventory-delta poll window after a successful click chain. The chain
     *  itself is awaited via {@link CombatDispatcher#isBusy()} (capped only
     *  by the overall loot deadline) — this constant only bounds the wait
     *  for the bag count to actually tick up after the click resolved. */
    private static final long LOOT_PICKUP_WAIT_MS = 1_500L;
    /** Items we collect after a chicken kill. Strict — bones (526) and any
     *  other drops are deliberately excluded per requirement. */
    private static final java.util.Set<Integer> LOOT_ITEM_IDS = java.util.Set.of(
        net.runelite.api.gameval.ItemID.FEATHER,
        net.runelite.api.gameval.ItemID.RAW_CHICKEN,
        net.runelite.api.gameval.ItemID.RAW_CHICKEN_UNDEAD
    );
    /** Engagement-broken threshold (ticks). Spec says "&gt;2 ticks". */
    private static final int BROKEN_THRESHOLD_TICKS = 2;
    /** Kill-steal threshold (ticks). One extra tick of confirmation before
     *  bailing — guards against transient retarget mid-tick while still
     *  reacting in ~1.2s, well before the bot looks like it's standing on
     *  someone else's chicken. */
    private static final int STOLEN_THRESHOLD_TICKS = 1;
    /** Polls of "player not loaded" we'll tolerate before aborting. ~1.8s at
     *  600ms cadence — covers a brief loading-screen flicker but bails fast
     *  if the user started the loop on the title screen. */
    private static final int NOT_READY_GRACE_TICKS = 3;
    /** Short memory for a chicken another player claimed, so we don't bounce
     *  right back into the same "already fighting" target. */
    private static final long CLAIMED_NPC_COOLDOWN_MS = 5_000L;
    private static final String ALREADY_FIGHTING_MESSAGE = "someone else is fighting that";

    private final CombatDispatcher dispatcher;
    private final Client client;
    @Nullable private final ClientThread clientThread;
    @Nullable private final EventBus eventBus;
    private final NpcSelector selector;
    private final TargetVisibility visibility;
    private final Consumer<String> statusSink;
    private final Random rng = new Random();

    private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);
    private final AtomicReference<CombatTarget> target = new AtomicReference<>(null);
    private final AtomicReference<String> latestStatus = new AtomicReference<>("idle");
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    /** Set by {@link #stopAfterCurrentKill()} — prevents the next
     *  {@link #doSelect()} from starting a new fight. Unlike
     *  {@link #stopRequested}, this does NOT interrupt the current
     *  kill or loot; the loop drains naturally. */
    private final AtomicBoolean stopAfterKill = new AtomicBoolean(false);
    private final AtomicInteger killCount = new AtomicInteger(0);
    /** World tile of the most recent confirmed kill, captured during the
     *  combat tick that flips KILLED. Read by {@link #doKilled} to seed
     *  the LOOTING state. Cleared once LOOTING completes (either all
     *  matching items collected or the timeout fires). */
    private final AtomicReference<WorldPoint> lastKillTile = new AtomicReference<>(null);
    /** Wall-clock deadline (ms since epoch) for the current LOOTING phase.
     *  Hard cap so we don't get stuck if loot never spawns or another
     *  player picks it up before us. */
    private long lootDeadlineMs = 0;
    private volatile Thread worker;
    /** True for the first {@code doSelect} call after entering SELECTING.
     *  Used to emit the "why no pick" diagnostic exactly once per entry,
     *  rather than every 600ms while we tick the budget down. */
    private volatile boolean justEnteredSelecting = false;
    /** Counter for consecutive "player not loaded" polls. Resets on any
     *  successful snapshot. Triggers an early abort once it crosses
     *  {@link #NOT_READY_GRACE_TICKS}. */
    private int notReadyTicks = 0;
    private volatile boolean registeredOnEventBus = false;
    private final AtomicBoolean serverRejectedAttack = new AtomicBoolean(false);
    private final AtomicInteger claimedNpcCooldownIndex = new AtomicInteger(-1);
    private volatile long claimedNpcCooldownUntilMs = 0;

    /** Convenience: wrap the humanized dispatcher and use the default
     *  selector + visibility filter (LOS / canvas / viewport / open menu)
     *  + no-op status sink. The plugin uses the full constructor. */
    public ChickenCombatLoop(HumanizedInputDispatcher dispatcher,
                             Client client,
                             @Nullable ClientThread clientThread)
    {
        this(CombatDispatcher.forHumanized(dispatcher), client, clientThread, null,
            new NpcSelector(CHICKEN_NAME), TargetVisibility.forClient(client), s -> {});
    }

    /** Same as the 3-arg constructor but pins target selection to a given
     *  WorldArea. The farm loop uses this to constrain combat to the pen. */
    public ChickenCombatLoop(HumanizedInputDispatcher dispatcher,
                             Client client,
                             @Nullable ClientThread clientThread,
                             @Nullable WorldArea confineTo)
    {
        this(CombatDispatcher.forHumanized(dispatcher), client, clientThread, null,
            new NpcSelector(CHICKEN_NAME, NpcSelector.DEFAULT_RANGE, confineTo),
            TargetVisibility.forClient(client), s -> {});
    }

    /** V3 production ctor — same as the area-constrained constructor, but
     *  with an EventBus so the loop can react immediately to server-side
     *  engage rejection chat lines. */
    public ChickenCombatLoop(HumanizedInputDispatcher dispatcher,
                             Client client,
                             @Nullable ClientThread clientThread,
                             @Nullable WorldArea confineTo,
                             @Nullable EventBus eventBus)
    {
        this(CombatDispatcher.forHumanized(dispatcher), client, clientThread, eventBus,
            new NpcSelector(CHICKEN_NAME, NpcSelector.DEFAULT_RANGE, confineTo),
            TargetVisibility.forClient(client), s -> {});
    }

    /** Tests can pass {@link TargetVisibility#alwaysVisible()} to bypass the
     *  per-NPC visibility filter without stubbing canvas / collision data. */
    public ChickenCombatLoop(CombatDispatcher dispatcher,
                             Client client,
                             @Nullable ClientThread clientThread,
                             @Nullable EventBus eventBus,
                             NpcSelector selector,
                             TargetVisibility visibility,
                             Consumer<String> statusSink)
    {
        this.dispatcher = dispatcher;
        this.client = client;
        this.clientThread = clientThread;
        this.eventBus = eventBus;
        this.selector = selector;
        this.visibility = visibility == null ? TargetVisibility.alwaysVisible() : visibility;
        this.statusSink = statusSink == null ? s -> {} : statusSink;
    }

    /** Spawn the worker. Idempotent — calling twice is a no-op while the
     *  previous run is still active. */
    public void start()
    {
        if (worker != null && worker.isAlive())
        {
            log.info("chicken loop already running — ignoring start");
            return;
        }
        stopRequested.set(false);
        stopAfterKill.set(false);
        target.set(null);
        killCount.set(0);
        serverRejectedAttack.set(false);
        clearClaimedNpcCooldown();
        if (eventBus != null && !registeredOnEventBus)
        {
            eventBus.register(this);
            registeredOnEventBus = true;
        }
        setState(State.SELECTING);
        setStatus("starting");
        Thread t = new Thread(this::runLoop, "chicken-combat-loop");
        t.setDaemon(true);
        worker = t;
        t.start();
    }

    /** Request the worker to wind down. Returns immediately; the worker exits
     *  on the next safe boundary (after the current click chain, never mid-
     *  click). */
    public void stop()
    {
        stopRequested.set(true);
        if (eventBus != null && registeredOnEventBus)
        {
            eventBus.unregister(this);
            registeredOnEventBus = false;
        }
        Thread t = worker;
        if (t != null) t.interrupt();
        setStatus("stopping");
    }

    /** Finish the current kill + loot, then stop — do not start the next fight.
     *  Unlike {@link #stop()}, this does not interrupt an in-progress loot
     *  cycle; the loop drains naturally and transitions to IDLE after the
     *  current chicken is dead and its drops are collected. */
    public void stopAfterCurrentKill()
    {
        stopAfterKill.set(true);
    }

    public State state() { return state.get(); }
    public String latestStatus() { return latestStatus.get(); }
    public int killCount() { return killCount.get(); }
    @Nullable public CombatTarget currentTarget() { return target.get(); }

    // ----- main loop -----

    /** Step the state machine. Package-private for tests, which can drive a
     *  single iteration without spawning a thread by calling
     *  {@link #stepForTesting()}. The production path uses {@link #runLoop()}. */
    private void runLoop()
    {
        log.info("chicken combat loop start");
        try
        {
            int selectingTickBudget = SELECT_TIMEOUT_TICKS;
            while (!stopRequested.get())
            {
                State s = state.get();
                switch (s)
                {
                    case SELECTING:
                    {
                        boolean picked = doSelect();
                        if (picked) selectingTickBudget = SELECT_TIMEOUT_TICKS;
                        else if (--selectingTickBudget <= 0) abort("no chickens nearby");
                        else sleepQuiet(POLL_INTERVAL_MS);
                        break;
                    }
                    case ENGAGING:
                        doEngage();
                        break;
                    case IN_COMBAT:
                        doInCombat();
                        break;
                    case KILLED:
                        doKilled();
                        break;
                    case LOOTING:
                        doLoot();
                        break;
                    case ABORTED:
                    case IDLE:
                        return;
                    default:
                        return;
                }
            }
        }
        catch (Throwable th)
        {
            log.warn("chicken loop crashed", th);
            setStatus("crashed: " + th.getClass().getSimpleName());
        }
        finally
        {
            if (eventBus != null && registeredOnEventBus)
            {
                eventBus.unregister(this);
                registeredOnEventBus = false;
            }
            // On stop, release the lock and reset to IDLE.
            target.set(null);
            setState(State.IDLE);
            setStatus("stopped after " + killCount.get() + " kill(s)");
            log.info("chicken combat loop end (kills={})", killCount.get());
        }
    }

    /** Visible for testing: pick a target and transition to ENGAGING. */
    boolean doSelect()
    {
        // If we were asked to stop after the previous kill, honour that now
        // by telling the outer loop to exit before picking the next chicken.
        if (stopAfterKill.get())
        {
            stopRequested.set(true);
            return false;
        }
        // Locking invariant: don't enter SELECTING while a target is held.
        if (target.get() != null)
        {
            log.warn("doSelect called with active lock — coercing to IN_COMBAT");
            setState(State.IN_COMBAT);
            return true;
        }
        Snapshot snap = takeSnapshot();
        if (snap == null) return false;
        // Not-ready guard: if the player isn't loaded (title screen, loading,
        // or world hop in progress), abort fast instead of ticking down 60
        // polls (~36s). The grace window absorbs a brief frame where the
        // engine reports null mid-load.
        if (snap.self == null || snap.playerPos == null)
        {
            if (++notReadyTicks >= NOT_READY_GRACE_TICKS)
            {
                abort("player not loaded (self=" + (snap.self == null ? "null" : "ok")
                    + ", pos=" + (snap.playerPos == null ? "null" : "ok")
                    + ") after " + notReadyTicks + " ticks");
            }
            return false;
        }
        notReadyTicks = 0;
        CombatTarget adopted = onClient(() -> detectActiveChickenCombat(snap));
        if (adopted != null)
        {
            justEnteredSelecting = false;
            target.set(adopted);
            setStatus("already in combat with " + adopted.npcName() + " #" + adopted.npcIndex());
            setState(State.IN_COMBAT);
            return true;
        }
        // pick + diagnostic both touch NPC accessors (getWorldLocation,
        // getHealthRatio, getInteracting, getComposition) — all of which
        // assert isClientThread() under -ea. Run them on the client thread
        // so we don't crash the worker the moment we're near a real chicken.
        NPC pick = onClient(() -> {
            WorldView wv = client.getTopLevelWorldView();
            NPC p = selector.pick(snap.npcs, snap.self, snap.playerPos,
                activeClaimedNpcCooldownIndex(), wv, visibility);
            if (p == null && justEnteredSelecting)
            {
                justEnteredSelecting = false;
                logSelectionDiagnostic(snap);
            }
            return p;
        });
        if (pick == null) return false;
        justEnteredSelecting = false;
        // Lock and transition. The lock-acquired-tick is captured from the
        // engine for diagnostics; not consulted by the state machine itself.
        int tick = onClientOrDefault(client::getTickCount, 0);
        String pickName = onClientOrDefault(() -> {
            var c = pick.getComposition();
            return c == null ? pick.getName() : c.getName();
        }, "Chicken");
        CombatTarget ct = new CombatTarget(pick.getIndex(), pickName, tick);
        target.set(ct);
        setStatus("engaging " + pickName + " #" + ct.npcIndex());
        setState(State.ENGAGING);
        return true;
    }

    /** Issue the attack click for the locked target and wait for engagement.
     *  This is the ONLY click the loop dispatches, except the re-attack path
     *  in {@link #doInCombat()}. Visible for testing. */
    void doEngage()
    {
        CombatTarget ct = target.get();
        if (ct == null)
        {
            // Defensive; shouldn't happen because doSelect sets the target.
            setState(State.SELECTING);
            return;
        }
        EngagePreflight preflight = onClientOrDefault(() -> preflightEngage(ct),
            EngagePreflight.TARGET_GONE);
        if (preflight == EngagePreflight.ALREADY_OURS)
        {
            setStatus("in combat with " + ct.npcName() + " #" + ct.npcIndex());
            setState(State.IN_COMBAT);
            return;
        }
        if (preflight == EngagePreflight.TAKEN_BY_OTHER)
        {
            log.info("chicken #{} claimed by another player before click — releasing lock",
                ct.npcIndex());
            rememberClaimedNpc(ct.npcIndex());
            target.set(null);
            setStatus("target already in combat — re-selecting");
            setState(State.SELECTING);
            return;
        }
        if (preflight == EngagePreflight.TARGET_GONE)
        {
            log.info("locked chicken #{} gone before click — releasing lock", ct.npcIndex());
            target.set(null);
            setStatus("target vanished");
            setState(State.SELECTING);
            return;
        }
        serverRejectedAttack.set(false);
        ActionRequest req = ActionRequest.builder()
            .kind(ActionRequest.Kind.CLICK_NPC)
            .channel(ActionRequest.Channel.MOUSE)
            .npcIndex(ct.npcIndex())
            .build();
        dispatcher.dispatch(req);
        // Wait for the dispatcher's click chain to finish.
        long until = System.currentTimeMillis() + ENGAGE_TIMEOUT_MS;
        while (dispatcher.isBusy())
        {
            if (stopRequested.get()) return;
            if (System.currentTimeMillis() > until) break;
            sleepQuiet(60);
        }
        if (serverRejectedAttack.getAndSet(false))
        {
            log.info("server rejected attack on chicken #{} — releasing lock", ct.npcIndex());
            rememberClaimedNpc(ct.npcIndex());
            target.set(null);
            setStatus("server rejected: already in combat");
            setState(State.SELECTING);
            return;
        }
        String err = dispatcher.lastErrorMessage();
        if (err != null)
        {
            // Click failed to reach the NPC (off-screen, dead between
            // selection and click, etc.). Drop the lock and re-select.
            log.info("engage dispatch failed: {}", err);
            if (isAlreadyClaimedError(err)) rememberClaimedNpc(ct.npcIndex());
            target.set(null);
            setStatus("engage failed: " + err);
            setState(State.SELECTING);
            return;
        }
        // Wait for engagement: either the player is interacting with the
        // chicken or the chicken is interacting with us. The engine fills one
        // direction first depending on whether the player or the NPC moved
        // into adjacency first; treat either as "engagement live" so we don't
        // get stuck in ENGAGING when combat is already running.
        long deadline = System.currentTimeMillis() + ENGAGE_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline)
        {
            if (stopRequested.get()) return;
            if (serverRejectedAttack.getAndSet(false))
            {
                log.info("server rejected attack on chicken #{} during engage wait — releasing lock",
                    ct.npcIndex());
                rememberClaimedNpc(ct.npcIndex());
                target.set(null);
                setStatus("server rejected: already in combat");
                setState(State.SELECTING);
                return;
            }
            Boolean engaged = onClient(() -> {
                Player self = client.getLocalPlayer();
                if (self == null) return false;
                NPC npc = findNpcByIndex(ct.npcIndex());
                if (npc == null) return null; // null = vanished; null literal is "?"
                if (self.getInteracting() == npc) return true;
                if (npc.getInteracting() == self) return true;
                return false;
            });
            if (engaged == null)
            {
                // NPC vanished between click and engage — bail back to select.
                log.info("locked chicken #{} vanished pre-engagement", ct.npcIndex());
                target.set(null);
                setStatus("target vanished");
                setState(State.SELECTING);
                return;
            }
            if (Boolean.TRUE.equals(engaged))
            {
                setStatus("in combat with " + ct.npcName() + " #" + ct.npcIndex());
                setState(State.IN_COMBAT);
                return;
            }
            sleepQuiet(150);
        }
        // Timeout — try once more from SELECTING (drops the current lock). If
        // the chicken is still around, the next pick may be the same one.
        log.info("engagement timeout for chicken #{} — releasing lock", ct.npcIndex());
        target.set(null);
        setStatus("engagement timeout — re-selecting");
        setState(State.SELECTING);
    }

    /** Poll the locked chicken once per tick. NO clicks are dispatched here
     *  unless engagement broke for &gt;2 ticks (re-attack the same chicken).
     *  This is the part of the spec that is most important: don't ever click
     *  another chicken while in combat. Visible for testing. */
    void doInCombat()
    {
        CombatTarget ct = target.get();
        if (ct == null)
        {
            setState(State.SELECTING);
            return;
        }
        CombatStateTracker tracker = new CombatStateTracker(ct.npcIndex());
        while (!stopRequested.get())
        {
            sleepQuiet(POLL_INTERVAL_MS);
            if (combatTick(ct, tracker)) return;
        }
    }

    /** One observation tick of the IN_COMBAT loop. Returns true when the
     *  outer loop should exit (state has transitioned). Package-private so
     *  tests can drive the combat poller deterministically without sleeping
     *  for real ticks. */
    boolean combatTick(CombatTarget ct, CombatStateTracker tracker)
    {
        Snapshot snap = takeSnapshot();
        if (snap == null) return false;
        // tracker.observe reads location/health/interacting on the locked NPC
        // and on snap.self — same client-thread requirement as the selector.
        // We also capture the locked NPC's current world tile in the same
        // closure so the eventual death position is available for LOOTING
        // (drops spawn on the chicken's last tile; reading getWorldLocation
        // after despawn returns null).
        WorldPoint lockedTile = onClient(() -> {
            NPC locked = findNpcInList(snap.npcs, ct.npcIndex());
            tracker.observe(locked, snap.self);
            return locked == null ? null : locked.getWorldLocation();
        });
        if (tracker.isDead())
        {
            if (lockedTile != null) lastKillTile.set(lockedTile);
            setStatus("killed " + ct.npcName() + " #" + ct.npcIndex());
            setState(State.KILLED);
            return true;
        }
        if (tracker.isStolen(STOLEN_THRESHOLD_TICKS))
        {
            // Another player engaged this chicken before our click landed.
            // Drop the lock without crediting a kill or attempting to loot —
            // the corpse will spawn drops owned by the other player. Going
            // back to SELECTING lets the selector pick a different chicken
            // immediately, which is what a human would do.
            log.info("kill stolen on chicken #{} — releasing lock, re-selecting",
                ct.npcIndex());
            rememberClaimedNpc(ct.npcIndex());
            target.set(null);
            setStatus("kill stolen — re-selecting");
            setState(State.SELECTING);
            return true;
        }
        if (tracker.isEngagementBroken(BROKEN_THRESHOLD_TICKS))
        {
            // Re-attack the SAME chicken (same lock) — spec rule. Don't
            // pick a new target.
            log.info("engagement broken (>{} ticks) — re-attacking chicken #{}",
                BROKEN_THRESHOLD_TICKS, ct.npcIndex());
            setStatus("re-attacking " + ct.npcName() + " #" + ct.npcIndex());
            setState(State.ENGAGING);
            return true;
        }
        return false;
    }

    /** Tally and short pause before LOOTING (or SELECTING when no kill
     *  tile was captured). Visible for testing. */
    void doKilled()
    {
        // Vanished kills (someone else killed our target) don't count — the
        // tracker flagged dead via vanish but no-one attributes it to us.
        // Distinguishing is fiddly without per-tick HP history; we count any
        // kill where we ever engaged.
        int n = killCount.incrementAndGet();
        log.info("kill #{} confirmed for chicken #{}", n,
            target.get() == null ? -1 : target.get().npcIndex());
        target.set(null);
        setStatus("kill #" + n);
        // First wait — let drops actually spawn server-side (1-2 ticks)
        // and the visual loot appear under the corpse before we go for it.
        sleepQuiet(400 + rng.nextInt(401));     // 400..800ms
        WorldPoint tile = lastKillTile.get();
        if (tile == null)
        {
            log.info("no death tile recorded — skipping loot");
            setState(State.SELECTING);
            return;
        }
        lootDeadlineMs = System.currentTimeMillis() + LOOT_TIMEOUT_MS;
        setStatus("looting at " + tile);
        setState(State.LOOTING);
    }

    /** Pick up our chicken drops (feathers + raw chicken) from the death
     *  tile. Items not owned by us, items not in the loot allow-list, and
     *  bones are skipped. Picks one item per call (driven by the run-loop
     *  switch); state stays in LOOTING until either no more matching
     *  items remain, the inventory is full, or the {@link #LOOT_TIMEOUT_MS}
     *  deadline passes. Visible for testing. */
    void doLoot()
    {
        WorldPoint tile = lastKillTile.get();
        if (tile == null)
        {
            setState(State.SELECTING);
            return;
        }
        if (System.currentTimeMillis() > lootDeadlineMs)
        {
            log.info("loot timeout at {} — done with this kill", tile);
            finishLooting();
            return;
        }
        // The dispatcher silently drops requests issued while it is still
        // running a previous click chain — block until it's free before
        // doing anything else. Normally the prior iteration already waited,
        // but if we exited early under deadline pressure the chain may
        // still be in flight.
        while (dispatcher.isBusy())
        {
            if (stopRequested.get()) return;
            if (System.currentTimeMillis() > lootDeadlineMs)
            {
                log.info("loot timeout (waiting for prior click) at {}", tile);
                finishLooting();
                return;
            }
            sleepQuiet(60);
        }
        // Inventory full check — pickup would silently fail and we'd burn
        // the timeout for nothing. Bail to SELECTING; the user can drop /
        // bank and the next kill will try again.
        Integer invCount = onClient(() -> {
            ItemContainer inv = client.getItemContainer(InventoryID.INV);
            return inv == null ? 0 : inv.count();
        });
        if (invCount != null && invCount >= 28)
        {
            log.info("inventory full ({}/28) — skipping loot", invCount);
            finishLooting();
            return;
        }
        int beforeCount = invCount == null ? 0 : invCount;
        // Pick the oldest matching ground item on the tile (longest visible
        // = closest to despawning, so loot it first).
        Integer nextItemId = onClient(() -> findOurLootItemId(tile));
        if (nextItemId == null)
        {
            // No matching loot YET — items may still be in flight. Wait one
            // poll and re-check; the deadline guards against waiting forever.
            sleepQuiet(POLL_INTERVAL_MS);
            return;
        }
        ActionRequest req = ActionRequest.builder()
            .kind(ActionRequest.Kind.CLICK_GROUND_ITEM)
            .channel(ActionRequest.Channel.MOUSE)
            .tile(tile)
            .itemId(nextItemId)
            .verb("Take")
            .build();
        log.info("loot dispatch: take id={} at {}", nextItemId, tile);
        dispatcher.dispatch(req);
        // Wait for the click chain to actually complete. Bounded only by
        // the overall loot deadline; the previous 1.5s cap routinely fired
        // mid-chain (humanized cursor + menu pick takes 2-3s), causing the
        // next iteration to re-dispatch while the dispatcher was still
        // busy — silently dropped, leaving us with one item picked up.
        while (dispatcher.isBusy())
        {
            if (stopRequested.get()) return;
            if (System.currentTimeMillis() > lootDeadlineMs) break;
            sleepQuiet(60);
        }
        String err = dispatcher.lastErrorMessage();
        if (err != null)
        {
            log.info("loot dispatch failed: {} — moving on", err);
            sleepQuiet(POLL_INTERVAL_MS);
            return;
        }
        // Wait for the inventory delta (bag count went up). Independent of
        // the busy wait — once the chain reports success, the bag tick is
        // a separate, short window.
        long invUntil = Math.min(
            System.currentTimeMillis() + LOOT_PICKUP_WAIT_MS,
            lootDeadlineMs);
        while (System.currentTimeMillis() < invUntil)
        {
            if (stopRequested.get()) return;
            Integer after = onClient(() -> {
                ItemContainer inv = client.getItemContainer(InventoryID.INV);
                return inv == null ? 0 : inv.count();
            });
            if (after != null && after > beforeCount) break;
            sleepQuiet(100);
        }
        // Inter-pickup human pause — real players don't fire pickups in
        // immediate succession. 200-400ms feels natural and is short enough
        // to drain a 3-item chicken stack inside the loot deadline.
        sleepQuiet(200 + rng.nextInt(201));
    }

    /** Closing wait + transition out of LOOTING. Mirrors the spec's
     *  "kill → loot (wait) → wait again → next chicken" cadence: this is
     *  the second wait. */
    private void finishLooting()
    {
        lastKillTile.set(null);
        sleepQuiet(300 + rng.nextInt(401));     // 300..700ms before re-select
        setState(State.SELECTING);
    }

    private enum EngagePreflight
    {
        READY,
        ALREADY_OURS,
        TAKEN_BY_OTHER,
        TARGET_GONE
    }

    private EngagePreflight preflightEngage(CombatTarget ct)
    {
        Player self = client.getLocalPlayer();
        if (self == null) return EngagePreflight.TARGET_GONE;
        NPC npc = findNpcByIndex(ct.npcIndex());
        if (npc == null) return EngagePreflight.TARGET_GONE;
        if (npc.getHealthRatio() == 0) return EngagePreflight.TARGET_GONE;
        Actor selfInteracting = self.getInteracting();
        if (selfInteracting == npc) return EngagePreflight.ALREADY_OURS;
        Actor npcInteracting = npc.getInteracting();
        if (npcInteracting == self) return EngagePreflight.ALREADY_OURS;
        if (npcInteracting instanceof Player) return EngagePreflight.TAKEN_BY_OTHER;
        return EngagePreflight.READY;
    }

    private void rememberClaimedNpc(int npcIndex)
    {
        claimedNpcCooldownIndex.set(npcIndex);
        claimedNpcCooldownUntilMs = System.currentTimeMillis() + CLAIMED_NPC_COOLDOWN_MS;
    }

    private int activeClaimedNpcCooldownIndex()
    {
        int idx = claimedNpcCooldownIndex.get();
        if (idx < 0) return -1;
        if (System.currentTimeMillis() <= claimedNpcCooldownUntilMs) return idx;
        clearClaimedNpcCooldown();
        return -1;
    }

    private void clearClaimedNpcCooldown()
    {
        claimedNpcCooldownIndex.set(-1);
        claimedNpcCooldownUntilMs = 0;
    }

    private static boolean isAlreadyClaimedError(String err)
    {
        return err != null && err.contains("already in combat with another player");
    }

    /** Client-thread: read TileItems on {@code tile}, return the id of the
     *  oldest one that is owned by us AND in {@link #LOOT_ITEM_IDS}.
     *  Returns null if no matching item is currently on the tile. */
    @Nullable
    private Integer findOurLootItemId(WorldPoint tile)
    {
        try
        {
            LocalPoint lp = LocalPoint.fromWorld(client, tile);
            if (lp == null) return null;
            Tile sceneTile = client.getScene().getTiles()
                [tile.getPlane()][lp.getSceneX()][lp.getSceneY()];
            if (sceneTile == null) return null;
            java.util.List<TileItem> items = sceneTile.getGroundItems();
            if (items == null || items.isEmpty()) return null;
            TileItem chosen = null;
            int chosenSpawn = Integer.MAX_VALUE;
            for (TileItem item : items)
            {
                if (item == null) continue;
                if (item.getOwnership() != TileItem.OWNERSHIP_SELF) continue;
                if (!LOOT_ITEM_IDS.contains(item.getId())) continue;
                if (item.getVisibleTime() < chosenSpawn)
                {
                    chosen = item;
                    chosenSpawn = item.getVisibleTime();
                }
            }
            return chosen == null ? null : chosen.getId();
        }
        catch (Throwable th)
        {
            log.debug("findOurLootItemId failed at {}", tile, th);
            return null;
        }
    }

    @Subscribe
    public void onChatMessage(ChatMessage event)
    {
        if (event == null) return;
        ChatMessageType type = event.getType();
        if (type != ChatMessageType.GAMEMESSAGE && type != ChatMessageType.SPAM) return;
        String msg = Text.standardize(event.getMessage());
        if (msg.contains(ALREADY_FIGHTING_MESSAGE))
        {
            log.info("server rejection chat seen: '{}'", msg);
            serverRejectedAttack.set(true);
        }
    }

    private void abort(String reason)
    {
        log.info("chicken loop abort: {}", reason);
        target.set(null);
        setStatus(reason);
        setState(State.ABORTED);
    }

    /** Walks {@code snap.npcs}, counts how many "Chicken" NPCs there are and
     *  why each one was rejected by {@link NpcSelector}. Logged once per
     *  SELECTING entry so the user gets a concrete reason ("closest 14 tiles
     *  away") instead of just "no chickens nearby" after 36s. */
    private void logSelectionDiagnostic(Snapshot snap)
    {
        int matching = 0, outOfArea = 0, outOfRange = 0, wrongPlane = 0, dying = 0, engagedByOther = 0;
        int closestDist = Integer.MAX_VALUE;
        int playerPlane = snap.playerPos.getPlane();
        for (NPC npc : snap.npcs)
        {
            if (npc == null) continue;
            NPCComposition c = npc.getComposition();
            String name = c == null ? npc.getName() : c.getName();
            if (name == null) continue;
            if (!CHICKEN_NAME.equalsIgnoreCase(name.replaceAll("<[^>]+>", "").trim())) continue;
            matching++;
            WorldPoint loc = npc.getWorldLocation();
            if (loc == null) continue;
            int dist = loc.distanceTo(snap.playerPos);
            if (dist < closestDist) closestDist = dist;
            WorldArea conf = selector.confineTo();
            if (conf != null
                && (loc.getPlane() != conf.getPlane()
                 || loc.getX() < conf.getX()
                 || loc.getX() >= conf.getX() + conf.getWidth()
                 || loc.getY() < conf.getY()
                 || loc.getY() >= conf.getY() + conf.getHeight()))
            {
                outOfArea++;
                continue;
            }
            if (loc.getPlane() != playerPlane) { wrongPlane++; continue; }
            if (npc.getHealthRatio() == 0) { dying++; continue; }
            if (dist > NpcSelector.DEFAULT_RANGE) { outOfRange++; continue; }
            Actor interacting = npc.getInteracting();
            if (interacting instanceof Player p && p != snap.self) engagedByOther++;
        }
        log.info("pick miss at {} (plane {}): {} chicken(s) total, closest dist={}, rejected outOfArea={} outOfRange={} wrongPlane={} dying={} engagedByOther={} (range={})",
            snap.playerPos, playerPlane, matching,
            closestDist == Integer.MAX_VALUE ? -1 : closestDist,
            outOfArea, outOfRange, wrongPlane, dying, engagedByOther,
            NpcSelector.DEFAULT_RANGE);
    }

    /** If the player is already fighting a chicken when SELECTING starts
     *  (e.g. a stray loot misclick or auto-retaliate engaged one), adopt that
     *  fight instead of trying to click a second target. */
    @Nullable
    private CombatTarget detectActiveChickenCombat(Snapshot snap)
    {
        if (snap.self == null) return null;
        NPC adopted = null;
        Actor selfInteracting = snap.self.getInteracting();
        if (selfInteracting instanceof NPC npc && isAdoptableChicken(npc, snap.playerPos))
        {
            adopted = npc;
        }
        if (adopted == null)
        {
            for (NPC npc : snap.npcs)
            {
                if (npc == null || !isAdoptableChicken(npc, snap.playerPos)) continue;
                if (npc.getInteracting() == snap.self)
                {
                    adopted = npc;
                    break;
                }
            }
        }
        if (adopted == null) return null;
        NPCComposition c = adopted.getComposition();
        String name = c == null ? adopted.getName() : c.getName();
        int tick = client.getTickCount();
        return new CombatTarget(adopted.getIndex(),
            name == null || name.isBlank() ? CHICKEN_NAME : name,
            tick);
    }

    private boolean isAdoptableChicken(NPC npc, @Nullable WorldPoint playerPos)
    {
        if (npc == null) return false;
        NPCComposition c = npc.getComposition();
        String name = c == null ? npc.getName() : c.getName();
        if (name == null || !CHICKEN_NAME.equalsIgnoreCase(name.replaceAll("<[^>]+>", "").trim()))
        {
            return false;
        }
        if (npc.getHealthRatio() == 0) return false;
        WorldPoint loc = npc.getWorldLocation();
        if (loc == null) return false;
        return playerPos == null || loc.getPlane() == playerPos.getPlane();
    }

    // ----- helpers -----

    /** Shape of one tick's worth of read-only data the state machine cares
     *  about. Captured atomically on the client thread so it doesn't drift
     *  mid-decision. */
    private static final class Snapshot
    {
        final java.util.List<NPC> npcs;
        @Nullable final Player self;
        @Nullable final WorldPoint playerPos;
        Snapshot(java.util.List<NPC> n, @Nullable Player p, @Nullable WorldPoint pos)
        { this.npcs = n; this.self = p; this.playerPos = pos; }
    }

    @Nullable
    private Snapshot takeSnapshot()
    {
        return onClient(() -> {
            Player self = client.getLocalPlayer();
            WorldPoint pos = self == null ? null : self.getWorldLocation();
            java.util.List<NPC> list = new java.util.ArrayList<>();
            try
            {
                for (NPC n : client.getTopLevelWorldView().npcs())
                {
                    if (n != null) list.add(n);
                }
            }
            catch (Throwable th)
            {
                log.debug("npcs() read failed", th);
            }
            return new Snapshot(list, self, pos);
        });
    }

    @Nullable
    private NPC findNpcByIndex(int idx)
    {
        try
        {
            for (NPC n : client.getTopLevelWorldView().npcs())
            {
                if (n != null && n.getIndex() == idx) return n;
            }
        }
        catch (Throwable th)
        {
            log.debug("findNpcByIndex failed", th);
        }
        return null;
    }

    @Nullable
    private static NPC findNpcInList(Iterable<NPC> npcs, int idx)
    {
        for (NPC n : npcs)
        {
            if (n != null && n.getIndex() == idx) return n;
        }
        return null;
    }

    /** Run a value-returning task on the RuneLite client thread. Mirror of
     *  the dispatcher's pattern. Returns null on failure / timeout. */
    @Nullable
    private <T> T onClient(Supplier<T> task)
    {
        if (clientThread == null || (client != null && client.isClientThread()))
        {
            try { return task.get(); }
            catch (Throwable th)
            {
                log.debug("onClient (direct) threw", th);
                return null;
            }
        }
        CompletableFuture<T> fut = new CompletableFuture<>();
        clientThread.invoke(() -> {
            try { fut.complete(task.get()); }
            catch (Throwable th) { fut.completeExceptionally(th); }
        });
        try { return fut.get(2000, TimeUnit.MILLISECONDS); }
        catch (InterruptedException ie)
        {
            Thread.currentThread().interrupt();
            return null;
        }
        catch (TimeoutException te)
        {
            log.warn("onClient timed out");
            return null;
        }
        catch (Exception ex)
        {
            log.debug("onClient threw", ex);
            return null;
        }
    }

    private <T> T onClientOrDefault(Supplier<T> task, T fallback)
    {
        T v = onClient(task);
        return v == null ? fallback : v;
    }

    private void setState(State s)
    {
        State old = state.getAndSet(s);
        if (old != s)
        {
            log.info("state {} → {}", old, s);
            if (s == State.SELECTING) justEnteredSelecting = true;
        }
    }

    private void setStatus(String msg)
    {
        latestStatus.set(msg);
        try { statusSink.accept(msg); }
        catch (Throwable th) { log.debug("status sink threw", th); }
    }

    private void sleepQuiet(long ms)
    {
        try { SequenceSleep.sleep(client, ms); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    // ----- testing hooks -----

    /** Force-set state. Test-only; not for production callers. */
    void setStateForTesting(State s) { state.set(s); }

    /** Force-set the lock. Test-only. */
    void setTargetForTesting(@Nullable CombatTarget t) { target.set(t); }
}
