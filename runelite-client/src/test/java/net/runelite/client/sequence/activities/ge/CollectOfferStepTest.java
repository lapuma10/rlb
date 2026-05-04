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
 *
 * <p>Strategies (COLLECT_ALL via {@code GeOffers.COLLECTALL} vs PER_SLOT via
 * {@code openOfferDetail+collect}) are pinned per-test via the deterministic
 * {@code BooleanSupplier} constructor so assertions are stable.
 */
public class CollectOfferStepTest {

    private static final int COINS = 995;
    /** Pin strategy: returns this value every call. */
    private static final java.util.function.BooleanSupplier ALWAYS_COLLECT_ALL = () -> true;
    private static final java.util.function.BooleanSupplier ALWAYS_PER_SLOT = () -> false;

    @Test
    public void buyCollectSucceedsOnSlotEmptyPlusItemDelta() {
        // Snapshots:
        // t0: slot 0 has COMPLETE buy of itemId=4151, inventory has 0 of 4151,
        //     collect view NOT yet open — onStart dispatches openOfferDetail.
        // t1: collect view now open + slot still COMPLETE — tick() dispatches
        //     the inventory-collect click after check() flips the phase.
        // t2..: slot empties + inventory has 1 of 4151 — check() succeeds.
        WorldSnapshot pre = new GeSnapBuilder().tick(0).geOpen(true)
            .offer(0, OfferSide.BUY, OfferStatus.COMPLETE, 4151, 1, 1, 1_500_000)
            .build();
        // t1: collect view opened by the openOfferDetail click; slot still
        // COMPLETE because the inventory-collect click hasn't landed yet.
        WorldSnapshot afterDetail = new GeSnapBuilder().tick(1).geOpen(true)
            .geCollectOpen(true)
            .offer(0, OfferSide.BUY, OfferStatus.COMPLETE, 4151, 1, 1, 1_500_000)
            .build();
        List<WorldSnapshot> snaps = new ArrayList<>();
        snaps.add(pre);
        snaps.add(afterDetail);
        for (int t = 2; t < 12; t++) {
            // Slot 0 EMPTY (default builder), inv has 1x4151 — collect landed.
            snaps.add(new GeSnapBuilder().tick(t).geOpen(true).geCollectOpen(true)
                .invItem(4151, 1).build());
        }
        RecordingGeActions a = new RecordingGeActions();
        GeEngineHarness h = new GeEngineHarness().queue(snaps);
        h.run(new LinearSequence("collectWrap")
            .then(new SlotWriter(0))
            .then(new CollectOfferStep(a, ALWAYS_PER_SLOT)));
        h.advance(12);

        assertEquals(SequenceState.IDLE, h.state());
        assertTrue("openOfferDetail dispatched", a.calls().contains("openOfferDetail(slot=0)"));
        assertTrue("collect dispatched", a.calls().contains("collect(slot=0)"));
    }

    /** Regression: PER_SLOT path's `tick()` dispatches `ge.collect(slot)`
     *  one tick after `openOfferDetail(slot)` from `onStart`. If the
     *  dispatcher worker is still parking the cursor from the first
     *  click (busy=true), the second dispatch is silently dropped by the
     *  busy guard and the bot waits 12 ticks for an inventory delta that
     *  never arrives. The fix: `check()` must keep the phase machine in
     *  OPEN_COLLECT while busy, mirroring CollectAllCompletedOffersStep.
     *
     *  <p>This test pins PER_SLOT, holds busy across the
     *  collectOpen-true tick, and asserts the second click is NOT
     *  dispatched until busy clears. */
    @Test
    public void perSlotWaitsForDispatcherIdleBeforeCollectingClick() {
        WorldSnapshot t0 = new GeSnapBuilder().tick(0).geOpen(true)
            .offer(0, OfferSide.BUY, OfferStatus.COMPLETE, 4151, 1, 1, 1_500_000)
            .build();
        WorldSnapshot t1 = new GeSnapBuilder().tick(1).geOpen(true).geCollectOpen(true)
            .offer(0, OfferSide.BUY, OfferStatus.COMPLETE, 4151, 1, 1, 1_500_000)
            .build();
        // Plenty of trailing snapshots so the step doesn't timeout while
        // we hold busy.
        java.util.List<WorldSnapshot> snaps = new java.util.ArrayList<>();
        snaps.add(t0); snaps.add(t1);
        for (int t = 2; t < 8; t++) {
            snaps.add(new GeSnapBuilder().tick(t).geOpen(true).geCollectOpen(true)
                .offer(0, OfferSide.BUY, OfferStatus.COMPLETE, 4151, 1, 1, 1_500_000)
                .build());
        }
        // After busy clears + collect lands: slot empty + inv has 1.
        for (int t = 8; t < 16; t++) {
            snaps.add(new GeSnapBuilder().tick(t).geOpen(true).geCollectOpen(true)
                .invItem(4151, 1).build());
        }
        RecordingGeActions a = new RecordingGeActions();
        GeEngineHarness h = new GeEngineHarness().queue(snaps);
        h.run(new LinearSequence("collectWrap")
            .then(new SlotWriter(0))
            .then(new CollectOfferStep(a, ALWAYS_PER_SLOT)));

        // Busy=true across the openOfferDetail dispatch and the
        // collectOpen-true tick where check() would advance the phase.
        h.dispatcher().setBusy(true);
        h.advance(4);
        // openOfferDetail must have fired, but collect MUST NOT have —
        // check() should hold OPEN_COLLECT while busy.
        assertTrue("openOfferDetail dispatched",
            a.calls().contains("openOfferDetail(slot=0)"));
        assertTrue("collect held while worker busy: " + a.calls(),
            !a.calls().contains("collect(slot=0)"));

        // Worker idle — phase advances, collect fires, step completes.
        h.dispatcher().setBusy(false);
        h.advance(12);
        assertEquals(SequenceState.IDLE, h.state());
        assertTrue("collect dispatched after busy cleared: " + a.calls(),
            a.calls().contains("collect(slot=0)"));
    }

    @Test
    public void buyCollectViaCollectAllSucceeds() {
        // COLLECT_ALL strategy: single ge.collectAll() click drains the slot.
        // No openOfferDetail / collect_inv dance needed.
        WorldSnapshot pre = new GeSnapBuilder().tick(0).geOpen(true)
            .offer(0, OfferSide.BUY, OfferStatus.COMPLETE, 4151, 1, 1, 1_500_000)
            .build();
        List<WorldSnapshot> snaps = new ArrayList<>();
        snaps.add(pre);
        for (int t = 1; t < 12; t++) {
            // Slot 0 EMPTY (default), inv has 1x4151 — drain landed.
            snaps.add(new GeSnapBuilder().tick(t).geOpen(true)
                .invItem(4151, 1).build());
        }
        RecordingGeActions a = new RecordingGeActions();
        GeEngineHarness h = new GeEngineHarness().queue(snaps);
        h.run(new LinearSequence("collectWrap")
            .then(new SlotWriter(0))
            .then(new CollectOfferStep(a, ALWAYS_COLLECT_ALL)));
        h.advance(12);

        assertEquals(SequenceState.IDLE, h.state());
        assertTrue("collectAll dispatched", a.calls().contains("collectAll()"));
        assertTrue("no openOfferDetail in COLLECT_ALL path",
            !a.calls().contains("openOfferDetail(slot=0)"));
        assertTrue("no collect(slot=N) in COLLECT_ALL path",
            !a.calls().contains("collect(slot=0)"));
    }

    @Test
    public void buyCollectViaCollectAllRetriesWhileSlotStaysComplete() {
        WorldSnapshot pre = new GeSnapBuilder().tick(0).geOpen(true)
            .offer(0, OfferSide.BUY, OfferStatus.COMPLETE, 4151, 1, 1, 1_500_000)
            .build();
        List<WorldSnapshot> snaps = new ArrayList<>();
        snaps.add(pre);
        // Missed first toolbar click: slot stays COMPLETE for a few ticks.
        for (int t = 1; t < 4; t++) {
            snaps.add(new GeSnapBuilder().tick(t).geOpen(true)
                .offer(0, OfferSide.BUY, OfferStatus.COMPLETE, 4151, 1, 1, 1_500_000)
                .build());
        }
        // Later retry lands: slot drains.
        for (int t = 4; t < 12; t++) {
            snaps.add(new GeSnapBuilder().tick(t).geOpen(true)
                .invItem(4151, 1).build());
        }
        RecordingGeActions a = new RecordingGeActions();
        GeEngineHarness h = new GeEngineHarness().queue(snaps);
        h.run(new LinearSequence("collectWrap")
            .then(new SlotWriter(0))
            .then(new CollectOfferStep(a, ALWAYS_COLLECT_ALL)));
        h.advance(12);

        assertEquals(SequenceState.IDLE, h.state());
        int count = 0;
        for (String call : a.calls()) {
            if (call.equals("collectAll()")) count++;
        }
        assertTrue("collectAll retried while slot stayed complete: " + a.calls(),
            count >= 2);
    }

    @Test
    public void sellCollectSucceedsOnCoinsDelta() {
        // SELL collect: expected delta is COINS, not the sold itemId.
        WorldSnapshot pre = new GeSnapBuilder().tick(0).geOpen(true)
            .offer(1, OfferSide.SELL, OfferStatus.COMPLETE, 4151, 1, 1, 1_500_000)
            .invCoins(0)
            .build();
        WorldSnapshot afterDetail = new GeSnapBuilder().tick(1).geOpen(true)
            .geCollectOpen(true)
            .offer(1, OfferSide.SELL, OfferStatus.COMPLETE, 4151, 1, 1, 1_500_000)
            .invCoins(0)
            .build();
        List<WorldSnapshot> snaps = new ArrayList<>();
        snaps.add(pre);
        snaps.add(afterDetail);
        for (int t = 2; t < 12; t++) {
            // Slot 1 EMPTY; coins are now 1.5M (the proceeds).
            snaps.add(new GeSnapBuilder().tick(t).geOpen(true).geCollectOpen(true)
                .invCoins(1_500_000).build());
        }
        RecordingGeActions a = new RecordingGeActions();
        GeEngineHarness h = new GeEngineHarness().queue(snaps);
        h.run(new LinearSequence("collectWrap")
            .then(new SlotWriter(1))
            .then(new CollectOfferStep(a, ALWAYS_PER_SLOT)));
        h.advance(12);

        assertEquals(SequenceState.IDLE, h.state());
        assertTrue("openOfferDetail dispatched", a.calls().contains("openOfferDetail(slot=1)"));
        assertTrue("collect dispatched", a.calls().contains("collect(slot=1)"));
    }

    @Test
    public void slotEmptiesWithoutUnnotedDeltaSucceeds() {
        // The bot now collects items as NOTES (left-click default action
        // "Collect-notes"), so `inventory.count(unnotedItemId)` will not
        // rise even on a fully successful collect. Slot draining is the
        // sole success signal — see CollectOfferStep.check().
        WorldSnapshot pre = new GeSnapBuilder().tick(0).geOpen(true)
            .offer(2, OfferSide.BUY, OfferStatus.COMPLETE, 4151, 5, 5, 1_500_000)
            .invItem(4151, 0)
            .build();
        List<WorldSnapshot> snaps = new ArrayList<>();
        snaps.add(pre);
        // Slot empties on tick 1, inventory unnoted-count stays at 0
        // (notes go to a different itemId). Step should succeed.
        for (int t = 1; t < 12; t++) {
            snaps.add(new GeSnapBuilder().tick(t).geOpen(true).geCollectOpen(true)
                .invItem(4151, 0).build());
        }
        RecordingGeActions a = new RecordingGeActions();
        GeEngineHarness h = new GeEngineHarness().queue(snaps);
        h.run(new LinearSequence("collectWrap")
            .then(new SlotWriter(2))
            .then(new CollectOfferStep(a, ALWAYS_PER_SLOT)));
        h.advance(12);

        assertEquals(SequenceState.IDLE, h.state());
    }

    @Test
    public void slotNeverEmptiesFailsWithCollectFailed() {
        // Click missed entirely: slot stays COMPLETE, no inventory change.
        // After timeoutTicks + retries, fail GeCollectFailed.
        WorldSnapshot pre = new GeSnapBuilder().tick(0).geOpen(true)
            .offer(2, OfferSide.BUY, OfferStatus.COMPLETE, 4151, 5, 5, 1_500_000)
            .invItem(4151, 0)
            .build();
        List<WorldSnapshot> snaps = new ArrayList<>();
        snaps.add(pre);
        // Slot stays COMPLETE forever — click never landed.
        for (int t = 1; t < 60; t++) {
            snaps.add(new GeSnapBuilder().tick(t).geOpen(true).geCollectOpen(true)
                .offer(2, OfferSide.BUY, OfferStatus.COMPLETE, 4151, 5, 5, 1_500_000)
                .invItem(4151, 0).build());
        }
        RecordingGeActions a = new RecordingGeActions();
        GeEngineHarness h = new GeEngineHarness().queue(snaps);
        h.run(new LinearSequence("collectWrap")
            .then(new SlotWriter(2))
            .then(new CollectOfferStep(a, ALWAYS_PER_SLOT)));
        h.advance(60);

        assertEquals(SequenceState.FAILED, h.state());
        boolean found = false;
        for (TelemetryRecord r : h.recentTelemetry()) {
            if (r.payload() != null && r.payload().contains("GeCollectFailed")) {
                found = true; break;
            }
        }
        assertTrue("expected GeCollectFailed (telemetry: " + h.recentTelemetry() + ")", found);
    }

    /** Regression: COLLECT_ALL strategy's check() had an early
     *  `return Completion.RUNNING` after re-dispatch that bypassed the
     *  step's own timeout, and the engine's outer timeout
     *  (StepFrame.timedOut) didn't catch it either because re-dispatching
     *  every REDISPATCH_COOLDOWN_TICKS keeps `lastBusyTick` refreshing.
     *  The result was an infinite loop on any persistent silent-miss
     *  (observed live 2026-05-03 22:34 — pie-dish-maker spammed
     *  COLLECT_ALL for 20s with the slot stuck COMPLETE).
     *
     *  <p>This test pins COLLECT_ALL, holds the slot COMPLETE forever,
     *  and asserts GeCollectFailed surfaces — the same shape as the
     *  PER_SLOT version above.
     *
     *  <p>Scope note: this test pins the STEP-LEVEL timeout that the fix
     *  hoists above the strategy branches. The harness's
     *  {@link RecordingGeActions} has no real dispatcher, so the
     *  busy ↔ idle cycle that defeats {@code StepFrame.timedOut} in
     *  production isn't reproduced here — exercising that pathology
     *  end-to-end would need an integration harness with a real
     *  {@code HumanizedInputDispatcher} cycling busy on each
     *  {@code ge.collectAll()}. The step-level guard added by the fix
     *  is sufficient on its own to prevent the infinite loop. */
    @Test
    public void slotNeverEmptiesFailsWithCollectFailedOnCollectAllStrategy() {
        WorldSnapshot pre = new GeSnapBuilder().tick(0).geOpen(true)
            .offer(2, OfferSide.BUY, OfferStatus.COMPLETE, 4151, 5, 5, 1_500_000)
            .invItem(4151, 0)
            .build();
        List<WorldSnapshot> snaps = new ArrayList<>();
        snaps.add(pre);
        for (int t = 1; t < 60; t++) {
            snaps.add(new GeSnapBuilder().tick(t).geOpen(true)
                .offer(2, OfferSide.BUY, OfferStatus.COMPLETE, 4151, 5, 5, 1_500_000)
                .invItem(4151, 0).build());
        }
        RecordingGeActions a = new RecordingGeActions();
        GeEngineHarness h = new GeEngineHarness().queue(snaps);
        h.run(new LinearSequence("collectWrap")
            .then(new SlotWriter(2))
            .then(new CollectOfferStep(a, ALWAYS_COLLECT_ALL)));
        h.advance(60);

        assertEquals(SequenceState.FAILED, h.state());
        // Confirm we actually went through the COLLECT_ALL branch (not
        // the PER_SLOT path), so the test pins what it claims to pin.
        assertTrue("expected ge.collectAll() to have been dispatched at least once: " + a.calls(),
            a.calls().contains("collectAll()"));
        boolean found = false;
        for (TelemetryRecord r : h.recentTelemetry()) {
            if (r.payload() != null && r.payload().contains("GeCollectFailed")) {
                found = true; break;
            }
        }
        assertTrue("expected GeCollectFailed for COLLECT_ALL strategy (telemetry: "
            + h.recentTelemetry() + ")", found);
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
            .then(new CollectOfferStep(a, ALWAYS_PER_SLOT)));
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
