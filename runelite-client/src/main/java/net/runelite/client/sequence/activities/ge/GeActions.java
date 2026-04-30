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

    /** Collect proceeds from the indicated slot (left-click → inventory). */
    void collect(int slot);

    /** Close the GE main interface (Escape / X). */
    void closeGrandExchange();
}
