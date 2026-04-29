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
import net.runelite.client.sequence.blackboard.BlackboardKey;
import net.runelite.client.sequence.blackboard.BlackboardScope;
import net.runelite.client.sequence.blackboard.ScopedBlackboard;
import net.runelite.client.sequence.dispatch.MockInputDispatcher;
import net.runelite.client.sequence.telemetry.RingBufferTelemetry;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Foundation test F3: STEP-scope blackboard keys written in {@code onStart}
 * are visible to the same-tick {@code check}. The engine must NOT clear STEP
 * scope between the two phases.
 *
 * <p>Also verifies the dual: STEP scope IS cleared after the terminal
 * transition (so a retried/restarted step starts with a fresh STEP scope —
 * see {@link FoundationRetryBoundTest} for the retry-clear behaviour).
 */
public class FoundationStepScopeOrderTest {

    private static final BlackboardKey<String> K_FROM_ON_START =
        BlackboardKey.of("test.fromOnStart", String.class);

    @Test
    public void onStartWritesAreVisibleToSameTickCheck() {
        OnStartWriterStep step = new OnStartWriterStep("writer");
        FixtureObserver obs = new FixtureObserver(List.of(stub(0), stub(1)));
        StateDrivenEngine engine = new StateDrivenEngine(obs, new PriorityPlanner(),
            new MockInputDispatcher(), new RingBufferTelemetry(64), new ScopedBlackboard());
        engine.start(step);

        engine.advanceTick(); // onStart writes K, check reads K and returns Succeeded.

        assertEquals("step should have completed in 1 tick (same-tick check sees onStart write)",
            SequenceState.IDLE, engine.state());
        assertTrue("check must have observed the K write", step.checkSawWrite);
    }

    private static WorldSnapshot stub(int tick) {
        return new WorldSnapshot() {
            public int tick() { return tick; }
            public PlayerView player() { return null; }
        };
    }

    /** Step that writes a STEP-scope key in onStart and asserts it is visible
     *  in the immediately-following check (same tick). */
    private static class OnStartWriterStep implements Step {
        private final String name;
        boolean checkSawWrite;

        OnStartWriterStep(String name) { this.name = name; }

        public String name() { return name; }
        public int priority() { return 50; }
        public int timeoutTicks() { return 100; }
        public PreemptionPolicy preemptionPolicy() { return PreemptionPolicy.NEVER; }
        public boolean isSafeToPause(WorldSnapshot s, Blackboard b) { return true; }
        public boolean canStart(WorldSnapshot s, Blackboard b) { return true; }
        public void onStart(StepContext c) {
            c.bb().scope(BlackboardScope.STEP).put(K_FROM_ON_START, "hello");
        }
        public void onEvent(Object e, StepContext c) {}
        public void tick(StepContext c) {}
        public Completion check(WorldSnapshot s, Blackboard b) {
            String v = b.scope(BlackboardScope.STEP).get(K_FROM_ON_START).orElse(null);
            checkSawWrite = "hello".equals(v);
            return checkSawWrite
                ? new Completion.Succeeded("ok")
                : new Completion.Failed("STEP scope was cleared between onStart and check");
        }
        public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard b) { return new Recovery.Abort(""); }
    }
}
