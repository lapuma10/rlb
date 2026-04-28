package net.runelite.client.plugins.recorder.walker;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;

/**
 * Pure 8-connected BFS over engine collision flags. Given a player tile and a
 * depth budget, returns a {@link Map} keyed by reachable tile that records the
 * shortest path back to the origin and the per-tile distance. Delegates the
 * per-step "can I cross from A to B?" check to
 * {@link WorldArea#canTravelInDirection(WorldView, int, int)}, which already
 * encodes the engine's full wall + diagonal flag matrix — so the BFS sees the
 * same world the player does.
 *
 * <p>The class is final and stateless; callers invoke {@link #compute} to
 * snapshot a {@link Map} from the current scene. No client-thread hopping
 * happens here — callers are expected to invoke {@link #compute} on the
 * client thread (or with a {@link WorldView} that's safe to read off-thread).
 *
 * <p>Frontier tiles are tiles whose 8-neighbour expansion was BLOCKED by a
 * collision flag. They are the obstacle-handler's hunting ground: a closed
 * gate, a fence, a locked door — anything the BFS couldn't traverse but
 * could potentially be made traversable by clicking the right object verb.
 */
public final class Reachability
{
    /** Default BFS depth cap. 16 tiles covers a comfortable click radius
     *  (the engine's minimap click-walk radius is ~14-20 tiles depending
     *  on minimap zoom) and matches dax-style continuous re-planning. */
    public static final int DEFAULT_DEPTH = 16;

    /** 8-connected neighbour offsets. Ordering: cardinal first, then
     *  diagonal — gives the BFS a slight preference for cardinal moves
     *  when multiple tiles are equidistant, mirroring how the OSRS
     *  pathfinder picks tiles. */
    private static final int[] DX = {0, 0, 1, -1, 1, 1, -1, -1};
    private static final int[] DY = {1, -1, 0, 0, 1, -1, 1, -1};

    private Reachability() {}

    /**
     * Compute a {@link ReachabilityMap} from {@code origin} expanding up to
     * {@code depth} tiles in 8 directions. Returns an empty map if
     * {@code origin} is null or the world view has no collision data
     * (logged-out / scene not yet loaded).
     */
    public static ReachabilityMap compute(WorldView wv, WorldPoint origin, int depth)
    {
        if (wv == null || origin == null || depth < 0)
        {
            return ReachabilityMap.empty(origin);
        }
        if (wv.getCollisionMaps() == null)
        {
            return ReachabilityMap.empty(origin);
        }

        int plane = origin.getPlane();
        Map<Long, Node> visited = new HashMap<>();
        Set<Long> frontier = new HashSet<>();
        Deque<long[]> queue = new ArrayDeque<>();

        long originKey = packXY(origin.getX(), origin.getY());
        visited.put(originKey, new Node(origin.getX(), origin.getY(), 0, 0));
        queue.add(new long[]{origin.getX(), origin.getY(), 0});

        while (!queue.isEmpty())
        {
            long[] cur = queue.poll();
            int x = (int) cur[0], y = (int) cur[1], d = (int) cur[2];
            if (d >= depth) continue;

            WorldArea here = new WorldArea(x, y, 1, 1, plane);
            boolean blockedThisTile = false;
            for (int i = 0; i < 8; i++)
            {
                int nx = x + DX[i], ny = y + DY[i];
                long key = packXY(nx, ny);
                if (visited.containsKey(key)) continue;
                boolean canMove;
                try
                {
                    canMove = here.canTravelInDirection(wv, DX[i], DY[i]);
                }
                catch (Throwable th)
                {
                    // Collision data may be missing for tiles outside the
                    // currently-loaded scene window. Treat as blocked rather
                    // than crashing the BFS — frontier collects the cell so
                    // the obstacle handler can still consider it.
                    canMove = false;
                }
                if (!canMove)
                {
                    blockedThisTile = true;
                    continue;
                }
                visited.put(key, new Node(nx, ny, d + 1, packXY(x, y)));
                queue.add(new long[]{nx, ny, d + 1});
            }
            if (blockedThisTile) frontier.add(packXY(x, y));
        }

        return new ReachabilityMap(origin, plane, visited, frontier);
    }

    /** Convenience overload that uses {@link #DEFAULT_DEPTH}. */
    public static ReachabilityMap compute(WorldView wv, WorldPoint origin)
    {
        return compute(wv, origin, DEFAULT_DEPTH);
    }

    static long packXY(int x, int y)
    {
        return ((long) x << 32) | (y & 0xffffffffL);
    }

    static int unpackX(long key)
    {
        return (int) (key >> 32);
    }

    static int unpackY(long key)
    {
        return (int) (key & 0xffffffffL);
    }

    /** Per-tile BFS metadata. {@code parentKey} is 0 only on the origin
     *  (every other tile points back at the cell it was reached from);
     *  origin's {@code parentKey} is also 0 — that's safe because the
     *  origin's distance is 0 and {@link ReachabilityMap#pathTo} stops
     *  walking the chain when {@code distance == 0}. */
    static final class Node
    {
        final int x;
        final int y;
        final int distance;
        final long parentKey;
        Node(int x, int y, int distance, long parentKey)
        {
            this.x = x; this.y = y; this.distance = distance; this.parentKey = parentKey;
        }
    }

    /**
     * A snapshot of the BFS result. Immutable; safe to pass between threads.
     * Contains only the origin's plane — querying tiles on a different plane
     * always returns "unreachable".
     */
    public static final class ReachabilityMap
    {
        private final WorldPoint origin;
        private final int plane;
        private final Map<Long, Node> visited;
        private final Set<Long> frontier;

        ReachabilityMap(WorldPoint origin, int plane,
                        Map<Long, Node> visited, Set<Long> frontier)
        {
            this.origin = origin;
            this.plane = plane;
            this.visited = visited;
            this.frontier = frontier;
        }

        static ReachabilityMap empty(@Nullable WorldPoint origin)
        {
            return new ReachabilityMap(origin,
                origin == null ? 0 : origin.getPlane(),
                Collections.emptyMap(), Collections.emptySet());
        }

        @Nullable public WorldPoint origin() { return origin; }
        public int plane() { return plane; }
        public int size() { return visited.size(); }

        /** True if {@code p} was reached by the BFS (and is on the same
         *  plane as the origin). */
        public boolean isReachable(WorldPoint p)
        {
            if (p == null || p.getPlane() != plane) return false;
            return visited.containsKey(packXY(p.getX(), p.getY()));
        }

        /** Distance in 8-connected hops from origin to {@code p}, or -1
         *  if {@code p} is unreachable / on a different plane. */
        public int distance(WorldPoint p)
        {
            if (p == null || p.getPlane() != plane) return -1;
            Node n = visited.get(packXY(p.getX(), p.getY()));
            return n == null ? -1 : n.distance;
        }

        /** Reconstruct the path from origin to {@code p}, inclusive. Empty
         *  list if {@code p} is unreachable. The first element is the
         *  origin; the last is {@code p}. */
        public List<WorldPoint> pathTo(WorldPoint p)
        {
            if (!isReachable(p)) return Collections.emptyList();
            ArrayList<WorldPoint> reversed = new ArrayList<>();
            long key = packXY(p.getX(), p.getY());
            while (true)
            {
                Node n = visited.get(key);
                if (n == null) return Collections.emptyList();
                reversed.add(new WorldPoint(n.x, n.y, plane));
                if (n.distance == 0) break;
                key = n.parentKey;
            }
            Collections.reverse(reversed);
            return reversed;
        }

        /** Every tile the BFS reached, in no particular order. */
        public List<WorldPoint> reachableTiles()
        {
            ArrayList<WorldPoint> out = new ArrayList<>(visited.size());
            for (Node n : visited.values())
            {
                out.add(new WorldPoint(n.x, n.y, plane));
            }
            return out;
        }

        /** Tiles the BFS reached whose 8-neighbour expansion hit a
         *  collision wall. These are the candidates the obstacle handler
         *  scans for known traversal verbs (gate, ladder, stile, etc.). */
        public List<WorldPoint> frontier()
        {
            ArrayList<WorldPoint> out = new ArrayList<>(frontier.size());
            for (long key : frontier)
            {
                out.add(new WorldPoint(unpackX(key), unpackY(key), plane));
            }
            return out;
        }
    }
}
