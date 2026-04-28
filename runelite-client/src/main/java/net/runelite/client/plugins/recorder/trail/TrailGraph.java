package net.runelite.client.plugins.recorder.trail;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.coords.WorldPoint;

/** Junction graph built from N trails. See spec
 *  {@code docs/superpowers/specs/2026-04-28-trail-network-webwalker-design.md}
 *  for edge rules. Pure data; build via {@link #build(Collection)}.
 *
 *  <p>Edges are bidirectional. Edge weight is encoded as an int cost; -1
 *  signals "no edge". Transport edges carry their {@link Leg.Transport}
 *  payload so the planner can emit it in the {@link TrailPath} output. */
public final class TrailGraph
{
    private final Set<WorldPoint> nodes;
    /** {@code adj.get(a).get(b) = cost(a->b)}. Symmetric for walk + junction. */
    private final Map<WorldPoint, Map<WorldPoint, Integer>> adj;
    /** {@code transports.get(a).get(b) = transport leg payload between a and b}. */
    private final Map<WorldPoint, Map<WorldPoint, Leg.Transport>> transports;

    private TrailGraph(Set<WorldPoint> nodes,
                       Map<WorldPoint, Map<WorldPoint, Integer>> adj,
                       Map<WorldPoint, Map<WorldPoint, Leg.Transport>> transports)
    {
        this.nodes = Collections.unmodifiableSet(nodes);
        this.adj = adj;
        this.transports = transports;
    }

    public Set<WorldPoint> nodes() { return nodes; }

    /** Cost of the cheapest direct edge from {@code a} to {@code b}, or
     *  -1 if no edge exists. */
    public int edgeCost(WorldPoint a, WorldPoint b)
    {
        Map<WorldPoint, Integer> n = adj.get(a);
        if (n == null) return -1;
        Integer c = n.get(b);
        return c == null ? -1 : c;
    }

    /** All neighbours of {@code a} with their costs. Empty map if isolated. */
    public Map<WorldPoint, Integer> neighbours(WorldPoint a)
    {
        Map<WorldPoint, Integer> n = adj.get(a);
        return n == null ? Collections.emptyMap() : Collections.unmodifiableMap(n);
    }

    /** Transport-edge payload for the edge {@code a -> b}, or null if the
     *  edge is a walk/junction edge. */
    public Leg.Transport transportBetween(WorldPoint a, WorldPoint b)
    {
        Map<WorldPoint, Leg.Transport> n = transports.get(a);
        return n == null ? null : n.get(b);
    }

    public static TrailGraph build(Collection<Trail> trails)
    {
        Set<WorldPoint> nodes = new HashSet<>();
        Map<WorldPoint, Map<WorldPoint, Integer>> adj = new HashMap<>();
        Map<WorldPoint, Map<WorldPoint, Leg.Transport>> trans = new HashMap<>();

        for (Trail t : trails) addWithinTrailEdges(t, nodes, adj, trans);
        addJunctionEdges(nodes, adj);
        return new TrailGraph(nodes, adj, trans);
    }

    private static void addWithinTrailEdges(Trail t, Set<WorldPoint> nodes,
        Map<WorldPoint, Map<WorldPoint, Integer>> adj,
        Map<WorldPoint, Map<WorldPoint, Leg.Transport>> trans)
    {
        List<TrailEvent> events = t.events();
        WorldPoint prevTile = null;
        TrailEvent.Transport pendingTransport = null;
        for (TrailEvent ev : events)
        {
            if (ev instanceof TrailEvent.Tile tile)
            {
                nodes.add(tile.tile());
                if (prevTile != null)
                {
                    if (pendingTransport != null)
                    {
                        Leg.Transport tr = new Leg.Transport(
                            pendingTransport.tile(),
                            pendingTransport.option(),
                            pendingTransport.targetId(),
                            pendingTransport.targetKind(),
                            pendingTransport.param0(),
                            pendingTransport.param1());
                        addEdge(adj, prevTile, tile.tile(), 1);
                        trans.computeIfAbsent(prevTile, k -> new HashMap<>())
                             .put(tile.tile(), tr);
                        trans.computeIfAbsent(tile.tile(), k -> new HashMap<>())
                             .put(prevTile, tr);
                        pendingTransport = null;
                    }
                    else
                    {
                        addEdge(adj, prevTile, tile.tile(), 1);
                    }
                }
                prevTile = tile.tile();
            }
            else if (ev instanceof TrailEvent.Transport tr)
            {
                pendingTransport = tr;
            }
        }
    }

    private static void addEdge(Map<WorldPoint, Map<WorldPoint, Integer>> adj,
                                WorldPoint a, WorldPoint b, int cost)
    {
        if (a.equals(b)) return;
        adj.computeIfAbsent(a, k -> new HashMap<>())
           .merge(b, cost, Math::min);
        adj.computeIfAbsent(b, k -> new HashMap<>())
           .merge(a, cost, Math::min);
    }

    /** Junction edges: any two nodes on the same plane within Chebyshev <= 1
     *  of each other get a cost-{0|1} edge. Spatial-hashed so we don't do
     *  O(N^2) over all node pairs. */
    private static void addJunctionEdges(Set<WorldPoint> nodes,
                                         Map<WorldPoint, Map<WorldPoint, Integer>> adj)
    {
        // Bucket nodes by (plane, x>>4, y>>4) — 16-tile boxes; only compare
        // a node against nodes in the same bucket and the 8 neighbour
        // buckets. For 10k tiles this brings build to typical-case O(N).
        Map<Long, java.util.ArrayList<WorldPoint>> buckets = new HashMap<>();
        for (WorldPoint p : nodes)
        {
            long key = bucketKey(p.getPlane(), p.getX() >> 4, p.getY() >> 4);
            buckets.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(p);
        }
        for (WorldPoint p : nodes)
        {
            int bx = p.getX() >> 4;
            int by = p.getY() >> 4;
            for (int dx = -1; dx <= 1; dx++)
            {
                for (int dy = -1; dy <= 1; dy++)
                {
                    long key = bucketKey(p.getPlane(), bx + dx, by + dy);
                    var list = buckets.get(key);
                    if (list == null) continue;
                    for (WorldPoint q : list)
                    {
                        if (q == p) continue;
                        if (q.getPlane() != p.getPlane()) continue;
                        int chebX = Math.abs(q.getX() - p.getX());
                        int chebY = Math.abs(q.getY() - p.getY());
                        if (chebX > 1 || chebY > 1) continue;
                        int cost = Math.max(chebX, chebY); // 0 or 1
                        addEdge(adj, p, q, cost);
                    }
                }
            }
        }
    }

    private static long bucketKey(int plane, int bx, int by)
    {
        // Pack plane (3 bits) + bx (24 bits) + by (24 bits). Trails span
        // OSRS world coords (~3000-4000) — 24 bits is more than enough.
        return ((long) (plane & 0x7) << 48)
             | ((long) (bx & 0xFFFFFF) << 24)
             | ((long) (by & 0xFFFFFF));
    }
}
