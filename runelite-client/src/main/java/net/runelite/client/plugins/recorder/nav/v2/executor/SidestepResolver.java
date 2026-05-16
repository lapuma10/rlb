package net.runelite.client.plugins.recorder.nav.v2.executor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.nav.v2.Waypoint;
import net.runelite.client.plugins.recorder.nav.v2.WaypointType;

/** Lane 5 plan Task 3 — Sidestep resolver. Picks the furthest-forward
 *  walkable+clean tile within the current waypoint's tolerance bucket.
 *  Honors spec §4 Lane 5 sidestep rules:
 *
 *  <ul>
 *    <li>Tile within {@code chebyshev(current.target()) <= toleranceRadius}.</li>
 *    <li>Collision permits the move (per the supplied {@code tileAccept}).</li>
 *    <li>All active predicates accept the tile (folded into {@code tileAccept}
 *        by the executor wiring; the resolver is decoupled from the
 *        predicate registry shape).</li>
 *    <li>Tile does not skip a {@code TRANSPORT_APPROACH},
 *        {@code OBJECT_INTERACTION}, or {@code SAFETY_ANCHOR} waypoint
 *        (the executor passes the upcoming-anchor tile set).</li>
 *    <li>Tile does not increase distance-to-next-waypoint beyond the
 *        {@code sidestepCostThreshold} (default 2 tiles).</li>
 *    <li>Sidestep up to ±2 tiles perpendicular to direction-of-travel.</li>
 *  </ul>
 *
 *  <p>For exact-required waypoints ({@code toleranceRadius == 0} —
 *  e.g. {@link WaypointType#TRANSPORT_APPROACH}), the resolver picks the
 *  exact tile or returns {@link Status#NO_LOCAL_WALKABLE_TILE} — never a
 *  near-miss tile that would silently miss the transport.
 *
 *  <p>Decoupled from {@code WorldSnapshot} / {@code PredicateRegistry}
 *  / {@code PathContext} (Lane 2/4 territory) by accepting a {@link
 *  Predicate}{@code <WorldPoint>} input. The executor's wiring composes
 *  collision + predicate registry into that lambda. */
public final class SidestepResolver
{
    /** Spec §4 Lane 5 default sidestep cost threshold (in tiles). */
    public static final int DEFAULT_SIDESTEP_COST_THRESHOLD = 2;
    /** Spec §4 Lane 5: sidestep up to ±2 tiles perpendicular to travel. */
    public static final int DEFAULT_SIDESTEP_PERP_MAX = 2;

    public enum Status { OK, NO_LOCAL_WALKABLE_TILE }

    /** Why a candidate tile was rejected. */
    public enum RejectReason
    {
        OUT_OF_TOLERANCE,
        WRONG_PLANE,
        PREDICATE_REJECT,
        SKIPS_ANCHOR,
        TOO_FAR_FROM_NEXT,
        BACKWARD_OF_PLAYER
    }

    /** Single rejected-candidate trace entry — Lane 6 reads these via
     *  {@link ResolveResult#rejected()}. */
    public record RejectedCandidate(WorldPoint tile, RejectReason reason) {}

    /** Resolve result. Holds the chosen tile (when {@link Status#OK}),
     *  whether a sidestep was used, and the rejected-candidate trace. */
    public record ResolveResult(
        Status status,
        Optional<WorldPoint> chosen,
        List<RejectedCandidate> rejected,
        boolean sidestepUsed)
    {
        public static ResolveResult ok(WorldPoint chosen, boolean sidestep, List<RejectedCandidate> rejected)
        {
            return new ResolveResult(Status.OK, Optional.of(chosen), rejected, sidestep);
        }

        public static ResolveResult noWalkable(List<RejectedCandidate> rejected)
        {
            return new ResolveResult(Status.NO_LOCAL_WALKABLE_TILE, Optional.empty(), rejected, false);
        }
    }

    private final int sidestepCostThreshold;
    private final int sidestepPerpMax;

    public SidestepResolver()
    {
        this(DEFAULT_SIDESTEP_COST_THRESHOLD, DEFAULT_SIDESTEP_PERP_MAX);
    }

    public SidestepResolver(int sidestepCostThreshold, int sidestepPerpMax)
    {
        this.sidestepCostThreshold = sidestepCostThreshold;
        this.sidestepPerpMax = sidestepPerpMax;
    }

    /** Resolve the best concrete tile to click for the current waypoint.
     *
     *  @param current     the current waypoint (target tile + tolerance + type)
     *  @param playerAt    the player's current world location
     *  @param next        next waypoint along the path, or {@code null} (used for
     *                     the "distance-to-next-waypoint" guard)
     *  @param tileAccept  composed collision + predicate filter; returns
     *                     {@code true} when the tile is walkable and accepted
     *                     by all active predicates
     *  @param skipAnchorTiles set of anchor tiles the resolver MUST NOT
     *                     skip past (TRANSPORT_APPROACH / OBJECT_INTERACTION /
     *                     SAFETY_ANCHOR tiles upcoming on the path) */
    public ResolveResult resolve(Waypoint current,
                                 WorldPoint playerAt,
                                 Waypoint next,
                                 Predicate<WorldPoint> tileAccept,
                                 Set<WorldPoint> skipAnchorTiles)
    {
        if (current == null || current.target() == null || playerAt == null)
        {
            return ResolveResult.noWalkable(List.of());
        }
        if (tileAccept == null) tileAccept = t -> true;
        if (skipAnchorTiles == null) skipAnchorTiles = Set.of();

        WorldPoint target = current.target();
        int tolerance = Math.max(0, current.toleranceRadius());
        boolean exactRequired = current.exactRequired()
            || current.type() == WaypointType.TRANSPORT_APPROACH
            || current.type() == WaypointType.OBJECT_INTERACTION;

        List<RejectedCandidate> rejected = new ArrayList<>();

        // Exact-required path — pick the exact tile or fail loud.
        if (exactRequired || tolerance == 0)
        {
            if (target.getPlane() != playerAt.getPlane())
            {
                rejected.add(new RejectedCandidate(target, RejectReason.WRONG_PLANE));
                return ResolveResult.noWalkable(rejected);
            }
            if (!tileAccept.test(target))
            {
                rejected.add(new RejectedCandidate(target, RejectReason.PREDICATE_REJECT));
                return ResolveResult.noWalkable(rejected);
            }
            return ResolveResult.ok(target, false, rejected);
        }

        // Build the corridor of candidate tiles: chebyshev <= tolerance
        // from the target, on the player's plane. Pre-rank by:
        //  1. furthest-forward in the direction-of-travel
        //  2. then by closeness to the target line (lower perpendicular
        //     offset preferred)
        List<WorldPoint> candidates = new ArrayList<>();
        for (int dx = -tolerance; dx <= tolerance; dx++)
        {
            for (int dy = -tolerance; dy <= tolerance; dy++)
            {
                WorldPoint t = new WorldPoint(target.getX() + dx, target.getY() + dy, target.getPlane());
                candidates.add(t);
            }
        }

        // Direction-of-travel = unit vector from player to target.
        int travelDx = Integer.signum(target.getX() - playerAt.getX());
        int travelDy = Integer.signum(target.getY() - playerAt.getY());

        // Sort: forwardmost first (largest scalar projection along travel
        // direction), then smallest perpendicular offset.
        candidates.sort((a, b) -> {
            int aProj = (a.getX() - playerAt.getX()) * travelDx
                      + (a.getY() - playerAt.getY()) * travelDy;
            int bProj = (b.getX() - playerAt.getX()) * travelDx
                      + (b.getY() - playerAt.getY()) * travelDy;
            if (aProj != bProj) return Integer.compare(bProj, aProj);
            int aPerp = Math.abs((a.getX() - target.getX()) * travelDy
                               - (a.getY() - target.getY()) * travelDx);
            int bPerp = Math.abs((b.getX() - target.getX()) * travelDy
                               - (b.getY() - target.getY()) * travelDx);
            return Integer.compare(aPerp, bPerp);
        });

        // Compute the "current distance from target to next waypoint" so we
        // can enforce the cost threshold guard.
        int targetToNext = (next == null) ? Integer.MAX_VALUE
            : chebyshev(target, next.target());

        WorldPoint targetTile = target;
        for (WorldPoint t : candidates)
        {
            // Plane guard
            if (t.getPlane() != playerAt.getPlane())
            {
                rejected.add(new RejectedCandidate(t, RejectReason.WRONG_PLANE));
                continue;
            }
            // Don't pick a tile that would skip an upcoming anchor.
            if (skipsAnchor(playerAt, t, skipAnchorTiles))
            {
                rejected.add(new RejectedCandidate(t, RejectReason.SKIPS_ANCHOR));
                continue;
            }
            // Cost-threshold guard: don't pick a tile that is significantly
            // further from the NEXT waypoint than the current target tile.
            if (next != null)
            {
                int tToNext = chebyshev(t, next.target());
                if (tToNext > targetToNext + sidestepCostThreshold)
                {
                    rejected.add(new RejectedCandidate(t, RejectReason.TOO_FAR_FROM_NEXT));
                    continue;
                }
            }
            // Perpendicular guard: sidestep limited to +/- sidestepPerpMax.
            int perp = Math.abs((t.getX() - targetTile.getX()) * travelDy
                              - (t.getY() - targetTile.getY()) * travelDx);
            if (perp > sidestepPerpMax)
            {
                rejected.add(new RejectedCandidate(t, RejectReason.OUT_OF_TOLERANCE));
                continue;
            }
            // Final predicate check (collision + active predicates).
            if (!tileAccept.test(t))
            {
                rejected.add(new RejectedCandidate(t, RejectReason.PREDICATE_REJECT));
                continue;
            }
            // Accepted. Mark sidestep=true iff we didn't pick the exact target.
            boolean sidestep = !t.equals(targetTile);
            return ResolveResult.ok(t, sidestep, rejected);
        }
        return ResolveResult.noWalkable(rejected);
    }

    private static int chebyshev(WorldPoint a, WorldPoint b)
    {
        return Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getY() - b.getY()));
    }

    /** Returns true if picking {@code candidate} (from {@code playerAt})
     *  would require walking past any anchor tile in
     *  {@code skipAnchorTiles}. Conservative test: an anchor "between"
     *  the player and the candidate (in the direction of travel) means
     *  the executor should pick the anchor itself, not skip past. */
    private static boolean skipsAnchor(WorldPoint playerAt, WorldPoint candidate,
                                       Set<WorldPoint> skipAnchorTiles)
    {
        if (skipAnchorTiles.isEmpty()) return false;
        int travelDx = Integer.signum(candidate.getX() - playerAt.getX());
        int travelDy = Integer.signum(candidate.getY() - playerAt.getY());
        for (WorldPoint a : skipAnchorTiles)
        {
            if (a.getPlane() != playerAt.getPlane()) continue;
            // "between" means projection along travel direction is
            // strictly between 0 and the candidate's projection.
            int aProj = (a.getX() - playerAt.getX()) * travelDx
                      + (a.getY() - playerAt.getY()) * travelDy;
            int cProj = (candidate.getX() - playerAt.getX()) * travelDx
                      + (candidate.getY() - playerAt.getY()) * travelDy;
            if (aProj >= 0 && aProj <= cProj && cProj > 0 && !candidate.equals(a))
            {
                // Candidate is past the anchor — skipping.
                return true;
            }
        }
        return false;
    }
}
