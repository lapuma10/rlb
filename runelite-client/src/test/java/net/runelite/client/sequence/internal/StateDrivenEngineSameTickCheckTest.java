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
import net.runelite.client.sequence.dispatch.MockInputDispatcher;
import net.runelite.client.sequence.telemetry.RingBufferTelemetry;
import net.runelite.client.sequence.telemetry.TelemetryRecord;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Regression test: the engine runs runPendingOnStarts immediately before the
 * leaf-check loop on the same tick. A step that is already satisfied at start
 * (onStart writes to STEP scope, check reads it and returns Succeeded) must
 * reach IDLE in exactly 1 advanceTick() call.
 */
public class StateDrivenEngineSameTickCheckTest {

    static final BlackboardKey<String> K_OUTCOME = BlackboardKey.of("outcome", String.class);

    /**
     * AlreadySatisfiedStep: onStart writes K_OUTCOME="done" to STEP scope.
     * check() reads K_OUTCOME and returns Succeeded if present.
     * This tests that check() is called in the same advanceTick() as onStart().
     */
    private static class AlreadySatisfiedStep implements Step {
        @Override public String name() { return "AlreadySatisfied"; }
        @Override public int priority() { return 50; }
        @Override public int timeoutTicks() { return 20; }
        @Override public PreemptionPolicy preemptionPolicy() { return PreemptionPolicy.ALWAYS; }
        @Override public boolean isSafeToPause(WorldSnapshot s, Blackboard b) { return true; }
        @Override public boolean canStart(WorldSnapshot s, Blackboard b) { return true; }

        @Override public void onStart(StepContext c) {
            c.bb().scope(BlackboardScope.STEP).put(K_OUTCOME, "done");
        }

        @Override public void onEvent(Object e, StepContext c) {}
        @Override public void tick(StepContext c) {}

        @Override public Completion check(WorldSnapshot s, Blackboard b) {
            if (b.scope(BlackboardScope.STEP).get(K_OUTCOME).isPresent()) {
                return new Completion.Succeeded("already-satisfied");
            }
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
    public void alreadySatisfiedStep_reachesIdleIn1Tick() {
        List<WorldSnapshot> snaps = new ArrayList<>();
        for (int i = 1; i <= 5; i++) snaps.add(snap(i));

        FixtureObserver obs = new FixtureObserver(snaps);
        MockInputDispatcher disp = new MockInputDispatcher();
        RingBufferTelemetry tel = new RingBufferTelemetry(64);
        ScopedBlackboard bb = new ScopedBlackboard();

        StateDrivenEngine engine = new StateDrivenEngine(obs, new PriorityPlanner(), disp, tel, bb);
        engine.start(new AlreadySatisfiedStep());

        // Exactly 1 tick to go from RUNNING → IDLE
        engine.advanceTick();

        assertEquals("already-satisfied step must complete in 1 tick", SequenceState.IDLE, engine.state());

        List<TelemetryRecord> records = tel.tail(64);
        assertTrue("STARTED event must appear",
            records.stream().anyMatch(r -> r.event() == TelemetryRecord.Event.STARTED));
        assertTrue("SUCCEEDED event must appear in same tick",
            records.stream().anyMatch(r -> r.event() == TelemetryRecord.Event.SUCCEEDED));
    }
}
