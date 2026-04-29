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
import net.runelite.client.sequence.affordance.BlockReason;
import net.runelite.client.sequence.blackboard.Blackboard;
import net.runelite.client.sequence.blackboard.ScopedBlackboard;
import net.runelite.client.sequence.dispatch.MockInputDispatcher;
import net.runelite.client.sequence.telemetry.RingBufferTelemetry;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Verifies that when a step returns Completion.Failed with a non-null DiagnosticReason,
 * the engine passes it through as Failure.diagnostic (not null) when calling onFailure.
 * The Failure reaching onFailure must have diagnostic instanceof BlockReason.WithdrawNoOp.
 */
public class StateDrivenEngineDiagnosticPassthroughTest {

    private static class WithdrawNoOpStep implements Step {
        final AtomicReference<Failure> capturedFailure = new AtomicReference<>();

        @Override public String name() { return "WithdrawNoOpStep"; }
        @Override public int priority() { return 50; }
        @Override public int timeoutTicks() { return 100; }
        @Override public PreemptionPolicy preemptionPolicy() { return PreemptionPolicy.ALWAYS; }
        @Override public boolean isSafeToPause(WorldSnapshot s, Blackboard b) { return true; }
        @Override public boolean canStart(WorldSnapshot s, Blackboard b) { return true; }
        @Override public void onStart(StepContext c) {}
        @Override public void onEvent(Object e, StepContext c) {}
        @Override public void tick(StepContext c) {}

        @Override public Completion check(WorldSnapshot s, Blackboard b) {
            // Return a typed diagnostic failure: item 995, 6 ticks elapsed
            return Completion.failed(new BlockReason.WithdrawNoOp(995, 6));
        }

        @Override public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard b) {
            capturedFailure.set(f);
            return new Recovery.Abort("propagate");
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

    @Test
    public void diagnosticReason_survivesIntoOnFailure() {
        List<WorldSnapshot> snaps = new ArrayList<>();
        for (int i = 1; i <= 5; i++) snaps.add(snap(i));

        FixtureObserver obs = new FixtureObserver(snaps);
        MockInputDispatcher disp = new MockInputDispatcher();
        RingBufferTelemetry tel = new RingBufferTelemetry(64);
        ScopedBlackboard bb = new ScopedBlackboard();

        WithdrawNoOpStep step = new WithdrawNoOpStep();
        StateDrivenEngine engine = new StateDrivenEngine(obs, new PriorityPlanner(), disp, tel, bb);
        engine.start(step);

        engine.advanceTick();  // onStart + check → Failed with WithdrawNoOp

        // Engine should be FAILED (Abort recovery)
        assertEquals("engine must be FAILED", SequenceState.FAILED, engine.state());

        Failure f = step.capturedFailure.get();
        assertNotNull("onFailure must have been called", f);
        assertNotNull("Failure.diagnostic must not be null", f.diagnostic());
        assertTrue("diagnostic must be WithdrawNoOp", f.diagnostic() instanceof BlockReason.WithdrawNoOp);

        BlockReason.WithdrawNoOp noOp = (BlockReason.WithdrawNoOp) f.diagnostic();
        assertEquals("itemId must match", 995, noOp.itemId());
        assertEquals("ticks must match", 6, noOp.ticks());
    }

    @Test
    public void nullDiagnostic_usesLegacyFromCheck() {
        // A step returning Failed with no diagnostic should still produce a non-null Failure
        // with null diagnostic (legacy path preserved)
        List<WorldSnapshot> snaps = new ArrayList<>();
        for (int i = 1; i <= 5; i++) snaps.add(snap(i));

        AtomicReference<Failure> capturedFailure = new AtomicReference<>();

        Step legacyStep = new Step() {
            @Override public String name() { return "LegacyFail"; }
            @Override public int priority() { return 50; }
            @Override public int timeoutTicks() { return 100; }
            @Override public PreemptionPolicy preemptionPolicy() { return PreemptionPolicy.ALWAYS; }
            @Override public boolean isSafeToPause(WorldSnapshot s, Blackboard b) { return true; }
            @Override public boolean canStart(WorldSnapshot s, Blackboard b) { return true; }
            @Override public void onStart(StepContext c) {}
            @Override public void onEvent(Object e, StepContext c) {}
            @Override public void tick(StepContext c) {}
            @Override public Completion check(WorldSnapshot s, Blackboard b) {
                return new Completion.Failed("legacy fail, no diagnostic");
            }
            @Override public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard b) {
                capturedFailure.set(f);
                return new Recovery.Abort("propagate");
            }
        };

        FixtureObserver obs = new FixtureObserver(snaps);
        MockInputDispatcher disp = new MockInputDispatcher();
        RingBufferTelemetry tel = new RingBufferTelemetry(64);
        ScopedBlackboard bb = new ScopedBlackboard();
        StateDrivenEngine engine = new StateDrivenEngine(obs, new PriorityPlanner(), disp, tel, bb);
        engine.start(legacyStep);

        engine.advanceTick();

        assertEquals("engine must be FAILED", SequenceState.FAILED, engine.state());
        assertNotNull("onFailure must have been called", capturedFailure.get());
        assertNull("legacy path: diagnostic must be null", capturedFailure.get().diagnostic());
    }
}
