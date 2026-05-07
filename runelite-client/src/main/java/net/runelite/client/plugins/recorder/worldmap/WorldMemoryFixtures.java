package net.runelite.client.plugins.recorder.worldmap;

import java.util.List;
import net.runelite.api.coords.WorldPoint;

/** Public test-only fixture builder for WorldMemory state. Lets tests
 *  in other packages (e.g. {@code nav.v2}) install snapshots without
 *  exposing the package-private {@link RegionChunkBuilder} or
 *  {@link MapStore#installSnapshotForTest(int, RegionChunkSnapshot)}.
 *
 *  <p>Production code does not call this — the scraper publishes
 *  through {@link MapStore#publish(int, RegionChunkBuilder)}. */
public final class WorldMemoryFixtures
{
    private WorldMemoryFixtures() {}

    /** Install a region snapshot built from a list of (x, y, plane,
     *  movementFlags) tile entries. {@code movement = 0} means a fully
     *  walkable open-floor tile. */
    public static void installRegion(MapStore store, int regionId,
                                     List<TileSpec> tiles)
    {
        RegionChunkBuilder b = new RegionChunkBuilder(regionId);
        for (TileSpec t : tiles)
        {
            b.setTile(t.x, t.y, t.plane, t.movement);
        }
        store.installSnapshotForTest(regionId, RegionChunkSnapshot.fromBuilder(b));
    }

    /** Same, plus a list of object sightings to attach to the region
     *  snapshot. */
    public static void installRegion(MapStore store, int regionId,
                                     List<TileSpec> tiles,
                                     List<EntitySighting> objects)
    {
        RegionChunkBuilder b = new RegionChunkBuilder(regionId);
        for (TileSpec t : tiles)
        {
            b.setTile(t.x, t.y, t.plane, t.movement);
        }
        for (EntitySighting o : objects)
        {
            b.objects.put(RegionChunkBuilder.packObjKey(o.id, o.lastTile), o);
        }
        store.installSnapshotForTest(regionId, RegionChunkSnapshot.fromBuilder(b));
    }

    public static TileSpec walkable(int x, int y, int plane)
    {
        return new TileSpec(x, y, plane, 0);
    }

    public static TileSpec withMovement(int x, int y, int plane, int movement)
    {
        return new TileSpec(x, y, plane, movement);
    }

    public static WorldPoint wp(int x, int y, int plane)
    {
        return new WorldPoint(x, y, plane);
    }

    public record TileSpec(int x, int y, int plane, int movement) {}
}
