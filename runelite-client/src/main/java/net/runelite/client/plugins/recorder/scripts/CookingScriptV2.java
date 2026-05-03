package net.runelite.client.plugins.recorder.scripts;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.recorder.cook.CookingFood;
import net.runelite.client.plugins.recorder.cook.CookingInteraction;
import net.runelite.client.plugins.recorder.cook.CookingLocation;
import net.runelite.client.plugins.recorder.farm.BankInteraction;
import net.runelite.client.plugins.recorder.transport.TransportResolver;
import net.runelite.client.plugins.recorder.walker.PathSpec;
import net.runelite.client.plugins.recorder.walker.UniversalWalker;
import net.runelite.client.plugins.recorder.widget.SidebarTab;
import net.runelite.client.plugins.recorder.widget.SidebarTabActions;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import net.runelite.client.sequence.dispatch.SequenceSleep;

/**
 * Cooking V2 — same FSM and dispatch primitives as {@link CookingScript},
 * with four targeted human-factor changes wired in:
 *
 * <ol>
 *   <li><b>Random walk target per trip.</b> Instead of always walking
 *       to the bbox centre of {@code bankArea} / {@code cookArea} (which
 *       projects to the same screen pixel each loop and screams bot),
 *       V2 picks a random tile inside the area at trip start and builds
 *       a one-leg {@link PathSpec} pointing at that tile. Different tile
 *       → different camera angle → different click pixel for the booth /
 *       logs / fire on that trip.</li>
 *   <li><b>Varied log-pile selection.</b>
 *       {@link CookingInteraction#findGroundLogsVaried} with
 *       {@code jitter=2} picks uniformly at random among log spawns
 *       within 2 Chebyshev tiles of the closest match. V1 always lit
 *       the strict-closest pile, so the fire tile was constant trip
 *       after trip.</li>
 *   <li><b>Weighted-random bank booth.</b>
 *       {@link BankInteraction#tryClickBankBoothVaried} with adjacency
 *       bias 0.65 — the closest booth wins ~65% of the time, the rest
 *       split among other in-range booths. V1's
 *       {@code tryClickBankBoothRandom} short-circuits on adjacent so
 *       the same booth was clicked every trip.</li>
 *   <li><b>Explicit fire-died state clear.</b> When the fire dies during
 *       cooking, V1 transitioned to {@code LIGHTING_FIRE} but
 *       {@code setState} preserved {@code litFireTile} +
 *       {@code lightDispatchedAtMs} (since the preserve-set is
 *       {@code LIGHTING_FIRE} | {@code COOKING}). On the very next tick
 *       {@code tickLightingFire} entered the "waiting on lit fire" branch
 *       with {@code lightDispatchedAtMs == 0}, which trips the no-anim
 *       check with {@code now - 0} as the elapsed time and logs an
 *       absurd "no firemaking anim seen after 1.7e12 ms" line. V2
 *       clears the fire-state fields explicitly before transitioning,
 *       so {@code tickLightingFire} starts cleanly at the "find logs"
 *       branch.</li>
 * </ol>
 *
 * <p>V2 keeps the BANKING_LEGACY flow (deposit → withdraw → close) as
 * the only banking path — no sequence-engine branch — to keep the file
 * compact. Every other gate (Bank PIN, container-load grace,
 * COOK_PACE_MS, COOK_BATCH_SETTLE_MS, COOK_STUCK_MS, level-up dismiss,
 * inventory-tab restore, walker-stuck cap, cook-stuck cap) is preserved
 * verbatim from V1 so we don't regress any of the carefully-tuned
 * timing.
 *
 * <p>Threading: identical to V1 — one daemon worker tick loop, every
 * client-state read marshalled via {@link ClientThread#invokeLater},
 * clicks dispatched through the injected
 * {@link HumanizedInputDispatcher}. The dispatcher is a separate
 * instance from V1's; the panel enforces mutual exclusion at the
 * Start-button level so they never click simultaneously.
 */
@Slf4j
public final class CookingScriptV2
{
    private static final long TICK_MS = 600;
    private static final long BANK_PACE_MS = 2000;
    private static final long LIGHT_TIMEOUT_MS = 30_000;
    private static final long FIRE_DEATH_PAUSE_MIN_MS = 2_000;
    private static final long FIRE_DEATH_PAUSE_MAX_MS = 10_000;
    private static final long COOK_BATCH_SETTLE_MS = 5_000;
    private static final long COOK_STUCK_MS = 30_000;
    private static final long COOK_PACE_MS = 1500;
    private static final long LEVEL_UP_DISMISS_MIN_MS = 3_000;
    private static final long LEVEL_UP_DISMISS_MAX_MS = 34_000;
    private static final long LIGHT_NO_ANIM_TIMEOUT_MS = 4_000;
    /** Variance applied by V2 to keep the loop spatially varied. */
    private static final int LOG_JITTER_TILES = 2;
    /** Probability mass given to the closest bank booth in V2's varied
     *  picker. 0.65 means the closest booth wins ~65% of trips, the
     *  rest split among other in-range booths. Tuned by feel: at 0.5
     *  we wandered to far booths too often (visible re-walk to a
     *  farther tile), at 0.85 the variation didn't read as variation. */
    private static final double BOOTH_ADJACENCY_BIAS = 0.65;
    /** How often the camera gets a small nudge between trips. Counted
     *  in arrived-walk-legs (one trip = WALK_TO_BANK arrival +
     *  WALK_TO_COOK arrival = 2 legs). Random in [min, max]. Real
     *  players adjust the camera while moving; a turret-camera over
     *  30 minutes is a textbook bot tell. */
    private static final int CAMERA_NUDGE_INTERVAL_MIN = 2;
    private static final int CAMERA_NUDGE_INTERVAL_MAX = 6;
    /** How often to insert a "human-AFK" micro-idle. Every 4-9 walk-leg
     *  arrivals the script holds for 3-12s, simulating a player reading
     *  chat / scratching their nose / glancing away. Below 4 it slows
     *  the cook rate too much; above 9 the temporal signature is still
     *  near-uniform. Pause length tuned to be visible to a watcher
     *  without obviously stalling the loop. */
    private static final int IDLE_INTERVAL_MIN = 4;
    private static final int IDLE_INTERVAL_MAX = 9;
    private static final long IDLE_PAUSE_MIN_MS = 3_000;
    private static final long IDLE_PAUSE_MAX_MS = 12_000;

    public enum State
    {
        IDLE,
        BANKING,
        WALK_TO_COOK,
        LIGHTING_FIRE,
        COOKING,
        WALK_TO_BANK,
        ABORTED
    }

    private final Client client;
    private final ClientThread clientThread;
    private final HumanizedInputDispatcher dispatcher;
    private final UniversalWalker walker;
    private final BankInteraction bank;
    private final CookingInteraction cook;
    private final SidebarTabActions sidebarTabs;

    private final AtomicReference<CookingLocation> location = new AtomicReference<>();
    private final AtomicInteger rawFoodId = new AtomicInteger(0);
    private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);
    private final AtomicReference<String> status = new AtomicReference<>("idle");
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<Thread> worker = new AtomicReference<>();

    private final AtomicInteger cookedCount = new AtomicInteger(0);
    private final AtomicInteger burntCount  = new AtomicInteger(0);

    // Per-trip cached PathSpecs — built fresh on each leg so the random
    // tile picked at leg-start is reused across subsequent walker.tick
    // calls until the leg ends. Cleared in setState when transitioning
    // out of the relevant leg.
    private PathSpec tripBankPath;
    private PathSpec tripCookPath;

    private long lastBankActionAtMs;
    private long lastCookActionAtMs;
    private long lastInventoryChangeAtMs;
    private int  lastRawCount = -1;
    private WorldPoint litFireTile;
    private long lightDispatchedAtMs;
    private boolean tallyDoneThisVisit;
    private int walkerStuckCount;
    private int cookStuckCount;
    private long lastRawDecreaseAtMs;
    private long bankOpenedAtMs;
    private int  bankFailCount;
    private int  cookMenuConfirmAttempts;
    private int  tripStartCooked;
    private int  tripStartBurnt;
    private long lightingBackoffUntilMs;
    private boolean seenFiremakingAnimSinceDispatch;
    private long levelUpFirstSeenAtMs;
    private long levelUpDismissAfterMs;

    // Human-factor counters. Decrement on each walker-ARRIVED; when a
    // counter hits 0 we trigger the corresponding behaviour and roll a
    // fresh random interval from [min, max].
    private int cameraNudgeCountdown = -1;   // -1 → roll on first ARRIVED
    private int idleCountdown = -1;

    private static final int BANK_MAX_FAIL = 3;
    private static final int WALKER_MAX_STUCK = 3;
    private static final int COOK_MAX_STUCK = 3;
    private static final int COOK_MENU_FALLBACK_AFTER = 2;
    private static final long BANK_LOAD_GRACE_MS = 1500;

    public CookingScriptV2(Client client, ClientThread clientThread,
                           HumanizedInputDispatcher dispatcher,
                           TransportResolver resolver)
    {
        this.client = client;
        this.clientThread = clientThread;
        this.dispatcher = dispatcher;
        this.walker = new UniversalWalker(client, clientThread, dispatcher, resolver);
        this.bank = new BankInteraction(client, clientThread, dispatcher);
        this.cook = new CookingInteraction(client, clientThread, dispatcher);
        this.sidebarTabs = new SidebarTabActions(client, clientThread, dispatcher);
    }

    public void setLocation(CookingLocation l) { location.set(l); }
    public void setRawFoodId(int id) { rawFoodId.set(id); }
    public CookingLocation location() { return location.get(); }
    public int rawFoodId() { return rawFoodId.get(); }
    public State state() { return state.get(); }
    public String status() { return status.get(); }
    public int cookedCount() { return cookedCount.get(); }
    public int burntCount() { return burntCount.get(); }

    public void start()
    {
        Thread existing = worker.get();
        if (existing != null && existing.isAlive())
        {
            status.set("already running");
            return;
        }
        if (!running.compareAndSet(false, true)) return;
        if (location.get() == null)
        {
            status.set("no location selected — abort");
            running.set(false);
            return;
        }
        if (rawFoodId.get() <= 0)
        {
            status.set("no food selected — abort");
            running.set(false);
            return;
        }
        State decided = decideResume();
        log.info("cookV2: resume → {} ({})", decided, status.get());
        setState(decided);
        if (decided == State.IDLE || decided == State.ABORTED)
        {
            running.set(false);
            return;
        }
        snapshotTripBaseline();
        Thread t = new Thread(this::tickLoop, "cooking-script-v2");
        t.setDaemon(true);
        worker.set(t);
        t.start();
    }

    public void stop()
    {
        running.set(false);
        Thread t = worker.getAndSet(null);
        if (t != null)
        {
            t.interrupt();
            try { t.join(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
        setState(State.IDLE);
        status.set("stopped");
    }

    private State decideResume()
    {
        CookingLocation l = location.get();
        WorldPoint here = onClient(() -> {
            Player p = client.getLocalPlayer();
            return p == null ? null : p.getWorldLocation();
        });
        if (here == null) { status.set("no player — abort"); return State.ABORTED; }
        boolean atBank = areaContains(l.bankArea(), here);
        boolean atCook = areaContains(l.cookArea(), here);
        int raw = inventoryAmountSafe(rawFoodId.get());
        if (atBank)
        {
            status.set("starting at bank");
            return State.BANKING;
        }
        if (atCook && raw > 0)
        {
            status.set("starting at cook spot — beginning to cook");
            return l.kind() == CookingLocation.SourceKind.FIRE_FROM_LOGS
                ? State.LIGHTING_FIRE : State.COOKING;
        }
        if (raw > 0) { status.set("mid-route — heading to cook"); return State.WALK_TO_COOK; }
        status.set("mid-route — heading to bank");
        return State.WALK_TO_BANK;
    }

    private void tickLoop()
    {
        try
        {
            if (!sidebarTabs.openTabAndWait(SidebarTab.INVENTORY, 2_000L))
                log.debug("cookV2: could not confirm inventory tab open at startup");

            while (running.get() && !Thread.currentThread().isInterrupted())
            {
                WorldPoint here = onClient(() -> {
                    Player p = client.getLocalPlayer();
                    return p == null ? null : p.getWorldLocation();
                });
                if (here == null)
                {
                    status.set("waiting for player (loading / disconnect?)");
                    SequenceSleep.sleep(client, TICK_MS);
                    continue;
                }
                safeDismissLevelUp();
                switch (state.get())
                {
                    case BANKING:        tickBanking();      break;
                    case WALK_TO_COOK:   tickWalk(true);     break;
                    case LIGHTING_FIRE:  tickLightingFire(); break;
                    case COOKING:        tickCooking();      break;
                    case WALK_TO_BANK:   tickWalk(false);    break;
                    case ABORTED:
                    case IDLE:
                    default:             running.set(false); break;
                }
                SequenceSleep.sleep(client, TICK_MS);
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
    // BANKING — legacy flow with V2's varied booth picker
    // ────────────────────────────────────────────────────────────────

    private void tickBanking() throws InterruptedException
    {
        CookingLocation l = location.get();
        if (!playerInArea(l.bankArea()))
        {
            UniversalWalker.Status s = walker.tick(currentBankPath());
            status.set("bank: walking to bank (" + s + ")");
            if (s == UniversalWalker.Status.ARRIVED)
            {
                walker.reset();
                walkerStuckCount = 0;
                tripBankPath = null;   // arrived — next leg picks a fresh tile
            }
            else if (s == UniversalWalker.Status.STUCK
                  || s == UniversalWalker.Status.ERROR)
            {
                walkerStuckCount++;
                log.info("cookV2 bank: walker {} (count={})", s, walkerStuckCount);
                walker.reset();
                tripBankPath = null;   // pick a different tile next attempt
                if (walkerStuckCount > WALKER_MAX_STUCK)
                    abortWithStatus("walker repeatedly stuck heading to bank — aborting");
            }
            return;
        }
        walkerStuckCount = 0;

        long now = System.currentTimeMillis();
        long since = lastBankActionAtMs == 0 ? Long.MAX_VALUE : now - lastBankActionAtMs;
        if (since < BANK_PACE_MS)
        {
            status.set("bank: pacing (" + since + "ms)");
            return;
        }

        Boolean pinUp = onClient(bank::isBankPinUp);
        if (Boolean.TRUE.equals(pinUp))
        {
            abortWithStatus("bank PIN required — aborting (enter manually)");
            return;
        }

        boolean open = onClient(bank::isBankOpen);
        if (!open)
        {
            bankOpenedAtMs = 0L;
            status.set("bank: opening");
            dispatcher.lastErrorMessage();
            // V2: weighted-random booth so the same booth isn't clicked
            // every trip. Different tile → different camera angle →
            // different click pixel.
            boolean clicked = bank.tryClickBankBoothVaried(BOOTH_ADJACENCY_BIAS);
            if (clicked)
            {
                lastBankActionAtMs = now;
                bankFailCount = 0;
            }
            else
            {
                bankFailCount++;
                if (bankFailCount > BANK_MAX_FAIL)
                {
                    abortWithStatus("no bank booth in range after "
                        + BANK_MAX_FAIL + " attempts — aborting");
                }
            }
            return;
        }
        if (bankOpenedAtMs == 0L) bankOpenedAtMs = now;

        String dispErr = dispatcher.lastErrorMessage();
        if (dispErr != null)
        {
            log.info("cookV2 bank: dispatcher error '{}'", dispErr);
            bankFailCount++;
            if (bankFailCount > BANK_MAX_FAIL)
            {
                abortWithBankClosed("bank dispatcher errors > " + BANK_MAX_FAIL
                    + " ('" + dispErr + "') — aborting");
                return;
            }
        }

        if (!tallyDoneThisVisit)
        {
            tallyCookedBurnt();
            tallyDoneThisVisit = true;
        }

        int depositId = firstDepositTargetId();
        if (depositId > 0)
        {
            status.set("bank: deposit-all item " + depositId);
            if (bank.tryDepositAll(depositId))
            {
                lastBankActionAtMs = now;
                lastInventoryChangeAtMs = now;
                bankFailCount = 0;
            }
            else
            {
                bankFailCount++;
                if (bankFailCount > BANK_MAX_FAIL)
                {
                    abortWithBankClosed("deposit-all itemId=" + depositId
                        + " failed " + BANK_MAX_FAIL + "× — aborting");
                    return;
                }
            }
            return;
        }

        int rawId = rawFoodId.get();
        int rawInInv = cook.inventoryAmount(rawId);
        boolean needTinderbox = l.kind() == CookingLocation.SourceKind.FIRE_FROM_LOGS
            && cook.inventoryAmount(ItemID.TINDERBOX) <= 0;

        boolean ready = bank.bankReady();
        if (!ready && (now - bankOpenedAtMs) < BANK_LOAD_GRACE_MS)
        {
            status.set("bank: waiting for container to populate");
            return;
        }
        if (!ready)
        {
            abortWithBankClosed("bank container did not populate in "
                + BANK_LOAD_GRACE_MS + "ms — aborting");
            return;
        }

        boolean takeRawFirst = needTinderbox && rawInInv == 0
            && !bank.isItemVisible(ItemID.TINDERBOX)
            && bank.isItemVisible(rawId);

        if (needTinderbox && !takeRawFirst)
        {
            if (!bank.bankContainsItem(ItemID.TINDERBOX))
            {
                abortWithBankClosed("bank missing Tinderbox — aborting");
                return;
            }
            status.set("bank: withdrawing tinderbox");
            if (bank.tryWithdrawOne(ItemID.TINDERBOX))
            {
                lastBankActionAtMs = now;
                bankFailCount = 0;
            }
            else bankFailCount++;
            return;
        }

        if (rawInInv == 0)
        {
            if (!bank.bankContainsItem(rawId))
            {
                CookingFood.Entry e = CookingFood.byRawId(rawId);
                String name = e == null ? ("id=" + rawId) : e.label;
                abortWithBankClosed("bank missing " + name + " — aborting");
                return;
            }
            status.set("bank: withdrawing raw food"
                + (takeRawFirst ? " (visible — taking before tinderbox)" : ""));
            if (bank.tryWithdrawAll(rawId))
            {
                lastBankActionAtMs = now;
                bankFailCount = 0;
            }
            else bankFailCount++;
            return;
        }

        status.set("bank: closing");
        if (bank.tryCloseBank())
        {
            lastBankActionAtMs = now;
            bankFailCount = 0;
        }
        else bankFailCount++;
        boolean stillOpen = onClient(bank::isBankOpen);
        if (!stillOpen)
        {
            snapshotTripBaseline();
            setState(State.WALK_TO_COOK);
        }
    }

    private void abortWithBankClosed(String reason) throws InterruptedException
    {
        log.warn("cookV2: {}", reason);
        tryCloseBankBestEffort();
        abortWithStatus(reason);
    }

    private void tryCloseBankBestEffort()
    {
        try
        {
            Boolean isOpen = onClient(bank::isBankOpen);
            if (Boolean.TRUE.equals(isOpen)) bank.tryCloseBank();
        }
        catch (Throwable th)
        {
            log.warn("cookV2: best-effort closeBank threw", th);
        }
    }

    private void abortWithStatus(String s)
    {
        status.set(s);
        setState(State.ABORTED);
    }

    // ────────────────────────────────────────────────────────────────
    // WALK_TO_COOK / WALK_TO_BANK — V2 uses per-trip random tile target
    // ────────────────────────────────────────────────────────────────

    private void tickWalk(boolean toCook) throws InterruptedException
    {
        PathSpec spec = toCook ? currentCookPath() : currentBankPath();
        UniversalWalker.Status st = walker.tick(spec);
        status.set((toCook ? "→ cook" : "→ bank") + ": " + st);
        switch (st)
        {
            case ARRIVED:
                walker.reset();
                walkerStuckCount = 0;
                // Per-arrival human-factor hooks: a small camera nudge
                // every 2-6 legs (turret-camera is the loudest 30-min
                // tell), and a 3-12s idle pause every 4-9 legs (kills
                // the uniform temporal cadence). Both run BEFORE the
                // state transition so they're indistinguishable from
                // the natural walk-end pause.
                maybeNudgeCamera();
                maybeIdlePause();
                if (toCook)
                {
                    setState(location.get().kind() == CookingLocation.SourceKind.FIRE_FROM_LOGS
                        ? State.LIGHTING_FIRE : State.COOKING);
                }
                else
                {
                    setState(State.BANKING);
                }
                break;
            case STUCK:
            case ERROR:
                walkerStuckCount++;
                log.info("cookV2 walk: walker {} on {} (count={})",
                    st, toCook ? "outbound" : "return", walkerStuckCount);
                walker.reset();
                if (toCook) tripCookPath = null;
                else tripBankPath = null;
                if (walkerStuckCount > WALKER_MAX_STUCK)
                {
                    abortWithStatus("walker stuck " + walkerStuckCount
                        + "× on " + (toCook ? "outbound" : "return"));
                }
                break;
            default:
                break;
        }
    }

    /** Lazily build (or return cached) per-trip bank-walk path. The
     *  random target tile inside {@code bankArea} is chosen once at
     *  trip start; reused across every walker.tick until ARRIVED or
     *  STUCK clears the cache (each makes the next leg pick a fresh
     *  tile). */
    private PathSpec currentBankPath()
    {
        if (tripBankPath == null)
        {
            CookingLocation l = location.get();
            tripBankPath = buildOneLegRandomTilePath(l.bankArea(), "v2-bank");
        }
        return tripBankPath;
    }

    private PathSpec currentCookPath()
    {
        if (tripCookPath == null)
        {
            CookingLocation l = location.get();
            tripCookPath = buildOneLegRandomTilePath(l.cookArea(), "v2-cook");
        }
        return tripCookPath;
    }

    /** Pick a uniformly-random tile inside {@code area}; wrap it in a
     *  1×1 sub-area; build a one-leg PathSpec targeting that sub-area.
     *  ARRIVED fires when the player's tile equals the picked tile,
     *  matching how V1's WALK_AREA arrival check behaves. */
    private static PathSpec buildOneLegRandomTilePath(WorldArea area, String label)
    {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int dx = r.nextInt(Math.max(1, area.getWidth()));
        int dy = r.nextInt(Math.max(1, area.getHeight()));
        WorldPoint pick = new WorldPoint(area.getX() + dx, area.getY() + dy, area.getPlane());
        WorldArea oneByOne = new WorldArea(pick.getX(), pick.getY(), 1, 1, area.getPlane());
        return PathSpec.builder(label + "-" + System.currentTimeMillis())
            .walk(label, oneByOne)
            .build();
    }

    // ────────────────────────────────────────────────────────────────
    // LIGHTING_FIRE — V2 explicit fire-state clear on death
    // ────────────────────────────────────────────────────────────────

    private void tickLightingFire() throws InterruptedException
    {
        CookingLocation l = location.get();
        long now = System.currentTimeMillis();

        if (litFireTile != null)
        {
            CookingInteraction.Match ours = cook.findFireAt(l.heatSourceName(), litFireTile);
            if (ours != null)
            {
                status.set("light: fire spawned at " + litFireTile + " — cooking");
                setState(State.COOKING);
                litFireTile = ours.tile;
                return;
            }
            if (cook.isFiremaking())
            {
                seenFiremakingAnimSinceDispatch = true;
                status.set("light: firemaking animation, waiting for fire");
                return;
            }
            if (!seenFiremakingAnimSinceDispatch
                    && (now - lightDispatchedAtMs) > LIGHT_NO_ANIM_TIMEOUT_MS)
            {
                log.info("cookV2: no firemaking anim seen after {}ms — click swallowed, retrying",
                    now - lightDispatchedAtMs);
                litFireTile = null;
                lightDispatchedAtMs = 0L;
                seenFiremakingAnimSinceDispatch = false;
                lightingBackoffUntilMs = now + 1000L;
                return;
            }
            if (now - lightDispatchedAtMs > LIGHT_TIMEOUT_MS)
            {
                String e = dispatcher.lastErrorMessage();
                log.info("cookV2: no fire on {} after {}ms — retrying (last err: {})",
                    litFireTile, LIGHT_TIMEOUT_MS, e);
                litFireTile = null;
                lightDispatchedAtMs = 0L;
                seenFiremakingAnimSinceDispatch = false;
                lightingBackoffUntilMs = now + 2000L;
                return;
            }
            status.set("light: waiting for fire on " + litFireTile);
            return;
        }

        if (now < lightingBackoffUntilMs)
        {
            status.set("light: backoff (" + (lightingBackoffUntilMs - now) + "ms)");
            return;
        }

        if (cook.inventoryAmount(ItemID.TINDERBOX) <= 0)
        {
            status.set("light: no tinderbox in inv — back to bank");
            setState(State.WALK_TO_BANK);
            return;
        }

        int logsId = l.groundLogsItemId();
        // V2: jittered log-pile pick so the bot doesn't always light the
        // strict-closest spawn. With LOG_JITTER_TILES=2, multiple piles
        // around the cook spot rotate naturally across trips.
        CookingInteraction.Match logs = cook.findGroundLogsVaried(logsId, LOG_JITTER_TILES);
        if (logs == null)
        {
            status.set("light: waiting for log spawn");
            return;
        }

        CookingInteraction.Match existingFire = cook.findFireAt(l.heatSourceName(), logs.tile);
        if (existingFire != null)
        {
            log.info("cookV2: fire already at log tile {} — using it directly", logs.tile);
            litFireTile = existingFire.tile;
            setState(State.COOKING);
            return;
        }

        if (cook.isFiremaking())
        {
            status.set("light: firemaking already in progress");
            return;
        }
        if (dispatcher.isBusy())
        {
            status.set("light: dispatcher busy");
            return;
        }

        status.set("light: right-click logs — Light");
        if (!cook.lightLogsViaClick(logs))
        {
            status.set("light: Light missing — trying tinderbox on logs");
            AtomicBoolean clickedLogs = new AtomicBoolean(false);
            AtomicReference<String> lightFailure = new AtomicReference<>(null);
            CookingInteraction.Match logsCaptured = logs;
            int logsIdCaptured = logsId;
            boolean ranLightFallback = dispatcher.runExclusive(() -> {
                dispatcher.clearSelectedWidgetTargetMode();
                if (!cook.useTinderboxOnWorker())
                {
                    lightFailure.set("light: tinderbox use failed");
                    return;
                }
                SequenceSleep.sleep(client, 350);
                CookingInteraction.Match logs2 = cook.findGroundLogsVaried(logsIdCaptured, LOG_JITTER_TILES);
                if (logs2 == null)
                {
                    dispatcher.clearSelectedWidgetTargetMode();
                    lightFailure.set("light: logs despawned during tinderbox use");
                    return;
                }
                if (!logsCaptured.tile.equals(logs2.tile))
                {
                    dispatcher.clearSelectedWidgetTargetMode();
                    lightFailure.set("light: logs moved during tinderbox use");
                    return;
                }
                if (!cook.clickLogsForLight(logs2))
                {
                    lightFailure.set("light: tinderbox-on-logs verify failed");
                    return;
                }
                clickedLogs.set(true);
            });
            if (!ranLightFallback)
            {
                status.set("light: dispatcher busy");
                return;
            }
            if (!clickedLogs.get())
            {
                status.set(lightFailure.get() == null
                    ? "light: logs click failed — backing off"
                    : lightFailure.get());
                lightingBackoffUntilMs = now + 1200L;
                return;
            }
        }
        litFireTile = logs.tile;
        seenFiremakingAnimSinceDispatch = false;
        lightDispatchedAtMs = System.currentTimeMillis();
        status.set("light: clicked logs at " + litFireTile + " — waiting for fire");
    }

    // ────────────────────────────────────────────────────────────────
    // COOKING — same gates as V1, with explicit fire-state clear when
    // the fire dies so the next LIGHTING_FIRE entry doesn't trip the
    // "no anim after 1.7e12 ms" log spam.
    // ────────────────────────────────────────────────────────────────

    private void tickCooking() throws InterruptedException
    {
        CookingLocation l = location.get();
        int rawId = rawFoodId.get();
        long now = System.currentTimeMillis();

        if (cook.isCookMenuOpen())
        {
            cookMenuConfirmAttempts++;
            if (cookMenuConfirmAttempts <= COOK_MENU_FALLBACK_AFTER)
            {
                status.set("cook: confirming Cook All (Space)");
                cook.confirmCookAll();
            }
            else
            {
                status.set("cook: confirming Cook All (widget click fallback)");
                cook.clickCookAllWidget();
            }
            if (cookMenuConfirmAttempts == 1) lastInventoryChangeAtMs = now;
            return;
        }
        cookMenuConfirmAttempts = 0;

        int raw = cook.inventoryAmount(rawId);
        if (raw != lastRawCount)
        {
            if (lastRawCount >= 0 && raw < lastRawCount)
            {
                lastRawDecreaseAtMs = now;
            }
            lastRawCount = raw;
            lastInventoryChangeAtMs = now;
            cookStuckCount = 0;
        }
        if (lastInventoryChangeAtMs == 0) lastInventoryChangeAtMs = now;

        if (raw == 0)
        {
            status.set("cook: out of raw food — back to bank");
            setState(State.WALK_TO_BANK);
            return;
        }

        CookingInteraction.Match heat;
        if (l.kind() == CookingLocation.SourceKind.FIRE_FROM_LOGS)
        {
            if (litFireTile == null)
            {
                status.set("cook: no lit fire tracked — relighting");
                setState(State.LIGHTING_FIRE);
                return;
            }
            heat = cook.findFireAt(l.heatSourceName(), litFireTile);
            if (heat == null)
            {
                long pause = FIRE_DEATH_PAUSE_MIN_MS
                    + ThreadLocalRandom.current().nextLong(
                        FIRE_DEATH_PAUSE_MAX_MS - FIRE_DEATH_PAUSE_MIN_MS);
                WorldPoint deadTile = litFireTile;
                // V2 fix: explicitly clear fire-state BEFORE setState. V1's
                // setState only clears these when transitioning OUT of
                // LIGHTING_FIRE/COOKING, so the fields leaked into the
                // next LIGHTING_FIRE tick and tripped the no-anim check
                // with `now - 0` as elapsed time — visible in V1 logs as
                // "no firemaking anim seen after 1.7e12 ms".
                litFireTile = null;
                lightDispatchedAtMs = 0L;
                seenFiremakingAnimSinceDispatch = false;
                status.set("cook: fire died at " + deadTile
                    + " — pausing " + pause + "ms before relight");
                setState(State.LIGHTING_FIRE);
                lightingBackoffUntilMs = System.currentTimeMillis() + pause;
                log.info("cookV2: fire at {} died, backing off {}ms", deadTile, pause);
                return;
            }
        }
        else
        {
            heat = cook.findHeatSource(l.heatSourceName());
            if (heat == null)
            {
                status.set("cook: range out of view — walking back");
                setState(State.WALK_TO_COOK);
                return;
            }
        }

        WorldPoint pp = onClient(() -> {
            Player p = client.getLocalPlayer();
            return p == null ? null : p.getWorldLocation();
        });
        if (pp != null && heat.tile != null
            && pp.getPlane() == heat.tile.getPlane()
            && Math.max(Math.abs(pp.getX() - heat.tile.getX()),
                        Math.abs(pp.getY() - heat.tile.getY())) > 8)
        {
            status.set("cook: heat source out of interaction range — walking closer");
            setState(State.WALK_TO_COOK);
            return;
        }

        if (cook.isCooking())
        {
            status.set("cook: cooking (" + raw + " raw left)");
            return;
        }

        if (lastRawDecreaseAtMs > 0 && (now - lastRawDecreaseAtMs) < COOK_BATCH_SETTLE_MS)
        {
            status.set("cook: batch in progress (" + raw
                + " raw, last cook " + (now - lastRawDecreaseAtMs) + "ms ago)");
            return;
        }

        if (now - lastInventoryChangeAtMs > COOK_STUCK_MS)
        {
            cookStuckCount++;
            log.info("cookV2: no progress for {}ms (count={})", COOK_STUCK_MS, cookStuckCount);
            if (cookStuckCount > COOK_MAX_STUCK)
            {
                abortWithStatus("cook: stuck " + cookStuckCount + "× — aborting");
                return;
            }
            lastInventoryChangeAtMs = now;
            lastCookActionAtMs = 0L;
            lastRawCount = -1;
        }

        if (dispatcher.isBusy())
        {
            status.set("cook: dispatcher busy");
            return;
        }
        if (lastCookActionAtMs > 0 && (now - lastCookActionAtMs) < COOK_PACE_MS)
        {
            status.set("cook: pacing (" + (now - lastCookActionAtMs) + "ms)");
            return;
        }

        if (!ensureInventoryTabOpen())
        {
            status.set("cook: waiting for inventory tab");
            return;
        }

        status.set("cook: use raw food on heat source");
        AtomicBoolean clickedHeat = new AtomicBoolean(false);
        AtomicBoolean rawGoneAfterSettle = new AtomicBoolean(false);
        AtomicBoolean heatVanished = new AtomicBoolean(false);
        AtomicReference<String> cookClickFailure = new AtomicReference<>(null);
        boolean ranCookClick = dispatcher.runExclusive(() -> {
            if (!cook.useRawFoodOnWorker(rawId))
            {
                cookClickFailure.set("cook: raw food use-mode failed — re-checking");
                return;
            }
            SequenceSleep.sleep(client, 400);
            if (cook.inventoryAmount(rawId) == 0)
            {
                dispatcher.clearSelectedWidgetTargetMode();
                rawGoneAfterSettle.set(true);
                return;
            }
            CookingInteraction.Match heat2 =
                l.kind() == CookingLocation.SourceKind.FIRE_FROM_LOGS
                    ? cook.findFireAt(l.heatSourceName(), litFireTile)
                    : cook.findHeatSource(l.heatSourceName());
            if (heat2 == null)
            {
                dispatcher.clearSelectedWidgetTargetMode();
                heatVanished.set(true);
                return;
            }
            if (!cook.clickHeatSourceForCook(heat2, rawId, l.heatSourceName()))
            {
                cookClickFailure.set("cook: heat-source verify failed — use-mode cancelled, retry");
                return;
            }
            clickedHeat.set(true);
        });
        if (!ranCookClick)
        {
            status.set("cook: dispatcher busy");
            return;
        }
        if (rawGoneAfterSettle.get())
        {
            status.set("cook: all raw cooked during settle — heading to bank");
            setState(State.WALK_TO_BANK);
            return;
        }
        if (heatVanished.get())
        {
            status.set("cook: heat source vanished mid-use");
            return;
        }
        if (!clickedHeat.get())
        {
            status.set(cookClickFailure.get() == null
                ? "cook: verified cook click failed — retry"
                : cookClickFailure.get());
            return;
        }
        lastCookActionAtMs = System.currentTimeMillis();
    }

    // ────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────

    /** Nudge the camera toward a random tile a few steps from the
     *  player. Force=false so the dispatcher's "already visible"
     *  short-circuit can decline the rotation if the target's already
     *  on-screen (the natural human pattern — camera adjusts only when
     *  there's a reason). Rolls a fresh interval after firing. */
    private void maybeNudgeCamera()
    {
        if (cameraNudgeCountdown < 0)
        {
            cameraNudgeCountdown = ThreadLocalRandom.current()
                .nextInt(CAMERA_NUDGE_INTERVAL_MIN, CAMERA_NUDGE_INTERVAL_MAX + 1);
            return;
        }
        if (--cameraNudgeCountdown > 0) return;
        cameraNudgeCountdown = ThreadLocalRandom.current()
            .nextInt(CAMERA_NUDGE_INTERVAL_MIN, CAMERA_NUDGE_INTERVAL_MAX + 1);

        WorldPoint here = onClient(() -> {
            Player p = client.getLocalPlayer();
            return p == null ? null : p.getWorldLocation();
        });
        if (here == null) return;
        // Pick a tile 4-8 steps away in a random compass direction —
        // far enough that the camera actually rotates, close enough
        // that it doesn't pan past the cook spot.
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int dist = r.nextInt(4, 9);
        double angle = r.nextDouble() * Math.PI * 2.0;
        int dx = (int) Math.round(Math.cos(angle) * dist);
        int dy = (int) Math.round(Math.sin(angle) * dist);
        WorldPoint target = new WorldPoint(
            here.getX() + dx, here.getY() + dy, here.getPlane());
        try
        {
            log.info("cookV2: camera nudge → {}", target);
            dispatcher.rotateCameraToward(target, false);
        }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        catch (Throwable th) { log.warn("cookV2: camera nudge threw", th); }
    }

    /** Insert a randomized idle pause to break the uniform inter-trip
     *  cadence. Only fires on the worker thread (where SequenceSleep
     *  is safe). Rolls a fresh interval after firing. */
    private void maybeIdlePause() throws InterruptedException
    {
        if (idleCountdown < 0)
        {
            idleCountdown = ThreadLocalRandom.current()
                .nextInt(IDLE_INTERVAL_MIN, IDLE_INTERVAL_MAX + 1);
            return;
        }
        if (--idleCountdown > 0) return;
        idleCountdown = ThreadLocalRandom.current()
            .nextInt(IDLE_INTERVAL_MIN, IDLE_INTERVAL_MAX + 1);

        long pause = IDLE_PAUSE_MIN_MS
            + ThreadLocalRandom.current().nextLong(IDLE_PAUSE_MAX_MS - IDLE_PAUSE_MIN_MS);
        log.info("cookV2: micro-idle pause {}ms", pause);
        status.set("idle: paused " + pause + "ms (human-factor)");
        SequenceSleep.sleep(client, pause);
    }

    private void safeDismissLevelUp()
    {
        try
        {
            if (!cook.isLevelUpVisible())
            {
                levelUpFirstSeenAtMs = 0L;
                return;
            }
            long now = System.currentTimeMillis();
            if (levelUpFirstSeenAtMs == 0L)
            {
                long delay = LEVEL_UP_DISMISS_MIN_MS
                    + ThreadLocalRandom.current().nextLong(
                        LEVEL_UP_DISMISS_MAX_MS - LEVEL_UP_DISMISS_MIN_MS);
                levelUpFirstSeenAtMs = now;
                levelUpDismissAfterMs = now + delay;
                log.info("cookV2: level-up — will dismiss in {}ms", delay);
                return;
            }
            if (now >= levelUpDismissAfterMs)
            {
                log.info("cookV2: level-up — pressing Space ({}ms after popup appeared)",
                    now - levelUpFirstSeenAtMs);
                cook.dismissLevelUp();
                levelUpFirstSeenAtMs = 0L;
            }
        }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        catch (Throwable th) { log.warn("cookV2: dismissLevelUp threw", th); }
    }

    private int firstDepositTargetId() throws InterruptedException
    {
        for (CookingFood.Entry e : CookingFood.all())
            if (cook.inventoryAmount(e.cookedId) > 0) return e.cookedId;
        for (CookingFood.Entry e : CookingFood.all())
            if (cook.inventoryAmount(e.burntId) > 0) return e.burntId;
        return 0;
    }

    private void tallyCookedBurnt() throws InterruptedException
    {
        CookingFood.Entry e = CookingFood.byRawId(rawFoodId.get());
        if (e == null) return;
        int cookedNow = cook.inventoryAmount(e.cookedId);
        int burntNow  = cook.inventoryAmount(e.burntId);
        int dCooked = Math.max(0, cookedNow - tripStartCooked);
        int dBurnt  = Math.max(0, burntNow  - tripStartBurnt);
        if (dCooked > 0) cookedCount.addAndGet(dCooked);
        if (dBurnt  > 0) burntCount.addAndGet(dBurnt);
    }

    private void snapshotTripBaseline()
    {
        CookingFood.Entry e = CookingFood.byRawId(rawFoodId.get());
        if (e == null) { tripStartCooked = 0; tripStartBurnt = 0; return; }
        try
        {
            tripStartCooked = cook.inventoryAmount(e.cookedId);
            tripStartBurnt  = cook.inventoryAmount(e.burntId);
        }
        catch (InterruptedException ie)
        {
            Thread.currentThread().interrupt();
            tripStartCooked = 0;
            tripStartBurnt = 0;
        }
    }

    private int inventoryAmountSafe(int id)
    {
        try { return cook.inventoryAmount(id); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); return 0; }
        catch (Throwable th) { return 0; }
    }

    private boolean playerInArea(WorldArea area)
    {
        WorldPoint here = onClient(() -> {
            Player p = client.getLocalPlayer();
            return p == null ? null : p.getWorldLocation();
        });
        return here != null && areaContains(area, here);
    }

    private static boolean areaContains(WorldArea a, WorldPoint p)
    {
        return p.getPlane() == a.getPlane()
            && p.getX() >= a.getX() && p.getX() < a.getX() + a.getWidth()
            && p.getY() >= a.getY() && p.getY() < a.getY() + a.getHeight();
    }

    private boolean ensureInventoryTabOpen() throws InterruptedException
    {
        if (Boolean.TRUE.equals(onClient(() -> sidebarTabs.isOpen(SidebarTab.INVENTORY))))
            return true;
        if (dispatcher.isBusy()) return false;
        return sidebarTabs.openTabAndWait(SidebarTab.INVENTORY, 2_000L);
    }

    private void setState(State s)
    {
        state.set(s);
        lastBankActionAtMs = 0L;
        lastCookActionAtMs = 0L;
        lastInventoryChangeAtMs = 0L;
        lastRawCount = -1;
        tallyDoneThisVisit = false;
        bankOpenedAtMs = 0L;
        bankFailCount = 0;
        cookMenuConfirmAttempts = 0;
        lightingBackoffUntilMs = 0L;
        walkerStuckCount = 0;
        cookStuckCount = 0;
        lastRawDecreaseAtMs = 0L;
        if (s != State.LIGHTING_FIRE && s != State.COOKING)
        {
            litFireTile = null;
            lightDispatchedAtMs = 0L;
            seenFiremakingAnimSinceDispatch = false;
        }
        // Per-trip path cache lifecycle — clear when leaving the leg
        // that owns it. The walker arrivals also clear in tickWalk /
        // tickBanking; this catches abort / non-walk transitions.
        if (s != State.WALK_TO_BANK && s != State.BANKING) tripBankPath = null;
        if (s != State.WALK_TO_COOK) tripCookPath = null;
    }

    private <T> T onClient(Supplier<T> s)
    {
        java.util.concurrent.atomic.AtomicReference<T> ref = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        clientThread.invokeLater(() -> {
            try { ref.set(s.get()); }
            catch (Throwable th) { log.warn("cookV2: onClient threw", th); }
            finally { latch.countDown(); }
        });
        try
        {
            if (!latch.await(2000, java.util.concurrent.TimeUnit.MILLISECONDS))
            {
                log.warn("cookV2: onClient timed out");
                return null;
            }
        }
        catch (InterruptedException ie)
        { Thread.currentThread().interrupt(); return null; }
        return ref.get();
    }
}
