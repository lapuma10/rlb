package net.runelite.client.sequence.activities.ge;

import net.runelite.client.sequence.views.OfferSide;

/**
 * Mutating actions a GE step library dispatches through. The production
 * implementation ({@code GeInteraction}) wraps the humanized input
 * dispatcher and resolves widget bounds at click time. Tests pass a
 * recording impl ({@code RecordingGeActions}) that captures call sequences
 * without touching the live RuneLite client.
 *
 * <p>Each method is a single humanized-dispatch chain. Methods are
 * fail-silent if the target widget is hidden; verification is the step's
 * job (its {@code check}).
 */
public interface GeActions {

    /** Click the GE clerk's "Exchange" verb (or an Exchange booth) to open
     *  the main offers interface. */
    void openGrandExchange();

    /** Click the BUY or SELL button on the indicated empty offer slot,
     *  surfacing the offer-setup interface for that slot. */
    void clickOfferSlotButton(int slot, OfferSide side);

    /** Click the indicated offer slot's container widget directly (no verb
     *  match), surfacing the offer-detail / collect view for slots holding an
     *  active or completed offer. Use this instead of
     *  {@link #clickOfferSlotButton} when the slot is NOT empty — the verb
     *  match in the latter looks for "Create Buy/Sell offer" which only
     *  exists on empty slots. */
    void openOfferDetail(int slot);

    /** Type the item name into the GE search chatbox WITHOUT submitting.
     *  Returns true if the chatbox-search prompt opened, the name was
     *  typed, and the result list rendered with at least one group of 3
     *  dynamic children. The caller is expected to follow up with
     *  {@link #pickSearchResult(int)} to validate the list contains the
     *  intended item id and click that specific row — never trust the
     *  engine's auto-pick (Enter), which can land on a partial match like
     *  "Team cape 25" for an input of "25". */
    boolean selectItem(int itemId, String displayName);

    /** Walk the search-result rows in {@code Chatbox.MES_LAYER_SCROLLCONTENTS}
     *  and click the row whose icon's {@code getItemId()} equals
     *  {@code itemId}. Returns true on a confirmed match-and-click, false
     *  if no result row carries the requested item id (typo, mistyped name,
     *  or item not tradeable on this account). */
    boolean pickSearchResult(int itemId);

    /** Type the quantity into the chatbox numeric prompt and submit.
     *  Returns true on type+submit success; false if the prompt never
     *  appeared. */
    boolean setQuantity(int qty);

    /** Type the per-item price into the chatbox numeric prompt and submit.
     *  Returns true on type+submit success; false if the prompt never
     *  appeared. */
    boolean setPrice(int priceEach);

    /** Click the "Confirm Offer" button on the offer-setup interface. */
    void confirmOffer();

    /** Dismiss the OSRS "Your offer is much higher than the guide price"
     *  warning popup. {@code accept=true} → click "Yes" (proceed at the
     *  overpriced bid; used by price-check probes). {@code accept=false}
     *  → click "No" (cancel the offer; default for normal trades, caller
     *  is expected to retry with a lower price).
     *
     *  <p>Targets {@code Popupoverlay.BUTTON_1} (Yes, 0x01210008) /
     *  {@code BUTTON_0} (No, 0x01210007). */
    void dismissPriceWarning(boolean accept);

    /** Collect proceeds from the indicated slot (left-click → inventory). */
    void collect(int slot);

    /** Click the {@code GeOffers.COLLECTALL} toolbar button on the main GE
     *  view — drains every completed offer (items AND leftover coins from
     *  partial fills) into the player's inventory in one click. Visible
     *  whenever the GE main 8-slot grid is showing and at least one slot
     *  has a completed or partially-completed offer to collect.
     *
     *  <p>Preferred over {@link #collect(int)} when there's no need to be
     *  surgical about a specific slot — bypasses the detail-view dance and
     *  the per-slot widget visibility issues (e.g. {@code INDEX_N} self-
     *  hidden when the slot has a stuck offer). */
    void collectAll();

    /** Close the GE main interface (Escape / X). */
    void closeGrandExchange();
}
