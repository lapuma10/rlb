package net.runelite.client.sequence.internal;

import net.runelite.api.coords.WorldPoint;
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
import net.runelite.client.sequence.telemetry.TelemetryRecord;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Verifies clearReactives() behavior:
 * - calling clear after register means the step is no longer selected by the planner.
 * - three sequential banking-style runs (each registers one reactive then clearReactives after stop)
 *   do not accumulate reactives across runs.
 */
public class StateDrivenEngineClearReactivesTest {

    /**
     * Reactive step that runs exactly once: canStart returns true only when not yet run.
     * Once onStart fires, canStart returns false (so it won't be re-selected after completing).
     * check() returns Succeeded immediately once started.
     */
    private static class ImmediateReactive implements Step {
        final String id;
        int selectedCount = 0;

        ImmediateReactive(String id) { this.id = id; }

        @Override public String name() { return "Reactive-" + id; }
        @Override public int priority() { return 60; }
        @Override public int timeoutTicks() { return 10; }
        @Override public PreemptionPolicy preemptionPolicy() { return PreemptionPolicy.ALWAYS; }
        @Override public boolean isSafeToPause(WorldSnapshot s, Blackboard b) { return true; }
        /** Only eligible when not yet run — prevents re-selection after completion. */
        @Override public boolean canStart(WorldSnapshot s, Blackboard b) { return selectedCount == 0; }
        @Override public void onStart(StepContext c) { selectedCount++; }
        @Override public void onEvent(Object e, StepContext c) {}
        @Override public void tick(StepContext c) {}
        @Override public Completion check(WorldSnapshot s, Blackboard b) {
            return selectedCount > 0 ? new Completion.Succeeded("done") : Completion.RUNNING;
        }
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

    private StateDrivenEngine makeEngine(List<WorldSnapshot> snaps) {
        FixtureObserver obs = new FixtureObserver(snaps);
        return new StateDrivenEngine(obs, new PriorityPlanner(),
            new MockInputDispatcher(), new RingBufferTelemetry(128), new ScopedBlackboard());
    }

    /** Immediate-succeed step used to kick the engine into RUNNING so the planner gets a turn. */
    private static class OneShotStep implements Step {
        @Override public String name() { return "OneShotStep"; }
        @Override public int priority() { return 10; }  // lower than reactive's 60
        @Override public int timeoutTicks() { return 10; }
        @Override public PreemptionPolicy preemptionPolicy() { return PreemptionPolicy.ALWAYS; }
        @Override public boolean isSafeToPause(WorldSnapshot s, Blackboard b) { return true; }
        @Override public boolean canStart(WorldSnapshot s, Blackboard b) { return true; }
        @Override public void onStart(StepContext c) {}
        @Override public void onEvent(Object e, StepContext c) {}
        @Override public void tick(StepContext c) {}
        @Override public Completion check(WorldSnapshot s, Blackboard b) {
            return new Completion.Succeeded("done");
        }
        @Override public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard b) {
            return new Recovery.Abort(f.reason());
        }
    }

    @Test
    public void clearReactives_wipesAllRegistrations() {
        List<WorldSnapshot> snaps = new ArrayList<>();
        for (int i = 1; i <= 5; i++) snaps.add(snap(i));

        StateDrivenEngine engine = makeEngine(snaps);
        ImmediateReactive r = new ImmediateReactive("A");
        engine.registerReactive(r);
        engine.clearReactives();

        // After clear: start a one-shot step so the engine reaches RUNNING.
        // When the one-shot step completes, frames become empty. The planner runs
        // but has no candidates → engine goes IDLE without selecting the cleared reactive.
        engine.start(new OneShotStep());
        engine.advanceTick();   // one-shot step: onStart → check succeeds → frames empty → IDLE (no reactive)
        assertEquals("engine must be IDLE — no reactive after clear", SequenceState.IDLE, engine.state());
        assertEquals("reactive must not have been onStart'd", 0, r.selectedCount);
    }

    @Test
    public void clearReactives_isIdempotent() {
        StateDrivenEngine engine = makeEngine(List.of(snap(1)));
        // Calling clearReactives on an empty set must not throw
        engine.clearReactives();
        engine.clearReactives();
        assertEquals(SequenceState.IDLE, engine.state());
    }

    @Test
    public void threeSequentialRuns_reactivesDontAccumulate() {
        // Simulate 3 banking-style runs on the SAME engine instance.
        // Each run: (1) registers all 3 reactives to simulate accidental accumulation,
        // (2) calls clearReactives() then registers only the current run's reactive,
        // (3) starts a one-shot root step so the engine is RUNNING,
        // (4) ticks once — one-shot completes, planner picks exactly one reactive,
        // (5) ticks again — reactive's check succeeds, engine goes IDLE,
        // (6) clearReactives() + stop() to reset for next run.
        //
        // Without clearReactives(), all 3 reactives would be in the set by run 3.
        // With clearReactives(), only the designated reactive fires each run.

        ImmediateReactive r1 = new ImmediateReactive("run1");
        ImmediateReactive r2 = new ImmediateReactive("run2");
        ImmediateReactive r3 = new ImmediateReactive("run3");
        ImmediateReactive[] reactives = {r1, r2, r3};

        List<WorldSnapshot> snaps = new ArrayList<>();
        for (int i = 1; i <= 20; i++) snaps.add(snap(i));

        StateDrivenEngine engine = makeEngine(snaps);

        for (int run = 0; run < 3; run++) {
            ImmediateReactive current = reactives[run];

            // Simulate an accumulation bug: register all three (as if prior cleanup failed)
            engine.registerReactive(r1);
            engine.registerReactive(r2);
            engine.registerReactive(r3);
            // Then clear — this is what the banking script is supposed to call
            engine.clearReactives();
            // Register only the current run's reactive
            engine.registerReactive(current);

            // Tick 1: one-shot root step starts and immediately succeeds, then frames become empty
            // and planner selects the reactive → reactive.onStart fires
            engine.start(new OneShotStep());
            engine.advanceTick();   // OneShotStep succeeds; planner picks reactive; reactive onStart fires

            // Tick 2: reactive's check() returns Succeeded (selectedCount > 0) → engine IDLE
            engine.advanceTick();
            assertEquals("engine IDLE after run " + (run + 1), SequenceState.IDLE, engine.state());

            // Reset for next run
            engine.stop();
            engine.clearReactives();
        }

        // Each reactive was selected exactly once across all 3 runs
        assertEquals("r1 selected exactly once", 1, r1.selectedCount);
        assertEquals("r2 selected exactly once", 1, r2.selectedCount);
        assertEquals("r3 selected exactly once", 1, r3.selectedCount);
    }
}
