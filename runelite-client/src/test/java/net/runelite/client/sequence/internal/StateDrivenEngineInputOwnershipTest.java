package net.runelite.client.sequence.internal;

import net.runelite.client.sequence.Completion;
import net.runelite.client.sequence.Failure;
import net.runelite.client.sequence.PlayerView;
import net.runelite.client.sequence.PreemptionPolicy;
import net.runelite.client.sequence.Recovery;
import net.runelite.client.sequence.SequenceState;
import net.runelite.client.sequence.Step;
import net.runelite.client.sequence.StepContext;
import net.runelite.client.sequence.WorldSnapshot;
import net.runelite.client.sequence.blackboard.Blackboard;
import net.runelite.client.sequence.blackboard.ScopedBlackboard;
import net.runelite.client.sequence.dispatch.InputOwnership;
import net.runelite.client.sequence.dispatch.MockInputDispatcher;
import net.runelite.client.sequence.telemetry.RingBufferTelemetry;
import net.runelite.client.sequence.telemetry.TelemetryRecord;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class StateDrivenEngineInputOwnershipTest {

    @Test
    public void engineFailsWhenOwnershipLostMidSequence() {
        WorldSnapshot s0 = stub(0);
        WorldSnapshot s1 = stub(1);
        WorldSnapshot s2 = stub(2);
        FixtureObserver obs = new FixtureObserver(List.of(s0, s1, s2));
        MockInputDispatcher disp = new MockInputDispatcher();
        RingBufferTelemetry tel = new RingBufferTelemetry(64);
        ScopedBlackboard bb = new ScopedBlackboard();

        InputOwnership ownership = new InputOwnership();
        ownership.tryAcquire("test-owner");

        StateDrivenEngine engine = new StateDrivenEngine(obs, new PriorityPlanner(), disp, tel, bb);
        engine.setInputOwnership(ownership, "test-owner");
        engine.start(new RunForeverStep("forever"));

        engine.advanceTick(); // started, running
        // Externally yank the lease away
        ownership.release("test-owner");
        ownership.tryAcquire("someone-else");

        engine.advanceTick(); // engine should detect lost ownership and fail

        assertEquals(SequenceState.FAILED, engine.state());
        boolean foundOwnershipDiagnostic = tel.tail(64).stream()
            .anyMatch(r -> r.payload() != null && r.payload().contains("input ownership lost"));
        assertTrue("expected telemetry mentioning lost ownership, got: " + tel.tail(64),
            foundOwnershipDiagnostic);
    }

    private static WorldSnapshot stub(int tick) {
        return new WorldSnapshot() {
            public int tick() { return tick; }
            public PlayerView player() { return null; }
        };
    }

    /** Always Running; does not call onStart-side actions. The ownership
     *  check happens at tick start (before check), so we just need a step
     *  that stays alive. */
    private static class RunForeverStep implements Step {
        private final String name;
        RunForeverStep(String n) { this.name = n; }
        public String name() { return name; }
        public int priority() { return 50; }
        public int timeoutTicks() { return 1000; }
        public PreemptionPolicy preemptionPolicy() { return PreemptionPolicy.NEVER; }
        public boolean isSafeToPause(WorldSnapshot s, Blackboard b) { return true; }
        public boolean canStart(WorldSnapshot s, Blackboard b) { return true; }
        public void onStart(StepContext c) {}
        public void onEvent(Object e, StepContext c) {}
        public void tick(StepContext c) {}
        public Completion check(WorldSnapshot s, Blackboard b) { return Completion.RUNNING; }
        public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard b) { return new Recovery.Abort(""); }
    }
}
