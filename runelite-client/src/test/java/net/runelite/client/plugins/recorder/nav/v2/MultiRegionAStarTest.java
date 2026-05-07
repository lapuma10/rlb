package net.runelite.client.plugins.recorder.nav.v2;

import java.util.ArrayList;
import java.util.List;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.worldmap.MapStore;
import net.runelite.client.plugins.recorder.worldmap.RegionIds;
import net.runelite.client.plugins.recorder.worldmap.TransportEdge;
import net.runelite.client.plugins.recorder.worldmap.TransportIndex;
import net.runelite.client.plugins.recorder.worldmap.WorldMemoryConfig;
import net.runelite.client.plugins.recorder.worldmap.WorldMemoryFixtures;
import org.junit.Test;
import static net.runelite.client.plugins.recorder.worldmap.WorldMemoryFixtures.walkable;
import static org.junit.Assert.*;

public class MultiRegionAStarTest
{
    private final WorldMemoryConfig wm = new WorldMemoryConfig();

    @Test
    public void plan_sameRegion_samePlane_returnsSingleWalkLeg()
    {
        MapStore store = new MapStore(wm);
        int regionId = RegionIds.regionIdFor(3208, 3213);
        List<WorldMemoryFixtures.TileSpec> tiles = new ArrayList<>();
        for (int x = 3208; x <= 3212; x++) tiles.add(walkable(x, 3213, 0));
        WorldMemoryFixtures.installRegion(store, regionId, tiles);

        MultiRegionAStar a = new MultiRegionAStar(store, new TransportIndex(), wm);
        V2Path path = a.plan(new WorldPoint(3208, 3213, 0), new WorldPoint(3212, 3213, 0));

        assertFalse(path.isEmpty());
        assertEquals(1, path.legs().size());
        assertTrue(path.legs().get(0) instanceof V2Leg.Walk);
        V2Leg.Walk walk = (V2Leg.Walk) path.legs().get(0);
        assertEquals(5, walk.tiles().size());
        assertEquals(new WorldPoint(3208, 3213, 0), walk.start());
        assertEquals(new WorldPoint(3212, 3213, 0), walk.end());
    }

    @Test
    public void plan_acrossRegions_viaWalkOnly_stitches()
    {
        MapStore store = new MapStore(wm);
        int regionA = RegionIds.regionIdFor(3208, 3263);
        int regionB = RegionIds.regionIdFor(3208, 3264);
        assertNotEquals("test fixture sanity: regions differ", regionA, regionB);

        List<WorldMemoryFixtures.TileSpec> tilesA = new ArrayList<>();
        for (int y = 3232; y <= 3263; y++) tilesA.add(walkable(3208, y, 0));
        WorldMemoryFixtures.installRegion(store, regionA, tilesA);

        List<WorldMemoryFixtures.TileSpec> tilesB = new ArrayList<>();
        for (int y = 3264; y <= 3270; y++) tilesB.add(walkable(3208, y, 0));
        WorldMemoryFixtures.installRegion(store, regionB, tilesB);

        MultiRegionAStar a = new MultiRegionAStar(store, new TransportIndex(), wm);
        V2Path path = a.plan(new WorldPoint(3208, 3260, 0), new WorldPoint(3208, 3268, 0));

        assertFalse("A* must stitch two regions when both snapshots are loaded",
            path.isEmpty());
        WorldPoint start = path.allTiles().get(0);
        WorldPoint end = path.allTiles().get(path.allTiles().size() - 1);
        assertEquals(new WorldPoint(3208, 3260, 0), start);
        assertEquals(new WorldPoint(3208, 3268, 0), end);
        assertEquals("8 cardinal hops y=3260→3268", 8, path.totalCost());
    }

    @Test
    public void plan_acrossPlanes_viaTransportEdge_insertsTransportLeg()
    {
        MapStore store = new MapStore(wm);
        int regionId = RegionIds.regionIdFor(3206, 3229);
        List<WorldMemoryFixtures.TileSpec> tiles = new ArrayList<>();
        // Plane 2: walkable from (3206,3229) east to (3210,3229)
        for (int x = 3206; x <= 3210; x++) tiles.add(walkable(x, 3229, 2));
        // Plane 1: walkable from (3201,3228) east to (3205,3228)
        for (int x = 3201; x <= 3205; x++) tiles.add(walkable(x, 3228, 1));
        WorldMemoryFixtures.installRegion(store, regionId, tiles);

        TransportIndex tidx = new TransportIndex();
        WorldPoint stairFrom = new WorldPoint(3206, 3229, 2);
        WorldPoint stairTo = new WorldPoint(3205, 3228, 1);
        tidx.add(new TransportEdge(stairFrom, stairTo,
            16671, "Staircase", "Climb-down",
            53, 14, "GAME_OBJECT_FIRST_OPTION",
            stairFrom, regionId, 1, 1L, 1200L));

        MultiRegionAStar a = new MultiRegionAStar(store, tidx, wm);
        V2Path path = a.plan(new WorldPoint(3210, 3229, 2), new WorldPoint(3201, 3228, 1));

        assertFalse("cross-plane plan via known stair must succeed", path.isEmpty());
        boolean hasTransport = path.legs().stream()
            .anyMatch(leg -> leg instanceof V2Leg.Transport);
        assertTrue("path must include the staircase transport leg", hasTransport);
        assertEquals(new WorldPoint(3210, 3229, 2), path.allTiles().get(0));
        assertEquals(new WorldPoint(3201, 3228, 1),
            path.allTiles().get(path.allTiles().size() - 1));
    }

    @Test
    public void plan_destinationUnreachable_returnsEmpty()
    {
        MapStore store = new MapStore(wm);
        int regionId = RegionIds.regionIdFor(3208, 3213);
        WorldMemoryFixtures.installRegion(store, regionId,
            List.of(walkable(3208, 3213, 0)));

        MultiRegionAStar a = new MultiRegionAStar(store, new TransportIndex(), wm);
        V2Path path = a.plan(new WorldPoint(3208, 3213, 0), new WorldPoint(3215, 3213, 0));

        assertTrue("unreachable destination → empty path", path.isEmpty());
    }

    @Test
    public void plan_noSnapshotForRegion_returnsEmpty()
    {
        MapStore store = new MapStore(wm);
        MultiRegionAStar a = new MultiRegionAStar(store, new TransportIndex(), wm);
        V2Path path = a.plan(new WorldPoint(3208, 3213, 0), new WorldPoint(3212, 3213, 0));
        assertTrue(path.isEmpty());
    }
}
