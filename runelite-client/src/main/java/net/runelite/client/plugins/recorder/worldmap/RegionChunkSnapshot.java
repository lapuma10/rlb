package net.runelite.client.plugins.recorder.worldmap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.runelite.api.coords.WorldPoint;

/**
 * Immutable snapshot of a region chunk's collision + object data. The planner
 * reads only this type; the builder ({@link RegionChunkBuilder}) is
 * package-private and only the scraper writes it.
 *
 * <p>Stored as primitive maps to keep the snapshot construction cheap and
 * the planner's reads cache-friendly. {@link TileEntry} is exposed for
 * iteration; per-coord lookups are O(1) via {@link #tile}.
 */
public final class RegionChunkSnapshot
{
    private final int regionId;
    private final int gameRevision;
    private final long lastScrapedAt;
    private final Map<Long, Integer> tiles;     // packedKey → movement int
    private final List<EntitySighting> objects;

    private RegionChunkSnapshot(int regionId, int gameRevision, long lastScrapedAt,
                                Map<Long, Integer> tiles, List<EntitySighting> objects)
    {
        this.regionId = regionId;
        this.gameRevision = gameRevision;
        this.lastScrapedAt = lastScrapedAt;
        this.tiles = tiles;
        this.objects = objects;
    }

    public static RegionChunkSnapshot empty(int regionId)
    {
        return new RegionChunkSnapshot(regionId, 0, 0L,
            Collections.emptyMap(), Collections.emptyList());
    }

    static RegionChunkSnapshot fromBuilder(RegionChunkBuilder b)
    {
        // Defensive copies — guarantee the snapshot is fully detached
        // from any future mutation of the builder.
        Map<Long, Integer> tilesCopy = new HashMap<>(b.tiles);
        List<EntitySighting> objCopy = new ArrayList<>(b.objects.values());
        return new RegionChunkSnapshot(b.regionId, b.gameRevision, b.lastScrapedAt,
            Collections.unmodifiableMap(tilesCopy),
            Collections.unmodifiableList(objCopy));
    }

    public int regionId() { return regionId; }
    public int gameRevision() { return gameRevision; }
    public long lastScrapedAt() { return lastScrapedAt; }

    public List<TileEntry> tiles()
    {
        ArrayList<TileEntry> out = new ArrayList<>(tiles.size());
        for (Map.Entry<Long, Integer> e : tiles.entrySet())
        {
            long k = e.getKey();
            int movement = e.getValue();
            // los is reserved/always-0 in v1 (LOS bits live inside `movement`).
            out.add(new TileEntry(unpackX(k), unpackY(k), unpackPlane(k), movement, 0));
        }
        return Collections.unmodifiableList(out);
    }

    public List<EntitySighting> objects() { return objects; }

    /** Tile at (x,y,plane) or null if not in this chunk. */
    @Nullable
    public TileEntry tile(int x, int y, int plane)
    {
        Integer movement = tiles.get(packTileKey(x, y, plane));
        if (movement == null) return null;
        return new TileEntry(x, y, plane, movement, 0);
    }

    /** True if a tile exists in this chunk and is "standable" per the spec.
     *  v1 definition: tile exists and the movement int does not have the
     *  BLOCK_MOVEMENT_FULL bit-set (which is the OR of three engine bits —
     *  do NOT hard-code 0x40000; that's only one of the three).
     *
     *  <p>The "at least one neighbour can travel in" check is delegated to
     *  MapAStar's transition predicate at planner time — this method only
     *  handles the local "exists + not floor-blocked" portion. */
    public boolean isStandableLocal(int x, int y, int plane)
    {
        TileEntry t = tile(x, y, plane);
        if (t == null) return false;
        return (t.movement & net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_FULL) == 0;
    }

    public static long packTileKey(int x, int y, int plane)
    {
        // 24 bits x, 24 bits y, 4 bits plane.
        return ((long)(x & 0xffffff) << 28)
            |  ((long)(y & 0xffffff) << 4)
            |  (plane & 0xf);
    }

    public static int unpackX(long k)     { return (int)((k >> 28) & 0xffffff); }
    public static int unpackY(long k)     { return (int)((k >> 4)  & 0xffffff); }
    public static int unpackPlane(long k) { return (int)(k & 0xf); }

    public static final class TileEntry
    {
        public final int x, y, plane, movement, los;
        public TileEntry(int x, int y, int plane, int movement, int los)
        {
            this.x = x; this.y = y; this.plane = plane;
            this.movement = movement; this.los = los;
        }
        public WorldPoint asWorldPoint() { return new WorldPoint(x, y, plane); }
    }
}
