package net.runelite.client.plugins.recorder.scripts;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
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
import net.runelite.client.plugins.recorder.nav.BehaviorMode;
import net.runelite.client.plugins.recorder.nav.NavRequest;
import net.runelite.client.plugins.recorder.nav.NavStatus;
import net.runelite.client.plugins.recorder.nav.Navigator;
import net.runelite.client.plugins.recorder.nav.NavigatorFactory;
import net.runelite.client.plugins.recorder.trail.TrailRegistry;
import net.runelite.client.plugins.recorder.transport.TransportResolver;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import net.runelite.client.sequence.dispatch.SequenceSleep;
import net.runelite.client.sequence.internal.ActionRequest;

/**
 * Chicken farm bot, V3 — drives the WALKING phases via the {@link
 * Navigator} interface. With V1 selected (the default) the Navigator
 * replays recorded trails by name; with V2 selected the Navigator
 * plans live from WorldMemory. The script depends on the interface,
 * never the concrete walker, so flipping the panel switch swaps
 * implementations without script changes.
 *
 * <p>The user records two trails once ({@code lumby_bank_to_pen} and
 * {@code pen_to_lumby_bank}); the Navigator resolves them. BANKING
 * and AT_PEN reuse V2 unchanged: same {@link BankInteraction}
 * primitives, same {@link ChickenCombatLoop}.
 *
 * <p>If the Navigator returns FAILED on a walk leg (trail missing,
 * walker stuck, etc.), V3 surfaces a status message and aborts.
 */
@Slf4j
public final class ChickenFarmV3Script
{
    public static final String OUTBOUND_TRAIL_NAME = "lumby_bank_to_pen";
    public static final String RETURN_TRAIL_NAME   = "pen_to_lumby_bank";

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
    private final NavigatorFactory navFactory;
    private final BankInteraction bank;
    private final ChickenCombatLoop combat;
    private final TrainingSession trainingSession;
    private final LogoutHelper logoutHelper;
    private volatile TrainingPlan trainingPlan;
    /** Resolved at {@link #start()} from {@link #navFactory}, so flipping
     *  the panel's V1/V2 selector is honored on the next Start. Held
     *  for the duration of one run; null between runs. */
    private volatile Navigator nav;

    private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);
    private final AtomicReference<String> status = new AtomicReference<>("idle");
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<Thread> worker = new AtomicReference<>();
    private long lastBankActionAtMs;

    // ──── Debug toggles ────
    // Read by the corresponding tick* methods to short-circuit phases
    // for walk-testing without running a full bot cycle. The panel's
    // debug box exposes these; they have no effect on normal Start.
    private final AtomicBoolean skipCombat = new AtomicBoolean(false);
    private final AtomicBoolean skipBanking = new AtomicBoolean(false);
    /** When true, after ARRIVED in any walk leg the script transitions
     *  to IDLE instead of starting the next phase. Useful for "I just
     *  want to test the bank→pen walk in isolation" — flip on, click
     *  Walk → Pen, watch it walk, watch it stop on arrival. */
    private final AtomicBoolean stopAfterArrival = new AtomicBoolean(false);
    /** Phase override for the next start(). Null = decideResume()
     *  picks normally. Setting this lets the debug buttons jump
     *  directly into a specific phase (OUTBOUND or RETURN) without
     *  waiting for resume detection to figure out where the player is. */
    private final AtomicReference<State> forceStartState = new AtomicReference<>(null);

    public ChickenFarmV3Script(Client client, ClientThread clientThread,
                               HumanizedInputDispatcher dispatcher,
                               TrailRegistry registry,
                               NavigatorFactory navFactory)
    {
        this(client, clientThread, dispatcher, registry, null, navFactory);
    }

    /** Full ctor — passes the {@link net.runelite.client.eventbus.EventBus}
     *  through to {@link TrainingSession} so it can subscribe to
     *  {@code StatChanged} events for instant level-up detection. The
     *  shorter ctor above omits it for tests that don't need event-bus wiring. */
    public ChickenFarmV3Script(Client client, ClientThread clientThread,
                               HumanizedInputDispatcher dispatcher,
                               TrailRegistry registry,
                               @javax.annotation.Nullable
                               net.runelite.client.eventbus.EventBus eventBus,
                               NavigatorFactory navFactory)
    {
        this.client = client;
        this.clientThread = clientThread;
        this.dispatcher = dispatcher;
        this.registry = registry;
        this.navFactory = navFactory;
        this.bank = new BankInteraction(client, clientThread, dispatcher);
        this.combat = new ChickenCombatLoop(dispatcher, client, clientThread, PEN_AREA, eventBus);
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

    // ──── Debug accessors ────
    public boolean isSkipCombat() { return skipCombat.get(); }
    public void setSkipCombat(boolean v) { skipCombat.set(v); }
    public boolean isSkipBanking() { return skipBanking.get(); }
    public void setSkipBanking(boolean v) { skipBanking.set(v); }
    public boolean isStopAfterArrival() { return stopAfterArrival.get(); }
    public void setStopAfterArrival(boolean v) { stopAfterArrival.set(v); }

    /** Start the script forced into {@link State#OUTBOUND} on the first
     *  tick. Used by the panel's "Walk → Pen" debug button to bypass
     *  decideResume() and skip the normal phase-detection logic. */
    public void startForcedOutbound()
    {
        forceStartState.set(State.OUTBOUND);
        start();
    }

    /** Start forced into {@link State#RETURN}. Mirror of
     *  {@link #startForcedOutbound} for the "Walk → Bank" debug button. */
    public void startForcedReturn()
    {
        forceStartState.set(State.RETURN);
        start();
    }

    public void start()
    {
        if (!running.compareAndSet(false, true)) return;
        // Resolve which Navigator implementation to drive on this run.
        // Done at start() rather than ctor time so flipping the panel's
        // V1/V2 selector between runs takes effect on the next Start
        // without restarting the plugin.
        nav = navFactory.getNavigator();
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
        State forced = forceStartState.getAndSet(null);
        State decided = forced != null ? forced : decideResume();
        log.info("v3: resume → {} (status: {}) {}", decided, status.get(),
            forced != null ? "[debug forced]" : "");
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

    /** Resolve a trail name to its last recorded tile — V2's planning
     *  destination. Returns null if the trail isn't in the registry
     *  (the script falls back to a V1-only request). */
    private WorldPoint trailEndTile(String trailName)
    {
        net.runelite.client.plugins.recorder.trail.Trail trail = registry.byName(trailName);
        return lastTileOf(trail);
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
        if (outbound && Boolean.TRUE.equals(onClient(() -> InventoryUtil.isInventoryFull(client))))
        {
            status.set("inventory full mid-outbound — switching to RETURN");
            nav.cancel();
            setState(State.RETURN);
            return;
        }

        String trailName = outbound ? OUTBOUND_TRAIL_NAME : RETURN_TRAIL_NAME;
        // Compose request — V1 reads the trail name; V2 plans to the
        // trail's destination tile. Resolving here lets the panel's
        // V1/V2 selector swap implementations without script changes.
        WorldPoint destTile = trailEndTile(trailName);
        NavRequest req = destTile == null
            ? NavRequest.byTrail(trailName, BehaviorMode.VARIED)
            : NavRequest.compose(trailName, destTile, BehaviorMode.VARIED);
        NavStatus st = nav.tick(req);
        status.set("walk: " + (outbound ? "outbound" : "return") + " (" + st + ")");
        switch (st)
        {
            case ARRIVED:
                nav.cancel();
                if (stopAfterArrival.get())
                {
                    log.info("v3: ARRIVED on {} — debug stopAfterArrival on, going IDLE",
                        outbound ? "OUTBOUND" : "RETURN");
                    status.set("walk-test complete (" + (outbound ? "→ pen" : "→ bank") + ")");
                    setState(State.IDLE);
                    running.set(false);
                    break;
                }
                if (outbound && skipCombat.get())
                {
                    log.info("v3: ARRIVED on OUTBOUND — debug skipCombat on, looping back to RETURN");
                    status.set("walk-test: at pen, skipping combat → RETURN");
                    setState(State.RETURN);
                    break;
                }
                if (!outbound && skipBanking.get())
                {
                    log.info("v3: ARRIVED on RETURN — debug skipBanking on, looping back to OUTBOUND");
                    status.set("walk-test: at bank, skipping banking → OUTBOUND");
                    setState(State.OUTBOUND);
                    break;
                }
                setState(outbound ? State.AT_PEN : State.BANKING);
                break;
            case FAILED:
                log.warn("v3: nav FAILED on {} (trail '{}') — aborting",
                    outbound ? "OUTBOUND" : "RETURN", trailName);
                nav.cancel();
                setState(State.ABORTED);
                break;
            case RUNNING:
            case IDLE:
            default:
                break;
        }
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
            // The combat loop aborts after ~36s of failed selections, then
            // exits to IDLE. If the player drifted outside PEN_AREA during
            // the previous burst (chicken died at the pen edge, player
            // followed and got displaced south of the fence), the next
            // SELECTING tick will face the same camera/aim failure mode
            // from the same bad spot. Re-anchor first — walk to a random
            // tile inside the pen — then start combat from a known-good
            // position.
            WorldPoint here = onClient(() -> {
                Player p = client.getLocalPlayer();
                return p == null ? null : p.getWorldLocation();
            });
            if (here != null && !areaContains(PEN_AREA, here))
            {
                log.info("v3: combat IDLE with player at {} outside PEN_AREA — re-anchoring", here);
                if (!recoverIntoPen())
                {
                    log.warn("v3: re-anchor into pen failed — aborting");
                    status.set("re-anchor failed — aborting");
                    setState(State.ABORTED);
                    return;
                }
            }
            status.set("starting combat");
            combat.start();
        }
        else
        {
            status.set("combat: " + combat.latestStatus()
                + " (kills=" + combat.killCount() + ")");
        }
    }

    /** Walk back into {@link #PEN_AREA} after the combat loop aborted with
     *  the player drifted outside. Per attempt: scan for a closed gate
     *  within 3 tiles and open it (no-op when no gate is closed), pick
     *  a random pen-interior tile, dispatch a
     *  {@link ActionRequest.Kind#WALK} (the dispatcher handles minimap
     *  fallback), and poll the player position until they enter the pen.
     *  The gate scan is what lets us recover when the engine's minimap
     *  pathfinding stops at a closed gate — without it the same walk
     *  fails forever.
     *
     *  <p>Returns {@code true} once the player is inside the pen,
     *  {@code false} on overall timeout (caller aborts the script). */
    boolean recoverIntoPen() throws InterruptedException
    {
        final long deadline = System.currentTimeMillis() + 25_000L;
        int attempts = 0;
        while (System.currentTimeMillis() < deadline && attempts < 4)
        {
            attempts++;
            WorldPoint here = onClient(() -> {
                Player p = client.getLocalPlayer();
                return p == null ? null : p.getWorldLocation();
            });
            if (here == null) return false;
            if (areaContains(PEN_AREA, here))
            {
                log.info("v3: recovered into pen at {} (attempt {})", here, attempts);
                return true;
            }

            // Aggro short-circuit: if a chicken is currently targeting us,
            // abandon the recovery walk and let combat.start() adopt the
            // fight via ChickenCombatLoop.detectActiveChickenCombat. Walking
            // away from an active aggro just leaves us out-of-pen with damage
            // incoming and the same chicken pursuing — better to fight it
            // where we stand and recover after.
            if (Boolean.TRUE.equals(onClient(this::aChickenIsAttackingUs)))
            {
                log.info("v3: re-anchor — chicken aggroed on us at {}, returning so combat can adopt", here);
                status.set("re-anchor → adopting aggro");
                return true;
            }

            // Open any closed gate within reach BEFORE the walk —
            // TransportResolver.findTransport(tile, "Open") only matches
            // closed gates (open ones expose "Close"), so this is a no-op
            // when no gate is closed nearby. Saves a wasted ~6s walk
            // attempt when we're standing right next to a closed gate.
            tryOpenNearbyGate(here);

            WorldPoint target = randomPenTile();
            log.info("v3: re-anchor attempt {} — walking to {} from {}", attempts, target, here);
            status.set("re-anchor → " + target + " (try " + attempts + ")");
            ActionRequest walk = ActionRequest.builder()
                .kind(ActionRequest.Kind.WALK)
                .channel(ActionRequest.Channel.MOUSE)
                .tile(target)
                .build();
            dispatcher.dispatch(walk);
            dispatcher.awaitIdle(3000L);

            long until = Math.min(System.currentTimeMillis() + 6_000L, deadline);
            while (System.currentTimeMillis() < until)
            {
                WorldPoint p = onClient(() -> {
                    Player lp = client.getLocalPlayer();
                    return lp == null ? null : lp.getWorldLocation();
                });
                if (p != null && areaContains(PEN_AREA, p))
                {
                    log.info("v3: recovered into pen at {} after walk attempt {}", p, attempts);
                    return true;
                }
                SequenceSleep.sleep(client, 400);
            }
        }
        return false;
    }

    /** Pick a random tile inside {@link #PEN_AREA}, staying one tile inside
     *  the bounds so the engine's minimap walk doesn't path us onto the
     *  fence itself. Visible for tests. */
    WorldPoint randomPenTile()
    {
        // Interior tiles span PEN_AREA + 1 inset on each edge: x in
        // [getX()+1, getX()+getWidth()-2] (inclusive). Width-2 candidates,
        // so the random offset is [0, width-3].
        int interiorW = Math.max(1, PEN_AREA.getWidth() - 2);
        int interiorH = Math.max(1, PEN_AREA.getHeight() - 2);
        int xOff = interiorW <= 1 ? 0 : java.util.concurrent.ThreadLocalRandom.current().nextInt(interiorW);
        int yOff = interiorH <= 1 ? 0 : java.util.concurrent.ThreadLocalRandom.current().nextInt(interiorH);
        return new WorldPoint(PEN_AREA.getX() + 1 + xOff,
            PEN_AREA.getY() + 1 + yOff,
            PEN_AREA.getPlane());
    }

    /** Client-thread scan: is any "Chicken" NPC currently interacting with
     *  the local player? Mirrors the loop in
     *  {@link net.runelite.client.plugins.recorder.combat.ChickenCombatLoop}
     *  so {@code recoverIntoPen} can see the same aggro the combat loop
     *  would adopt on its first SELECTING tick. */
    private Boolean aChickenIsAttackingUs()
    {
        Player self = client.getLocalPlayer();
        if (self == null) return Boolean.FALSE;
        for (NPC npc : client.getTopLevelWorldView().npcs())
        {
            if (npc == null || npc.getInteracting() != self) continue;
            NPCComposition c = npc.getComposition();
            String name = c == null ? npc.getName() : c.getName();
            if (name == null) continue;
            if (ChickenCombatLoop.CHICKEN_NAME.equalsIgnoreCase(
                name.replaceAll("<[^>]+>", "").trim()))
            {
                return Boolean.TRUE;
            }
        }
        return Boolean.FALSE;
    }

    /** Look at every tile within 3 of the player for a game object whose
     *  composition advertises an "Open" verb (i.e. a closed gate / door),
     *  and dispatch an "Open" click on the first match. Returns true if a
     *  gate was clicked. The 800ms post-click sleep that gives the engine
     *  time to play the open animation lives in {@link #recoverIntoPen}. */
    private boolean tryOpenNearbyGate(WorldPoint here) throws InterruptedException
    {
        TransportResolver resolver = new TransportResolver(client);
        for (int dx = -3; dx <= 3; dx++)
        {
            for (int dy = -3; dy <= 3; dy++)
            {
                final int x = here.getX() + dx;
                final int y = here.getY() + dy;
                final int plane = here.getPlane();
                TransportResolver.Match m = onClient(() ->
                    resolver.findTransport(new WorldPoint(x, y, plane), "Open"));
                if (m != null && m.matchedVerb() != null)
                {
                    WorldPoint gateTile = new WorldPoint(x, y, plane);
                    log.info("v3: re-anchor — opening gate at {} (objectId={})",
                        gateTile, m.matchedObjectId());
                    status.set("re-anchor → opening gate");
                    ActionRequest req = ActionRequest.builder()
                        .kind(ActionRequest.Kind.CLICK_GAME_OBJECT)
                        .channel(ActionRequest.Channel.MOUSE)
                        .tile(gateTile)
                        .verb("Open")
                        .build();
                    dispatcher.dispatch(req);
                    dispatcher.awaitIdle(3000L);
                    SequenceSleep.sleep(client, 800);
                    return true;
                }
            }
        }
        return false;
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
