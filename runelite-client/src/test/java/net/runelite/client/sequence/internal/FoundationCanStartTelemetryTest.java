package net.runelite.client.sequence.internal;

import net.runelite.client.sequence.Completion;
import net.runelite.client.sequence.Failure;
import net.runelite.client.sequence.PlayerView;
import net.runelite.client.sequence.PreemptionPolicy;
import net.runelite.client.sequence.Recovery;
import net.runelite.client.sequence.Step;
import net.runelite.client.sequence.StepContext;
import net.runelite.client.sequence.WorldSnapshot;
import net.runelite.client.sequence.affordance.BlockReason;
import net.runelite.client.sequence.affordance.BlockingInterface;
import net.runelite.client.sequence.affordance.DiagnosticReason;
import net.runelite.client.sequence.blackboard.Blackboard;
import net.runelite.client.sequence.blackboard.BlackboardScope;
import net.runelite.client.sequence.blackboard.ScopedBlackboard;
import net.runelite.client.sequence.blackboard.SequenceBlackboardKeys;
import net.runelite.client.sequence.telemetry.RingBufferTelemetry;
import net.runelite.client.sequence.telemetry.TelemetryRecord;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Foundation test: when the planner rejects a candidate via {@code canStart},
 * it records a {@code CHECK} telemetry event with the step's name and the
 * typed reason the step left in {@code LAST_BLOCK_REASON}.
 */
public class FoundationCanStartTelemetryTest {

    @Test
    public void rejectedStepIsRecordedWithReason() {
        RingBufferTelemetry tel = new RingBufferTelemetry(64);
        PriorityPlanner p = new PriorityPlanner();
        p.setTelemetry(tel);

        ScopedBlackboard bb = new ScopedBlackboard();
        WorldSnapshot snap = new WorldSnapshot() {
            public int tick() { return 7; }
            public PlayerView player() { return null; }
        };

        DiagnosticReason reason = new BlockReason.WorldInteractionBlocked(
            new BlockingInterface("test-modal", 12345, true, true));
        Step rejected = new RejectingStep("rejected-step", reason);

        Step result = p.select(snap, bb, List.of(rejected));

        org.junit.Assert.assertNull("no candidate eligible → null", result);

        TelemetryRecord match = null;
        for (TelemetryRecord r : tel.tail(64)) {
            if ("rejected-step".equals(r.stepName())
                && r.event() == TelemetryRecord.Event.CHECK) {
                match = r;
                break;
            }
        }
        assertNotNull("expected CHECK telemetry for rejected step, got: " + tel.tail(64), match);
        assertTrue("payload should mention canStart=false: " + match.payload(),
            match.payload() != null && match.payload().contains("canStart=false"));
        assertTrue("payload should mention the reason: " + match.payload(),
            match.payload().contains("WorldInteractionBlocked"));
    }

    @Test
    public void plannerWithoutTelemetryDoesNotCrash() {
        // Backwards compat: no telemetry set → no recording, no NPE.
        PriorityPlanner p = new PriorityPlanner();
        ScopedBlackboard bb = new ScopedBlackboard();
        WorldSnapshot snap = new WorldSnapshot() {
            public int tick() { return 0; }
            public PlayerView player() { return null; }
        };
        Step rejected = new RejectingStep("r", new BlockReason.PinKeypadUp());
        org.junit.Assert.assertNull(p.select(snap, bb, List.of(rejected)));
    }

    /** Step whose canStart returns false and writes a typed reason to STEP scope. */
    private static class RejectingStep implements Step {
        private final String name;
        private final DiagnosticReason reason;

        RejectingStep(String name, DiagnosticReason reason) {
            this.name = name;
            this.reason = reason;
        }

        public String name() { return name; }
        public int priority() { return 50; }
        public int timeoutTicks() { return 100; }
        public PreemptionPolicy preemptionPolicy() { return PreemptionPolicy.NEVER; }
        public boolean isSafeToPause(WorldSnapshot s, Blackboard b) { return true; }
        public boolean canStart(WorldSnapshot s, Blackboard b) {
            b.scope(BlackboardScope.STEP).put(SequenceBlackboardKeys.LAST_BLOCK_REASON, reason);
            return false;
        }
        public void onStart(StepContext c) {}
        public void onEvent(Object e, StepContext c) {}
        public void tick(StepContext c) {}
        public Completion check(WorldSnapshot s, Blackboard b) { return Completion.RUNNING; }
        public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard b) { return new Recovery.Abort(""); }
    }
}
