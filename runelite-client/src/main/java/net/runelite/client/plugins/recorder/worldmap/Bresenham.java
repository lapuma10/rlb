package net.runelite.client.plugins.recorder.worldmap;

import net.runelite.api.CollisionDataFlag;
import net.runelite.api.coords.WorldPoint;

/** Bresenham line-of-sight walker over a {@link RegionChunkSnapshot}'s
 *  collision flags. Mirror's RuneLite's own LOS algorithm but operates on
 *  persisted flags rather than a live WorldView — so it runs on the worker
 *  thread without a client-thread hop. */
public final class Bresenham
{
    private Bresenham() {}

    public static boolean hasLineOfSight(RegionChunkSnapshot snap,
                                         WorldPoint from, WorldPoint to)
    {
        if (from == null || to == null) return false;
        if (from.getPlane() != to.getPlane()) return false;
        int x0 = from.getX(), y0 = from.getY();
        int x1 = to.getX(),   y1 = to.getY();
        int plane = from.getPlane();

        int dx = Math.abs(x1 - x0), sx = x0 < x1 ? 1 : -1;
        int dy = -Math.abs(y1 - y0), sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;

        int cx = x0, cy = y0;
        while (true)
        {
            if (!(cx == x0 && cy == y0) && !(cx == x1 && cy == y1))
            {
                RegionChunkSnapshot.TileEntry t = snap.tile(cx, cy, plane);
                // Tile not scraped → conservatively treat as "no LOS" so
                // we don't walk into unknown territory expecting visibility.
                if (t == null) return false;
                if ((t.movement & CollisionDataFlag.BLOCK_LINE_OF_SIGHT_FULL) != 0)
                    return false;
            }
            if (cx == x1 && cy == y1) return true;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; cx += sx; }
            if (e2 <= dx) { err += dx; cy += sy; }
        }
    }
}
