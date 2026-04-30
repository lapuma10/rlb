package net.runelite.client.plugins.recorder.scripts;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.recorder.combat.ChickenCombatLoop;
import net.runelite.client.plugins.recorder.combat.TrainingPlan;
import net.runelite.client.plugins.recorder.combat.TrainingSession;
import net.runelite.client.plugins.recorder.farm.BankInteraction;
import net.runelite.client.plugins.recorder.farm.InventoryUtil;
import net.runelite.client.plugins.recorder.trail.TrailPath;
import net.runelite.client.plugins.recorder.trail.TrailRegistry;
import net.runelite.client.plugins.recorder.trail.TrailWalker;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import net.runelite.client.sequence.dispatch.SequenceSleep;
import net.runelite.client.sequence.internal.ActionRequest;

/**
 * Chicken farm bot, V3 — uses the recorded {@link
 * net.runelite.client.plugins.recorder.trail.Trail trail framework} for
 * the WALKING phases. The user records two trails once
 * ({@code lumby-bank-to-pen} and {@code pen-to-lumby-bank}); V3 plans
 * over the resulting graph at each phase transition and feeds the path
 * into a {@link TrailWalker}.
 *
 * <p>BANKING and AT_PEN are reused from V2 unchanged: same
 * {@link BankInteraction} primitives, same {@link ChickenCombatLoop}.
 *
 * <p>If the registry is missing the required trails, V3 surfaces a
 * status message and aborts — the user has to record the trails before
 * V3 can run.
 */
@Slf4j
public final class ChickenFarmV3Script
{
    public static final String OUTBOUND_TRAIL_NAME = "lumby-bank-to-pen";
    public static final String RETURN_TRAIL_NAME   = "pen-to-lumby-bank";

    /** Bank stand area — used for "are we at the bank?" check and as the
     *  RETURN destination. Mirrors V2/V1's BANK_AREA. */
    public static final WorldArea BANK_AREA = new WorldArea(3206, 3215, 13, 5, 2);
    /** Pen kill area — passed to the combat loop. Mirrors V2/V1. */
    public static final WorldArea PEN_AREA  = new WorldArea(3225, 3290, 13, 12, 0);

    private static final long TICK_MS = 600;
    private static final long BANK_PACE_MS = 2000;

    public enum State { IDLE, BANKING, OUTBOUND, AT_PEN, RETURN, LOGGING_OFF, ABORTED }

    private final Client client;
    private final ClientThread clientThread;
    private final HumanizedInputDispatcher dispatcher;
    private final TrailRegistry registry;
    private final TrailWalker walker;
    private final BankInteraction bank;
    private final ChickenCombatLoop combat;
    private final TrainingSession trainingSession;
    private final LogoutHelper logoutHelper;
    private volatile TrainingPlan trainingPlan;

    private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);
    private final AtomicReference<String> status = new AtomicReference<>("idle");
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<Thread> worker = new AtomicReference<>();
    private long lastBankActionAtMs;
    private TrailPath currentPath;

    public ChickenFarmV3Script(Client client, ClientThread clientThread,
                               HumanizedInputDispatcher dispatcher,
                               TrailRegistry registry)
    {
        this(client, clientThread, dispatcher, registry, null);
    }

    /** Full ctor — passes the {@link net.runelite.client.eventbus.EventBus}
     *  through to {@link TrainingSession} so it can subscribe to
     *  {@code StatChanged} events for instant level-up detection. The
     *  3-arg ctor above retains the pre-EventBus signature for tests. */
    public ChickenFarmV3Script(Client client, ClientThread clientThread,
                               HumanizedInputDispatcher dispatcher,
                               TrailRegistry registry,
                               @javax.annotation.Nullable
                               net.runelite.client.eventbus.EventBus eventBus)
    {
        this.client = client;
        this.clientThread = clientThread;
        this.dispatcher = dispatcher;
        this.registry = registry;
        this.walker = new TrailWalker(client, clientThread, dispatcher);
        this.bank = new BankInteraction(client, clientThread, dispatcher);
        this.combat = new ChickenCombatLoop(dispatcher, client, clientThread, PEN_AREA);
        this.trainingSession = new TrainingSession(client, clientThread, dispatcher, eventBus);
        this.logoutHelper = new LogoutHelper(client, clientThread, dispatcher);
    }

    public State state() { return state.get(); }
    public String status() { return status.get(); }
    public int killCount() { return combat.killCount(); }

    /** Set the training plan before calling {@link #start()}. Pass
     *  {@code null} to run in normal (non-training) mode. */
    public void setTrainingPlan(TrainingPlan plan)
    {
        this.trainingPlan = plan;
    }

    public TrainingPlan trainingPlan()
    {
        return trainingPlan;
    }

    public void start()
    {
        if (!running.compareAndSet(false, true)) return;
        // All startup work runs on the worker thread, never on the caller.
        // The Start Training button handler runs on the Swing EDT, and the
        // resume path calls onClient() which awaits a CountDownLatch on the
        // Client thread. On macOS the Client thread can be inside a native
        // GPU swapBuffers that transitively waits on the EDT, deadlocking
        // both. Hand the work to the worker and return immediately.
        Thread t = new Thread(this::startupThenTick, "chicken-farm-v3");
        t.setDaemon(true);
        worker.set(t);
        t.start();
    }

    private void startupThenTick()
    {
        registry.load();
        if (registry.byName(OUTBOUND_TRAIL_NAME) == null
            || registry.byName(RETURN_TRAIL_NAME) == null)
        {
            status.set("missing trail — record \"" + OUTBOUND_TRAIL_NAME
                + "\" and \"" + RETURN_TRAIL_NAME + "\"");
            running.set(false);
            return;
        }
        if (trainingPlan != null)
        {
            trainingSession.start(trainingPlan);
        }
        State decided = decideResume();
        log.info("v3: resume → {} (status: {})", decided, status.get());
        setState(decided);
        if (decided == State.IDLE || decided == State.ABORTED)
        {
            running.set(false);
            return;
        }
        tickLoop();
    }

    public void stop()
    {
        running.set(false);
        setState(State.IDLE);
        status.set("stopped");
        Thread t = worker.getAndSet(null);
        if (t != null) t.interrupt();
        combat.stop();
        // Drop the EventBus subscription so a later restart does a clean
        // re-register. Otherwise StatChanged events would still fire on the
        // stale instance and could feed phantom level-ups across plans.
        trainingSession.stop();
    }

    private State decideResume()
    {
        WorldPoint here = onClient(() -> {
            Player p = client.getLocalPlayer();
            return p == null ? null : p.getWorldLocation();
        });
        if (here == null) { status.set("no player — abort"); return State.ABORTED; }
        boolean invFull = onClient(() -> InventoryUtil.isInventoryFull(client));
        boolean invEmpty = onClient(() ->
            InventoryUtil.freeSlotCount(client) >= InventoryUtil.INVENTORY_SIZE);

        // Trail-based landmark detection. We're "at the pen" if we're
        // within 6 tiles of the recorded pen-end tile, "at the bank" if
        // within 6 tiles of the recorded bank-end tile. The hardcoded
        // PEN_AREA / BANK_AREA WorldAreas are kept as a fallback for
        // when the registry hasn't loaded the trails yet.
        WorldPoint penTile = lastTileOf(registry.byName(OUTBOUND_TRAIL_NAME));
        WorldPoint bankTile = lastTileOf(registry.byName(RETURN_TRAIL_NAME));
        boolean atPen = (penTile != null && near(here, penTile, 6))
            || areaContains(PEN_AREA, here);
        boolean atBank = (bankTile != null && near(here, bankTile, 6))
            || areaContains(BANK_AREA, here);

        if (atPen)
        {
            status.set("starting at pen");
            return invFull ? State.RETURN : State.AT_PEN;
        }
        if (atBank)
        {
            status.set("starting at bank");
            return invEmpty ? State.OUTBOUND : State.BANKING;
        }
        status.set("starting mid-route");
        return invFull ? State.RETURN : State.OUTBOUND;
    }

    /** Last TILE event in a trail, or null if the trail is null/empty. */
    private static WorldPoint lastTileOf(net.runelite.client.plugins.recorder.trail.Trail trail)
    {
        if (trail == null) return null;
        for (int i = trail.events().size() - 1; i >= 0; i--)
        {
            net.runelite.client.plugins.recorder.trail.TrailEvent ev = trail.events().get(i);
            if (ev instanceof net.runelite.client.plugins.recorder.trail.TrailEvent.Tile t)
            {
                return t.tile();
            }
        }
        return null;
    }

    private static boolean near(WorldPoint a, WorldPoint b, int chebyshev)
    {
        return a.getPlane() == b.getPlane()
            && Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getY() - b.getY())) <= chebyshev;
    }

    private void tickLoop()
    {
        try
        {
            while (running.get() && !Thread.currentThread().isInterrupted())
            {
                try
                {
                    switch (state.get())
                    {
                        case BANKING:     tickBanking();      break;
                        case OUTBOUND:    tickWalk(true);     break;
                        case AT_PEN:      tickAtPen();        break;
                        case RETURN:      tickWalk(false);    break;
                        case LOGGING_OFF: tickLoggingOff();   break;
                        case ABORTED:
                        case IDLE:
                        default:          running.set(false); break;
                    }
                }
                catch (RuntimeException re)
                {
                    // Don't let a bad tick kill the whole loop — log and
                    // try the next tick. Otherwise the bot freezes mid-pen
                    // (no retaliate, no attack) and looks like a stalled
                    // bot. The user has to notice and restart manually.
                    log.warn("v3: tick threw — continuing loop (state={})",
                        state.get(), re);
                    status.set("tick error: " + re.getClass().getSimpleName()
                        + " — see log");
                }
                SequenceSleep.sleep(client, TICK_MS);
            }
        }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        finally { running.set(false); }
    }

    private void tickWalk(boolean outbound) throws InterruptedException
    {
        // Mid-walk inventory check: if we go full mid-OUTBOUND (rare —
        // someone dropped loot we picked up, an event mob, etc.), pivot
        // to RETURN immediately rather than continuing all the way to the
        // pen and finding out we have no slots when combat tries to start.
        // Same shape as V1's tickOutbound check.
        if (outbound && Boolean.TRUE.equals(onClient(() -> InventoryUtil.isInventoryFull(client))))
        {
            status.set("inventory full mid-outbound — switching to RETURN");
            walker.reset();
            currentPath = null;
            setState(State.RETURN);
            return;
        }
        if (currentPath == null) currentPath = planForCurrentPhase(outbound);
        if (currentPath == null) return;
        TrailWalker.Status st = walker.tick(currentPath);
        status.set("walk: " + (outbound ? "outbound" : "return") + " (" + st + ")");
        switch (st)
        {
            case ARRIVED:
                walker.reset();
                currentPath = null;
                setState(outbound ? State.AT_PEN : State.BANKING);
                break;
            case STUCK:
            case ERROR:
                log.warn("v3: walker {} on {} — aborting", st, outbound ? "OUTBOUND" : "RETURN");
                walker.reset();
                currentPath = null;
                setState(State.ABORTED);
                break;
            case IN_PROGRESS:
            default:
                break;
        }
    }

    private TrailPath planForCurrentPhase(boolean outbound)
    {
        WorldPoint here = onClient(() -> {
            Player p = client.getLocalPlayer();
            return p == null ? null : p.getWorldLocation();
        });
        if (here == null) { status.set("no player — abort"); setState(State.ABORTED); return null; }
        // Replay the matching trail directly — no planner / A* / junction
        // edges. The graph-based approach kept finding short-cut paths
        // through "post-staircase" tiles (tiles the engine routes the
        // player onto AS PART OF the climb action) and emitting them as
        // ordinary WALK legs, so the bot tried to walk-click stair tiles
        // instead of clicking the staircase object. V1/V2 worked by
        // walking to a stairs landing area and triggering the action;
        // TrailPath.fromTrail produces the same shape from the recording.
        String trailName = outbound ? OUTBOUND_TRAIL_NAME : RETURN_TRAIL_NAME;
        net.runelite.client.plugins.recorder.trail.Trail trail = registry.byName(trailName);
        if (trail == null || trail.events().isEmpty())
        {
            status.set("trail \"" + trailName + "\" missing or empty — re-record");
            setState(State.ABORTED);
            return null;
        }
        TrailPath full = TrailPath.fromTrail(trail);
        int entry = full.findEntryLeg(here);
        TrailPath path = full.subPath(entry);
        if (path.isEmpty())
        {
            status.set("trail \"" + trailName + "\" has no usable legs from " + here);
            setState(State.ABORTED);
            return null;
        }
        log.info("v3: replay \"{}\" {} legs (entry leg {} of {}) from {}",
            trailName, path.size(), entry, full.size(), here);
        return path;
    }

    private void tickBanking() throws InterruptedException
    {
        long now = System.currentTimeMillis();
        long since = lastBankActionAtMs == 0
            ? Long.MAX_VALUE : now - lastBankActionAtMs;
        if (since < BANK_PACE_MS)
        {
            status.set("bank — pacing (" + since + "ms)");
            return;
        }
        // Bank PIN keypad up = STOP. Re-clicking the booth during PIN
        // entry can lock the player out for 5 minutes. Surface the state
        // and abort so the user can enter the PIN themselves and resume.
        Boolean pin = onClient(bank::isBankPinUp);
        if (Boolean.TRUE.equals(pin))
        {
            status.set("bank PIN keypad up — aborting (enter PIN, then restart V3)");
            log.warn("v3: bank PIN up — aborting before lockout");
            setState(State.ABORTED);
            return;
        }
        boolean open = onClient(bank::isBankOpen);
        boolean empty = onClient(() ->
            InventoryUtil.freeSlotCount(client) >= InventoryUtil.INVENTORY_SIZE);
        if (!open && !empty)
        {
            status.set("clicking bank booth");
            if (bank.tryClickBankBoothRandom()) lastBankActionAtMs = now;
            return;
        }
        if (open && !empty)
        {
            status.set("depositing inventory");
            if (clickDepositInventoryThreadSafe()) lastBankActionAtMs = now;
            return;
        }
        if (open && empty)
        {
            status.set("closing bank");
            if (bank.tryCloseBank()) lastBankActionAtMs = now;
            return;
        }
        // !open && empty
        if (trainingPlan != null && trainingSession.isComplete())
        {
            status.set("training done — logging off");
            setState(State.LOGGING_OFF);
        }
        else
        {
            status.set("bank closed — heading to pen");
            setState(State.OUTBOUND);
        }
    }

    private void tickAtPen() throws InterruptedException
    {
        if (trainingPlan != null)
        {
            // TrainingSession.tick() reads varbits and varplayers (sidebar
            // tab id, retaliate state, current style index). All of those
            // call client.getVarbitValue / getVarpValue under -ea, which
            // assert isClientThread(). We're on the chicken-farm-v3 worker
            // thread here — invoke onto the client thread and wait.
            onClient(() -> { trainingSession.tick(); return null; });
            if (trainingSession.isComplete())
            {
                ChickenCombatLoop.State cs = combat.state();
                boolean midKill = cs == ChickenCombatLoop.State.ENGAGING
                    || cs == ChickenCombatLoop.State.IN_COMBAT
                    || cs == ChickenCombatLoop.State.KILLED
                    || cs == ChickenCombatLoop.State.LOOTING;
                if (midKill)
                {
                    // Let the current kill and loot finish, then the loop
                    // will idle before picking the next chicken.
                    combat.stopAfterCurrentKill();
                    status.set("training done — finishing kill");
                }
                else
                {
                    // Between kills or never started — go to bank now.
                    status.set("training complete — returning to bank");
                    handoffFromCombatToWalk();
                    setState(State.RETURN);
                }
                return;
            }
            status.set("training: " + trainingSession.status());
        }
        if (onClient(() -> InventoryUtil.isInventoryFull(client)))
        {
            status.set("inventory full — RETURN");
            handoffFromCombatToWalk();
            setState(State.RETURN);
            return;
        }
        if (combat.state() == ChickenCombatLoop.State.IDLE)
        {
            status.set("starting combat");
            combat.start();
        }
        else
        {
            status.set("combat: " + combat.latestStatus()
                + " (kills=" + combat.killCount() + ")");
        }
    }

    /** Stop the combat loop and wait for both the worker thread to exit
     *  AND any in-flight click chain to settle before the walking phase
     *  starts dispatching. The combat loop and walker share one
     *  HumanizedInputDispatcher; if we hand off mid-click the dispatcher's
     *  busy guard silently drops the walker's first WALK request and the
     *  bot stalls on a stale "leg-changed" log line until the 15s STUCK
     *  timer trips. Bounded waits so a wedged combat thread can't hang
     *  the whole script. */
    private void handoffFromCombatToWalk() throws InterruptedException
    {
        combat.stop();
        // Combat poll cadence is 600ms; 3000ms gives ~5 polls of grace.
        long until = System.currentTimeMillis() + 3000L;
        while (combat.state() != ChickenCombatLoop.State.IDLE
            && System.currentTimeMillis() < until)
        {
            SequenceSleep.sleep(client, 60);
        }
        try { dispatcher.awaitIdle(2000L); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw ie; }
    }

    private void tickLoggingOff() throws InterruptedException
    {
        // Only click logout when player is idle and out of combat.
        Boolean idleAndOutOfCombat = onClient(() ->
        {
            Player p = client.getLocalPlayer();
            if (p == null) return false;
            if (p.getInteracting() != null) return false;
            return p.getPoseAnimation() == p.getIdlePoseAnimation();
        });
        if (!Boolean.TRUE.equals(idleAndOutOfCombat))
        {
            status.set("logout: waiting for idle");
            return;
        }
        boolean dispatched = logoutHelper.tryLogout();
        status.set(dispatched ? "logout: clicked" : "logout: panel not open — retrying");
        // If player is gone (null) we consider ourselves logged off — stop.
        WorldPoint here = onClient(() ->
        {
            Player p = client.getLocalPlayer();
            return p == null ? null : p.getWorldLocation();
        });
        if (here == null)
        {
            status.set("logged off — done");
            running.set(false);
            setState(State.IDLE);
        }
    }

    private boolean clickDepositInventoryThreadSafe() throws InterruptedException
    {
        Boolean visible = onClient(() -> {
            Widget w = client.getWidget(InterfaceID.Bankmain.DEPOSITINV);
            return w != null && !w.isHidden();
        });
        if (!Boolean.TRUE.equals(visible)) return false;
        ActionRequest req = ActionRequest.builder()
            .kind(ActionRequest.Kind.CLICK_WIDGET)
            .channel(ActionRequest.Channel.MOUSE)
            .widgetId(InterfaceID.Bankmain.DEPOSITINV)
            .build();
        dispatcher.dispatch(req);
        return true;
    }

    private void setState(State s) { state.set(s); lastBankActionAtMs = 0L; }

    private static boolean areaContains(WorldArea a, WorldPoint p)
    {
        return p.getPlane() == a.getPlane()
            && p.getX() >= a.getX() && p.getX() < a.getX() + a.getWidth()
            && p.getY() >= a.getY() && p.getY() < a.getY() + a.getHeight();
    }

    private <T> T onClient(Supplier<T> s)
    {
        AtomicReference<T> ref = new AtomicReference<>();
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        clientThread.invokeLater(() -> {
            try { ref.set(s.get()); }
            catch (Throwable th) { log.warn("v3: onClient threw", th); }
            finally { latch.countDown(); }
        });
        try { latch.await(); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); return null; }
        return ref.get();
    }
}
