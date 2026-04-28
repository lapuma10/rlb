package net.runelite.client.plugins.recorder.scripts;

import java.awt.Rectangle;
import java.awt.event.KeyEvent;
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
import net.runelite.client.plugins.recorder.farm.BankInteraction;
import net.runelite.client.plugins.recorder.farm.InventoryUtil;
import net.runelite.client.plugins.recorder.transport.TransportResolver;
import net.runelite.client.plugins.recorder.walker.PathSpec;
import net.runelite.client.plugins.recorder.walker.UniversalWalker;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;

/**
 * Chicken farm bot, V2 — uses the {@link UniversalWalker walker framework}
 * for the WALKING phases (OUTBOUND / RETURN). BANKING and AT_PEN are still
 * script-level concerns: this class drives them directly using
 * {@link BankInteraction} primitives and {@link ChickenCombatLoop} per the
 * walker's design boundary ("the walker only walks").
 *
 * <h2>Architecture vs the V1 hand-coded script</h2>
 *
 * <p>{@link LumbridgeBankPenScript} (V1) is ~1500 lines: it implements the
 * walking, banking, combat, gate logic, and stairs logic in one class.
 * This V2 replaces only the walking. BANKING and AT_PEN logic is mirrored
 * (simpler — see notes below).
 *
 * <pre>
 *  ┌─────────────┐
 *  │ V2 outer FSM│   IDLE → BANKING → OUTBOUND → AT_PEN → RETURN → BANKING ...
 *  ├─────────────┤
 *  │  BANKING    │   bank booth click + deposit-inv + close (this class)
 *  │  OUTBOUND   │   walker.tick(OUTBOUND_SPEC)              ←── framework
 *  │  AT_PEN     │   ChickenCombatLoop.start/stop/state      ←── existing
 *  │  RETURN     │   walker.tick(RETURN_SPEC)                ←── framework
 *  └─────────────┘
 * </pre>
 *
 * <h2>What's intentionally simpler than V1</h2>
 *
 * <ul>
 *   <li><b>Booth picking.</b> V1's {@code clickRandomBooth} scans both
 *       NPC bankers AND GameObject bank booths and randomises the choice.
 *       V2 uses {@link BankInteraction#clickBankBooth} (NPC-only, first
 *       match). Good enough at Lumbridge upstairs; we can promote to a
 *       shared {@code farm/BankFlow} class once a second bot needs the
 *       same flow.</li>
 *   <li><b>Stairs / gate.</b> V1 handles these as in-line state-machine
 *       branches at specific landmarks. V2 declares them as part of the
 *       {@link PathSpec} and lets {@link UniversalWalker} drive — gate is
 *       a one-line {@code .gate(tile)}, stairs are {@code .climbDown(tile)}.
 *       The framework handles the &quot;already-open gate&quot; case and the
 *       middle-floor staircase verb-pick via the same humanized
 *       right-click flow.</li>
 * </ul>
 *
 * <h2>This script does NOT replace V1</h2>
 *
 * <p>V1 ({@link LumbridgeBankPenScript}) is the actively-used script and
 * is left untouched. V2 lives alongside it as the proof-of-concept that
 * the framework works for a real script. The panel exposes both behind
 * separate buttons so they can be compared side-by-side on real game
 * state.
 */
@Slf4j
public final class ChickenFarmV2Script
{
    // ────────────────────────────────────────────────────────────────
    // Landmarks — match LumbridgeBankPenScript verbatim so V1 and V2
    // walk the SAME route. If V1's coordinates change later, mirror them
    // here. (Future cleanup: lift into a shared constants class once V1
    // migrates onto the framework.)
    // ────────────────────────────────────────────────────────────────

    private static final WorldArea BANK_AREA          = new WorldArea(3208, 3220, 5, 6, 2);
    private static final WorldArea STAIRS_LANDING_P0  = new WorldArea(3206, 3227, 4, 2, 0);
    private static final WorldArea CASTLE_YARD        = new WorldArea(3219, 3217, 9, 4, 0);
    private static final WorldArea STONE_BRIDGE       = new WorldArea(3237, 3225, 7, 2, 0);
    private static final WorldArea GOBLIN_FENCE       = new WorldArea(3259, 3234, 2, 4, 0);
    private static final WorldArea COW_FENCE          = new WorldArea(3249, 3254, 4, 4, 0);
    private static final WorldArea PEN_APPROACH       = new WorldArea(3238, 3289, 3, 8, 0);
    private static final WorldArea PEN_AREA           = new WorldArea(3225, 3290, 13, 12, 0);

    private static final WorldPoint STAIRS_TILE_P2 = new WorldPoint(3205, 3229, 2);
    private static final WorldPoint STAIRS_TILE_P1 = new WorldPoint(3205, 3229, 1);
    private static final WorldPoint STAIRS_TILE_P0 = new WorldPoint(3205, 3229, 0);
    private static final WorldPoint PEN_GATE       = new WorldPoint(3236, 3296, 0);

    /** Bank → pen path. The walker walks to each landmark in order; at a
     *  Transport step it invokes the verb on the matching object via
     *  the humanized right-click → menu-pick flow. */
    private static final PathSpec OUTBOUND_SPEC = PathSpec.builder("chicken-out")
        .climbDown(STAIRS_TILE_P2)
        .climbDown(STAIRS_TILE_P1)
        .walk("stairs-landing", STAIRS_LANDING_P0)
        .walk("castle-yard",    CASTLE_YARD)
        .walk("stone-bridge",   STONE_BRIDGE)
        .walk("goblin-fence",   GOBLIN_FENCE)
        .walk("cow-fence",      COW_FENCE)
        .walk("pen-approach",   PEN_APPROACH)
        .gate(PEN_GATE)
        .walk("pen",            PEN_AREA)
        .build();

    /** Pen → bank path. Symmetric reverse, with an explicit walk into
     *  BANK_AREA at the end so RETURN ARRIVED ⇒ BANKING can run the
     *  deposit cycle without an extra walk step. */
    private static final PathSpec RETURN_SPEC = PathSpec.builder("chicken-return")
        .gate(PEN_GATE)
        .walk("pen-approach",   PEN_APPROACH)
        .walk("cow-fence",      COW_FENCE)
        .walk("goblin-fence",   GOBLIN_FENCE)
        .walk("stone-bridge",   STONE_BRIDGE)
        .walk("castle-yard",    CASTLE_YARD)
        .walk("stairs-landing", STAIRS_LANDING_P0)
        .climbUp(STAIRS_TILE_P0)
        .climbUp(STAIRS_TILE_P1)
        .walk("bank",           BANK_AREA)
        .build();

    /** Cadence between walker ticks. 600ms ≈ one OSRS engine tick — long
     *  enough that the engine has updated player position by the time we
     *  re-read it, short enough to feel responsive. */
    private static final long TICK_MS = 600;

    /** Min interval between any two banking dispatches (booth-click,
     *  deposit-inv, close). The bank widget needs ≥ one engine tick to
     *  process the previous click; spamming clicks drops them. */
    private static final long BANK_PACE_MS = 2000;

    public enum State { IDLE, BANKING, OUTBOUND, AT_PEN, RETURN, ABORTED }

    private final Client client;
    private final ClientThread clientThread;
    private final HumanizedInputDispatcher dispatcher;

    private final UniversalWalker walker;
    private final BankInteraction bank;
    private final ChickenCombatLoop combat;

    private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);
    private final AtomicReference<String> status = new AtomicReference<>("idle");
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<Thread> worker = new AtomicReference<>();
    private long lastBankActionAtMs;

    public ChickenFarmV2Script(Client client, ClientThread clientThread,
                               HumanizedInputDispatcher dispatcher,
                               TransportResolver resolver)
    {
        this.client = client;
        this.clientThread = clientThread;
        this.dispatcher = dispatcher;
        this.walker = new UniversalWalker(client, clientThread, dispatcher, resolver);
        this.bank = new BankInteraction(client, clientThread, dispatcher);
        this.combat = new ChickenCombatLoop(dispatcher, client, clientThread, PEN_AREA);
    }

    public State state()    { return state.get(); }
    public String status()  { return status.get(); }
    public int killCount()  { return combat.killCount(); }

    public void start()
    {
        if (!running.compareAndSet(false, true)) return;
        State decided = decideResume();
        log.info("v2: resume → {} (status: {})", decided, status.get());
        setState(decided);
        if (decided == State.IDLE || decided == State.ABORTED)
        {
            running.set(false);
            return;
        }
        Thread t = new Thread(this::tickLoop, "chicken-farm-v2");
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

    /** Decide initial state from player position + inventory — same shape
     *  as V1's {@code decideResume}, simpler logic. */
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
                    case BANKING:  tickBanking();                  break;
                    case OUTBOUND: tickWalk(OUTBOUND_SPEC, State.AT_PEN); break;
                    case AT_PEN:   tickAtPen();                    break;
                    case RETURN:   tickWalk(RETURN_SPEC, State.BANKING);  break;
                    case ABORTED:
                    case IDLE:
                    default:       running.set(false); break;
                }
                Thread.sleep(TICK_MS);
            }
        }
        catch (InterruptedException ie)
        {
            Thread.currentThread().interrupt();
        }
        finally
        {
            running.set(false);
        }
    }

    /** Drive a walking phase. The walker is stateful — it remembers which
     *  step we're on across ticks. On ARRIVED we reset it so the next
     *  walking phase starts fresh. */
    private void tickWalk(PathSpec spec, State onArrived) throws InterruptedException
    {
        UniversalWalker.Status st = walker.tick(spec);
        status.set("walk: " + spec.name() + " (" + st + ")");
        switch (st)
        {
            case ARRIVED:
                walker.reset();
                setState(onArrived);
                break;
            case STUCK:
            case ERROR:
                log.warn("v2: walker {} on {} — aborting", st, spec.name());
                setState(State.ABORTED);
                break;
            case IN_PROGRESS:
            default:
                break;
        }
    }

    /** Bank state machine — paced at ≥ 2s between any dispatch.
     *  Identical shape to V1's tickBanking; uses {@link BankInteraction}
     *  primitives instead of V1's private banker-search helpers. */
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

        boolean open  = onClient(bank::isBankOpen);
        boolean empty = onClient(() ->
            InventoryUtil.freeSlotCount(client) >= InventoryUtil.INVENTORY_SIZE);

        if (!open && !empty)
        {
            status.set("clicking bank booth");
            if (bank.clickBankBooth()) lastBankActionAtMs = now;
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
            closeBankThreadSafe();
            lastBankActionAtMs = now;
            return;
        }
        // !open && empty — done.
        status.set("bank closed — heading to pen");
        setState(State.OUTBOUND);
    }

    private void tickAtPen()
    {
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

    /** Read the deposit-inv widget on the client thread, dispatch the
     *  click off-thread. {@link BankInteraction#clickDepositInventory}
     *  reads the widget on whatever thread the caller is on, which trips
     *  the engine's client-thread assertion under -ea. */
    private boolean clickDepositInventoryThreadSafe() throws InterruptedException
    {
        Rectangle b = onClient(() -> {
            Widget w = client.getWidget(InterfaceID.Bankmain.DEPOSITINV);
            if (w == null || w.isHidden()) return null;
            Rectangle r = w.getBounds();
            return r == null || r.isEmpty() ? null : r;
        });
        if (b == null) return false;
        dispatcher.clickCanvas(b.x + b.width / 2, b.y + b.height / 2);
        return true;
    }

    /** Same threading care as {@link #clickDepositInventoryThreadSafe}. */
    private void closeBankThreadSafe() throws InterruptedException
    {
        Boolean stillOpen = onClient(() -> {
            Widget w = client.getWidget(InterfaceID.Bankmain.UNIVERSE);
            return w != null && !w.isHidden();
        });
        if (stillOpen == null || !stillOpen) return;
        dispatcher.tapKey(KeyEvent.VK_ESCAPE);
    }

    // ────────────────────────────────────────────────────────────────
    // helpers
    // ────────────────────────────────────────────────────────────────

    private void setState(State s)
    {
        state.set(s);
        lastBankActionAtMs = 0L;
    }

    private static boolean areaContains(WorldArea a, WorldPoint p)
    {
        return p.getPlane() == a.getPlane()
            && p.getX() >= a.getX() && p.getX() < a.getX() + a.getWidth()
            && p.getY() >= a.getY() && p.getY() < a.getY() + a.getHeight();
    }

    /** Run {@code s} on the client thread, return its result. Blocks the
     *  caller — only safe from the worker thread, never the EDT. */
    private <T> T onClient(Supplier<T> s)
    {
        java.util.concurrent.atomic.AtomicReference<T> ref = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        clientThread.invokeLater(() -> {
            try { ref.set(s.get()); }
            catch (Throwable th) { log.warn("v2: onClient threw", th); }
            finally { latch.countDown(); }
        });
        try { latch.await(); }
        catch (InterruptedException ie)
        { Thread.currentThread().interrupt(); return null; }
        return ref.get();
    }
}
