package net.runelite.client.plugins.recorder.scripts;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.NpcID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.recorder.combat.NpcSelector;
import net.runelite.client.plugins.recorder.farm.BankInteraction;
import net.runelite.client.plugins.recorder.npc.NpcInteraction;
import net.runelite.client.plugins.recorder.npc.NpcScan;
import net.runelite.client.plugins.recorder.trail.Route;
import net.runelite.client.plugins.recorder.trail.TrailRegistry;
import net.runelite.client.plugins.recorder.trail.TrailWalker;
import net.runelite.client.plugins.recorder.transport.TransportResolver;
import net.runelite.client.plugins.recorder.widget.SidebarTab;
import net.runelite.client.plugins.recorder.widget.SidebarTabActions;
import net.runelite.client.sequence.SequenceState;
import net.runelite.client.sequence.activities.ge.BuyItemIntent;
import net.runelite.client.sequence.activities.ge.OfferWaitPolicy;
import net.runelite.client.sequence.activities.ge.PricePolicy;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import net.runelite.client.sequence.dispatch.SequenceSleep;
import net.runelite.client.sequence.internal.ActionRequest;

/**
 * One-shot quest script for Cook's Assistant.
 *
 * <p>Acquires the three ingredients (pot of flour, egg, bucket of milk),
 * walks to the Lumbridge Cook, and talks twice (start the quest then hand
 * the ingredients in). The acquisition strategy is bank-first then GE:
 *
 * <ol>
 *   <li>Inventory check. All three present → straight to Cook.</li>
 *   <li>{@code CHECK_BANK} — open the Lumbridge bank, withdraw whatever
 *       missing items the bank has, close the bank.</li>
 *   <li>If anything is still missing after banking, {@code WALK_TO_GE}
 *       → {@code BUYING} (sequential one-item buys for whatever's
 *       missing) → {@code WALK_BACK_TO_LUMBRIDGE}.</li>
 *   <li>{@code WALK_TO_COOK} → {@code TALKING_TO_COOK}.</li>
 * </ol>
 *
 * <p>The script assumes the player starts at the Lumbridge bank — the
 * {@code CHECK_BANK} handler aborts loudly if no booth/banker is in
 * range so the failure mode is "tell me to start at the bank" instead
 * of an infinite open-bank-loop.
 *
 * <p><b>Threading:</b> one daemon worker thread pumps the FSM at ~650 ms
 * per tick. All client-API reads are marshalled to the client thread via
 * {@link ClientThread}. NPC dialogue is driven inside a
 * {@link ActionRequest.Kind#RUN_TASK} so the multi-step click chain runs
 * on the dispatcher worker, never on the client thread.
 */
@Slf4j
public final class CooksAssistantScript
{
    // ── NPC selectors ───────────────────────────────────────────────
    private static final NpcSelector COOK_SELECTOR = new NpcSelector("Cook", 12);
    /** Lumbridge Castle Kitchen Cook (Cook's Assistant quest giver).
     *  ID match is the primary path because it's invariant against
     *  name markup ("<col=ffff00>Cook") and any future name drift,
     *  and disambiguates from other "Cook"-named NPCs that may share
     *  the scene (none in Lumbridge but defence in depth). Name match
     *  is the fallback. */
    private static final int COOK_NPC_ID = NpcID.COOK;
    /** Permissive scene-range scan radius for {@link #findCookOnScene}.
     *  Selector range was 12 tiles (Chebyshev) which already covers
     *  the kitchen, but the dispatcher does its own visibility/aim
     *  pipeline once we hand off — so we just need "is the cook
     *  somewhere in scene-streamed memory" here, not "is he within
     *  combat range". 24 mirrors typical OSRS scene-load radius. */
    private static final int COOK_SCENE_SCAN_RADIUS = 24;
    /** Throttle for the per-tick "what NPCs do I see" diagnostic log
     *  in {@link #tickWalkToCook}. Without this the worker thread
     *  would spam the log at TICK_MS cadence. */
    private static final long COOK_VISIBILITY_LOG_PACE_MS = 3_000;

    // ── Route prefixes per walk state ───────────────────────────────
    // The walker uses {@link Route#fromTrails} to scoop up every trail
    // whose name starts with the prefix, so:
    //   - dropping multiple variants in the trails dir (e.g.
    //     `lumby-bank-to-cook-via-bridge.json` +
    //     `lumby-bank-to-cook-via-shortcut.json`) gives the v2 walker
    //     random alternation for free, and
    //   - the script aborts loudly with the missing prefix if no trail
    //     matches — much better than silently spinning a broken walker.
    //
    // Existing user trails matching these prefixes (as of 2026-05-03):
    //   lumbridge_bank_to_ge_safe        ✓ (+ ..._v2 → Route picks both)
    //   ge_to_lumbridge_bank_safe        ✓
    //   lumby_bank_to_cook               ✗ — record one to enable WALK_TO_COOK
    private static final Map<State, String> ROUTE_PREFIX = new EnumMap<>(State.class);
    static
    {
        ROUTE_PREFIX.put(State.WALK_TO_GE,             "lumbridge_bank_to_ge_safe");
        ROUTE_PREFIX.put(State.WALK_BACK_TO_LUMBRIDGE, "ge_to_lumbridge_bank_safe");
        ROUTE_PREFIX.put(State.WALK_TO_COOK,           "lumby_bank_to_cook");
    }

    // ── NPC verb strings ────────────────────────────────────────────
    private static final String VERB_TALK_TO = "Talk-to";

    // ── GE buy intents (one per ingredient) ─────────────────────────
    /** Item-id → BuyItemIntent pre-baked. Prices are deliberately set
     *  generous (500 gp) since flour/egg/milk are pennies on the GE
     *  and we'd rather pay through the nose than fail an offer because
     *  the price moved 30 gp. {@link OfferWaitPolicy#until(int)} = 100
     *  means "wait until the offer is fully filled (100%)." */
    private static final BuyItemIntent BUY_FLOUR = new BuyItemIntent(
        ItemID.POT_FLOUR, "Pot of flour", 1,
        new PricePolicy.Exact(500), OfferWaitPolicy.until(100));
    private static final BuyItemIntent BUY_EGG = new BuyItemIntent(
        ItemID.EGG, "Egg", 1,
        new PricePolicy.Exact(500), OfferWaitPolicy.until(100));
    private static final BuyItemIntent BUY_MILK = new BuyItemIntent(
        ItemID.BUCKET_MILK, "Bucket of milk", 1,
        new PricePolicy.Exact(500), OfferWaitPolicy.until(100));

    // ── Cook dialogue options ───────────────────────────────────────
    /** Option strings to pick during Cook's Assistant dialogue.
     *  {@link NpcInteraction#completeDialogue} matches in display order,
     *  not list order — i.e. for any visible option menu it picks the
     *  FIRST visible child whose text matches ANY entry here. So listing
     *  every menu we expect to encounter across both talks is safe and
     *  the order in this array doesn't matter.
     *
     *  <p>Quest dialogue tree (verified vs OSRS wiki + working OSBot
     *  reference):
     *  <ol>
     *    <li>First "Talk-to": Cook laments → menu — pick "What's wrong?".
     *    <li>Cook explains he needs ingredients → menu — pick
     *        "I'm always happy to help a cook in distress." (the other
     *        options abort the quest start).
     *    <li>Second "Talk-to" (with ingredients): Cook asks if we got
     *        them → menu — pick "Actually, I know where to find this
     *        stuff." (some clients show a literal "Yes." follow-up
     *        confirmation; harmless to keep listed).
     *  </ol>
     *  If Jagex rephrases any of these, {@code completeDialogue} logs
     *  a warn naming the wanted list — easy to update from the log. */
    private static final String[] COOK_DIALOGUE_OPTIONS = {
        "What's wrong?",
        "I'm always happy to help a cook in distress.",
        "Actually, I know where to find this stuff.",
        "Yes."
    };

    /** Hard cap on talk attempts. Two is the canonical maximum (start +
     *  hand-in); anything beyond means the option-pick logic missed
     *  and the cook never took the items. */
    private static final int MAX_COOK_TALKS = 2;

    /** Grace window after a booth click before re-attempting. The bank
     *  widget can take 1-3 seconds to render server-side; re-clicking
     *  inside this window finds the booth in a different state and the
     *  verb-pick fails. Also long enough to absorb a missed first click
     *  without the bot looking impatient. */
    private static final long BANK_OPEN_TIMEOUT_MS = 6_000;
    /** After this many failed booth-click attempts (each timing out
     *  without the bank window appearing), abort. */
    private static final int  MAX_BOOTH_CLICK_ATTEMPTS = 4;

    // ── Timing ──────────────────────────────────────────────────────
    private static final long TICK_MS           = 650;
    private static final long DISPATCH_PACE_MS  = 2_000;
    /** Passed to {@link NpcInteraction#talkTo} as the dialogue-open
     *  timeout. {@code talkTo} polls {@link NpcInteraction#inDialogue}
     *  internally; if the dialog never appears within this window we
     *  get back {@code NEVER_OPENED} and the script retries on the
     *  next tick. */
    private static final long DIALOGUE_RETRY_MS = 5_000;
    private static final int  WALKER_MAX_STUCK  = 3;

    // ── State machine ───────────────────────────────────────────────
    public enum State
    {
        IDLE,
        CHECK_BANK,            // Open bank, withdraw missing ingredients, close
        WALK_TO_GE,            // Lumbridge bank → GE (only if bank didn't supply everything)
        BUYING,                // GE buy loop — one ingredient at a time (each buy
                               // self-funds: opens GE bank → withdraws coins → buys)
        DEPOSIT_CASH_AT_GE,    // After buys: dump leftover coins at the GE bank
                               // before walking back, so we never carry trade
                               // money between zones.
        WALK_BACK_TO_LUMBRIDGE,// GE → Lumbridge bank
        WALK_TO_COOK,          // Lumbridge bank (p2) → Cook (kitchen, p0)
        TALKING_TO_COOK,
        DONE,
        ABORTED
    }

    // ── Dependencies ────────────────────────────────────────────────
    private final Client client;
    private final ClientThread clientThread;
    private final HumanizedInputDispatcher dispatcher;
    private final NpcInteraction npcInteraction;
    private final BankInteraction bank;
    private final TrailWalker trailWalker;
    private final TrailRegistry trailRegistry;
    private final GrandExchangeScript geScript;
    private final SidebarTabActions sidebarTabs;

    // ── Runtime fields ──────────────────────────────────────────────
    private final AtomicReference<State>  state  = new AtomicReference<>(State.IDLE);
    private final AtomicReference<String> status = new AtomicReference<>("idle");
    private final AtomicBoolean           running = new AtomicBoolean(false);
    private final AtomicReference<Thread> worker  = new AtomicReference<>();

    /** Epoch-ms of the last dispatcher call — throttles re-dispatches. */
    private long lastDispatchMs;
    /** True once the RUN_TASK that runs {@link NpcInteraction#talkTo}
     *  has been dispatched for this talk attempt. Reset by
     *  {@link #setState} on entry and after a finished talk that didn't
     *  consume the ingredients (caller-driven retry). */
    private boolean cookClickDispatched;
    /** Count of completed cook talk cycles — incremented after each
     *  dialogue session ends. Capped at {@link #MAX_COOK_TALKS}. */
    private int talkAttempts;
    /** Consecutive STUCK/ERROR increments from the walker; reset on ARRIVED
     *  and on every state transition. */
    private int walkerStuckCount;
    /** Item-id of the GE buy currently in flight, or 0 between buys.
     *  Tracked at the script level so we can detect "GE went IDLE
     *  because the buy completed" vs "GE was IDLE because we haven't
     *  started yet." */
    private int currentBuyItemId;
    /** Wall-clock of the last booth click. After a successful click
     *  the bank widget can take 1-3 seconds to render (server tick +
     *  network round-trip). DISPATCH_PACE_MS=2000 isn't enough — by
     *  the time the next tick fires another click, the player has
     *  walked closer to the booth and the menu no longer contains
     *  "Bank". This timestamp gates re-clicks: stay patient until
     *  {@link #BANK_OPEN_TIMEOUT_MS} has elapsed before re-attempting. */
    private long lastBoothClickMs;
    /** How many booth-click attempts have failed to open the bank
     *  (timeout elapsed without the widget appearing). Capped at
     *  {@link #MAX_BOOTH_CLICK_ATTEMPTS} then we abort. */
    private int boothClickAttempts;
    /** Latched true once {@link BankInteraction#closeBank} has been
     *  dispatched for this CHECK_BANK session. Without this flag
     *  Phase 4 closes the bank, Phase 5 never runs (we return), and
     *  next tick Phase 1 sees a closed bank and re-opens it — an
     *  infinite open/close loop. With it: Phase 1 stops opening
     *  once we've decided withdrawals are done, and Phase 5 fires
     *  on the next tick once {@code isBankOpen()} reflects the
     *  close. Reset by {@link #setState}. */
    private boolean bankSessionDone;
    /** Lazy cache of compiled Routes per state. Built on first need by
     *  {@link #routeFor}. Cleared on stop so a re-record + restart picks
     *  up the new trail set. */
    private final Map<State, Route> routeCache = new EnumMap<>(State.class);
    /** Wall-clock of the last cook-visibility diagnostic. Throttle for
     *  {@link #tickWalkToCook}'s per-tick scan log. */
    private long lastCookVisibilityLogMs;

    // ── Constructor ─────────────────────────────────────────────────

    /** {@code resolver} is retained for source/binary compatibility with
     *  prior callers but is no longer used internally — UniversalWalker
     *  was dropped in favour of the v2 {@link TrailWalker} + {@link Route}
     *  flow. Safe to pass {@code null}. */
    public CooksAssistantScript(Client client, ClientThread clientThread,
                                HumanizedInputDispatcher dispatcher,
                                TransportResolver resolver,
                                TrailRegistry trailRegistry,
                                GrandExchangeScript geScript)
    {
        this.client         = client;
        this.clientThread   = clientThread;
        this.dispatcher     = dispatcher;
        this.npcInteraction = new NpcInteraction(client, clientThread, dispatcher);
        this.bank           = new BankInteraction(client, clientThread, dispatcher);
        this.trailWalker    = new TrailWalker(client, clientThread, dispatcher);
        this.trailRegistry  = trailRegistry;
        this.geScript       = geScript;
        this.sidebarTabs    = new SidebarTabActions(client, clientThread, dispatcher);
    }

    // ── Public API ──────────────────────────────────────────────────

    public State  state()  { return state.get(); }
    public String status() { return status.get(); }

    public void start()
    {
        Thread existing = worker.get();
        if (existing != null && existing.isAlive()) { status.set("already running"); return; }
        if (!running.compareAndSet(false, true)) return;

        State initial = decideInitialState();
        if (initial == State.IDLE || initial == State.ABORTED)
        {
            running.set(false);
            return;
        }
        setState(initial);

        Thread t = new Thread(this::tickLoop, "cooks-assistant");
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

    // ── State decision ──────────────────────────────────────────────

    private State decideInitialState()
    {
        if (allIngredientsPresent())
        {
            status.set("quest: all ingredients in inventory — going to Cook");
            log.info("cooks-assistant: all ingredients present — going straight to Cook");
            return State.WALK_TO_COOK;
        }
        status.set("quest: ingredients missing — checking bank");
        log.info("cooks-assistant: missing ingredient(s) — opening bank first, GE as fallback");
        return State.CHECK_BANK;
    }

    /** True iff all three ingredients (flour + egg + milk) are in the
     *  player's inventory. Single client-thread hop. */
    private boolean allIngredientsPresent()
    {
        int[] inv = readIngredientCounts();
        return inv[0] > 0 && inv[1] > 0 && inv[2] > 0;
    }

    /** Returns {@code [flour, egg, milk]} counts from the inventory.
     *  Single client-thread hop — cheaper than three {@code inventoryCount}
     *  calls. Treats null inventory as all zero. */
    private int[] readIngredientCounts()
    {
        int[] inv = onClient(() -> {
            ItemContainer c = client.getItemContainer(InventoryID.INV);
            int flour = 0, egg = 0, milk = 0;
            if (c != null)
            {
                for (Item item : c.getItems())
                {
                    if (item == null) continue;
                    int id  = item.getId();
                    int qty = item.getQuantity();
                    if      (id == ItemID.POT_FLOUR)   flour += qty;
                    else if (id == ItemID.EGG)         egg   += qty;
                    else if (id == ItemID.BUCKET_MILK) milk  += qty;
                }
            }
            return new int[]{flour, egg, milk};
        });
        return inv != null ? inv : new int[]{0, 0, 0};
    }

    // ── Tick loop ───────────────────────────────────────────────────

    private void tickLoop()
    {
        try
        {
            // Post-login the inventory tab is often closed on the modern
            // client. Use-bucket-on-cow and the cook-dialog item-give path
            // both target inventory items, so the tab must be visible
            // before the first dispatch.
            if (!sidebarTabs.openTabAndWait(SidebarTab.INVENTORY, 2_000L))
                log.debug("cooks-assistant: could not confirm inventory tab open at startup");

            while (running.get() && !Thread.currentThread().isInterrupted())
            {
                if (playerPos() == null)
                {
                    status.set("quest: waiting for player");
                    SequenceSleep.sleep(client, TICK_MS);
                    continue;
                }
                State cur = state.get();
                switch (cur)
                {
                    case CHECK_BANK             -> tickCheckBank();
                    case WALK_TO_GE             -> tickRouteWalk(cur, State.BUYING);
                    case BUYING                 -> tickBuying();
                    case DEPOSIT_CASH_AT_GE     -> tickDepositCashAtGe();
                    case WALK_BACK_TO_LUMBRIDGE -> tickRouteWalk(cur, State.WALK_TO_COOK);
                    case WALK_TO_COOK           -> tickWalkToCook();
                    case TALKING_TO_COOK        -> tickTalkToCook();
                    case DONE, ABORTED, IDLE    -> running.set(false);
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

    // ── State handlers ──────────────────────────────────────────────

    /** Drive a v2 {@link Route} via {@link TrailWalker#walkRoute}. The
     *  Route is built lazily from {@link #routeCache} the first time
     *  this state runs — so a missing trail aborts loudly with the
     *  exact prefix to record, rather than silently looping. */
    private void tickRouteWalk(State curState, State onArrival) throws InterruptedException
    {
        Route route = routeFor(curState);
        if (route == null) return;   // routeFor already aborted with a clear reason
        String prefix = ROUTE_PREFIX.get(curState);
        TrailWalker.Status st = trailWalker.walkRoute(route);
        status.set("route[" + prefix + "]: " + st);
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
                log.info("cooks-assistant: route stuck #{} on '{}'", walkerStuckCount, prefix);
                if (walkerStuckCount > WALKER_MAX_STUCK)
                    abortWith("walker stuck " + walkerStuckCount + "× on '" + prefix + "'");
            }
            default -> {}
        }
    }

    /** Walk-to-Cook with NPC short-circuit, driven via
     *  {@link TrailWalker#walkRouteUntil}. The arrival predicate scans
     *  the loaded scene for the Lumbridge Cook every tick; as soon as
     *  his canvas-tile poly is present we cut the walk short and hand
     *  off to {@link State#TALKING_TO_COOK}. The dispatcher's
     *  {@link HumanizedInputDispatcher} npcClick rotates the camera,
     *  re-resolves the hull at click time, and lets the OSRS server
     *  pathfind us into talk-to range — we don't need to be on his
     *  tile.
     *
     *  <p>Diagnostic logging (which NPCs we see, why the cook didn't
     *  match) runs from inside the predicate, throttled by
     *  {@link #COOK_VISIBILITY_LOG_PACE_MS} so it surfaces during
     *  failure investigation without spamming every tick. */
    private void tickWalkToCook() throws InterruptedException
    {
        Route route = routeFor(State.WALK_TO_COOK);
        if (route == null) return;
        String prefix = ROUTE_PREFIX.get(State.WALK_TO_COOK);

        TrailWalker.Status st = trailWalker.walkRouteUntil(route, () -> {
            NpcScan scan = onClient(() -> npcInteraction.findOnScene(
                COOK_SCENE_SCAN_RADIUS, new int[]{ COOK_NPC_ID }, "Cook"));
            long now = System.currentTimeMillis();
            boolean cookOnCanvas = scan != null && scan.found() && scan.onCanvas();
            if (cookOnCanvas)
            {
                log.info("cooks-assistant: cook on canvas (idx={}, tile={}) — short-circuiting walker",
                    scan.npcIndex(), scan.tile());
                return true;
            }
            if (now - lastCookVisibilityLogMs > COOK_VISIBILITY_LOG_PACE_MS)
            {
                lastCookVisibilityLogMs = now;
                logCookScan(scan);
            }
            return false;
        });

        status.set("route[" + prefix + "]: " + st);
        switch (st)
        {
            case ARRIVED ->
            {
                walkerStuckCount = 0;
                setState(State.TALKING_TO_COOK);
            }
            case STUCK, ERROR ->
            {
                walkerStuckCount++;
                log.info("cooks-assistant: route stuck #{} on '{}'", walkerStuckCount, prefix);
                if (walkerStuckCount > WALKER_MAX_STUCK)
                    abortWith("walker stuck " + walkerStuckCount + "× on '" + prefix + "'");
            }
            default -> {}
        }
    }

    /** Diagnostic dump for {@link #tickWalkToCook}'s short-circuit
     *  predicate. Throttled by the caller — pulled out so the
     *  walkRouteUntil predicate stays readable. */
    private void logCookScan(NpcScan scan)
    {
        if (scan == null)
        {
            log.info("cooks-assistant: cook scan returned null (client-thread timeout?) — continuing walk");
        }
        else if (!scan.found())
        {
            log.info("cooks-assistant: cook not on scene yet — {}", scan.diagnostic());
        }
        else
        {
            log.info("cooks-assistant: cook on scene at {} but offscreen — walker will continue ({})",
                scan.tile(), scan.diagnostic());
        }
    }

    /** Build (and cache) a {@link Route} for {@code curState} by
     *  globbing {@link #trailRegistry}. Returns {@code null} after
     *  calling {@link #abortWith} when no trail matches the prefix —
     *  the user needs to record one before this state can run. */
    @javax.annotation.Nullable
    private Route routeFor(State curState)
    {
        Route cached = routeCache.get(curState);
        if (cached != null) return cached;
        String prefix = ROUTE_PREFIX.get(curState);
        if (prefix == null)
        {
            abortWith("internal: no route prefix mapped for state " + curState);
            return null;
        }
        try
        {
            Route built = Route.fromTrails(trailRegistry.all(), prefix);
            routeCache.put(curState, built);
            log.info("cooks-assistant: route '{}' loaded with {} trail(s) for state {}",
                prefix, built.entries().size(), curState);
            return built;
        }
        catch (IllegalArgumentException e)
        {
            abortWith("no recorded trail starts with '" + prefix
                + "' — record one (save as " + prefix + ".json under "
                + "~/.runelite/recorder/trails/) and restart the script");
            return null;
        }
    }

    /** Open the Lumbridge bank, withdraw any missing ingredients the
     *  bank holds, close it, then transition. After this state runs:
     *  - all 3 ingredients in inventory → WALK_TO_COOK
     *  - still missing one or more  → WALK_TO_GE (then BUYING)
     *
     *  <p>Aborts if no banker/booth is in click range — assumption is
     *  the player started at the Lumbridge bank. */
    private void tickCheckBank() throws InterruptedException
    {
        boolean bankOpen = bank.isBankOpen();

        // Phase 1: open the bank if it isn't already AND we haven't
        // already finished our withdraw session this state-entry. The
        // session-done flag is the loop firewall: once we've dispatched
        // closeBank, this guard prevents the next tick from seeing a
        // closed bank and re-opening it.
        if (!bankOpen && !bankSessionDone)
        {
            if (bank.isBankPinUp())
            {
                abortWith("bank: PIN keypad is up — enter your PIN manually then restart");
                return;
            }
            long now = System.currentTimeMillis();
            // A click already went out — stay patient until the window
            // either renders or our grace period times out. Re-clicking
            // mid-render finds the booth in a stale-menu state and the
            // verb-pick fails.
            if (lastBoothClickMs > 0 && now - lastBoothClickMs < BANK_OPEN_TIMEOUT_MS)
            {
                status.set("bank: waiting for window (clicked "
                    + (now - lastBoothClickMs) + "ms ago)");
                return;
            }
            if (dispatcher.isBusy()) { status.set("bank: dispatcher busy"); return; }
            if (lastBoothClickMs > 0)
            {
                // Grace window expired without bank opening — count as
                // a failed attempt before re-clicking.
                boothClickAttempts++;
                log.info("cooks-assistant: bank booth click attempt {} timed out after {}ms",
                    boothClickAttempts, BANK_OPEN_TIMEOUT_MS);
                if (boothClickAttempts >= MAX_BOOTH_CLICK_ATTEMPTS)
                {
                    abortWith("bank: " + boothClickAttempts
                        + " booth-click attempts failed to open the window");
                    return;
                }
            }

            // Pre-flight: if the closest booth's model isn't on canvas
            // (occluded by a wall, beyond render LOD), dispatch a walk
            // toward its tile so the next tick has a chance to find a
            // resolvable pixel. WALKED_CLOSER doesn't count as a click
            // attempt — booth wasn't actually clicked, just approached.
            BankInteraction.BoothPrep prep = bank.ensureBoothInClickRange();
            if (prep == BankInteraction.BoothPrep.NO_CANDIDATE)
            {
                abortWith("bank: no booth/banker in click range — start the script at the Lumbridge bank");
                return;
            }
            if (prep == BankInteraction.BoothPrep.WALKED_CLOSER)
            {
                status.set("bank: walking closer to booth (off-canvas)");
                return;
            }

            boolean ok = bank.tryClickBankBoothRandom();
            if (!ok)
            {
                abortWith("bank: no booth/banker in click range — start the script at the Lumbridge bank");
                return;
            }
            lastBoothClickMs = now;
            lastDispatchMs   = now;
            status.set("bank: booth clicked, waiting for window");
            return;
        }

        if (bankOpen)
        {
            // Bank opened — clear the click guard so subsequent CHECK_BANK
            // entries (after a setState reset) start fresh.
            lastBoothClickMs   = 0;
            boothClickAttempts = 0;

            // Phase 2: wait for the inventory container.
            if (!bank.bankReady())
            {
                status.set("bank: waiting for container to load");
                return;
            }

            // Phase 3: withdraw the next missing-but-banked ingredient.
            // One per tick keeps the loop responsive and lets the inventory
            // settle before re-evaluating.
            int[] inv = readIngredientCounts();
            if (tryWithdrawIfMissing(inv[0], ItemID.POT_FLOUR,   "pot of flour"))  return;
            if (tryWithdrawIfMissing(inv[1], ItemID.EGG,         "egg"))            return;
            if (tryWithdrawIfMissing(inv[2], ItemID.BUCKET_MILK, "bucket of milk")) return;

            // Phase 4: nothing left to withdraw — close the bank and
            // latch the session-done flag so Phase 1 won't re-open on
            // the next tick.
            bank.closeBank();
            bankSessionDone = true;
            status.set("bank: closed, evaluating inventory");
            return;
        }

        // Phase 5: bank is closed AND we've completed our withdraw
        // session — transition based on what we ended up with.
        int[] post = readIngredientCounts();
        boolean allPresent = post[0] > 0 && post[1] > 0 && post[2] > 0;
        if (allPresent)
        {
            log.info("cooks-assistant: bank supplied all ingredients — going straight to Cook");
            setState(State.WALK_TO_COOK);
        }
        else
        {
            log.info("cooks-assistant: bank missing flour={} egg={} milk={} — heading to GE",
                post[0] == 0, post[1] == 0, post[2] == 0);
            setState(State.WALK_TO_GE);
        }
    }

    /** If {@code currentCount == 0} and the bank holds {@code itemId},
     *  attempt one withdraw. Returns true iff a withdraw was attempted
     *  (caller should return so the inventory settles before another
     *  withdraw fires). */
    private boolean tryWithdrawIfMissing(int currentCount, int itemId, String desc)
        throws InterruptedException
    {
        if (currentCount > 0) return false;
        if (!bank.bankContainsItem(itemId))
        {
            log.debug("bank: {} not in bank — skipping (will buy on GE)", desc);
            return false;
        }
        boolean ok = bank.tryWithdrawOne(itemId);
        log.info("cooks-assistant: bank withdraw {} → {}", desc, ok);
        status.set("bank: withdrew " + desc);
        return true;
    }

    /** Sequential GE buy loop. Each tick: re-read inventory; if all
     *  three present → walk back. Otherwise wait for the in-flight
     *  buy (if any) and then start the next missing ingredient.
     *
     *  <p>{@link #currentBuyItemId} disambiguates "GE went IDLE because
     *  the buy completed" from "GE was IDLE because we haven't started
     *  the next one yet" — without it, two consecutive IDLE polls
     *  could fire the same buy twice. */
    private void tickBuying() throws InterruptedException
    {
        int[] inv = readIngredientCounts();
        boolean haveFlour = inv[0] > 0;
        boolean haveEgg   = inv[1] > 0;
        boolean haveMilk  = inv[2] > 0;

        if (haveFlour && haveEgg && haveMilk)
        {
            log.info("cooks-assistant: GE supplied everything — depositing leftover coins");
            currentBuyItemId = 0;
            setState(State.DEPOSIT_CASH_AT_GE);
            return;
        }

        SequenceState geSt = geScript.state();
        if (geSt == SequenceState.RUNNING)
        {
            status.set("GE: buying item " + currentBuyItemId + "…");
            return;
        }
        if (geSt == SequenceState.FAILED)
        {
            // geScript.status() is racy on FAILED — onGameTick only
            // updates the cached status on the *next* game tick after
            // the engine internally fails, so polling here can read a
            // stale "running: ... STARTED". lastFailedStepDescription
            // walks the telemetry buffer for the real CHECK FAILED line.
            String detail = geScript.lastFailedStepDescription();
            String reason = detail.isEmpty() ? geScript.status() : detail;
            abortWith("GE buy failed (item " + currentBuyItemId + "): " + reason);
            return;
        }

        // GE is IDLE. If we had a buy in flight, it finished — clear
        // the marker so we can start the next one (or, if the buy
        // failed silently, the inventory check below will catch it
        // when we loop and find that item still missing).
        if (currentBuyItemId != 0)
        {
            log.info("cooks-assistant: GE buy of {} returned IDLE; inventory now flour={} egg={} milk={}",
                currentBuyItemId, haveFlour, haveEgg, haveMilk);
            currentBuyItemId = 0;
        }

        // Start the next missing-ingredient buy. First-match-wins.
        if (!haveFlour) { startGeBuy(BUY_FLOUR); return; }
        if (!haveEgg)   { startGeBuy(BUY_EGG);   return; }
        if (!haveMilk)  { startGeBuy(BUY_MILK);  return; }
    }

    private void startGeBuy(BuyItemIntent intent)
    {
        // startBuyWithPrep self-funds the buy: if inventory doesn't already
        // hold enough coins, the GE engine opens the GE bank booth, withdraws
        // a buffered amount, closes the bank, then buys. We deliberately
        // never withdraw cash at the Lumbridge bank — keeps trade money
        // local to GE so a script abort mid-flow doesn't strand coins
        // outside our destination zone.
        boolean ok = geScript.startBuyWithPrep(intent);
        if (!ok)
        {
            abortWith("GE: failed to start buy-with-prep for " + intent.displayName());
            return;
        }
        currentBuyItemId = intent.itemId();
        status.set("GE: buy-with-prep submitted for " + intent.displayName());
        log.info("cooks-assistant: GE buy-with-prep submitted for {}", intent.displayName());
    }

    /** After all GE buys complete: dump leftover coins into the GE bank
     *  before walking back to Lumbridge. The startBuyWithPrep flow leaves
     *  whatever wasn't spent in inventory; the user wants to land at the
     *  Cook with no trade money in hand.
     *
     *  <p>Same shape as {@link #tickCheckBank} (open booth → wait
     *  ready → mutate → close), but the "mutate" phase is a single
     *  depositAll(coins) call. Reuses {@link #bankSessionDone},
     *  {@link #lastBoothClickMs}, and {@link #boothClickAttempts}
     *  because they were reset in {@link #setState} on entry to this
     *  state — the prior CHECK_BANK session at Lumbridge can't
     *  contaminate. */
    private void tickDepositCashAtGe() throws InterruptedException
    {
        // Fast path: no coins in inventory, nothing to deposit.
        if (!bankSessionDone && inventoryCount(ItemID.COINS) == 0)
        {
            log.info("cooks-assistant: no leftover coins to deposit — skipping GE bank trip");
            setState(State.WALK_BACK_TO_LUMBRIDGE);
            return;
        }

        boolean bankOpen = bank.isBankOpen();

        // Phase 1: open the GE bank booth.
        if (!bankOpen && !bankSessionDone)
        {
            if (bank.isBankPinUp())
            {
                abortWith("ge-bank: PIN keypad is up — enter your PIN manually then restart");
                return;
            }
            long now = System.currentTimeMillis();
            if (lastBoothClickMs > 0 && now - lastBoothClickMs < BANK_OPEN_TIMEOUT_MS)
            {
                status.set("ge-bank: waiting for window (clicked "
                    + (now - lastBoothClickMs) + "ms ago)");
                return;
            }
            if (dispatcher.isBusy()) { status.set("ge-bank: dispatcher busy"); return; }
            if (lastBoothClickMs > 0)
            {
                boothClickAttempts++;
                log.info("cooks-assistant: ge-bank booth click attempt {} timed out after {}ms",
                    boothClickAttempts, BANK_OPEN_TIMEOUT_MS);
                if (boothClickAttempts >= MAX_BOOTH_CLICK_ATTEMPTS)
                {
                    abortWith("ge-bank: " + boothClickAttempts
                        + " booth-click attempts failed to open the window");
                    return;
                }
            }
            BankInteraction.BoothPrep prep = bank.ensureBoothInClickRange();
            if (prep == BankInteraction.BoothPrep.NO_CANDIDATE)
            {
                abortWith("ge-bank: no booth/banker in click range");
                return;
            }
            if (prep == BankInteraction.BoothPrep.WALKED_CLOSER)
            {
                status.set("ge-bank: walking closer to booth (off-canvas)");
                return;
            }
            boolean ok = bank.tryClickBankBoothRandom();
            if (!ok)
            {
                abortWith("ge-bank: no booth/banker in click range");
                return;
            }
            lastBoothClickMs = now;
            lastDispatchMs   = now;
            status.set("ge-bank: booth clicked, waiting for window");
            return;
        }

        if (bankOpen)
        {
            lastBoothClickMs   = 0;
            boothClickAttempts = 0;

            // Phase 2: wait for the bank container.
            if (!bank.bankReady())
            {
                status.set("ge-bank: waiting for container to load");
                return;
            }

            // Phase 3: deposit all coins. depositAll right-clicks the
            // coin slot and picks "Deposit-All", which is a no-op when
            // the slot doesn't exist (no coins in inventory) — but the
            // fast path above caught that, so by here we have coins.
            int coinsBefore = inventoryCount(ItemID.COINS);
            if (coinsBefore > 0)
            {
                log.info("cooks-assistant: depositing {} coins at GE bank", coinsBefore);
                bank.depositAll(ItemID.COINS);
                status.set("ge-bank: deposited " + coinsBefore + " coins");
            }

            // Phase 4: close, latch session-done.
            bank.closeBank();
            bankSessionDone = true;
            return;
        }

        // Phase 5: bank closed AND session done — walk back.
        log.info("cooks-assistant: GE bank deposit complete, walking back to Lumbridge");
        setState(State.WALK_BACK_TO_LUMBRIDGE);
    }

    private void tickTalkToCook() throws InterruptedException
    {
        if (!cookClickDispatched)
        {
            if (dispatcher.isBusy()) { status.set("cook: dispatcher busy"); return; }
            NpcScan scan = onClient(() -> npcInteraction.findOnScene(
                COOK_SCENE_SCAN_RADIUS, new int[]{ COOK_NPC_ID }, "Cook"));
            if (scan == null || !scan.found())
            {
                status.set("cook: Cook not on scene — walking closer");
                log.info("cooks-assistant: cook not on scene during talk-attempt — bouncing to WALK_TO_COOK ({})",
                    scan == null ? "scan-null" : scan.diagnostic());
                setState(State.WALK_TO_COOK);
                return;
            }
            // Hull may be null this exact frame (camera mid-rotate, behind a
            // wall) — let the dispatcher's npcClick re-resolve when talkTo
            // runs. It already rotates the camera + re-fetches the hull
            // at click time, and bails with "not on screen" if it still
            // can't see him.
            final int idx = scan.npcIndex();
            final WorldPoint tile = scan.tile();
            status.set("cook: talking (idx=" + idx + ", talk #" + (talkAttempts + 1) + ")");
            log.info("cooks-assistant: dispatching talkTo cook idx={} tile={} (talk #{})",
                idx, tile, talkAttempts + 1);
            dispatcher.dispatch(ActionRequest.builder()
                .kind(ActionRequest.Kind.RUN_TASK)
                .channel(ActionRequest.Channel.MOUSE)
                .task(() -> {
                    NpcInteraction.TalkResult r = npcInteraction.talkTo(
                        idx, VERB_TALK_TO, DIALOGUE_RETRY_MS, COOK_DIALOGUE_OPTIONS);
                    log.info("cooks-assistant: talkTo cook idx={} → {}", idx, r);
                })
                .taskName("CooksAssistant.talkTo")
                .build());
            cookClickDispatched = true;
            return;
        }

        if (dispatcher.isBusy())
        {
            status.set("cook: dialogue in progress");
            return;
        }

        // Talk task finished. The Cook only consumes ingredients on
        // the *second* talk (first talk starts the quest, second hands
        // them over). Inventory check is the truth source — items gone =
        // quest complete; items still here = need another talk.
        talkAttempts++;
        boolean stillHasIngredients =
            inventoryCount(ItemID.EGG)         > 0
            || inventoryCount(ItemID.BUCKET_MILK) > 0
            || inventoryCount(ItemID.POT_FLOUR)   > 0;

        if (!stillHasIngredients)
        {
            log.info("cooks-assistant: ingredients consumed by Cook — quest complete!");
            status.set("Cook's Assistant: COMPLETE");
            setState(State.DONE);
            return;
        }

        if (talkAttempts >= MAX_COOK_TALKS)
        {
            abortWith("talked to Cook " + talkAttempts
                + "× but ingredients still in inventory — option strings may have drifted");
            return;
        }

        log.info("cooks-assistant: ingredients still present after talk #{} — talking again", talkAttempts);
        status.set("cook: talk " + talkAttempts + " done, items still here — talking again");
        cookClickDispatched    = false;
        lastDispatchMs         = 0;
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private void setState(State s)
    {
        state.set(s);
        lastDispatchMs         = 0;
        cookClickDispatched    = false;
        walkerStuckCount       = 0;
        currentBuyItemId       = 0;
        lastBoothClickMs       = 0;
        boothClickAttempts     = 0;
        bankSessionDone        = false;
        talkAttempts           = 0;
        // trailWalker.reset() clears its currentPath; the next walkRoute
        // call rebuilds activeRoutePath from a fresh weighted pick, so
        // we don't need to track route state at the script level.
        trailWalker.reset();
    }

    private void abortWith(String reason)
    {
        log.warn("cooks-assistant: {}", reason);
        status.set("ABORTED: " + reason);
        setState(State.ABORTED);
    }

    private WorldPoint playerPos()
    {
        return onClient(() -> {
            Player p = client.getLocalPlayer();
            return p == null ? null : p.getWorldLocation();
        });
    }

    private int inventoryCount(int itemId)
    {
        Integer n = onClient(() -> {
            ItemContainer inv = client.getItemContainer(InventoryID.INV);
            if (inv == null) return 0;
            int total = 0;
            for (Item item : inv.getItems())
            {
                if (item != null && item.getId() == itemId) total += item.getQuantity();
            }
            return total;
        });
        return n == null ? 0 : n;
    }

    private <T> T onClient(Supplier<T> task)
    {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> ref = new AtomicReference<>();
        clientThread.invokeLater(() -> {
            try { ref.set(task.get()); }
            catch (Throwable th) { log.warn("cooks-assistant: onClient threw", th); }
            finally { latch.countDown(); }
        });
        try
        {
            if (!latch.await(2000, TimeUnit.MILLISECONDS))
            {
                log.warn("cooks-assistant: onClient timed out");
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
