package net.runelite.client.sequence.activities.ge;

/**
 * UI-side request for a GE sell. Mirror of {@link BuyItemIntent}.
 */
public record SellItemIntent(
    int itemId,
    String displayName,
    int quantity,
    PricePolicy pricePolicy,
    OfferWaitPolicy waitPolicy
) {
    public SellItemIntent {
        if (itemId <= 0)            throw new IllegalArgumentException("itemId must be > 0, got " + itemId);
        if (quantity <= 0)          throw new IllegalArgumentException("quantity must be > 0, got " + quantity);
        if (pricePolicy == null)    throw new IllegalArgumentException("pricePolicy must not be null");
        if (waitPolicy == null)     throw new IllegalArgumentException("waitPolicy must not be null");
        if (displayName == null)    displayName = "item#" + itemId;
    }
}
