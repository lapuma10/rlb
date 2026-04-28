package net.runelite.client.plugins.recorder.scripts;

import java.awt.Rectangle;
import java.awt.Shape;
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
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.recorder.combat.ChickenCombatLoop;
import net.runelite.client.plugins.recorder.farm.BankInteraction;
import net.runelite.client.plugins.recorder.farm.InventoryUtil;
import net.runelite.client.plugins.recorder.transport.TransportResolver;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;

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
    /** Throttle for bank booth clicks — bank widget takes ~600ms to load
     *  after click, so we wait ≥ 3s between booth clicks before retrying. */
    private long lastBankClickAtMs;

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

    private void tickBanking() throws InterruptedException
    {
        // 1. If we're not at the bank tiles yet, walk there (plane 2).
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
        // 2. If bank widget not open, click a bank booth / banker.
        boolean open = onClientThread(bank::isBankOpen);
        if (!open)
        {
            // Throttle bank clicks: ≥ 3s between attempts so the bank
            // widget has time to open and we don't spam.
            long now = System.currentTimeMillis();
            long sinceClick = lastBankClickAtMs == 0
                ? Long.MAX_VALUE : now - lastBankClickAtMs;
            if (sinceClick < 3000) return;
            status.set("clicking bank booth");
            if (clickRandomBooth()) lastBankClickAtMs = now;
            return;
        }
        // 3. Inventory empty? close bank, advance to OUTBOUND.
        boolean empty = onClientThread(() ->
            InventoryUtil.freeSlotCount(client) >= InventoryUtil.INVENTORY_SIZE);
        if (empty)
        {
            status.set("inventory empty — heading to pen");
            bank.closeBank();
            setState(State.OUTBOUND);
            return;
        }
        // 4. Deposit inventory.
        status.set("depositing inventory");
        bank.clickDepositInventory();
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
                status.set("climb down (p2 → p1)");
                climbDown();
                return;

            case 1:
                if (!playerInArea(STAIRS_AREA_P1))
                {
                    status.set("walk → stairs (p1)");
                    if (advanceWalk(STAIRS_AREA_P1) == WalkResult.STUCK)
                        setState(State.ABORTED);
                    return;
                }
                status.set("climb down (p1 → p0)");
                climbDown();
                return;

            case 0:
                if (areaContains(PEN_AREA, here))
                {
                    status.set("arrived at pen");
                    setState(State.AT_PEN);
                    return;
                }
                int idx = currentLandmarkIndex(OUTBOUND_PATH_P0, here);
                if (idx == OUTBOUND_PATH_P0.length - 1)
                {
                    status.set("opening gate (or already open)");
                    if (!openGate())
                    {
                        // No gate verb visible — try to walk into the pen
                        // (the engine routes around what's blocking).
                        if (advanceWalk(PEN_AREA) == WalkResult.STUCK)
                            setState(State.ABORTED);
                    }
                    return;
                }
                WorldArea next = idx >= 0
                    ? OUTBOUND_PATH_P0[idx + 1]
                    : closestLandmark(OUTBOUND_PATH_P0, here);
                status.set(idx >= 0
                    ? "walk → landmark #" + (idx + 1)
                    : "walk → nearest landmark");
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
                    status.set("opening gate to leave pen");
                    if (!openGate())
                    {
                        if (advanceWalk(PEN_APPROACH) == WalkResult.STUCK)
                            setState(State.ABORTED);
                    }
                    return;
                }
                int rIdx = currentLandmarkIndex(OUTBOUND_PATH_P0, here);
                if (rIdx == 0)
                {
                    status.set("climb up (p0 → p1)");
                    climbUp();
                    return;
                }
                WorldArea prev = rIdx > 0
                    ? OUTBOUND_PATH_P0[rIdx - 1]
                    : closestLandmark(OUTBOUND_PATH_P0, here);
                status.set(rIdx > 0
                    ? "walk back → landmark #" + (rIdx - 1)
                    : "walk back → nearest landmark");
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
                status.set("climb up (p1 → p2)");
                climbUp();
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
        // Player is still walking from previous click while sinceMove
        // is small. Don't re-click during that window.
        boolean stillWalking = sinceMove < 1500;
        boolean recentClick = sinceClick < 1500;

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
        lastBankClickAtMs = 0L;
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

    /** Index of the first landmark in {@code path} the point is inside,
     *  or -1 if the point isn't in any of them. */
    private static int currentLandmarkIndex(WorldArea[] path, WorldPoint p)
    {
        for (int i = 0; i < path.length; i++)
        {
            if (areaContains(path[i], p)) return i;
        }
        return -1;
    }

    /** Landmark whose bbox center is closest to {@code p} by Chebyshev
     *  distance. Used to converge the bot back onto the path when it
     *  starts mid-route or wanders off. Never null because we always
     *  pass a non-empty {@code path}. */
    private static WorldArea closestLandmark(WorldArea[] path, WorldPoint p)
    {
        WorldArea best = path[0];
        int bestDist = Integer.MAX_VALUE;
        for (WorldArea a : path)
        {
            if (a.getPlane() != p.getPlane()) continue;
            int cx = a.getX() + a.getWidth() / 2;
            int cy = a.getY() + a.getHeight() / 2;
            int d = Math.max(Math.abs(p.getX() - cx), Math.abs(p.getY() - cy));
            if (d < bestDist) { bestDist = d; best = a; }
        }
        return best;
    }

    /**
     * Click a random walkable tile inside {@code area} to walk-here.
     * Picks tiles from the area's bounds, filtered for current plane +
     * canvas-projectability. No-op if no tile projects (e.g. the entire
     * area is off-screen).
     */
    private void walkTo(WorldArea area) throws InterruptedException
    {
        WorldPoint pick = onClientThread(() -> {
            Player self = client.getLocalPlayer();
            if (self == null) return null;
            WorldPoint here = self.getWorldLocation();
            if (here == null) return null;
            // Try the area's center first; if it doesn't project, scan
            // outward. The chicken-farm-bot's RouteWalker uses random
            // sampling; for the script we use deterministic center +
            // fallback so logs are easier to read.
            int cx = area.getX() + area.getWidth() / 2;
            int cy = area.getY() + area.getHeight() / 2;
            for (int rx = 0; rx < area.getWidth(); rx++)
            {
                for (int ry = 0; ry < area.getHeight(); ry++)
                {
                    WorldPoint candidate = new WorldPoint(
                        cx + ((rx % 2 == 0) ? rx / 2 : -(rx / 2 + 1)),
                        cy + ((ry % 2 == 0) ? ry / 2 : -(ry / 2 + 1)),
                        area.getPlane());
                    if (candidate.getPlane() != here.getPlane()) continue;
                    if (projects(candidate)) return candidate;
                }
            }
            return null;
        });
        if (pick == null) return;
        clickWorldPoint(pick);
    }

    /** Click the tile, projected to canvas. Off-thread dispatch. */
    private void clickWorldPoint(WorldPoint wp) throws InterruptedException
    {
        Point cp = onClientThread(() -> projectCenter(wp));
        if (cp == null) return;
        dispatcher.clickCanvas(cp.getX(), cp.getY());
    }

    /** Project a world tile to canvas center, or return null. */
    private Point projectCenter(WorldPoint wp)
    {
        WorldView wv = client.getTopLevelWorldView();
        if (wv == null) return null;
        LocalPoint lp = LocalPoint.fromWorld(wv, wp);
        if (lp == null) return null;
        return Perspective.localToCanvas(client, lp, wp.getPlane());
    }

    /** True if the tile projects onto the visible canvas viewport. */
    private boolean projects(WorldPoint wp)
    {
        Point cp = projectCenter(wp);
        if (cp == null) return false;
        int vx = client.getViewportXOffset();
        int vy = client.getViewportYOffset();
        int vw = client.getViewportWidth();
        int vh = client.getViewportHeight();
        return cp.getX() >= vx && cp.getX() < vx + vw
            && cp.getY() >= vy && cp.getY() < vy + vh;
    }

    /** Find the closest staircase with the Climb-down verb and click it. */
    private void climbDown() throws InterruptedException
    {
        clickNearestObjectWithVerb("Climb-down");
    }

    /** Find the closest staircase with the Climb-up verb and click it. */
    private void climbUp() throws InterruptedException
    {
        clickNearestObjectWithVerb("Climb-up");
    }

    /**
     * Open the gate at the chicken pen. If a gate within range has the
     * "Open" verb → click it (returns true). If it only has "Close"
     * (already open) → no click, walk through (returns true). If no
     * gate is found → false (caller decides what to do).
     */
    private boolean openGate() throws InterruptedException
    {
        // Try Open first — if the gate is closed this finds it.
        if (clickNearestObjectWithVerb("Open")) return true;
        // No "Open" verb in range — maybe the gate is already open
        // (only "Close" verb). Treat that as "walk through".
        TransportResolver.Match closeMatch = onClientThread(() ->
            findNearestMatchWithVerb("Close"));
        if (closeMatch != null && closeMatch.isSuccess())
        {
            log.info("script: gate already open (Close verb present) — walking through");
            return true;
        }
        return false;
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
                            ObjectComposition def = client.getObjectDefinition(go.getId());
                            if (def == null) continue;
                            String name = def.getName();
                            // Some object defs return "null" string; filter
                            // by Bank action presence as the strict check.
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
