package net.runelite.client.sequence.activities.ge;

import net.runelite.client.sequence.SequenceState;
import net.runelite.client.sequence.WorldSnapshot;
import net.runelite.client.sequence.telemetry.TelemetryRecord;
import net.runelite.client.sequence.views.OfferSide;
import net.runelite.client.sequence.views.OfferStatus;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class EnsureNoConflictingOfferStepTest {

    @Test
    public void noOffersSucceeds() {
        WorldSnapshot s = new GeSnapBuilder().tick(0).geOpen(true).build();
        GeEngineHarness h = new GeEngineHarness().queue(s, s);
        h.run(new EnsureNoConflictingOfferStep(4151, OfferSide.BUY));
        h.advance(2);
        assertEquals(SequenceState.IDLE, h.state());
    }

    @Test
    public void existingActiveBuyConflicts() {
        WorldSnapshot s = new GeSnapBuilder()
            .tick(0).geOpen(true)
            .offer(0, OfferSide.BUY, OfferStatus.ACTIVE, 4151, 100, 25, 1000)
            .build();
        GeEngineHarness h = new GeEngineHarness().queue(s, s);
        h.run(new EnsureNoConflictingOfferStep(4151, OfferSide.BUY));
        h.advance(2);
        assertEquals(SequenceState.FAILED, h.state());
        boolean foundReason = false;
        for (TelemetryRecord r : h.recentTelemetry()) {
            if (r.payload() != null && r.payload().contains("GeExistingOfferConflict")) {
                foundReason = true;
                break;
            }
        }
        assertTrue("expected GeExistingOfferConflict telemetry", foundReason);
    }

    @Test
    public void completedBuyAlsoConflicts() {
        // A COMPLETE but uncollected offer for the same item is still a conflict
        // — user must collect it before the bot creates a new one.
        WorldSnapshot s = new GeSnapBuilder()
            .tick(0).geOpen(true)
            .offer(2, OfferSide.BUY, OfferStatus.COMPLETE, 4151, 100, 100, 1000)
            .build();
        GeEngineHarness h = new GeEngineHarness().queue(s, s);
        h.run(new EnsureNoConflictingOfferStep(4151, OfferSide.BUY));
        h.advance(2);
        assertEquals(SequenceState.FAILED, h.state());
    }

    @Test
    public void existingSellForSameItemDoesNotConflictWithBuy() {
        // Different sides → independent. A sell for whip doesn't block a buy
        // for whip (rare combination but valid).
        WorldSnapshot s = new GeSnapBuilder()
            .tick(0).geOpen(true)
            .offer(0, OfferSide.SELL, OfferStatus.ACTIVE, 4151, 100, 0, 1000)
            .build();
        GeEngineHarness h = new GeEngineHarness().queue(s, s);
        h.run(new EnsureNoConflictingOfferStep(4151, OfferSide.BUY));
        h.advance(2);
        assertEquals(SequenceState.IDLE, h.state());
    }
}
