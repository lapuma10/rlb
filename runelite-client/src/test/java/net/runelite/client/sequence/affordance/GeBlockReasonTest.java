package net.runelite.client.sequence.affordance;

import net.runelite.api.coords.WorldArea;
import net.runelite.client.sequence.views.OfferSide;
import net.runelite.client.sequence.views.OfferStatus;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class GeBlockReasonTest {

    @Test
    public void allRecordsImplementDiagnosticReason() {
        WorldArea area = new WorldArea(3160, 3480, 5, 5, 0);
        DiagnosticReason[] reasons = new DiagnosticReason[] {
            new GeBlockReason.NotAtGrandExchange(area),
            new GeBlockReason.GeNotOpen(),
            new GeBlockReason.GeOfferSetupNotOpen(),
            new GeBlockReason.GeCollectNotOpen(),
            new GeBlockReason.GeSlotsFull(),
            new GeBlockReason.GeExistingOfferConflict(0, OfferSide.BUY, 4151, OfferStatus.ACTIVE),
            new GeBlockReason.GeOfferRejected("nope"),
            new GeBlockReason.GeOfferTimeout(2, 30),
            new GeBlockReason.GeOfferIncomplete(2, 1, 5),
            new GeBlockReason.GeOfferQuantityMismatch(2, 5, 3),
            new GeBlockReason.GeOfferItemMismatch(2, 4151, 1234),
            new GeBlockReason.GeOfferPriceMismatch(2, 100, 200),
            new GeBlockReason.GeCollectFailed(2, 4151, 0),
            new GeBlockReason.InsufficientCoins(1000, 500),
            new GeBlockReason.InsufficientSellItems(4151, 5, 3),
        };
        for (DiagnosticReason r : reasons) {
            assertTrue(r instanceof GeBlockReason);
        }
    }

    @Test
    public void existingOfferConflictAccessors() {
        GeBlockReason.GeExistingOfferConflict c =
            new GeBlockReason.GeExistingOfferConflict(3, OfferSide.SELL, 1234, OfferStatus.PARTIALLY_COMPLETE);
        assertEquals(3, c.slot());
        assertEquals(OfferSide.SELL, c.side());
        assertEquals(1234, c.itemId());
        assertEquals(OfferStatus.PARTIALLY_COMPLETE, c.status());
    }
}
