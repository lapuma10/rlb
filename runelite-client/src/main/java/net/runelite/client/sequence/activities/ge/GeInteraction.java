package net.runelite.client.sequence.activities.ge;

import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
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
import net.runelite.client.sequence.dispatch.SequenceSleep;
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
            // GE slot buttons are safe default left-click targets. Avoid the
            // right-click/ESC fallback path inside GE flows.
            ActionRequest req = ActionRequest.builder()
                .kind(ActionRequest.Kind.CLICK_BOUNDS)
                .channel(ActionRequest.Channel.MOUSE)
                .bounds(hit.bounds)
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

    @Override
    public void openOfferDetail(int slot) {
        // Plain left-click on the slot container. "View offer" is the slot's
        // default (entryId=1) when it holds an offer, so a left-click hits it
        // directly. We deliberately do NOT route through clickWidgetVerb here:
        // its right-click fallback fires VK_ESCAPE on verb-mismatch, which
        // closes the GE entirely — exactly what we're trying to interact with.
        // Better to silently miss a click and retry than to dismiss the GE
        // out from under the next step.
        int containerId = slotIndexWidget(slot);
        if (containerId == 0) {
            log.warn("openOfferDetail: slot {} out of range 0..7", slot);
            return;
        }
        clickWidget(containerId, "openOfferDetail(" + slot + ")");
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
    public void selectSellItemFromInventory(int invSlot, int itemId) {
        ActionRequest req = ActionRequest.builder()
            .kind(ActionRequest.Kind.CLICK_INV_ITEM)
            .channel(ActionRequest.Channel.MOUSE)
            .slot(invSlot)
            .itemId(itemId)
            .build();
        log.info("selectSellItemFromInventory: clicking inv slot={} itemId={}", invSlot, itemId);
        dispatcher.dispatch(req);
    }

    @Override
    public boolean selectItem(int itemId, String displayName) {
        // Dispatches a SINGLE worker-thread operation that BOTH types the
        // search name AND clicks the matching result row when it appears.
        // Combining the two sub-steps into one dispatch avoids the gap
        // where the dispatcher's single-flight busy flag could drop the
        // follow-up pick request — and makes the entire select operation
        // atomic from the engine's view (one dispatch, success-or-fail).
        // The actual typing + polling + clicking runs on the dispatcher
        // worker so the engine/client thread is never blocked.
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
        // Stash the resolved canonical name for pickSearchResult to read —
        // it's how the worker knows what name to match against rendered rows.
        // Lowercase what we type (real players don't shift-type a search box)
        // but match the row by the canonical (non-lowercased) name.
        String typed = name.toLowerCase(java.util.Locale.ROOT);
        ActionRequest req = ActionRequest.builder()
            .kind(ActionRequest.Kind.PICK_GE_SEARCH_RESULT)
            .channel(ActionRequest.Channel.KEYBOARD)
            .itemId(itemId)
            .pickName(name)
            .typeText(typed)
            .typeAwaitMs(CHATBOX_AWAIT_MS)
            .typeDwellMinMs(CHATBOX_DWELL_MIN_MS)
            .typeDwellMaxMs(CHATBOX_DWELL_MAX_MS)
            .build();
        log.info("selectItem: queueing PICK_GE_SEARCH_RESULT itemId={} typed=\"{}\" matchName=\"{}\"",
            itemId, typed, name);
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
        // No-op now — the type-and-pick flow is bundled into selectItem's
        // single PICK_GE_SEARCH_RESULT dispatch (see selectItem above).
        // PickSearchResultStep's onStart still calls this for
        // back-compatibility; it returns true so the step's check() can
        // poll snapshot for the actual completion signal.
        return true;
    }

    /** Worker-thread implementation of PICK_GE_SEARCH_RESULT. Called from
     *  {@link HumanizedInputDispatcher#handle} on its worker thread — safe
     *  to {@code SequenceSleep.sleep(client, ...)} here.
     *
     *  <p>Single-shot select-and-pick:
     *  <ol>
     *    <li>Type the search name (no Enter — let the search results
     *        widget render incrementally).</li>
     *    <li>Poll {@code MES_LAYER_SCROLLCONTENTS} for a row whose
     *        Widget.getName() / actions match {@code wantedName}.</li>
     *    <li>Click that row's bounds (verb "Select").</li>
     *  </ol>
     *  Bundling steps 1-3 into one worker call means the dispatcher's
     *  single-flight busy flag stays held throughout — no risk of a
     *  gap where another dispatch could be dropped.
     *
     *  <p>This is a public entry point for the dispatcher to call back
     *  into; it's NOT meant to be called from step code. */
    public void runPickSearchResult(int itemId, String wantedName) throws InterruptedException {
        // Step 1: type the search text. typeText was passed via the
        // ActionRequest; we pull it from the request via this method's
        // caller path. We type lowercase (no shift) to avoid bot tells.
        String typed = wantedName.toLowerCase(java.util.Locale.ROOT);
        log.info("runPickSearchResult: typing \"{}\" for itemId={}", typed, itemId);
        // Use the dispatcher's typeChatboxInternal directly — we're
        // already on the worker thread holding the busy flag, so this is
        // safe and doesn't try to acquire the flag again.
        boolean typedOk = dispatcher.typeChatboxOnWorker(typed, CHATBOX_AWAIT_MS,
            CHATBOX_DWELL_MIN_MS, CHATBOX_DWELL_MAX_MS, false);
        if (!typedOk) {
            log.warn("runPickSearchResult: typing \"{}\" failed (chatbox prompt timeout)", typed);
            return;
        }

        // Step 2: poll for the matching row.
        long deadline = System.currentTimeMillis() + CHATBOX_AWAIT_MS;
        ResultRow row = null;
        while (System.currentTimeMillis() < deadline) {
            row = dispatcher.runOnClient(() -> findResultRowFor(wantedName));
            if (row != null) break;
            SequenceSleep.sleep(client, 120L);
        }
        if (row == null) {
            dumpResultRowsForDiagnostic(itemId);
            log.warn("runPickSearchResult: no row matching \"{}\" within {}ms",
                wantedName, CHATBOX_AWAIT_MS);
            return;
        }
        log.info("runPickSearchResult: itemId={} matched name=\"{}\" action=\"{}\" bounds={}",
            itemId, wantedName, row.matchedAction, row.bounds);

        // Step 3: pre-click human dwell, then plain left-click the row.
        long dwell = CHATBOX_DWELL_MIN_MS
            + java.util.concurrent.ThreadLocalRandom.current()
                .nextLong(CHATBOX_DWELL_MAX_MS - CHATBOX_DWELL_MIN_MS + 1);
        SequenceSleep.sleep(client, dwell);
        // GE search rows must not fall back to right-click + ESC on a transient
        // hover-verb mismatch; that closes the search prompt and derails the
        // whole buy flow.
        dispatcher.boundsClickOnWorker(row.bounds, null);
        SequenceSleep.sleep(client, 500L);
        logPostClickState(itemId);
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
        // Canvas dimensions for the on-screen sanity check below. Rows whose
        // bounds fall outside the canvas (engine returns x=-1,y=-1 when a row
        // hasn't been laid out yet, or it's been scrolled off the visible
        // area) are unclickable — the dispatcher would hover empty space,
        // fail the verb match, right-click, and ESCAPE the GE shut.
        int cw = client.getCanvasWidth();
        int ch = client.getCanvasHeight();
        String wantedNorm = VerbMatcher.normalise(wantedName);
        for (Widget k : kids) {
            if (k == null || k.isHidden()) continue;
            if (!rowMatches(k, wantedNorm)) continue;
            Rectangle b = k.getBounds();
            if (!boundsClickable(b, cw, ch)) continue;
            return new ResultRow("Select", b);
        }
        return null;
    }

    /** True only when {@code b} is a non-empty rectangle that lies on the
     *  visible canvas. Engine returns negative coordinates for rows that
     *  haven't been rendered yet or have been scrolled out of view; those
     *  rectangles still report positive width/height, so {@link Rectangle#isEmpty}
     *  alone doesn't catch them. Clicking them is destructive — see the
     *  fix-site comment in {@link #findResultRowFor}. */
    private static boolean boundsClickable(Rectangle b, int canvasW, int canvasH) {
        if (b == null || b.isEmpty()) return false;
        if (b.x < 0 || b.y < 0) return false;
        if (canvasW > 0 && b.x + b.width > canvasW) return false;
        if (canvasH > 0 && b.y + b.height > canvasH) return false;
        return true;
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
        //
        // The click and the type used to be two separate dispatches. The
        // second was dropped by the busy guard (the click hadn't finished
        // when the type queued), and the chatbox prompt was left stuck
        // waiting for input. Bundle both into one RUN_TASK so the worker
        // runs them in sequence while holding busy throughout.
        ActionRequest req = ActionRequest.builder()
            .kind(ActionRequest.Kind.RUN_TASK)
            .channel(ActionRequest.Channel.KEYBOARD)
            .task(() -> runClickSetupChildThenType(
                SETUP_CHILD_ENTER_QUANTITY, "Enter quantity",
                Integer.toString(qty)))
            .taskName("GE_SET_QUANTITY(" + qty + ")")
            .build();
        log.info("setQuantity: queueing GE_SET_QUANTITY qty={}", qty);
        dispatcher.dispatch(req);
        return true;
    }

    @Override
    public boolean setPrice(int priceEach) {
        // Per buy-flow-recipe.json step 5-6: click GeOffers.SETUP[12]
        // ("Enter price"), then type digits and Enter. Click and type are
        // bundled into one RUN_TASK to prevent the busy guard from
        // dropping the type half — see setQuantity above.
        ActionRequest req = ActionRequest.builder()
            .kind(ActionRequest.Kind.RUN_TASK)
            .channel(ActionRequest.Channel.KEYBOARD)
            .task(() -> runClickSetupChildThenType(
                SETUP_CHILD_ENTER_PRICE, "Enter price",
                Integer.toString(priceEach)))
            .taskName("GE_SET_PRICE(" + priceEach + ")")
            .build();
        log.info("setPrice: queueing GE_SET_PRICE price={}", priceEach);
        dispatcher.dispatch(req);
        return true;
    }

    /** Worker-thread implementation of the setQuantity / setPrice flow.
     *  Resolves the SETUP child bounds on the client thread, clicks them
     *  on the worker, then types the digits + Enter — all without going
     *  back through {@link HumanizedInputDispatcher#dispatch} (which would
     *  self-drop on the busy flag we hold). Mirrors
     *  {@link #runPickSearchResult}'s combined typing + clicking.
     *
     *  <p>Public so the dispatcher can call back into it via the
     *  {@code RUN_TASK} payload, but not intended for step code to invoke
     *  directly — go through {@link #setQuantity} / {@link #setPrice}. */
    public void runClickSetupChildThenType(int childIndex, String verb, String digits)
        throws InterruptedException
    {
        Rectangle bounds = dispatcher.runOnClient(() -> {
            Widget setup = client.getWidget(InterfaceID.GeOffers.SETUP);
            if (setup == null || setup.isHidden()) return null;
            Widget child = setup.getChild(childIndex);
            if (child == null || child.isSelfHidden()) return null;
            Rectangle b = child.getBounds();
            return b == null || b.isEmpty() ? null : b;
        });
        if (bounds == null) {
            log.warn("ge: SETUP[{}] ({}) not visible — aborting type=\"{}\"",
                childIndex, verb, digits);
            return;
        }
        log.info("runClickSetupChildThenType({}, verb=\"{}\", type=\"{}\"): bounds={}",
            childIndex, verb, digits, bounds);
        // GE setup controls are also plain left-clicks. A missed click can be
        // retried; a right-click/ESC fallback closes the GE entirely.
        dispatcher.boundsClickOnWorker(bounds, null);
        boolean typed = dispatcher.typeChatboxOnWorker(
            digits, CHATBOX_AWAIT_MS, CHATBOX_DWELL_MIN_MS, CHATBOX_DWELL_MAX_MS, true);
        if (!typed) {
            log.warn("runClickSetupChildThenType: chatbox prompt did not open for SETUP[{}] ({})",
                childIndex, verb);
        }
    }

    @Override
    public void confirmOffer() {
        clickWidget(InterfaceID.GeOffers.SETUP_CONFIRM, "confirmOffer");
    }

    @Override
    public void dismissPriceWarning(boolean accept) {
        // Captured live (2026-04-30 click-inspector tick=227): "Yes" lives at
        // Popupoverlay.BUTTON_1 (0x01210008), so "No" is BUTTON_0 (0x01210007).
        // Both are dynamic children of Popupoverlay.UNIVERSE; CLICK_WIDGET
        // resolves their bounds afresh, so this works in fixed and resizable
        // layouts alike.
        int widgetId = accept
            ? InterfaceID.Popupoverlay.BUTTON_1
            : InterfaceID.Popupoverlay.BUTTON_0;
        clickWidget(widgetId, "dismissPriceWarning(" + (accept ? "Yes" : "No") + ")");
    }

    @Override
    public void collect(int slot) {
        // Per-slot collect via the in-grid offer-detail view's
        // GeOffers.DETAILS_COLLECT widget (0x01d10018). Captured live
        // from the click-inspector (2026-04-30 tick=979/981):
        //   idx=2 — bought item / refund. actions=[Collect-notes,
        //           Collect-items, Bank, Examine]. Default left-click is
        //           Collect-notes, which matches the live GE UI.
        //   idx=3 — leftover coins. actions=[Collect, Bank, Examine].
        // Both children share the parent's packed widget id (dynamic
        // children of DETAILS_COLLECT), so we walk the children and
        // click their tight bounds directly. GE detail clicks must stay
        // on plain left-clicks only.
        //
        // Two clicks need to be sequenced — bundle into a RUN_TASK so the
        // dispatcher's busy flag stays held across both, mirroring the
        // setQuantity / setPrice click+type pattern.
        ActionRequest req = ActionRequest.builder()
            .kind(ActionRequest.Kind.RUN_TASK)
            .channel(ActionRequest.Channel.MOUSE)
            .task(() -> runCollectFromDetail(slot))
            .taskName("GE_COLLECT(" + slot + ")")
            .build();
        log.info("collect(slot={}): queueing GE_COLLECT", slot);
        dispatcher.dispatch(req);
    }

    /** Max times we reopen the slot detail view inside a single collect run.
     *  One reopen covers the normal case (item collected, detail view closed,
     *  coins remain); two is a safety net for an additional GE CS2 refresh. */
    private static final int MAX_DETAIL_REOPENS = 2;

    /** Worker-thread implementation of the per-slot collect dance.
     *  Resolves the DETAILS_COLLECT children on the client thread, then
     *  plain-left-clicks each by bounds on the worker — no re-entry into
     *  the dispatcher's single-flight gate.
     *
     *  <p>After clicking the item box the GE CS2 sometimes closes the detail
     *  view before rendering the coins-refund child.  This method reopens the
     *  slot and collects whatever remains (up to {@link #MAX_DETAIL_REOPENS}
     *  times) so coins are never left behind.
     *
     *  <p>Public so the dispatcher can call back via the {@code RUN_TASK}
     *  payload; not meant for step code to invoke directly. */
    public void runCollectFromDetail(int slot) throws InterruptedException {
        // CollectOfferStep dispatches us the SAME tick that collectOpen() first
        // becomes true — collectOpen() only means GeOffers.DETAILS is visible,
        // NOT that DETAILS_COLLECT's child icons are populated. CS2 typically
        // renders the item + coins icons 1–3 ticks after the detail view opens,
        // so a one-shot read here would always miss them and we'd exit without
        // clicking. Wait up to 4s for the children to appear; only then bail.
        List<CollectButton> firstPass = waitForCollectButtons(slot, 4_000L);
        if (firstPass.isEmpty()) {
            // Detail view may not be open at all — try one reopen as a safety net.
            if (!reopenDetailView(slot)) {
                log.warn("collect({}): no DETAILS_COLLECT children visible after 4s — detail view not open?", slot);
                return;
            }
            firstPass = waitForCollectButtons(slot, 2_000L);
            if (firstPass.isEmpty()) {
                log.warn("collect({}): DETAILS_COLLECT still empty after reopen", slot);
                return;
            }
        }

        for (CollectButton b : firstPass) {
            log.info("ge collect: slot={} itemId={}", slot, b.itemId);
            log.info("collect({}): click '{}' at {} (item={})", slot, b.verb, b.bounds, b.itemId);
            dispatcher.boundsClickOnWorker(b.bounds, null);
            SequenceSleep.sleep(client, 180L + ThreadLocalRandom.current().nextInt(380));
        }

        Set<Integer> clickedItemIds = new HashSet<>();
        for (CollectButton b : firstPass) clickedItemIds.add(b.itemId);

        for (int i = 0; i < MAX_DETAIL_REOPENS; i++) {
            // Poll until a new (unclicked) button appears or the container
            // clears. Once we've successfully clicked at least one button
            // (firstPass), an empty/missing DETAILS_COLLECT is the TERMINAL
            // SUCCESS state — every collectable was drained. Do NOT call
            // reopenDetailView here: its case-3 branch ("detail open but
            // DETAILS_COLLECT hidden") burns 2s polling for a child that
            // will never appear, and the resulting busy-worker time blows
            // CollectOfferStep's tick budget — the bot then times out and
            // aborts after a SUCCESSFUL collect.
            List<CollectButton> remaining = pollForNewOrClearedButtons(clickedItemIds);
            if (remaining == null || remaining.isEmpty()) return;
            boolean anyNew = false;
            for (CollectButton b : remaining) {
                if (clickedItemIds.contains(b.itemId)) continue;
                log.info("ge collect: slot={} itemId={}", slot, b.itemId);
                log.info("collect({}): late click '{}' at {} (item={})", slot, b.verb, b.bounds, b.itemId);
                dispatcher.boundsClickOnWorker(b.bounds, null);
                clickedItemIds.add(b.itemId);
                anyNew = true;
                SequenceSleep.sleep(client, 180L + ThreadLocalRandom.current().nextInt(380));
            }
            if (!anyNew) return;
        }
    }

    /** Poll DETAILS_COLLECT every 100ms for up to {@code timeoutMs}, returning
     *  as soon as at least one populated collect-button child is visible.
     *  Returns an empty list on timeout. Used at entry to {@code runCollect-
     *  FromDetail}: the GE CS2 takes 1–3 ticks to render the icons after the
     *  detail view opens, and CollectOfferStep dispatches us the moment the
     *  detail view becomes visible — so we MUST patiently wait for the
     *  children rather than treating the empty initial read as failure. */
    private List<CollectButton> waitForCollectButtons(int slot, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        List<CollectButton> buttons = dispatcher.runOnClient(this::findCollectButtons);
        if (buttons != null && !buttons.isEmpty()) return buttons;
        while (System.currentTimeMillis() < deadline) {
            SequenceSleep.sleep(client, 100L);
            buttons = dispatcher.runOnClient(this::findCollectButtons);
            if (buttons != null && !buttons.isEmpty()) return buttons;
        }
        return List.of();
    }

    /** Poll DETAILS_COLLECT every 150ms for up to 2s, returning as soon as a
     *  NEW (not-yet-clicked) button appears or the container empties/closes.
     *  Falls back to the current widget state after the timeout.
     *
     *  <p>The GE CS2 renders the coins-refund child ~800ms after the item
     *  button is clicked; a flat sleep risks reading mid-transition (item
     *  widget still visible, coins not yet shown).  The anyNew==false early-
     *  exit in the caller then breaks the loop before coins are ever clicked. */
    private List<CollectButton> pollForNewOrClearedButtons(Set<Integer> clickedItemIds)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2_000L;
        while (System.currentTimeMillis() < deadline) {
            SequenceSleep.sleep(client, 150L);
            List<CollectButton> buttons = dispatcher.runOnClient(this::findCollectButtons);
            if (buttons == null || buttons.isEmpty()) return buttons;
            for (CollectButton b : buttons) {
                if (!clickedItemIds.contains(b.itemId)) return buttons;
            }
        }
        return dispatcher.runOnClient(this::findCollectButtons);
    }

    /** Ensure DETAILS_COLLECT has at least one visible child.
     *
     *  <p>Three cases:
     *  <ol>
     *    <li>{@code DETAILS_COLLECT} visible with children: already ready, return immediately.</li>
     *    <li>{@code DETAILS_COLLECT} visible but empty: CS2 needs 1-2 ticks to render
     *        the coins/item boxes — poll; do NOT try to click INDEX_N (hidden while
     *        detail view is showing).</li>
     *    <li>{@code DETAILS_COLLECT} hidden/null AND {@code INDEX_N} hidden: the detail
     *        view is open but CS2 has not yet shown the DETAILS_COLLECT sub-container.
     *        This is the common startup case — the bot dispatches {@code ge.collect}
     *        in the same engine tick that {@code collectOpen()} first becomes true,
     *        before the CS2 renders the collect buttons.  Poll exactly like case 2.</li>
     *    <li>{@code DETAILS_COLLECT} hidden/null AND {@code INDEX_N} visible: detail
     *        view is genuinely closed — click INDEX_N to open it, then poll.</li>
     *  </ol>
     *  Returns true when collect buttons appear within ~2 s; false otherwise. */
    private boolean reopenDetailView(int slot) throws InterruptedException {
        // Check DETAILS_COLLECT visibility first.
        Boolean containerVisible = dispatcher.runOnClient(() -> {
            Widget w = client.getWidget(InterfaceID.GeOffers.DETAILS_COLLECT);
            return w != null && !w.isHidden();
        });

        if (Boolean.TRUE.equals(containerVisible)) {
            // Case 2: container visible but no children yet.
            log.info("collect({}): DETAILS_COLLECT visible but empty — waiting for CS2", slot);
            long deadline = System.currentTimeMillis() + 2_000L;
            while (System.currentTimeMillis() < deadline) {
                SequenceSleep.sleep(client, 150L);
                List<CollectButton> check = dispatcher.runOnClient(this::findCollectButtons);
                if (check != null && !check.isEmpty()) return true;
            }
            log.warn("collect({}): DETAILS_COLLECT did not populate within 2s", slot);
            return false;
        }

        // DETAILS_COLLECT is hidden or null. Check INDEX_N to distinguish:
        //   • INDEX_N hidden  → detail view IS open, DETAILS_COLLECT not yet rendered (case 3)
        //   • INDEX_N visible → detail view is closed (case 4)
        // NOTE: INDEX_N is hidden by the GE CS2 while the detail view is showing
        // (GE_SELECTEDSLOT > 0), so checking it here is safe — the slot-click
        // comment in the original code noted this edge case.
        int containerId = slotIndexWidget(slot);
        if (containerId == 0) return false;
        Boolean slotVisible = dispatcher.runOnClient(() -> {
            Widget w = client.getWidget(containerId);
            return w != null && !w.isHidden();
        });

        if (!Boolean.TRUE.equals(slotVisible)) {
            // Case 3: INDEX_N hidden → detail view open, DETAILS_COLLECT not rendered yet.
            // This is the common race: ge.collect dispatched in the same tick
            // collectOpen() first fired, before CS2 renders the collect buttons.
            log.info("collect({}): detail view open but DETAILS_COLLECT hidden — waiting for CS2", slot);
            long deadline = System.currentTimeMillis() + 2_000L;
            while (System.currentTimeMillis() < deadline) {
                SequenceSleep.sleep(client, 150L);
                List<CollectButton> check = dispatcher.runOnClient(this::findCollectButtons);
                if (check != null && !check.isEmpty()) return true;
            }
            log.warn("collect({}): detail view open but DETAILS_COLLECT never populated within 2s", slot);
            return false;
        }

        // Case 4: detail view is closed, INDEX_N is visible — click to open it.
        log.info("collect({}): reopening offer detail view", slot);
        dispatcher.widgetClickOnWorker(containerId);
        long deadline = System.currentTimeMillis() + 2_000L;
        while (System.currentTimeMillis() < deadline) {
            SequenceSleep.sleep(client, 150L);
            List<CollectButton> check = dispatcher.runOnClient(this::findCollectButtons);
            if (check != null && !check.isEmpty()) return true;
        }
        return false;
    }

    private record CollectButton(Rectangle bounds, String verb, int itemId) {}

    /** Walk the dynamic children of {@code GeOffers.DETAILS_COLLECT} on
     *  the client thread and pick the DEFAULT left-click action for each
     *  visible child. Real player muscle-memory is "click the item icon →
     *  click the coins icon" — straight left-clicks, no right-click menu
     *  routing. The default action is {@code Collect-notes} for
     *  unstackable items (delivers the noted form), {@code Collect} for
     *  stackable items (potions, runes — already stack so noting is
     *  meaningless), and {@code Collect} for the coins child. Skips
     *  children whose default action isn't a Collect-* verb (defensive
     *  against future widget-tree changes). Must run on the client thread. */
    private List<CollectButton> findCollectButtons() {
        Widget container = client.getWidget(InterfaceID.GeOffers.DETAILS_COLLECT);
        if (container == null || container.isHidden()) return List.of();
        Widget[] kids = container.getDynamicChildren();
        if (kids == null) return List.of();
        List<CollectButton> out = new ArrayList<>(2);
        for (Widget k : kids) {
            if (k == null || k.isHidden()) continue;
            Rectangle b = k.getBounds();
            if (b == null || b.isEmpty()) continue;
            String[] actions = k.getActions();
            if (actions == null || actions.length == 0) continue;
            String defaultVerb = actions[0];
            if (defaultVerb == null || defaultVerb.isEmpty()) continue;
            // Defensive — DETAILS_COLLECT children should always have a
            // Collect-* default action, but skip non-collect children just
            // in case the widget tree changes shape.
            String n = VerbMatcher.normalise(defaultVerb);
            if (!n.startsWith("collect")) continue;
            out.add(new CollectButton(b, defaultVerb, k.getItemId()));
        }
        return out;
    }

    @Override
    public void collectAll() {
        // GeOffers.COLLECTALL toolbar button. Default verb captured from
        // click-inspector 2026-05-01: actions=['Collect to inventory',
        // 'Collect to bank'], entryId=1 → left-click = "Collect to inventory".
        // Plain left-click — see openOfferDetail for why we avoid the
        // verb-routed path here (its right-click fallback closes the GE on
        // verb mismatch via VK_ESCAPE).
        clickWidget(InterfaceID.GeOffers.COLLECTALL, "collectAll");
    }

    @Override
    public void closeGrandExchange() {
        try {
            dispatcher.tapKey(KeyEvent.VK_ESCAPE);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /** Same as {@link #clickWidget} but dispatches with an explicit verb,
     *  triggering {@code widgetVerbClick} in the dispatcher (hover-check
     *  then left-click if default action matches, right-click + menu select
     *  otherwise). Use when the desired action is NOT the first default
     *  action on the widget (or to be explicit about what we expect). */
    private void clickWidgetVerb(int widgetId, String verb, String purpose) {
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
                .verb(verb)
                .build();
            dispatcher.dispatch(req);
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
