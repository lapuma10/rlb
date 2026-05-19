package net.runelite.client.plugins.recorder.scripts;

import java.awt.Rectangle;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
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
import net.runelite.client.plugins.recorder.trail.Route;
import net.runelite.client.plugins.recorder.trail.TrailRegistry;
import net.runelite.client.plugins.recorder.trail.TrailWalker;
import net.runelite.client.plugins.recorder.widget.SidebarTab;
import net.runelite.client.plugins.recorder.widget.SidebarTabActions;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import net.runelite.client.sequence.dispatch.SequenceSleep;
import net.runelite.client.sequence.internal.ActionRequest;

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
    /** Lumbridge Castle plane-2 bank room.  Mirrors
     *  {@code CookingLocations.LUMBRIDGE_CASTLE_P2.bankArea()} — every
     *  tile in the box has line-of-sight to a bank booth, verified by
     *  the chicken-farm and cooking scripts. */
    private static final WorldArea BANK_AREA   = new WorldArea(3208, 3218, 3, 3, 2);
    /** Lumbridge kitchen plane 0 — covers the cook NPC, the range tile
     *  in front of it, and the food crates.  The recorded
     *  {@code lumby_bank_to_cook} trail ends inside this area. */
    private static final WorldArea KITCHEN_AREA = new WorldArea(3207, 3213, 5, 5, 0);

    // ─── Trail prefixes ─────────────────────────────────────────────────
    /** Walk: bank (p=2) → kitchen (p=0).  Recorded as part of the
     *  Cook's Assistant flow; reused here verbatim. */
    private static final String TRAIL_BANK_TO_COOK = "lumby_bank_to_cook";
    /** Walk: kitchen (p=0) → bank (p=2).  User must record this once
     *  (save as {@code ~/.runelite/recorder/trails/cook_to_lumby_bank.json}).
     *  If missing, the script aborts with the prefix in the status so
     *  the user knows what to record. */
    private static final String TRAIL_COOK_TO_BANK = "cook_to_lumby_bank";

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
    private static final long BANK_LOAD_GRACE_MS     = 1_500L;
    private static final long SKILLMULTI_TIMEOUT_MS  = 5_000L;
    private static final long CRAFT_TIMEOUT_MS       = 90_000L;
    private static final long COOK_BATCH_SETTLE_MS   = 5_000L;
    private static final long COOK_STUCK_MS          = 30_000L;
    private static final long COOK_PACE_MS           = 1_500L;
    private static final long LEVEL_UP_DISMISS_MIN_MS = 3_000L;
    private static final long LEVEL_UP_DISMISS_MAX_MS = 34_000L;

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
    private final TrailRegistry            trailRegistry;
    private final BankInteraction          bank;
    private final CookingInteraction       cook;
    private final TrailWalker              trailWalker;
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

    // Lazy Route cache (keyed by State; built on first walk tick).
    private final Map<State, Route> routeCache = new EnumMap<>(State.class);

    public PizzaScript(Client client, ClientThread clientThread,
                       HumanizedInputDispatcher dispatcher,
                       TrailRegistry trailRegistry)
    {
        this.client        = client;
        this.clientThread  = clientThread;
        this.dispatcher    = dispatcher;
        this.trailRegistry = trailRegistry;
        this.bank          = new BankInteraction(client, clientThread, dispatcher);
        this.cook          = new CookingInteraction(client, clientThread, dispatcher);
        this.trailWalker   = new TrailWalker(client, clientThread, dispatcher);
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
                    case WALK_TO_RANGE             ->
                        // Tried a P0 shortcut on 2026-05-06 (skip leg-2
                        // walk if findHeatSource returns non-null on
                        // plane 0).  Backed it out: the range is in
                        // scene radius from the staircase-landing tile
                        // (3206, 3208) but a wall sits between player
                        // and range — the convex-hull projection
                        // clips off-canvas (negative x bounds), the
                        // verb-click verification fails ("top='Cancel'"
                        // — cursor not over the object), cook stalls
                        // for COOK_STUCK_MS.  The trail's leg-2 walk
                        // to (3207, 3215) puts the player inside the
                        // kitchen with a clean line-of-sight; that's
                        // the cheapest "make the range clickable"
                        // path.  If a smarter shortcut is wanted,
                        // gate it on the projected hull actually
                        // landing on-canvas — not just findHeatSource
                        // returning a Match.
                        tickWalk(State.WALK_TO_RANGE, TRAIL_BANK_TO_COOK, State.COOK_AT_RANGE);
                    case COOK_AT_RANGE             -> tickCookAtRange();
                    case WALK_TO_BANK              ->
                        tickWalk(State.WALK_TO_BANK, TRAIL_COOK_TO_BANK, State.DECIDE);
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
            scheduleInBankAction();
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
            int qty = (int) Math.min(COOK_BATCH, bankUncook);
            status.set("decide: withdrawing " + qty + " uncooked pizza for the range");
            boolean ok = (bankUncook <= qty)
                ? bank.tryWithdrawAll(UNCOOKED_PIZZA)
                : bank.tryWithdrawX(UNCOOKED_PIZZA, qty);
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
            scheduleInBankAction();
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

    // ─── Walks ──────────────────────────────────────────────────────────
    /** Generic recorded-trail walk tick.  Looks up the route lazily,
     *  walks it, transitions on ARRIVED, aborts after WALKER_MAX_STUCK
     *  consecutive STUCK / ERROR.  Mirrors
     *  {@link CooksAssistantScript#tickRouteWalk}. */
    private void tickWalk(State curState, String prefix, State onArrival) throws InterruptedException
    {
        Route route = routeFor(curState, prefix);
        if (route == null) return;     // routeFor already aborted with a reason
        TrailWalker.Status st = trailWalker.walkRoute(route);
        status.set("walk[" + prefix + "]: " + st);
        switch (st)
        {
            case ARRIVED ->
            {
                walkerStuckCount = 0;
                setState(onArrival);
            }
            case STUCK, ERROR ->
            {
                walkerStuckCount++;
                log.info("pizza walk: stuck #{} on '{}'", walkerStuckCount, prefix);
                if (walkerStuckCount > WALKER_MAX_STUCK)
                    abortWith("walker stuck " + walkerStuckCount + "× on '" + prefix + "'");
            }
            default -> {}
        }
    }

    /** Lazy {@link Route} builder — globs trails from
     *  {@link #trailRegistry} matching the prefix.  Aborts loudly if no
     *  trail matches: the pizza loop can't run without recorded paths
     *  for both legs, and silently spinning a broken walker is worse
     *  than a clear "go record this trail" message. */
    private Route routeFor(State curState, String prefix)
    {
        Route cached = routeCache.get(curState);
        if (cached != null) return cached;
        try
        {
            // Trail goes through tight indoor spaces (Lumbridge castle
            // kitchen, stairwells).  The reachability BFS that filters
            // corridor picks accepts ANY reachable tile within the
            // distance budget — including tiles that require routing
            // OUT a door and back IN.  At radius=3 that lets picks
            // land on (3204, y) which is reachable via the kitchen
            // exit, and the bot visibly dances around the kitchen
            // then steps outside before coming back.  Radius=1 keeps
            // jitter to immediate neighbors of the centerline; the
            // 9-tile pool is small but every tile is geometrically
            // adjacent so no detour is possible.  Trade some variety
            // for staying inside the building.  noRepeat=false matches
            // — with only 9 picks, forcing a different one each trip
            // tends to produce the same 2-3 tiles in rotation anyway.
            Route.Builder b = Route.builder()
                .corridorRadius(1)
                .noRepeat(false);
            int matched = 0;
            for (net.runelite.client.plugins.recorder.trail.Trail t : trailRegistry.all())
            {
                if (t != null && t.name() != null && t.name().startsWith(prefix))
                {
                    b.trail(t);
                    matched++;
                }
            }
            if (matched == 0)
            {
                abortWith("no recorded trail starts with '" + prefix
                    + "' — record one (save as " + prefix + ".json under "
                    + "~/.runelite/recorder/trails/) and restart");
                return null;
            }
            Route built = b.build();
            routeCache.put(curState, built);
            log.info("pizza: route '{}' loaded with {} trail(s) for state {} (corridorRadius=1, noRepeat=false)",
                prefix, matched, curState);
            return built;
        }
        catch (IllegalArgumentException e)
        {
            abortWith("route '" + prefix + "' build failed: " + e.getMessage());
            return null;
        }
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

        // Track inventory change to detect stuck/no-progress.
        if (rawLeft != lastUncookedCount)
        {
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

        if (lastCookActionMs > 0 && (now - lastCookActionMs) < COOK_BATCH_SETTLE_MS)
        {
            status.set("cook: batch in progress (" + rawLeft + " raw, " + (now - lastCookActionMs) + "ms ago)");
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
        if (lastCookActionMs > 0 && (now - lastCookActionMs) < COOK_PACE_MS)
        {
            status.set("cook: pacing (" + (now - lastCookActionMs) + "ms)");
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
        // trailWalker.reset() drops its currentPath; the next walkRoute
        // call rebuilds activeRoutePath from a fresh weighted pick.
        trailWalker.reset();
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
