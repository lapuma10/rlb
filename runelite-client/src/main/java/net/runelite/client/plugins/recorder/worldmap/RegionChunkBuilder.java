package net.runelite.client.plugins.recorder.worldmap;

import java.util.HashMap;
import java.util.Map;
import net.runelite.api.coords.WorldPoint;

/** Mutable builder for a region chunk. Used by {@link SceneScraper} only.
 *  Package-private — readers see {@link RegionChunkSnapshot} instead.
 *
 *  <p>Thread-safety: a builder is owned by exactly one thread (the client
 *  thread) for its lifetime. {@link MapStore#publish} converts it to an
 *  immutable snapshot before any reader sees the data. */
final class RegionChunkBuilder
{
    final int regionId;
    int gameRevision;
    long lastScrapedAt;

    /** Tiles, keyed by packed (x,y,plane). Value is the 32-bit movement
     *  flags from CollisionData — LOS bits live in the same int (per spec).
     *  v1 stores movement only; the JSON `los` field is reserved space
     *  (always 0) for v2 to populate if a separate LOS read becomes useful. */
    final Map<Long, Integer> tiles = new HashMap<>();

    /** Object sightings in this region, keyed by (id, x, y, plane). */
    final Map<Long, EntitySighting> objects = new HashMap<>();

    RegionChunkBuilder(int regionId)
    {
        this.regionId = regionId;
    }

    static RegionChunkBuilder copyOf(RegionChunkSnapshot snap)
    {
        RegionChunkBuilder b = new RegionChunkBuilder(snap.regionId());
        b.gameRevision = snap.gameRevision();
        b.lastScrapedAt = snap.lastScrapedAt();
        for (RegionChunkSnapshot.TileEntry t : snap.tiles())
        {
            long key = RegionChunkSnapshot.packTileKey(t.x, t.y, t.plane);
            b.tiles.put(key, t.movement);
        }
        for (EntitySighting o : snap.objects())
        {
            long key = packObjKey(o.id, o.lastTile);
            b.objects.put(key, o);
        }
        return b;
    }

    void setTile(int x, int y, int plane, int movement)
    {
        long key = RegionChunkSnapshot.packTileKey(x, y, plane);
        tiles.put(key, movement);
    }

    void recordObject(int objectId, String name, WorldPoint tile, String[] actions, long now)
    {
        long key = packObjKey(objectId, tile);
        EntitySighting prev = objects.get(key);
        if (prev == null)
        {
            objects.put(key, new EntitySighting(
                EntitySighting.Kind.OBJECT, objectId, name, tile, 1, now));
        }
        else
        {
            objects.put(key, prev.withUpdatedSighting(tile, now));
        }
    }

    static long packObjKey(int id, WorldPoint t)
    {
        // 16 bits id, 16 bits x, 16 bits y, 4 bits plane, rest unused.
        return ((long)(id & 0xffff) << 48)
            |  ((long)(t.getX() & 0xffff) << 32)
            |  ((long)(t.getY() & 0xffff) << 16)
            |  (t.getPlane() & 0xf);
    }
}
