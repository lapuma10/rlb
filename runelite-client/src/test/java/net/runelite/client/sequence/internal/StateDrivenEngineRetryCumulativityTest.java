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
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * Verifies that Retry(N) produces exactly N+1 total onStart calls
 * (1 initial + N retries) before the engine transitions to FAILED.
 */
public class StateDrivenEngineRetryCumulativityTest {

    /**
     * AlwaysFailStep: check() always returns Failed. onFailure returns Retry(maxAttempts).
     * Counts how many times onStart is called.
     */
    private static class AlwaysFailStep implements Step {
        final AtomicInteger startCount = new AtomicInteger(0);
        final int maxAttempts;

        AlwaysFailStep(int maxAttempts) { this.maxAttempts = maxAttempts; }

        @Override public String name() { return "AlwaysFail"; }
        @Override public int priority() { return 50; }
        @Override public int timeoutTicks() { return 200; }
        @Override public PreemptionPolicy preemptionPolicy() { return PreemptionPolicy.ALWAYS; }
        @Override public boolean isSafeToPause(WorldSnapshot s, Blackboard b) { return true; }
        @Override public boolean canStart(WorldSnapshot s, Blackboard b) { return true; }

        @Override public void onStart(StepContext c) { startCount.incrementAndGet(); }
        @Override public void onEvent(Object e, StepContext c) {}
        @Override public void tick(StepContext c) {}

        @Override public Completion check(WorldSnapshot s, Blackboard b) {
            return new Completion.Failed("always fails");
        }

        @Override public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard b) {
            return new Recovery.Retry(maxAttempts);
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

    private StateDrivenEngine makeEngine(RingBufferTelemetry tel, List<WorldSnapshot> snaps) {
        FixtureObserver obs = new FixtureObserver(snaps);
        MockInputDispatcher disp = new MockInputDispatcher();
        ScopedBlackboard bb = new ScopedBlackboard();
        return new StateDrivenEngine(obs, new PriorityPlanner(), disp, tel, bb);
    }

    @Test
    public void retry1_produces2StartsThenFailed() {
        // Retry(1) means 1 retry allowed = 2 total starts
        List<WorldSnapshot> snaps = new ArrayList<>();
        for (int i = 1; i <= 20; i++) snaps.add(snap(i));

        RingBufferTelemetry tel = new RingBufferTelemetry(128);
        AlwaysFailStep step = new AlwaysFailStep(1);
        StateDrivenEngine engine = makeEngine(tel, snaps);
        engine.start(step);

        // Drive ticks until engine terminates (FAILED or IDLE) — max 20
        for (int i = 0; i < 20 && engine.state() == SequenceState.RUNNING; i++) {
            engine.advanceTick();
        }

        assertEquals("Retry(1) must allow exactly 2 total onStart calls", 2, step.startCount.get());
        assertEquals("engine must be FAILED after Retry(1) exhausted", SequenceState.FAILED, engine.state());
    }

    @Test
    public void retry3_produces4StartsThenFailed() {
        // Retry(3) means 3 retries allowed = 4 total starts
        List<WorldSnapshot> snaps = new ArrayList<>();
        for (int i = 1; i <= 20; i++) snaps.add(snap(i));

        RingBufferTelemetry tel = new RingBufferTelemetry(128);
        AlwaysFailStep step = new AlwaysFailStep(3);
        StateDrivenEngine engine = makeEngine(tel, snaps);
        engine.start(step);

        for (int i = 0; i < 20 && engine.state() == SequenceState.RUNNING; i++) {
            engine.advanceTick();
        }

        assertEquals("Retry(3) must allow exactly 4 total onStart calls", 4, step.startCount.get());
        assertEquals("engine must be FAILED after Retry(3) exhausted", SequenceState.FAILED, engine.state());
    }
}
