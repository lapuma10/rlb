package net.runelite.client.plugins.recorder.walker;

import javax.annotation.Nullable;

/**
 * Path executor contract. Implementations consume a {@link PathSpec} via
 * {@link #tick} and drive the player toward the spec's waypoints. Different
 * impls may humanize input differently (deterministic clicks vs replayed
 * mouse-trace simulation, planned for v3).
 *
 * <p>Contract is {@link #tick(PathSpec)} only. How the impl tracks step
 * progress, last-clicked-transport bookkeeping, click cadence, etc. between
 * ticks is its own internal concern — the interface does NOT require any
 * specific bookkeeping.
 */
public interface Walker
{
    enum Status { IN_PROGRESS, ARRIVED, STUCK, ERROR }

    enum InternalState { IDLE, WALKING, AT_TRANSPORT, CROSSING, ARRIVED, STUCK }

    Status tick(PathSpec spec) throws InterruptedException;

    /** Reset all per-spec state. */
    void reset();

    /** Current internal state for diagnostics / overlays. */
    InternalState state();

    /** Index of the active waypoint in the current spec. */
    int currentStepIndex();

    /** The spec currently being executed, or null before the first tick. */
    @Nullable PathSpec currentSpec();
}
