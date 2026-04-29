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
import net.runelite.client.sequence.blackboard.BlackboardKey;
import net.runelite.client.sequence.blackboard.BlackboardScope;
import net.runelite.client.sequence.blackboard.ScopedBlackboard;
import net.runelite.client.sequence.composite.LinearSequence;
import net.runelite.client.sequence.dispatch.MockInputDispatcher;
import net.runelite.client.sequence.telemetry.RingBufferTelemetry;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

/**
 * Regression tests for STEP-scope blackboard lifecycle:
 * 1. A value written to STEP scope in onStart is visible in same-tick check().
 * 2. After a child step completes and the next child starts, the previous STEP scope is cleared.
 */
public class StateDrivenEngineStepScopeLifecycleTest {

    static final BlackboardKey<String> K_OUTCOME = BlackboardKey.of("outcome", String.class);

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

    /**
     * A step that writes K_OUTCOME to STEP scope in onStart, then reads it in check().
     * Records whether the value was visible in check().
     */
    private static class StepScopeWriteReadStep implements Step {
        final AtomicBoolean valueVisibleInCheck = new AtomicBoolean(false);
        private final String label;

        StepScopeWriteReadStep(String label) { this.label = label; }

        @Override public String name() { return "StepScopeWR-" + label; }
        @Override public int priority() { return 50; }
        @Override public int timeoutTicks() { return 20; }
        @Override public PreemptionPolicy preemptionPolicy() { return PreemptionPolicy.ALWAYS; }
        @Override public boolean isSafeToPause(WorldSnapshot s, Blackboard b) { return true; }
        @Override public boolean canStart(WorldSnapshot s, Blackboard b) { return true; }

        @Override public void onStart(StepContext c) {
            c.bb().scope(BlackboardScope.STEP).put(K_OUTCOME, label);
        }

        @Override public void onEvent(Object e, StepContext c) {}
        @Override public void tick(StepContext c) {}

        @Override public Completion check(WorldSnapshot s, Blackboard b) {
            Optional<String> v = b.scope(BlackboardScope.STEP).get(K_OUTCOME);
            valueVisibleInCheck.set(v.isPresent() && label.equals(v.get()));
            return new Completion.Succeeded("done-" + label);
        }

        @Override public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard b) {
            return new Recovery.Abort(f.reason());
        }
    }

    /**
     * A step that checks whether K_OUTCOME is absent from STEP scope when it starts.
     * Used as the second child to verify the previous STEP scope was cleared.
     */
    private static class CheckStepScopeClearedStep implements Step {
        final AtomicBoolean clearedOnEntry = new AtomicBoolean(false);

        @Override public String name() { return "CheckScopeCleared"; }
        @Override public int priority() { return 50; }
        @Override public int timeoutTicks() { return 20; }
        @Override public PreemptionPolicy preemptionPolicy() { return PreemptionPolicy.ALWAYS; }
        @Override public boolean isSafeToPause(WorldSnapshot s, Blackboard b) { return true; }
        @Override public boolean canStart(WorldSnapshot s, Blackboard b) { return true; }

        @Override public void onStart(StepContext c) {
            // Check whether STEP scope is clear when this step starts
            Optional<String> v = c.bb().scope(BlackboardScope.STEP).get(K_OUTCOME);
            clearedOnEntry.set(v.isEmpty());
        }

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
    public void stepScopeValue_visibleInSameTickCheck() {
        List<WorldSnapshot> snaps = new ArrayList<>();
        for (int i = 1; i <= 5; i++) snaps.add(snap(i));

        FixtureObserver obs = new FixtureObserver(snaps);
        MockInputDispatcher disp = new MockInputDispatcher();
        RingBufferTelemetry tel = new RingBufferTelemetry(64);
        ScopedBlackboard bb = new ScopedBlackboard();

        StepScopeWriteReadStep step = new StepScopeWriteReadStep("A");
        StateDrivenEngine engine = new StateDrivenEngine(obs, new PriorityPlanner(), disp, tel, bb);
        engine.start(step);

        engine.advanceTick();

        assertTrue("STEP-scope value written in onStart must be visible in same-tick check()",
            step.valueVisibleInCheck.get());
        assertEquals("engine must be IDLE after immediate success", SequenceState.IDLE, engine.state());
    }

    @Test
    public void stepScope_clearedBetweenSequentialChildren() {
        // Run a LinearSequence of two children.
        // Child 1: writes K_OUTCOME="A" to STEP scope, completes immediately.
        // Child 2: checks whether K_OUTCOME is absent at onStart time (STEP scope was cleared).
        List<WorldSnapshot> snaps = new ArrayList<>();
        for (int i = 1; i <= 10; i++) snaps.add(snap(i));

        FixtureObserver obs = new FixtureObserver(snaps);
        MockInputDispatcher disp = new MockInputDispatcher();
        RingBufferTelemetry tel = new RingBufferTelemetry(128);
        ScopedBlackboard bb = new ScopedBlackboard();

        StepScopeWriteReadStep child1 = new StepScopeWriteReadStep("A");
        CheckStepScopeClearedStep child2 = new CheckStepScopeClearedStep();

        LinearSequence seq = new LinearSequence("seq").then(child1).then(child2);
        StateDrivenEngine engine = new StateDrivenEngine(obs, new PriorityPlanner(), disp, tel, bb);
        engine.start(seq);

        // Drive to completion
        for (int i = 0; i < 10 && engine.state() == SequenceState.RUNNING; i++) {
            engine.advanceTick();
        }

        assertEquals("engine must be IDLE after sequence completes", SequenceState.IDLE, engine.state());
        assertTrue("child1 must have seen its STEP-scope value in check",
            child1.valueVisibleInCheck.get());
        assertTrue("child2 must see STEP scope cleared on entry (previous step's data gone)",
            child2.clearedOnEntry.get());
    }
}
