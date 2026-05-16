package net.runelite.client.plugins.recorder.nav.v2.qc.contracts;

import java.util.Optional;
import net.runelite.api.coords.WorldPoint;

/** Lane-6 scaffolding mirror of spec §3 {@code ExecutorTickResult}.
 *  Lane 5 owns the production type. Lane 6 correlates per-tick logs by
 *  {@link #debugTraceId()}. */
public interface ExecutorTickResult
{
    ExecutorResult result();
    Optional<ReplanReason> replanReason();
    Optional<WorldPoint> playerAt();
    Optional<Waypoint> currentWaypoint();
    Optional<TransportLeg> currentTransport();
    String debugTraceId();
}
