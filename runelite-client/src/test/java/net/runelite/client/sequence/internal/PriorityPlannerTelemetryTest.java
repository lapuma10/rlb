package net.runelite.client.sequence.internal;

import net.runelite.api.coords.WorldPoint;
import net.runelite.client.sequence.Completion;
import net.runelite.client.sequence.Failure;
import net.runelite.client.sequence.PlayerView;
import net.runelite.client.sequence.PreemptionPolicy;
import net.runelite.client.sequence.Recovery;
import net.runelite.client.sequence.Step;
import net.runelite.client.sequence.StepContext;
import net.runelite.client.sequence.WorldSnapshot;
import net.runelite.client.sequence.affordance.BlockReason;
import net.runelite.client.sequence.affordance.DiagnosticReason;
import net.runelite.client.sequence.blackboard.Blackboard;
import net.runelite.client.sequence.blackboard.BlackboardScope;
import net.runelite.client.sequence.blackboard.SequenceBlackboardKeys;
import net.runelite.client.sequence.blackboard.ScopedBlackboard;
import net.runelite.client.sequence.telemetry.RingBufferTelemetry;
import net.runelite.client.sequence.telemetry.TelemetryRecord;
import org.junit.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.Assert.*;

/**
 * Verifies that PriorityPlanner records a CHECK telemetry record when a step's
 * canStart returns false, including the LAST_BLOCK_REASON from STEP scope.
 */
public class PriorityPlannerTelemetryTest {

    /**
     * A step that writes LAST_BLOCK_REASON to STEP scope then returns false from canStart.
     */
    private static class BlockedStep implements Step {
        private final DiagnosticReason reason;
        boolean canStartCalled = false;

        BlockedStep(DiagnosticReason reason) { this.reason = reason; }

        @Override public String name() { return "BlockedStep"; }
        @Override public int priority() { return 50; }
        @Override public int timeoutTicks() { return 10; }
        @Override public PreemptionPolicy preemptionPolicy() { return PreemptionPolicy.ALWAYS; }
        @Override public boolean isSafeToPause(WorldSnapshot s, Blackboard b) { return true; }

        @Override
        public boolean canStart(WorldSnapshot s, Blackboard b) {
            canStartCalled = true;
            b.scope(BlackboardScope.STEP).put(SequenceBlackboardKeys.LAST_BLOCK_REASON, reason);
            return false;
        }

        @Override public void onStart(StepContext c) {}
        @Override public void onEvent(Object e, StepContext c) {}
        @Override public void tick(StepContext c) {}
        @Override public Completion check(WorldSnapshot s, Blackboard b) { return Completion.RUNNING; }
        @Override public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard b) {
            return new Recovery.Abort(f.reason());
        }
    }

    private static WorldSnapshot snap(int tick) {
        return new WorldSnapshot() {
            public int tick() { return tick; }
            public PlayerView player() {
                return new PlayerView() {
                    public WorldPoint worldLocation() { return new WorldPoint(0, 0, 0); }
                    public int animation() { return -1; }
                    public boolean isIdle() { return true; }
                    public int health() { return 99; }
                    public int maxHealth() { return 99; }
                };
            }
        };
    }

    @Test
    public void blockedStep_emitsCHECKRecordWithReason() {
        RingBufferTelemetry telemetry = new RingBufferTelemetry(64);
        ScopedBlackboard bb = new ScopedBlackboard();
        PriorityPlanner planner = new PriorityPlanner();
        planner.setTelemetry(telemetry);

        DiagnosticReason expected = new BlockReason.BankNotOpen();
        BlockedStep step = new BlockedStep(expected);
        WorldSnapshot snap = snap(1);

        // select returns null because the only candidate's canStart returns false
        Step result = planner.select(snap, bb, List.of(step));

        assertNull("step must not be selected when canStart returns false", result);
        assertTrue("canStart must have been called", step.canStartCalled);

        List<TelemetryRecord> records = telemetry.tail(64);
        assertFalse("telemetry must have records", records.isEmpty());

        TelemetryRecord rec = records.get(0);
        assertEquals("record step name must match", "BlockedStep", rec.stepName());
        assertEquals("record event must be CHECK", TelemetryRecord.Event.CHECK, rec.event());
        assertTrue("record payload must contain reason string",
            rec.payload().contains("BankNotOpen"));
        assertEquals("record tick must match snapshot tick", 1, rec.tick());
    }

    @Test
    public void blockedStep_withNullReason_emitsCheckWithEmptyPayload() {
        RingBufferTelemetry telemetry = new RingBufferTelemetry(64);
        ScopedBlackboard bb = new ScopedBlackboard();
        PriorityPlanner planner = new PriorityPlanner();
        planner.setTelemetry(telemetry);

        // Step that returns false from canStart but does NOT write LAST_BLOCK_REASON
        Step noReasonStep = new Step() {
            @Override public String name() { return "NoReasonStep"; }
            @Override public int priority() { return 40; }
            @Override public int timeoutTicks() { return 10; }
            @Override public PreemptionPolicy preemptionPolicy() { return PreemptionPolicy.ALWAYS; }
            @Override public boolean isSafeToPause(WorldSnapshot s, Blackboard b) { return true; }
            @Override public boolean canStart(WorldSnapshot s, Blackboard b) { return false; }
            @Override public void onStart(StepContext c) {}
            @Override public void onEvent(Object e, StepContext c) {}
            @Override public void tick(StepContext c) {}
            @Override public Completion check(WorldSnapshot s, Blackboard b) { return Completion.RUNNING; }
            @Override public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard b) {
                return new Recovery.Abort(f.reason());
            }
        };

        planner.select(snap(2), bb, List.of(noReasonStep));

        List<TelemetryRecord> records = telemetry.tail(64);
        assertFalse("telemetry must have a record even with null reason", records.isEmpty());
        TelemetryRecord rec = records.get(0);
        assertEquals("NoReasonStep", rec.stepName());
        assertEquals(TelemetryRecord.Event.CHECK, rec.event());
        // Payload mentions canStart=false even when no typed reason is available
        // (matches FoundationCanStartTelemetryTest's contract).
        assertTrue("payload must contain canStart=false when reason is null: " + rec.payload(),
            rec.payload().contains("canStart=false"));
    }

    @Test
    public void lastBlockReason_isReadableFromStepScopeAfterCanStart() {
        ScopedBlackboard bb = new ScopedBlackboard();
        PriorityPlanner planner = new PriorityPlanner();  // no telemetry needed for this assert

        DiagnosticReason expected = new BlockReason.BankNotOpen();
        BlockedStep step = new BlockedStep(expected);
        planner.select(snap(3), bb, List.of(step));

        Optional<DiagnosticReason> actual = bb.scope(BlackboardScope.STEP)
            .get(SequenceBlackboardKeys.LAST_BLOCK_REASON);
        assertTrue("LAST_BLOCK_REASON must be readable from STEP scope after canStart", actual.isPresent());
        assertEquals(expected, actual.get());
    }

    @Test
    public void noTelemetry_doesNotThrowWhenCandidateBlocked() {
        ScopedBlackboard bb = new ScopedBlackboard();
        PriorityPlanner planner = new PriorityPlanner();  // null telemetry — existing tests

        BlockedStep step = new BlockedStep(new BlockReason.BankNotOpen());
        // Must not throw even with no telemetry injected
        Step result = planner.select(snap(1), bb, List.of(step));
        assertNull(result);
    }
}
