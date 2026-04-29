package net.runelite.client.sequence.activities.banking;

import net.runelite.client.sequence.*;
import net.runelite.client.sequence.dispatch.MockInputDispatcher;
import net.runelite.client.sequence.internal.Observer;
import net.runelite.client.sequence.telemetry.Telemetry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Test harness for banking step tests.
 *
 * <p>Wraps {@link SequenceManager#withDefaults()} with:
 * <ul>
 *   <li>A mutable-queue {@link Observer} that replays queued {@link WorldSnapshot}s in order
 *       (clamped to the last snapshot once the queue is exhausted — same semantics as
 *       {@link net.runelite.client.sequence.internal.FixtureObserver}).</li>
 *   <li>A {@link MockInputDispatcher} so tests can assert dispatched actions.</li>
 * </ul>
 */
public final class BankingEngineHarness {

    private final SequenceManager manager;
    private final MockInputDispatcher dispatcher;
    private final QueuedObserver observer;

    public BankingEngineHarness() {
        dispatcher = new MockInputDispatcher();
        observer   = new QueuedObserver();

        manager = SequenceManager.withDefaults();
        manager.setDispatcher(dispatcher);
        manager.setObserver(observer);
        // engine auto-builds inside SequenceManager.rebuildEngineIfReady()
    }

    /** Append one or more snapshots to the observer's playback queue. */
    public void queue(WorldSnapshot... snaps) {
        observer.addAll(Arrays.asList(snaps));
    }

    /** Start the engine with the given root step (synchronous — runs on calling thread). */
    public void run(Step rootStep) {
        manager.run(rootStep);
    }

    /** Advance the engine by {@code n} ticks. */
    public void advance(int n) {
        manager.getEngine().advanceTicks(n);
    }

    /** Current engine state. */
    public SequenceState state() {
        return manager.state();
    }

    /** The mock dispatcher — use to assert dispatched actions. */
    public MockInputDispatcher dispatcher() {
        return dispatcher;
    }

    /** The underlying manager — use for telemetry subscriptions, etc. */
    public SequenceManager manager() {
        return manager;
    }

    /** The manager's telemetry. */
    public Telemetry telemetry() {
        return manager.getTelemetry();
    }

    // -------------------------------------------------------------------------
    // Internal: mutable observer queue
    // -------------------------------------------------------------------------

    /** Mutable observer backed by a list; clamps to the last snapshot when exhausted. */
    private static final class QueuedObserver implements Observer {
        private final List<WorldSnapshot> snapshots = new ArrayList<>();
        private int idx = 0;

        void addAll(List<WorldSnapshot> more) {
            snapshots.addAll(more);
        }

        @Override
        public WorldSnapshot snapshot(int currentTick) {
            if (snapshots.isEmpty()) {
                // Return a bare-minimum snapshot so the engine doesn't NPE
                return new WorldSnapshot() {
                    @Override public int tick()           { return currentTick; }
                    @Override public PlayerView player()  { return null; }
                };
            }
            WorldSnapshot s = snapshots.get(Math.min(idx, snapshots.size() - 1));
            if (idx < snapshots.size() - 1) idx++;
            return s;
        }

        void reset() {
            idx = 0;
            snapshots.clear();
        }
    }
}
