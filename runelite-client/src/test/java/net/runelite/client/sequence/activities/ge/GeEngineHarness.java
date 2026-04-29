package net.runelite.client.sequence.activities.ge;

import java.util.ArrayList;
import java.util.List;
import net.runelite.client.sequence.SequenceManager;
import net.runelite.client.sequence.SequenceState;
import net.runelite.client.sequence.Step;
import net.runelite.client.sequence.WorldSnapshot;
import net.runelite.client.sequence.dispatch.MockInputDispatcher;
import net.runelite.client.sequence.internal.FixtureObserver;
import net.runelite.client.sequence.telemetry.RingBufferTelemetry;
import net.runelite.client.sequence.telemetry.TelemetryRecord;

/**
 * End-to-end test harness for GE step / factory tests. Wraps a
 * {@link SequenceManager} with a {@link FixtureObserver} over a queued
 * snapshot list and a {@link MockInputDispatcher}.
 *
 * <p>Usage:
 * <pre>{@code
 *   GeEngineHarness h = new GeEngineHarness();
 *   h.queue(snap0, snap1, snap2);
 *   h.run(rootStep);
 *   h.advance(3);
 *   assertEquals(SequenceState.IDLE, h.state());
 * }</pre>
 */
public final class GeEngineHarness {

    private final List<WorldSnapshot> queued = new ArrayList<>();
    private final MockInputDispatcher dispatcher = new MockInputDispatcher();
    private final RingBufferTelemetry telemetry = new RingBufferTelemetry(256);
    private SequenceManager manager;

    public GeEngineHarness queue(WorldSnapshot... snaps) {
        for (WorldSnapshot s : snaps) queued.add(s);
        return this;
    }

    public GeEngineHarness queue(List<WorldSnapshot> snaps) {
        queued.addAll(snaps);
        return this;
    }

    /** Build the manager and start the run with {@code root}. */
    public void run(Step root) {
        if (manager != null) throw new IllegalStateException("harness already started");
        manager = SequenceManager.withDefaults();
        manager.setObserver(new FixtureObserver(queued));
        manager.setDispatcher(dispatcher);
        manager.setTelemetry(telemetry);
        manager.run(root);
    }

    /** Advance the engine by N ticks. */
    public void advance(int n) {
        if (manager == null) throw new IllegalStateException("call run() first");
        for (int i = 0; i < n; i++) {
            if (manager.state() != SequenceState.RUNNING) break;
            manager.getEngine().advanceTick();
        }
    }

    public SequenceState state()              { return manager == null ? SequenceState.IDLE : manager.state(); }
    public MockInputDispatcher dispatcher()   { return dispatcher; }
    public RingBufferTelemetry telemetry()    { return telemetry; }
    public SequenceManager manager()          { return manager; }

    public List<TelemetryRecord> recentTelemetry() { return telemetry.tail(64); }
}
