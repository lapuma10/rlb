package net.runelite.client.plugins.recorder.scripts;

import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.event.KeyEvent;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.MenuAction;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.Perspective;
import net.runelite.api.WorldView;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.recorder.combat.ChickenCombatLoop;
import net.runelite.client.plugins.recorder.farm.BankInteraction;
import net.runelite.client.plugins.recorder.farm.InventoryUtil;
import net.runelite.client.plugins.recorder.transport.TransportResolver;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import net.runelite.client.sequence.internal.ActionRequest;

/**
 * Walking script: Lumbridge bank ↔ chicken pen, round-trip.
 *
 * <p>This is a hand-written loop — no route file, no annotator. Each
 * step is explicit Java, so when something goes wrong you can read the
 * code and tell exactly which step is broken.
 *
 * <p>Threading model mirrors {@link net.runelite.client.plugins.recorder.farm.ChickenFarmLoop}:
 * one daemon thread runs the outer tick loop on a sleep cadence; every
 * call into client APIs (player position, scene tiles, dispatch) is
 * routed through {@link ClientThread#invokeLater} because the engine
 * asserts those run on the client thread (with -ea).
 */
@Slf4j
public final class LumbridgeBankPenScript
{
    // ────────────────────────────────────────────────────────────────
    // Tile constants — fill in / verify with Mark tile.
    // ────────────────────────────────────────────────────────────────

    /** Bank tile area (plane 2). */
    private static final WorldArea BANK_AREA = new WorldArea(3208, 3218, 3, 3, 2);

    /** Staircase landing on each plane — the cluster of tiles right beside
     *  the staircase. We walk to here, then look for the stairs by verb. */
    private static final WorldArea STAIRS_AREA_P2 = new WorldArea(3205, 3227, 3, 4, 2);
    private static final WorldArea STAIRS_AREA_P1 = new WorldArea(3205, 3227, 3, 4, 1);
    private static final WorldArea STAIRS_LANDING_P0 = new WorldArea(3206, 3227, 4, 2, 0);

    /** Walking landmarks between stairs landing and the pen gate, plane 0. */
    private static final WorldArea CASTLE_YARD   = new WorldArea(3219, 3217, 9, 4, 0);
    private static final WorldArea STONE_BRIDGE  = new WorldArea(3237, 3225, 7, 2, 0);
    private static final WorldArea GOBLIN_FENCE  = new WorldArea(3259, 3234, 2, 4, 0);
    private static final WorldArea COW_FENCE     = new WorldArea(3249, 3254, 4, 4, 0);
    private static final WorldArea PEN_APPROACH  = new WorldArea(3238, 3289, 3, 8, 0);

    /** Pen interior — passed to ChickenCombatLoop so it knows where to fight. */
    private static final WorldArea PEN_AREA = new WorldArea(3225, 3290, 13, 12, 0);

    /** Ordered list of landmarks the bot walks through on plane 0,
     *  bank-side → pen-side. Matches the route file you wrote:
     *  Downstairs → Castle yard → Stone bridge → Goblin fence →
     *  Cow fence → Pen approach. The gate sits at the end and the pen
     *  interior is past it. */
    private static final WorldArea[] OUTBOUND_PATH_P0 = {
        STAIRS_LANDING_P0,
        CASTLE_YARD,
        STONE_BRIDGE,
        GOBLIN_FENCE,
        COW_FENCE,
        PEN_APPROACH,
    };

    /** Search radius (tiles) when looking for the closest staircase / gate.
     *  Spiral expands outward from the player, so the first match found is
     *  the closest by Chebyshev (walking) distance. */
    private static final int OBJECT_SEARCH_RADIUS = 15;

    /** If a banker / bank booth is within this many tiles, pick that one
     *  deterministically. Beyond it, pick at random from candidates so we
     *  don't always click the same booth. */
    private static final int BANK_NEAR_RADIUS = 1;

    // ────────────────────────────────────────────────────────────────
    // State
    // ────────────────────────────────────────────────────────────────

    // WHAT THIS NEEDS TO DO:

    /**
     *  script normally should start in lumby bank, but it should be able to resume and find where it left off last(based on location and inventory state)
     *
     * start in lumby bank(plane 2 the first entry.) If our inventory is empty. good! lets go to the chickens!
     * is the inventory full? not empty? well then we have to find the bank booths and bankers and randomly choose if were gonna a bankbooth or rightclick a banked and select bank.(menu)
     *
     * then we deposit the inventory.
     *
     * when we then go ahead and walk to the stairs. (the second area in our path)
     * Here we look for the closest strais object! Oh look its right next to us? great! Click it, were going down!
     * Once we go down, we see the next stairs? Were in plane 1 and the strais are next to us again! Oh wow! Rightclick the stair object and go downstairs! (on the way back well go upstairs!)
     * for thsi we need the API to ensure that we get the correct object and correct object id.
     *
     * Then were in plane 0! at the bottom of lumby castle! were walking now all the way one by one area, all the way to I believe #7! here we stop. Look for the gate, exact gate thats between us and the chicken pen(theres the 2 parts to it, theyre side by side shouldve been one but i made it 2...)
     * Here we do the stairs logic again! look for gate and check if it says open or close, does it say close? good! then we walk trough it! does it say open? damn, lets open it and walk through to the pen!
     *
     * hooray! were at the pen! that wasnt so hard was it????
     *
     * to go back? well check the gate, walk all the way to the stairs back to lumby castle plane 0 (just follow the platforms areas reversed)
     * Then we climb u the stairs with our newly made stairs util. Oh look plane 1? climb up to 2! and then here we go were at the bank. lets walk over to the bank (dont need to go to the inbetween area between plane 2 stairs area and the bank, go straihh to the bank and use the bank, bank it all)
     *
     * Good! Now rinse and repeat!
     *
     *
     *
     */


    /** What phase the script is in. Used by tick() to choose what to do. */
    public enum State
    {
        IDLE,
        BANKING,            // open bank widget, deposit, close (plane 2)
        OUTBOUND,           // bank → pen
        AT_PEN,             // arrived at pen interior — combat runs here
        RETURN,             // pen → bank
        ABORTED
    }

    private final Client client;
    private final ClientThread clientThread;
    private final HumanizedInputDispatcher dispatcher;
    private final TransportResolver resolver;
    private final BankInteraction bank;
    private final ChickenCombatLoop combat;

    private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);
    private final AtomicReference<String> status = new AtomicReference<>("idle");
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<Thread> worker = new AtomicReference<>();

    // Walk-pacing state. The outer tick loop re-clicks every ~500ms, but
    // the engine takes ~600ms per tile + only paths up to 25 tiles per
    // click. Re-clicking on every tick spams the engine with redundant
    // walk targets. We track:
    //  - what we last clicked toward (so a target change forces a
    //    re-click)
    //  - when we last clicked
    //  - the player position last time we polled (so we can tell if
    //    they're actually moving)
    //  - when the player last moved (so we can detect "stuck")
    // Re-click only fires when target changed OR player has been still
    // for ≥ 2.5s since the last click took effect.
    private WorldArea lastWalkTarget;
    private long lastClickAtMs;
    private WorldPoint lastSeenPos;
    private long lastMoveAtMs;
    private long stateEnteredAtMs;
    /** Throttle for ANY banking action — booth click, deposit, close.
     *  Each click has to be processed by the engine (one game tick =
     *  ~600ms minimum, plus animation + UI update). Spamming clicks
     *  inside the same window cancels prior actions. We pace at ≥ 2s
     *  between any two bank-related dispatches. */
    private long lastBankActionAtMs;
    /** Throttle for stairs / gate menu invocations. Plane change after a
     *  staircase click takes ~1-2s; without this we'd re-invoke every
     *  tick during the animation. */
    private long lastInteractionAtMs;
    /** Latest landmark we've been inside on plane 0 — used to keep the
     *  outbound/return walk monotonic. Without this, when the player is
     *  between two landmarks, closestLandmark can flip back to the one
     *  we just left and we oscillate. -1 = uninitialised (set on first
     *  tick of OUTBOUND/RETURN). */
    private int lastVisitedIdx = -1;

    public LumbridgeBankPenScript(Client client, ClientThread clientThread,
                                  HumanizedInputDispatcher dispatcher,
                                  TransportResolver resolver)
    {
        this.client = client;
        this.clientThread = clientThread;
        this.dispatcher = dispatcher;
        this.resolver = resolver;
        this.bank = new BankInteraction(client, clientThread, dispatcher);
        this.combat = new ChickenCombatLoop(dispatcher, client, clientThread, PEN_AREA);
    }

    public State state()   { return state.get(); }
    public String status() { return status.get(); }
    public int killCount() { return combat.killCount(); }

    // ────────────────────────────────────────────────────────────────
    // Lifecycle
    // ────────────────────────────────────────────────────────────────

    /**
     * Decide initial state from current player position + inventory and
     * fork the worker thread. Idempotent — calling start() while already
     * running is a no-op.
     */
    public void start()
    {
        if (!running.compareAndSet(false, true)) return;
        State decided = decideResume();
        log.info("script: resume → {} (status: {})", decided, status.get());
        setState(decided);  // resets walk-pacing
        if (decided == State.ABORTED || decided == State.IDLE)
        {
            running.set(false);
            return;
        }
        Thread t = new Thread(this::tickLoop, "lumby-bank-pen-script");
        t.setDaemon(true);
        worker.set(t);
        t.start();
    }

    /**
     * Pick the most plausible starting state based on where the player
     * is right now and what's in the inventory. Bias: prefer "do the
     * trip we'd be on if we were already running" so a stop+start
     * resumes seamlessly.
     */
    private State decideResume()
    {
        WorldPoint here = onClientThread(() -> {
            Player self = client.getLocalPlayer();
            return self == null ? null : self.getWorldLocation();
        });
        if (here == null)
        {
            status.set("no player — aborting");
            return State.ABORTED;
        }
        boolean invFull = onClientThread(() -> InventoryUtil.isInventoryFull(client));
        int free = onClientThread(() -> InventoryUtil.freeSlotCount(client));
        boolean invEmpty = free >= InventoryUtil.INVENTORY_SIZE;

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
        // Anywhere else — pick by inventory: empty/partial = go to pen,
        // full = go to bank.
        status.set("starting mid-route");
        return invFull ? State.RETURN : State.OUTBOUND;
    }

    /** Stop the loop, interrupt any in-flight combat or worker. */
    public void stop()
    {
        running.set(false);
        setState(State.IDLE);
        status.set("stopped");
        Thread t = worker.getAndSet(null);
        if (t != null) t.interrupt();
        combat.stop();
    }

    // ────────────────────────────────────────────────────────────────
    // Outer loop — runs on the worker thread, sleeps between ticks.
    // ────────────────────────────────────────────────────────────────

    private void tickLoop()
    {
        try
        {
            while (running.get() && !Thread.currentThread().isInterrupted())
            {
                State s = state.get();
                switch (s)
                {
                    case BANKING:  tickBanking();  break;
                    case OUTBOUND: tickOutbound(); break;
                    case AT_PEN:   tickAtPen();    break;
                    case RETURN:   tickReturn();   break;
                    case ABORTED:
                    case IDLE:
                    default:       running.set(false); break;
                }
                Thread.sleep(humanCadence());
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

    // ────────────────────────────────────────────────────────────────
    // BANKING — at bank, inventory non-empty: open bank, deposit, advance
    // ────────────────────────────────────────────────────────────────

    /**
     * Bank workflow as a flat state machine driven by widget state +
     * inventory state, paced at ≥ 2s between any bank-related dispatch:
     * <ol>
     *   <li>Not at bank → walk there.</li>
     *   <li>Bank widget closed → click random booth/banker.</li>
     *   <li>Bank widget open AND inventory non-empty → click deposit-inv.</li>
     *   <li>Bank widget open AND inventory empty → click close (Esc).</li>
     *   <li>Bank widget closed AND inventory empty → transition to OUTBOUND.</li>
     * </ol>
     * Each dispatch sets {@link #lastBankActionAtMs}; the next action is
     * gated until ≥ 2s have passed so the engine can process the previous
     * widget update (one game tick + UI lag). Without this we spam the
     * deposit button repeatedly and the bank never closes before we walk.
     */
    private void tickBanking() throws InterruptedException
    {
        if (!playerInArea(BANK_AREA))
        {
            WalkResult r = advanceWalk(BANK_AREA);
            if (r == WalkResult.STUCK)
            {
                log.warn("script: stuck walking to bank — aborting");
                setState(State.ABORTED);
            }
            return;
        }
        long now = System.currentTimeMillis();
        long sinceAction = lastBankActionAtMs == 0
            ? Long.MAX_VALUE : now - lastBankActionAtMs;
        if (sinceAction < 2000)
        {
            status.set("bank — pacing (last action " + sinceAction + "ms ago)");
            return;
        }

        boolean open = onClientThread(bank::isBankOpen);
        boolean empty = onClientThread(() ->
            InventoryUtil.freeSlotCount(client) >= InventoryUtil.INVENTORY_SIZE);

        if (!open && !empty)
        {
            // Open the bank.
            status.set("clicking bank booth");
            if (clickRandomBooth()) lastBankActionAtMs = now;
            return;
        }
        if (open && !empty)
        {
            // Deposit.
            status.set("depositing inventory");
            if (clickDepositInventorySafe()) lastBankActionAtMs = now;
            return;
        }
        if (open && empty)
        {
            // Close the widget before walking — otherwise the next click
            // (walk to stairs) might land on the bank UI instead of the
            // game canvas.
            status.set("closing bank");
            closeBankSafe();
            lastBankActionAtMs = now;
            return;
        }
        // !open && empty — done.
        status.set("bank closed — heading to pen");
        setState(State.OUTBOUND);
    }

    /**
     * Click the Deposit-inventory orb. Reads the widget bounds on the
     * client thread (Widget.isHidden / getBounds assert client thread
     * under -ea) and dispatches the canvas click off-thread (the
     * dispatcher humanizes timing, which we don't want to do on the
     * client thread). BankInteraction's version touches the widget
     * from whatever thread called it; that works in production
     * without -ea but throws in dev mode.
     */
    private boolean clickDepositInventorySafe() throws InterruptedException
    {
        Rectangle b = onClientThread(() -> {
            Widget w = client.getWidget(InterfaceID.Bankmain.DEPOSITINV);
            if (w == null || w.isHidden()) return null;
            Rectangle r = w.getBounds();
            return r == null || r.isEmpty() ? null : r;
        });
        if (b == null) return false;
        dispatcher.clickCanvas(b.x + b.width / 2, b.y + b.height / 2);
        return true;
    }

    /**
     * Close the bank widget by sending Escape. Same threading concern
     * as {@link #clickDepositInventorySafe}: the bank-open check reads
     * a widget; do that on the client thread and the keypress dispatch
     * off-thread.
     */
    private void closeBankSafe() throws InterruptedException
    {
        boolean open = onClientThread(() -> {
            Widget w = client.getWidget(InterfaceID.Bankmain.UNIVERSE);
            return w != null && !w.isHidden();
        });
        if (!open) return;
        dispatcher.tapKey(KeyEvent.VK_ESCAPE);
    }

    // ────────────────────────────────────────────────────────────────
    // OUTBOUND — bank → stairs ×2 → walk through Lumbridge → gate → pen
    // ────────────────────────────────────────────────────────────────

    private void tickOutbound() throws InterruptedException
    {
        WorldPoint here = playerPos();
        if (here == null) return;
        int plane = here.getPlane();

        // Inventory full? Switch to RETURN — the trip already happened
        // (e.g. user resumed mid-route after killing).
        if (onClientThread(() -> InventoryUtil.isInventoryFull(client)))
        {
            status.set("inventory full mid-outbound — switching to RETURN");
            setState(State.RETURN);
            return;
        }

        switch (plane)
        {
            case 2:
                if (!playerInArea(STAIRS_AREA_P2))
                {
                    status.set("walk → stairs (p2)");
                    if (advanceWalk(STAIRS_AREA_P2) == WalkResult.STUCK)
                        setState(State.ABORTED);
                    return;
                }
                if (!isPlayerSettled())
                {
                    status.set("at stairs (p2) — waiting for walk to settle");
                    return;
                }
                if (!canInteract())
                {
                    status.set("at stairs (p2) — waiting for previous climb");
                    return;
                }
                status.set("climb down (p2 → p1)");
                climbDown();
                lastInteractionAtMs = System.currentTimeMillis();
                return;

            case 1:
                if (!playerInArea(STAIRS_AREA_P1))
                {
                    status.set("walk → stairs (p1)");
                    if (advanceWalk(STAIRS_AREA_P1) == WalkResult.STUCK)
                        setState(State.ABORTED);
                    return;
                }
                if (!isPlayerSettled())
                {
                    status.set("at stairs (p1) — waiting for walk to settle");
                    return;
                }
                if (!canInteract())
                {
                    status.set("at stairs (p1) — waiting for previous climb");
                    return;
                }
                status.set("climb down (p1 → p0)");
                climbDown();
                lastInteractionAtMs = System.currentTimeMillis();
                return;

            case 0:
                if (areaContains(PEN_AREA, here))
                {
                    status.set("arrived at pen");
                    setState(State.AT_PEN);
                    return;
                }
                // Initialise lastVisitedIdx on the first tick of OUTBOUND
                // on plane 0 — pick the closest landmark as the anchor.
                if (lastVisitedIdx < 0)
                {
                    lastVisitedIdx = closestLandmarkIdx(OUTBOUND_PATH_P0, here);
                    log.info("script: outbound starting from landmark #{}", lastVisitedIdx);
                }
                // Advance lastVisitedIdx if the player has entered any
                // forward landmark since last tick. We never go backwards
                // — that's the whole point of tracking idx.
                for (int i = lastVisitedIdx + 1; i < OUTBOUND_PATH_P0.length; i++)
                {
                    if (areaContains(OUTBOUND_PATH_P0[i], here))
                    {
                        lastVisitedIdx = i;
                        log.info("script: outbound advanced to landmark #{}", i);
                        break;
                    }
                }
                // Past the last landmark — gate logic.
                if (lastVisitedIdx >= OUTBOUND_PATH_P0.length - 1)
                {
                    if (!isPlayerSettled())
                    {
                        status.set("at gate — waiting for walk to settle");
                        return;
                    }
                    if (!canInteract())
                    {
                        status.set("at gate — waiting after previous open");
                        return;
                    }
                    status.set("opening gate (or already open)");
                    switch (openGate())
                    {
                        case CLICKED_OPEN:
                            // Engine walks-and-opens. Throttle and wait.
                            lastInteractionAtMs = System.currentTimeMillis();
                            break;
                        case ALREADY_OPEN:
                        case NOT_FOUND:
                            // ALREADY_OPEN: gate is open but no click was fired,
                            // so we still need to walk through into the pen.
                            // NOT_FOUND: scene hasn't loaded the wall yet, so
                            // step closer until it does.
                            if (advanceWalk(PEN_AREA) == WalkResult.STUCK)
                                setState(State.ABORTED);
                            break;
                    }
                    return;
                }
                WorldArea next = OUTBOUND_PATH_P0[lastVisitedIdx + 1];
                status.set("walk → landmark #" + (lastVisitedIdx + 1));
                if (advanceWalk(next) == WalkResult.STUCK) setState(State.ABORTED);
                return;

            default:
                status.set("unexpected plane " + plane + " — aborting");
                setState(State.ABORTED);
        }
    }

    // ────────────────────────────────────────────────────────────────
    // AT_PEN — kill chickens until inventory full, then RETURN
    // ────────────────────────────────────────────────────────────────

    private void tickAtPen()
    {
        // Inventory full → stop combat, head back.
        if (onClientThread(() -> InventoryUtil.isInventoryFull(client)))
        {
            status.set("inventory full — RETURN");
            combat.stop();
            setState(State.RETURN);
            return;
        }
        // Combat idle → start it.
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

    // ────────────────────────────────────────────────────────────────
    // RETURN — gate → walk back through landmarks → stairs ×2 → bank
    // ────────────────────────────────────────────────────────────────

    private void tickReturn() throws InterruptedException
    {
        WorldPoint here = playerPos();
        if (here == null) return;
        int plane = here.getPlane();

        switch (plane)
        {
            case 0:
                if (areaContains(PEN_AREA, here))
                {
                    if (!isPlayerSettled())
                    {
                        status.set("at pen — waiting for walk to settle before gate");
                        return;
                    }
                    if (!canInteract())
                    {
                        status.set("at pen — waiting after previous open");
                        return;
                    }
                    status.set("opening gate to leave pen");
                    switch (openGate())
                    {
                        case CLICKED_OPEN:
                            lastInteractionAtMs = System.currentTimeMillis();
                            break;
                        case ALREADY_OPEN:
                        case NOT_FOUND:
                            if (advanceWalk(PEN_APPROACH) == WalkResult.STUCK)
                                setState(State.ABORTED);
                            break;
                    }
                    return;
                }
                // Same monotonic-index trick as outbound, but going
                // backwards. lastVisitedIdx walks from N-1 down toward 0.
                if (lastVisitedIdx < 0)
                {
                    lastVisitedIdx = closestLandmarkIdx(OUTBOUND_PATH_P0, here);
                    log.info("script: return starting from landmark #{}", lastVisitedIdx);
                }
                // Advance backward — if we've entered any earlier
                // landmark since last tick, drop idx to it.
                for (int i = lastVisitedIdx - 1; i >= 0; i--)
                {
                    if (areaContains(OUTBOUND_PATH_P0[i], here))
                    {
                        lastVisitedIdx = i;
                        log.info("script: return advanced back to landmark #{}", i);
                        break;
                    }
                }
                if (lastVisitedIdx == 0)
                {
                    if (!isPlayerSettled())
                    {
                        status.set("at stairs (p0) — waiting for walk to settle");
                        return;
                    }
                    if (!canInteract())
                    {
                        status.set("at stairs (p0) — waiting for previous climb");
                        return;
                    }
                    status.set("climb up (p0 → p1)");
                    climbUp();
                    lastInteractionAtMs = System.currentTimeMillis();
                    return;
                }
                WorldArea prev = OUTBOUND_PATH_P0[lastVisitedIdx - 1];
                status.set("walk back → landmark #" + (lastVisitedIdx - 1));
                if (advanceWalk(prev) == WalkResult.STUCK) setState(State.ABORTED);
                return;

            case 1:
                if (!playerInArea(STAIRS_AREA_P1))
                {
                    status.set("walk → stairs (p1)");
                    if (advanceWalk(STAIRS_AREA_P1) == WalkResult.STUCK)
                        setState(State.ABORTED);
                    return;
                }
                if (!isPlayerSettled())
                {
                    status.set("at stairs (p1) — waiting for walk to settle");
                    return;
                }
                if (!canInteract())
                {
                    status.set("at stairs (p1) — waiting for previous climb");
                    return;
                }
                status.set("climb up (p1 → p2)");
                climbUp();
                lastInteractionAtMs = System.currentTimeMillis();
                return;

            case 2:
                if (!playerInArea(BANK_AREA))
                {
                    status.set("walk → bank");
                    if (advanceWalk(BANK_AREA) == WalkResult.STUCK)
                        setState(State.ABORTED);
                    return;
                }
                status.set("at bank — switching to BANKING");
                setState(State.BANKING);
                return;

            default:
                status.set("unexpected plane " + plane + " on return — aborting");
                setState(State.ABORTED);
        }
    }

    /** Atomic state transition + walk-pacing reset. Use this everywhere
     *  except {@link #stop} (which already resets via cleanup). */
    private void setState(State newState)
    {
        state.set(newState);
        resetWalkState();
    }

    // ────────────────────────────────────────────────────────────────
    // Helpers — primitive operations the tick* methods compose.
    // ────────────────────────────────────────────────────────────────

    /** Read the player's current world location on the client thread. */
    private WorldPoint playerPos()
    {
        return onClientThread(() -> {
            Player self = client.getLocalPlayer();
            return self == null ? null : self.getWorldLocation();
        });
    }

    /** True when the player's tile is inside {@code area}. */
    private boolean playerInArea(WorldArea area)
    {
        WorldPoint here = playerPos();
        if (here == null) return false;
        return areaContains(area, here);
    }

    /** Result of a single advanceWalk call. */
    private enum WalkResult { ARRIVED, WAITING, STUCK }

    /**
     * Walk one tick toward {@code target} with click-spam suppression.
     * Re-clicks only when the target changed OR the player has been
     * still for ≥ 1.5s since the last click. Returns ARRIVED when the
     * player tile is inside {@code target}, STUCK if no movement for
     * ≥ 15s, otherwise WAITING.
     */
    private WalkResult advanceWalk(WorldArea target) throws InterruptedException
    {
        WorldPoint here = playerPos();
        if (here == null) return WalkResult.WAITING;
        if (areaContains(target, here)) return WalkResult.ARRIVED;

        long now = System.currentTimeMillis();
        if (lastSeenPos == null || !lastSeenPos.equals(here))
        {
            lastSeenPos = here;
            lastMoveAtMs = now;
        }
        long sinceClick = lastClickAtMs == 0 ? Long.MAX_VALUE : now - lastClickAtMs;
        long sinceMove = now - lastMoveAtMs;

        boolean targetChanged = lastWalkTarget == null
            || !sameArea(lastWalkTarget, target);
        // Tunable thresholds: pathfinding caps at 25 tiles per click and
        // a long walk takes ~10-15s. Wait at least 3s after a click
        // before considering a re-click (engine latency + animation).
        boolean stillWalking = sinceMove < 2500;
        boolean recentClick = sinceClick < 3000;

        boolean shouldClick = targetChanged
            || (!recentClick && !stillWalking);

        if (shouldClick)
        {
            log.info("script: walking → {} (sinceClick={}ms, sinceMove={}ms)",
                describeArea(target), sinceClick == Long.MAX_VALUE ? "never" : sinceClick + "",
                sinceMove);
            walkTo(target);
            lastWalkTarget = target;
            lastClickAtMs = now;
        }

        if (lastClickAtMs > 0 && sinceMove > 15000) return WalkResult.STUCK;
        return WalkResult.WAITING;
    }

    private static boolean sameArea(WorldArea a, WorldArea b)
    {
        return a.getX() == b.getX() && a.getY() == b.getY()
            && a.getWidth() == b.getWidth() && a.getHeight() == b.getHeight()
            && a.getPlane() == b.getPlane();
    }

    private static String describeArea(WorldArea a)
    {
        return "(" + a.getX() + "," + a.getY() + " " + a.getWidth()
            + "x" + a.getHeight() + ",p=" + a.getPlane() + ")";
    }

    /** Reset walk-pacing state — called on every state transition so
     *  a fresh state starts cleanly without inheriting the prior walk's
     *  stale "we just clicked here" counters. */
    private void resetWalkState()
    {
        lastWalkTarget = null;
        lastClickAtMs = 0L;
        lastSeenPos = null;
        lastMoveAtMs = System.currentTimeMillis();
        stateEnteredAtMs = System.currentTimeMillis();
        lastBankActionAtMs = 0L;
        lastInteractionAtMs = 0L;
        lastVisitedIdx = -1;
    }

    /**
     * True when the player is NOT animating a walk / run / interaction
     * pose. Compares getPoseAnimation() to getIdlePoseAnimation() —
     * when they match the player is standing still. This is the engine's
     * source of truth, not a heuristic over position deltas.
     */
    private boolean isPlayerSettled()
    {
        Boolean v = onClientThread(() -> {
            Player self = client.getLocalPlayer();
            if (self == null) return false;
            return self.getPoseAnimation() == self.getIdlePoseAnimation();
        });
        return v != null && v;
    }

    /** True if it's been ≥ 3s since the last stairs/gate interaction.
     *  Throttle so we don't re-invoke during the climb/open animation. */
    private boolean canInteract()
    {
        if (lastInteractionAtMs == 0L) return true;
        return (System.currentTimeMillis() - lastInteractionAtMs) > 3000;
    }

    /** Bbox-bounds check: is the point inside the rectangle, on the
     *  same plane? Standalone so {@link #decideResume} can reuse it. */
    private static boolean areaContains(WorldArea area, WorldPoint p)
    {
        return p.getPlane() == area.getPlane()
            && p.getX() >= area.getX()
            && p.getX() < area.getX() + area.getWidth()
            && p.getY() >= area.getY()
            && p.getY() < area.getY() + area.getHeight();
    }

    /** Index of the landmark whose bbox center is closest to {@code p}
     *  by Chebyshev distance. Used as the starting anchor when entering
     *  OUTBOUND/RETURN mid-route. */
    private static int closestLandmarkIdx(WorldArea[] path, WorldPoint p)
    {
        int best = 0;
        int bestDist = Integer.MAX_VALUE;
        for (int i = 0; i < path.length; i++)
        {
            WorldArea a = path[i];
            if (a.getPlane() != p.getPlane()) continue;
            int cx = a.getX() + a.getWidth() / 2;
            int cy = a.getY() + a.getHeight() / 2;
            int d = Math.max(Math.abs(p.getX() - cx), Math.abs(p.getY() - cy));
            if (d < bestDist) { bestDist = d; best = i; }
        }
        return best;
    }

    /**
     * Walk toward {@code area}. Picks the tile inside {@code area} that
     * is closest to the player (Chebyshev) and dispatches a click.
     * <ul>
     *   <li>Prefer a canvas click if the target projects onto the visible
     *       game viewport (more accurate, avoids minimap zoom quirks).</li>
     *   <li>Fall back to a minimap click for tiles within minimap range
     *       (~50 tiles) but off the visible canvas.</li>
     * </ul>
     */
    private void walkTo(WorldArea area) throws InterruptedException
    {
        WalkPick pick = onClientThread(() -> pickWalkTarget(area));
        if (pick == null)
        {
            log.warn("script: walkTo({}) — no reachable tile (none projects to canvas or minimap)",
                describeArea(area));
            return;
        }
        log.info("script: clicking {} ({}px,{}px) → tile {}",
            pick.viaMinimap ? "minimap" : "canvas",
            pick.canvas.getX(), pick.canvas.getY(),
            pick.tile);
        dispatcher.clickCanvas(pick.canvas.getX(), pick.canvas.getY());
    }

    /** Resolved click target: a world tile, the canvas pixel to click,
     *  and which kind of click (canvas / minimap). */
    private static final class WalkPick
    {
        final WorldPoint tile;
        final Point canvas;
        final boolean viaMinimap;
        WalkPick(WorldPoint t, Point c, boolean m)
        { tile = t; canvas = c; viaMinimap = m; }
    }

    /**
     * Pick the closest tile in the area, prefer a canvas-visible tile,
     * fall back to minimap. Must be called on the client thread.
     */
    private WalkPick pickWalkTarget(WorldArea area)
    {
        Player self = client.getLocalPlayer();
        if (self == null) return null;
        WorldPoint here = self.getWorldLocation();
        if (here == null) return null;
        WorldView wv = client.getTopLevelWorldView();
        if (wv == null) return null;

        WalkPick canvasPick = null;
        int canvasBestDist = Integer.MAX_VALUE;
        WalkPick minimapPick = null;
        int minimapBestDist = Integer.MAX_VALUE;

        for (int dx = 0; dx < area.getWidth(); dx++)
        {
            for (int dy = 0; dy < area.getHeight(); dy++)
            {
                WorldPoint t = new WorldPoint(
                    area.getX() + dx, area.getY() + dy, area.getPlane());
                if (t.getPlane() != here.getPlane()) continue;
                int d = Math.max(Math.abs(t.getX() - here.getX()),
                                 Math.abs(t.getY() - here.getY()));

                LocalPoint lp = LocalPoint.fromWorld(wv, t);
                if (lp == null) continue;

                // Try canvas projection first (preferred — more accurate).
                Point cv = Perspective.localToCanvas(client, lp, t.getPlane());
                if (cv != null && inViewport(cv))
                {
                    if (d < canvasBestDist)
                    {
                        canvasBestDist = d;
                        canvasPick = new WalkPick(t, cv, false);
                    }
                    continue;
                }

                // Off-canvas — try minimap.
                Point mp = Perspective.localToMinimap(client, lp);
                if (mp != null)
                {
                    if (d < minimapBestDist)
                    {
                        minimapBestDist = d;
                        minimapPick = new WalkPick(t, mp, true);
                    }
                }
            }
        }

        // If both are available, alternate ~30% minimap for humanization
        // (real players sometimes use the minimap for in-range walks too).
        if (canvasPick != null && minimapPick != null)
        {
            return Math.random() < 0.3 ? minimapPick : canvasPick;
        }
        if (canvasPick != null) return canvasPick;
        if (minimapPick != null) return minimapPick;

        // No tile in the target area is reachable as a single click — the
        // hop is past minimap range (>20 tiles) or behind a wall. Fall back
        // to a BFS-based stepping-stone: walk toward the target along
        // collision-passable tiles, picking the furthest one that projects.
        return pickStepToward(area, here, wv);
    }

    /** BFS depth for the step-toward fallback. The minimap projects tiles
     *  up to ~20 tiles from the player; we cap the BFS at 16 to leave a
     *  margin. The engine's per-click pathfind reaches 25 tiles, so a
     *  16-tile click is comfortably one walk segment. */
    private static final int STEP_DEPTH = 16;

    /** Don't pick a stepping-stone within this many tiles of the player —
     *  we'd waste a click on barely any progress and re-click on the next
     *  tick. Has to be larger than the player's per-tick walk speed (1) and
     *  small enough that single-tile detours around obstacles still
     *  qualify. */
    private static final int STEP_MIN_PROGRESS = 4;

    /**
     * Walk toward {@code target} via a single intermediate stepping-stone.
     * Runs an 8-connected BFS from the player's tile (using engine
     * collision flags via {@link WorldArea#canTravelInDirection}, so it
     * correctly stops at closed gates / walls / water) up to
     * {@link #STEP_DEPTH} steps. Picks the BFS-reachable tile with the
     * minimum Chebyshev distance to {@code target}'s centre that ALSO
     * projects to the minimap, AND strictly improves toward the target
     * compared to the player's current position. Must be called on the
     * client thread.
     *
     * <p>Always uses minimap clicks: a canvas click resolves through
     * whatever's rendered at the picked pixel — trees become "Chop", logs
     * become "Take", NPCs become "Attack". Minimap clicks always route as
     * "Walk here" with no object-action ambiguity. The direct path in
     * {@link #pickWalkTarget} keeps canvas as an option because the
     * destination ITSELF is the click target there; only this fallback
     * picks an intermediate tile, where a wrong-pixel-overlap is the rule
     * not the exception.
     *
     * <p>Returns null when:
     * <ul>
     *   <li>player and target are on different planes (can't BFS across);
     *   <li>BFS finds no reachable tile that strictly improves toTarget
     *       AND projects to the minimap (player walled in / too far).
     * </ul>
     */
    private WalkPick pickStepToward(WorldArea target, WorldPoint here, WorldView wv)
    {
        if (target.getPlane() != here.getPlane()) return null;
        int plane = here.getPlane();
        int tcx = target.getX() + target.getWidth() / 2;
        int tcy = target.getY() + target.getHeight() / 2;
        // Player's current distance to target — any candidate must beat it.
        int hereToTarget = Math.max(Math.abs(here.getX() - tcx),
                                    Math.abs(here.getY() - tcy));

        WalkPick best = null;
        int bestToTarget = hereToTarget;

        // 8-connected — engine collision encodes diagonals via the wall-flag
        // matrix in WorldArea, and players walk diagonally on open tiles.
        final int[] DX = {0, 0, 1, -1, 1, 1, -1, -1};
        final int[] DY = {1, -1, 0, 0, 1, -1, 1, -1};

        HashSet<Long> visited = new HashSet<>();
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        visited.add(packXY(here.getX(), here.getY()));
        queue.add(new int[]{here.getX(), here.getY(), 0});

        while (!queue.isEmpty())
        {
            int[] cur = queue.poll();
            int x = cur[0], y = cur[1], d = cur[2];
            if (d >= STEP_DEPTH) continue;
            WorldArea hereArea = new WorldArea(x, y, 1, 1, plane);
            for (int i = 0; i < 8; i++)
            {
                int nx = x + DX[i], ny = y + DY[i];
                long key = packXY(nx, ny);
                if (visited.contains(key)) continue;
                if (!hereArea.canTravelInDirection(wv, DX[i], DY[i])) continue;
                visited.add(key);
                queue.add(new int[]{nx, ny, d + 1});

                // Reached a new tile — could it be a better click target?
                int progress = Math.max(Math.abs(nx - here.getX()),
                                        Math.abs(ny - here.getY()));
                if (progress < STEP_MIN_PROGRESS) continue;
                int toTarget = Math.max(Math.abs(nx - tcx),
                                        Math.abs(ny - tcy));
                // Strict progress toward target — never click a tile that's
                // farther from target than the player already is.
                if (toTarget >= bestToTarget) continue;

                WorldPoint candTile = new WorldPoint(nx, ny, plane);
                LocalPoint lp = LocalPoint.fromWorld(wv, candTile);
                if (lp == null) continue;
                Point mp = Perspective.localToMinimap(client, lp);
                if (mp == null) continue;

                bestToTarget = toTarget;
                best = new WalkPick(candTile, mp, true);
            }
        }
        return best;
    }

    private static long packXY(int x, int y)
    {
        return ((long) x << 32) | (y & 0xffffffffL);
    }

    private boolean inViewport(Point cp)
    {
        int vx = client.getViewportXOffset();
        int vy = client.getViewportYOffset();
        int vw = client.getViewportWidth();
        int vh = client.getViewportHeight();
        return cp.getX() >= vx && cp.getX() < vx + vw
            && cp.getY() >= vy && cp.getY() < vy + vh;
    }

    /** Find the closest object with the Climb-down verb and invoke it.
     *  Middle-floor staircases expose BOTH Climb-up and Climb-down as
     *  separate menu options, so we can't rely on the engine's default
     *  L-click — invoke the verb's slot directly via menuAction. */
    private void climbDown() throws InterruptedException
    {
        invokeNearestObjectAction("Climb-down");
    }

    /** Same as {@link #climbDown} for Climb-up. */
    private void climbUp() throws InterruptedException
    {
        invokeNearestObjectAction("Climb-up");
    }

    /**
     * Spiral-search for the nearest GameObject whose composition exposes
     * {@code verb}, then dispatch a humanized right-click + menu-pick via
     * {@link HumanizedInputDispatcher#dispatch}. The dispatcher routes the
     * cursor onto the object, opens the right-click menu when the verb is
     * not the L-click default, and selects the matching row.
     *
     * <p>Earlier this called {@link Client#menuAction} directly with
     * {@code GAME_OBJECT_THIRD_OPTION} for "Climb-down" on middle-floor
     * stairs (id=16672). The engine silently dropped that — the menuAction
     * pipeline only honours the L-click slot reliably, deeper slots need
     * the full hover-state-driven flow the dispatcher provides.
     */
    private boolean invokeNearestObjectAction(String verb) throws InterruptedException
    {
        WorldPoint matchedTile = onClientThread(() -> findNearestObjectTileWithVerb(verb));
        if (matchedTile == null) return false;
        ActionRequest req = ActionRequest.builder()
            .kind(ActionRequest.Kind.CLICK_GAME_OBJECT)
            .channel(ActionRequest.Channel.MOUSE)
            .tile(matchedTile)
            .verb(verb)
            .build();
        log.info("script: dispatching '{}' on object at world {}", verb, matchedTile);
        dispatcher.dispatch(req);
        return true;
    }

    /** Spiral-search the loaded scene around the player for a GameObject
     *  whose composition (or impostor composition for multi-state objects)
     *  exposes {@code verb}. Returns the matched object's world tile, or
     *  null if nothing within {@link #OBJECT_SEARCH_RADIUS} matches. Must
     *  be called on the client thread. */
    private WorldPoint findNearestObjectTileWithVerb(String verb)
    {
        Player self = client.getLocalPlayer();
        if (self == null) return null;
        WorldPoint here = self.getWorldLocation();
        if (here == null) return null;
        WorldView wv = client.getTopLevelWorldView();
        if (wv == null) return null;
        Scene scene = wv.getScene();
        if (scene == null) return null;
        Tile[][][] tiles = scene.getTiles();
        int plane = here.getPlane();
        if (tiles == null || plane < 0 || plane >= tiles.length) return null;
        Tile[][] planeTiles = tiles[plane];

        int hereSx = here.getX() - wv.getBaseX();
        int hereSy = here.getY() - wv.getBaseY();

        for (int r = 0; r <= OBJECT_SEARCH_RADIUS; r++)
        {
            for (int dx = -r; dx <= r; dx++)
            {
                for (int dy = -r; dy <= r; dy++)
                {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != r) continue;
                    int sx = hereSx + dx;
                    int sy = hereSy + dy;
                    if (sx < 0 || sy < 0
                        || sx >= planeTiles.length
                        || sy >= planeTiles[0].length) continue;
                    Tile t = planeTiles[sx][sy];
                    if (t == null) continue;
                    GameObject[] gos = t.getGameObjects();
                    if (gos == null) continue;
                    for (GameObject go : gos)
                    {
                        if (go == null) continue;
                        ObjectComposition baseDef = client.getObjectDefinition(go.getId());
                        if (baseDef == null) continue;
                        ObjectComposition def = baseDef;
                        int liveId = go.getId();
                        if (baseDef.getImpostorIds() != null)
                        {
                            try
                            {
                                ObjectComposition imp = baseDef.getImpostor();
                                if (imp != null)
                                {
                                    def = imp;
                                    liveId = imp.getId();
                                }
                            }
                            catch (Throwable ignored) { /* base def fallback */ }
                        }
                        String[] actions = def.getActions();
                        if (actions == null) continue;
                        for (int i = 0; i < actions.length && i < 5; i++)
                        {
                            String a = actions[i];
                            if (a == null) continue;
                            if (!a.equalsIgnoreCase(verb)) continue;
                            LocalPoint lp = go.getLocalLocation();
                            if (lp == null) continue;
                            WorldPoint wp = WorldPoint.fromLocal(client, lp);
                            log.info("script: matched '{}' on object baseId={} liveId={} '{}' (slot {}) at world {}",
                                a, go.getId(), liveId, def.getName(), i, wp);
                            return wp;
                        }
                    }
                }
            }
        }
        log.warn("script: no '{}' action within {} tiles", verb, OBJECT_SEARCH_RADIUS);
        return null;
    }

    /** Outcome of attempting to traverse the pen gate, signalled to the
     *  outer tick so it knows whether a click was dispatched (and the
     *  engine will walk-and-open) or whether the caller still needs to
     *  fire a walk-through click (gate was already open — no click yet). */
    private enum GateResult
    {
        /** A click on the closed gate's "Open" was dispatched; the engine
         *  walks the player to it and opens it. Caller throttles via
         *  {@code lastInteractionAtMs} and waits. */
        CLICKED_OPEN,
        /** No "Open" verb in range, but a "Close" verb is present — the
         *  gate is already open. No click was dispatched; caller must
         *  walk through into the destination area. */
        ALREADY_OPEN,
        /** Neither "Open" nor "Close" within range — we're not actually
         *  near the gate, or the scene hasn't loaded the wall object yet.
         *  Caller falls back to walking toward the destination. */
        NOT_FOUND
    }

    /**
     * Decide what to do at the pen gate. Splits the three outcomes so
     * the caller can react: {@link GateResult#CLICKED_OPEN} fires the
     * gate's pathfind, {@link GateResult#ALREADY_OPEN} requires the
     * caller to walk through (no click was dispatched here),
     * {@link GateResult#NOT_FOUND} means we're not in range yet.
     *
     * <p>Earlier this returned a boolean; the open-but-no-click case was
     * indistinguishable from the open-and-click case, so the player
     * silently stalled at the open gate. This split is the fix.
     */
    private GateResult openGate() throws InterruptedException
    {
        // Try Open first — if the gate is closed this finds it.
        if (clickNearestObjectWithVerb("Open")) return GateResult.CLICKED_OPEN;
        // No "Open" verb in range — maybe the gate is already open
        // (only "Close" verb). Caller must walk through.
        TransportResolver.Match closeMatch = onClientThread(() ->
            findNearestMatchWithVerb("Close"));
        if (closeMatch != null && closeMatch.isSuccess())
        {
            log.info("script: gate already open (Close verb present) — caller walks through");
            return GateResult.ALREADY_OPEN;
        }
        return GateResult.NOT_FOUND;
    }

    /**
     * Spiral-search around the player for any object that exposes
     * {@code verb}. If found, dispatch a click on its hull and return
     * true. Otherwise false.
     */
    private boolean clickNearestObjectWithVerb(String verb) throws InterruptedException
    {
        TransportResolver.Match m = onClientThread(() -> findNearestMatchWithVerb(verb));
        if (m == null || !m.isSuccess()) return false;
        Rectangle b = onClientThread(() -> hullBounds(m));
        if (b == null) return false;
        int cx = b.x + b.width / 2;
        int cy = b.y + b.height / 2;
        dispatcher.clickCanvas(cx, cy);
        return true;
    }

    /**
     * Scan tiles in a square ring outward from the player up to
     * {@link #OBJECT_SEARCH_RADIUS}, returning the first match where
     * an object has the given verb. Must be called on the client
     * thread (reads scene state).
     */
    private TransportResolver.Match findNearestMatchWithVerb(String verb)
    {
        Player self = client.getLocalPlayer();
        if (self == null) return null;
        WorldPoint here = self.getWorldLocation();
        if (here == null) return null;
        for (int r = 0; r <= OBJECT_SEARCH_RADIUS; r++)
        {
            for (int dx = -r; dx <= r; dx++)
            {
                for (int dy = -r; dy <= r; dy++)
                {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != r) continue;
                    WorldPoint p = new WorldPoint(
                        here.getX() + dx, here.getY() + dy, here.getPlane());
                    TransportResolver.Match m = resolver.findTransport(p, verb);
                    if (m.isSuccess()) return m;
                }
            }
        }
        return null;
    }

    /** Bounds of the matched object's clickable hull, on canvas. */
    private Rectangle hullBounds(TransportResolver.Match m)
    {
        if (m.wallObject() != null)
        {
            Shape h = m.wallObject().getConvexHull();
            if (h != null) return h.getBounds();
        }
        if (m.gameObject() != null)
        {
            Shape h = m.gameObject().getConvexHull();
            if (h != null) return h.getBounds();
        }
        if (m.decorativeObject() != null)
        {
            var poly = m.decorativeObject().getCanvasTilePoly();
            if (poly != null) return poly.getBounds();
        }
        if (m.groundObject() != null)
        {
            var poly = m.groundObject().getCanvasTilePoly();
            if (poly != null) return poly.getBounds();
        }
        return null;
    }

    /**
     * Find every "Banker" / "Bank booth" NPC on the same plane, then:
     * <ul>
     *   <li>If any is within {@link #BANK_NEAR_RADIUS} tiles (Chebyshev),
     *       click that one — no point walking past a closer banker.</li>
     *   <li>Otherwise pick one uniformly at random from the candidates so
     *       repeated banking trips don't always click the same booth.</li>
     * </ul>
     * Returns true if a click was dispatched, false if no banker was
     * visible (caller can retry next tick — the player may need to walk
     * closer).
     */
    /** Either an NPC banker or a GameObject bank booth, plus the slot
     *  whose action starts with "Bank" (used to invoke the right menu
     *  action when the default left-click isn't "Bank"). */
    private static final class BankCandidate
    {
        final NPC npc;
        final GameObject go;
        final int slot;        // 0..4, slot containing "Bank" action
        final String actionText;
        final int distance;    // Chebyshev tiles from player

        BankCandidate(NPC n, int slot, String actionText, int dist)
        { this.npc = n; this.go = null; this.slot = slot; this.actionText = actionText; this.distance = dist; }
        BankCandidate(GameObject g, int slot, String actionText, int dist)
        { this.npc = null; this.go = g; this.slot = slot; this.actionText = actionText; this.distance = dist; }
    }

    private boolean clickRandomBooth() throws InterruptedException
    {
        BankCandidate pick = onClientThread(this::findBankCandidate);
        if (pick == null) return false;
        if (pick.npc != null)
        {
            // Banker NPC — default L-click is "Talk-to". Use menuAction.
            return onClientThread(() -> {
                MenuAction ma = npcOptionForSlot(pick.slot);
                if (ma == null) return false;
                log.info("script: invoking '{}' on banker {} (slot {})",
                    pick.actionText, pick.npc.getName(), pick.slot);
                client.menuAction(0, 0, ma, pick.npc.getIndex(), -1,
                    pick.actionText, pick.npc.getName());
                return true;
            });
        }
        // Bank booth GameObject — default L-click IS "Bank Bank booth"
        // (engine picks "Bank" as default even though it's slot 1). So
        // a plain hull click invokes the right action.
        Rectangle b = onClientThread(() -> {
            Shape h = pick.go.getConvexHull();
            return h == null ? null : h.getBounds();
        });
        if (b == null)
        {
            log.warn("script: bank booth at {} has no convex hull",
                pick.go.getWorldLocation());
            return false;
        }
        int cx = b.x + b.width / 2;
        int cy = b.y + b.height / 2;
        log.info("script: clicking bank booth (id={}) hull center ({},{})",
            pick.go.getId(), cx, cy);
        dispatcher.clickCanvas(cx, cy);
        return true;
    }

    /**
     * Collect every banker (NPC) and every bank booth (GameObject) that's
     * within {@link #OBJECT_SEARCH_RADIUS} tiles on the player's plane.
     * If any is at Chebyshev distance ≤ {@link #BANK_NEAR_RADIUS}, return
     * that one deterministically. Otherwise pick uniformly at random.
     * Must be called on the client thread (reads scene + NPC state).
     */
    private BankCandidate findBankCandidate()
    {
        WorldView wv = client.getTopLevelWorldView();
        if (wv == null) return null;
        Player self = client.getLocalPlayer();
        if (self == null) return null;
        WorldPoint here = self.getWorldLocation();
        if (here == null) return null;
        int plane = here.getPlane();

        List<BankCandidate> candidates = new ArrayList<>();
        BankCandidate adjacent = null;

        // 1) NPCs (Bankers, occasionally an NPC named "Bank booth").
        for (NPC npc : wv.npcs())
        {
            if (npc == null) continue;
            String name = npc.getName();
            if (name == null) continue;
            if (!name.equalsIgnoreCase("Banker")
                && !name.equalsIgnoreCase("Bank booth")) continue;
            WorldPoint loc = npc.getWorldLocation();
            if (loc == null || loc.getPlane() != plane) continue;
            NPCComposition def = npc.getComposition();
            if (def == null) continue;
            String[] actions = def.getActions();
            if (actions == null) continue;
            int slot = bankActionSlot(actions);
            if (slot < 0) continue;
            int cheb = Math.max(
                Math.abs(loc.getX() - here.getX()),
                Math.abs(loc.getY() - here.getY()));
            if (cheb > OBJECT_SEARCH_RADIUS) continue;
            BankCandidate c = new BankCandidate(npc, slot, actions[slot], cheb);
            if (cheb <= BANK_NEAR_RADIUS
                && (adjacent == null || cheb < adjacent.distance))
                adjacent = c;
            candidates.add(c);
        }

        // 2) Scene GameObjects (the actual Bank booth scenery — multiple
        //    object IDs exist, so we match by name + Bank action, not id).
        Scene scene = wv.getScene();
        if (scene != null)
        {
            Tile[][][] tiles = scene.getTiles();
            if (tiles != null && plane >= 0 && plane < tiles.length)
            {
                Tile[][] planeTiles = tiles[plane];
                int hereSx = here.getX() - wv.getBaseX();
                int hereSy = here.getY() - wv.getBaseY();
                int loSx = Math.max(0, hereSx - OBJECT_SEARCH_RADIUS);
                int loSy = Math.max(0, hereSy - OBJECT_SEARCH_RADIUS);
                int hiSx = Math.min(planeTiles.length - 1, hereSx + OBJECT_SEARCH_RADIUS);
                int hiSy = Math.min(planeTiles[0].length - 1, hereSy + OBJECT_SEARCH_RADIUS);
                for (int sx = loSx; sx <= hiSx; sx++)
                {
                    for (int sy = loSy; sy <= hiSy; sy++)
                    {
                        Tile t = planeTiles[sx][sy];
                        if (t == null) continue;
                        GameObject[] gos = t.getGameObjects();
                        if (gos == null) continue;
                        for (GameObject go : gos)
                        {
                            if (go == null) continue;
                            ObjectComposition baseDef = client.getObjectDefinition(go.getId());
                            if (baseDef == null) continue;
                            ObjectComposition def = baseDef;
                            if (baseDef.getImpostorIds() != null)
                            {
                                try
                                {
                                    ObjectComposition imp = baseDef.getImpostor();
                                    if (imp != null) def = imp;
                                }
                                catch (Throwable ignored) { /* fall back */ }
                            }
                            String name = def.getName();
                            String[] actions = def.getActions();
                            if (actions == null) continue;
                            int slot = bankActionSlot(actions);
                            if (slot < 0) continue;
                            // Optional: require name to look bank-ish so we
                            // don't grab some unrelated Bank-action object.
                            if (name != null
                                && !name.toLowerCase().contains("bank")) continue;
                            WorldPoint loc = go.getWorldLocation();
                            if (loc == null) continue;
                            int cheb = Math.max(
                                Math.abs(loc.getX() - here.getX()),
                                Math.abs(loc.getY() - here.getY()));
                            if (cheb > OBJECT_SEARCH_RADIUS) continue;
                            BankCandidate c = new BankCandidate(go, slot, actions[slot], cheb);
                            if (cheb <= BANK_NEAR_RADIUS
                                && (adjacent == null || cheb < adjacent.distance))
                                adjacent = c;
                            candidates.add(c);
                        }
                    }
                }
            }
        }

        if (adjacent != null)
        {
            log.info("script: bank candidate adjacent (dist={}, kind={})",
                adjacent.distance, adjacent.npc != null ? "NPC" : "GameObject");
            return adjacent;
        }
        if (candidates.isEmpty())
        {
            log.warn("script: no bank candidates within {} tiles",
                OBJECT_SEARCH_RADIUS);
            return null;
        }
        BankCandidate randomPick = candidates.get((int)(Math.random() * candidates.size()));
        log.info("script: random bank candidate (n={}, picked dist={}, kind={})",
            candidates.size(), randomPick.distance,
            randomPick.npc != null ? "NPC" : "GameObject");
        return randomPick;
    }

    /** First slot in {@code actions} whose text starts with "Bank"
     *  (case-insensitive). -1 if no slot has a Bank action. */
    private static int bankActionSlot(String[] actions)
    {
        for (int i = 0; i < actions.length && i < 5; i++)
        {
            String a = actions[i];
            if (a == null) continue;
            if (a.toLowerCase().startsWith("bank")) return i;
        }
        return -1;
    }

    private static MenuAction npcOptionForSlot(int slot)
    {
        switch (slot)
        {
            case 0:  return MenuAction.NPC_FIRST_OPTION;
            case 1:  return MenuAction.NPC_SECOND_OPTION;
            case 2:  return MenuAction.NPC_THIRD_OPTION;
            case 3:  return MenuAction.NPC_FOURTH_OPTION;
            case 4:  return MenuAction.NPC_FIFTH_OPTION;
            default: return null;
        }
    }

    /**
     * Run a callable on the client thread and block this (worker)
     * thread until it completes. Returns null on interruption.
     */
    private <T> T onClientThread(Supplier<T> work)
    {
        AtomicReference<T> result = new AtomicReference<>();
        java.util.concurrent.CountDownLatch latch =
            new java.util.concurrent.CountDownLatch(1);
        clientThread.invokeLater(() -> {
            try { result.set(work.get()); }
            catch (Throwable ex) { log.warn("clientThread work failed", ex); }
            finally { latch.countDown(); }
        });
        try { latch.await(); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); return null; }
        return result.get();
    }

    /** Human-ish sleep duration between ticks (300-700ms). */
    private static long humanCadence()
    {
        return 300L + (long)(Math.random() * 400);
    }
}
