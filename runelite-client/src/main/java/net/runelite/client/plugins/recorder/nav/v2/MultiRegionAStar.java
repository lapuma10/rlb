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
 *  <p>Heuristic: Chebyshev distance in (x, y) plus
 *  {@link #TRANSPORT_BASE_COST} when source and goal are on different
 *  planes. Admissible for 8-direction movement: any cross-plane path
 *  must traverse at least one transport edge of cost
 *  {@code TRANSPORT_BASE_COST}, so the plane term is a valid lower
 *  bound on the inevitable transport cost. The added term is critical
 *  for cross-plane queries — without it A* would treat same-plane
 *  walking as cost-equivalent to (transport + destination-plane walk)
 *  and flood the source plane until {@link
 *  WorldMemoryConfig#maxExpandedTiles} is exhausted. */
@Slf4j
public final class MultiRegionAStar
{
    /** Transport cost: small enough that a transport-using route is
     *  preferred over a long walkaround, large enough that a
     *  10-tile detour through a transport is rejected when a 5-tile
     *  walk exists. Spec leaves this an "implementation determines"
     *  value; tune as data warrants. */
    static final int TRANSPORT_BASE_COST = 2;

    /** Cost multiplier for a step into a tile not present in the
     *  {@link MapStore} snapshot. Set to 1.0001 — effectively neutral
     *  with known-walkable tiles, but enough of a tie-break that A*
     *  prefers known corridors when both are equally direct.
     *
     *  <p>Earlier experiments tried 5.0 (NO_ROUTE: heuristic became
     *  massively under-admissible), 1.05 (planner detoured 110 tiles
     *  through known terrain), and 1.0 (NO_ROUTE again because
     *  same-plane unknown walking became cheaper than transport+walk
     *  on the destination plane, so A* never considered transports).
     *  1.0001 is the empirical sweet spot — biases priority order
     *  toward known-walkable without distorting cost arithmetic. */
    static final double UNKNOWN_TILE_COST = 1.0001;

    /** Cost for a tile flagged with the engine's "off-scene" sentinel
     *  (0x00ffffff — every block-bit set). The scraper used to write
     *  this for tiles inside the scrape window but outside the truly-
     *  loaded scene chunks. New scrapes skip writing it, but historical
     *  snapshots have it baked in. The sentinel often masks WATER or
     *  VOID tiles, which are genuinely unwalkable — so charge a stiff
     *  but not infinite cost. The planner prefers known walkable +
     *  truly-missing tiles over sentinel tiles when alternatives
     *  exist, but still routes through sentinels when no other path is
     *  available. The executor's stall handling + replan recovers if
     *  a sentinel tile turns out to be water/wall mid-route. */
    static final double SENTINEL_TILE_COST = 3.0;

    /** Multiplier on walk-edge cost when stepping into a tile recorded
     *  in the request's V1 trail. Halving makes A* prefer corridors that
     *  hit trail tiles even when the cost-optimal off-trail path is
     *  comparable — gives V2 V1's natural-looking routes for known
     *  destinations while keeping A*'s ability to deviate around live
     *  obstacles (NPCs, closed gates) the trail can't anticipate. */
    static final double TRAIL_PREFERENCE_MULTIPLIER = 0.5;
    /** Sentinel: walk impossible. Distinct from UNKNOWN_TILE_COST
     *  (allowed-but-expensive) so the caller can drop the neighbor
     *  with a cheap isInfinite check. */
    private static final double BLOCKED_COST = Double.POSITIVE_INFINITY;

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
        return plan(from, to, noise, edgePenalties, rng, Collections.emptySet());
    }

    /** Trail-bias variant: walk edges whose destination tile is in
     *  {@code preferredDestKeys} get their cost halved. Used to steer A*
     *  toward a recorded V1 trail when one exists for the request, while
     *  still allowing deviation when the trail's tiles are blocked
     *  (NPCs, transient obstacles). The bias is applied to the
     *  destination tile (not the edge) so any approach into a trail tile
     *  benefits — that's enough to make an A* with admissible heuristic
     *  prefer corridors that hit trail tiles, even when the cost-optimal
     *  off-trail path is similar in raw distance. Live observation that
     *  motivated this: V2 found a 110-tile path through the Lumbridge
     *  castle SE corner that loops around the outer wall (cost-optimal,
     *  visually awkward); V1's recorded trail goes via the y=3219
     *  corridor — both walkable, but only the trail-biased route looks
     *  player-natural. */
    public V2Path plan(WorldPoint from, WorldPoint to, double noise,
                       Map<String, Double> edgePenalties, @Nullable Random rng,
                       Set<Long> preferredDestKeys)
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
                double baseCost = walkCost(x, y, plane, DX[i], DY[i]);
                if (Double.isInfinite(baseCost)) continue;
                long nk = pack(nx, ny, plane);
                double cost = baseCost;
                if (noise > 0 && rng != null)
                {
                    cost *= 1.0 + (rng.nextDouble() * 2 - 1) * noise;
                }
                Double pen = edgePenalties.get(walkEdgeKey(x, y, plane, nx, ny));
                if (pen != null) cost *= pen;
                if (!preferredDestKeys.isEmpty() && preferredDestKeys.contains(pack(nx, ny, plane)))
                {
                    cost *= TRAIL_PREFERENCE_MULTIPLIER;
                }
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
            log.warn("multi-region-astar: no path {}→{} (expanded={}, gScore.size={}, hitCap={})",
                from, to, expanded, gScore.size(), expanded >= config.maxExpandedTiles);
            return V2Path.EMPTY;
        }
        return reconstruct(from, to, walkParent, transportParent,
            (int) Math.round(gScore.get(goalKey)));
    }

    /** Plane-aware Chebyshev heuristic. Returns the lower bound on
     *  remaining cost: Chebyshev distance in (x, y) plus the minimum
     *  cost of any required plane change. Any path between tiles on
     *  different planes must traverse at least one transport edge
     *  (cost {@link #TRANSPORT_BASE_COST}), so adding that cost when
     *  the planes differ stays admissible while telling A* that
     *  same-plane wandering toward a cross-plane goal is provably
     *  more expensive than descending via a transport.
     *
     *  <p>Without the plane term, A* with Chebyshev_xy alone treats
     *  walking 75 tiles on the source plane as equivalent in
     *  estimated cost to (transport + walking 75 on the destination
     *  plane). With {@link #TRANSPORT_BASE_COST}=2 and unknown-tile
     *  cost ≈ 1.0, the same-plane walk wins the priority queue, A*
     *  floods the source plane, and a cross-plane plan exhausts
     *  {@link WorldMemoryConfig#maxExpandedTiles} before reaching
     *  the goal — exactly the live failure that surfaced this fix. */
    private static int heuristic(WorldPoint a, WorldPoint b)
    {
        int dx = Math.abs(a.getX() - b.getX());
        int dy = Math.abs(a.getY() - b.getY());
        int planeMismatch = a.getPlane() != b.getPlane() ? TRANSPORT_BASE_COST : 0;
        return Math.max(dx, dy) + planeMismatch;
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

    /** Cross-region-aware step cost. Returns:
     *  <ul>
     *    <li>{@code 1.0} — known walkable (snapshot has tile, no
     *        block flags blocking the step)</li>
     *    <li>{@link #UNKNOWN_TILE_COST} — region or tile missing from
     *        the snapshot. Treated as crossable so the planner can
     *        produce a path through partially-scraped corridors; the
     *        executor handles the inevitable static-collision /
     *        openable-blocker cases at run time.</li>
     *    <li>{@link #BLOCKED_COST} — destination tile is in the
     *        snapshot and its collision flags forbid the step.</li>
     *  </ul> */
    private double walkCost(int x, int y, int plane, int dx, int dy)
    {
        dx = Integer.signum(dx);
        dy = Integer.signum(dy);
        if (dx == 0 && dy == 0) return BLOCKED_COST;

        int nx = x + dx, ny = y + dy;
        int destRegion = RegionIds.regionIdFor(nx, ny);
        RegionChunkSnapshot destSnap = mapStore.snapshotFor(destRegion);
        if (destSnap == null) return UNKNOWN_TILE_COST;
        RegionChunkSnapshot.TileEntry destTile = destSnap.tile(nx, ny, plane);
        if (destTile == null) return UNKNOWN_TILE_COST;
        int destFlags = destTile.movement;
        // Engine's "off-scene" sentinel — collision data for a tile
        // inside the scrape window but outside the truly-loaded scene
        // chunks. The scraper now skips writing it (see SceneScraper)
        // but historical snapshots have it baked in. Treat as
        // *expensive* unknown: more costly than a truly-missing tile
        // (which is genuinely unscraped terrain, often walkable) so
        // the planner prefers known walkables and truly-missing tiles
        // over sentinel ones — but still routes through sentinel
        // tiles when no alternative exists. Mid-route the bot
        // discovers some sentinel tiles are walkable and others are
        // water/walls; the executor's stall handling + replan
        // recovers either way. */
        if (destFlags == 0x00ffffff) return SENTINEL_TILE_COST;

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

        if (dx != 0 && (destFlags & xFlags) != 0) return BLOCKED_COST;
        if (dy != 0 && (destFlags & yFlags) != 0) return BLOCKED_COST;
        if (dx != 0 && dy != 0 && (destFlags & xyFlags) != 0) return BLOCKED_COST;
        return 1.0;
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
