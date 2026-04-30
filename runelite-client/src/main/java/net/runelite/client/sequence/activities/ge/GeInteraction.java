package net.runelite.client.sequence.activities.ge;

import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.WorldView;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.recorder.transport.VerbMatcher;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import net.runelite.client.sequence.internal.ActionRequest;
import net.runelite.client.sequence.views.OfferSide;

/**
 * Production {@link GeActions} backed by {@link HumanizedInputDispatcher}.
 *
 * <p>Phase A best-effort: widget lookups use {@link InterfaceID.GeOffers}
 * field names where pinned; dispatch falls through to {@code Escape}-based
 * dismiss / log warnings when the exact widget id is unknown. Methods are
 * fail-silent if the target widget is hidden — verification is the step's
 * job (its {@code check} polls the snapshot).
 *
 * <p>Threading: methods may be called from any worker thread. Widget reads
 * are gated on the client thread via {@link HumanizedInputDispatcher#runOnClient}.
 */
@Slf4j
public final class GeInteraction implements GeActions {

    /** GE clerk NPC ids — only four are defined in NpcID. */
    private static final Set<Integer> GE_CLERK_NPC_IDS = Set.of(
        NpcID.GE_CLERK_1, NpcID.GE_CLERK_2, NpcID.GE_CLERK_3, NpcID.GE_CLERK_4
    );

    private final Client client;
    private final ClientThread clientThread;
    private final HumanizedInputDispatcher dispatcher;

    public GeInteraction(Client client, ClientThread clientThread, HumanizedInputDispatcher dispatcher) {
        this.client = client;
        this.clientThread = clientThread;
        this.dispatcher = dispatcher;
    }

    @Override
    public void openGrandExchange() {
        // Find a nearby GE clerk and dispatch a CLICK_NPC with verb="Exchange".
        // Goes through the same pipeline as the chicken-combat attack click —
        // PixelResolver.resolveNpc samples a pixel inside the model's actual
        // convex hull (not the bounds-rect center, which can land on empty
        // grass), then the dispatcher does the hover-default check and falls
        // back to right-click + menu-match for "Exchange".
        try {
            NPC clerk = dispatcher.runOnClient(this::findNearestGeClerk);
            if (clerk == null) {
                log.warn("openGrandExchange: no GE clerk found on the loaded scene");
                return;
            }
            ActionRequest req = ActionRequest.builder()
                .kind(ActionRequest.Kind.CLICK_NPC)
                .channel(ActionRequest.Channel.MOUSE)
                .npcIndex(clerk.getIndex())
                .verb("Exchange")
                .build();
            dispatcher.dispatch(req);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("openGrandExchange threw: {}", e.toString());
        }
    }

    private NPC findNearestGeClerk() {
        WorldView wv = client.getTopLevelWorldView();
        if (wv == null) return null;
        NPC closest = null;
        int closestDistSq = Integer.MAX_VALUE;
        var localPlayer = client.getLocalPlayer();
        if (localPlayer == null || localPlayer.getWorldLocation() == null) return null;
        var pl = localPlayer.getWorldLocation();
        for (NPC n : wv.npcs()) {
            if (n == null || !GE_CLERK_NPC_IDS.contains(n.getId())) continue;
            var loc = n.getWorldLocation();
            if (loc == null) continue;
            int dx = loc.getX() - pl.getX();
            int dy = loc.getY() - pl.getY();
            int dsq = dx * dx + dy * dy;
            if (dsq < closestDistSq) {
                closestDistSq = dsq;
                closest = n;
            }
        }
        return closest;
    }

    @Override
    public void clickOfferSlotButton(int slot, OfferSide side) {
        // Each empty slot (INDEX_0..INDEX_7) shows two adjacent sub-buttons.
        // Both are dynamic children of the slot container — their packed
        // Widget.getId() is the parent's id (e.g. INDEX_2 = 0x01D1_0009 is
        // shared by both buttons in slot 2), so packed-id click resolution
        // would land inside the parent's whole-slot bounds and hit the
        // wrong half. Pick the specific child by its first menu verb
        // ("Create Buy offer" / "Create Sell offer") and click inside THAT
        // child's tight bounds via CLICK_BOUNDS.
        if (side == null || side == OfferSide.NONE) {
            log.warn("clickOfferSlotButton: side must be BUY or SELL, got {}", side);
            return;
        }
        int containerId = slotIndexWidget(slot);
        if (containerId == 0) {
            log.warn("clickOfferSlotButton: slot {} out of range 0..7", slot);
            return;
        }
        String wantedVerb = side == OfferSide.BUY ? "Create Buy offer" : "Create Sell offer";
        try {
            SideButton hit = dispatcher.runOnClient(() -> findSideButton(containerId, wantedVerb));
            if (hit == null) {
                log.warn("clickOfferSlotButton(slot={}, side={}): no child with verb \"{}\" under slot 0x{}",
                    slot, side, wantedVerb, Integer.toHexString(containerId));
                return;
            }
            log.info("clickOfferSlotButton(slot={}, side={}): bounds={} verb=\"{}\"",
                slot, side, hit.bounds, hit.verb);
            ActionRequest req = ActionRequest.builder()
                .kind(ActionRequest.Kind.CLICK_BOUNDS)
                .channel(ActionRequest.Channel.MOUSE)
                .bounds(hit.bounds)
                .verb(hit.verb)
                .build();
            dispatcher.dispatch(req);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private record SideButton(Rectangle bounds, String verb) {}

    /** Walk the slot container's child tree (static, dynamic, nested) and
     *  return the first descendant whose {@code getActions()} matches
     *  {@code wantedVerb} exactly (case-insensitive, trimmed) along with
     *  that descendant's tight bounds. The bounds are read fresh per call
     *  on the client thread, so a resized client produces correct
     *  rectangles for the current layout — no hardcoded coordinates.
     *
     *  <p>Why we can't return a packed widget id and click by it: the
     *  buy/sell sub-buttons are dynamic children whose {@code getId()}
     *  returns the slot parent's packed id (e.g. {@code 0x01D1_0009} for
     *  slot 2 — shared by BOTH buttons). Resolving a click pixel by that
     *  packed id lands inside the parent's whole-slot bounds and is a
     *  coin flip between buy and sell.
     *
     *  <p>Must run on the client thread. */
    private SideButton findSideButton(int containerId, String wantedVerb) {
        Widget root = client.getWidget(containerId);
        if (root == null) return null;
        Deque<Widget> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            Widget w = stack.pop();
            if (w == null || w.isHidden()) continue;
            String[] actions = w.getActions();
            if (actions != null) {
                for (String a : actions) {
                    if (a == null || a.isEmpty()) continue;
                    // VerbMatcher.matches strips RuneLite colour tags
                    // (the menu reads e.g. "Create <col=ff9040>Buy</col>
                    // offer") and normalises whitespace/hyphens so our
                    // plain "Create Buy offer" matches.
                    if (!VerbMatcher.matches(wantedVerb, a)) continue;
                    Rectangle b = w.getBounds();
                    if (b == null || b.isEmpty()) continue;
                    return new SideButton(b, a);
                }
            }
            pushAll(stack, w.getDynamicChildren());
            pushAll(stack, w.getStaticChildren());
            pushAll(stack, w.getNestedChildren());
        }
        return null;
    }

    private static void pushAll(Deque<Widget> stack, Widget[] arr) {
        if (arr == null) return;
        for (Widget c : arr) if (c != null) stack.push(c);
    }

    private static int slotIndexWidget(int slot) {
        return switch (slot) {
            case 0 -> InterfaceID.GeOffers.INDEX_0;
            case 1 -> InterfaceID.GeOffers.INDEX_1;
            case 2 -> InterfaceID.GeOffers.INDEX_2;
            case 3 -> InterfaceID.GeOffers.INDEX_3;
            case 4 -> InterfaceID.GeOffers.INDEX_4;
            case 5 -> InterfaceID.GeOffers.INDEX_5;
            case 6 -> InterfaceID.GeOffers.INDEX_6;
            case 7 -> InterfaceID.GeOffers.INDEX_7;
            default -> 0;
        };
    }

    /** Safety-net cap on chatbox-prompt polling. The poll exits as soon as
     *  {@code MESLAYERMODE != 0}, so the actual wait is event-driven — this
     *  number is just "give up if the prompt never opens". 15s is generous
     *  enough to ride out a sticky cs2 transition / server lag without
     *  hanging the engine on a genuinely missed click. */
    private static final long CHATBOX_AWAIT_MS = 15_000L;
    /** Randomized dwell range applied AFTER the prompt is detected and
     *  BEFORE the first keystroke. Models a player reading the prompt and
     *  reaching for the keyboard. Tightened from 3–8s after live testing
     *  felt "forever" — 800–2200ms is still humanly visible (most players
     *  don't react in <500ms) without making the bot feel comatose. */
    private static final long CHATBOX_DWELL_MIN_MS = 800L;
    private static final long CHATBOX_DWELL_MAX_MS = 2_200L;

    @Override
    public boolean selectItem(int itemId, String displayName) {
        // Dispatches an async TYPE_CHATBOX request. Returns true if the
        // dispatch was queued; the actual typing runs on the dispatcher's
        // worker thread (NEVER on the client thread — see SequenceSleep).
        // The calling step polls snapshot for completion (MESLAYERINPUT
        // shows the typed text and search results render) instead of
        // blocking on the typing path.
        String name = displayName;
        if (name == null || name.isEmpty() || name.startsWith("item#")) {
            try {
                String resolved = dispatcher.runOnClient(() -> {
                    var def = client.getItemDefinition(itemId);
                    return def == null ? null : def.getName();
                });
                if (resolved != null && !resolved.isEmpty() && !"null".equals(resolved)) {
                    name = resolved;
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Exception ex) {
                log.warn("selectItem: failed to resolve name for item {}: {}", itemId, ex.toString());
            }
        }
        if (name == null || name.isEmpty() || name.startsWith("item#")) {
            log.warn("selectItem: no usable name for item {} (displayName='{}')", itemId, displayName);
            return false;
        }
        // Lowercase — real players don't shift-type into a search box.
        String typed = name.toLowerCase(java.util.Locale.ROOT);
        ActionRequest req = ActionRequest.builder()
            .kind(ActionRequest.Kind.TYPE_CHATBOX)
            .channel(ActionRequest.Channel.KEYBOARD)
            .typeText(typed)
            .typePressEnter(false)
            .typeAwaitMs(CHATBOX_AWAIT_MS)
            .typeDwellMinMs(CHATBOX_DWELL_MIN_MS)
            .typeDwellMaxMs(CHATBOX_DWELL_MAX_MS)
            .build();
        log.info("selectItem: queueing TYPE_CHATBOX itemId={} text=\"{}\"", itemId, typed);
        dispatcher.dispatch(req);
        return true;
    }

    /** Dump the entire chatbox sub-tree (MES_LAYER + MES_LAYER_SCROLLCONTENTS)
     *  including static, dynamic, and nested children. Called when
     *  {@code awaitSearchResultsPopulated} times out — tells us where the
     *  GE search results actually live in the live widget tree. */
    private void dumpChatboxWidgetTreeForDiagnostic() {
        try {
            dispatcher.runOnClient(() -> {
                int[] widgetIds = {
                    InterfaceID.Chatbox.MES_LAYER,
                    InterfaceID.Chatbox.MES_LAYER_SCROLLCONTENTS,
                    InterfaceID.Chatbox.MES_LAYER_SCROLLAREA,
                    InterfaceID.Chatbox.CHATAREA,
                };
                String[] names = {
                    "MES_LAYER", "MES_LAYER_SCROLLCONTENTS",
                    "MES_LAYER_SCROLLAREA", "CHATAREA",
                };
                for (int i = 0; i < widgetIds.length; i++) {
                    Widget w = client.getWidget(widgetIds[i]);
                    if (w == null) {
                        log.warn("chatbox-diag: {} (0x{}) is null",
                            names[i], Integer.toHexString(widgetIds[i]));
                        continue;
                    }
                    int staticN = w.getStaticChildren() == null ? 0 : w.getStaticChildren().length;
                    int dynN    = w.getDynamicChildren() == null ? 0 : w.getDynamicChildren().length;
                    int nestN   = w.getNestedChildren() == null ? 0 : w.getNestedChildren().length;
                    log.warn("chatbox-diag: {} (0x{}) hidden={} bounds={} static={} dynamic={} nested={}",
                        names[i], Integer.toHexString(widgetIds[i]), w.isHidden(),
                        w.getBounds(), staticN, dynN, nestN);
                    dumpKids(names[i] + ".dynamic", w.getDynamicChildren());
                    dumpKids(names[i] + ".static",  w.getStaticChildren());
                    dumpKids(names[i] + ".nested",  w.getNestedChildren());
                }
                int mode = client.getVarcIntValue(
                    net.runelite.api.gameval.VarClientID.MESLAYERMODE);
                String typed = client.getVarcStrValue(
                    net.runelite.api.gameval.VarClientID.MESLAYERINPUT);
                log.warn("chatbox-diag: MESLAYERMODE={} MESLAYERINPUT=\"{}\"", mode, typed);
                return null;
            });
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static void dumpKids(String label, Widget[] kids) {
        if (kids == null || kids.length == 0) return;
        for (int i = 0; i < Math.min(kids.length, 20); i++) {
            Widget c = kids[i];
            if (c == null) continue;
            String text = c.getText();
            String textShort = text == null ? "null" :
                (text.length() > 40 ? text.substring(0, 40) + "…" : text)
                    .replaceAll("<[^>]+>", "");
            log.warn("  {}[{}] id=0x{} itemId={} text=\"{}\" hidden={} bounds={}",
                label, i, Integer.toHexString(c.getId()), c.getItemId(),
                textShort, c.isHidden(), c.getBounds());
        }
        if (kids.length > 20) {
            log.warn("  {}: ... and {} more", label, kids.length - 20);
        }
    }

    @Override
    public boolean pickSearchResult(int itemId) {
        // Search-result rows live as dynamic children of
        // Chatbox.MES_LAYER_SCROLLCONTENTS. They share the parent's packed
        // widget id (so getId() is useless for addressing them) — but each
        // row carries an action like "Select <col=ff9040>Bread</col>" with
        // the canonical item name baked in. We resolve the canonical name
        // from the item id, then poll for a row whose action matches via
        // VerbMatcher (case-insensitive, color-tag tolerant). Polling
        // because the row list flickers between "placeholder" and "results"
        // as the cs2 search runs — a single snapshot misses the window.
        String wantedName;
        try {
            wantedName = dispatcher.runOnClient(() -> {
                var def = client.getItemDefinition(itemId);
                return def == null ? null : def.getName();
            });
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception ex) {
            log.warn("pickSearchResult: failed to resolve name for item {}: {}", itemId, ex.toString());
            return false;
        }
        if (wantedName == null || wantedName.isEmpty() || "null".equals(wantedName)) {
            log.warn("pickSearchResult: no canonical name for item {}", itemId);
            return false;
        }
        try {
            // Poll for the matching row up to CHATBOX_AWAIT_MS — same
            // safety-net cap as the chatbox prompt await. The widget's
            // stored action is the bare verb "Select"; the item-name
            // suffix only exists in the rendered menu. So we match on
            // "Select" and disambiguate by Widget.getName(), which cs2
            // sets to the item name on each result row.
            long deadline = System.currentTimeMillis() + CHATBOX_AWAIT_MS;
            ResultRow row = null;
            while (System.currentTimeMillis() < deadline) {
                row = dispatcher.runOnClient(() -> findResultRowFor(wantedName));
                if (row != null) break;
                Thread.sleep(120L);
            }
            if (row == null) {
                dumpResultRowsForDiagnostic(itemId);
                log.warn("pickSearchResult: no row matching item \"{}\" within {}ms",
                    wantedName, CHATBOX_AWAIT_MS);
                return false;
            }
            log.info("pickSearchResult: itemId={} matched name=\"{}\" action=\"{}\" bounds={}",
                itemId, wantedName, row.matchedAction, row.bounds);
            // Pre-click human dwell — reading the row list takes a beat.
            long dwell = CHATBOX_DWELL_MIN_MS
                + java.util.concurrent.ThreadLocalRandom.current()
                    .nextLong(CHATBOX_DWELL_MAX_MS - CHATBOX_DWELL_MIN_MS + 1);
            Thread.sleep(dwell);
            ActionRequest req = ActionRequest.builder()
                .kind(ActionRequest.Kind.CLICK_BOUNDS)
                .channel(ActionRequest.Channel.MOUSE)
                .bounds(row.bounds)
                .verb(row.matchedAction)
                .build();
            dispatcher.dispatch(req);
            // Post-click probe — 1s after dispatch, snapshot live state.
            Thread.sleep(1000L);
            logPostClickState(itemId);
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private record ResultRow(String matchedAction, Rectangle bounds) {}

    /** Walk dynamic children of {@code MES_LAYER_SCROLLCONTENTS} and
     *  return the row matching {@code wantedName}. Per the click-inspector
     *  capture (buy-flow-recipe.json step 4), each row stores action
     *  "Select" — the menu's "Select &lt;name&gt;" string is built at
     *  render time by appending the row's identifying name to the verb,
     *  so {@link Widget#getActions()} alone can't disambiguate.
     *
     *  <p>Disambiguation tries each of these in order against the
     *  normalised item name:
     *  <ol>
     *    <li>{@link Widget#getName()} — primary signal cs2 sets per row.</li>
     *    <li>{@link Widget#getText()} — fallback (some revisions populate
     *        text instead of name).</li>
     *    <li>Any {@code getActions()} entry that, when stripped of color
     *        tags, ends with the item name (e.g. "Select Bread").</li>
     *  </ol>
     *  Color tags are stripped via {@link VerbMatcher#normalise}.
     *  Must run on the client thread. */
    private ResultRow findResultRowFor(String wantedName) {
        Widget container = client.getWidget(InterfaceID.Chatbox.MES_LAYER_SCROLLCONTENTS);
        if (container == null || container.isHidden()) return null;
        Widget[] kids = container.getDynamicChildren();
        if (kids == null) return null;
        String wantedNorm = VerbMatcher.normalise(wantedName);
        for (Widget k : kids) {
            if (k == null || k.isHidden()) continue;
            if (!rowMatches(k, wantedNorm)) continue;
            Rectangle b = k.getBounds();
            if (b == null || b.isEmpty()) continue;
            return new ResultRow("Select", b);
        }
        return null;
    }

    /** Three-way name match for a search result row: getName(), getText(),
     *  or any action ending with the wanted name (e.g. "Select Bread"). */
    private static boolean rowMatches(Widget row, String wantedNorm) {
        String name = row.getName();
        if (name != null && !name.isEmpty()
            && VerbMatcher.normalise(name).equals(wantedNorm)) return true;
        String text = row.getText();
        if (text != null && !text.isEmpty()
            && VerbMatcher.normalise(text).equals(wantedNorm)) return true;
        String[] actions = row.getActions();
        if (actions != null) {
            for (String a : actions) {
                if (a == null || a.isEmpty()) continue;
                String an = VerbMatcher.normalise(a);
                if (an.equals(wantedNorm)) return true;
                if (an.endsWith("-" + wantedNorm)) return true;
                if (an.endsWith(wantedNorm) && an.length() > wantedNorm.length()) return true;
            }
        }
        return false;
    }

    /** When findResultRowFor returns null, dump every row's full child
     *  layout so we can see what the live widget tree actually looks like
     *  — the failure mode is silent otherwise. Each line: row index, child
     *  index, packed widget id, getItemId, getText (truncated), getActions
     *  (first 3), bounds. */
    private void dumpResultRowsForDiagnostic(int wantedItemId) {
        try {
            dispatcher.runOnClient(() -> {
                Widget container = client.getWidget(
                    InterfaceID.Chatbox.MES_LAYER_SCROLLCONTENTS);
                if (container == null) {
                    log.warn("pickSearchResult diag: MES_LAYER_SCROLLCONTENTS is null");
                    return null;
                }
                if (container.isHidden()) {
                    log.warn("pickSearchResult diag: MES_LAYER_SCROLLCONTENTS hidden");
                    return null;
                }
                Widget[] kids = container.getDynamicChildren();
                if (kids == null || kids.length == 0) {
                    log.warn("pickSearchResult diag: MES_LAYER_SCROLLCONTENTS has 0 dynamic children");
                    return null;
                }
                log.warn("pickSearchResult diag: wantedItemId={}, total dynamic children={}",
                    wantedItemId, kids.length);
                int rows = kids.length / 3;
                int extra = kids.length % 3;
                log.warn("pickSearchResult diag: rowCount(/3)={} extraChildren={}", rows, extra);
                for (int i = 0; i < kids.length; i++) {
                    Widget w = kids[i];
                    int rowIdx = i / 3;
                    int childIdx = i % 3;
                    if (w == null) {
                        log.warn("  row={} child={} (null)", rowIdx, childIdx);
                        continue;
                    }
                    String text = w.getText();
                    String textShort = text == null ? "null" :
                        (text.length() > 40 ? text.substring(0, 40) + "…" : text)
                            .replaceAll("<[^>]+>", "");
                    String[] actions = w.getActions();
                    StringBuilder ab = new StringBuilder();
                    if (actions != null) {
                        int shown = 0;
                        for (String a : actions) {
                            if (a == null || a.isEmpty()) continue;
                            if (shown > 0) ab.append('|');
                            ab.append(a.replaceAll("<[^>]+>", ""));
                            if (++shown >= 3) break;
                        }
                    }
                    Rectangle b = null;
                    try { b = w.getBounds(); } catch (Exception ignored) {}
                    String wname = null;
                    try { wname = w.getName(); } catch (Exception ignored) {}
                    log.warn("  row={} child={} id=0x{} itemId={} name=\"{}\" text=\"{}\" actions=[{}] bounds={}",
                        rowIdx, childIdx, Integer.toHexString(w.getId()),
                        w.getItemId(),
                        wname == null ? "" : wname.replaceAll("<[^>]+>", ""),
                        textShort, ab,
                        b == null ? "?" : b.x + "," + b.y + " " + b.width + "x" + b.height);
                }
                return null;
            });
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /** Log post-click chatbox / search / offer-setup state so we can tell
     *  whether the row click triggered the expected transition. Called ~1s
     *  after dispatch so the engine has had a tick to respond. */
    private void logPostClickState(int itemId) {
        try {
            dispatcher.runOnClient(() -> {
                int mode = client.getVarcIntValue(
                    net.runelite.api.gameval.VarClientID.MESLAYERMODE);
                Widget results = client.getWidget(
                    InterfaceID.Chatbox.MES_LAYER_SCROLLCONTENTS);
                int resultRows = 0;
                boolean resultsHidden = true;
                if (results != null) {
                    resultsHidden = results.isHidden();
                    Widget[] kids = results.getDynamicChildren();
                    if (kids != null) resultRows = kids.length / 3;
                }
                Widget setup = client.getWidget(InterfaceID.GeOffers.SETUP);
                boolean setupOpen = setup != null && !setup.isHidden();
                log.info("pickSearchResult post-click(itemId={}): MESLAYERMODE={} resultsHidden={} resultRows={} setupOpen={}",
                    itemId, mode, resultsHidden, resultRows, setupOpen);
                return null;
            });
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /** Child indices on {@code GeOffers.SETUP} captured from the
     *  click-inspector recipe (buy-flow-recipe.json). The setup widget's
     *  buttons are dynamic children at fixed offsets — no chatbox prompt
     *  auto-opens after picking the search result, so the bot must click
     *  the right button to OPEN the numeric prompt before typing. */
    private static final int SETUP_CHILD_QTY_PLUS_ONE     = 2;
    private static final int SETUP_CHILD_QTY_PLUS_TEN     = 4;
    private static final int SETUP_CHILD_ENTER_QUANTITY   = 7;
    private static final int SETUP_CHILD_ENTER_PRICE      = 12;

    @Override
    public boolean setQuantity(int qty) {
        // Per buy-flow-recipe.json step 8-9: click GeOffers.SETUP[7]
        // ("Enter quantity"), MESLAYERMODE flips 0->7, type the digits,
        // press Enter, GE_NEWOFFER_QUANTITY commits to the typed value.
        // Both clicks are async via the dispatcher worker — never block
        // the engine/client thread.
        try {
            if (!clickSetupChild(SETUP_CHILD_ENTER_QUANTITY, "Enter quantity")) {
                log.warn("ge: SETUP[7] (Enter quantity) not visible — aborting setQuantity({})", qty);
                return false;
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
        ActionRequest req = ActionRequest.builder()
            .kind(ActionRequest.Kind.TYPE_CHATBOX)
            .channel(ActionRequest.Channel.KEYBOARD)
            .typeText(Integer.toString(qty))
            .typePressEnter(true)
            .typeAwaitMs(CHATBOX_AWAIT_MS)
            .typeDwellMinMs(CHATBOX_DWELL_MIN_MS)
            .typeDwellMaxMs(CHATBOX_DWELL_MAX_MS)
            .build();
        log.info("setQuantity: queueing TYPE_CHATBOX qty={}", qty);
        dispatcher.dispatch(req);
        return true;
    }

    @Override
    public boolean setPrice(int priceEach) {
        // Per buy-flow-recipe.json step 5-6: click GeOffers.SETUP[12]
        // ("Enter price"), then type digits and Enter. Async via worker.
        try {
            if (!clickSetupChild(SETUP_CHILD_ENTER_PRICE, "Enter price")) {
                log.warn("ge: SETUP[12] (Enter price) not visible — aborting setPrice({})", priceEach);
                return false;
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
        ActionRequest req = ActionRequest.builder()
            .kind(ActionRequest.Kind.TYPE_CHATBOX)
            .channel(ActionRequest.Channel.KEYBOARD)
            .typeText(Integer.toString(priceEach))
            .typePressEnter(true)
            .typeAwaitMs(CHATBOX_AWAIT_MS)
            .typeDwellMinMs(CHATBOX_DWELL_MIN_MS)
            .typeDwellMaxMs(CHATBOX_DWELL_MAX_MS)
            .build();
        log.info("setPrice: queueing TYPE_CHATBOX price={}", priceEach);
        dispatcher.dispatch(req);
        return true;
    }

    /** Click a specific child of {@code GeOffers.SETUP} by index — the
     *  setup widget's buttons (Enter quantity, Enter price, +1, +10, ...)
     *  share the SETUP packed widget id but are addressable as dynamic
     *  children via {@code parent.getChild(idx)}. Same pattern the
     *  dispatcher uses for inventory slots ({@code invSlotClick}).
     *  Returns true on a successful dispatch; false if the child isn't
     *  visible (offer-setup not open, slot-overview showing instead). */
    private boolean clickSetupChild(int childIndex, String verb) throws InterruptedException {
        Rectangle bounds = dispatcher.runOnClient(() -> {
            Widget setup = client.getWidget(InterfaceID.GeOffers.SETUP);
            if (setup == null || setup.isHidden()) return null;
            Widget child = setup.getChild(childIndex);
            if (child == null || child.isSelfHidden()) return null;
            Rectangle b = child.getBounds();
            return b == null || b.isEmpty() ? null : b;
        });
        if (bounds == null) return false;
        log.info("clickSetupChild({}, verb=\"{}\"): bounds={}", childIndex, verb, bounds);
        ActionRequest req = ActionRequest.builder()
            .kind(ActionRequest.Kind.CLICK_BOUNDS)
            .channel(ActionRequest.Channel.MOUSE)
            .bounds(bounds)
            .verb(verb)
            .build();
        dispatcher.dispatch(req);
        return true;
    }

    @Override
    public void confirmOffer() {
        clickWidget(InterfaceID.GeOffers.SETUP_CONFIRM, "confirmOffer");
    }

    @Override
    public void collect(int slot) {
        // GeCollect.COLLECT_INV is the "Collect to inventory" button. Phase A
        // just clicks it once; if multiple slots are ready, COLLECTALL handles
        // them.
        clickWidget(InterfaceID.GeCollect.COLLECT_INV, "collect(" + slot + ")");
    }

    @Override
    public void closeGrandExchange() {
        try {
            dispatcher.tapKey(KeyEvent.VK_ESCAPE);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /** Verify the widget is visible up the entire ancestor chain (per
     *  CLAUDE.md §1: a leaf-only isHidden check leaks clicks into walk-here
     *  drift when a parent tab is collapsed) then dispatch a proper
     *  CLICK_WIDGET action through the humanized dispatcher — NOT a raw
     *  canvas click — so the engine resolves the widget's actual click
     *  handler instead of whatever happens to be under the cursor pixel. */
    private void clickWidget(int widgetId, String purpose) {
        try {
            Boolean visible = dispatcher.runOnClient(() -> {
                Widget w = client.getWidget(widgetId);
                if (w == null) return false;
                for (Widget cur = w; cur != null; cur = cur.getParent()) {
                    if (cur.isHidden()) return false;
                }
                return true;
            });
            if (!Boolean.TRUE.equals(visible)) {
                log.warn("{}: widget 0x{} unresolved/hidden (ancestor-chain check)",
                    purpose, Integer.toHexString(widgetId));
                return;
            }
            ActionRequest req = ActionRequest.builder()
                .kind(ActionRequest.Kind.CLICK_WIDGET)
                .channel(ActionRequest.Channel.MOUSE)
                .widgetId(widgetId)
                .build();
            dispatcher.dispatch(req);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
