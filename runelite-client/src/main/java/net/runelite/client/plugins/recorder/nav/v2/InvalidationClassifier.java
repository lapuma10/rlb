package net.runelite.client.plugins.recorder.nav.v2;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.worldmap.TransportEdge;

/** Typed classification of failed clicks plus session-local recovery
 *  state (failure counts, blacklist, transient penalties, stale
 *  transports). Per spec section "Snapshot invalidation":
 *
 *  <ul>
 *    <li>{@link FailureClass#STATIC_COLLISION_MISMATCH} — snapshot
 *        thinks the tile is walkable; live collision says no. Mark the
 *        tile dirty so the planner avoids it. The current round mutates
 *        only this classifier's blacklist; round-2 should propagate
 *        into {@code MapStore} so persisted memory reflects the
 *        correction.</li>
 *    <li>{@link FailureClass#DYNAMIC_BLOCKER} — NPC / player standing
 *        on the tile, no static change. Apply a short-lived penalty;
 *        the world model is fine.</li>
 *    <li>{@link FailureClass#TRANSPORT_STATE_MISMATCH} — door locked,
 *        verb missing. Mark the {@link TransportEdge} stale by key so
 *        the planner reroutes around it for this run/account
 *        context.</li>
 *    <li>{@link FailureClass#UNKNOWN} — none of the above; bump the
 *        per-tile failure counter.</li>
 *  </ul>
 *
 *  <p>Failure threshold (round 1): one failure → caller picks a
 *  different tile; two failures → tile is blacklisted for this route
 *  attempt. {@link #resetForNewRoute} wipes attempt-local state at the
 *  start of every new plan so transient blacklists don't accumulate
 *  across replans. Stale transports persist — they reflect in-world
 *  state.
 *
 *  <p>Threading: not thread-safe. Call only from the
 *  {@link V2Executor}'s tick context (worker thread). */
@Slf4j
public final class InvalidationClassifier
{
    public enum FailureClass
    {
        STATIC_COLLISION_MISMATCH,
        DYNAMIC_BLOCKER,
        TRANSPORT_STATE_MISMATCH,
        UNKNOWN
    }

    /** Read-only observation packet describing a failed click. The
     *  caller computes every field on the client thread before passing
     *  to {@link #classify}. */
    public record FailureContext(
        WorldPoint clickedTile,
        WorldPoint playerStartLoc,
        WorldPoint playerCurrentLoc,
        boolean snapshotSaysWalkable,
        boolean liveCollisionAllows,
        boolean dynamicEntityOnTile,
        boolean targetWasTransport,
        @Nullable TransportEdge edge,
        boolean expectedVerbStillPresent)
    {
    }

    /** Failures threshold to blacklist a tile within the current route attempt. */
    public static final int BLACKLIST_THRESHOLD = 2;

    /** Transient (dynamic-blocker) penalty TTL in ms. After this window
     *  the penalty falls off and the tile becomes a candidate again. */
    public static final long DYNAMIC_PENALTY_TTL_MS = 6_000L;

    private final Map<WorldPoint, Integer> failureCounts = new HashMap<>();
    private final Set<WorldPoint> blacklist = new HashSet<>();
    private final Map<WorldPoint, Long> transientPenalties = new HashMap<>();
    /** Keyed by {@link TransportEdge#key()} so a stale state for the
     *  same fromTile + verb + objectId triple is one entry regardless
     *  of how many edge objects we've seen. */
    private final Set<String> staleTransportKeys = new HashSet<>();

    public FailureClass classify(FailureContext ctx)
    {
        if (ctx == null) return FailureClass.UNKNOWN;

        FailureClass result;
        if (ctx.targetWasTransport() && !ctx.expectedVerbStillPresent())
        {
            result = FailureClass.TRANSPORT_STATE_MISMATCH;
            if (ctx.edge() != null)
            {
                staleTransportKeys.add(ctx.edge().key());
                log.info("classifier: transport edge stale — key={}", ctx.edge().key());
            }
        }
        else if (ctx.snapshotSaysWalkable() && !ctx.liveCollisionAllows())
        {
            result = FailureClass.STATIC_COLLISION_MISMATCH;
            blacklist.add(ctx.clickedTile());
            log.info("classifier: static collision mismatch at {} — blacklisted", ctx.clickedTile());
        }
        else if (ctx.dynamicEntityOnTile())
        {
            result = FailureClass.DYNAMIC_BLOCKER;
            transientPenalties.put(ctx.clickedTile(), System.currentTimeMillis());
            log.debug("classifier: dynamic blocker at {} — transient penalty", ctx.clickedTile());
        }
        else
        {
            result = FailureClass.UNKNOWN;
        }

        int newCount = failureCounts.merge(ctx.clickedTile(), 1, Integer::sum);
        if (newCount >= BLACKLIST_THRESHOLD)
        {
            blacklist.add(ctx.clickedTile());
            log.info("classifier: tile {} hit failure threshold ({}× ≥ {}) — blacklisted for route",
                ctx.clickedTile(), newCount, BLACKLIST_THRESHOLD);
        }

        return result;
    }

    public boolean isBlacklisted(WorldPoint tile)
    {
        return blacklist.contains(tile);
    }

    /** Add a tile to the attempt-local blacklist directly, bypassing
     *  the {@link #classify} flow. Used for failures where the cause
     *  is already known and a {@link FailureContext} would be made-up
     *  data — e.g. the dispatcher rejecting a press because
     *  {@code isLeftClickWalk} reported a non-walk top verb (tree
     *  "Chop down" overlay covering the cursor pixel). */
    public void blacklistTile(WorldPoint tile)
    {
        if (tile == null) return;
        blacklist.add(tile);
    }

    /** True if the tile has a non-expired transient penalty. */
    public boolean hasTransientPenalty(WorldPoint tile)
    {
        Long t = transientPenalties.get(tile);
        if (t == null) return false;
        if (System.currentTimeMillis() - t > DYNAMIC_PENALTY_TTL_MS)
        {
            transientPenalties.remove(tile);
            return false;
        }
        return true;
    }

    public boolean isTransportStale(String edgeKey)
    {
        return staleTransportKeys.contains(edgeKey);
    }

    public int failureCount(WorldPoint tile)
    {
        return failureCounts.getOrDefault(tile, 0);
    }

    /** Wipe attempt-local state at the start of every new plan. Stale
     *  transport keys persist — they describe in-world state, not
     *  attempt-local accidents. */
    public void resetForNewRoute()
    {
        failureCounts.clear();
        blacklist.clear();
        transientPenalties.clear();
    }
}
