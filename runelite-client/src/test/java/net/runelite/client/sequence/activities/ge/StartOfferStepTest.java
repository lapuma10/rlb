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

    /** Regression for the 2026-04-30 17:47 / 17:48 GE_SET_QUANTITY chatbox-
     *  prompt timeout. The slot click's CLICK_BOUNDS chain holds the
     *  dispatcher worker for ~0.5–3s (cursor humanization + post-click
     *  parking); meanwhile the engine ticks fast enough that
     *  {@code offerSetupOpen} flips true while the worker is still
     *  finishing. If StartOfferStep declares Succeeded purely on
     *  {@code offerSetupOpen}, the next step (SelectItemStep) onStarts
     *  with the worker still busy → its PICK_GE_SEARCH_RESULT dispatch
     *  hits the busy guard, gets logged "dispatcher busy, dropping ...",
     *  and the search never runs. Fix: gate Succeeded on
     *  {@code !dispatcher.isBusy()} so the next step always onStarts
     *  against an idle worker. */
    @Test
    public void waitsForDispatcherIdleBeforeSucceeding() {
        WorldSnapshot before = new GeSnapBuilder().tick(0).geOpen(true).build();
        WorldSnapshot setup1 = new GeSnapBuilder().tick(1).geOpen(true).geSetupOpen(true).build();
        WorldSnapshot setup2 = new GeSnapBuilder().tick(2).geOpen(true).geSetupOpen(true).build();
        WorldSnapshot setup3 = new GeSnapBuilder().tick(3).geOpen(true).geSetupOpen(true).build();
        RecordingGeActions a = new RecordingGeActions();
        GeEngineHarness h = new GeEngineHarness().queue(before, setup1, setup2, setup3);
        h.run(new StartOfferStep(OfferSide.BUY, a));
        // Simulate the slot-click chain holding the worker busy across the
        // SETUP-open transition. With the bug present, Succeeded fires here.
        h.dispatcher().setBusy(true);
        h.advance(2);
        assertEquals("dispatch should still be the only call",
            1, a.callCount());
        assertEquals("clickOfferSlotButton(slot=0, side=BUY)", a.calls().get(0));
        assertEquals("step must stay RUNNING while worker is busy",
            SequenceState.RUNNING, h.state());
        // Worker finishes — Succeeded should fire on the next check.
        h.dispatcher().setBusy(false);
        h.advance(2);
        assertEquals(SequenceState.IDLE, h.state());
    }
}
