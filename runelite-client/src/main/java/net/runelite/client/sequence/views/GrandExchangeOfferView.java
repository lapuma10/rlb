package net.runelite.client.sequence.views;

/**
 * Snapshot of one Grand Exchange slot. Mapped from
 * {@link net.runelite.api.GrandExchangeOffer} via spec §6.1.
 *
 * <p>{@code itemId} is 0 when {@link OfferStatus#EMPTY}.
 * {@code completedQuantity} is {@code quantitySold} from the API — items
 * delivered so far; {@code requestedQuantity} is the original total
 * requested.
 *
 * <p>{@code spent} is the running coin total moved by the offer (negative
 * direction for the player on a buy, positive on a sell — the API's sign
 * convention).
 */
public record GrandExchangeOfferView(
    int slot,
    OfferSide side,
    OfferStatus status,
    int itemId,
    int requestedQuantity,
    int completedQuantity,
    int priceEach,
    int spent
) {
    public boolean isEmpty()             { return status == OfferStatus.EMPTY; }
    public boolean isComplete()          { return status == OfferStatus.COMPLETE; }
    public boolean isActive() {
        return status == OfferStatus.ACTIVE || status == OfferStatus.PARTIALLY_COMPLETE;
    }
    public boolean isCancelled()         { return status == OfferStatus.CANCELLED; }

    /** Convenience: empty placeholder for a slot at index {@code slot}. */
    public static GrandExchangeOfferView empty(int slot) {
        return new GrandExchangeOfferView(slot, OfferSide.NONE, OfferStatus.EMPTY,
            0, 0, 0, 0, 0);
    }
}
