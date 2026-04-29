package net.runelite.client.sequence.activities.ge;

import net.runelite.client.sequence.SequenceState;
import net.runelite.client.sequence.WorldSnapshot;
import net.runelite.client.sequence.telemetry.TelemetryRecord;
import net.runelite.client.sequence.views.OfferSide;
import net.runelite.client.sequence.views.OfferStatus;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class StartOfferStepTest {

    @Test
    public void geNotOpenFatal() {
        WorldSnapshot s = new GeSnapBuilder().tick(0).geOpen(false).build();
        RecordingGeActions a = new RecordingGeActions();
        GeEngineHarness h = new GeEngineHarness().queue(s, s);
        h.run(new StartOfferStep(OfferSide.BUY, a));
        h.advance(2);
        assertEquals(SequenceState.FAILED, h.state());
        assertEquals("GE not open → no dispatch", 0, a.callCount());
    }

    @Test
    public void slotsFullFatal() {
        // All 8 slots occupied with arbitrary offers.
        GeSnapBuilder b = new GeSnapBuilder().tick(0).geOpen(true);
        for (int i = 0; i < 8; i++) {
            b.offer(i, OfferSide.BUY, OfferStatus.ACTIVE, 100 + i, 1, 0, 1);
        }
        WorldSnapshot s = b.build();
        RecordingGeActions a = new RecordingGeActions();
        GeEngineHarness h = new GeEngineHarness().queue(s, s);
        h.run(new StartOfferStep(OfferSide.BUY, a));
        h.advance(2);
        assertEquals(SequenceState.FAILED, h.state());
        assertEquals(0, a.callCount());

        boolean foundReason = false;
        for (TelemetryRecord r : h.recentTelemetry()) {
            if (r.payload() != null && r.payload().contains("GeSlotsFull")) {
                foundReason = true;
                break;
            }
        }
        assertTrue("expected GeSlotsFull telemetry", foundReason);
    }

    @Test
    public void happyPathDispatchesAndWaitsForSetup() {
        WorldSnapshot before = new GeSnapBuilder().tick(0).geOpen(true).build();
        WorldSnapshot setup = new GeSnapBuilder().tick(1).geOpen(true).geSetupOpen(true).build();
        RecordingGeActions a = new RecordingGeActions();
        GeEngineHarness h = new GeEngineHarness().queue(before, setup);
        h.run(new StartOfferStep(OfferSide.BUY, a));
        h.advance(2);
        assertEquals(SequenceState.IDLE, h.state());
        assertEquals(1, a.callCount());
        assertEquals("clickOfferSlotButton(slot=0, side=BUY)", a.calls().get(0));
    }
}
