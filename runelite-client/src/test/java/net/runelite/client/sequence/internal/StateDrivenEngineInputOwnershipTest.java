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
import net.runelite.client.sequence.dispatch.InputOwnership;
import net.runelite.client.sequence.dispatch.MockInputDispatcher;
import net.runelite.client.sequence.telemetry.RingBufferTelemetry;
import net.runelite.client.sequence.telemetry.TelemetryRecord;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Verifies that the input-ownership mid-sequence guard works:
 * when the lease is lost externally before / during a tick, the engine:
 * - does NOT dispatch any actions to the dispatcher
 * - transitions to FAILED
 * - records a telemetry record containing "input ownership lost mid-sequence"
 */
public class StateDrivenEngineInputOwnershipTest {

    /** A step that enqueues a walkTo on every tick() call, never completes. */
    private static class WalkingStep implements Step {
        @Override public String name() { return "WalkingStep"; }
        @Override public int priority() { return 50; }
        @Override public int timeoutTicks() { return 20; }
        @Override public PreemptionPolicy preemptionPolicy() { return PreemptionPolicy.ALWAYS; }
        @Override public boolean isSafeToPause(WorldSnapshot s, Blackboard b) { return true; }
        @Override public boolean canStart(WorldSnapshot s, Blackboard b) { return true; }
        @Override public void onStart(StepContext c) {}
        @Override public void onEvent(Object e, StepContext c) {}

        @Override
        public void tick(StepContext c) {
            c.actions().walkTo(new WorldPoint(3220, 3218, 0));
        }

        @Override
        public Completion check(WorldSnapshot s, Blackboard b) {
            return Completion.RUNNING;
        }

        @Override
        public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard b) {
            return new Recovery.Abort(f.reason());
        }
    }

    /** Always Running with no actions; used when we just need a long-lived step. */
    private static class RunForeverStep implements Step {
        private final String name;
        RunForeverStep(String n) { this.name = n; }
        @Override public String name() { return name; }
        @Override public int priority() { return 50; }
        @Override public int timeoutTicks() { return 1000; }
        @Override public PreemptionPolicy preemptionPolicy() { return PreemptionPolicy.NEVER; }
        @Override public boolean isSafeToPause(WorldSnapshot s, Blackboard b) { return true; }
        @Override public boolean canStart(WorldSnapshot s, Blackboard b) { return true; }
        @Override public void onStart(StepContext c) {}
        @Override public void onEvent(Object e, StepContext c) {}
        @Override public void tick(StepContext c) {}
        @Override public Completion check(WorldSnapshot s, Blackboard b) { return Completion.RUNNING; }
        @Override public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard b) {
            return new Recovery.Abort("");
        }
    }

    private static WorldSnapshot snap(int tick) {
        return new WorldSnapshot() {
            @Override public int tick() { return tick; }
            @Override public PlayerView player() {
                return new PlayerView() {
                    @Override public WorldPoint worldLocation() { return new WorldPoint(0, 0, 0); }
                    @Override public int animation() { return -1; }
                    @Override public boolean isIdle() { return true; }
                    @Override public int health() { return 99; }
                    @Override public int maxHealth() { return 99; }
                };
            }
        };
    }

    private static WorldSnapshot stub(int tick) {
        return new WorldSnapshot() {
            @Override public int tick() { return tick; }
            @Override public PlayerView player() { return null; }
        };
    }

    @Test
    public void engineFailsWhenOwnershipLostMidSequence() {
        WorldSnapshot s0 = stub(0);
        WorldSnapshot s1 = stub(1);
        WorldSnapshot s2 = stub(2);
        FixtureObserver obs = new FixtureObserver(List.of(s0, s1, s2));
        MockInputDispatcher disp = new MockInputDispatcher();
        RingBufferTelemetry tel = new RingBufferTelemetry(64);
        ScopedBlackboard bb = new ScopedBlackboard();

        InputOwnership ownership = new InputOwnership();
        ownership.tryAcquire("test-owner");

        StateDrivenEngine engine = new StateDrivenEngine(obs, new PriorityPlanner(), disp, tel, bb);
        engine.setInputOwnership(ownership, "test-owner");
        engine.start(new RunForeverStep("forever"));

        engine.advanceTick(); // started, running
        // Externally yank the lease away
        ownership.release("test-owner");
        ownership.tryAcquire("someone-else");

        engine.advanceTick(); // engine should detect lost ownership and fail

        assertEquals(SequenceState.FAILED, engine.state());
        boolean foundOwnershipDiagnostic = tel.tail(64).stream()
            .anyMatch(r -> r.payload() != null && r.payload().contains("input ownership lost"));
        assertTrue("expected telemetry mentioning lost ownership, got: " + tel.tail(64),
            foundOwnershipDiagnostic);
    }

    @Test
    public void lostLease_preventsDispatch_andEngineFailsWithDiagnostic() {
        List<WorldSnapshot> snaps = new ArrayList<>();
        for (int i = 1; i <= 5; i++) snaps.add(snap(i));

        FixtureObserver obs = new FixtureObserver(snaps);
        MockInputDispatcher disp = new MockInputDispatcher();
        RingBufferTelemetry tel = new RingBufferTelemetry(64);
        ScopedBlackboard bb = new ScopedBlackboard();

        String ownerToken = "cooking-script";
        InputOwnership ownership = new InputOwnership();
        assertTrue("initial acquire must succeed", ownership.tryAcquire(ownerToken));

        StateDrivenEngine engine = new StateDrivenEngine(obs, new PriorityPlanner(), disp, tel, bb);
        engine.setInputOwnership(ownership, ownerToken);
        engine.start(new WalkingStep());

        // Tick 1: lease held — dispatch should proceed normally
        engine.advanceTick();
        assertEquals("engine still RUNNING after tick 1", SequenceState.RUNNING, engine.state());
        assertFalse("dispatcher must have received the walk action on tick 1", disp.getRequests().isEmpty());
        disp.clear();

        // Externally steal the lease before tick 2
        assertTrue("simulated external release", ownership.release(ownerToken));
        assertFalse("lease must now be free", ownership.isOwner(ownerToken));

        // Tick 2: lease is gone — engine must fail without dispatching
        engine.advanceTick();

        assertEquals("engine must be FAILED after ownership loss", SequenceState.FAILED, engine.state());
        assertTrue("dispatcher must receive NO requests when ownership is lost",
            disp.getRequests().isEmpty());

        // Verify some telemetry record mentions "input ownership lost mid-sequence"
        List<TelemetryRecord> records = tel.tail(64);
        boolean hasOwnershipRecord = records.stream()
            .anyMatch(r -> r.payload() != null
                && r.payload().contains("input ownership lost mid-sequence"));
        assertTrue("telemetry must record 'input ownership lost mid-sequence'", hasOwnershipRecord);
    }

    @Test
    public void withOwnership_heldLeaseDoesNotPreventDispatch() {
        List<WorldSnapshot> snaps = new ArrayList<>();
        for (int i = 1; i <= 3; i++) snaps.add(snap(i));

        FixtureObserver obs = new FixtureObserver(snaps);
        MockInputDispatcher disp = new MockInputDispatcher();
        RingBufferTelemetry tel = new RingBufferTelemetry(64);
        ScopedBlackboard bb = new ScopedBlackboard();

        String ownerToken = "cooking-script";
        InputOwnership ownership = new InputOwnership();
        ownership.tryAcquire(ownerToken);

        StateDrivenEngine engine = new StateDrivenEngine(obs, new PriorityPlanner(), disp, tel, bb);
        engine.setInputOwnership(ownership, ownerToken);
        engine.start(new WalkingStep());

        engine.advanceTick();
        assertEquals("engine still RUNNING with valid lease", SequenceState.RUNNING, engine.state());
        assertFalse("dispatch must proceed with valid lease", disp.getRequests().isEmpty());
    }

    @Test
    public void withNoOwnership_dispatchProceeds() {
        // No setInputOwnership call — ownership check must be skipped entirely
        List<WorldSnapshot> snaps = new ArrayList<>();
        for (int i = 1; i <= 3; i++) snaps.add(snap(i));

        FixtureObserver obs = new FixtureObserver(snaps);
        MockInputDispatcher disp = new MockInputDispatcher();
        RingBufferTelemetry tel = new RingBufferTelemetry(64);
        ScopedBlackboard bb = new ScopedBlackboard();

        StateDrivenEngine engine = new StateDrivenEngine(obs, new PriorityPlanner(), disp, tel, bb);
        // No setInputOwnership — ownership is null
        engine.start(new WalkingStep());

        engine.advanceTick();
        assertEquals("engine RUNNING without ownership config", SequenceState.RUNNING, engine.state());
        assertFalse("dispatch must proceed with no ownership config", disp.getRequests().isEmpty());
    }
}
