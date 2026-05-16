package net.runelite.client.plugins.recorder.nav.v2;

import java.util.Optional;
import net.runelite.api.coords.WorldPoint;

/** Spec §3 (Lane 1 contract; Lane 5 file owner): typed result of one
 *  {@link V2Executor#tick()} call. Replaces the prior cross-layer flag
 *  ({@code wantsReplanFromHere}) and out-of-band error fields with one
 *  immutable record carrying everything Lane 6 needs to correlate a
 *  per-tick trace.
 *
 *  <p>{@link #debugTraceId} is required (non-null, non-empty). Lane 6's
 *  {@code RouteTraceRecorder} groups per-tick log lines by this id. */
public interface ExecutorTickResult
{
    ExecutorResult result();
    Optional<ReplanReason> replanReason();
    Optional<WorldPoint> playerAt();
    Optional<Waypoint> currentWaypoint();
    Optional<TransportLeg> currentTransport();
    String debugTraceId();

    /** Optional typed correction request — emitted when an executor
     *  observed transport-state-mismatch (planned to-tile != actual
     *  to-tile). The navigator (NOT the executor) decides whether to
     *  apply the correction. */
    default Optional<TransportCorrectionRequest> transportCorrection() { return Optional.empty(); }
}
