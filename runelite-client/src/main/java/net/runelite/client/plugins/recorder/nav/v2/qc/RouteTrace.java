package net.runelite.client.plugins.recorder.nav.v2.qc;

import java.util.List;
import java.util.Optional;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.nav.v2.qc.contracts.ExecutorResult;
import net.runelite.client.plugins.recorder.nav.v2.qc.contracts.ReplanReason;
import net.runelite.client.plugins.recorder.nav.v2.qc.contracts.TransportLeg;
import net.runelite.client.plugins.recorder.nav.v2.qc.contracts.Waypoint;

/** One executor-tick record. JSONL row emitted by
 *  {@link RouteTraceRecorder}. Format mirrors the per-tick log shape
 *  in spec §5 "Required per-tick debug log" so Lane 5's debug output
 *  and Lane 6's trace store agree on field names. */
public final class RouteTrace
{
    public final String tickId;
    public final long tickEpochMs;
    public final WorldPoint playerAt;
    public final Optional<Waypoint> currentWaypoint;
    public final Optional<TransportLeg> currentTransport;
    public final List<WorldPoint> candidatesConsidered;
    public final List<Rejected> candidatesRejected;
    public final Optional<WorldPoint> candidateChosen;
    public final boolean sidestepUsed;
    public final ExecutorResult result;
    public final Optional<ReplanReason> replanReason;

    public RouteTrace(String tickId,
                      long tickEpochMs,
                      WorldPoint playerAt,
                      Optional<Waypoint> currentWaypoint,
                      Optional<TransportLeg> currentTransport,
                      List<WorldPoint> candidatesConsidered,
                      List<Rejected> candidatesRejected,
                      Optional<WorldPoint> candidateChosen,
                      boolean sidestepUsed,
                      ExecutorResult result,
                      Optional<ReplanReason> replanReason)
    {
        this.tickId = tickId;
        this.tickEpochMs = tickEpochMs;
        this.playerAt = playerAt;
        this.currentWaypoint = currentWaypoint;
        this.currentTransport = currentTransport;
        this.candidatesConsidered = List.copyOf(candidatesConsidered);
        this.candidatesRejected = List.copyOf(candidatesRejected);
        this.candidateChosen = candidateChosen;
        this.sidestepUsed = sidestepUsed;
        this.result = result;
        this.replanReason = replanReason;
    }

    /** One rejected candidate + the reason it was rejected. Reason is a
     *  free-form string sourced from the executor's debug log (e.g.
     *  "collision_blocked", "predicate:NotDangerousArea",
     *  "off_corridor"). Lane 6 does not enforce the vocabulary — Lane 5
     *  picks it and ships it. */
    public static final class Rejected
    {
        public final WorldPoint tile;
        public final String reason;

        public Rejected(WorldPoint tile, String reason)
        {
            this.tile = tile;
            this.reason = reason;
        }
    }
}
