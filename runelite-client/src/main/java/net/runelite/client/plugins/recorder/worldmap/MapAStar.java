package net.runelite.client.plugins.recorder.worldmap;

import java.util.*;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.coords.WorldPoint;

/** Multi-target Dijkstra over a {@link RegionChunkSnapshot}'s collision
 *  flags. Wall/diagonal semantics mirror {@code WorldArea.canTravelInDirection}
 *  (1×1 entity case). Runs on a worker thread; reads only the snapshot. */
public final class MapAStar
{
    private MapAStar() {}

    private static final int[] DX = {0, 0, 1, -1, 1, 1, -1, -1};
    private static final int[] DY = {1, -1, 0, 0, 1, -1, 1, -1};

    /** Dijkstra from {@code origin} that terminates when every tile in
     *  {@code goals} is either visited or beyond the caps. Returns a map
     *  keyed by every input goal — value is the path length, or -1 if
     *  unreachable within the caps. */
    public static Map<WorldPoint, Integer> dijkstraToAny(
        RegionChunkSnapshot snap, WorldPoint origin, Set<WorldPoint> goals,
        int maxPathLength, int maxExpandedTiles)
    {
        Map<WorldPoint, Integer> result = new HashMap<>();
        for (WorldPoint g : goals) result.put(g, -1);
        if (snap == null || origin == null || goals.isEmpty()) return result;

        int plane = origin.getPlane();
        // Confirm origin and all goals share the same plane — required by v1.
        for (WorldPoint g : goals)
        {
            if (g.getPlane() != plane) return result;
        }

        Set<Long> goalKeys = new HashSet<>();
        for (WorldPoint g : goals)
            goalKeys.add(RegionChunkSnapshot.packTileKey(g.getX(), g.getY(), g.getPlane()));
        Set<Long> remainingGoals = new HashSet<>(goalKeys);

        PriorityQueue<long[]> pq = new PriorityQueue<>(
            Comparator.comparingInt(a -> (int) a[0]));
        Map<Long, Integer> dist = new HashMap<>();
        long oKey = RegionChunkSnapshot.packTileKey(
            origin.getX(), origin.getY(), plane);
        dist.put(oKey, 0);
        pq.add(new long[]{0, oKey});

        int expanded = 0;

        while (!pq.isEmpty() && expanded < maxExpandedTiles && !remainingGoals.isEmpty())
        {
            long[] head = pq.poll();
            int d = (int) head[0];
            long k = head[1];
            if (d > dist.getOrDefault(k, Integer.MAX_VALUE)) continue;
            expanded++;

            if (goalKeys.contains(k))
            {
                WorldPoint p = new WorldPoint(
                    RegionChunkSnapshot.unpackX(k),
                    RegionChunkSnapshot.unpackY(k),
                    RegionChunkSnapshot.unpackPlane(k));
                result.put(p, d);
                remainingGoals.remove(k);
            }
            if (d >= maxPathLength) continue;

            int x = RegionChunkSnapshot.unpackX(k);
            int y = RegionChunkSnapshot.unpackY(k);
            for (int i = 0; i < 8; i++)
            {
                int nx = x + DX[i], ny = y + DY[i];
                if (!canTravel(snap, x, y, plane, DX[i], DY[i])) continue;
                long nKey = RegionChunkSnapshot.packTileKey(nx, ny, plane);
                int nd = d + 1;
                if (nd < dist.getOrDefault(nKey, Integer.MAX_VALUE))
                {
                    dist.put(nKey, nd);
                    pq.add(new long[]{nd, nKey});
                }
            }
        }
        return result;
    }

    /** Mirror of {@code WorldArea.canTravelInDirection} for a 1×1 entity.
     *  Builds the same xFlags/yFlags/xyFlags masks WorldArea builds and
     *  tests them against the destination tile's movement int. The OSRS
     *  collision data is dual-encoded (both sides of every wall see it),
     *  so testing only the destination tile is sufficient — see the
     *  WorldArea source at runelite-api/.../coords/WorldArea.java:261-360. */
    private static boolean canTravel(RegionChunkSnapshot snap,
                                     int x, int y, int plane, int dx, int dy)
    {
        dx = Integer.signum(dx);
        dy = Integer.signum(dy);
        if (dx == 0 && dy == 0) return true;

        RegionChunkSnapshot.TileEntry destTile = snap.tile(x + dx, y + dy, plane);
        if (destTile == null) return false;
        int destFlags = destTile.movement;

        int xFlags  = CollisionDataFlag.BLOCK_MOVEMENT_FULL;
        int yFlags  = CollisionDataFlag.BLOCK_MOVEMENT_FULL;
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
}
