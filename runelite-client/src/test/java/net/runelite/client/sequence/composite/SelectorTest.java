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

public class SelectorTest {
    @Test
    public void firstChildSucceeds_secondChildSkipped() {
        var snap = new WorldSnapshot() {
            public int tick() { return 0; }
            public PlayerView player() { return null; }
        };
        FixtureObserver obs = new FixtureObserver(List.of(snap, snap, snap));
        RingBufferTelemetry tel = new RingBufferTelemetry(32);
        StateDrivenEngine eng = new StateDrivenEngine(
            obs, new PriorityPlanner(), new MockInputDispatcher(), tel, new ScopedBlackboard());

        eng.start(new Selector("sel")
            .option(new ImmediateSucceed("A"))
            .option(new ImmediateSucceed("B")));

        eng.advanceTick();   // A succeeds, selector finishes
        eng.advanceTick();   // engine settles
        List<String> succ = tel.tail(32).stream()
            .filter(r -> r.event() == TelemetryRecord.Event.SUCCEEDED)
            .map(TelemetryRecord::stepName).toList();
        assertTrue(succ.contains("A"));
        assertFalse(succ.contains("B"));
    }

    private static class ImmediateSucceed implements Step {
        private final String n;
        ImmediateSucceed(String n) { this.n = n; }
        public String name() { return n; }
        public int priority() { return 50; }
        public int timeoutTicks() { return 50; }
        public PreemptionPolicy preemptionPolicy() { return PreemptionPolicy.ALWAYS; }
        public boolean isSafeToPause(WorldSnapshot s, Blackboard b) { return true; }
        public boolean canStart(WorldSnapshot s, Blackboard b) { return true; }
        public void onStart(StepContext c) {}
        public void onEvent(Object e, StepContext c) {}
        public void tick(StepContext c) {}
        public Completion check(WorldSnapshot s, Blackboard b) { return new Completion.Succeeded("ok"); }
        public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard b) { return new Recovery.Abort(""); }
    }
}
