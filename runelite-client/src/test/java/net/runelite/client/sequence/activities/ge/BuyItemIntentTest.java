package net.runelite.client.sequence.activities.ge;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class BuyItemIntentTest {

    @Test
    public void zeroQuantityRejected() {
        try {
            new BuyItemIntent(4151, "Abyssal whip", 0, new PricePolicy.Exact(1), OfferWaitPolicy.until(100));
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) { /* ok */ }
    }

    @Test
    public void negativeQuantityRejected() {
        try {
            new BuyItemIntent(4151, "Abyssal whip", -1, new PricePolicy.Exact(1), OfferWaitPolicy.until(100));
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) { /* ok */ }
    }

    @Test
    public void zeroItemIdRejected() {
        try {
            new BuyItemIntent(0, "Abyssal whip", 1, new PricePolicy.Exact(1), OfferWaitPolicy.until(100));
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) { /* ok */ }
    }

    @Test
    public void nullPricePolicyRejected() {
        try {
            new BuyItemIntent(4151, "Abyssal whip", 1, null, OfferWaitPolicy.until(100));
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) { /* ok */ }
    }

    @Test
    public void nullDisplayNameDefaultsToItemId() {
        BuyItemIntent intent = new BuyItemIntent(
            4151, null, 1, new PricePolicy.Exact(1), OfferWaitPolicy.until(100));
        assertEquals("item#4151", intent.displayName());
    }

    @Test
    public void validIntent() {
        BuyItemIntent intent = new BuyItemIntent(
            4151, "Abyssal whip", 5, new PricePolicy.Exact(1_500_000), OfferWaitPolicy.until(300));
        assertEquals(4151, intent.itemId());
        assertEquals(5, intent.quantity());
        assertEquals(1_500_000, ((PricePolicy.Exact) intent.pricePolicy()).coinsEach());
        assertEquals(300, intent.waitPolicy().timeoutTicks());
    }
}
