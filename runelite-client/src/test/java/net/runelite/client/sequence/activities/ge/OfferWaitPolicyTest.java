package net.runelite.client.sequence.activities.ge;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class OfferWaitPolicyTest {

    @Test
    public void untilFactoryProducesExpectedFields() {
        OfferWaitPolicy p = OfferWaitPolicy.until(300);
        assertEquals(300, p.timeoutTicks());
        assertFalse("acceptPartialOnTimeout default for until() is false",
            p.acceptPartialOnTimeout());
    }

    @Test
    public void untilOrPartialFactoryProducesExpectedFields() {
        OfferWaitPolicy p = OfferWaitPolicy.untilOrPartial(300);
        assertEquals(300, p.timeoutTicks());
        assertTrue("untilOrPartial accepts partial fills at timeout",
            p.acceptPartialOnTimeout());
    }

    @Test
    public void noWaitFactoryProducesZeroTimeout() {
        OfferWaitPolicy p = OfferWaitPolicy.noWait();
        assertEquals(0, p.timeoutTicks());
        assertFalse(p.acceptPartialOnTimeout());
    }

    @Test
    public void negativeTimeoutRejected() {
        try {
            new OfferWaitPolicy(-1, false);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) { /* ok */ }
    }
}
