package net.runelite.client.plugins.recorder.trail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import net.runelite.api.coords.WorldPoint;

/** A* planner over a {@link TrailGraph}. Heuristic = Chebyshev distance
 *  to target on the same plane (admissible — every walk and transport
 *  edge costs at least 1). */
@RequiredArgsConstructor
public final class TrailPlanner
{
    private final TrailGraph graph;

    public Optional<TrailPath> plan(WorldPoint from, WorldPoint to)
    {
        if (from == null || to == null) return Optional.empty();
        WorldPoint snapFrom = snapToGraph(from);
        if (snapFrom == null) return Optional.empty();
        WorldPoint snapTo = snapToGraph(to);
        if (snapTo == null) return Optional.empty();
        from = snapFrom; to = snapTo;
        if (from.equals(to))
        {
            return Optional.of(new TrailPath(List.of(new Leg.Walk(List.of(from)))));
        }

        Map<WorldPoint, Integer> gScore = new HashMap<>();
        Map<WorldPoint, WorldPoint> cameFrom = new HashMap<>();
        gScore.put(from, 0);
        PriorityQueue<Node> open = new PriorityQueue<>();
        open.add(new Node(from, heuristic(from, to)));
        Set<WorldPoint> closed = new HashSet<>();

        while (!open.isEmpty())
        {
            Node cur = open.poll();
            if (cur.tile.equals(to))
            {
                return Optional.of(buildPath(cameFrom, cur.tile));
            }
            if (!closed.add(cur.tile)) continue;
            int curG = gScore.get(cur.tile);
            for (Map.Entry<WorldPoint, Integer> en : graph.neighbours(cur.tile).entrySet())
            {
                WorldPoint nb = en.getKey();
                int nextG = curG + en.getValue();
                Integer prev = gScore.get(nb);
                if (prev == null || nextG < prev)
                {
                    gScore.put(nb, nextG);
                    cameFrom.put(nb, cur.tile);
                    open.add(new Node(nb, nextG + heuristic(nb, to)));
                }
            }
        }
        return Optional.empty();
    }

    private static int heuristic(WorldPoint a, WorldPoint b)
    {
        // Chebyshev — admissible because every edge costs >= 1.
        if (a.getPlane() != b.getPlane())
        {
            return Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getY() - b.getY())) + 1;
        }
        return Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getY() - b.getY()));
    }

    /** Walk back through {@code cameFrom} and emit a {@link TrailPath} of
     *  alternating walk/transport legs. Two consecutive walk legs are
     *  coalesced into one. */
    private TrailPath buildPath(Map<WorldPoint, WorldPoint> cameFrom, WorldPoint end)
    {
        // Reconstruct tile chain start -> end.
        List<WorldPoint> chain = new ArrayList<>();
        WorldPoint cur = end;
        while (cur != null) { chain.add(cur); cur = cameFrom.get(cur); }
        Collections.reverse(chain);

        List<Leg> legs = new ArrayList<>();
        List<WorldPoint> walkBuf = new ArrayList<>();
        walkBuf.add(chain.get(0));
        for (int i = 1; i < chain.size(); i++)
        {
            WorldPoint a = chain.get(i - 1);
            WorldPoint b = chain.get(i);
            Leg.Transport tr = graph.transportBetween(a, b);
            if (tr != null)
            {
                if (!walkBuf.isEmpty())
                {
                    legs.add(new Leg.Walk(new ArrayList<>(walkBuf)));
                    walkBuf.clear();
                }
                legs.add(tr);
                walkBuf.add(b);
            }
            else
            {
                walkBuf.add(b);
            }
        }
        if (walkBuf.size() > 1)
        {
            legs.add(new Leg.Walk(new ArrayList<>(walkBuf)));
        }
        else if (legs.isEmpty() && !walkBuf.isEmpty())
        {
            // Single-tile path — emit it as a one-tile walk so the walker
            // has something to do.
            legs.add(new Leg.Walk(new ArrayList<>(walkBuf)));
        }
        return new TrailPath(legs);
    }

    /** Generous snap radius for the player's CURRENT tile — we don't need
     *  the player to be standing exactly on a recorded trail tile; the
     *  walker's WALK click will route the player to the snapped trail
     *  tile via the OSRS pathfinder. 32 tiles is roughly minimap-walk
     *  range; anything further means the user is in the wrong area
     *  entirely. */
    private static final int SNAP_RADIUS = 32;

    /** Snap {@code p} to the nearest graph node on the same plane within
     *  {@link #SNAP_RADIUS} tiles. Returns {@code p} unchanged if it is
     *  already on the graph, the closest qualifying node otherwise, or
     *  null if nothing on the same plane is within range. Euclidean
     *  distance breaks Chebyshev ties for determinism. */
    private WorldPoint snapToGraph(WorldPoint p)
    {
        if (graph.nodes().contains(p)) return p;
        WorldPoint best = null;
        int bestCheb = Integer.MAX_VALUE;
        int bestEucSq = Integer.MAX_VALUE;
        for (WorldPoint n : graph.nodes())
        {
            if (n.getPlane() != p.getPlane()) continue;
            int dx = Math.abs(n.getX() - p.getX());
            int dy = Math.abs(n.getY() - p.getY());
            int cheb = Math.max(dx, dy);
            if (cheb > SNAP_RADIUS) continue;
            int eucSq = dx * dx + dy * dy;
            if (cheb < bestCheb || (cheb == bestCheb && eucSq < bestEucSq))
            {
                best = n;
                bestCheb = cheb;
                bestEucSq = eucSq;
            }
        }
        return best;
    }

    private static final class Node implements Comparable<Node>
    {
        final WorldPoint tile;
        final int f;
        Node(WorldPoint tile, int f) { this.tile = tile; this.f = f; }
        @Override public int compareTo(Node o) { return Integer.compare(this.f, o.f); }
    }
}
