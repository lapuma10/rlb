package net.runelite.client.sequence.views;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

/**
 * Snapshot view of the eight Grand Exchange offer slots and the currently
 * visible GE interfaces (offers / offer-setup / collect).
 *
 * <p>Steps reason about offers via this view rather than touching the live
 * RuneLite {@code Client.getGrandExchangeOffers()} array on a non-client
 * thread. The observer ({@code GrandExchangeObserver}, Task 6) populates
 * this from the live state on every snapshot tick.
 *
 * <p>{@link #empty()} returns the engine-default null-object: GE closed,
 * all 8 slots {@code EMPTY}.
 */
public interface GrandExchangeView {

    /** True when the main GE offers interface is rendered. */
    boolean open();

    /** True when the per-slot offer-setup interface is rendered. */
    boolean offerSetupOpen();

    /** True when the post-completion collect interface is rendered. */
    boolean collectOpen();

    /** All eight slots, in index order 0..7. */
    List<GrandExchangeOfferView> offers();

    /** First slot whose status is {@link OfferStatus#EMPTY}, or empty
     *  Optional if all slots are occupied. */
    OptionalInt firstEmptySlot();

    /** Number of slots whose status is {@link OfferStatus#EMPTY}. */
    int emptySlotCount();

    /** Slots currently holding any offer for {@code (itemId, side)} (any
     *  non-EMPTY status), in slot order. */
    List<GrandExchangeOfferView> offersFor(int itemId, OfferSide side);

    /** Engine-default null-object: GE closed, all 8 slots EMPTY. */
    static GrandExchangeView empty() { return EMPTY; }

    GrandExchangeView EMPTY = new GrandExchangeView() {
        private final List<GrandExchangeOfferView> emptyOffers = buildEmpty();

        private static List<GrandExchangeOfferView> buildEmpty() {
            List<GrandExchangeOfferView> out = new ArrayList<>(8);
            for (int i = 0; i < 8; i++) out.add(GrandExchangeOfferView.empty(i));
            return List.copyOf(out);
        }

        public boolean open()                 { return false; }
        public boolean offerSetupOpen()       { return false; }
        public boolean collectOpen()          { return false; }
        public List<GrandExchangeOfferView> offers()              { return emptyOffers; }
        public OptionalInt firstEmptySlot()                       { return OptionalInt.of(0); }
        public int emptySlotCount()                               { return 8; }
        public List<GrandExchangeOfferView> offersFor(int itemId, OfferSide side) {
            return List.of();
        }
    };
}
