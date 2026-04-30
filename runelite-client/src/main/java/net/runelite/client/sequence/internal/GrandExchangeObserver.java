package net.runelite.client.sequence.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.WorldType;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
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
        boolean collectOpen = isVisible(InterfaceID.GeCollect.UNIVERSE);

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
        return new SnapshotGrandExchangeView(offersOpen, setupOpen, collectOpen,
            List.copyOf(slots), usableSlots);
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
        List<GrandExchangeOfferView> offers,
        int usableSlots
    ) implements GrandExchangeView {

        @Override public OptionalInt firstEmptySlot() {
            for (int i = 0; i < usableSlots && i < offers.size(); i++) {
                GrandExchangeOfferView o = offers.get(i);
                if (o.isEmpty()) return OptionalInt.of(o.slot());
            }
            return OptionalInt.empty();
        }

        @Override public int emptySlotCount() {
            int n = 0;
            for (int i = 0; i < usableSlots && i < offers.size(); i++) {
                if (offers.get(i).isEmpty()) n++;
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
