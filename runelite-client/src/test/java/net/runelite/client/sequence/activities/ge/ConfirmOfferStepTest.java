package net.runelite.client.sequence.activities.ge;

import net.runelite.client.sequence.SequenceState;
import net.runelite.client.sequence.WorldSnapshot;
import net.runelite.client.sequence.blackboard.BlackboardScope;
import net.runelite.client.sequence.composite.LinearSequence;
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
    public void itemMismatchAbortsWhenWrongItemSurfacesInTentativeSlot() {
        // Spec scenario 11: an offer surfaces in our slot for a DIFFERENT
        // item than we requested. ConfirmOfferStep should detect this from
        // K_GE_TENTATIVE_SLOT (written by StartOfferStep) and abort with
        // GeOfferItemMismatch — NOT time out as GeOfferRejected.
        WorldSnapshot before = new GeSnapBuilder().tick(0).geOpen(true).build();
        // Slot 2 holds an offer for itemId=999 (we asked for 4151).
        WorldSnapshot wrongItem = new GeSnapBuilder()
            .tick(1).geOpen(true)
            .offer(2, OfferSide.BUY, OfferStatus.ACTIVE, 999, 5, 0, 1_500_000)
            .build();
        // Pre-populate K_GE_TENTATIVE_SLOT (StartOfferStep would set this in
        // the real LinearSequence). Use a TinyStartOfferStep stub that just
        // writes the key, then runs the real ConfirmOfferStep.
        GeEngineHarness h = new GeEngineHarness().queue(before, wrongItem);
        h.run(new LinearSequence("WrongItemHarness")
            .then(new TentativeSlotWriterStep(2))
            .then(new ConfirmOfferStep(4151, OfferSide.BUY, 5, 1_500_000, new RecordingGeActions())));
        h.advance(2);

        assertEquals(SequenceState.FAILED, h.state());
        boolean foundReason = false;
        for (TelemetryRecord r : h.recentTelemetry()) {
            if (r.payload() != null && r.payload().contains("GeOfferItemMismatch")) {
                foundReason = true;
                break;
            }
        }
        assertTrue("expected GeOfferItemMismatch (got telemetry: " + h.recentTelemetry() + ")",
            foundReason);
    }

    /** Minimal helper step that writes K_GE_TENTATIVE_SLOT and succeeds. Used
     *  to simulate StartOfferStep's blackboard write in isolated
     *  ConfirmOfferStep tests. */
    private static final class TentativeSlotWriterStep
        implements net.runelite.client.sequence.Step {

        private final int slot;
        TentativeSlotWriterStep(int slot) { this.slot = slot; }

        public String name()                 { return "TentativeSlotWriter(" + slot + ")"; }
        public int priority()                { return 100; }
        public int timeoutTicks()            { return 4; }
        public net.runelite.client.sequence.PreemptionPolicy preemptionPolicy() {
            return net.runelite.client.sequence.PreemptionPolicy.NEVER;
        }
        public boolean isSafeToPause(WorldSnapshot s,
            net.runelite.client.sequence.blackboard.Blackboard b) { return true; }
        public boolean canStart(WorldSnapshot s,
            net.runelite.client.sequence.blackboard.Blackboard b) { return true; }
        public void onStart(net.runelite.client.sequence.StepContext c) {
            c.bb().scope(BlackboardScope.SEQUENCE)
                .put(GeBlackboardKeys.K_GE_TENTATIVE_SLOT, slot);
        }
        public void onEvent(Object e, net.runelite.client.sequence.StepContext c) {}
        public void tick(net.runelite.client.sequence.StepContext c) {}
        public net.runelite.client.sequence.Completion check(WorldSnapshot s,
            net.runelite.client.sequence.blackboard.Blackboard b) {
            return new net.runelite.client.sequence.Completion.Succeeded("slot recorded");
        }
        public net.runelite.client.sequence.Recovery onFailure(
            net.runelite.client.sequence.Failure f, WorldSnapshot s,
            net.runelite.client.sequence.blackboard.Blackboard b) {
            return new net.runelite.client.sequence.Recovery.Abort("");
        }
    }

    @Test
    public void priceWarningRejectedByDefaultFailsWithPriceTooHigh() {
        // Default (acceptHighPriceWarning=false): popup arrives, step
        // dismisses with No, fails GeOfferPriceTooHigh.
        WorldSnapshot before = new GeSnapBuilder().tick(0).geOpen(true).build();
        WorldSnapshot withPopup = new GeSnapBuilder()
            .tick(1).geOpen(true).priceWarningOpen(true).build();
        // Subsequent snapshots: popup gone, no offer surfaced — confirms
        // the step short-circuits via the K_WARNING_DISMISSED gate.
        java.util.List<WorldSnapshot> snaps = new java.util.ArrayList<>();
        snaps.add(before);
        snaps.add(withPopup);
        for (int t = 2; t < 6; t++) {
            snaps.add(new GeSnapBuilder().tick(t).geOpen(true).build());
        }
        RecordingGeActions a = new RecordingGeActions();
        GeEngineHarness h = new GeEngineHarness().queue(snaps);
        h.run(new ConfirmOfferStep(4151, OfferSide.BUY, 5, 1_500_000, a));
        h.advance(6);

        assertEquals(SequenceState.FAILED, h.state());
        assertTrue("dispatched dismiss-No",
            a.calls().contains("dismissPriceWarning(No)"));
        boolean foundReason = false;
        for (TelemetryRecord r : h.recentTelemetry()) {
            if (r.payload() != null && r.payload().contains("GeOfferPriceTooHigh")) {
                foundReason = true; break;
            }
        }
        assertTrue("expected GeOfferPriceTooHigh", foundReason);
    }

    /** Regression: `tick()` must NOT dispatch `dismissPriceWarning` while
     *  the prior `confirmOffer` click chain is still busy. Without the
     *  gate, the dismiss is silently dropped by the busy guard,
     *  K_WARNING_DISMISSED gets set anyway, and check() falsely surfaces
     *  GeOfferPriceTooHigh on the next tick — the popup hangs. */
    @Test
    public void priceWarningWaitsForDispatcherIdleBeforeDismissing() {
        WorldSnapshot before = new GeSnapBuilder().tick(0).geOpen(true).build();
        WorldSnapshot withPopup = new GeSnapBuilder()
            .tick(1).geOpen(true).priceWarningOpen(true).build();
        // Popup persists for a few ticks while busy, then clears once
        // busy releases and the No click lands.
        java.util.List<WorldSnapshot> snaps = new java.util.ArrayList<>();
        snaps.add(before);
        for (int t = 1; t < 6; t++) {
            snaps.add(new GeSnapBuilder().tick(t).geOpen(true).priceWarningOpen(true).build());
        }
        // After busy clears + dismiss lands: popup gone, no offer.
        for (int t = 6; t < 12; t++) {
            snaps.add(new GeSnapBuilder().tick(t).geOpen(true).build());
        }
        RecordingGeActions a = new RecordingGeActions();
        GeEngineHarness h = new GeEngineHarness().queue(snaps);
        h.run(new ConfirmOfferStep(4151, OfferSide.BUY, 5, 1_500_000, a));
        // Busy=true across the popup-arrival ticks. tick() must NOT dispatch.
        h.dispatcher().setBusy(true);
        h.advance(4);
        assertTrue("dismiss held while worker busy: " + a.calls(),
            !a.calls().contains("dismissPriceWarning(No)"));
        // Worker idle — tick fires the dismiss; check fails PriceTooHigh.
        h.dispatcher().setBusy(false);
        h.advance(8);
        assertTrue("dismiss dispatched after busy cleared: " + a.calls(),
            a.calls().contains("dismissPriceWarning(No)"));
        assertEquals(SequenceState.FAILED, h.state());
    }

    @Test
    public void priceWarningAcceptedContinuesToOffer() {
        // acceptHighPriceWarning=true: popup arrives, click Yes, then offer
        // surfaces and step succeeds.
        WorldSnapshot before = new GeSnapBuilder().tick(0).geOpen(true).build();
        WorldSnapshot withPopup = new GeSnapBuilder()
            .tick(1).geOpen(true).priceWarningOpen(true).build();
        WorldSnapshot popupGone = new GeSnapBuilder().tick(2).geOpen(true).build();
        WorldSnapshot offerLanded = new GeSnapBuilder().tick(3).geOpen(true)
            .offer(2, OfferSide.BUY, OfferStatus.ACTIVE, 4151, 5, 0, 1_500_000)
            .build();
        RecordingGeActions a = new RecordingGeActions();
        GeEngineHarness h = new GeEngineHarness()
            .queue(before, withPopup, popupGone, offerLanded);
        h.run(new ConfirmOfferStep(4151, OfferSide.BUY, 5, 1_500_000, a, true));
        h.advance(4);

        assertEquals(SequenceState.IDLE, h.state());
        assertTrue("dispatched dismiss-Yes",
            a.calls().contains("dismissPriceWarning(Yes)"));
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
