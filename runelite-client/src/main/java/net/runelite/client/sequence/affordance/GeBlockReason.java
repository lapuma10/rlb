package net.runelite.client.sequence.affordance;

import net.runelite.api.coords.WorldArea;
import net.runelite.client.sequence.views.OfferSide;
import net.runelite.client.sequence.views.OfferStatus;

/**
 * Grand-Exchange-domain blocking / failure reasons.
 *
 * <p>{@link InsufficientCoins} and {@link InsufficientSellItems} are the
 * primary inventory-pre-flight guards in GE Core (no upstream bank
 * withdraw catches them).
 *
 * <p>Mismatches ({@code GeOffer{Quantity,Item,Price}Mismatch}) are surfaced
 * by {@code ConfirmOfferStep} when the offer that arrives in the slot
 * disagrees with the requested intent.
 */
public sealed interface GeBlockReason extends DiagnosticReason
    permits GeBlockReason.NotAtGrandExchange,
            GeBlockReason.GeNotOpen,
            GeBlockReason.GeOfferSetupNotOpen,
            GeBlockReason.GeCollectNotOpen,
            GeBlockReason.GeSlotsFull,
            GeBlockReason.GeExistingOfferConflict,
            GeBlockReason.GeOfferRejected,
            GeBlockReason.GeOfferTimeout,
            GeBlockReason.GeOfferIncomplete,
            GeBlockReason.GeOfferQuantityMismatch,
            GeBlockReason.GeOfferItemMismatch,
            GeBlockReason.GeOfferPriceMismatch,
            GeBlockReason.GeCollectFailed,
            GeBlockReason.InsufficientCoins,
            GeBlockReason.InsufficientSellItems {

    record NotAtGrandExchange(WorldArea required) implements GeBlockReason {}
    record GeNotOpen() implements GeBlockReason {}
    record GeOfferSetupNotOpen() implements GeBlockReason {}
    record GeCollectNotOpen() implements GeBlockReason {}
    record GeSlotsFull() implements GeBlockReason {}
    record GeExistingOfferConflict(int slot, OfferSide side, int itemId, OfferStatus status) implements GeBlockReason {}
    record GeOfferRejected(String reasonText) implements GeBlockReason {}
    record GeOfferTimeout(int slot, int ticks) implements GeBlockReason {}
    record GeOfferIncomplete(int slot, int completed, int requested) implements GeBlockReason {}
    record GeOfferQuantityMismatch(int slot, int expected, int actual) implements GeBlockReason {}
    record GeOfferItemMismatch(int slot, int expectedItemId, int actualItemId) implements GeBlockReason {}
    record GeOfferPriceMismatch(int slot, int expectedPriceEach, int actualPriceEach) implements GeBlockReason {}
    record GeCollectFailed(int slot, int expectedDeltaItemId, int observedDelta) implements GeBlockReason {}
    record InsufficientCoins(int needed, int have) implements GeBlockReason {}
    record InsufficientSellItems(int itemId, int needed, int have) implements GeBlockReason {}
}
