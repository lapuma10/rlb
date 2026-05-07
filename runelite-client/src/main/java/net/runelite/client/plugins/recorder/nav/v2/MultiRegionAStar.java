package net.runelite.client.plugins.recorder.nav.v2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.worldmap.MapStore;
import net.runelite.client.plugins.recorder.worldmap.RegionChunkSnapshot;
import net.runelite.client.plugins.recorder.worldmap.RegionIds;
import net.runelite.client.plugins.recorder.worldmap.TransportEdge;
import net.runelite.client.plugins.recorder.worldmap.TransportIndex;
import net.runelite.client.plugins.recorder.worldmap.WorldMemoryConfig;

/** A* over the union of every loaded region snapshot plus every known
 *  {@link TransportEdge}. Replaces the single-region {@code MapPlanner}
 *  for V2 navigation.
 *
 *  <p>Graph:
 *  <ul>
 *    <li>Walk edge: 8-direction tile→tile within a plane, gated by
 *        {@link CollisionDataFlag} on the destination tile. Cost 1
 *        (or 1×noise when {@link #plan(WorldPoint, WorldPoint, double)}
 *        is used).</li>
 *    <li>Transport edge: jump from {@link TransportEdge#fromTile()} to
 *        {@link TransportEdge#toTile()}. Cost {@link
 *        #TRANSPORT_BASE_COST}. NOT subject to noise — transport
 *        selection is the responsibility of the top-K layer.</li>
 *  </ul>
 *
 *  <p>Heuristic: Chebyshev distance in (x, y) ignoring plane. Admissible
 *  for 8-direction movement and stays admissible across plane changes
 *  because plane traversal is free in Chebyshev terms (real cost is
 *  paid by the transport edge). */
@Slf4j
public final class MultiRegionAStar
{
    /** Transport cost: small enough that a transport-using route is
     *  preferred over a long walkaround, large enough that a
     *  10-tile detour through a transport is rejected when a 5-tile
     *  walk exists. Spec leaves this an "implementation determines"
     *  value; tune as data warrants. */
    static final int TRANSPORT_BASE_COST = 2;

    private final MapStore mapStore;
    private final TransportIndex transports;
    private final WorldMemoryConfig config;

    public MultiRegionAStar(MapStore mapStore, TransportIndex transports,
                            WorldMemoryConfig config)
    {
        this.mapStore = mapStore;
        this.transports = transports;
        this.config = config;
    }

    public V2Path plan(WorldPoint from, WorldPoint to)
    {
        return plan(from, to, 0.0, Collections.emptyMap(), null);
    }

    /** Variant with edge-cost noise on walk edges only. {@code noise} is
     *  the half-amplitude (e.g. 0.12 → walk-edge cost ∈ [0.88, 1.12]).
     *  Transport edges are NOT noisy — see spec lines 296-311. */
    public V2Path plan(WorldPoint from, WorldPoint to, double noise)
    {
        return plan(from, to, noise, Collections.emptyMap(), new Random());
    }

    /** Variant for top-K's repeated A*: an extra multiplicative penalty
     *  applied to specific edges (keyed by {@link #walkEdgeKey} for
     *  walks and {@link TransportEdge#key} for transports). */
    public V2Path plan(WorldPoint from, WorldPoint to, double noise,
                       Map<String, Double> edgePenalties, @Nullable Random rng)
    {
        if (from == null || to == null) return V2Path.EMPTY;
        long startKey = pack(from);
        long goalKey = pack(to);

        Map<Long, Double> gScore = new HashMap<>();
        Map<Long, Long> walkParent = new HashMap<>();
        Map<Long, TransportEdge> transportParent = new HashMap<>();
        PriorityQueue<long[]> open = new PriorityQueue<>(
            Comparator.comparingLong(a -> a[0]));
        gScore.put(startKey, 0.0);
        open.add(new long[]{heuristic(from, to), startKey, 0});  // f, key, g*1000

        Set<Long> closed = new HashSet<>();
        int expanded = 0;
        boolean found = false;

        while (!open.isEmpty())
        {
            if (expanded++ >= config.maxExpandedTiles) break;
            long[] head = open.poll();
            long k = head[1];
            if (!closed.add(k)) continue;
            if (k == goalKey) { found = true; break; }

            int x = unpackX(k), y = unpackY(k), plane = unpackPlane(k);
            double gNow = gScore.getOrDefault(k, Double.POSITIVE_INFINITY);

            // Walk neighbours (8 directions).
            for (int i = 0; i < 8; i++)
            {
                int nx = x + DX[i], ny = y + DY[i];
                if (!canWalk(x, y, plane, DX[i], DY[i])) continue;
                long nk = pack(nx, ny, plane);
                double cost = 1.0;
                if (noise > 0 && rng != null)
                {
                    cost = 1.0 + (rng.nextDouble() * 2 - 1) * noise;
                }
                Double pen = edgePenalties.get(walkEdgeKey(x, y, plane, nx, ny));
                if (pen != null) cost *= pen;
                double tentative = gNow + cost;
                Double existing = gScore.get(nk);
                if (existing == null || tentative < existing)
                {
                    gScore.put(nk, tentative);
                    walkParent.put(nk, k);
                    transportParent.remove(nk);
                    long f = (long) Math.round((tentative + heuristic(
                        new WorldPoint(nx, ny, plane), to)) * 1000);
                    open.add(new long[]{f, nk, (long) Math.round(tentative * 1000)});
                }
            }

            // Transport edges originating at this tile.
            WorldPoint here = new WorldPoint(x, y, plane);
            List<TransportEdge> outgoing = transports.getOutgoing(here);
            for (TransportEdge edge : outgoing)
            {
                long nk = pack(edge.toTile());
                double cost = TRANSPORT_BASE_COST;
                Double pen = edgePenalties.get(edge.key());
                if (pen != null) cost *= pen;
                double tentative = gNow + cost;
                Double existing = gScore.get(nk);
                if (existing == null || tentative < existing)
                {
                    gScore.put(nk, tentative);
                    walkParent.remove(nk);
                    transportParent.put(nk, edge);
                    long f = (long) Math.round((tentative + heuristic(edge.toTile(), to)) * 1000);
                    open.add(new long[]{f, nk, (long) Math.round(tentative * 1000)});
                }
            }
        }

        if (!found || !gScore.containsKey(goalKey))
        {
            log.debug("multi-region-astar: no path {}→{} (expanded={})",
                from, to, expanded);
            return V2Path.EMPTY;
        }
        return reconstruct(from, to, walkParent, transportParent,
            (int) Math.round(gScore.get(goalKey)));
    }

    /** Heuristic in milliunits (we use long math in the priority queue).
     *  Chebyshev distance × 1000 + 0 plane penalty (transports pay their
     *  own cost). */
    private static long heuristic(WorldPoint a, WorldPoint b)
    {
        int dx = Math.abs(a.getX() - b.getX());
        int dy = Math.abs(a.getY() - b.getY());
        return Math.max(dx, dy) * 1000L;
    }

    /** Reconstruct the V2Path by walking back from goal via the parent
     *  maps, splitting into Walk legs and Transport legs at every
     *  transport-arrival tile. */
    private V2Path reconstruct(WorldPoint from, WorldPoint to,
                               Map<Long, Long> walkParent,
                               Map<Long, TransportEdge> transportParent,
                               int totalCost)
    {
        List<V2Leg> legsReversed = new ArrayList<>();
        List<WorldPoint> currentWalkReversed = new ArrayList<>();
        currentWalkReversed.add(to);
        long cur = pack(to);

        while (cur != pack(from))
        {
            TransportEdge te = transportParent.get(cur);
            if (te != null)
            {
                if (currentWalkReversed.size() > 1)
                {
                    // Reverse to forward order.
                    List<WorldPoint> forward = new ArrayList<>(currentWalkReversed);
                    Collections.reverse(forward);
                    legsReversed.add(new V2Leg.Walk(
                        RegionIds.regionIdFor(forward.get(0).getX(), forward.get(0).getY()),
                        forward));
                }
                legsReversed.add(new V2Leg.Transport(te));
                cur = pack(te.fromTile());
                currentWalkReversed = new ArrayList<>();
                currentWalkReversed.add(te.fromTile());
                continue;
            }
            Long parent = walkParent.get(cur);
            if (parent == null)
            {
                // No parent and not at start → broken reconstruction.
                log.warn("multi-region-astar: reconstruction broke at {}",
                    new WorldPoint(unpackX(cur), unpackY(cur), unpackPlane(cur)));
                return V2Path.EMPTY;
            }
            WorldPoint prev = new WorldPoint(unpackX(parent), unpackY(parent), unpackPlane(parent));
            currentWalkReversed.add(prev);
            cur = parent;
        }
        if (currentWalkReversed.size() > 1)
        {
            List<WorldPoint> forward = new ArrayList<>(currentWalkReversed);
            Collections.reverse(forward);
            legsReversed.add(new V2Leg.Walk(
                RegionIds.regionIdFor(forward.get(0).getX(), forward.get(0).getY()),
                forward));
        }
        Collections.reverse(legsReversed);
        return new V2Path(legsReversed, totalCost);
    }

    /** Stable string key for a walk edge — used by the top-K router to
     *  penalize re-use across attempts. Direction-insensitive: the same
     *  edge from either tile yields the same key. */
    public static String walkEdgeKey(int x1, int y1, int plane, int x2, int y2)
    {
        int ax, ay, bx, by;
        if (x1 < x2 || (x1 == x2 && y1 <= y2))
        {
            ax = x1; ay = y1; bx = x2; by = y2;
        }
        else
        {
            ax = x2; ay = y2; bx = x1; by = y1;
        }
        return "W|" + ax + "," + ay + "|" + bx + "," + by + "|p" + plane;
    }

    /** Cross-region-aware {@code canTravel}. Looks the destination tile
     *  up via the {@link MapStore} so a region boundary doesn't block
     *  expansion when the neighbour's snapshot is loaded. */
    private boolean canWalk(int x, int y, int plane, int dx, int dy)
    {
        dx = Integer.signum(dx);
        dy = Integer.signum(dy);
        if (dx == 0 && dy == 0) return true;

        int nx = x + dx, ny = y + dy;
        int destRegion = RegionIds.regionIdFor(nx, ny);
        RegionChunkSnapshot destSnap = mapStore.snapshotFor(destRegion);
        if (destSnap == null) return false;
        RegionChunkSnapshot.TileEntry destTile = destSnap.tile(nx, ny, plane);
        if (destTile == null) return false;
        int destFlags = destTile.movement;

        int xFlags = CollisionDataFlag.BLOCK_MOVEMENT_FULL;
        int yFlags = CollisionDataFlag.BLOCK_MOVEMENT_FULL;
        int xyFlags = CollisionDataFlag.BLOCK_MOVEMENT_FULL;

        if (dx < 0) xFlags |= CollisionDataFlag.BLOCK_MOVEMENT_EAST;
        if (dx > 0) xFlags |= CollisionDataFlag.BLOCK_MOVEMENT_WEST;
        if (dy < 0) yFlags |= CollisionDataFlag.BLOCK_MOVEMENT_NORTH;
        if (dy > 0) yFlags |= CollisionDataFlag.BLOCK_MOVEMENT_SOUTH;

        if (dx < 0 && dy < 0) xyFlags |= CollisionDataFlag.BLOCK_MOVEMENT_NORTH_EAST;
        if (dx < 0 && dy > 0) xyFlags |= CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_EAST;
        if (dx > 0 && dy < 0) xyFlags |= CollisionDataFlag.BLOCK_MOVEMENT_NORTH_WEST;
        if (dx > 0 && dy > 0) xyFlags |= CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_WEST;

        if (dx != 0 && (destFlags & xFlags) != 0) return false;
        if (dy != 0 && (destFlags & yFlags) != 0) return false;
        if (dx != 0 && dy != 0 && (destFlags & xyFlags) != 0) return false;
        return true;
    }

    private static long pack(WorldPoint p)
    {
        return RegionChunkSnapshot.packTileKey(p.getX(), p.getY(), p.getPlane());
    }
    private static long pack(int x, int y, int plane)
    {
        return RegionChunkSnapshot.packTileKey(x, y, plane);
    }
    private static int unpackX(long k) { return RegionChunkSnapshot.unpackX(k); }
    private static int unpackY(long k) { return RegionChunkSnapshot.unpackY(k); }
    private static int unpackPlane(long k) { return RegionChunkSnapshot.unpackPlane(k); }

    private static final int[] DX = {0, 0, 1, -1, 1, 1, -1, -1};
    private static final int[] DY = {1, -1, 0, 0, 1, -1, 1, -1};
}
