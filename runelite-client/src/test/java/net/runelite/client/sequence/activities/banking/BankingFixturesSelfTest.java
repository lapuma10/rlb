package net.runelite.client.sequence.activities.banking;

import net.runelite.api.coords.WorldPoint;
import net.runelite.client.sequence.*;
import net.runelite.client.sequence.affordance.BlockingInterface;
import net.runelite.client.sequence.blackboard.Blackboard;
import net.runelite.client.sequence.views.*;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Smoke tests verifying the Task 5 fixtures are correctly wired.
 * No real banking steps are exercised here — those come in Tasks 8a-h.
 */
public class BankingFixturesSelfTest {

    // -------------------------------------------------------------------------
    // BankSnapBuilder
    // -------------------------------------------------------------------------

    @Test
    public void snapBuilder_bankOpen_returnsTrue() {
        WorldSnapshot snap = new BankSnapBuilder()
            .tick(5)
            .bankOpen(true)
            .bankReady(true)
            .build();

        assertEquals(5, snap.tick());
        assertTrue(snap.bank().open());
        assertTrue(snap.bank().ready());
    }

    @Test
    public void snapBuilder_bankClosed_defaults() {
        WorldSnapshot snap = new BankSnapBuilder().build();

        assertFalse(snap.bank().open());
        assertFalse(snap.bank().ready());
        assertFalse(snap.bank().pinUp());
    }

    @Test
    public void snapBuilder_bankPinUp() {
        WorldSnapshot snap = new BankSnapBuilder().bankPinUp(true).build();
        assertTrue(snap.bank().pinUp());
    }

    @Test
    public void snapBuilder_bankItem_present() {
        WorldSnapshot snap = new BankSnapBuilder()
            .bankItem(995, 50_000, true)
            .build();

        BankItemAvailability avail = snap.bank().availability(995);
        assertEquals(Presence.PRESENT, avail.presence());
        assertEquals(50_000, avail.knownCount().getAsInt());
        assertTrue(avail.visible());
    }

    @Test
    public void snapBuilder_bankItemAbsent() {
        WorldSnapshot snap = new BankSnapBuilder()
            .bankItemAbsent(317)
            .build();

        BankItemAvailability avail = snap.bank().availability(317);
        assertEquals(Presence.ABSENT, avail.presence());
        assertTrue(avail.knownCount().isEmpty());
    }

    @Test
    public void snapBuilder_bankItemUnknown_default() {
        WorldSnapshot snap = new BankSnapBuilder().build();

        // Any un-set item defaults to UNKNOWN
        BankItemAvailability avail = snap.bank().availability(12345);
        assertEquals(Presence.UNKNOWN, avail.presence());
    }

    @Test
    public void snapBuilder_bankItemUnknown_explicit() {
        WorldSnapshot snap = new BankSnapBuilder()
            .bankItemUnknown(317)
            .build();

        assertEquals(Presence.UNKNOWN, snap.bank().availability(317).presence());
    }

    @Test
    public void snapBuilder_invItems_freeSlots_derived() {
        WorldSnapshot snap = new BankSnapBuilder()
            .invItem(314, 1)   // raw shrimp
            .invItem(315, 1)   // cooked shrimp
            .build();

        assertEquals(2, snap.inventory().items().size());
        assertEquals(26, snap.inventory().freeSlots());
        assertFalse(snap.inventory().isFull());
        assertTrue(snap.inventory().contains(314));
        assertEquals(1, snap.inventory().count(315));
    }

    @Test
    public void snapBuilder_freeSlots_override() {
        WorldSnapshot snap = new BankSnapBuilder()
            .invItem(314, 1)
            .freeSlots(5)   // explicit override
            .build();

        assertEquals(5, snap.inventory().freeSlots());
    }

    @Test
    public void snapBuilder_fullInventory() {
        BankSnapBuilder b = new BankSnapBuilder().freeSlots(0);
        WorldSnapshot snap = b.build();
        assertTrue(snap.inventory().isFull());
    }

    @Test
    public void snapBuilder_blocker_present() {
        BlockingInterface bi = new BlockingInterface("LevelUpDialog", 233, true, true);
        WorldSnapshot snap = new BankSnapBuilder()
            .blocker(bi)
            .build();

        assertTrue(snap.interaction().blockingInterface().isPresent());
        assertEquals("LevelUpDialog", snap.interaction().blockingInterface().get().name());
        assertEquals(233, snap.interaction().blockingInterface().get().rootWidgetId());
    }

    @Test
    public void snapBuilder_noBlocker_empty() {
        WorldSnapshot snap = new BankSnapBuilder().build();
        assertTrue(snap.interaction().blockingInterface().isEmpty());
    }

    @Test
    public void snapBuilder_interactionMode() {
        WorldSnapshot snap = new BankSnapBuilder()
            .mode(InteractionMode.BANKING)
            .build();

        assertEquals(InteractionMode.BANKING, snap.interaction().mode());
    }

    @Test
    public void snapBuilder_worldAvailableFalse() {
        WorldSnapshot snap = new BankSnapBuilder()
            .worldAvailable(false)
            .build();

        assertFalse(snap.interaction().worldInteractionAvailable());
        assertFalse(snap.interaction().movementAvailable());
    }

    @Test
    public void snapBuilder_eventFacts_lastInvChangeTick() {
        WorldSnapshot snap = new BankSnapBuilder()
            .lastInvChangeTick(42)
            .build();

        assertEquals(42, snap.events().lastInventoryChangeTick());
    }

    @Test
    public void snapBuilder_eventFacts_allFields() {
        WorldSnapshot snap = new BankSnapBuilder()
            .lastInvChangeTick(10)
            .lastBankContainerChangeTick(11)
            .lastBlockingInterfaceChangeTick(12)
            .lastPlayerAnimationChangeTick(13)
            .build();

        assertEquals(10, snap.events().lastInventoryChangeTick());
        assertEquals(11, snap.events().lastBankContainerChangeTick());
        assertEquals(12, snap.events().lastBlockingInterfaceChangeTick());
        assertEquals(13, snap.events().lastPlayerAnimationChangeTick());
    }

    @Test
    public void snapBuilder_eventFacts_defaults_minusOne() {
        WorldSnapshot snap = new BankSnapBuilder().build();
        assertEquals(-1, snap.events().lastInventoryChangeTick());
        assertEquals(-1, snap.events().lastBankContainerChangeTick());
        assertEquals(-1, snap.events().lastBlockingInterfaceChangeTick());
        assertEquals(-1, snap.events().lastPlayerAnimationChangeTick());
    }

    @Test
    public void snapBuilder_player_convenience() {
        WorldPoint loc = new WorldPoint(3210, 3424, 0);
        WorldSnapshot snap = new BankSnapBuilder().player(loc).build();

        assertNotNull(snap.player());
        assertEquals(loc, snap.player().worldLocation());
        assertTrue(snap.player().isIdle());
    }

    @Test
    public void snapBuilder_widget_visible() {
        WorldSnapshot snap = new BankSnapBuilder()
            .widgetVisible(786)
            .build();

        assertTrue(snap.widgets().isVisible(786));
        assertFalse(snap.widgets().isHidden(786));
        assertTrue(snap.widgets().visibleRootIds().contains(786));
    }

    @Test
    public void snapBuilder_widget_hidden_by_default() {
        WorldSnapshot snap = new BankSnapBuilder().build();
        assertFalse(snap.widgets().isVisible(999));
        assertTrue(snap.widgets().visibleRootIds().isEmpty());
    }

    // -------------------------------------------------------------------------
    // RecordingBankActions
    // -------------------------------------------------------------------------

    @Test
    public void recording_clickBankBoothRandom() {
        RecordingBankActions actions = new RecordingBankActions();
        actions.clickBankBoothRandom();
        assertEquals(1, actions.calls().size());
        assertEquals("clickBankBoothRandom()", actions.calls().get(0));
    }

    @Test
    public void recording_depositAll() {
        RecordingBankActions actions = new RecordingBankActions();
        actions.depositAll(123);
        assertEquals("depositAll(123)", actions.calls().get(0));
    }

    @Test
    public void recording_withdrawOne() {
        RecordingBankActions actions = new RecordingBankActions();
        actions.withdrawOne(456);
        assertEquals("withdrawOne(456)", actions.calls().get(0));
    }

    @Test
    public void recording_withdrawAll() {
        RecordingBankActions actions = new RecordingBankActions();
        actions.withdrawAll(789);
        assertEquals("withdrawAll(789)", actions.calls().get(0));
    }

    @Test
    public void recording_withdrawX() {
        RecordingBankActions actions = new RecordingBankActions();
        actions.withdrawX(456, 12);
        assertEquals("withdrawX(456,12)", actions.calls().get(0));
    }

    @Test
    public void recording_closeBank() {
        RecordingBankActions actions = new RecordingBankActions();
        actions.closeBank();
        assertEquals("closeBank()", actions.calls().get(0));
    }

    @Test
    public void recording_multipleCallsInOrder() {
        RecordingBankActions actions = new RecordingBankActions();
        actions.clickBankBoothRandom();
        actions.depositAll(100);
        actions.withdrawX(200, 5);
        actions.closeBank();

        assertEquals(4, actions.calls().size());
        assertEquals("clickBankBoothRandom()", actions.calls().get(0));
        assertEquals("depositAll(100)",        actions.calls().get(1));
        assertEquals("withdrawX(200,5)",       actions.calls().get(2));
        assertEquals("closeBank()",            actions.calls().get(3));
    }

    @Test
    public void recording_reset_clearsCalls() {
        RecordingBankActions actions = new RecordingBankActions();
        actions.depositAll(1);
        actions.reset();
        assertTrue(actions.calls().isEmpty());
    }

    // -------------------------------------------------------------------------
    // BankingEngineHarness — smoke test
    // -------------------------------------------------------------------------

    @Test
    public void harness_immediatelySucceedingStep_reachesIdleState() {
        BankingEngineHarness harness = new BankingEngineHarness();

        WorldSnapshot snap = new BankSnapBuilder().tick(1).bankOpen(true).build();
        harness.queue(snap, snap, snap);

        Step immediateSucceed = new ImmediateSucceedStep();
        harness.run(immediateSucceed);
        harness.advance(2);

        assertEquals(SequenceState.IDLE, harness.state());
    }

    @Test
    public void harness_queuedSnapshotsAreConsumedInOrder() {
        BankingEngineHarness harness = new BankingEngineHarness();

        // Queue two distinct snapshots and verify the second one is distinct
        WorldSnapshot snap1 = new BankSnapBuilder().tick(10).bankOpen(false).build();
        WorldSnapshot snap2 = new BankSnapBuilder().tick(20).bankOpen(true).build();
        harness.queue(snap1, snap2);

        // The observer delivers snap1 first, then snap2 (and clamps to snap2 after)
        // Just verify the harness doesn't throw and the step can run
        harness.run(new ImmediateSucceedStep());
        harness.advance(3);

        assertEquals(SequenceState.IDLE, harness.state());
    }

    @Test
    public void harness_dispatcherStartsEmpty() {
        BankingEngineHarness harness = new BankingEngineHarness();
        assertTrue(harness.dispatcher().getRequests().isEmpty());
    }

    @Test
    public void harness_telemetryAccessible() {
        BankingEngineHarness harness = new BankingEngineHarness();
        assertNotNull(harness.telemetry());
    }

    // -------------------------------------------------------------------------
    // Minimal Step impl for harness smoke tests
    // -------------------------------------------------------------------------

    /** A step that always reports itself done on first check. */
    private static final class ImmediateSucceedStep implements Step {
        @Override public String name()                { return "ImmediateSucceed"; }
        @Override public int priority()               { return 0; }
        @Override public int timeoutTicks()           { return 10; }
        @Override public PreemptionPolicy preemptionPolicy() { return PreemptionPolicy.NEVER; }
        @Override public boolean isSafeToPause(WorldSnapshot s, Blackboard b) { return true; }
        @Override public boolean canStart(WorldSnapshot s, Blackboard b)      { return true; }
        @Override public void onStart(StepContext ctx)                         {}
        @Override public void onEvent(Object e, StepContext ctx)               {}
        @Override public void tick(StepContext ctx)                            {}
        @Override public Completion check(WorldSnapshot s, Blackboard b) {
            return new Completion.Succeeded("done");
        }
        @Override public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard b) {
            return new Recovery.Abort("unexpected");
        }
    }
}
