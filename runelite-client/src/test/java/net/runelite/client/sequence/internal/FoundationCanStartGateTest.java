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
import net.runelite.client.sequence.dispatch.MockInputDispatcher;
import net.runelite.client.sequence.telemetry.RingBufferTelemetry;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Foundation test F4: the engine must NOT call onStart on a leaf frame whose
 * canStart returns false. The frame stays parked until canStart flips to true,
 * then onStart fires once.
 */
public class FoundationCanStartGateTest {

    @Test
    public void onStartIsNotCalledWhileCanStartIsFalse() {
        List<WorldSnapshot> snaps = List.of(stub(0), stub(1), stub(2), stub(3), stub(4));
        FixtureObserver obs = new FixtureObserver(snaps);
        MockInputDispatcher disp = new MockInputDispatcher();
        RingBufferTelemetry tel = new RingBufferTelemetry(64);
        ScopedBlackboard bb = new ScopedBlackboard();

        // FixtureObserver walks through snapshots in order; first call returns
        // snap[0] (tick=0), second call snap[1] (tick=1), … Predicate makes
        // canStart return false until snap.tick() >= 2 (the 3rd snapshot).
        GatedStep step = new GatedStep("g", s -> s.tick() >= 2);

        StateDrivenEngine engine = new StateDrivenEngine(obs, new PriorityPlanner(), disp, tel, bb);
        engine.start(step);

        engine.advanceTick(); // snap[0] tick=0, canStart=false → no onStart
        engine.advanceTick(); // snap[1] tick=1, canStart=false → no onStart
        assertEquals(0, step.onStartCount);

        engine.advanceTick(); // snap[2] tick=2, canStart=true → onStart fires
        assertEquals("onStart should fire on the tick canStart returns true", 1, step.onStartCount);

        engine.advanceTick(); // snap[3] tick=3, already started → no second onStart
        assertEquals("onStart must fire exactly once", 1, step.onStartCount);
    }

    @Test
    public void canStartFalseDoesNotAdvanceEngineToFailure() {
        // A step that never permits canStart should NOT cause the engine to FAIL —
        // it should just stay RUNNING (parked). Domain-specific logic (e.g. an
        // outer LinearSequence's child timeout) decides when to give up.
        List<WorldSnapshot> snaps = List.of(stub(0), stub(1), stub(2));
        FixtureObserver obs = new FixtureObserver(snaps);
        MockInputDispatcher disp = new MockInputDispatcher();
        RingBufferTelemetry tel = new RingBufferTelemetry(64);
        ScopedBlackboard bb = new ScopedBlackboard();

        GatedStep step = new GatedStep("never", s -> false);

        StateDrivenEngine engine = new StateDrivenEngine(obs, new PriorityPlanner(), disp, tel, bb);
        engine.start(step);

        engine.advanceTick();
        engine.advanceTick();

        assertEquals(0, step.onStartCount);
        assertEquals(SequenceState.RUNNING, engine.state());
    }

    private static WorldSnapshot stub(int tick) {
        return new WorldSnapshot() {
            public int tick() { return tick; }
            public PlayerView player() { return null; }
        };
    }

    /** Step whose canStart is supplied as a predicate over the snapshot tick. */
    private static class GatedStep implements Step {
        private final String name;
        private final java.util.function.Predicate<WorldSnapshot> canStart;
        int onStartCount;

        GatedStep(String name, java.util.function.Predicate<WorldSnapshot> canStart) {
            this.name = name;
            this.canStart = canStart;
        }

        public String name() { return name; }
        public int priority() { return 50; }
        public int timeoutTicks() { return 100; }
        public PreemptionPolicy preemptionPolicy() { return PreemptionPolicy.NEVER; }
        public boolean isSafeToPause(WorldSnapshot s, Blackboard b) { return true; }
        public boolean canStart(WorldSnapshot s, Blackboard b) { return canStart.test(s); }
        public void onStart(StepContext c) { onStartCount++; }
        public void onEvent(Object e, StepContext c) {}
        public void tick(StepContext c) {}
        public Completion check(WorldSnapshot s, Blackboard b) { return Completion.RUNNING; }
        public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard b) { return new Recovery.Abort(""); }
    }
}
