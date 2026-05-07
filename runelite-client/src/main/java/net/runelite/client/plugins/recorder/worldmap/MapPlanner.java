package net.runelite.client.plugins.recorder.worldmap;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.walker.PathSpec;

/** WorldMemory's planner — turns "stand near target T with LOS" into a
 *  PathSpec the existing UniversalWalker can execute. Runs on a worker
 *  thread; never touches live Client/Scene/WorldView. */
@Slf4j
public final class MapPlanner
{
    private final MapStore store;
    private final WorldMemoryConfig config;

    public MapPlanner(MapStore store, WorldMemoryConfig config)
    {
        this.store = store;
        this.config = config;
    }

    public Optional<WorldPoint> findInteractTile(
        WorldPoint player, WorldPoint target,
        int maxDistance, boolean requireLineOfSight)
    {
        if (player == null || target == null) return Optional.empty();
        if (player.getPlane() != target.getPlane())
        {
            log.debug("[mapplanner] reject: cross-plane player={} target={}", player, target);
            return Optional.empty();
        }
        int regionId = RegionIds.regionIdFor(target.getX(), target.getY());
        RegionChunkSnapshot snap = store.snapshotFor(regionId);
        if (snap == null)
        {
            log.debug("[mapplanner] reject: no snapshot for target region {} (player={} target={})",
                regionId, player, target);
            return Optional.empty();
        }
        if (RegionIds.regionIdFor(player.getX(), player.getY()) != regionId)
        {
            log.debug("[mapplanner] reject: cross-region player={} target={} (regions {}↔{})",
                player, target, RegionIds.regionIdFor(player.getX(), player.getY()), regionId);
            return Optional.empty();
        }

        // Phase 1: enumerate candidates with rejection counters.
        Set<WorldPoint> candidates = new HashSet<>();
        int plane = target.getPlane();
        int rejectedNotStandable = 0;
        int rejectedNoLos = 0;
        for (int dx = -maxDistance; dx <= maxDistance; dx++)
        {
            for (int dy = -maxDistance; dy <= maxDistance; dy++)
            {
                if (dx == 0 && dy == 0) continue;        // can't stand on target
                int x = target.getX() + dx, y = target.getY() + dy;
                WorldPoint c = new WorldPoint(x, y, plane);
                if (!snap.isStandableLocal(x, y, plane)) { rejectedNotStandable++; continue; }
                if (requireLineOfSight && !Bresenham.hasLineOfSight(snap, c, target))
                {
                    rejectedNoLos++;
                    continue;
                }
                candidates.add(c);
            }
        }
        if (candidates.isEmpty())
        {
            log.debug("[mapplanner] no candidates: target={} R={} reqLOS={} rejectNotStandable={} rejectNoLOS={}",
                target, maxDistance, requireLineOfSight, rejectedNotStandable, rejectedNoLos);
            return Optional.empty();
        }

        // Phase 2: single Dijkstra, all candidates as goals.
        Map<WorldPoint, Integer> dist = MapAStar.dijkstraToAny(
            snap, player, candidates, config.maxPathLength, config.maxExpandedTiles);

        // Phase 3: rank, pick best.
        WorldPoint best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        int bestPath = -1;
        int rejectedUnreachable = 0;
        for (WorldPoint c : candidates)
        {
            int d = dist.getOrDefault(c, -1);
            if (d < 0) { rejectedUnreachable++; continue; }     // unreachable within caps
            double score = config.rankWeightPathLength * d
                + config.rankWeightChebyshevToTarget * chebyshev(c, target);
            if (score < bestScore) { bestScore = score; best = c; bestPath = d; }
        }
        if (best == null)
        {
            log.debug("[mapplanner] all candidates unreachable: target={} candidates={} rejectUnreachable={}",
                target, candidates.size(), rejectedUnreachable);
            return Optional.empty();
        }
        log.debug("[mapplanner] picked: player={} target={} stand={} pathLen={} candidates={} (rejNotStandable={} rejNoLOS={} rejUnreachable={})",
            player, target, best, bestPath, candidates.size(),
            rejectedNotStandable, rejectedNoLos, rejectedUnreachable);
        return Optional.of(best);
    }

    public Optional<PathSpec> planWithin(WorldPoint player, WorldPoint goal)
    {
        if (player == null || goal == null) return Optional.empty();
        int rPlayer = RegionIds.regionIdFor(player.getX(), player.getY());
        int rGoal = RegionIds.regionIdFor(goal.getX(), goal.getY());
        if (rPlayer != rGoal) return Optional.empty();
        RegionChunkSnapshot snap = store.snapshotFor(rGoal);
        if (snap == null) return Optional.empty();

        // Verify reachability before emitting the spec.
        Map<WorldPoint, Integer> d = MapAStar.dijkstraToAny(
            snap, player, Set.of(goal),
            config.maxPathLength, config.maxExpandedTiles);
        if (d.getOrDefault(goal, -1) < 0) return Optional.empty();

        return Optional.of(walkSpecToTile(goal, "wm-plan"));
    }

    public Optional<PathSpec> planToInteractTile(
        WorldPoint player, WorldPoint target,
        int maxDistance, boolean requireLineOfSight, String pathName)
    {
        return findInteractTile(player, target, maxDistance, requireLineOfSight)
            .map(stand -> walkSpecToTile(stand, pathName));
    }

    /** Synthesize a single-step PathSpec: 1×1 WALK_AREA around the picked tile. */
    private PathSpec walkSpecToTile(WorldPoint tile, String name)
    {
        WorldArea oneByOne = new WorldArea(
            tile.getX(), tile.getY(), 1, 1, tile.getPlane());
        return PathSpec.builder(name)
            .walk("wm-target", oneByOne)
            .build();
    }

    private static int chebyshev(WorldPoint a, WorldPoint b)
    {
        return Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getY() - b.getY()));
    }
}
