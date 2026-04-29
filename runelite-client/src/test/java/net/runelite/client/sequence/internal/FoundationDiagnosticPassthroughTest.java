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
import net.runelite.client.sequence.affordance.BlockReason;
import net.runelite.client.sequence.affordance.DiagnosticReason;
import net.runelite.client.sequence.affordance.GeBlockReason;
import net.runelite.client.sequence.blackboard.Blackboard;
import net.runelite.client.sequence.blackboard.ScopedBlackboard;
import net.runelite.client.sequence.dispatch.MockInputDispatcher;
import net.runelite.client.sequence.telemetry.RingBufferTelemetry;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Foundation test: when a step returns {@code Completion.failed(reason)} with
 * a typed {@link DiagnosticReason}, the {@link Failure} delivered to
 * {@code onFailure} carries that reason on its {@code diagnostic} field — not
 * a plain {@code fromCheck} string.
 */
public class FoundationDiagnosticPassthroughTest {

    @Test
    public void typedDiagnosticReachesOnFailure() {
        AtomicReference<Failure> seen = new AtomicReference<>();
        DiagnosticReason expected = new GeBlockReason.GeNotOpen();

        Step step = new Step() {
            public String name() { return "diag"; }
            public int priority() { return 50; }
            public int timeoutTicks() { return 100; }
            public PreemptionPolicy preemptionPolicy() { return PreemptionPolicy.NEVER; }
            public boolean isSafeToPause(WorldSnapshot s, Blackboard b) { return true; }
            public boolean canStart(WorldSnapshot s, Blackboard b) { return true; }
            public void onStart(StepContext c) {}
            public void onEvent(Object e, StepContext c) {}
            public void tick(StepContext c) {}
            public Completion check(WorldSnapshot s, Blackboard b) {
                return Completion.failed(expected);
            }
            public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard b) {
                seen.set(f);
                return new Recovery.Abort("typed");
            }
        };

        FixtureObserver obs = new FixtureObserver(List.of(stub(0), stub(1)));
        StateDrivenEngine engine = new StateDrivenEngine(obs, new PriorityPlanner(),
            new MockInputDispatcher(), new RingBufferTelemetry(64), new ScopedBlackboard());
        engine.start(step);
        engine.advanceTick();

        Failure f = seen.get();
        assertNotNull("onFailure should have been called", f);
        assertNotNull("Failure.diagnostic should be populated when Completion.Failed carries one",
            f.diagnostic());
        assertSame("diagnostic should be the same instance the step returned",
            expected, f.diagnostic());
        assertTrue("BlockReason / GeBlockReason both flow through DiagnosticReason",
            f.diagnostic() instanceof GeBlockReason.GeNotOpen);
    }

    @Test
    public void blockReasonAlsoFlows() {
        AtomicReference<Failure> seen = new AtomicReference<>();
        DiagnosticReason expected = new BlockReason.PinKeypadUp();

        Step step = new Step() {
            public String name() { return "diag2"; }
            public int priority() { return 50; }
            public int timeoutTicks() { return 100; }
            public PreemptionPolicy preemptionPolicy() { return PreemptionPolicy.NEVER; }
            public boolean isSafeToPause(WorldSnapshot s, Blackboard b) { return true; }
            public boolean canStart(WorldSnapshot s, Blackboard b) { return true; }
            public void onStart(StepContext c) {}
            public void onEvent(Object e, StepContext c) {}
            public void tick(StepContext c) {}
            public Completion check(WorldSnapshot s, Blackboard b) {
                return Completion.failed(expected);
            }
            public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard b) {
                seen.set(f);
                return new Recovery.Abort("typed");
            }
        };

        FixtureObserver obs = new FixtureObserver(List.of(stub(0), stub(1)));
        StateDrivenEngine engine = new StateDrivenEngine(obs, new PriorityPlanner(),
            new MockInputDispatcher(), new RingBufferTelemetry(64), new ScopedBlackboard());
        engine.start(step);
        engine.advanceTick();

        assertSame(expected, seen.get().diagnostic());
    }

    private static WorldSnapshot stub(int tick) {
        return new WorldSnapshot() {
            public int tick() { return tick; }
            public PlayerView player() { return null; }
        };
    }
}
