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
 * Verifies the canStart gate in runPendingOnStarts:
 * a leaf step whose canStart returns false is NOT started (no STARTED telemetry)
 * until canStart returns true.
 */
public class StateDrivenEngineCanStartGateTest {

    /**
     * GatedStep: canStart returns false for ticks 1 and 2, true from tick 3 onward.
     * check() succeeds immediately once started.
     */
    private static class GatedStep implements Step {
        private final int allowFromTick;
        int startCount = 0;

        GatedStep(int allowFromTick) { this.allowFromTick = allowFromTick; }

        @Override public String name() { return "GatedStep"; }
        @Override public int priority() { return 50; }
        @Override public int timeoutTicks() { return 20; }
        @Override public PreemptionPolicy preemptionPolicy() { return PreemptionPolicy.ALWAYS; }
        @Override public boolean isSafeToPause(WorldSnapshot s, Blackboard b) { return true; }

        @Override public boolean canStart(WorldSnapshot s, Blackboard b) {
            return s.tick() >= allowFromTick;
        }

        @Override public void onStart(StepContext c) { startCount++; }
        @Override public void onEvent(Object e, StepContext c) {}
        @Override public void tick(StepContext c) {}

        @Override public Completion check(WorldSnapshot s, Blackboard b) {
            // Succeeds immediately once started
            if (startCount > 0) return new Completion.Succeeded("done");
            return Completion.RUNNING;
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

    @Test
    public void gatedStep_notStartedUntilCanStartReturnsTrue() {
        // Ticks: 1, 2 → canStart=false; tick 3 → canStart=true
        List<WorldSnapshot> snaps = new ArrayList<>();
        for (int i = 1; i <= 5; i++) snaps.add(snap(i));

        FixtureObserver obs = new FixtureObserver(snaps);
        MockInputDispatcher disp = new MockInputDispatcher();
        RingBufferTelemetry tel = new RingBufferTelemetry(64);
        ScopedBlackboard bb = new ScopedBlackboard();

        GatedStep step = new GatedStep(3);   // canStart iff tick >= 3
        StateDrivenEngine engine = new StateDrivenEngine(obs, new PriorityPlanner(), disp, tel, bb);
        engine.start(step);

        // Tick 1: canStart=false — step must NOT be started
        engine.advanceTick();
        assertEquals("onStart must not fire on tick 1", 0, step.startCount);
        List<TelemetryRecord> recs1 = tel.tail(64);
        assertFalse("no STARTED telemetry on tick 1",
            recs1.stream().anyMatch(r -> r.event() == TelemetryRecord.Event.STARTED));
        assertEquals("engine still RUNNING on tick 1", SequenceState.RUNNING, engine.state());

        // Tick 2: canStart still false
        engine.advanceTick();
        assertEquals("onStart must not fire on tick 2", 0, step.startCount);
        assertEquals("engine still RUNNING on tick 2", SequenceState.RUNNING, engine.state());

        // Tick 3: canStart=true — step must be started and can complete
        engine.advanceTick();
        assertEquals("onStart must fire on tick 3", 1, step.startCount);
        List<TelemetryRecord> recs3 = tel.tail(64);
        assertTrue("STARTED telemetry on tick 3",
            recs3.stream().anyMatch(r -> r.event() == TelemetryRecord.Event.STARTED));
        // Step's check() returns Succeeded immediately after onStart, so engine should be IDLE
        assertEquals("engine IDLE after step completes on tick 3", SequenceState.IDLE, engine.state());
    }
}
