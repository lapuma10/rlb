package net.runelite.client.sequence.activities.ge;

import net.runelite.client.sequence.SequenceState;
import net.runelite.client.sequence.WorldSnapshot;
import net.runelite.client.sequence.telemetry.TelemetryRecord;
import net.runelite.client.sequence.views.OfferSide;
import net.runelite.client.sequence.views.OfferStatus;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ConfirmOfferStepTest {

    @Test
    public void happyPathWritesSlotKey() {
        WorldSnapshot before = new GeSnapBuilder().tick(0).geOpen(true).build();
        WorldSnapshot after = new GeSnapBuilder()
            .tick(1).geOpen(true)
            .offer(2, OfferSide.BUY, OfferStatus.ACTIVE, 4151, 5, 0, 1_500_000)
            .build();
        RecordingGeActions a = new RecordingGeActions();
        GeEngineHarness h = new GeEngineHarness().queue(before, after);
        h.run(new ConfirmOfferStep(4151, OfferSide.BUY, 5, 1_500_000, a));
        h.advance(2);

        assertEquals(SequenceState.IDLE, h.state());
        assertEquals(1, a.callCount());
        assertEquals("confirmOffer()", a.calls().get(0));
    }

    @Test
    public void quantityMismatchAborts() {
        WorldSnapshot before = new GeSnapBuilder().tick(0).geOpen(true).build();
        WorldSnapshot wrong = new GeSnapBuilder()
            .tick(1).geOpen(true)
            // requested 5 but offer has 1 — mismatch.
            .offer(2, OfferSide.BUY, OfferStatus.ACTIVE, 4151, 1, 0, 1_500_000)
            .build();
        GeEngineHarness h = new GeEngineHarness().queue(before, wrong);
        h.run(new ConfirmOfferStep(4151, OfferSide.BUY, 5, 1_500_000, new RecordingGeActions()));
        h.advance(2);
        assertEquals(SequenceState.FAILED, h.state());
        boolean foundReason = false;
        for (TelemetryRecord r : h.recentTelemetry()) {
            if (r.payload() != null && r.payload().contains("GeOfferQuantityMismatch")) {
                foundReason = true;
                break;
            }
        }
        assertTrue("expected GeOfferQuantityMismatch", foundReason);
    }

    @Test
    public void priceMismatchAborts() {
        WorldSnapshot before = new GeSnapBuilder().tick(0).geOpen(true).build();
        WorldSnapshot wrong = new GeSnapBuilder()
            .tick(1).geOpen(true)
            .offer(2, OfferSide.BUY, OfferStatus.ACTIVE, 4151, 5, 0, 999_999)
            .build();
        GeEngineHarness h = new GeEngineHarness().queue(before, wrong);
        h.run(new ConfirmOfferStep(4151, OfferSide.BUY, 5, 1_500_000, new RecordingGeActions()));
        h.advance(2);
        assertEquals(SequenceState.FAILED, h.state());
        boolean foundReason = false;
        for (TelemetryRecord r : h.recentTelemetry()) {
            if (r.payload() != null && r.payload().contains("GeOfferPriceMismatch")) {
                foundReason = true;
                break;
            }
        }
        assertTrue("expected GeOfferPriceMismatch", foundReason);
    }

    @Test
    public void notSurfacedAfterTimeoutFailsWithRejected() {
        // Offer never surfaces — confirm dispatched but ge.offers stays empty.
        WorldSnapshot s = new GeSnapBuilder().tick(0).geOpen(true).build();
        // Need enough snapshots for timeoutTicks=8 to elapse + a few retries.
        java.util.List<WorldSnapshot> snaps = new java.util.ArrayList<>();
        for (int i = 0; i < 80; i++) {
            snaps.add(new GeSnapBuilder().tick(i).geOpen(true).build());
        }
        GeEngineHarness h = new GeEngineHarness().queue(snaps);
        h.run(new ConfirmOfferStep(4151, OfferSide.BUY, 5, 1_500_000, new RecordingGeActions()));
        h.advance(80);
        assertEquals(SequenceState.FAILED, h.state());
        boolean foundReason = false;
        for (TelemetryRecord r : h.recentTelemetry()) {
            if (r.payload() != null && r.payload().contains("GeOfferRejected")) {
                foundReason = true;
                break;
            }
        }
        assertTrue("expected GeOfferRejected after timeout: " + h.recentTelemetry(),
            foundReason);
    }
}
