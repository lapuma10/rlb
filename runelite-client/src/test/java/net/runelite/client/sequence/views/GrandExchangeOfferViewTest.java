package net.runelite.client.sequence.views;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Predicate truth-table for each {@link OfferStatus}. */
public class GrandExchangeOfferViewTest {

    @Test
    public void emptyStatusPredicates() {
        GrandExchangeOfferView v = GrandExchangeOfferView.empty(0);
        assertTrue(v.isEmpty());
        assertFalse(v.isActive());
        assertFalse(v.isComplete());
        assertFalse(v.isCancelled());
        assertEquals(OfferSide.NONE, v.side());
        assertEquals(OfferStatus.EMPTY, v.status());
    }

    @Test
    public void activeStatusPredicates() {
        GrandExchangeOfferView v = new GrandExchangeOfferView(
            0, OfferSide.BUY, OfferStatus.ACTIVE, 4151, 100, 0, 1000, 0);
        assertFalse(v.isEmpty());
        assertTrue(v.isActive());
        assertFalse(v.isComplete());
        assertFalse(v.isCancelled());
    }

    @Test
    public void partiallyCompleteIsAlsoActive() {
        GrandExchangeOfferView v = new GrandExchangeOfferView(
            1, OfferSide.SELL, OfferStatus.PARTIALLY_COMPLETE, 4151, 100, 50, 1000, 50_000);
        assertTrue("partial fills count as active for waiting purposes", v.isActive());
        assertFalse(v.isComplete());
        assertFalse(v.isEmpty());
        assertFalse(v.isCancelled());
    }

    @Test
    public void completeStatusPredicates() {
        GrandExchangeOfferView v = new GrandExchangeOfferView(
            2, OfferSide.BUY, OfferStatus.COMPLETE, 4151, 100, 100, 1000, 100_000);
        assertTrue(v.isComplete());
        assertFalse(v.isActive());
        assertFalse(v.isEmpty());
        assertFalse(v.isCancelled());
    }

    @Test
    public void cancelledStatusPredicates() {
        GrandExchangeOfferView v = new GrandExchangeOfferView(
            3, OfferSide.BUY, OfferStatus.CANCELLED, 4151, 100, 25, 1000, 25_000);
        assertTrue(v.isCancelled());
        assertFalse(v.isComplete());
        assertFalse(v.isActive());
        assertFalse(v.isEmpty());
    }
}
