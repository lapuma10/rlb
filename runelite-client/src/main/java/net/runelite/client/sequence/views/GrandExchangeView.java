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

    /** True when the GE item-search result list is rendered with at least
     *  one row. The chatbox-search results widget
     *  ({@code Chatbox.MES_LAYER_SCROLLCONTENTS}) carries dynamic children
     *  in groups of 3 per row; this is true iff that container is visible
     *  and has at least one full group. Used by {@code PickSearchResultStep}
     *  to detect that the search dialog has closed (results gone) after
     *  a row click — i.e. the cs2 transitioned the offer setup to the
     *  next input state. */
    boolean searchResultsPopulated();

    /** True when any chatbox text/numeric input prompt is currently open
     *  ({@code VarClientID.MESLAYERMODE != 0}). Single signal that covers
     *  search / quantity / price prompts; finer-grained mode discrimination
     *  is in {@link #chatboxPromptMode()} for steps that need it. */
    boolean chatboxPromptOpen();

    /** Raw {@code MESLAYERMODE} varc value — useful for steps that need to
     *  detect a transition between two distinct prompts (e.g. search → set
     *  quantity) since the modes for each are different. {@code 0} means
     *  no prompt is open. */
    int chatboxPromptMode();

    /** Raw {@code VarbitID.GE_NEWOFFER_TYPE} value — the engine's signal
     *  for which side of offer-setup is currently active.
     *  <ul>
     *    <li>{@code 0} — no offer-setup active.</li>
     *    <li>{@code 1} — SELL setup active (verified via click-inspector
     *        2026-05-01: clicking "Create Sell offer" flipped the varbit
     *        from 0 → 1 in the same tick).</li>
     *    <li>{@code 2} — BUY setup active.</li>
     *  </ul>
     *  Use this instead of tick-counting after {@code clickOfferSlotButton}
     *  to know when the setup is truly ready to accept item input — the
     *  inventory action map (Use/Drop/Examine → Offer-1/.../Offer-All)
     *  has finished swapping by the time this varbit reads non-zero. */
    int newOfferType();

    /** Raw {@code VarbitID.GE_NEWOFFER_QUANTITY} value — non-zero iff the
     *  engine has attached an item to the active sell/buy setup and
     *  populated a default quantity. Use this to verify a
     *  select-sell-item click actually landed (don't just trust the
     *  dispatch — the Inventory.ITEMS widget is hidden when GE setup is
     *  open, and a CLICK_INV_ITEM that resolves against it silently
     *  misses). Reading 0 after a click + a few ticks means the click
     *  didn't reach the right widget. */
    int newOfferQuantity();

    /** True when the OSRS "Your offer is much higher / lower than the guide
     *  price. Are you sure you wish to place this offer?" warning popup is
     *  on screen. Detected via {@code Popupoverlay.UNIVERSE} visibility +
     *  the body text containing "guide price". {@code ConfirmOfferStep}
     *  dispatches the appropriate Yes/No dismiss based on whether the
     *  trade is a price-check (accept) or a normal trade (reject and
     *  abort with {@code GeOfferPriceTooHigh}). */
    boolean priceWarningOpen();

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
        public boolean searchResultsPopulated() { return false; }
        public boolean chatboxPromptOpen()    { return false; }
        public int chatboxPromptMode()        { return 0; }
        public int newOfferType()             { return 0; }
        public int newOfferQuantity()         { return 0; }
        public boolean priceWarningOpen()     { return false; }
        public List<GrandExchangeOfferView> offers()              { return emptyOffers; }
        public OptionalInt firstEmptySlot()                       { return OptionalInt.of(0); }
        public int emptySlotCount()                               { return 8; }
        public List<GrandExchangeOfferView> offersFor(int itemId, OfferSide side) {
            return List.of();
        }
    };
}
