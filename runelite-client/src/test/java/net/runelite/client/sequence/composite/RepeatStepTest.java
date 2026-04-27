package net.runelite.client.sequence.composite;

import net.runelite.client.sequence.*;
import net.runelite.client.sequence.blackboard.Blackboard;
import net.runelite.client.sequence.blackboard.ScopedBlackboard;
import net.runelite.client.sequence.dispatch.MockInputDispatcher;
import net.runelite.client.sequence.internal.*;
import net.runelite.client.sequence.telemetry.RingBufferTelemetry;
import net.runelite.client.sequence.telemetry.TelemetryRecord;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class RepeatStepTest {
    @Test
    public void runsBodyExactlyNTimes() {
        WorldSnapshot snap = new WorldSnapshot() {
            public int tick() { return 0; }
            public PlayerView player() { return null; }
        };
        FixtureObserver obs = new FixtureObserver(List.of(snap, snap, snap, snap, snap));
        RingBufferTelemetry tel = new RingBufferTelemetry(64);
        StateDrivenEngine eng = new StateDrivenEngine(
            obs, new PriorityPlanner(), new MockInputDispatcher(), tel, new ScopedBlackboard());

        Counter body = new Counter("BODY");
        eng.start(new RepeatStep("rep", body, 3));
        for (int i = 0; i < 6; i++) eng.advanceTick();

        long doneCount = tel.tail(64).stream()
            .filter(r -> r.event() == TelemetryRecord.Event.SUCCEEDED && r.stepName().equals("BODY"))
            .count();
        assertEquals(3, doneCount);
    }

    private static class Counter implements Step {
        final String n; int count = 0;
        Counter(String n) { this.n = n; }
        public String name() { return n; }
        public int priority() { return 50; }
        public int timeoutTicks() { return 10; }
        public PreemptionPolicy preemptionPolicy() { return PreemptionPolicy.ALWAYS; }
        public boolean isSafeToPause(WorldSnapshot s, Blackboard b) { return true; }
        public boolean canStart(WorldSnapshot s, Blackboard b) { return true; }
        public void onStart(StepContext c) {}
        public void onEvent(Object e, StepContext c) {}
        public void tick(StepContext c) {}
        public Completion check(WorldSnapshot s, Blackboard b) { return new Completion.Succeeded("done"); }
        public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard b) { return new Recovery.Abort(""); }
    }
}
