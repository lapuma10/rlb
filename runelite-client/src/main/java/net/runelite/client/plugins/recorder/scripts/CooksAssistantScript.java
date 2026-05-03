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
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.recorder.combat.NpcSelector;
import net.runelite.client.plugins.recorder.npc.NpcInteraction;
import net.runelite.client.plugins.recorder.scene.SceneScanner;
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
 * <p>Collects the three quest ingredients and then talks to the Lumbridge
 * Cook to complete the quest:
 * <ol>
 *   <li><b>Egg</b> — picked up from the ground in the chicken pen south of
 *       Lumbridge.</li>
 *   <li><b>Bucket of milk</b> — player must have an empty bucket in
 *       inventory; the script walks to the dairy cow at Fred's Farm and
 *       uses the "Milk" option.</li>
 *   <li><b>Pot of flour</b> — must already be in the player's inventory;
 *       the script will not collect it automatically.</li>
 * </ol>
 *
 * <p>Items already in inventory skip their collection phase.
 *
 * <p><b>Pre-conditions (checked in {@link #start()}):</b>
 * <ul>
 *   <li>{@link ItemID#POT_FLOUR Pot of flour} in inventory.</li>
 *   <li>{@link ItemID#BUCKET_MILK Bucket of milk} already in inventory,
 *       OR {@link ItemID#BUCKET_EMPTY empty bucket} for milking.</li>
 * </ul>
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
    private static final NpcSelector COOK_SELECTOR      = new NpcSelector("Cook", 12);
    private static final NpcSelector DAIRY_COW_SELECTOR = new NpcSelector("Dairy cow", 10);

    // ── Route prefixes per walk state ───────────────────────────────
    // The walker uses {@link Route#fromTrails} to scoop up every trail
    // whose name starts with the prefix, so:
    //   - dropping multiple variants in the trails dir (e.g.
    //     `to-cook-via-bridge.json` + `to-cook-via-shortcut.json`)
    //     gives the v2 walker random alternation for free, and
    //   - the script aborts loudly with the missing prefix if no trail
    //     matches — much better than the v1 PathSpec failure mode where
    //     UniversalWalker silently spun BFS-bouncing-off-the-castle-wall.
    //
    // Existing user trails matching these prefixes (as of 2026-05-03):
    //   lumbridge_bank_to_ge_safe        ✓ (+ ..._v2 → Route picks both)
    //   ge_to_lumbridge_bank_safe        ✓
    //   lumby-bank-to-pen                ✓
    //   kitchen-to-pen                   ✗ — record one
    //   to-cow                           ✗ — record one
    //   to-cook                          ✗ — record one
    private static final Map<State, String> ROUTE_PREFIX = new EnumMap<>(State.class);
    static
    {
        ROUTE_PREFIX.put(State.WALK_TO_GE,             "lumbridge_bank_to_ge_safe");
        ROUTE_PREFIX.put(State.WALK_BACK_TO_LUMBRIDGE, "ge_to_lumbridge_bank_safe");
        ROUTE_PREFIX.put(State.WALK_BANK_TO_PEN,       "lumby-bank-to-pen");
        ROUTE_PREFIX.put(State.WALK_TO_EGG_AREA,       "kitchen-to-pen");
        ROUTE_PREFIX.put(State.WALK_TO_COW_AREA,       "to-cow");
        ROUTE_PREFIX.put(State.WALK_TO_COOK,           "to-cook");
    }

    // ── NPC verb strings ────────────────────────────────────────────
    private static final String VERB_TALK_TO = "Talk-to";
    private static final String VERB_MILK    = "Milk";

    // ── Cook dialogue options ───────────────────────────────────────
    /** Strings the option-menu walker should pick when the Cook offers
     *  a choice. First-match-wins, so list both the quest-start
     *  ("Yes, I'll help.") and quest-hand-in ("Yes.") options.
     *  Wiki-canonical wording for Cook's Assistant; if Jagex rephrases
     *  one of these the dialogue stalls and the abort path will surface
     *  the issue with the exact list we tried. */
    private static final String[] COOK_DIALOGUE_OPTIONS = {
        "Yes, I'll help.",
        "Yes.",
        "I'll help."
    };

    /** Hard cap on talk attempts. Two is the canonical maximum (start +
     *  hand-in); anything beyond means the option-pick logic missed
     *  and the cook never took the items. */
    private static final int MAX_COOK_TALKS = 2;

    // ── Timing ──────────────────────────────────────────────────────
    private static final long TICK_MS           = 650;
    private static final long DISPATCH_PACE_MS  = 2_000;
    /** After clicking the Cook, wait this long before checking if the
     *  dialogue rendered (server round-trip + client render). */
    private static final long DIALOGUE_WAIT_MS  = 1_500;
    /** Give up waiting for dialogue this long after the initial click,
     *  then retry. */
    private static final long DIALOGUE_RETRY_MS = 5_000;
    private static final int  WALKER_MAX_STUCK  = 3;

    // ── State machine ───────────────────────────────────────────────
    public enum State
    {
        IDLE,
        WALK_TO_GE,            // Lumbridge bank → GE via recorded trail
        BUYING_FLOUR,          // GrandExchangeScript buying 1× pot of flour
        WALK_BACK_TO_LUMBRIDGE,// GE → Lumbridge bank via recorded trail
        WALK_BANK_TO_PEN,      // Lumbridge bank (p2) → chicken pen (p0) via trail
        WALK_TO_EGG_AREA,
        COLLECTING_EGG,
        WALK_TO_COW_AREA,
        COLLECTING_MILK,
        WALK_TO_COOK,
        TALKING_TO_COOK,
        DONE,
        ABORTED
    }

    // ── Dependencies ────────────────────────────────────────────────
    private final Client client;
    private final ClientThread clientThread;
    private final HumanizedInputDispatcher dispatcher;
    private final NpcInteraction npcInteraction;
    private final SceneScanner scene;
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
    /** True once CLICK_NPC("Talk-to") has been dispatched toward the Cook. */
    private boolean cookClickDispatched;
    /** True once the RUN_TASK for completeDialogue() has been dispatched. */
    private boolean dialogueTaskDispatched;
    /** Count of completed cook talk cycles — incremented after each
     *  dialogue session ends. Capped at {@link #MAX_COOK_TALKS}. */
    private int talkAttempts;
    /** Consecutive STUCK/ERROR increments from the walker; reset on ARRIVED
     *  and on every state transition. */
    private int walkerStuckCount;
    /** True once GE startBuy has been called for pot of flour. */
    private boolean flourBuyStarted;
    /** Lazy cache of compiled Routes per state. Built on first need by
     *  {@link #routeFor}. Cleared on stop so a re-record + restart picks
     *  up the new trail set. */
    private final Map<State, Route> routeCache = new EnumMap<>(State.class);

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
        this.scene          = new SceneScanner(client);
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
        // Single client-thread hop to read all four relevant item counts.
        int[] inv = onClient(() -> {
            ItemContainer c = client.getItemContainer(InventoryID.INV);
            int flour = 0, egg = 0, milk = 0, bucket = 0;
            if (c != null)
            {
                for (Item item : c.getItems())
                {
                    if (item == null) continue;
                    int id  = item.getId();
                    int qty = item.getQuantity();
                    if      (id == ItemID.POT_FLOUR)    flour  += qty;
                    else if (id == ItemID.EGG)          egg    += qty;
                    else if (id == ItemID.BUCKET_MILK)  milk   += qty;
                    else if (id == ItemID.BUCKET_EMPTY) bucket += qty;
                }
            }
            return new int[]{flour, egg, milk, bucket};
        });
        int flourCount  = inv != null ? inv[0] : 0;
        int eggCount    = inv != null ? inv[1] : 0;
        int milkCount   = inv != null ? inv[2] : 0;
        int bucketCount = inv != null ? inv[3] : 0;

        if (flourCount == 0)
        {
            status.set("quest: pot of flour missing — walking to GE to buy one");
            log.info("cooks-assistant: no pot of flour — heading to GE");
            return State.WALK_TO_GE;
        }

        boolean needMilk = milkCount == 0;

        if (needMilk && bucketCount == 0)
        {
            status.set("quest: no empty bucket for milking — add one and restart");
            log.warn("cooks-assistant: no empty bucket — aborting");
            setState(State.ABORTED);
            return State.ABORTED;
        }

        if (eggCount == 0)  { status.set("quest: collecting egg");  return State.WALK_TO_EGG_AREA; }
        if (needMilk)       { status.set("quest: collecting milk"); return State.WALK_TO_COW_AREA; }
        status.set("quest: all items found — going to Cook");
        return State.WALK_TO_COOK;
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
                    case WALK_TO_GE             -> tickRouteWalk(cur, State.BUYING_FLOUR);
                    case BUYING_FLOUR           -> tickBuyFlour();
                    case WALK_BACK_TO_LUMBRIDGE -> tickRouteWalk(cur, State.WALK_BANK_TO_PEN);
                    case WALK_BANK_TO_PEN       -> tickRouteWalk(cur, null);
                    case WALK_TO_EGG_AREA       -> tickRouteWalk(cur, State.COLLECTING_EGG);
                    case COLLECTING_EGG         -> tickCollectEgg();
                    case WALK_TO_COW_AREA       -> tickRouteWalk(cur, State.COLLECTING_MILK);
                    case COLLECTING_MILK        -> tickCollectMilk();
                    case WALK_TO_COOK           -> tickRouteWalk(cur, State.TALKING_TO_COOK);
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
                setState(onArrival != null ? onArrival : nextStateForCurrentInventory());
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

    private void tickBuyFlour() throws InterruptedException
    {
        if (inventoryCount(ItemID.POT_FLOUR) > 0)
        {
            log.info("cooks-assistant: pot of flour acquired — returning to Lumbridge");
            setState(State.WALK_BACK_TO_LUMBRIDGE);
            return;
        }
        SequenceState geSt = geScript.state();
        if (geSt == SequenceState.RUNNING)
        {
            status.set("GE: buying pot of flour…");
            return;
        }
        if (geSt == SequenceState.FAILED)
        {
            abortWith("GE buy failed: " + geScript.status());
            return;
        }
        // IDLE — start the buy on first call
        if (!flourBuyStarted)
        {
            BuyItemIntent intent = new BuyItemIntent(
                ItemID.POT_FLOUR, "Pot of flour", 1,
                new PricePolicy.Exact(500),
                OfferWaitPolicy.until(100));
            boolean ok = geScript.startBuy(intent);
            if (!ok) { abortWith("GE: failed to start buy for pot of flour"); return; }
            flourBuyStarted = true;
            status.set("GE: buy submitted for pot of flour");
            return;
        }
        // Was started and GE is IDLE again — buy finished (or cancelled)
        if (inventoryCount(ItemID.POT_FLOUR) == 0)
        {
            abortWith("GE finished but pot of flour not in inventory");
        }
        else
        {
            log.info("cooks-assistant: pot of flour bought");
            setState(State.WALK_BACK_TO_LUMBRIDGE);
        }
    }

    /** Read inventory and return the next logical state after arriving back at
     *  Lumbridge (plane 0). Never returns {@link State#WALK_TO_GE} — flour is
     *  assumed present by the time this is called. */
    private State nextStateForCurrentInventory() throws InterruptedException
    {
        int[] inv = onClient(() -> {
            ItemContainer c = client.getItemContainer(InventoryID.INV);
            int egg = 0, milk = 0, bucket = 0;
            if (c != null)
            {
                for (Item item : c.getItems())
                {
                    if (item == null) continue;
                    int id = item.getId(), qty = item.getQuantity();
                    if      (id == ItemID.EGG)          egg    += qty;
                    else if (id == ItemID.BUCKET_MILK)  milk   += qty;
                    else if (id == ItemID.BUCKET_EMPTY) bucket += qty;
                }
            }
            return new int[]{egg, milk, bucket};
        });
        int egg    = inv != null ? inv[0] : 0;
        int milk   = inv != null ? inv[1] : 0;
        int bucket = inv != null ? inv[2] : 0;
        if (egg == 0) return State.WALK_TO_EGG_AREA;
        if (milk == 0)
        {
            if (bucket == 0)
            {
                log.warn("cooks-assistant: no empty bucket after GE return");
                status.set("ABORTED: no empty bucket for milking");
                return State.ABORTED;
            }
            return State.WALK_TO_COW_AREA;
        }
        return State.WALK_TO_COOK;
    }

    private void tickCollectEgg() throws InterruptedException
    {
        if (inventoryCount(ItemID.EGG) > 0)
        {
            log.info("cooks-assistant: egg collected");
            if (inventoryCount(ItemID.BUCKET_MILK) == 0)
                setState(State.WALK_TO_COW_AREA);
            else
                setState(State.WALK_TO_COOK);
            return;
        }
        if (dispatcher.isBusy()) { status.set("egg: dispatcher busy"); return; }

        long now = System.currentTimeMillis();
        if (now - lastDispatchMs < DISPATCH_PACE_MS) { status.set("egg: pacing"); return; }

        SceneScanner.Match egg = onClient(() -> scene.findTileItemById(ItemID.EGG, 10));
        if (egg == null)
        {
            status.set("egg: no egg on ground — waiting for spawn");
            return;
        }
        status.set("egg: picking up at " + egg.tile);
        dispatcher.dispatch(ActionRequest.builder()
            .kind(ActionRequest.Kind.CLICK_GROUND_ITEM)
            .channel(ActionRequest.Channel.MOUSE)
            .tile(egg.tile)
            .itemId(ItemID.EGG)
            .build());
        lastDispatchMs = now;
    }

    private void tickCollectMilk() throws InterruptedException
    {
        if (inventoryCount(ItemID.BUCKET_MILK) > 0)
        {
            log.info("cooks-assistant: milk collected");
            setState(State.WALK_TO_COOK);
            return;
        }
        if (inventoryCount(ItemID.BUCKET_EMPTY) == 0)
        {
            abortWith("empty bucket consumed — can't milk dairy cow");
            return;
        }
        if (dispatcher.isBusy()) { status.set("milk: dispatcher busy"); return; }

        long now = System.currentTimeMillis();
        if (now - lastDispatchMs < DISPATCH_PACE_MS) { status.set("milk: pacing"); return; }

        Integer cowIndex = onClient(() -> {
            Player p = client.getLocalPlayer();
            if (p == null) return null;
            net.runelite.api.NPC cow = DAIRY_COW_SELECTOR.pick(
                client.getTopLevelWorldView().npcs(), p, p.getWorldLocation());
            return cow == null ? null : cow.getIndex();
        });
        if (cowIndex == null)
        {
            status.set("milk: no dairy cow in range — waiting");
            return;
        }
        status.set("milk: clicking dairy cow (" + VERB_MILK + ")");
        dispatcher.dispatch(ActionRequest.builder()
            .kind(ActionRequest.Kind.CLICK_NPC)
            .channel(ActionRequest.Channel.MOUSE)
            .npcIndex(cowIndex)
            .verb(VERB_MILK)
            .build());
        lastDispatchMs = now;
    }

    private void tickTalkToCook() throws InterruptedException
    {
        if (!cookClickDispatched)
        {
            if (dispatcher.isBusy()) { status.set("cook: dispatcher busy"); return; }
            Integer cookIndex = onClient(() -> {
                Player p = client.getLocalPlayer();
                if (p == null) return null;
                net.runelite.api.NPC cook = COOK_SELECTOR.pick(
                    client.getTopLevelWorldView().npcs(), p, p.getWorldLocation());
                return cook == null ? null : cook.getIndex();
            });
            if (cookIndex == null)
            {
                status.set("cook: Cook not visible — walking closer");
                setState(State.WALK_TO_COOK);
                return;
            }
            status.set("cook: clicking " + VERB_TALK_TO);
            dispatcher.dispatch(ActionRequest.builder()
                .kind(ActionRequest.Kind.CLICK_NPC)
                .channel(ActionRequest.Channel.MOUSE)
                .npcIndex(cookIndex)
                .verb(VERB_TALK_TO)
                .build());
            cookClickDispatched = true;
            lastDispatchMs = System.currentTimeMillis();
            return;
        }

        if (!dialogueTaskDispatched)
        {
            long elapsed = System.currentTimeMillis() - lastDispatchMs;
            if (elapsed < DIALOGUE_WAIT_MS)
            {
                status.set("cook: waiting for dialogue (" + elapsed + "ms)");
                return;
            }
            if (dispatcher.isBusy())
            {
                status.set("cook: click still in flight");
                return;
            }
            boolean inDlg = npcInteraction.inDialogue();
            if (!inDlg)
            {
                if (elapsed < DIALOGUE_RETRY_MS)
                {
                    status.set("cook: dialogue not open yet — waiting");
                    return;
                }
                log.info("cooks-assistant: dialogue not opened after {}ms — retrying click", elapsed);
                cookClickDispatched = false;
                lastDispatchMs = 0;
                return;
            }
            status.set("cook: dialogue open — completing quest dialogue (talk #" + (talkAttempts + 1) + ")");
            dispatcher.dispatch(ActionRequest.builder()
                .kind(ActionRequest.Kind.RUN_TASK)
                .channel(ActionRequest.Channel.MOUSE)
                .task(() -> npcInteraction.completeDialogue(COOK_DIALOGUE_OPTIONS))
                .taskName("CooksAssistant.completeDialogue")
                .build());
            dialogueTaskDispatched = true;
            return;
        }

        if (dispatcher.isBusy())
        {
            status.set("cook: dialogue in progress");
            return;
        }

        // Dialogue task finished. The Cook only consumes ingredients on
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
        dialogueTaskDispatched = false;
        lastDispatchMs         = 0;
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private void setState(State s)
    {
        state.set(s);
        lastDispatchMs         = 0;
        cookClickDispatched    = false;
        dialogueTaskDispatched = false;
        walkerStuckCount       = 0;
        flourBuyStarted        = false;
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
