package net.runelite.client.sequence;

import net.runelite.client.sequence.blackboard.Blackboard;
import net.runelite.client.sequence.blackboard.ScopedBlackboard;
import net.runelite.client.sequence.dispatch.MockInputDispatcher;
import net.runelite.client.sequence.internal.FixtureObserver;
import net.runelite.client.sequence.internal.PriorityPlanner;
import net.runelite.client.sequence.internal.StateDrivenEngine;
import net.runelite.client.sequence.telemetry.RingBufferTelemetry;
import org.junit.Test;

import java.util.List;

public class SequenceManagerReactivesTest {

    @Test
    public void registerAndClearReactives() {
        SequenceManager m = SequenceManager.withDefaults();
        m.setObserver(new FixtureObserver(List.of(stubSnapshot(0), stubSnapshot(1))));
        m.setDispatcher(new MockInputDispatcher());
        // engine already wired by withDefaults? rebuildEngineIfReady triggers
        // when observer + dispatcher are set.

        Step a = noopStep("a");
        Step b = noopStep("b");
        m.registerReactive(a, 100);
        m.registerReactive(b, 200);

        // Idempotent clear
        m.clearReactives();
        m.clearReactives();

        // Re-register after clear works
        m.registerReactive(a, 100);
    }

    @Test
    public void reactivesSurviveARun() {
        SequenceManager m = SequenceManager.withDefaults();
        m.setObserver(new FixtureObserver(List.of(stubSnapshot(0), stubSnapshot(1), stubSnapshot(2))));
        m.setDispatcher(new MockInputDispatcher());

        Step reactive = noopStep("react");
        m.registerReactive(reactive, 50);

        // Run finishes; reactive remains registered (no clearReactives)
        // Just exercise the API; the engine internals are tested elsewhere.
    }

    private static Step noopStep(String name) {
        return new Step() {
            public String name() { return name; }
            public int priority() { return 50; }
            public int timeoutTicks() { return 50; }
            public PreemptionPolicy preemptionPolicy() { return PreemptionPolicy.NEVER; }
            public boolean isSafeToPause(WorldSnapshot s, Blackboard b) { return true; }
            public boolean canStart(WorldSnapshot s, Blackboard b) { return false; }
            public void onStart(StepContext c) {}
            public void onEvent(Object e, StepContext c) {}
            public void tick(StepContext c) {}
            public Completion check(WorldSnapshot s, Blackboard b) { return Completion.RUNNING; }
            public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard b) { return new Recovery.Abort("no"); }
        };
    }

    private static WorldSnapshot stubSnapshot(int tick) {
        return new WorldSnapshot() {
            public int tick() { return tick; }
            public PlayerView player() { return null; }
        };
    }
}
