package net.runelite.client.plugins.recorder.scripts;

import java.awt.Rectangle;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.recorder.cook.CookingInteraction;
import net.runelite.client.plugins.recorder.farm.BankInteraction;
import net.runelite.client.plugins.recorder.trail.TrailRegistry;
import net.runelite.client.plugins.recorder.widget.SidebarTab;
import net.runelite.client.plugins.recorder.widget.SidebarTabActions;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import net.runelite.client.sequence.dispatch.SequenceSleep;
import net.runelite.client.sequence.internal.ActionRequest;
import net.runelite.client.sequence.login.LoginAssistantV2;
import net.runelite.client.sequence.login.LoginCredentials;

/**
 * Pizza maker — three (optionally four) batched loops at the Lumbridge
 * Castle bank + Cook's range, exactly the wiki / hand-loop the user
 * described:
 *
 * <ol>
 *   <li>tomato + pizza base → incomplete pizza (at bank, 14+14 batches).</li>
 *   <li>cheese + incomplete pizza → uncooked pizza (at bank, 14+14).</li>
 *   <li>uncooked pizza on Cook's range → plain pizza (walk down to the
 *       kitchen, cook 28 at a time).</li>
 *   <li>(optional) cooked anchovies + plain pizza → anchovy pizza (back
 *       at bank, 14+14).</li>
 * </ol>
 *
 * <p>Each phase runs to completion before moving on (batch-mode), not
 * one full pizza per trip. Loop 3 is the only one that requires walking
 * — loops 1, 2 and 4 are bank↔bank.
 *
 * <p><b>Reuse over rebuild.</b> The actual primitives all live in
 * existing classes; this script is mostly an FSM that orders them:
 * <ul>
 *   <li>{@link BankInteraction} for verified-result deposit / withdraw /
 *       booth click.  Returns true only after the inventory actually
 *       moved, so we don't wrap with our own poll.</li>
 *   <li>{@link CookingInteraction#findHeatSource} +
 *       {@link CookingInteraction#useRawFoodOnWorker} +
 *       {@link CookingInteraction#clickHeatSourceForCook} for the range
 *       cook (same pattern {@link CookingScriptV3} uses for fish).</li>
 *   <li>{@link TrailWalker#walkRoute} with
 *       {@link Route#fromTrails(java.util.Collection, String)} for the
 *       P2 (bank) ↔ P0 (kitchen) walks.  The bank-to-cook trail is
 *       already recorded for {@link CooksAssistantScript}; this script
 *       reuses it for the outbound leg and asks the user to record one
 *       return trail (key: {@code cook_to_lumby_bank}).</li>
 *   <li>{@link PieDishScript}'s tickCraftBatch shape for the
 *       bank-side "Use X on Y → Make All" combine loops (loops 1, 2, 4).
 *       Pizza recipes are single-output Skillmulti dialogs, so Space
 *       confirms — no recipe-option widget click needed.</li>
 * </ul>
 *
 * <p><b>Threading.</b> One daemon worker thread drives the FSM at
 * ~600 ms ticks.  All client-API reads are marshalled to the client
 * thread via {@link ClientThread}.  Multi-step click flows (Use → Skillmulti
 * → Space, Use → range) run inside {@link HumanizedInputDispatcher#runExclusive}
 * on the worker so the dispatcher's busy flag holds across the whole
 * sequence.  Worker-only primitives ({@link BankInteraction#tryWithdrawX}
 * et al.) self-assert and would throw on the client thread anyway.
 *
 * <p><b>Pre-conditions for first run.</b>
 * <ul>
 *   <li>Cooking lvl ≥ 35 (pizza assembly minimum).</li>
 *   <li>Lumbridge Castle Cook's Assistant complete (so the kitchen
 *       range exists / is unlocked); script doesn't verify this — it
 *       just looks for an object named "Cooking range" in the kitchen area and
 *       aborts cleanly if none is found.</li>
 *   <li>Recorded trails: {@code lumby_bank_to_cook} (bank → kitchen,
 *       already exists from the Cook's Assistant script) and
 *       {@code cook_to_lumby_bank} (kitchen → bank, user records once).</li>
 *   <li>Bank holds the input items.  The script does NOT auto-buy on
 *       the GE — it just consumes what's in the bank and moves to the
 *       next loop when an input runs out.</li>
 *   <li>Player starts at the Lumbridge bank (recommended) or in the
 *       Cook's kitchen with valid in-flight inventory.  Anywhere else
 *       and {@link #decideResume} aborts with a clear message.</li>
 * </ul>
 */
@Slf4j
public final class PizzaScript
{
    // ─── Item IDs ───────────────────────────────────────────────────────
    private static final int TOMATO            = ItemID.TOMATO;            // 1982
    private static final int CHEESE            = ItemID.CHEESE;            // 1985
    private static final int PIZZA_BASE        = ItemID.PIZZA_BASE;        // 2283
    private static final int INCOMPLETE_PIZZA  = ItemID.INCOMPLETE_PIZZA;  // 2285
    private static final int UNCOOKED_PIZZA    = ItemID.UNCOOKED_PIZZA;    // 2287
    private static final int PLAIN_PIZZA       = ItemID.PLAIN_PIZZA;       // 2289
    private static final int BURNT_PIZZA       = ItemID.BURNT_PIZZA;       // 2305
    /** Cooked anchovies — the pizza-topping ingredient.  Raw anchovies
     *  do NOT make anchovy pizza; only cooked do. */
    private static final int ANCHOVIES_COOKED  = ItemID.ANCHOVIES;         // 319
    private static final int ANCHOVY_PIZZA     = ItemID.ANCHOVIE_PIZZA;    // 2297

    // ─── Areas ──────────────────────────────────────────────────────────
    /** Lumbridge Castle plane-2 bank room — booth-front strip only.
     *  Tiles directly south of the two booths at (3208,3221) and
     *  (3209,3221). Stops at x=3209 / y=3220 so the picker can never
     *  roll the NE corner (3210,3220) the user flagged 2026-05-20 —
     *  from there the bot still chains bank-booth clicks but each
     *  retry has a longer walk attached, raising race odds (one click
     *  opens the bank, the next walks past it and closes it). */
    private static final WorldArea BANK_AREA   = new WorldArea(3208, 3219, 2, 2, 2);
    /** Lumbridge kitchen plane 0 — covers the cook NPC, the range tile
     *  in front of it, and the food crates. */
    private static final WorldArea KITCHEN_AREA = new WorldArea(3207, 3213, 5, 5, 0);

    // ─── Hardcoded Lumbridge walk waypoints ─────────────────────────────
    // Staircase between bank (p=2) and kitchen (p=0). Same model;
    // standing tile to click differs by direction.
    /** Tile the staircase verb-click is dispatched on (plane=2 → climb down). */
    private static final WorldPoint STAIRCASE_DOWN_TILE =
        new WorldPoint(3205, 3208, 2);
    /** Tile the staircase verb-click is dispatched on (plane=0 → climb up). */
    private static final WorldPoint STAIRCASE_UP_TILE   =
        new WorldPoint(3204, 3207, 0);
    /** Approach TILES for the descend leg — a small box east of the
     *  staircase on plane=2. Each approach walk picks a random tile
     *  from the list so we don't click the exact same minimap pixel
     *  every trip. (3206..3207, 3208..3209) = 4 walkable tiles. */
    private static final List<WorldPoint> APPROACH_DOWN_TILES = List.of(
        new WorldPoint(3206, 3208, 2),
        new WorldPoint(3206, 3209, 2),
        new WorldPoint(3207, 3208, 2),
        new WorldPoint(3207, 3209, 2));
    /** Approach TILES for the ascend leg — east of the staircase on
     *  plane=0. Mirrors the in-game "p0 south staircase lumby" area
     *  marker (~/.runelite/sequencer/routes/test.txt): 8 walkable tiles
     *  east-northeast of {@link #STAIRCASE_UP_TILE}, picked because
     *  the original 2-tile column was unreliable — from certain camera
     *  yaws the cursor over those tiles resolved to the staircase hull
     *  ("Climb-up") instead of "Walk here", triggering the dispatcher's
     *  minimap fallback and leaving the player stuck outside chebyshev
     *  2 of the stair tile (2026-05-20 failed-bank-return). The wider
     *  list gives the planner room to retry from a different angle. */
    private static final List<WorldPoint> APPROACH_UP_TILES = List.of(
        new WorldPoint(3205, 3209, 0),
        new WorldPoint(3206, 3208, 0),
        new WorldPoint(3206, 3209, 0),
        new WorldPoint(3206, 3210, 0),
        new WorldPoint(3207, 3209, 0),
        new WorldPoint(3207, 3210, 0),
        new WorldPoint(3208, 3209, 0),
        new WorldPoint(3208, 3210, 0));
    /** Tile to point the camera at when looking for the range on p=0.
     *  Center of the kitchen — the cooking range model sits ~here. */
    private static final WorldPoint RANGE_LOOK_TILE =
        new WorldPoint(3209, 3215, 0);
    /** Minimum ms between consecutive walk-tile dispatches so we don't
     *  spam clicks while the player is mid-step. Each dispatch picks a
     *  fresh random tile inside the relevant area. */
    private static final long WALK_DISPATCH_PACE_MS = 1_500L;
    /** Settle after dispatching a staircase verb-click. Real cost is
     *  cursor flight + HULL→TILE_POLY retry chain + climb animation +
     *  plane-swap signal — ~5 s observed on Lumby staircase 2026-05-19.
     *  Short values caused redundant second clicks before the first
     *  finished. */
    private static final long TRANSPORT_SETTLE_MS   = 6_500L;

    // ─── Batch sizes ────────────────────────────────────────────────────
    /** Combine recipes consume both inputs into one output, so 14+14 fits
     *  exactly in 28 inventory slots — same shape as {@code PIE_SHELL_BATCH}. */
    private static final int COMBINE_BATCH = 14;
    /** Cooking on a range fills inventory entirely with raw food (no
     *  by-product), so 28 slots = 28 raw pizzas per trip. */
    private static final int COOK_BATCH    = 28;

    // ─── Timing ─────────────────────────────────────────────────────────
    private static final long TICK_MS                = 600L;
    // Active inside the bank — every deposit / withdraw / close gate
    // picks a fresh value in this range.  Tight: the goal is "open,
    // grab, close" without 1.5 s of wasted wait between every action.
    private static final long BANK_IN_USE_MIN_MS     = 220L;
    private static final long BANK_IN_USE_MAX_MS     = 520L;
    // Random idle BEFORE clicking the booth on the first touch of each
    // bank trip — the bot just walked here, a longer humanized pause
    // here is welcome (and is the right place to absorb jitter that
    // we no longer waste between in-bank actions).  In-trip retries
    // fall through to the in-use range above.
    private static final long BANK_PRE_OPEN_MIN_MS   = 800L;
    private static final long BANK_PRE_OPEN_MAX_MS   = 2_400L;
    // Replaces the hardcoded 400 ms post-close verify sleep.
    private static final long BANK_CLOSE_VERIFY_MIN_MS = 200L;
    private static final long BANK_CLOSE_VERIFY_MAX_MS = 380L;
    // After dispatching a booth-open click, give the server enough time
    // to process it (≥ 2 game ticks) before considering another click.
    // BANK_IN_USE (220-520ms) was used here; it's too tight — 520 ms
    // can fire before the server tick (600 ms), so isBankOpen() still
    // reads false and we dispatched a 2nd booth click. The 2nd click on
    // a now-open bank toggled it closed → "bank closed mid-deposit" 3×
    // in a row → ABORTED (2026-05-20).
    private static final long BANK_OPEN_WAIT_MIN_MS  = 1_500L;
    private static final long BANK_OPEN_WAIT_MAX_MS  = 2_500L;
    private static final long BANK_LOAD_GRACE_MS     = 1_500L;
    private static final long SKILLMULTI_TIMEOUT_MS  = 5_000L;
    private static final long CRAFT_TIMEOUT_MS       = 90_000L;
    private static final long COOK_BATCH_SETTLE_MS   = 5_000L;
    private static final long COOK_STUCK_MS          = 30_000L;
    /** Randomized minimum delay between a cook click and the next
     *  cook-tick decision. Picks a fresh value per click — 1.5 s
     *  used to fall through before {@code isCooking()} flipped true,
     *  causing a double-fire of the range click before the first batch
     *  even started. Real player reaction + animation observation is
     *  several seconds, well within this range. */
    private static final long COOK_PACE_MIN_MS       = 5_000L;
    private static final long COOK_PACE_MAX_MS       = 9_000L;
    private static final long LEVEL_UP_DISMISS_MIN_MS = 3_000L;
    private static final long LEVEL_UP_DISMISS_MAX_MS = 34_000L;

    // Randomized reaction delay before pressing Space on a Skillmulti
    // / Cook-All dialog. Real players don't slam Space at frame 0 — they
    // see the dialog, register it, and press. Constant 0-ms timing was
    // the most visible tell.
    private static final long COOK_CONFIRM_MIN_MS = 220L;
    private static final long COOK_CONFIRM_MAX_MS = 680L;

    private static final int MAX_BANK_FAILURES   = 3;
    private static final int WALKER_MAX_STUCK    = 3;
    private static final int COOK_MAX_STUCK      = 3;
    private static final int COOK_MENU_FALLBACK_AFTER = 2;
    /** Max consecutive ticks the cook gate may fail to find the heat
     *  source / be in interaction range before we abort.  At
     *  TICK_MS=600 this is ~3s of tolerance — enough for a scene-load
     *  after a transport, but not enough to mask a wrong object name
     *  or a trail that lands too far from the cook spot. */
    private static final int COOK_MAX_HEAT_MISS  = 5;

    // ─── State machine ──────────────────────────────────────────────────
    public enum State
    {
        IDLE,
        /** Open bank, plan next phase from bank counts. */
        DECIDE,
        /** Loop: tomato + pizza_base → incomplete pizza, 14+14 per trip. */
        COMBINE_BASE_TOMATO,
        /** Loop: cheese + incomplete pizza → uncooked pizza, 14+14. */
        COMBINE_INCOMPLETE_CHEESE,
        /** Walk from bank (p2) to kitchen (p0). */
        WALK_TO_RANGE,
        /** Cook uncooked pizza on the range until inventory empty. */
        COOK_AT_RANGE,
        /** Walk back kitchen → bank. */
        WALK_TO_BANK,
        /** (Optional) cooked anchovies + plain pizza → anchovy pizza, 14+14. */
        COMBINE_PLAIN_ANCHOVIES,
        DONE,
        ABORTED
    }

    // ─── Dependencies ───────────────────────────────────────────────────
    private final Client                   client;
    private final ClientThread             clientThread;
    private final HumanizedInputDispatcher dispatcher;
    private final BankInteraction          bank;
    private final CookingInteraction       cook;
    private final SidebarTabActions        sidebarTabs;

    // ─── Runtime state ──────────────────────────────────────────────────
    private final AtomicReference<State>   state   = new AtomicReference<>(State.IDLE);
    private final AtomicReference<String>  status  = new AtomicReference<>("idle");
    private final AtomicBoolean            running = new AtomicBoolean(false);
    private final AtomicReference<Thread>  worker  = new AtomicReference<>();

    public boolean isRunning() { return running.get(); }
    // ─── Per-loop enable flags (panel-driven) ───────────────────────────
    // Read live in tickDecide so toggling a checkbox during a run takes
    // effect at the NEXT bank trip, not mid-batch.  Default = all true:
    // unchecked, the script behaves identically to the pre-toggle build.
    /** Loop 1: pizza_base + tomato → incomplete_pizza. */
    private final AtomicBoolean addTomato    = new AtomicBoolean(true);
    /** Loop 2: incomplete_pizza + cheese → uncooked_pizza. */
    private final AtomicBoolean addCheese    = new AtomicBoolean(true);
    /** Loop 3: uncooked_pizza → plain_pizza on the range. */
    private final AtomicBoolean cookPizza    = new AtomicBoolean(true);
    /** Loop 4: plain_pizza + cooked anchovies → anchovy_pizza. */
    private final AtomicBoolean addAnchovies = new AtomicBoolean(true);
    /** AFK-break humanizer; live-toggled from the panel.  When false,
     *  the {@link #breaks} scheduler is disabled and never fires. */
    private final AtomicBoolean afkBreaksEnabled = new AtomicBoolean(true);

    /** AFK break scheduler.  Constructed in {@link #start()} so a
     *  Stop+Start rolls a fresh activity window.  Null while the
     *  worker isn't running. */
    private net.runelite.client.plugins.recorder.afk.BreakScheduler breaks;

    // Pizza-made counters (delta from session-start baseline) — surfaced
    // on the panel so the user can see throughput without alt-tabbing to
    // the bank.  We track the four output items the script produces plus
    // burnt pizzas so failed cooks are visible.
    private final java.util.concurrent.atomic.AtomicInteger incompleteMade =
        new java.util.concurrent.atomic.AtomicInteger(0);
    private final java.util.concurrent.atomic.AtomicInteger uncookedMade =
        new java.util.concurrent.atomic.AtomicInteger(0);
    private final java.util.concurrent.atomic.AtomicInteger plainMade =
        new java.util.concurrent.atomic.AtomicInteger(0);
    private final java.util.concurrent.atomic.AtomicInteger anchovyMade =
        new java.util.concurrent.atomic.AtomicInteger(0);
    private final java.util.concurrent.atomic.AtomicInteger burntMade =
        new java.util.concurrent.atomic.AtomicInteger(0);
    /** Snapshot of inventory plain/burnt counts at COOK_AT_RANGE entry.
     *  Cook-side counters are computed live as
     *  `entryCounter + (currentInv - entrySnapshot)` so the panel
     *  shows progress per pizza without polling bank/inv containers
     *  (which has a deposit-event race).  -1 = no snapshot yet,
     *  taken on the first cook tick after each setState. */
    private int cookSnapshotPlainInv = -1;
    private int cookSnapshotBurntInv = -1;
    /** Counter values frozen at cook-state entry — the live updater
     *  recomputes plainMade / burntMade as `entry + invDelta` rather
     *  than blindly adding, so snapshotting the cook entry value
     *  prevents drift from earlier session contributions. */
    private int cookEntryPlainMade;
    private int cookEntryBurntMade;

    // Pace / fail tracking — reset on every {@link #setState}.
    /** Wall-clock at which the next bank click is allowed.  0 = no
     *  schedule yet (next gate primes a humanized pre-open delay). */
    private long nextBankClickAtMs;
    private long lastCookActionMs;
    /** Randomized pace, rolled at each cook-click dispatch in
     *  {@link #COOK_PACE_MIN_MS}..{@link #COOK_PACE_MAX_MS}. The next
     *  cook-tick blocks until {@code now - lastCookActionMs >= cookPaceDelayMs}.
     *  Reset (0) in {@link #setState}. */
    private long cookPaceDelayMs;
    /** Wall-clock of the most recent {@code rawLeft} decrease while at
     *  the range. Anchors the batch-settle gate to actual inventory
     *  progress instead of click time — so a normal animation gap
     *  between cooked pieces doesn't trigger a re-dispatch. Mirrors
     *  {@code CookingScriptV3.lastRawDecreaseAtMs}. */
    private long lastRawDecreaseMs;
    /** Hardcoded-walk pacing — last walk-tile click + last staircase
     *  verb-click. Both reset in {@link #setState}. */
    private long lastWalkDispatchMs;
    private long lastTransportDispatchMs;
    private long lastInventoryChangeMs;
    private long bankOpenedAtMs;
    private int  bankFailures;
    private int  walkerStuckCount;
    private int  cookStuckCount;
    /** Consecutive cook-gate misses (heat source missing OR out of
     *  interaction range).  Deliberately NOT reset by {@link #setState}
     *  — that is what made the WALK_TO_RANGE↔COOK_AT_RANGE ping-pong
     *  loop infinite.  Reset only in {@link #start()} and on successful
     *  passage of both cook gates. */
    private int  cookHeatMissCount;
    private int  cookMenuConfirmAttempts;
    private int  lastUncookedCount = -1;

    // Combine sub-phase flags (mirror PieDishScript.tickCraftBatch).
    private boolean craftBankDone;       // bank trip complete, items in inv
    private boolean craftDepositDone;    // depositAllInventory verified done
    private boolean craftClicksDone;     // Use → target clicks dispatched
    private boolean skillmultiConfirmed; // Space pressed on Make-All dialog
    private long    skillmultiWaitMs;
    private long    craftWaitMs;

    // Level-up dismissal — dismiss after a humanized random delay.
    private long levelUpFirstSeenAtMs;
    private long levelUpDismissAfterMs;

    // ─── Auto-login (optional) ──────────────────────────────────────────
    // If wired by the panel before {@link #start()}, the tick loop will
    // attempt LoginAssistantV2 when the game state drops to a non-
    // LOGGED_IN value (OSRS auto-kick after >5min idle, manual logout,
    // etc.). Null = leave alone, status-only wait for manual recovery.
    private LoginAssistantV2 loginAssistant;
    private Supplier<LoginCredentials> credsSupplier;
    private Integer targetWorldId;
    private boolean jagexAccount;
    /** Wall-clock at which the next auto-login attempt is allowed.
     *  Throttles retries when login fails (network blip / world full). */
    private long nextLoginAttemptAtMs;

    public PizzaScript(Client client, ClientThread clientThread,
                       HumanizedInputDispatcher dispatcher,
                       TrailRegistry trailRegistry)
    {
        // trailRegistry kept on the constructor for call-site stability
        // and so a future variant can fall back to recorded-trail
        // replay if the hardcoded waypoints ever drift. Currently unused.
        this.client        = client;
        this.clientThread  = clientThread;
        this.dispatcher    = dispatcher;
        this.bank          = new BankInteraction(client, clientThread, dispatcher);
        this.cook          = new CookingInteraction(client, clientThread, dispatcher);
        this.sidebarTabs   = new SidebarTabActions(client, clientThread, dispatcher);
    }

    // ─── Public API ─────────────────────────────────────────────────────
    public State  state()  { return state.get(); }
    public String status() { return status.get(); }
    public boolean addTomato()    { return addTomato.get(); }
    public void setAddTomato(boolean v)    { addTomato.set(v); }
    public boolean addCheese()    { return addCheese.get(); }
    public void setAddCheese(boolean v)    { addCheese.set(v); }
    public boolean cookPizza()    { return cookPizza.get(); }
    public void setCookPizza(boolean v)    { cookPizza.set(v); }
    public boolean addAnchovies() { return addAnchovies.get(); }
    public void setAddAnchovies(boolean v) { addAnchovies.set(v); }
    public boolean afkBreaksEnabled() { return afkBreaksEnabled.get(); }
    public void setAfkBreaksEnabled(boolean v)
    {
        afkBreaksEnabled.set(v);
        if (breaks != null)
        {
            if (v) breaks.enable(System.currentTimeMillis());
            else   breaks.disable();
        }
    }
    /** Panel accessor for the break-status countdown line. */
    public String breakStatus()
    {
        return breaks == null
            ? "breaks: idle (script not running)"
            : breaks.statusLine(System.currentTimeMillis());
    }
    public int incompleteMade() { return incompleteMade.get(); }
    public int uncookedMade()   { return uncookedMade.get(); }
    public int plainMade()      { return plainMade.get(); }
    public int anchovyMade()    { return anchovyMade.get(); }
    public int burntMade()      { return burntMade.get(); }

    /** Wire optional auto-login. Call before {@link #start()}. When set,
     *  the tick loop attempts {@link LoginAssistantV2#login} whenever the
     *  game state isn't LOGGED_IN (OSRS auto-kick after long idle, etc.)
     *  so the script resumes without manual intervention. Pass null
     *  credsSupplier to disable. */
    public void setAutoLogin(LoginAssistantV2 la,
                             Supplier<LoginCredentials> credsSupplier,
                             Integer worldId,
                             boolean jagex)
    {
        this.loginAssistant = la;
        this.credsSupplier  = credsSupplier;
        this.targetWorldId  = worldId;
        this.jagexAccount   = jagex;
    }

    public void start()
    {
        Thread existing = worker.get();
        if (existing != null && existing.isAlive())
        {
            status.set("already running");
            return;
        }
        if (!running.compareAndSet(false, true)) return;

        // Reset session counters every start.  Baselines are -1 (next
        // tally seeds them); the AtomicInteger counts are zeroed so
        // a stop+restart shows fresh throughput numbers in the panel.
        incompleteMade.set(0);
        uncookedMade.set(0);
        plainMade.set(0);
        anchovyMade.set(0);
        burntMade.set(0);
        cookSnapshotPlainInv = -1;
        cookSnapshotBurntInv = -1;
        cookHeatMissCount    = 0;

        // Fresh scheduler each Start — clean activity window.  start()
        // is guarded by running.compareAndSet so the construct/set isn't
        // racy with the worker thread, which spawns AFTER this line.
        breaks = new net.runelite.client.plugins.recorder.afk.BreakScheduler(
            System::currentTimeMillis,
            ThreadLocalRandom.current(),
            afkBreaksEnabled.get());

        nextLoginAttemptAtMs = 0L;

        State decided = decideResume();
        log.info("pizza: resume → {} ({})", decided, status.get());
        setState(decided);
        if (decided == State.IDLE || decided == State.ABORTED || decided == State.DONE)
        {
            running.set(false);
            return;
        }
        Thread t = new Thread(this::tickLoop, "pizza-script");
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
            try { t.join(2_000); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
        // Halt the scheduler explicitly — otherwise the panel's refresh
        // timer keeps polling breaks.statusLine() and the countdown
        // visually ticks forward even though the worker has exited.
        if (breaks != null) breaks.disable();
        setState(State.IDLE);
        status.set("stopped");
    }

    // ─── Resume decision ────────────────────────────────────────────────
    /** Pick an initial state from the current world position + inventory.
     *  Mirrors {@link CookingScriptV3#decideResume} but pizza-specific.
     *  Players who start mid-route aren't supported — the trails are
     *  fixed bank↔kitchen and we have no generic walker that can
     *  pathfind across planes. */
    private State decideResume()
    {
        WorldPoint here = playerPos();
        if (here == null) { status.set("no player — abort"); return State.ABORTED; }

        boolean atBank    = areaContains(BANK_AREA, here);
        boolean atKitchen = areaContains(KITCHEN_AREA, here);
        int invUncooked   = invCount(UNCOOKED_PIZZA);
        int invPlain      = invCount(PLAIN_PIZZA);
        int invBurnt      = invCount(BURNT_PIZZA);
        int invIncomplete = invCount(INCOMPLETE_PIZZA);

        if (atBank)
        {
            status.set("starting at bank — planning next loop");
            return State.DECIDE;
        }
        if (atKitchen && invUncooked > 0)
        {
            status.set("starting at kitchen with uncooked pizzas — cooking");
            return State.COOK_AT_RANGE;
        }
        if (atKitchen && (invPlain > 0 || invBurnt > 0 || invIncomplete > 0))
        {
            status.set("starting at kitchen with cooked/in-flight pizzas — heading back to bank");
            return State.WALK_TO_BANK;
        }
        if (atKitchen)
        {
            // Empty inv at kitchen — just walk back to bank and re-plan.
            status.set("starting at kitchen with empty inventory — heading to bank");
            return State.WALK_TO_BANK;
        }
        status.set("ABORTED: must start at Lumbridge bank (p2) or in Cook's kitchen (p0)");
        return State.ABORTED;
    }

    // ─── Tick loop ──────────────────────────────────────────────────────
    private void tickLoop()
    {
        try
        {
            // Post-login the inventory tab is often closed.  Every craft
            // click reads the inventory items widget; opening at startup
            // avoids a slow first tick.
            if (!sidebarTabs.openTabAndWait(SidebarTab.INVENTORY, 2_000L))
                log.debug("pizza: could not confirm inventory tab open at startup");

            while (running.get() && !Thread.currentThread().isInterrupted())
            {
                // Auto-login gate. If the OSRS auto-kick fired during a
                // long idle (or the user manually logged out) we'll see
                // GameState != LOGGED_IN. With creds wired by the panel,
                // attempt LoginAssistantV2; otherwise just wait for a
                // manual login and let the worker resume in place.
                GameState gs = onClient(client::getGameState);
                if (gs != null && gs != GameState.LOGGED_IN)
                {
                    handleLoggedOut(gs);
                    SequenceSleep.sleep(client, TICK_MS);
                    continue;
                }

                if (playerPos() == null)
                {
                    status.set("waiting for player (loading?)");
                    SequenceSleep.sleep(client, TICK_MS);
                    continue;
                }

                // AFK-break gate.  Must run BEFORE safeDismissLevelUp
                // (which can dispatch a click) AND before the state
                // switch (which dispatches per-state work).  Break is
                // only allowed to START when state==DECIDE — between
                // bank trips, never mid-cook/walk.  endBreakIfDue runs
                // first so the wake-up tick proceeds to normal work
                // immediately rather than wasting a tick.
                long breakNow = System.currentTimeMillis();
                if (breaks != null) breaks.endBreakIfDue(breakNow);
                if (breaks != null && breaks.isInBreak(breakNow))
                {
                    status.set(breaks.statusLine(breakNow));
                    SequenceSleep.sleep(client, TICK_MS);
                    continue;
                }
                if (breaks != null
                    && breaks.isBreakDue(breakNow, state.get() == State.DECIDE))
                {
                    breaks.startBreak(breakNow);
                    status.set(breaks.statusLine(breakNow));
                    SequenceSleep.sleep(client, TICK_MS);
                    continue;
                }

                safeDismissLevelUp();
                // Cook progress (plain + burnt) updates live each tick
                // while we're at the range — combine outputs are added
                // event-based at batch-done in tickCombineAtBank.
                if (state.get() == State.COOK_AT_RANGE) updateCookCountersLive();

                switch (state.get())
                {
                    case DECIDE                    -> tickDecide();
                    // Click order: source-item first (clicked into use-mode),
                    // then target.  Pick whichever input has "Use" as its
                    // default left-click action so the dispatcher's verified
                    // click goes left-click straight into use-mode instead of
                    // right-click → "Use" pick (one less menu interaction
                    // per batch).
                    //  - PIZZA_BASE: Use default; TOMATO: Eat default → use base first
                    //  - INCOMPLETE_PIZZA: Use default; CHEESE: Eat default → use incomplete first
                    //  - PLAIN_PIZZA + ANCHOVIES: both Eat default — order doesn't help; pizza first by convention
                    case COMBINE_BASE_TOMATO       ->
                        tickCombineAtBank(PIZZA_BASE, TOMATO, INCOMPLETE_PIZZA);
                    case COMBINE_INCOMPLETE_CHEESE ->
                        tickCombineAtBank(INCOMPLETE_PIZZA, CHEESE, UNCOOKED_PIZZA);
                    case COMBINE_PLAIN_ANCHOVIES   ->
                        tickCombineAtBank(PLAIN_PIZZA, ANCHOVIES_COOKED, ANCHOVY_PIZZA);
                    case WALK_TO_RANGE             -> tickWalkToRange();
                    case COOK_AT_RANGE             -> tickCookAtRange();
                    case WALK_TO_BANK              -> tickWalkToBank();
                    case DONE, ABORTED, IDLE       -> running.set(false);
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

    // ─── DECIDE ─────────────────────────────────────────────────────────
    /** At the bank, pick which loop to run next based on what the bank
     *  + inventory hold.  Order is the user's batch sequence (loops 1 →
     *  2 → 3 → 4); inside each priority bucket we deposit any leftover
     *  inventory first so the next phase starts with a clean slate. */
    private void tickDecide() throws InterruptedException
    {
        if (Boolean.TRUE.equals(onClient(bank::isBankPinUp)))
        {
            abortWith("bank PIN required — enter manually then restart");
            return;
        }
        // Don't gate on strict playerInArea(BANK_AREA): if the user
        // recorded the kitchen→bank trail with an end tile a step
        // outside the box, DECIDE → WALK_TO_BANK → walker ARRIVED →
        // DECIDE would loop forever.  bank.ensureBoothInClickRange
        // below is the truth source — if no booth/banker is reachable,
        // we abort with a clear message instead.
        long now = System.currentTimeMillis();
        if (!bank.isBankOpen())
        {
            // First touch of this bank trip — schedule a humanized
            // pre-open delay rather than clicking immediately.  Lets
            // the player visibly "settle" after the walk-up.
            if (nextBankClickAtMs == 0L)
            {
                schedulePreOpen();
                status.set("decide: settling before opening bank");
                return;
            }
            if (now < nextBankClickAtMs) { status.set("decide: pacing"); return; }
            if (dispatcher.isBusy()) { status.set("decide: dispatcher busy"); return; }
            BankInteraction.BoothPrep prep = bank.ensureBoothInClickRange();
            if (prep == BankInteraction.BoothPrep.NO_CANDIDATE)
            {
                abortWith("decide: no booth in range — start at the Lumbridge bank");
                return;
            }
            if (prep == BankInteraction.BoothPrep.WALKED_CLOSER)
            {
                status.set("decide: walking closer to booth");
                return;
            }
            status.set("decide: opening bank");
            bank.tryClickBankBoothRandom();
            scheduleOpenWait();
            bankOpenedAtMs = now;
            return;
        }
        if (!bank.bankReady())
        {
            if (bankOpenedAtMs == 0L) bankOpenedAtMs = now;
            if (now - bankOpenedAtMs > BANK_LOAD_GRACE_MS + 4_000L)
            {
                bank.tryCloseBank();
                abortWith("decide: bank container did not load");
                return;
            }
            status.set("decide: bank loading");
            return;
        }

        // Always deposit anything in inventory first — gives a clean
        // slate for whichever loop we pick next.  Verified: returns true
        // only after every slot is empty.
        if (inventorySlotsUsed() > 0)
        {
            status.set("decide: depositing leftover inventory");
            if (!bank.depositAllInventory())
            {
                if (++bankFailures >= MAX_BANK_FAILURES)
                {
                    abortWith("decide: deposit-all-inventory failed " + bankFailures + "× in a row");
                    return;
                }
                return;
            }
            bankFailures = 0;
            return;     // re-enter next tick to plan with empty inv
        }

        long bankBase    = bank.bankItemAmount(PIZZA_BASE);
        long bankTomato  = bank.bankItemAmount(TOMATO);
        long bankIncompl = bank.bankItemAmount(INCOMPLETE_PIZZA);
        long bankCheese  = bank.bankItemAmount(CHEESE);
        long bankUncook  = bank.bankItemAmount(UNCOOKED_PIZZA);
        long bankPlain   = bank.bankItemAmount(PLAIN_PIZZA);
        long bankAnchov  = bank.bankItemAmount(ANCHOVIES_COOKED);

        log.info("pizza decide: bank base={} tomato={} incomplete={} cheese={} uncooked={} plain={} anchovies={} flags(tomato={} cheese={} cook={} anchovies={})",
            bankBase, bankTomato, bankIncompl, bankCheese, bankUncook, bankPlain, bankAnchov,
            addTomato.get(), addCheese.get(), cookPizza.get(), addAnchovies.get());

        // Loop 1 — pizza base + tomato.  Skipped when the panel
        // checkbox is off, even if both inputs are stocked.
        if (addTomato.get() && bankBase > 0 && bankTomato > 0)
        {
            log.info("pizza: → COMBINE_BASE_TOMATO ({} bases, {} tomatoes)", bankBase, bankTomato);
            setState(State.COMBINE_BASE_TOMATO);
            return;
        }
        // Loop 2 — incomplete pizza + cheese.
        if (addCheese.get() && bankIncompl > 0 && bankCheese > 0)
        {
            log.info("pizza: → COMBINE_INCOMPLETE_CHEESE ({} incomplete, {} cheese)", bankIncompl, bankCheese);
            setState(State.COMBINE_INCOMPLETE_CHEESE);
            return;
        }
        // Loop 3 — cook uncooked pizza on the range.  Skipped when
        // the panel checkbox is off, e.g. user wants to stockpile
        // uncooked pizza in the bank without cooking yet.
        if (cookPizza.get() && bankUncook > 0)
        {
            // Always Withdraw-All. Cooking fills the inventory (no
            // by-products); the bank caps it at free slots (28) so we
            // never spill. Withdraw-X for a 252-stack would type "28"
            // in the chatbox every single trip, which is both slow and
            // an obvious bot tell. The verb-scan retry overhead on
            // partial withdraws (seen in logs at 21:16:11-13) also
            // disappears — Withdraw-All is a single right-click pick.
            status.set("decide: withdraw-all uncooked pizza (bank=" + bankUncook + ")");
            boolean ok = bank.tryWithdrawAll(UNCOOKED_PIZZA);
            scheduleInBankAction();
            if (!ok)
            {
                if (++bankFailures >= MAX_BANK_FAILURES)
                {
                    abortWith("decide: withdraw uncooked pizza failed " + bankFailures + "×");
                    return;
                }
                return;
            }
            bankFailures = 0;
            bank.tryCloseBank();
            // Verify the bank actually closed before transitioning so the
            // walker doesn't try to step away with the bank widget still
            // intercepting clicks.
            SequenceSleep.sleep(client, nextCloseVerifyMs());
            if (!Boolean.TRUE.equals(onClient(bank::isBankOpen)))
            {
                log.info("pizza: → WALK_TO_RANGE with {} uncooked pizzas", invCount(UNCOOKED_PIZZA));
                setState(State.WALK_TO_RANGE);
            }
            return;
        }
        // Loop 4 — anchovy topping (optional).
        if (addAnchovies.get() && bankPlain > 0 && bankAnchov > 0)
        {
            log.info("pizza: → COMBINE_PLAIN_ANCHOVIES ({} plain, {} anchovies)", bankPlain, bankAnchov);
            setState(State.COMBINE_PLAIN_ANCHOVIES);
            return;
        }

        // Nothing left to do.
        bank.tryCloseBank();
        log.info("pizza: nothing left to make — DONE");
        status.set("DONE — bank stocks exhausted for selected phases");
        setState(State.DONE);
    }

    // ─── Combine (loops 1, 2, 4) — shared shape ─────────────────────────
    /** "Use {@code useId} on {@code targetId} → Make-All {@code productId}"
     *  loop.  Withdraws {@link #COMBINE_BATCH} of each input per trip,
     *  fires the click chain, polls inventory until either input runs
     *  out, then re-banks.
     *
     *  <p>When either bank stack hits zero, transitions to
     *  {@link State#DECIDE} so the next loop can be picked up
     *  (DECIDE will deposit any partial leftovers first). */
    private void tickCombineAtBank(int useId, int targetId, int productId)
        throws InterruptedException
    {
        // Bank phase — open / deposit / withdraw / close.
        if (!craftBankDone)
        {
            tickCombineBanking(useId, targetId);
            return;
        }

        // ── Craft phase ──
        long now = System.currentTimeMillis();

        // Sub-phase 1: send Use(useId) → click(targetId).
        if (!craftClicksDone)
        {
            // Closing the bank restores whatever sidebar tab was active
            // before banking; re-assert inventory or the slot lookups
            // below come back null.
            if (!ensureInventoryTabOpen())
            {
                status.set("craft: opening inventory tab");
                return;
            }
            if (dispatcher.isBusy())
            {
                status.set("craft: dispatcher busy");
                return;
            }

            int useSlot = invSlotOf(useId);
            if (useSlot < 0)
            {
                log.info("pizza craft: use-item {} gone from inv — reopening bank", useId);
                craftBankDone    = false;
                craftDepositDone = false;
                return;
            }

            // Step A — engage use-mode on the source item.
            dispatcher.dispatch(ActionRequest.builder()
                .kind(ActionRequest.Kind.CLICK_INV_ITEM)
                .channel(ActionRequest.Channel.MOUSE)
                .slot(useSlot)
                .verb("Use")
                .build());
            dispatcher.awaitIdle(3_000L);
            String err = dispatcher.lastErrorMessage();
            if (err != null)
            {
                log.warn("pizza craft: Use click error: {}", err);
                return;
            }

            // Brief settle so use-mode is registered before the second click.
            SequenceSleep.sleep(client, 350L);

            // Step B — click the target item slot.
            Rectangle bounds = onClient(() -> resolveInvItemBounds(targetId));
            if (bounds == null)
            {
                log.warn("pizza craft: target {} slot not resolvable — re-banking", targetId);
                craftBankDone    = false;
                craftDepositDone = false;
                return;
            }
            dispatcher.clickCanvas(bounds.x + bounds.width / 2,
                                    bounds.y + bounds.height / 2);
            dispatcher.awaitIdle(3_000L);

            craftClicksDone  = true;
            skillmultiWaitMs = now;
            status.set("craft: clicks dispatched — waiting for Make dialog");
            return;
        }

        // Sub-phase 2: wait for Skillmulti, then Space.
        if (!skillmultiConfirmed)
        {
            boolean open = Boolean.TRUE.equals(onClient(() -> {
                Widget w = client.getWidget(InterfaceID.Skillmulti.UNIVERSE);
                return w != null && !w.isHidden();
            }));
            if (!open)
            {
                long elapsed = now - skillmultiWaitMs;
                if (elapsed > SKILLMULTI_TIMEOUT_MS)
                {
                    log.warn("pizza craft: Skillmulti did not open after {}ms — retrying", elapsed);
                    // Stuck right-click menu ≠ stuck OSRS — dismiss any
                    // leftover menu before re-dispatching the chain
                    // (CLAUDE.md §8: a stuck menu blocks the cs2 that
                    // would open the dialog).
                    dispatcher.dismissMenu();
                    craftClicksDone  = false;
                    skillmultiWaitMs = 0L;
                }
                else
                {
                    status.set("craft: waiting for Make dialog (" + elapsed + "ms)");
                }
                return;
            }
            // Pizza recipes are single-output Skillmulti dialogs (one
            // recipe slot, ALL pre-selected) — Space picks it.  No
            // recipe-option widget click needed (vs. the 4-option flour
            // + water dialog that PieDishScript navigates).
            status.set("craft: Make dialog open — Space");
            sleepCookConfirmReaction();
            dispatcher.tapKey(java.awt.event.KeyEvent.VK_SPACE);
            skillmultiConfirmed = true;
            craftWaitMs         = now;
            return;
        }

        // Sub-phase 3: wait for the batch to finish.
        int useLeft    = invCount(useId);
        int targetLeft = invCount(targetId);

        if (useLeft == 0 || targetLeft == 0)
        {
            int productCount = invCount(productId);
            log.info("pizza craft: batch done — use={} target={} product={}",
                useLeft, targetLeft, productCount);
            // Event-based count: this batch produced exactly productCount
            // of the product (could be < batch size if an input ran out
            // mid-batch).  Atomic add — no race with bank/inv container
            // updates, no double-counting from polling.
            if      (productId == INCOMPLETE_PIZZA) incompleteMade.addAndGet(productCount);
            else if (productId == UNCOOKED_PIZZA)   uncookedMade.addAndGet(productCount);
            else if (productId == ANCHOVY_PIZZA)    anchovyMade.addAndGet(productCount);
            // Reset for the next batch — banking phase re-enters.
            craftBankDone       = false;
            craftDepositDone    = false;
            craftClicksDone     = false;
            skillmultiConfirmed = false;
            craftWaitMs         = 0L;
            return;
        }

        // Re-confirm Skillmulti if it reappears (rare — engine can
        // re-render the dialog mid-batch on item swap).
        boolean dialogStillOpen = Boolean.TRUE.equals(onClient(() -> {
            Widget w = client.getWidget(InterfaceID.Skillmulti.UNIVERSE);
            return w != null && !w.isHidden();
        }));
        if (dialogStillOpen)
        {
            sleepCookConfirmReaction();
            dispatcher.tapKey(java.awt.event.KeyEvent.VK_SPACE);
        }

        long elapsed = now - craftWaitMs;
        if (elapsed > CRAFT_TIMEOUT_MS)
        {
            abortWith("craft timeout — " + targetLeft + " of itemId=" + targetId
                + " still in inventory after " + elapsed + "ms");
            return;
        }
        status.set("craft: making (use=" + useLeft + " target=" + targetLeft
            + ", " + elapsed + "ms)");
    }

    /** Banking sub-phase of {@link #tickCombineAtBank}: open booth →
     *  deposit-all → withdraw {@link #COMBINE_BATCH} of each input.
     *  When either bank stack runs out, transitions to
     *  {@link State#DECIDE} (which will pick the next loop or finish). */
    private void tickCombineBanking(int useId, int targetId) throws InterruptedException
    {
        if (Boolean.TRUE.equals(onClient(bank::isBankPinUp)))
        {
            abortWith("bank PIN required — enter manually");
            return;
        }
        long now = System.currentTimeMillis();

        if (!bank.isBankOpen())
        {
            // First touch of this bank trip — humanized pre-open delay.
            if (nextBankClickAtMs == 0L)
            {
                schedulePreOpen();
                status.set("bank: settling before opening");
                return;
            }
            if (now < nextBankClickAtMs) { status.set("bank: pacing"); return; }
            if (dispatcher.isBusy()) { status.set("bank: dispatcher busy"); return; }
            BankInteraction.BoothPrep prep = bank.ensureBoothInClickRange();
            if (prep == BankInteraction.BoothPrep.NO_CANDIDATE)
            {
                abortWith("bank: no booth in range");
                return;
            }
            if (prep == BankInteraction.BoothPrep.WALKED_CLOSER)
            {
                status.set("bank: walking closer to booth");
                return;
            }
            status.set("bank: opening");
            bank.tryClickBankBoothRandom();
            scheduleOpenWait();
            bankOpenedAtMs = now;
            return;
        }
        if (!bank.bankReady())
        {
            if (now - bankOpenedAtMs > BANK_LOAD_GRACE_MS + 4_000L)
            {
                bank.tryCloseBank();
                abortWith("bank: container did not load");
                return;
            }
            status.set("bank: loading");
            return;
        }

        // Deposit anything left in inventory before withdrawing — the
        // previous batch's combined product (e.g. incomplete_pizza) is
        // still sitting in inventory at this point.
        if (!craftDepositDone)
        {
            if (inventorySlotsUsed() == 0)
            {
                craftDepositDone = true;
            }
            else
            {
                status.set("bank: depositing leftover inventory");
                if (!bank.depositAllInventory())
                {
                    if (++bankFailures >= MAX_BANK_FAILURES)
                    {
                        abortWith("bank: deposit-all-inventory failed " + bankFailures + "×");
                        return;
                    }
                    return;
                }
                bankFailures     = 0;
                craftDepositDone = true;
                return;
            }
        }

        long bankUse    = bank.bankItemAmount(useId);
        long bankTarget = bank.bankItemAmount(targetId);
        int  invUse     = invCount(useId);
        int  invTarget  = invCount(targetId);

        // Out of inputs only when bank AND inv are empty for that side.
        // Withdraw-All drains the bank stack to 0 while moving items into
        // inventory, so checking bank alone bails one tick after a clean
        // withdraw and never reaches the craft step.
        if (bankUse + invUse <= 0 || bankTarget + invTarget <= 0)
        {
            log.info("pizza bank: ran out of inputs (use bank={}/inv={}, target bank={}/inv={}) — replanning",
                bankUse, invUse, bankTarget, invTarget);
            bank.tryCloseBank();
            setState(State.DECIDE);
            return;
        }

        // Withdraw enough of each input to fill a 14+14 batch (or less
        // if one input is short).  Batch shrinks to the smaller total
        // (inv+bank) so we don't waste a withdraw of one input we can't pair.
        int batchSize = (int) Math.min(COMBINE_BATCH,
            Math.min(bankUse + invUse, bankTarget + invTarget));

        if (invUse < batchSize)
        {
            if (now < nextBankClickAtMs) { status.set("bank: pacing withdraw"); return; }
            int need = batchSize - invUse;
            status.set("bank: withdraw " + need + " of useId=" + useId);
            boolean ok = (bankUse <= need)
                ? bank.tryWithdrawAll(useId)
                : bank.tryWithdrawX(useId, need);
            scheduleInBankAction();
            if (!ok)
            {
                if (++bankFailures >= MAX_BANK_FAILURES)
                {
                    abortWith("bank: withdraw use-item " + useId + " failed " + bankFailures + "×");
                    return;
                }
            }
            else { bankFailures = 0; }
            return;
        }
        if (invTarget < batchSize)
        {
            if (now < nextBankClickAtMs) { status.set("bank: pacing withdraw"); return; }
            int need = batchSize - invTarget;
            status.set("bank: withdraw " + need + " of targetId=" + targetId);
            boolean ok = (bankTarget <= need)
                ? bank.tryWithdrawAll(targetId)
                : bank.tryWithdrawX(targetId, need);
            scheduleInBankAction();
            if (!ok)
            {
                if (++bankFailures >= MAX_BANK_FAILURES)
                {
                    abortWith("bank: withdraw target-item " + targetId + " failed " + bankFailures + "×");
                    return;
                }
            }
            else { bankFailures = 0; }
            return;
        }

        // Both inputs in inventory at batch size — close + commit to
        // crafting.
        if (now < nextBankClickAtMs) { status.set("bank: pacing close"); return; }
        status.set("bank: closing — " + invUse + "/" + invTarget + " inputs ready");
        bank.tryCloseBank();
        scheduleInBankAction();
        SequenceSleep.sleep(client, nextCloseVerifyMs());
        if (!Boolean.TRUE.equals(onClient(bank::isBankOpen)))
        {
            craftBankDone = true;
            log.info("pizza bank: ready to craft — useId={} targetId={} (inv={}/{})",
                useId, targetId, invCount(useId), invCount(targetId));
        }
    }

    // ─── Walks — two explicit modes ─────────────────────────────────────
    // Each mode is a short stateless tick: arrived? → transition;
    // wrong plane? → staircase leg; right plane? → in-plane walk to a
    // varied tile in the destination area. The dispatcher picks
    // canvas-vs-minimap from the tile distance and visibility — short
    // walks inside the kitchen / bank stay on canvas, the long
    // bank↔staircase haul falls through to minimap.
    //
    // No Navigator / TrailWalker — v2.1 had repeated trouble keeping
    // the cursor on the staircase hull during camera follow (logged
    // 2026-05-19). Two waypoints + one verb-click is simpler and more
    // reliable than any smart planner second-guessing the same route.

    /** Bank (plane=2) → kitchen range (plane=0).
     *
     *  <p>On p=2: descend the staircase (approach-walk + verb-click).
     *
     *  <p>On p=0: short walk into {@link #KITCHEN_AREA}. Camera-only
     *  doesn't work here — the player lands ~7 tiles south of the
     *  range, the model's projected hull sits below the world-view
     *  area of the canvas (overlapping the side-panel UI), and the
     *  cook tick's verify-click lands on UI instead of the model
     *  (logged 2026-05-19 22:46 — bounds y≈1281, cursor over UI tabs,
     *  menu='Cancel'). A 5-tile walk north puts the player inside the
     *  kitchen with the range in the middle of the canvas. */
    private void tickWalkToRange() throws InterruptedException
    {
        WorldPoint here = playerPos();
        if (here == null) { status.set("→range: waiting for player"); return; }

        if (areaContains(KITCHEN_AREA, here))
        {
            log.info("pizza →range: ARRIVED at {} in kitchen", here);
            walkerStuckCount = 0;
            setState(State.COOK_AT_RANGE);
            return;
        }
        if (here.getPlane() != 0)
        {
            tickStaircase(here, STAIRCASE_DOWN_TILE, APPROACH_DOWN_TILES, "Bottom-floor");
            return;
        }
        // On p=0 but not yet in kitchen — short walk via engine
        // pathfinding (no walls between staircase landing and kitchen).
        tickInPlaneWalk(KITCHEN_AREA, "→ kitchen");
    }

    /** Kitchen range (plane=0) → bank (plane=2).
     *
     *  <p>On p=0: approach-walk to {@link #APPROACH_UP_TILES}, then
     *  verb-click "Top-floor" on {@link #STAIRCASE_UP_TILE}. Same
     *  shape as the descend leg — clicking from the kitchen (~8 tiles
     *  away) produced unresolvable pixel loops because the staircase
     *  tile failed to project, no matter how the camera was rotated.
     *
     *  <p>On p=2: walk to a bank booth in {@link #BANK_AREA}. */
    private void tickWalkToBank() throws InterruptedException
    {
        WorldPoint here = playerPos();
        if (here == null) { status.set("→bank: waiting for player"); return; }

        if (areaContains(BANK_AREA, here))
        {
            log.info("pizza →bank: ARRIVED at {} in bank", here);
            walkerStuckCount = 0;
            setState(State.DECIDE);
            return;
        }
        if (here.getPlane() == 0)
        {
            tickStaircase(here, STAIRCASE_UP_TILE, APPROACH_UP_TILES, "Top-floor");
            return;
        }
        // On p=2 — walk to a bank booth.
        tickInPlaneWalk(BANK_AREA, "→ bank");
    }

    /** Shared staircase leg: approach via a varied tile in
     *  {@code approachZone}, then dispatch the verb-click on
     *  {@code stairTile}. Settle after click; skip re-dispatch while
     *  the player is mid-step or busy. */
    private void tickStaircase(WorldPoint here, WorldPoint stairTile,
                                List<WorldPoint> approachTiles, String verb) throws InterruptedException
    {
        long now = System.currentTimeMillis();

        if (now - lastTransportDispatchMs < TRANSPORT_SETTLE_MS)
        {
            status.set("walk: " + verb + " settle ("
                + (now - lastTransportDispatchMs) + "ms)");
            return;
        }
        if (dispatcher.isBusy()) { status.set("walk: dispatcher busy"); return; }

        int dist = (here.getPlane() == stairTile.getPlane())
            ? chebyshev(here, stairTile)
            : Integer.MAX_VALUE;

        if (dist > 2)
        {
            if (now - lastWalkDispatchMs < WALK_DISPATCH_PACE_MS)
            {
                status.set("walk: approach " + verb);
                return;
            }
            if (!playerIsIdle())
            {
                status.set("walk: walking → " + verb);
                return;
            }
            // Only pick tiles that put the player within chebyshev 2 of
            // the stair tile — otherwise the dist>2 gate above kicks
            // back in after arrival and we burn another walk-dispatch
            // round trip. Observed 3-walk sequence 2026-05-20: 8-tile
            // marker had only 3 close enough. The user's marker is the
            // safe-walk zone (no hull-overlap); we further filter to
            // the stair-clickable subset.
            List<WorldPoint> reachable = approachTiles.stream()
                .filter(t -> chebyshev(t, stairTile) <= 2)
                .toList();
            if (reachable.isEmpty()) reachable = approachTiles;
            WorldPoint pick = randomTileIn(reachable);
            log.info("pizza walk: approaching '{}' staircase → {}", verb, pick);
            dispatchWalk(pick);
            status.set("walk: → " + verb + " staircase " + pick);
            return;
        }

        // Don't dispatch the verb-click while the player is still walking
        // the last tile into the approach zone. dist≤2 holds during that
        // walk-through, so the click would resolve a hull pixel against
        // the camera's current frame, then by the time cursor flight
        // (~200-500ms) lands, the camera has tracked the still-walking
        // player and the staircase has shifted on screen. Symptom: cursor
        // ends up on a 'Walk here' tile or an unrelated NPC. Same gate
        // tickCookAtRange uses before the range click — 2026-05-20.
        if (!playerIsIdle())
        {
            status.set("walk: arriving at " + verb);
            return;
        }

        log.info("pizza walk: clicking '{}' on staircase tile {}", verb, stairTile);
        dispatcher.dispatch(ActionRequest.builder()
            .kind(ActionRequest.Kind.CLICK_GAME_OBJECT)
            .channel(ActionRequest.Channel.MOUSE)
            .tile(stairTile)
            .verb(verb)
            .ensureVisibleRotation(true)
            .build());
        lastTransportDispatchMs = now;
        lastWalkDispatchMs      = 0L;
        status.set("walk: clicked " + verb);
    }

    /** Same-plane walk leg: pick a fresh random tile inside the
     *  destination area each dispatch. Throttled so we don't refresh
     *  the click target every tick while the player is walking. */
    private void tickInPlaneWalk(WorldArea destArea, String label) throws InterruptedException
    {
        long now = System.currentTimeMillis();
        if (now - lastWalkDispatchMs < WALK_DISPATCH_PACE_MS)
        {
            status.set("walk: pacing " + label);
            return;
        }
        if (dispatcher.isBusy()) { status.set("walk: dispatcher busy"); return; }
        if (!playerIsIdle())
        {
            status.set("walk: walking " + label);
            return;
        }

        WorldPoint target = randomTileIn(destArea);
        dispatchWalk(target);
        status.set("walk: " + label + " " + target);
    }

    /** Uniform random pick inside {@code area} (single-tile areas pick
     *  themselves). Used by both the staircase approach and the
     *  in-plane walks so neither ever clicks the exact same tile two
     *  trips in a row. */
    private static WorldPoint randomTileIn(WorldArea area)
    {
        int dx = ThreadLocalRandom.current().nextInt(Math.max(1, area.getWidth()));
        int dy = ThreadLocalRandom.current().nextInt(Math.max(1, area.getHeight()));
        return new WorldPoint(area.getX() + dx, area.getY() + dy, area.getPlane());
    }

    /** Uniform random pick from {@code tiles}. Used by the staircase
     *  approach where the safe-walk zone is an irregular tile set marked
     *  in-game (cannot be expressed as a single {@link WorldArea}). */
    private static WorldPoint randomTileIn(List<WorldPoint> tiles)
    {
        return tiles.get(ThreadLocalRandom.current().nextInt(tiles.size()));
    }

    /** Chebyshev (king-move) distance, ignoring plane. */
    private static int chebyshev(WorldPoint a, WorldPoint b)
    {
        return Math.max(Math.abs(a.getX() - b.getX()),
                        Math.abs(a.getY() - b.getY()));
    }

    /** Walk-click dispatch + book-keep. Kind.WALK lets the dispatcher
     *  prefer a canvas (tile) click when the target is visible and
     *  walkable, falling through to minimap for off-canvas targets. */
    private void dispatchWalk(WorldPoint target) throws InterruptedException
    {
        dispatcher.dispatch(ActionRequest.builder()
            .kind(ActionRequest.Kind.WALK)
            .channel(ActionRequest.Channel.MOUSE)
            .tile(target)
            .build());
        lastWalkDispatchMs = System.currentTimeMillis();
    }

    // ─── COOK_AT_RANGE ──────────────────────────────────────────────────
    /** Cook every uncooked pizza in inventory on the kitchen range.
     *  Same shape as {@link CookingScriptV3#tickCooking} but always uses
     *  the named "Cooking range" object (no fire-from-logs branch) and locks the
     *  raw-food id to {@link #UNCOOKED_PIZZA}. */
    private void tickCookAtRange() throws InterruptedException
    {
        long now = System.currentTimeMillis();
        int rawLeft = invCount(UNCOOKED_PIZZA);
        int rawId   = UNCOOKED_PIZZA;

        // Cook menu open → confirm.  First two attempts use Space (the
        // canonical Cook-All key), fall back to a direct Skillmulti.ALL
        // widget click if Space isn't taking effect (engine drift).
        if (cook.isCookMenuOpen())
        {
            cookMenuConfirmAttempts++;
            // Humanized reaction time before the Space tap — see
            // {@link #sleepCookConfirmReaction}. Same window for the
            // widget-click fallback.
            sleepCookConfirmReaction();
            if (cookMenuConfirmAttempts <= COOK_MENU_FALLBACK_AFTER)
            {
                status.set("cook: confirming Cook All (Space)");
                cook.confirmCookAll();
            }
            else
            {
                status.set("cook: confirming Cook All (widget click)");
                cook.clickCookAllWidget();
            }
            if (cookMenuConfirmAttempts == 1) lastInventoryChangeMs = now;
            return;
        }
        cookMenuConfirmAttempts = 0;

        // Track inventory change to detect stuck/no-progress AND to
        // anchor the batch-settle gate. A real cook reduces rawLeft by 1
        // every ~3-5 s; stamping lastRawDecreaseMs each time means we
        // can tell "still cooking" apart from "animation gap, time to
        // re-fire" without depending on the cook-pose pose-id (which
        // briefly drops to idle between pieces).
        if (rawLeft != lastUncookedCount)
        {
            if (lastUncookedCount >= 0 && rawLeft < lastUncookedCount)
                lastRawDecreaseMs = now;
            lastUncookedCount     = rawLeft;
            lastInventoryChangeMs = now;
            cookStuckCount        = 0;
        }
        if (lastInventoryChangeMs == 0L) lastInventoryChangeMs = now;

        if (rawLeft == 0)
        {
            log.info("pizza cook: out of uncooked — heading back to bank");
            status.set("cook: out of uncooked pizza — back to bank");
            setState(State.WALK_TO_BANK);
            return;
        }

        CookingInteraction.Match heat = cook.findHeatSource("Cooking range");
        WorldPoint pp = playerPos();
        if (heat == null)
        {
            // Tolerate a few ticks for scene-load after a transport
            // (Bottom-floor click teleports + scene re-streams), but
            // do NOT bounce back to WALK_TO_RANGE — the trail's last
            // leg ends here, walking again just lands us in the same
            // spot and re-enters this branch (the FSM ping-pong bug
            // we hit on 2026-05-06).  Diagnose loudly, then abort.
            cookHeatMissCount++;
            log.warn("pizza cook: heat source 'Cooking range' not in scene "
                + "radius (player={}, miss {}/{})",
                pp, cookHeatMissCount, COOK_MAX_HEAT_MISS);
            if (cookHeatMissCount >= COOK_MAX_HEAT_MISS)
            {
                abortWith("cook: 'Cooking range' not in scene after "
                    + cookHeatMissCount + " ticks — wrong object name, "
                    + "wrong trail end, or scene failed to load");
                return;
            }
            status.set("cook: waiting for scene to load (" + cookHeatMissCount
                + "/" + COOK_MAX_HEAT_MISS + ")");
            return;
        }
        if (pp != null && heat.tile != null
            && pp.getPlane() == heat.tile.getPlane()
            && Math.max(Math.abs(pp.getX() - heat.tile.getX()),
                        Math.abs(pp.getY() - heat.tile.getY())) > 8)
        {
            // Same anti-loop reasoning: walker just told us we
            // arrived; re-walking won't move us closer.  Tolerate
            // a couple ticks (camera/player settle), then abort.
            cookHeatMissCount++;
            log.warn("pizza cook: range too far to interact "
                + "(player={}, range={}, miss {}/{})",
                pp, heat.tile, cookHeatMissCount, COOK_MAX_HEAT_MISS);
            if (cookHeatMissCount >= COOK_MAX_HEAT_MISS)
            {
                abortWith("cook: range out of interaction range "
                    + "after " + cookHeatMissCount + " ticks "
                    + "(player=" + pp + ", range=" + heat.tile + ")");
                return;
            }
            status.set("cook: range too far (" + cookHeatMissCount
                + "/" + COOK_MAX_HEAT_MISS + ")");
            return;
        }
        // Both cook gates passed — clear the failure counter so a
        // future legitimate scene-load tolerance starts fresh.
        cookHeatMissCount = 0;

        if (cook.isCooking())
        {
            status.set("cook: cooking (" + rawLeft + " raw left)");
            return;
        }

        // Settle gate — block re-dispatch while the inventory is still
        // showing recent cook progress. Anchoring on lastRawDecreaseMs
        // (set when rawLeft drops) instead of lastCookActionMs (click
        // time) means a 5+s batch keeps gating itself as long as raw
        // count is dropping. CookingScriptV3 does the same.
        if (lastRawDecreaseMs > 0 && (now - lastRawDecreaseMs) < COOK_BATCH_SETTLE_MS)
        {
            status.set("cook: batch in progress (" + rawLeft + " raw, last cook "
                + (now - lastRawDecreaseMs) + "ms ago)");
            return;
        }

        if (now - lastInventoryChangeMs > COOK_STUCK_MS)
        {
            cookStuckCount++;
            log.info("pizza cook: no progress for {}ms (count={})", COOK_STUCK_MS, cookStuckCount);
            if (cookStuckCount > COOK_MAX_STUCK)
            {
                abortWith("cook: stuck " + cookStuckCount + "× — aborting");
                return;
            }
            lastInventoryChangeMs = now;
            lastCookActionMs      = 0L;
            lastUncookedCount     = -1;
        }

        if (dispatcher.isBusy())
        {
            status.set("cook: dispatcher busy");
            return;
        }
        if (lastCookActionMs > 0 && (now - lastCookActionMs) < cookPaceDelayMs)
        {
            status.set("cook: pacing (" + (now - lastCookActionMs)
                + "/" + cookPaceDelayMs + "ms)");
            return;
        }

        if (!ensureInventoryTabOpen())
        {
            status.set("cook: opening inventory tab");
            return;
        }

        // Don't fire "Use raw on Cooking range" while still walking.
        // The walker can report ARRIVED with the player 1-2 tiles short
        // of the leg endpoint — clicking the range mid-step triggers
        // an extra walk-to-range, looks bot-y, and burns a tick.  Wait
        // for the idle pose before dispatching.
        if (!playerIsIdle())
        {
            status.set("cook: waiting for player to settle");
            return;
        }

        // Direct-cook: range has 'Cook Cooking range' as L-click verb
        // when the inventory holds a single cookable type (we always
        // do — the bank trip deposits everything else).  One click,
        // straight to the Skillmulti dialog.  No Use-on dance.  Wraps
        // in runExclusive so no other subsystem interleaves a click
        // between the cook click and the Skillmulti confirm.
        status.set("cook: clicking Cooking range");
        AtomicBoolean clickedHeat   = new AtomicBoolean(false);
        AtomicBoolean rawGoneSettle = new AtomicBoolean(false);
        AtomicBoolean heatVanished  = new AtomicBoolean(false);
        AtomicReference<String> failure = new AtomicReference<>(null);
        boolean ran = dispatcher.runExclusive(() -> {
            // Re-confirm raw food is still in inventory (defensive —
            // the check at the top of tickCookAtRange could have
            // been a tick stale).
            if (cook.inventoryAmount(rawId) == 0)
            {
                rawGoneSettle.set(true);
                return;
            }
            CookingInteraction.Match heat2 = cook.findHeatSource("Cooking range");
            if (heat2 == null)
            {
                heatVanished.set(true);
                return;
            }
            if (!cook.clickRangeCookDirect(heat2, "Cooking range"))
            {
                failure.set("cook: direct-cook click failed");
                return;
            }
            clickedHeat.set(true);
        });
        if (!ran)
        {
            status.set("cook: dispatcher busy");
            return;
        }
        if (rawGoneSettle.get())
        {
            log.info("pizza cook: all raw cooked during settle — heading to bank");
            setState(State.WALK_TO_BANK);
            return;
        }
        if (heatVanished.get())
        {
            status.set("cook: range vanished mid-use");
            return;
        }
        if (!clickedHeat.get())
        {
            status.set(failure.get() == null ? "cook: verify failed" : failure.get());
            return;
        }
        lastCookActionMs = System.currentTimeMillis();
        // Roll a fresh wait window before the next cook-tick decision.
        // Per-click randomization means consecutive batches don't fire
        // at a constant cadence.
        cookPaceDelayMs = ThreadLocalRandom.current().nextLong(
            COOK_PACE_MIN_MS, COOK_PACE_MAX_MS + 1);
        log.info("pizza cook: dispatched — next gate in {}ms", cookPaceDelayMs);
    }

    // ─── Helpers ────────────────────────────────────────────────────────
    /** Live-update plain/burnt counters while we're cooking on the
     *  range.  Cooking is the only phase that produces multiple items
     *  inside one inventory batch (28 raw → mix of plain+burnt) and
     *  the user wants per-pizza updates as they happen, not just at
     *  the end of the trip.
     *
     *  <p>On the first tick after entering COOK_AT_RANGE, snapshot the
     *  current inv counts of plain + burnt (could be non-zero if the
     *  user resumes mid-cook with a few cooked already in inv) plus
     *  the current values of plainMade / burntMade.  Subsequent ticks
     *  recompute `counter = entryCounter + (invNow - invSnapshot)` —
     *  no race because bank stays untouched while we're cooking and
     *  inv only grows during cook (raw → cooked).  The combine
     *  flow's atomic add at batch-done is unaffected by this.
     *
     *  <p>Snapshots reset in {@link #setState}, so each fresh entry
     *  to COOK_AT_RANGE re-snapshots. */
    private void updateCookCountersLive()
    {
        if (cookSnapshotPlainInv < 0)
        {
            cookSnapshotPlainInv = invCount(PLAIN_PIZZA);
            cookSnapshotBurntInv = invCount(BURNT_PIZZA);
            cookEntryPlainMade   = plainMade.get();
            cookEntryBurntMade   = burntMade.get();
        }
        int plainInvNow = invCount(PLAIN_PIZZA);
        int burntInvNow = invCount(BURNT_PIZZA);
        int newPlain    = Math.max(0, plainInvNow - cookSnapshotPlainInv);
        int newBurnt    = Math.max(0, burntInvNow - cookSnapshotBurntInv);
        plainMade.set(cookEntryPlainMade + newPlain);
        burntMade.set(cookEntryBurntMade + newBurnt);
    }

    /** Called from the top of {@link #tickLoop} when the game state
     *  isn't LOGGED_IN. With creds wired by the panel, fire
     *  {@link LoginAssistantV2#login}; otherwise just update the
     *  status string and let the user log in manually. Throttled by
     *  {@link #nextLoginAttemptAtMs} so a failing login doesn't
     *  hammer the server. */
    private void handleLoggedOut(GameState gs)
    {
        long now = System.currentTimeMillis();
        if (loginAssistant == null || credsSupplier == null)
        {
            status.set("logged out (" + gs + ") — waiting for manual login");
            return;
        }
        if (now < nextLoginAttemptAtMs)
        {
            long left = (nextLoginAttemptAtMs - now) / 1000L;
            status.set("auto-login backoff (" + left + "s)");
            return;
        }
        LoginCredentials creds;
        try { creds = credsSupplier.get(); }
        catch (Throwable th)
        {
            log.warn("pizza: creds supplier threw", th);
            status.set("auto-login: no credentials available");
            nextLoginAttemptAtMs = now + 10_000L;
            return;
        }
        if (creds == null)
        {
            status.set("auto-login: no character selected");
            nextLoginAttemptAtMs = now + 10_000L;
            return;
        }
        status.set("auto-login: " + gs + " — attempting");
        log.info("pizza: auto-login attempt (gameState={})", gs);
        boolean ok = loginAssistant.login(creds, targetWorldId, status::set, jagexAccount);
        if (ok)
        {
            log.info("pizza: auto-login success");
            nextLoginAttemptAtMs = 0L;
        }
        else
        {
            log.warn("pizza: auto-login FAILED — backing off");
            // 30 s backoff so repeated failures (world full, captcha,
            // network blip) don't spam the login screen.
            nextLoginAttemptAtMs = System.currentTimeMillis() + 30_000L;
        }
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
                log.info("pizza: level-up — will dismiss in {}ms", delay);
                return;
            }
            if (now >= levelUpDismissAfterMs)
            {
                log.info("pizza: dismissing level-up popup ({}ms after appearance)",
                    now - levelUpFirstSeenAtMs);
                cook.dismissLevelUp();
                levelUpFirstSeenAtMs = 0L;
            }
        }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        catch (Throwable th) { log.warn("pizza: dismissLevelUp threw", th); }
    }

    /** Schedule the next bank click for some time in the [min, max] ms
     *  window from now.  Each call burns a fresh random pick — so the
     *  cadence between bank actions is never identical. */
    private void scheduleBankClick(long minMs, long maxMs)
    {
        long delay = ThreadLocalRandom.current().nextLong(minMs, maxMs + 1);
        nextBankClickAtMs = System.currentTimeMillis() + delay;
    }

    /** Tight in-bank pacing — used between deposit / withdraw / close. */
    private void scheduleInBankAction()
    {
        scheduleBankClick(BANK_IN_USE_MIN_MS, BANK_IN_USE_MAX_MS);
    }

    /** Longer pacing after dispatching a booth-open click — waits for
     *  the server to actually open the bank (≥ 2 ticks) before letting
     *  the tick loop consider another click. Critical: the in-use pace
     *  (220-520ms) can expire BEFORE the bank-open packet round-trips,
     *  which dispatches a 2nd booth click on a now-open bank → closes
     *  it → deposit aborts. */
    private void scheduleOpenWait()
    {
        scheduleBankClick(BANK_OPEN_WAIT_MIN_MS, BANK_OPEN_WAIT_MAX_MS);
    }

    /** Long humanized pacing — used once per trip, before the FIRST
     *  booth click after walking up to the bank. */
    private void schedulePreOpen()
    {
        scheduleBankClick(BANK_PRE_OPEN_MIN_MS, BANK_PRE_OPEN_MAX_MS);
    }

    /** Random close-verify sleep ms — replaces the hardcoded 400 ms. */
    private long nextCloseVerifyMs()
    {
        return ThreadLocalRandom.current().nextLong(
            BANK_CLOSE_VERIFY_MIN_MS, BANK_CLOSE_VERIFY_MAX_MS + 1);
    }

    /** Humanized reaction delay before pressing Space on a Skillmulti /
     *  Cook-All dialog. Each call rolls a fresh value in
     *  [COOK_CONFIRM_MIN_MS, COOK_CONFIRM_MAX_MS] so the dispatch
     *  cadence never repeats. */
    private void sleepCookConfirmReaction() throws InterruptedException
    {
        long d = ThreadLocalRandom.current().nextLong(
            COOK_CONFIRM_MIN_MS, COOK_CONFIRM_MAX_MS + 1);
        SequenceSleep.sleep(client, d);
    }

    private boolean ensureInventoryTabOpen() throws InterruptedException
    {
        if (Boolean.TRUE.equals(onClient(() -> sidebarTabs.isOpen(SidebarTab.INVENTORY))))
            return true;
        if (dispatcher.isBusy()) return false;
        return sidebarTabs.openTabAndWait(SidebarTab.INVENTORY, 2_000L);
    }

    private int invCount(int itemId)
    {
        Integer n = onClient(() -> {
            ItemContainer inv = client.getItemContainer(InventoryID.INV);
            if (inv == null) return 0;
            int total = 0;
            for (Item it : inv.getItems())
                if (it != null && it.getId() == itemId)
                    total += Math.max(1, it.getQuantity());
            return total;
        });
        return n == null ? 0 : n;
    }

    private int invSlotOf(int itemId)
    {
        Integer s = onClient(() -> {
            ItemContainer inv = client.getItemContainer(InventoryID.INV);
            if (inv == null) return -1;
            Item[] items = inv.getItems();
            for (int i = 0; i < items.length; i++)
            {
                Item it = items[i];
                if (it != null && it.getId() == itemId) return i;
            }
            return -1;
        });
        return s == null ? -1 : s;
    }

    private int inventorySlotsUsed()
    {
        Integer n = onClient(() -> {
            ItemContainer inv = client.getItemContainer(InventoryID.INV);
            if (inv == null) return 0;
            int used = 0;
            for (Item it : inv.getItems())
                if (it != null && it.getId() > 0) used++;
            return used;
        });
        return n == null ? 0 : n;
    }

    /** Resolve the inventory widget bounds for the first slot holding
     *  {@code itemId}.  Must be called on the client thread. */
    private Rectangle resolveInvItemBounds(int itemId)
    {
        ItemContainer inv = client.getItemContainer(InventoryID.INV);
        if (inv == null) return null;
        Item[] items = inv.getItems();
        int slot = -1;
        for (int i = 0; i < items.length; i++)
        {
            Item it = items[i];
            if (it != null && it.getId() == itemId) { slot = i; break; }
        }
        if (slot < 0) return null;
        Widget parent = client.getWidget(InterfaceID.Inventory.ITEMS);
        if (parent == null || parent.isHidden()) return null;
        Widget child = parent.getChild(slot);
        if (child == null || child.isSelfHidden()) return null;
        Rectangle r = child.getBounds();
        return r == null || r.isEmpty() ? null : r;
    }

    private WorldPoint playerPos()
    {
        return onClient(() -> {
            Player p = client.getLocalPlayer();
            return p == null ? null : p.getWorldLocation();
        });
    }

    /** True when the player's pose animation matches the idle pose —
     *  i.e. not currently walking/running/animating.  Same idiom used
     *  in TrailWalker, ChickenFarmV3, LumbridgeBankPenScript. */
    private boolean playerIsIdle()
    {
        return Boolean.TRUE.equals(onClient(() -> {
            Player p = client.getLocalPlayer();
            return p != null && p.getPoseAnimation() == p.getIdlePoseAnimation();
        }));
    }

    private static boolean areaContains(WorldArea a, WorldPoint p)
    {
        return p.getPlane() == a.getPlane()
            && p.getX() >= a.getX() && p.getX() < a.getX() + a.getWidth()
            && p.getY() >= a.getY() && p.getY() < a.getY() + a.getHeight();
    }

    /** Reset per-state tracking on every transition.  Mirror of
     *  {@link CookingScriptV3#setState}'s discipline — flags that span
     *  multiple ticks of one phase belong here so the next phase starts
     *  cold. */
    private void setState(State s)
    {
        log.info("pizza: {} → {}", state.get(), s);
        state.set(s);
        // 0L is the "needs pre-open priming" sentinel — the next bank
        // gate will schedulePreOpen() before the first booth click.
        nextBankClickAtMs   = 0L;
        lastCookActionMs    = 0L;
        cookPaceDelayMs     = 0L;
        lastRawDecreaseMs   = 0L;
        lastWalkDispatchMs  = 0L;
        lastTransportDispatchMs = 0L;
        lastInventoryChangeMs = 0L;
        bankOpenedAtMs      = 0L;
        bankFailures        = 0;
        walkerStuckCount    = 0;
        cookStuckCount      = 0;
        cookMenuConfirmAttempts = 0;
        lastUncookedCount   = -1;
        craftBankDone       = false;
        craftDepositDone    = false;
        craftClicksDone     = false;
        skillmultiConfirmed = false;
        skillmultiWaitMs    = 0L;
        craftWaitMs         = 0L;
        // Reset cook-snapshot so re-entry to COOK_AT_RANGE re-anchors
        // the live plain/burnt counter against the fresh inv state.
        cookSnapshotPlainInv = -1;
        cookSnapshotBurntInv = -1;
    }

    private void abortWith(String reason)
    {
        log.warn("pizza: {}", reason);
        status.set("ABORTED: " + reason);
        setState(State.ABORTED);
    }

    private <T> T onClient(Supplier<T> task)
    {
        if (client != null && client.isClientThread())
        {
            try { return task.get(); }
            catch (Throwable th) { log.warn("pizza onClient threw (inline)", th); return null; }
        }
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> ref = new AtomicReference<>();
        clientThread.invokeLater(() -> {
            try   { ref.set(task.get()); }
            catch (Throwable th) { log.warn("pizza onClient threw", th); }
            finally { latch.countDown(); }
        });
        try
        {
            if (!latch.await(2_000L, TimeUnit.MILLISECONDS))
            {
                log.warn("pizza: onClient timed out");
                return null;
            }
        }
        catch (InterruptedException ie)
        {
            Thread.currentThread().interrupt();
            return null;
        }
        return ref.get();
    }
}
