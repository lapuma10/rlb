package net.runelite.client.plugins.recorder.scripts;

import java.util.Optional;
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
import net.runelite.client.plugins.recorder.trail.TrailGraph;
import net.runelite.client.plugins.recorder.trail.TrailPath;
import net.runelite.client.plugins.recorder.trail.TrailPlanner;
import net.runelite.client.plugins.recorder.trail.TrailRegistry;
import net.runelite.client.plugins.recorder.trail.TrailWalker;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
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
        this.client = client;
        this.clientThread = clientThread;
        this.dispatcher = dispatcher;
        this.registry = registry;
        this.walker = new TrailWalker(client, clientThread, dispatcher);
        this.bank = new BankInteraction(client, clientThread, dispatcher);
        this.combat = new ChickenCombatLoop(dispatcher, client, clientThread, PEN_AREA);
        this.trainingSession = new TrainingSession(client, clientThread, dispatcher);
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
        Thread t = new Thread(this::tickLoop, "chicken-farm-v3");
        t.setDaemon(true);
        worker.set(t);
        t.start();
    }

    public void stop()
    {
        running.set(false);
        setState(State.IDLE);
        status.set("stopped");
        Thread t = worker.getAndSet(null);
        if (t != null) t.interrupt();
        combat.stop();
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

        if (areaContains(PEN_AREA, here))
        {
            status.set("starting at pen");
            return invFull ? State.RETURN : State.AT_PEN;
        }
        if (areaContains(BANK_AREA, here))
        {
            status.set("starting at bank");
            return invEmpty ? State.OUTBOUND : State.BANKING;
        }
        status.set("starting mid-route");
        return invFull ? State.RETURN : State.OUTBOUND;
    }

    private void tickLoop()
    {
        try
        {
            while (running.get() && !Thread.currentThread().isInterrupted())
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
                Thread.sleep(TICK_MS);
            }
        }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        finally { running.set(false); }
    }

    private void tickWalk(boolean outbound) throws InterruptedException
    {
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
        WorldPoint dest = outbound
            ? new WorldPoint(PEN_AREA.getX() + PEN_AREA.getWidth() / 2,
                             PEN_AREA.getY() + PEN_AREA.getHeight() / 2,
                             PEN_AREA.getPlane())
            : new WorldPoint(BANK_AREA.getX() + BANK_AREA.getWidth() / 2,
                             BANK_AREA.getY() + BANK_AREA.getHeight() / 2,
                             BANK_AREA.getPlane());
        TrailGraph graph = TrailGraph.build(registry.all());
        TrailPlanner planner = new TrailPlanner(graph);
        Optional<TrailPath> p = planner.plan(here, dest);
        if (!p.isPresent())
        {
            status.set("no trail covers " + (outbound ? "bank→pen" : "pen→bank"));
            setState(State.ABORTED);
            return null;
        }
        return p.get();
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
        boolean open = onClient(bank::isBankOpen);
        boolean empty = onClient(() ->
            InventoryUtil.freeSlotCount(client) >= InventoryUtil.INVENTORY_SIZE);
        if (!open && !empty)
        {
            status.set("clicking bank booth");
            if (bank.clickBankBoothRandom()) lastBankActionAtMs = now;
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
            if (bank.closeBank()) lastBankActionAtMs = now;
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

    private void tickAtPen()
    {
        if (trainingPlan != null)
        {
            trainingSession.tick();
            if (trainingSession.isComplete())
            {
                status.set("training complete — returning to bank");
                combat.stop();
                setState(State.RETURN);
                return;
            }
            status.set("training: " + trainingSession.status());
        }
        if (onClient(() -> InventoryUtil.isInventoryFull(client)))
        {
            status.set("inventory full — RETURN");
            combat.stop();
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
