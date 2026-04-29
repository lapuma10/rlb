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

    /** In the offer-setup interface, set the item being traded. The impl
     *  may type-and-click-result or use the direct varc-set shortcut; the
     *  step verifies via {@code GrandExchangeView.offerSetupOpen()} +
     *  the selected-item widget value. */
    void selectItem(int itemId, String displayName);

    /** Click the quantity *X widget and submit the typed quantity via the
     *  humanized dispatcher's chatbox helper. */
    void setQuantity(int qty);

    /** Click the price *X widget and submit the typed coin-per-item price. */
    void setPrice(int priceEach);

    /** Click the "Confirm Offer" button on the offer-setup interface. */
    void confirmOffer();

    /** Collect proceeds from the indicated slot (left-click → inventory). */
    void collect(int slot);

    /** Close the GE main interface (Escape / X). */
    void closeGrandExchange();
}
