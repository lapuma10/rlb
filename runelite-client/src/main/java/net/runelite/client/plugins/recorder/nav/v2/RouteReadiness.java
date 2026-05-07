package net.runelite.client.plugins.recorder.nav.v2;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.worldmap.MapStore;
import net.runelite.client.plugins.recorder.worldmap.RegionChunkSnapshot;
import net.runelite.client.plugins.recorder.worldmap.RegionIds;
import net.runelite.client.plugins.recorder.worldmap.TransportEdge;
import net.runelite.client.plugins.recorder.worldmap.TransportIndex;

/** Pre-flight diagnostic: "before V2 plans this route, can it actually
 *  succeed offline?" Reports loaded/missing region chunks, walkability of
 *  endpoints, BFS reachability from start, the nearest known tile to the
 *  goal, the first reason a connected corridor breaks, and the planner's
 *  yes/no answer for the route.
 *
 *  <p>Pure data — does not dispatch clicks, does not touch the live
 *  client. Safe to call from any thread (MapStore + TransportIndex are
 *  concurrent-safe). Used by the panel "Check V2 readiness" button and
 *  by the morning live-test workflow to decide whether V2_STRICT has a
 *  chance before flipping the switch.
 *
 *  <p>Spec phase 8: readiness lands BEFORE Phase 9 (V2 strict bank↔pen)
 *  so when V2 fails the user sees a precise reason instead of "watch
 *  the bot and guess." */
@Slf4j
public final class RouteReadiness
{
    /** Reason a connected corridor between {@code from} and {@code to}
     *  breaks. Classifier precedence (see {@link #classify}):
     *  <ol>
     *    <li>If the deterministic A* found a path AND the path uses an
     *        unsupported transport leg → {@link #TRANSPORT_EXECUTOR_MISSING}.</li>
     *    <li>If the planner found a path → {@link #NONE}.</li>
     *    <li>If the from-tile or to-tile is not walkable, surface the
     *        endpoint problem first: missing region snapshot →
     *        {@link #REGION_MISSING}; tile not in snapshot →
     *        {@link #UNKNOWN_TILE}.</li>
     *    <li>If the endpoints are on different planes:
     *        no transports known → {@link #TRANSPORT_REQUIRED};
     *        transports known → {@link #PLANE_MISMATCH}.</li>
     *    <li>Otherwise the BFS from the start populates either
     *        {@link #COLLISION_BLOCKED} or {@link #DIAGONAL_BLOCKED}
     *        from the first edge it could not traverse.</li>
     *    <li>Fallback (shouldn't be reachable in practice) →
     *        {@link #UNKNOWN_TILE}.</li>
     *  </ol> */
    public enum BreakReason
    {
        NONE,
        UNKNOWN_TILE,
        COLLISION_BLOCKED,
        DIAGONAL_BLOCKED,
        REGION_MISSING,
        PLANE_MISMATCH,
        TRANSPORT_REQUIRED,
        TRANSPORT_EXECUTOR_MISSING
    }

    /** Concrete pair of tiles the BFS could not traverse, with the exact
     *  movement flags + attempted direction. Useful for "why does the
     *  corridor break here?" — the fix is usually a fresh scrape, but
     *  the diagnostic shows whether it's a real wall or a stale flag. */
    public record CollisionDetail(WorldPoint a, WorldPoint b,
                                  int movementFlags, int dx, int dy) {}

    /** Self-contained snapshot. Renders cleanly via {@link #toString} for
     *  log lines + UI status bars. */
    public record Report(
        WorldPoint from,
        WorldPoint to,
        boolean fromWalkable,
        boolean toWalkable,
        int fromRegion,
        int toRegion,
        Set<Integer> bboxRegionIds,
        Set<Integer> loadedRegionIds,
        Set<Integer> missingRegionIds,
        int knownTilesInBbox,
        int reachableFromStart,
        @Nullable WorldPoint lastReachableFromStart,
        @Nullable WorldPoint nearestKnownToTarget,
        BreakReason firstBreakReason,
        @Nullable CollisionDetail collisionDetail,
        boolean canPlan,
        @Nullable String plannerDiagnostic,
        boolean transportRequired,
        int transportEdgesAvailable)
    {
        /** Compact human-readable form for the panel status label / log line. */
        public String summary()
        {
            return "from=" + from + " to=" + to
                + " from-walkable=" + fromWalkable
                + " to-walkable=" + toWalkable
                + " regions{loaded=" + loadedRegionIds.size()
                + "/missing=" + missingRegionIds.size()
                + "} knownTiles=" + knownTilesInBbox
                + " reachable=" + reachableFromStart
                + " break=" + firstBreakReason
                + " canPlan=" + canPlan
                + (transportRequired ? " transportRequired" : "")
                + (transportEdgesAvailable > 0
                    ? " transports=" + transportEdgesAvailable : "");
        }
    }

    /** Hard cap on BFS expansions — readiness is a diagnostic, not a
     *  planner. Guards against runaway scans when a region is huge.
     *  Bank↔pen is ~30 tiles; 8000 covers Lumby↔GE without trouble. */
    public static final int MAX_BFS_EXPANSIONS = 8000;
    /** Inflation around the from↔to bbox so the readiness check sees
     *  reasonable detour space. Tiles outside this box are never
     *  considered for nearestKnownToTarget. */
    public static final int BBOX_PADDING = 8;

    private final MapStore store;
    private final TransportIndex transports;
    @Nullable private final V2Planner planner;

    public RouteReadiness(MapStore store, TransportIndex transports, @Nullable V2Planner planner)
    {
        this.store = store;
        this.transports = transports;
        this.planner = planner;
    }

    public Report check(WorldPoint from, WorldPoint to)
    {
        if (from == null || to == null)
        {
            return new Report(from, to, false, false, -1, -1,
                Collections.emptySet(), Collections.emptySet(), Collections.emptySet(),
                0, 0, null, null, BreakReason.UNKNOWN_TILE, null,
                false, "(null endpoints)", false, 0);
        }

        int fromRegion = RegionIds.regionIdFor(from.getX(), from.getY());
        int toRegion = RegionIds.regionIdFor(to.getX(), to.getY());

        // Region bbox covering both endpoints + padding so detour space
        // is included. Region IDs are 6-bit-shifted x/y so we step by 1
        // on the region grid, not by tile.
        Set<Integer> bbox = bboxRegions(from, to);
        Set<Integer> loaded = new TreeSet<>();
        Set<Integer> missing = new TreeSet<>();
        for (int r : bbox)
        {
            if (store.snapshotFor(r) != null) loaded.add(r);
            else missing.add(r);
        }

        boolean fromWalk = isWalkable(from);
        boolean toWalk = isWalkable(to);

        int known = countKnownTilesInBbox(from, to);
        BfsResult bfs = bfsFromStart(from, to);

        boolean transportReq = from.getPlane() != to.getPlane()
            || !sameConnectedComponent(bfs, to);
        int transportCount = transports.size();

        boolean canPlan = false;
        String plannerDiag = null;
        boolean transportExecutorMissing = false;
        if (planner != null)
        {
            // Use the deterministic A* — readiness is a yes/no diagnostic,
            // it must NOT depend on the user's variation flag or the
            // current RouteHistory state. Two consecutive readiness
            // checks against the same world memory must return the same
            // BreakReason / canPlan answer.
            V2Path path = planner.planDeterministic(from, to);
            canPlan = path != null && !path.isEmpty();
            plannerDiag = planner.diagnose(from, to);
            if (canPlan)
            {
                for (V2Leg leg : path.legs())
                {
                    if (leg instanceof V2Leg.Transport)
                    {
                        transportExecutorMissing = true;
                        break;
                    }
                }
            }
        }

        BreakReason reason = classify(from, to, fromRegion, toRegion,
            missing, fromWalk, toWalk, bfs, transportReq, transportCount,
            transportExecutorMissing, canPlan);

        return new Report(from, to, fromWalk, toWalk, fromRegion, toRegion,
            bbox, loaded, missing, known,
            bfs.reachableCount, bfs.lastTile, bfs.nearestToGoal,
            reason, bfs.firstCollision, canPlan, plannerDiag,
            transportReq, transportCount);
    }

    private boolean isWalkable(WorldPoint p)
    {
        int rid = RegionIds.regionIdFor(p.getX(), p.getY());
        RegionChunkSnapshot snap = store.snapshotFor(rid);
        if (snap == null) return false;
        return snap.isStandableLocal(p.getX(), p.getY(), p.getPlane());
    }

    /** All region IDs whose bbox intersects the inflated from↔to bbox.
     *  Tiles inside this box are the ones the BFS scans + the readiness
     *  reports as "in corridor." */
    private Set<Integer> bboxRegions(WorldPoint from, WorldPoint to)
    {
        int xMin = Math.min(from.getX(), to.getX()) - BBOX_PADDING;
        int xMax = Math.max(from.getX(), to.getX()) + BBOX_PADDING;
        int yMin = Math.min(from.getY(), to.getY()) - BBOX_PADDING;
        int yMax = Math.max(from.getY(), to.getY()) + BBOX_PADDING;
        Set<Integer> out = new TreeSet<>();
        for (int x = xMin; x <= xMax; x += 64)
            for (int y = yMin; y <= yMax; y += 64)
                out.add(RegionIds.regionIdFor(x, y));
        out.add(RegionIds.regionIdFor(xMin, yMax));
        out.add(RegionIds.regionIdFor(xMax, yMin));
        out.add(RegionIds.regionIdFor(xMax, yMax));
        return out;
    }

    private int countKnownTilesInBbox(WorldPoint from, WorldPoint to)
    {
        int xMin = Math.min(from.getX(), to.getX()) - BBOX_PADDING;
        int xMax = Math.max(from.getX(), to.getX()) + BBOX_PADDING;
        int yMin = Math.min(from.getY(), to.getY()) - BBOX_PADDING;
        int yMax = Math.max(from.getY(), to.getY()) + BBOX_PADDING;
        int plane = from.getPlane();
        int count = 0;
        Set<Integer> seenRegions = new HashSet<>();
        for (int x = xMin; x <= xMax; x++)
        {
            int regionId = RegionIds.regionIdFor(x, yMin);
            if (seenRegions.add(regionId))
            {
                RegionChunkSnapshot snap = store.snapshotFor(regionId);
                if (snap == null) continue;
                for (RegionChunkSnapshot.TileEntry te : snap.tiles())
                {
                    if (te.plane != plane) continue;
                    if (te.x < xMin || te.x > xMax) continue;
                    if (te.y < yMin || te.y > yMax) continue;
                    count++;
                }
            }
        }
        return count;
    }

    private static final int[] DX = {0, 0, 1, -1, 1, 1, -1, -1};
    private static final int[] DY = {1, -1, 0, 0, 1, -1, 1, -1};

    /** BFS from {@code from} on the start plane. Stops at MAX_BFS_EXPANSIONS
     *  or when the goal is dequeued. Records the nearest reachable tile
     *  to the goal and the first collision/diagonal block. */
    private BfsResult bfsFromStart(WorldPoint from, WorldPoint to)
    {
        BfsResult r = new BfsResult();
        int plane = from.getPlane();
        RegionChunkSnapshot startSnap = store.snapshotFor(
            RegionIds.regionIdFor(from.getX(), from.getY()));
        if (startSnap == null) return r;
        if (startSnap.tile(from.getX(), from.getY(), plane) == null) return r;

        Set<Long> visited = new HashSet<>();
        Deque<long[]> q = new ArrayDeque<>();
        q.add(new long[]{from.getX(), from.getY()});
        visited.add(packXY(from.getX(), from.getY()));
        int bestDistToGoal = Integer.MAX_VALUE;
        WorldPoint bestNearest = from;
        WorldPoint last = from;
        int expansions = 0;

        while (!q.isEmpty() && expansions < MAX_BFS_EXPANSIONS)
        {
            long[] head = q.poll();
            int x = (int) head[0], y = (int) head[1];
            expansions++;
            last = new WorldPoint(x, y, plane);
            int d = chebyshev(x, y, to.getX(), to.getY());
            if (d < bestDistToGoal)
            {
                bestDistToGoal = d;
                bestNearest = last;
            }
            if (x == to.getX() && y == to.getY() && plane == to.getPlane())
            {
                r.reachedGoal = true;
                break;
            }
            for (int i = 0; i < 8; i++)
            {
                int nx = x + DX[i], ny = y + DY[i];
                long nk = packXY(nx, ny);
                if (visited.contains(nk)) continue;
                int regionId = RegionIds.regionIdFor(nx, ny);
                RegionChunkSnapshot snap = store.snapshotFor(regionId);
                if (snap == null)
                {
                    if (r.firstCollision == null && r.firstBreakReason == null)
                    {
                        r.firstBreakReason = BreakReason.REGION_MISSING;
                    }
                    continue;
                }
                RegionChunkSnapshot.TileEntry destTile = snap.tile(nx, ny, plane);
                if (destTile == null)
                {
                    if (r.firstCollision == null && r.firstBreakReason == null)
                    {
                        r.firstBreakReason = BreakReason.UNKNOWN_TILE;
                    }
                    continue;
                }
                int blockBits = blockingBits(DX[i], DY[i]);
                if ((destTile.movement & blockBits) != 0)
                {
                    if (r.firstCollision == null)
                    {
                        boolean diagonal = (DX[i] != 0 && DY[i] != 0);
                        r.firstCollision = new CollisionDetail(
                            new WorldPoint(x, y, plane),
                            new WorldPoint(nx, ny, plane),
                            destTile.movement, DX[i], DY[i]);
                        if (r.firstBreakReason == null)
                        {
                            r.firstBreakReason = diagonal
                                ? BreakReason.DIAGONAL_BLOCKED
                                : BreakReason.COLLISION_BLOCKED;
                        }
                    }
                    continue;
                }
                visited.add(nk);
                q.add(new long[]{nx, ny});
            }
        }
        r.reachableCount = visited.size();
        r.lastTile = last;
        r.nearestToGoal = bestNearest;
        return r;
    }

    private static long packXY(int x, int y) { return (((long) x) << 24) | (y & 0xffffff); }

    private static int chebyshev(int ax, int ay, int bx, int by)
    {
        return Math.max(Math.abs(ax - bx), Math.abs(ay - by));
    }

    /** Same blocking-bits logic as MultiRegionAStar.canWalk — kept local
     *  so readiness can be tested without depending on the planner. */
    private static int blockingBits(int dx, int dy)
    {
        dx = Integer.signum(dx);
        dy = Integer.signum(dy);
        int xFlags = 0, yFlags = 0, xyFlags = 0;
        if (dx < 0) xFlags |= CollisionDataFlag.BLOCK_MOVEMENT_EAST;
        if (dx > 0) xFlags |= CollisionDataFlag.BLOCK_MOVEMENT_WEST;
        if (dy < 0) yFlags |= CollisionDataFlag.BLOCK_MOVEMENT_NORTH;
        if (dy > 0) yFlags |= CollisionDataFlag.BLOCK_MOVEMENT_SOUTH;
        if (dx < 0 && dy < 0) xyFlags |= CollisionDataFlag.BLOCK_MOVEMENT_NORTH_EAST;
        if (dx < 0 && dy > 0) xyFlags |= CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_EAST;
        if (dx > 0 && dy < 0) xyFlags |= CollisionDataFlag.BLOCK_MOVEMENT_NORTH_WEST;
        if (dx > 0 && dy > 0) xyFlags |= CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_WEST;
        int bits = CollisionDataFlag.BLOCK_MOVEMENT_FULL;
        if (dx != 0) bits |= xFlags;
        if (dy != 0) bits |= yFlags;
        if (dx != 0 && dy != 0) bits |= xyFlags;
        return bits;
    }

    private boolean sameConnectedComponent(BfsResult bfs, WorldPoint to)
    {
        return bfs.reachedGoal;
    }

    private static BreakReason classify(WorldPoint from, WorldPoint to,
                                        int fromRegion, int toRegion,
                                        Set<Integer> missing,
                                        boolean fromWalk, boolean toWalk,
                                        BfsResult bfs,
                                        boolean transportReq,
                                        int transportCount,
                                        boolean transportExecutorMissing,
                                        boolean canPlan)
    {
        if (canPlan && transportExecutorMissing) return BreakReason.TRANSPORT_EXECUTOR_MISSING;
        if (canPlan) return BreakReason.NONE;

        // Endpoint problems take priority.
        RegionChunkSnapshot.TileEntry endTile;
        if (!fromWalk)
        {
            if (missing.contains(fromRegion)) return BreakReason.REGION_MISSING;
            return BreakReason.UNKNOWN_TILE;
        }
        if (!toWalk)
        {
            if (missing.contains(toRegion)) return BreakReason.REGION_MISSING;
            return BreakReason.UNKNOWN_TILE;
        }

        if (from.getPlane() != to.getPlane())
        {
            if (transportCount == 0) return BreakReason.TRANSPORT_REQUIRED;
            return BreakReason.PLANE_MISMATCH;
        }

        if (bfs.firstBreakReason != null) return bfs.firstBreakReason;
        if (transportReq && transportCount == 0) return BreakReason.TRANSPORT_REQUIRED;
        return BreakReason.UNKNOWN_TILE;
    }

    /** Ordered scan of the from↔to bbox for users who want to stream the
     *  contents into a corridor dump. Public so InspectionDumper can
     *  share the bbox-walk implementation without re-deriving it. */
    public List<RegionChunkSnapshot.TileEntry> bboxTiles(WorldPoint from, WorldPoint to)
    {
        int plane = from.getPlane();
        int xMin = Math.min(from.getX(), to.getX()) - BBOX_PADDING;
        int xMax = Math.max(from.getX(), to.getX()) + BBOX_PADDING;
        int yMin = Math.min(from.getY(), to.getY()) - BBOX_PADDING;
        int yMax = Math.max(from.getY(), to.getY()) + BBOX_PADDING;
        Set<Integer> seenRegions = new HashSet<>();
        for (int rx = xMin >> 6; rx <= xMax >> 6; rx++)
            for (int ry = yMin >> 6; ry <= yMax >> 6; ry++)
                seenRegions.add((rx << 8) | ry);
        List<RegionChunkSnapshot.TileEntry> out = new ArrayList<>();
        for (int rid : seenRegions)
        {
            RegionChunkSnapshot snap = store.snapshotFor(rid);
            if (snap == null) continue;
            for (RegionChunkSnapshot.TileEntry te : snap.tiles())
            {
                if (te.plane != plane) continue;
                if (te.x < xMin || te.x > xMax) continue;
                if (te.y < yMin || te.y > yMax) continue;
                out.add(te);
            }
        }
        return out;
    }

    /** Per-row corridor analysis. Keyed by y; each entry has the row's
     *  x range, count of known tiles, count reachable from start, count
     *  of blocked edges adjacent to row tiles, and the region IDs the
     *  row touches. {@link InspectionDumper} writes this to inspect/. */
    public Map<Integer, RowAnalysis> analyzeCorridor(WorldPoint from, WorldPoint to)
    {
        int plane = from.getPlane();
        int xMin = Math.min(from.getX(), to.getX()) - BBOX_PADDING;
        int xMax = Math.max(from.getX(), to.getX()) + BBOX_PADDING;
        int yMin = Math.min(from.getY(), to.getY()) - BBOX_PADDING;
        int yMax = Math.max(from.getY(), to.getY()) + BBOX_PADDING;

        // BFS to mark reachable tiles.
        Set<Long> reachable = new HashSet<>();
        BfsResult bfs = bfsFromStart(from, to);
        // Re-run the BFS but capture the full visited set this time —
        // bfsFromStart already populated reachableCount; we want the
        // membership for per-row counting. Cheap second pass.
        Set<Long> visited = new HashSet<>();
        Deque<long[]> q = new ArrayDeque<>();
        q.add(new long[]{from.getX(), from.getY()});
        visited.add(packXY(from.getX(), from.getY()));
        int expansions = 0;
        while (!q.isEmpty() && expansions++ < MAX_BFS_EXPANSIONS)
        {
            long[] h = q.poll();
            int x = (int) h[0], y = (int) h[1];
            for (int i = 0; i < 8; i++)
            {
                int nx = x + DX[i], ny = y + DY[i];
                long nk = packXY(nx, ny);
                if (visited.contains(nk)) continue;
                if (nx < xMin || nx > xMax || ny < yMin || ny > yMax) continue;
                RegionChunkSnapshot snap = store.snapshotFor(RegionIds.regionIdFor(nx, ny));
                if (snap == null) continue;
                RegionChunkSnapshot.TileEntry destTile = snap.tile(nx, ny, plane);
                if (destTile == null) continue;
                if ((destTile.movement & blockingBits(DX[i], DY[i])) != 0) continue;
                visited.add(nk);
                q.add(new long[]{nx, ny});
            }
        }
        reachable.addAll(visited);

        Map<Integer, RowAnalysis> rows = new HashMap<>();
        for (int y = yMin; y <= yMax; y++)
        {
            int known = 0, reach = 0, blockedEdges = 0;
            int rowXMin = Integer.MAX_VALUE, rowXMax = Integer.MIN_VALUE;
            Set<Integer> rowRegions = new TreeSet<>();
            for (int x = xMin; x <= xMax; x++)
            {
                int rid = RegionIds.regionIdFor(x, y);
                RegionChunkSnapshot snap = store.snapshotFor(rid);
                if (snap == null) continue;
                RegionChunkSnapshot.TileEntry te = snap.tile(x, y, plane);
                if (te == null) continue;
                known++;
                rowRegions.add(rid);
                if (x < rowXMin) rowXMin = x;
                if (x > rowXMax) rowXMax = x;
                if (reachable.contains(packXY(x, y))) reach++;
                // Count blocked outgoing edges (cardinal only — diagonals
                // would double-count blocking pairs).
                for (int i = 0; i < 4; i++)
                {
                    int nx = x + DX[i], ny = y + DY[i];
                    RegionChunkSnapshot s2 = store.snapshotFor(RegionIds.regionIdFor(nx, ny));
                    if (s2 == null) continue;
                    RegionChunkSnapshot.TileEntry n2 = s2.tile(nx, ny, plane);
                    if (n2 == null) continue;
                    if ((n2.movement & blockingBits(DX[i], DY[i])) != 0) blockedEdges++;
                }
            }
            if (known > 0)
            {
                rows.put(y, new RowAnalysis(y, rowXMin, rowXMax,
                    known, reach, blockedEdges, rowRegions));
            }
        }
        return rows;
    }

    public record RowAnalysis(int y, int xMin, int xMax,
                              int knownTileCount,
                              int reachableFromStartCount,
                              int blockedEdgeCount,
                              Set<Integer> regionIds) {}

    private static final class BfsResult
    {
        int reachableCount;
        boolean reachedGoal;
        @Nullable WorldPoint lastTile;
        @Nullable WorldPoint nearestToGoal;
        @Nullable CollisionDetail firstCollision;
        @Nullable BreakReason firstBreakReason;
    }
}
