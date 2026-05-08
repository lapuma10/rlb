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
    public void plan_unknownTilesAcrossGap_routesAtPenaltyCost()
    {
        // Round-2 stabilization: when intermediate tiles aren't in the
        // snapshot, the planner crosses them at UNKNOWN_TILE_COST instead
        // of returning empty. The bot drives the partial plan; the
        // scraper fills in tiles as the player walks; the next replan
        // completes on a now-complete graph. Live failure that motivated
        // this: bank → pen route had a y=3239..3263 strip with sparse
        // snapshot data; strict planner NO_ROUTE'd, executor never moved.
        MapStore store = new MapStore(wm);
        int regionId = RegionIds.regionIdFor(3208, 3213);
        // Only the start + goal tiles are in the snapshot — the 6 tiles
        // between them are unknown.
        WorldMemoryFixtures.installRegion(store, regionId,
            List.of(walkable(3208, 3213, 0), walkable(3215, 3213, 0)));

        MultiRegionAStar a = new MultiRegionAStar(store, new TransportIndex(), wm);
        V2Path path = a.plan(new WorldPoint(3208, 3213, 0), new WorldPoint(3215, 3213, 0));

        assertFalse("permissive planner must route across unknown tiles", path.isEmpty());
        assertTrue("path crosses unknown tiles → cost ≥ Chebyshev distance",
            path.totalCost() >= 7);   // 7 steps min for Chebyshev distance of 7
    }

    @Test
    public void plan_crossPlaneSparseSource_routesViaTransport_doesNotFloodSourcePlane()
    {
        // Regression for the live failure that prompted the plane-aware
        // heuristic. Setup: start on plane 2 with sparse source-plane
        // data (forces unknown-tile walks at UNKNOWN_TILE_COST≈1.0).
        // Goal far north on plane 0. A single staircase transport
        // (Bottom-floor) is the only cross-plane edge.
        //
        // Without the plane-aware heuristic, A* sees same-plane walking
        // as cost-equivalent to (transport + destination-plane walk),
        // floods plane 2 outward, and exhausts maxExpandedTiles
        // returning EMPTY. With the plane-aware h, A* correctly prefers
        // transport descendants and finds the goal in bounded expansion.
        MapStore store = new MapStore(wm);
        int regionId = RegionIds.regionIdFor(3208, 3220);
        java.util.List<WorldMemoryFixtures.TileSpec> tiles = new java.util.ArrayList<>();
        // Start tile + a sparse plane-2 walkable corridor to the staircase
        // approach — leave the rest of plane 2 entirely unknown.
        tiles.add(walkable(3208, 3220, 2));
        for (int y = 3221; y <= 3229; y++) tiles.add(walkable(3206, y, 2));
        // Plane-0 corridor from staircase exit to goal.
        for (int y = 3228; y <= 3294; y++) tiles.add(walkable(3205, y, 0));
        for (int x = 3206; x <= 3235; x++) tiles.add(walkable(x, 3294, 0));
        tiles.add(walkable(3235, 3295, 0));
        WorldMemoryFixtures.installRegion(store, regionId, tiles);

        TransportIndex idx = new TransportIndex();
        idx.add(new TransportEdge(
            new WorldPoint(3206, 3229, 2),
            new WorldPoint(3205, 3228, 0),
            56231, "Staircase", "Bottom-floor",
            0, 0, "object",
            new WorldPoint(3206, 3229, 2),
            regionId, 1, 0L, 0L));

        MultiRegionAStar a = new MultiRegionAStar(store, idx, wm);
        V2Path path = a.plan(new WorldPoint(3208, 3220, 2), new WorldPoint(3235, 3295, 0));

        assertFalse("cross-plane plan with sparse source plane must succeed",
            path.isEmpty());
        boolean hasTransport = path.legs().stream().anyMatch(l -> l instanceof V2Leg.Transport);
        assertTrue("path must use the staircase transport, not flood plane 2",
            hasTransport);
    }

    @Test
    public void plan_blockedTilesRespected_eachStepPrefersKnownWalkable()
    {
        // Permissive mode still respects collision flags on known tiles.
        // Path from (3208,3213,0) → (3211,3213,0):
        //   (3209,3213) is BLOCK_MOVEMENT_FULL — must not be stepped on
        //   (3209,3214) is walkable — alternate northern route
        // Planner must produce a non-empty path that avoids (3209,3213).
        MapStore store = new MapStore(wm);
        int regionId = RegionIds.regionIdFor(3208, 3213);
        java.util.List<WorldMemoryFixtures.TileSpec> tiles = new java.util.ArrayList<>();
        tiles.add(walkable(3208, 3213, 0));
        tiles.add(walkable(3210, 3213, 0));
        tiles.add(walkable(3211, 3213, 0));
        tiles.add(walkable(3209, 3214, 0));   // alternate path
        tiles.add(walkable(3210, 3214, 0));
        // The blocked tile (with collision flags forbidding movement).
        tiles.add(WorldMemoryFixtures.withMovement(3209, 3213, 0,
            net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_FULL));
        WorldMemoryFixtures.installRegion(store, regionId, tiles);

        MultiRegionAStar a = new MultiRegionAStar(store, new TransportIndex(), wm);
        V2Path path = a.plan(new WorldPoint(3208, 3213, 0), new WorldPoint(3211, 3213, 0));

        assertFalse("alternate northern route should be planable", path.isEmpty());
        boolean steppedOnBlocked = path.allTiles().stream()
            .anyMatch(t -> t.getX() == 3209 && t.getY() == 3213 && t.getPlane() == 0);
        assertFalse("plan must not step on the BLOCK_MOVEMENT_FULL tile",
            steppedOnBlocked);
    }

    @Test
    public void plan_withNoise_producesVariation()
    {
        // 5×3 grid with two diagonal-equivalent paths between
        // (3208,3213) → (3212,3215). With noise, A* should occasionally
        // pick different tile sequences even though all share the same
        // total cost.
        MapStore store = new MapStore(wm);
        int regionId = RegionIds.regionIdFor(3208, 3213);
        List<WorldMemoryFixtures.TileSpec> tiles = new ArrayList<>();
        for (int x = 3208; x <= 3215; x++)
        {
            for (int y = 3213; y <= 3217; y++) tiles.add(walkable(x, y, 0));
        }
        WorldMemoryFixtures.installRegion(store, regionId, tiles);

        MultiRegionAStar a = new MultiRegionAStar(store, new TransportIndex(), wm);
        WorldPoint from = new WorldPoint(3208, 3213, 0);
        WorldPoint to = new WorldPoint(3215, 3217, 0);
        java.util.Set<String> distinctTileSequences = new java.util.HashSet<>();
        for (int trial = 0; trial < 50; trial++)
        {
            V2Path p = a.plan(from, to, 0.12);
            distinctTileSequences.add(tileSeqKey(p));
        }
        assertTrue("noise produces ≥3 distinct tile sequences over 50 trials (got "
            + distinctTileSequences.size() + ")",
            distinctTileSequences.size() >= 3);
    }

    private static String tileSeqKey(V2Path p)
    {
        StringBuilder sb = new StringBuilder();
        for (WorldPoint t : p.allTiles())
        {
            sb.append(t.getX()).append(',').append(t.getY()).append(';');
        }
        return sb.toString();
    }
}
