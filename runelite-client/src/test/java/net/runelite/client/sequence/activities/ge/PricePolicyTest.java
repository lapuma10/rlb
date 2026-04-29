package net.runelite.client.sequence.activities.ge;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class PricePolicyTest {

    @Test
    public void exactZeroRejected() {
        try {
            new PricePolicy.Exact(0);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) { /* ok */ }
    }

    @Test
    public void exactNegativeRejected() {
        try {
            new PricePolicy.Exact(-1);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) { /* ok */ }
    }

    @Test
    public void exactPositiveAccepted() {
        assertEquals(100, new PricePolicy.Exact(100).coinsEach());
        assertEquals(1_500_000, new PricePolicy.Exact(1_500_000).coinsEach());
    }

    @Test
    public void exactCanBeCastFromSealedInterface() {
        PricePolicy p = new PricePolicy.Exact(42);
        assertEquals(42, ((PricePolicy.Exact) p).coinsEach());
    }
}
