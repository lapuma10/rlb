package net.runelite.client.sequence.activities.ge;

import java.util.ArrayList;
import java.util.List;
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

/**
 * Tests for {@link WaitForOfferStep} covering spec scenarios 13, 14, 15, 16
 * (partial-accepted / timeout-no-partial for buy and sell sides).
 */
public class WaitForOfferStepTest {

    /** Helper: build a snapshot with one offer in slot {@code slot}. */
    private static WorldSnapshot snap(int tick, int slot, OfferSide side,
                                      OfferStatus status, int itemId,
                                      int requestedQty, int completedQty,
                                      int priceEach) {
        return new GeSnapBuilder().tick(tick).geOpen(true)
            .offer(slot, side, status, itemId, requestedQty, completedQty, priceEach)
            .build();
    }

    @Test
    public void completeOfferSucceeds() {
        // The slot is already COMPLETE — first check returns Succeeded.
        List<WorldSnapshot> snaps = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            snaps.add(snap(t, 0, OfferSide.BUY, OfferStatus.COMPLETE, 4151, 5, 5, 1_500_000));
        }
        GeEngineHarness h = new GeEngineHarness().queue(snaps);
        h.run(new LinearSequence("waitWrap")
            .then(new SlotWriter(0))
            .then(new WaitForOfferStep(OfferWaitPolicy.until(50))));
        h.advance(4);
        assertEquals(SequenceState.IDLE, h.state());
    }

    @Test
    public void partialAcceptedOnTimeoutSucceeds() {
        // Spec 13: BUY partial + acceptPartialOnTimeout=true.
        // Wait policy: until-or-partial(2). After 2 ticks elapsed, partial fill
        // counts as success.
        List<WorldSnapshot> snaps = new ArrayList<>();
        for (int t = 0; t < 6; t++) {
            // 2 of 5 filled at every snapshot
            snaps.add(snap(t, 0, OfferSide.BUY, OfferStatus.PARTIALLY_COMPLETE, 4151, 5, 2, 1_500_000));
        }
        GeEngineHarness h = new GeEngineHarness().queue(snaps);
        h.run(new LinearSequence("waitWrap")
            .then(new SlotWriter(0))
            .then(new WaitForOfferStep(OfferWaitPolicy.untilOrPartial(2))));
        h.advance(6);
        assertEquals("partial fill at timeout = success when acceptPartialOnTimeout=true",
            SequenceState.IDLE, h.state());
    }

    @Test
    public void partialNotAcceptedOnTimeoutFailsWithIncomplete() {
        // Spec 14 (partial variant): BUY partial + acceptPartialOnTimeout=false.
        List<WorldSnapshot> snaps = new ArrayList<>();
        for (int t = 0; t < 6; t++) {
            snaps.add(snap(t, 0, OfferSide.BUY, OfferStatus.PARTIALLY_COMPLETE, 4151, 5, 2, 1_500_000));
        }
        GeEngineHarness h = new GeEngineHarness().queue(snaps);
        h.run(new LinearSequence("waitWrap")
            .then(new SlotWriter(0))
            .then(new WaitForOfferStep(OfferWaitPolicy.until(2))));
        h.advance(6);
        assertEquals(SequenceState.FAILED, h.state());
        boolean found = false;
        for (TelemetryRecord r : h.recentTelemetry()) {
            if (r.payload() != null && r.payload().contains("GeOfferIncomplete")) {
                found = true; break;
            }
        }
        assertTrue("expected GeOfferIncomplete (telemetry: " + h.recentTelemetry() + ")", found);
    }

    @Test
    public void zeroProgressTimeoutFailsWithGeOfferTimeout() {
        // Spec 14: no progress + !acceptPartial + timeout → GeOfferTimeout.
        // No cancel-offer dispatch should occur (the spec forbids auto-abort).
        List<WorldSnapshot> snaps = new ArrayList<>();
        for (int t = 0; t < 6; t++) {
            // ACTIVE offer with completedQuantity=0 throughout.
            snaps.add(snap(t, 0, OfferSide.BUY, OfferStatus.ACTIVE, 4151, 5, 0, 1_500_000));
        }
        GeEngineHarness h = new GeEngineHarness().queue(snaps);
        h.run(new LinearSequence("waitWrap")
            .then(new SlotWriter(0))
            .then(new WaitForOfferStep(OfferWaitPolicy.until(2))));
        h.advance(6);
        assertEquals(SequenceState.FAILED, h.state());
        boolean found = false;
        for (TelemetryRecord r : h.recentTelemetry()) {
            if (r.payload() != null && r.payload().contains("GeOfferTimeout")) {
                found = true; break;
            }
        }
        assertTrue("expected GeOfferTimeout (telemetry: " + h.recentTelemetry() + ")", found);
        // No GeActions calls should have happened — the wait step doesn't
        // dispatch anything; harness's MockInputDispatcher should be untouched
        // by this step.
    }

    @Test
    public void sellPartialAcceptedOnTimeoutSucceeds() {
        // Spec 15: SELL partial + acceptPartialOnTimeout=true.
        List<WorldSnapshot> snaps = new ArrayList<>();
        for (int t = 0; t < 6; t++) {
            snaps.add(snap(t, 1, OfferSide.SELL, OfferStatus.PARTIALLY_COMPLETE, 4151, 5, 3, 1_500_000));
        }
        GeEngineHarness h = new GeEngineHarness().queue(snaps);
        h.run(new LinearSequence("waitWrap")
            .then(new SlotWriter(1))
            .then(new WaitForOfferStep(OfferWaitPolicy.untilOrPartial(2))));
        h.advance(6);
        assertEquals(SequenceState.IDLE, h.state());
    }

    @Test
    public void sellZeroProgressTimeoutFailsWithGeOfferTimeout() {
        // Spec 16: SELL no progress + !acceptPartial + timeout.
        List<WorldSnapshot> snaps = new ArrayList<>();
        for (int t = 0; t < 6; t++) {
            snaps.add(snap(t, 1, OfferSide.SELL, OfferStatus.ACTIVE, 4151, 5, 0, 1_500_000));
        }
        GeEngineHarness h = new GeEngineHarness().queue(snaps);
        h.run(new LinearSequence("waitWrap")
            .then(new SlotWriter(1))
            .then(new WaitForOfferStep(OfferWaitPolicy.until(2))));
        h.advance(6);
        assertEquals(SequenceState.FAILED, h.state());
        boolean found = false;
        for (TelemetryRecord r : h.recentTelemetry()) {
            if (r.payload() != null && r.payload().contains("GeOfferTimeout")) {
                found = true; break;
            }
        }
        assertTrue("expected GeOfferTimeout (telemetry: " + h.recentTelemetry() + ")", found);
    }

    /** Pre-step that writes K_GE_OFFER_SLOT to SEQUENCE scope so
     *  WaitForOfferStep finds the slot index. Mirrors what ConfirmOfferStep
     *  would do in the production LinearSequence. */
    private static final class SlotWriter implements net.runelite.client.sequence.Step {
        private final int slot;
        SlotWriter(int slot) { this.slot = slot; }
        public String name()                 { return "SlotWriter(" + slot + ")"; }
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
                .put(GeBlackboardKeys.K_GE_OFFER_SLOT, slot);
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
}
