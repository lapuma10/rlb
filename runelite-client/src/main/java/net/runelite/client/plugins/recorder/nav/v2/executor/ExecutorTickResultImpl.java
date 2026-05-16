package net.runelite.client.plugins.recorder.nav.v2.executor;

import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nullable;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.nav.v2.ExecutorResult;
import net.runelite.client.plugins.recorder.nav.v2.ExecutorTickResult;
import net.runelite.client.plugins.recorder.nav.v2.ReplanReason;
import net.runelite.client.plugins.recorder.nav.v2.TransportCorrectionRequest;
import net.runelite.client.plugins.recorder.nav.v2.TransportLeg;
import net.runelite.client.plugins.recorder.nav.v2.Waypoint;

/** Concrete {@link ExecutorTickResult} impl. Built by the executor per
 *  tick; carried back to {@link
 *  net.runelite.client.plugins.recorder.nav.v2.V2Navigator} which then
 *  decides whether to advance, replan, or fail.
 *
 *  <p>{@link #debugTraceId} is always non-null/non-empty (per spec §3
 *  contract). Lane 6's {@code RouteTraceRecorder} groups per-tick log
 *  lines by it. */
public final class ExecutorTickResultImpl implements ExecutorTickResult
{
    private final ExecutorResult result;
    private final Optional<ReplanReason> replanReason;
    private final Optional<WorldPoint> playerAt;
    private final Optional<Waypoint> currentWaypoint;
    private final Optional<TransportLeg> currentTransport;
    private final String debugTraceId;
    private final Optional<TransportCorrectionRequest> transportCorrection;

    private ExecutorTickResultImpl(Builder b)
    {
        this.result = b.result;
        this.replanReason = Optional.ofNullable(b.replanReason);
        this.playerAt = Optional.ofNullable(b.playerAt);
        this.currentWaypoint = Optional.ofNullable(b.currentWaypoint);
        this.currentTransport = Optional.ofNullable(b.currentTransport);
        this.debugTraceId = b.debugTraceId == null || b.debugTraceId.isEmpty()
            ? UUID.randomUUID().toString() : b.debugTraceId;
        this.transportCorrection = Optional.ofNullable(b.transportCorrection);
    }

    @Override public ExecutorResult result() { return result; }
    @Override public Optional<ReplanReason> replanReason() { return replanReason; }
    @Override public Optional<WorldPoint> playerAt() { return playerAt; }
    @Override public Optional<Waypoint> currentWaypoint() { return currentWaypoint; }
    @Override public Optional<TransportLeg> currentTransport() { return currentTransport; }
    @Override public String debugTraceId() { return debugTraceId; }
    @Override public Optional<TransportCorrectionRequest> transportCorrection()
    { return transportCorrection; }

    public static Builder builder() { return new Builder(); }

    /** Quick factory: in-progress result with a player loc and a trace id. */
    public static ExecutorTickResult inProgress(@Nullable WorldPoint player,
                                                @Nullable Waypoint cur,
                                                String traceId)
    {
        return builder()
            .result(ExecutorResult.IN_PROGRESS)
            .playerAt(player)
            .currentWaypoint(cur)
            .debugTraceId(traceId)
            .build();
    }

    public static final class Builder
    {
        private ExecutorResult result = ExecutorResult.IN_PROGRESS;
        @Nullable private ReplanReason replanReason;
        @Nullable private WorldPoint playerAt;
        @Nullable private Waypoint currentWaypoint;
        @Nullable private TransportLeg currentTransport;
        @Nullable private String debugTraceId;
        @Nullable private TransportCorrectionRequest transportCorrection;

        public Builder result(ExecutorResult r) { this.result = r; return this; }
        public Builder replanReason(@Nullable ReplanReason r) { this.replanReason = r; return this; }
        public Builder playerAt(@Nullable WorldPoint p) { this.playerAt = p; return this; }
        public Builder currentWaypoint(@Nullable Waypoint w) { this.currentWaypoint = w; return this; }
        public Builder currentTransport(@Nullable TransportLeg t) { this.currentTransport = t; return this; }
        public Builder debugTraceId(@Nullable String id) { this.debugTraceId = id; return this; }
        public Builder transportCorrection(@Nullable TransportCorrectionRequest c)
        { this.transportCorrection = c; return this; }

        public ExecutorTickResultImpl build() { return new ExecutorTickResultImpl(this); }
    }
}
