package net.runelite.client.sequence.internal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.OptionalInt;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.WorldType;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.recorder.transport.VerbMatcher;
import net.runelite.client.sequence.views.GrandExchangeOfferView;
import net.runelite.client.sequence.views.GrandExchangeView;
import net.runelite.client.sequence.views.OfferSide;
import net.runelite.client.sequence.views.OfferStatus;

/**
 * Reads {@link Client#getGrandExchangeOffers()} + GE-interface widgets on the
 * client thread to produce an immutable {@link GrandExchangeView} snapshot.
 *
 * <p>Maps each {@link GrandExchangeOfferState} to {@link OfferStatus} per spec
 * §6.1 (BUYING/SELLING with completedQuantity == 0 → ACTIVE; with
 * 0 &lt; completedQuantity &lt; total → PARTIALLY_COMPLETE; BOUGHT/SOLD →
 * COMPLETE; CANCELLED_* → CANCELLED).
 */
public final class GrandExchangeObserver {

    private static final int SLOT_COUNT = 8;
    /** Slots 0-2 are usable for everyone (free + members); slots 3-7 are
     *  members-only. The OSRS UI hides the +/buttons for the locked slots
     *  on F2P, so {@code firstEmptySlot()} must not return them. */
    private static final int F2P_SLOT_COUNT = 3;

    private final Client client;

    public GrandExchangeObserver(Client client) {
        this.client = client;
    }

    public GrandExchangeView read(int tick) {
        boolean offersOpen = isVisible(InterfaceID.GeOffers.UNIVERSE);
        // Offer-setup is a child of the main offers interface; its child id is
        // GeOffers.SETUP. When the main interface is up AND the SETUP child is
        // visible, the per-slot offer-setup view is active.
        boolean setupOpen = offersOpen && isVisible(InterfaceID.GeOffers.SETUP);
        // Two collect-view shapes — both expose collect actions:
        //   - GeCollect.UNIVERSE — legacy popup overlay (login auto-popup)
        //   - GeOffers.DETAILS — the in-grid detail view that the slot's
        //     "View offer" click surfaces. DETAILS_COLLECT lives here.
        // collect(slot) clicks DETAILS_COLLECT children, so we MUST treat the
        // DETAILS view as collect-open or the step's phase machine never
        // advances (observed live 2026-04-30 20:01: PER_SLOT openOfferDetail
        // landed but check() saw collectOpen=false because DETAILS != GeCollect).
        boolean collectOpen = isVisible(InterfaceID.GeCollect.UNIVERSE)
            || (offersOpen && isVisible(InterfaceID.GeOffers.DETAILS));
        // Generic OSRS warning popup ("Your offer is much higher than the
        // guide price...") — Popupoverlay is shared with other warnings, so
        // narrow to "GE flow active + popup body mentions guide price".
        boolean priceWarning = offersOpen && isVisible(InterfaceID.Popupoverlay.UNIVERSE)
            && popupBodyMentions("guide price");

        // Search-result detection: chatbox MES_LAYER_SCROLLCONTENTS carries
        // the result rows as dynamic children in groups of 3 — at least one
        // full group means at least one row rendered. (Pattern lifted from
        // RuneLite's GrandExchangePlugin#highlightSearchMatches.)
        boolean searchPopulated = false;
        Widget searchResults = client.getWidget(InterfaceID.Chatbox.MES_LAYER_SCROLLCONTENTS);
        if (searchResults != null && !searchResults.isHidden()) {
            Widget[] kids = searchResults.getDynamicChildren();
            searchPopulated = kids != null && kids.length >= 3;
        }
        int promptMode = client.getVarcIntValue(
            net.runelite.api.gameval.VarClientID.MESLAYERMODE);

        // Members detection: cap usable slots to 3 for F2P. Worlds without
        // the MEMBERS flag are F2P; the locked slot widgets aren't clickable
        // there, so picking firstEmptySlot()=3 would silently no-op.
        int usableSlots = client.getWorldType().contains(WorldType.MEMBERS)
            ? SLOT_COUNT : F2P_SLOT_COUNT;

        GrandExchangeOffer[] raw = client.getGrandExchangeOffers();
        List<GrandExchangeOfferView> slots = new ArrayList<>(SLOT_COUNT);
        if (raw == null) {
            for (int i = 0; i < SLOT_COUNT; i++) slots.add(GrandExchangeOfferView.empty(i));
        } else {
            for (int i = 0; i < SLOT_COUNT; i++) {
                GrandExchangeOffer o = (i < raw.length) ? raw[i] : null;
                slots.add(o == null ? GrandExchangeOfferView.empty(i) : map(i, o));
            }
        }

        // Cross-check each slot's widget for an actual "Create Buy/Sell offer"
        // descendant. firstEmptySlot() must agree with what's clickable RIGHT
        // NOW — the API-only check missed leftover offers that the OSRS UI
        // still rendered as occupied (slot 0 stuck-offer regression of
        // 2026-04-30). Only walk widget trees while the GE is open; the
        // values are unused otherwise.
        boolean[] slotCanCreate = new boolean[SLOT_COUNT];
        if (offersOpen) {
            for (int i = 0; i < SLOT_COUNT; i++) {
                slotCanCreate[i] = slotHasCreateOfferButton(slotIndexWidget(i));
            }
        }

        return new SnapshotGrandExchangeView(offersOpen, setupOpen, collectOpen,
            searchPopulated, promptMode, priceWarning, List.copyOf(slots), usableSlots,
            slotCanCreate);
    }

    /** Walk a slot container widget for any descendant whose first non-empty
     *  action matches "Create Buy offer" or "Create Sell offer". Mirrors
     *  {@code GeInteraction.findSideButton} but without bounds extraction.
     *  Must run on the client thread. */
    private boolean slotHasCreateOfferButton(int containerId) {
        Widget root = client.getWidget(containerId);
        if (root == null || root.isHidden()) return false;
        Deque<Widget> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            Widget w = stack.pop();
            if (w == null || w.isHidden()) continue;
            String[] actions = w.getActions();
            if (actions != null) {
                for (String a : actions) {
                    if (a == null || a.isEmpty()) continue;
                    if (VerbMatcher.matches("Create Buy offer", a)
                        || VerbMatcher.matches("Create Sell offer", a)) {
                        return true;
                    }
                }
            }
            pushAll(stack, w.getDynamicChildren());
            pushAll(stack, w.getStaticChildren());
            pushAll(stack, w.getNestedChildren());
        }
        return false;
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

    private static GrandExchangeOfferView map(int slot, GrandExchangeOffer o) {
        GrandExchangeOfferState st = o.getState();
        OfferSide side = sideFor(st);
        OfferStatus status = statusFor(st, o.getQuantitySold(), o.getTotalQuantity());
        return new GrandExchangeOfferView(slot, side, status,
            o.getItemId(), o.getTotalQuantity(), o.getQuantitySold(),
            o.getPrice(), o.getSpent());
    }

    private static OfferSide sideFor(GrandExchangeOfferState st) {
        return switch (st) {
            case BUYING, BOUGHT, CANCELLED_BUY -> OfferSide.BUY;
            case SELLING, SOLD, CANCELLED_SELL -> OfferSide.SELL;
            case EMPTY -> OfferSide.NONE;
        };
    }

    private static OfferStatus statusFor(GrandExchangeOfferState st, int completed, int total) {
        return switch (st) {
            case EMPTY -> OfferStatus.EMPTY;
            case BUYING, SELLING -> {
                if (completed <= 0) yield OfferStatus.ACTIVE;
                else if (completed < total) yield OfferStatus.PARTIALLY_COMPLETE;
                else yield OfferStatus.COMPLETE;   // shouldn't happen but defensive
            }
            case BOUGHT, SOLD -> OfferStatus.COMPLETE;
            case CANCELLED_BUY, CANCELLED_SELL -> OfferStatus.CANCELLED;
        };
    }

    /** True if any visible text widget under {@code Popupoverlay.UNIVERSE}
     *  contains the given (lower-cased) substring. Used to narrow the
     *  generic popup detector to specifically the price-warning popup. */
    private boolean popupBodyMentions(String needleLower) {
        Widget root = client.getWidget(InterfaceID.Popupoverlay.UNIVERSE);
        if (root == null || root.isHidden()) return false;
        Deque<Widget> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            Widget w = stack.pop();
            if (w == null || w.isHidden()) continue;
            String t = w.getText();
            if (t != null && !t.isEmpty()) {
                // Locale.ROOT to avoid the Turkish-locale 'I' bug — the
                // needle is ASCII so default-locale lowering would only
                // diverge for non-English clients, but this is the
                // codebase convention (see GeInteraction.selectItem).
                if (t.toLowerCase(java.util.Locale.ROOT).contains(needleLower)) return true;
            }
            pushAll(stack, w.getDynamicChildren());
            pushAll(stack, w.getStaticChildren());
            pushAll(stack, w.getNestedChildren());
        }
        return false;
    }

    private boolean isVisible(int widgetId) {
        Widget w = client.getWidget(widgetId);
        if (w == null) return false;
        // Walk the ancestor chain — a leaf-only check misses tabs collapsed
        // at the root layer (CLAUDE.md §1).
        for (Widget cur = w; cur != null; cur = cur.getParent()) {
            if (cur.isHidden()) return false;
        }
        return true;
    }

    private record SnapshotGrandExchangeView(
        boolean open,
        boolean offerSetupOpen,
        boolean collectOpen,
        boolean searchResultsPopulated,
        int chatboxPromptMode,
        boolean priceWarningOpen,
        List<GrandExchangeOfferView> offers,
        int usableSlots,
        boolean[] slotCanCreate
    ) implements GrandExchangeView {

        @Override public boolean chatboxPromptOpen() { return chatboxPromptMode != 0; }

        @Override public OptionalInt firstEmptySlot() {
            for (int i = 0; i < usableSlots && i < offers.size(); i++) {
                GrandExchangeOfferView o = offers.get(i);
                // Reject API-empty slots whose widget still renders an
                // existing offer (no Create-offer buttons descendant). This
                // catches leftover offers the API doesn't repopulate fast
                // enough after open, and prior-session offers not in the
                // API array.
                if (o.isEmpty() && slotCanCreate[i]) return OptionalInt.of(o.slot());
            }
            return OptionalInt.empty();
        }

        @Override public int emptySlotCount() {
            int n = 0;
            for (int i = 0; i < usableSlots && i < offers.size(); i++) {
                if (offers.get(i).isEmpty() && slotCanCreate[i]) n++;
            }
            return n;
        }

        @Override public List<GrandExchangeOfferView> offersFor(int itemId, OfferSide side) {
            List<GrandExchangeOfferView> out = new ArrayList<>(2);
            for (GrandExchangeOfferView o : offers) {
                if (o.itemId() == itemId && o.side() == side && !o.isEmpty()) {
                    out.add(o);
                }
            }
            return List.copyOf(out);
        }
    }
}
