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
     *  reaching for the keyboard. 3–8s is intentionally on the long side —
     *  instant-typing immediately after the prompt opens is the most
     *  obvious bot tell on GE search / quantity / price flows. */
    private static final long CHATBOX_DWELL_MIN_MS = 3_000L;
    private static final long CHATBOX_DWELL_MAX_MS = 8_000L;

    @Override
    public boolean selectItem(int itemId, String displayName) {
        // Step 1 of the two-step item flow: type the search name into the
        // chatbox prompt WITHOUT submitting Enter. We don't trust the
        // engine's auto-pick — pickSearchResult is responsible for finding
        // the row whose icon's getItemId() matches our intent and clicking
        // exactly that row. If the caller passed a placeholder ("item#NNN")
        // because the UI received a numeric id, resolve the canonical name
        // from the item definition first so the search returns sensible
        // matches.
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
        try {
            if (!dispatcher.typeChatbox(name, CHATBOX_AWAIT_MS,
                CHATBOX_DWELL_MIN_MS, CHATBOX_DWELL_MAX_MS)) {
                log.warn("ge: search chatbox prompt did not appear within {}ms — aborting type for item {}",
                    CHATBOX_AWAIT_MS, itemId);
                return false;
            }
            // Wait for the result list to render before declaring success —
            // pickSearchResult needs the dynamic children populated.
            if (!dispatcher.awaitSearchResultsPopulated(CHATBOX_AWAIT_MS)) {
                log.warn("ge: search results widget did not populate within {}ms for item {} (\"{}\")",
                    CHATBOX_AWAIT_MS, itemId, name);
                return false;
            }
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public boolean pickSearchResult(int itemId) {
        // Search results live at Chatbox.MES_LAYER_SCROLLCONTENTS as
        // dynamic children in groups of 3 per row — pattern lifted from
        // RuneLite's GrandExchangePlugin#highlightSearchMatches. Layout
        // observed in the wild: index 0 = item icon (carries getItemId),
        // index 1 = item-name text, index 2 = price text. We match by id
        // (unique) and click the icon child's bounds — its rectangle is
        // tight to the row's left edge but the engine accepts a click
        // anywhere on the row.
        try {
            ResultRow row = dispatcher.runOnClient(() -> findResultRowFor(itemId));
            if (row == null) {
                log.warn("pickSearchResult: no row with itemId={} in search results", itemId);
                return false;
            }
            log.info("pickSearchResult: itemId={} → bounds={}", itemId, row.bounds);
            ActionRequest req = ActionRequest.builder()
                .kind(ActionRequest.Kind.CLICK_BOUNDS)
                .channel(ActionRequest.Channel.MOUSE)
                .bounds(row.bounds)
                .build();
            // Pre-click human dwell to look like the player scanned the
            // list before clicking — the typing dwell already covered the
            // post-prompt pause, but reading 5–20 result rows takes its
            // own visual beat.
            long dwell = CHATBOX_DWELL_MIN_MS
                + (long) (Math.random() * (CHATBOX_DWELL_MAX_MS - CHATBOX_DWELL_MIN_MS + 1));
            Thread.sleep(dwell);
            dispatcher.dispatch(req);
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private record ResultRow(Rectangle bounds) {}

    /** Walk dynamic children of {@code MES_LAYER_SCROLLCONTENTS} in groups
     *  of 3, searching for an icon child whose {@code getItemId()} equals
     *  {@code itemId}. Returns the union of bounds across the row's three
     *  children so the click lands somewhere humanly-plausible (icon, name,
     *  or price text — not a tiny pixel inside one of them). Must run on
     *  the client thread. */
    private ResultRow findResultRowFor(int itemId) {
        Widget container = client.getWidget(InterfaceID.Chatbox.MES_LAYER_SCROLLCONTENTS);
        if (container == null || container.isHidden()) return null;
        Widget[] kids = container.getDynamicChildren();
        if (kids == null || kids.length < 3) return null;
        int rowCount = kids.length / 3;
        for (int i = 0; i < rowCount; i++) {
            Widget icon = kids[i * 3];
            if (icon == null) continue;
            if (icon.getItemId() != itemId) continue;
            Rectangle merged = null;
            for (int j = 0; j < 3; j++) {
                Widget part = kids[i * 3 + j];
                if (part == null) continue;
                Rectangle b = part.getBounds();
                if (b == null || b.isEmpty()) continue;
                merged = merged == null ? new Rectangle(b) : merged.union(b);
            }
            if (merged == null || merged.isEmpty()) continue;
            return new ResultRow(merged);
        }
        return null;
    }

    @Override
    public boolean setQuantity(int qty) {
        // After a successful selectItem (item-name search → Enter), the cs2
        // script transitions the chatbox directly to the "Set quantity"
        // numeric prompt — no separate *X click needed. The same conservative
        // timeout applies because cs2 transitions still cost a tick or two.
        try {
            if (!dispatcher.typeChatboxAndEnter(Integer.toString(qty), CHATBOX_AWAIT_MS,
                CHATBOX_DWELL_MIN_MS, CHATBOX_DWELL_MAX_MS)) {
                log.warn("ge: quantity chatbox prompt did not appear within {}ms — aborting type qty={}",
                    CHATBOX_AWAIT_MS, qty);
                return false;
            }
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public boolean setPrice(int priceEach) {
        try {
            if (!dispatcher.typeChatboxAndEnter(Integer.toString(priceEach), CHATBOX_AWAIT_MS,
                CHATBOX_DWELL_MIN_MS, CHATBOX_DWELL_MAX_MS)) {
                log.warn("ge: price chatbox prompt did not appear within {}ms — aborting type price={}",
                    CHATBOX_AWAIT_MS, priceEach);
                return false;
            }
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
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
