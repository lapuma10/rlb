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

import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * Foundation test F2: {@code Recovery.Retry(N)} permits exactly N retries
 * (N+1 total onStart invocations) regardless of what the step's
 * {@code onFailure} returns on subsequent calls. Without cumulative tracking,
 * a step that returns {@code Retry(1)} unconditionally would loop forever.
 */
public class FoundationRetryBoundTest {

    @Test
    public void retryOneBoundedAtTwoTotalAttempts() {
        AlwaysFailRetryStep step = new AlwaysFailRetryStep("r1", 1);
        runUntilTerminal(step);
        assertEquals("Retry(1) means 1 retry + 1 initial = 2 total onStart calls",
            2, step.onStartCount);
    }

    @Test
    public void retryThreeBoundedAtFourTotalAttempts() {
        AlwaysFailRetryStep step = new AlwaysFailRetryStep("r3", 3);
        runUntilTerminal(step);
        assertEquals("Retry(3) means 3 retries + 1 initial = 4 total onStart calls",
            4, step.onStartCount);
    }

    private void runUntilTerminal(Step step) {
        FixtureObserver obs = new FixtureObserver(List.of(
            stub(0), stub(1), stub(2), stub(3), stub(4), stub(5), stub(6), stub(7)
        ));
        StateDrivenEngine engine = new StateDrivenEngine(obs, new PriorityPlanner(),
            new MockInputDispatcher(), new RingBufferTelemetry(64), new ScopedBlackboard());
        engine.start(step);
        // Up to 16 ticks should be plenty; test asserts onStart count, not tick budget.
        for (int i = 0; i < 16 && engine.state() == SequenceState.RUNNING; i++) {
            engine.advanceTick();
        }
        assertEquals(SequenceState.FAILED, engine.state());
    }

    private static WorldSnapshot stub(int tick) {
        return new WorldSnapshot() {
            public int tick() { return tick; }
            public PlayerView player() { return null; }
        };
    }

    /** Step that always fails its first {@code check}, and unconditionally
     *  asks for {@code Retry(N)} on every {@code onFailure}. The engine MUST
     *  enforce N as a hard cap on retries even though {@code onFailure} keeps
     *  asking. */
    private static class AlwaysFailRetryStep implements Step {
        private final String name;
        private final int retryN;
        int onStartCount;

        AlwaysFailRetryStep(String name, int retryN) {
            this.name = name;
            this.retryN = retryN;
        }

        public String name() { return name; }
        public int priority() { return 50; }
        public int timeoutTicks() { return 1000; }
        public PreemptionPolicy preemptionPolicy() { return PreemptionPolicy.NEVER; }
        public boolean isSafeToPause(WorldSnapshot s, Blackboard b) { return true; }
        public boolean canStart(WorldSnapshot s, Blackboard b) { return true; }
        public void onStart(StepContext c) { onStartCount++; }
        public void onEvent(Object e, StepContext c) {}
        public void tick(StepContext c) {}
        public Completion check(WorldSnapshot s, Blackboard b) { return new Completion.Failed("boom"); }
        public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard b) { return new Recovery.Retry(retryN); }
    }
}
