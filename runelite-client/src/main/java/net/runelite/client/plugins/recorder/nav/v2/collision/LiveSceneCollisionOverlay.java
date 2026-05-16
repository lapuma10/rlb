package net.runelite.client.plugins.recorder.nav.v2.collision;

import javax.annotation.Nullable;
import net.runelite.api.CollisionData;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.Constants;
import net.runelite.api.coords.WorldPoint;

/** Defensive copy of the live 104×104 scene's collision flags, captured at
 *  one client-thread instant.
 *
 *  <p>Per CLAUDE.md threading rules: the {@link CollisionData}[] handed to
 *  {@link #capture(CollisionData[], int, int, int)} must already have been
 *  read on the client thread. This class only does an array copy (cheap,
 *  safe on either thread) of the already-fetched flags.
 *
 *  <p>Why defensive-copy: the live CollisionData arrays mutate as the
 *  player moves and objects spawn. The planner needs a snapshot that
 *  doesn't change underneath it. Per spec §10, "Live overlay introduces
 *  latency per plan call. Mitigation: snapshot built once at plan-call
 *  entry on client thread, then immutable."
 *
 *  <p>Storage cost: 104 × 104 × planeCount × 4 bytes — at most ~170 KB
 *  for all 4 planes. Negligible per plan call.
 *
 *  <p>This class is part of the Lane 2 deliverable for the observation-aware
 *  navigation engine. */
public final class LiveSceneCollisionOverlay
{
    private static final int SIZE = Constants.SCENE_SIZE;

    /** flags[plane] is either a defensive copy of the source CollisionData
     *  flag array, or {@code null} if that plane was unavailable. */
    private final int[][][] flags;

    private final int baseX;
    private final int baseY;
    private final int basePlane;

    private LiveSceneCollisionOverlay(int[][][] flags, int baseX, int baseY, int basePlane)
    {
        this.flags = flags;
        this.baseX = baseX;
        this.baseY = baseY;
        this.basePlane = basePlane;
    }

    /** Capture an immutable snapshot of the live scene's collision flags.
     *
     *  @param liveMaps  the result of {@code client.getCollisionMaps()};
     *                   array-of-plane, may be {@code null} if no scene is
     *                   loaded; individual planes may be {@code null} if
     *                   that plane has no data.
     *  @param baseX     scene origin world X (from
     *                   {@code WorldView.getBaseX()}).
     *  @param baseY     scene origin world Y.
     *  @param basePlane the player's current plane at capture time. Used
     *                   only for debug output.
     */
    public static LiveSceneCollisionOverlay capture(
        @Nullable CollisionData[] liveMaps, int baseX, int baseY, int basePlane)
    {
        if (liveMaps == null || liveMaps.length == 0)
        {
            return new LiveSceneCollisionOverlay(null, baseX, baseY, basePlane);
        }
        int[][][] copy = new int[liveMaps.length][][];
        for (int p = 0; p < liveMaps.length; p++)
        {
            CollisionData cd = liveMaps[p];
            if (cd == null) continue;
            int[][] src = cd.getFlags();
            if (src == null) continue;
            int[][] dst = new int[SIZE][SIZE];
            for (int x = 0; x < SIZE && x < src.length; x++)
            {
                int[] srcRow = src[x];
                if (srcRow == null) continue;
                System.arraycopy(srcRow, 0, dst[x], 0, Math.min(SIZE, srcRow.length));
            }
            copy[p] = dst;
        }
        return new LiveSceneCollisionOverlay(copy, baseX, baseY, basePlane);
    }

    /** Returns the captured live flags for {@code p}. If the tile is outside
     *  the scene or the plane is unavailable, returns
     *  {@link CollisionDataFlag#BLOCK_MOVEMENT_FULL} as a "no data" sentinel.
     *  {@link #containsTile(WorldPoint)} is the cleaner way to ask "do I
     *  have data for this tile?". */
    public int flagsAt(WorldPoint p)
    {
        return flagsAt(p.getX(), p.getY(), p.getPlane());
    }

    public int flagsAt(int worldX, int worldY, int plane)
    {
        if (!containsTile(worldX, worldY, plane))
        {
            return CollisionDataFlag.BLOCK_MOVEMENT_FULL;
        }
        return flags[plane][worldX - baseX][worldY - baseY];
    }

    /** True iff this overlay has a flag for {@code p}. */
    public boolean containsTile(WorldPoint p)
    {
        return containsTile(p.getX(), p.getY(), p.getPlane());
    }

    public boolean containsTile(int worldX, int worldY, int plane)
    {
        if (flags == null) return false;
        if (plane < 0 || plane >= flags.length) return false;
        if (flags[plane] == null) return false;
        int sx = worldX - baseX;
        int sy = worldY - baseY;
        return sx >= 0 && sx < SIZE && sy >= 0 && sy < SIZE;
    }

    /** Scene origin world X — exposed for debug output and downstream
     *  diagnostics (Lane 6 trace). */
    public int baseX() { return baseX; }

    /** Scene origin world Y. */
    public int baseY() { return baseY; }

    /** The player's plane at capture time. */
    public int basePlane() { return basePlane; }

    /** True iff this overlay has no data at all. */
    public boolean isEmpty()
    {
        return flags == null;
    }
}
