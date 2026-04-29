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
 * Tests for {@link CollectOfferStep} covering buy / sell collect happy paths
 * and spec scenario 17 (slot empties but inventory delta missing).
 */
public class CollectOfferStepTest {

    private static final int COINS = 995;

    @Test
    public void buyCollectSucceedsOnSlotEmptyPlusItemDelta() {
        // Snapshots:
        // t0: slot 0 has COMPLETE buy of itemId=4151, inventory has 0 of 4151
        // t1: dispatch happens — same as t0 from the engine's view
        // t2..t4: slot empties + inventory has 1 of 4151
        WorldSnapshot pre = new GeSnapBuilder().tick(0).geOpen(true)
            .offer(0, OfferSide.BUY, OfferStatus.COMPLETE, 4151, 1, 1, 1_500_000)
            .build();
        WorldSnapshot post = new GeSnapBuilder().tick(1).geOpen(true)
            .invItem(4151, 1)
            .build();   // slot 0 is implicitly EMPTY in default builder
        List<WorldSnapshot> snaps = new ArrayList<>();
        snaps.add(pre);
        for (int t = 1; t < 8; t++) {
            snaps.add(new GeSnapBuilder().tick(t).geOpen(true).invItem(4151, 1).build());
        }
        RecordingGeActions a = new RecordingGeActions();
        GeEngineHarness h = new GeEngineHarness().queue(snaps);
        h.run(new LinearSequence("collectWrap")
            .then(new SlotWriter(0))
            .then(new CollectOfferStep(a)));
        h.advance(8);

        assertEquals(SequenceState.IDLE, h.state());
        assertTrue("collect dispatched", a.calls().contains("collect(slot=0)"));
    }

    @Test
    public void sellCollectSucceedsOnCoinsDelta() {
        // SELL collect: expected delta is COINS, not the sold itemId.
        WorldSnapshot pre = new GeSnapBuilder().tick(0).geOpen(true)
            .offer(1, OfferSide.SELL, OfferStatus.COMPLETE, 4151, 1, 1, 1_500_000)
            .invCoins(0)
            .build();
        List<WorldSnapshot> snaps = new ArrayList<>();
        snaps.add(pre);
        for (int t = 1; t < 8; t++) {
            // Slot 1 EMPTY; coins are now 1.5M (the proceeds).
            snaps.add(new GeSnapBuilder().tick(t).geOpen(true).invCoins(1_500_000).build());
        }
        RecordingGeActions a = new RecordingGeActions();
        GeEngineHarness h = new GeEngineHarness().queue(snaps);
        h.run(new LinearSequence("collectWrap")
            .then(new SlotWriter(1))
            .then(new CollectOfferStep(a)));
        h.advance(8);

        assertEquals(SequenceState.IDLE, h.state());
        assertTrue("collect dispatched", a.calls().contains("collect(slot=1)"));
    }

    @Test
    public void slotEmptiesButNoDeltaFailsWithCollectFailed() {
        // Spec 17: collect dispatched, slot transitions to EMPTY, but the
        // inventory item count never rises. Failed(GeCollectFailed).
        // Recovery is Retry(2): so we need enough ticks for first attempt (8t)
        // + 2 retries (8t each) + abort = ~28 ticks.
        WorldSnapshot pre = new GeSnapBuilder().tick(0).geOpen(true)
            .offer(2, OfferSide.BUY, OfferStatus.COMPLETE, 4151, 5, 5, 1_500_000)
            .invItem(4151, 0)   // start at 0
            .build();
        List<WorldSnapshot> snaps = new ArrayList<>();
        snaps.add(pre);
        // Slot 2 is now EMPTY but inv stays at 0 — delta never observed.
        for (int t = 1; t < 40; t++) {
            snaps.add(new GeSnapBuilder().tick(t).geOpen(true).invItem(4151, 0).build());
        }
        RecordingGeActions a = new RecordingGeActions();
        GeEngineHarness h = new GeEngineHarness().queue(snaps);
        h.run(new LinearSequence("collectWrap")
            .then(new SlotWriter(2))
            .then(new CollectOfferStep(a)));
        h.advance(40);

        assertEquals(SequenceState.FAILED, h.state());
        boolean found = false;
        for (TelemetryRecord r : h.recentTelemetry()) {
            if (r.payload() != null && r.payload().contains("GeCollectFailed")) {
                found = true; break;
            }
        }
        assertTrue("expected GeCollectFailed (telemetry: " + h.recentTelemetry() + ")", found);
    }

    @Test
    public void geNotOpenOnStartFailsWithGeNotOpen() {
        WorldSnapshot s = new GeSnapBuilder().tick(0).geOpen(false).build();
        List<WorldSnapshot> snaps = new ArrayList<>();
        for (int t = 0; t < 4; t++) snaps.add(s);
        RecordingGeActions a = new RecordingGeActions();
        GeEngineHarness h = new GeEngineHarness().queue(snaps);
        h.run(new LinearSequence("collectWrap")
            .then(new SlotWriter(0))
            .then(new CollectOfferStep(a)));
        h.advance(4);
        assertEquals(SequenceState.FAILED, h.state());
        assertEquals("no collect dispatched when GE not open", 0, a.callCount());
    }

    /** Pre-step that writes K_GE_OFFER_SLOT to SEQUENCE scope. */
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
