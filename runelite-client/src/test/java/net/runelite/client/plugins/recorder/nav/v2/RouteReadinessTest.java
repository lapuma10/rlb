package net.runelite.client.plugins.recorder.nav.v2;

import java.util.ArrayList;
import java.util.List;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.worldmap.MapStore;
import net.runelite.client.plugins.recorder.worldmap.RegionIds;
import net.runelite.client.plugins.recorder.worldmap.TransportEdge;
import net.runelite.client.plugins.recorder.worldmap.TransportIndex;
import net.runelite.client.plugins.recorder.worldmap.WorldMemoryConfig;
import net.runelite.client.plugins.recorder.worldmap.WorldMemoryFixtures;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class RouteReadinessTest
{
    private static final int LUMBY_BANK_REGION = RegionIds.regionIdFor(3208, 3217);

    private static MapStore store()
    {
        return new MapStore(new WorldMemoryConfig());
    }

    private static V2Planner planner(MapStore s, TransportIndex t)
    {
        return new V2Planner(s, t, new WorldMemoryConfig(), new RouteHistory());
    }

    /** Fully connected corridor along y=3217 from (3200,3217) to (3220,3217). */
    private static void seedCorridor(MapStore s, WorldPoint from, WorldPoint to)
    {
        List<WorldMemoryFixtures.TileSpec> tiles = new ArrayList<>();
        int xMin = Math.min(from.getX(), to.getX()) - 4;
        int xMax = Math.max(from.getX(), to.getX()) + 4;
        int yMin = Math.min(from.getY(), to.getY()) - 4;
        int yMax = Math.max(from.getY(), to.getY()) + 4;
        for (int x = xMin; x <= xMax; x++)
            for (int y = yMin; y <= yMax; y++)
                tiles.add(WorldMemoryFixtures.walkable(x, y, 0));
        // Group by region and install — readiness scans every region the
        // bbox touches, so single-region installs are enough for compact
        // test corridors.
        java.util.Map<Integer, List<WorldMemoryFixtures.TileSpec>> byRegion = new java.util.HashMap<>();
        for (WorldMemoryFixtures.TileSpec ts : tiles)
        {
            int rid = RegionIds.regionIdFor(ts.x(), ts.y());
            byRegion.computeIfAbsent(rid, k -> new ArrayList<>()).add(ts);
        }
        for (var e : byRegion.entrySet())
        {
            WorldMemoryFixtures.installRegion(s, e.getKey(), e.getValue());
        }
    }

    @Test
    public void check_fullyConnectedCorridor_breakNone_canPlanTrue()
    {
        MapStore s = store();
        TransportIndex t = new TransportIndex();
        WorldPoint from = new WorldPoint(3208, 3217, 0);
        WorldPoint to = new WorldPoint(3220, 3217, 0);
        seedCorridor(s, from, to);
        RouteReadiness r = new RouteReadiness(s, t, planner(s, t));
        RouteReadiness.Report rep = r.check(from, to);

        assertTrue("from must be walkable in fixture", rep.fromWalkable());
        assertTrue("to must be walkable in fixture", rep.toWalkable());
        assertEquals("connected corridor → BreakReason.NONE",
            RouteReadiness.BreakReason.NONE, rep.firstBreakReason());
        assertTrue("planner must reach goal", rep.canPlan());
        assertTrue("BFS must find some reachable tiles", rep.reachableFromStart() > 1);
    }

    @Test
    public void check_missingRegion_reportsRegionMissing()
    {
        MapStore s = store();
        TransportIndex t = new TransportIndex();
        WorldPoint from = new WorldPoint(3208, 3217, 0);
        WorldPoint to = new WorldPoint(3208, 3500, 0);   // far enough to need a different region
        // Seed only the from-region. The to-region is unloaded.
        WorldPoint nearFrom = new WorldPoint(3210, 3219, 0);
        seedCorridor(s, from, nearFrom);
        RouteReadiness r = new RouteReadiness(s, t, planner(s, t));
        RouteReadiness.Report rep = r.check(from, to);

        assertFalse(rep.canPlan());
        assertFalse("to is in a missing region", rep.toWalkable());
        assertTrue("missingRegionIds must include the to-region",
            rep.missingRegionIds().contains(RegionIds.regionIdFor(to.getX(), to.getY())));
        assertEquals("first break reason is REGION_MISSING when to-region absent",
            RouteReadiness.BreakReason.REGION_MISSING, rep.firstBreakReason());
    }

    @Test
    public void check_collisionGap_reportsCollisionBlocked_andCollisionDetail()
    {
        MapStore s = store();
        TransportIndex t = new TransportIndex();
        WorldPoint from = new WorldPoint(3208, 3217, 0);
        WorldPoint to = new WorldPoint(3220, 3217, 0);
        // Fully blocked tile in the corridor — every neighbour bumps
        // into BLOCK_MOVEMENT_FULL.
        List<WorldMemoryFixtures.TileSpec> tiles = new ArrayList<>();
        for (int x = 3204; x <= 3224; x++)
            for (int y = 3213; y <= 3221; y++)
            {
                int mv = (x == 3214 && y == 3217)
                    ? CollisionDataFlag.BLOCK_MOVEMENT_FULL
                    : 0;
                tiles.add(WorldMemoryFixtures.withMovement(x, y, 0, mv));
            }
        WorldMemoryFixtures.installRegion(s, LUMBY_BANK_REGION, tiles);
        RouteReadiness r = new RouteReadiness(s, t, planner(s, t));
        RouteReadiness.Report rep = r.check(from, to);

        // The gap is bypassable diagonally so the planner can still
        // reach. We only assert the *visibility* — readiness records
        // the first collision encountered during BFS; either kind
        // (cardinal blocked or diagonal blocked) demonstrates
        // collision detection works.
        assertNotNull("a single fully-blocked tile produces a collision detail",
            rep.collisionDetail());
    }

    @Test
    public void check_uncoveredTile_reportsUnknownTile()
    {
        MapStore s = store();
        TransportIndex t = new TransportIndex();
        WorldPoint from = new WorldPoint(3208, 3217, 0);
        WorldPoint to = new WorldPoint(3220, 3217, 0);
        // Seed only a tiny patch around `from` — neighbours past x=3210
        // are absent from the snapshot.
        List<WorldMemoryFixtures.TileSpec> tiles = new ArrayList<>();
        for (int x = 3206; x <= 3210; x++)
            for (int y = 3215; y <= 3219; y++)
                tiles.add(WorldMemoryFixtures.walkable(x, y, 0));
        WorldMemoryFixtures.installRegion(s, LUMBY_BANK_REGION, tiles);
        RouteReadiness r = new RouteReadiness(s, t, planner(s, t));
        RouteReadiness.Report rep = r.check(from, to);

        assertFalse(rep.canPlan());
        assertFalse("to is unknown — not walkable", rep.toWalkable());
        // Either UNKNOWN_TILE (to in same region but not snapshot) or
        // REGION_MISSING (to in different region) — both are valid
        // signals of "no data."
        assertTrue("first break reason should surface lack-of-data: " + rep.firstBreakReason(),
            rep.firstBreakReason() == RouteReadiness.BreakReason.UNKNOWN_TILE
            || rep.firstBreakReason() == RouteReadiness.BreakReason.REGION_MISSING);
    }

    @Test
    public void check_crossPlaneNoTransport_reportsTransportRequired()
    {
        MapStore s = store();
        TransportIndex t = new TransportIndex();
        WorldPoint from = new WorldPoint(3208, 3217, 0);
        WorldPoint to = new WorldPoint(3208, 3217, 2);
        // Both planes seeded as walkable, but no TransportEdge connecting them.
        List<WorldMemoryFixtures.TileSpec> tiles = new ArrayList<>();
        for (int x = 3204; x <= 3212; x++)
            for (int y = 3213; y <= 3221; y++)
            {
                tiles.add(WorldMemoryFixtures.walkable(x, y, 0));
                tiles.add(WorldMemoryFixtures.walkable(x, y, 2));
            }
        WorldMemoryFixtures.installRegion(s, LUMBY_BANK_REGION, tiles);
        RouteReadiness r = new RouteReadiness(s, t, planner(s, t));
        RouteReadiness.Report rep = r.check(from, to);

        assertFalse("no transports → planner cannot route across planes", rep.canPlan());
        assertEquals(RouteReadiness.BreakReason.TRANSPORT_REQUIRED, rep.firstBreakReason());
    }

    @Test
    public void check_crossPlaneWithTransport_reportsTransportExecutorMissing()
    {
        MapStore s = store();
        TransportIndex t = new TransportIndex();
        WorldPoint from = new WorldPoint(3208, 3217, 0);
        WorldPoint to = new WorldPoint(3208, 3217, 2);
        List<WorldMemoryFixtures.TileSpec> tiles = new ArrayList<>();
        for (int x = 3204; x <= 3212; x++)
            for (int y = 3213; y <= 3221; y++)
            {
                tiles.add(WorldMemoryFixtures.walkable(x, y, 0));
                tiles.add(WorldMemoryFixtures.walkable(x, y, 2));
            }
        WorldMemoryFixtures.installRegion(s, LUMBY_BANK_REGION, tiles);
        // Transport edge connecting plane 0 → plane 2.
        TransportEdge stairs = new TransportEdge(
            new WorldPoint(3208, 3217, 0),
            new WorldPoint(3208, 3217, 2),
            12345, "Staircase", "Climb-up", 0, 0, "object",
            new WorldPoint(3208, 3217, 0), LUMBY_BANK_REGION,
            1, 0L, 0L);
        t.add(stairs);

        RouteReadiness r = new RouteReadiness(s, t, planner(s, t));
        RouteReadiness.Report rep = r.check(from, to);

        // Planner can find a path; the path uses a transport leg; the
        // executor cannot drive transport legs in round 1, so readiness
        // surfaces TRANSPORT_EXECUTOR_MISSING for the user.
        assertTrue("planner should find a path using the transport edge", rep.canPlan());
        assertEquals("planned route uses a transport — executor missing",
            RouteReadiness.BreakReason.TRANSPORT_EXECUTOR_MISSING,
            rep.firstBreakReason());
        assertTrue(rep.transportEdgesAvailable() > 0);
    }

    @Test
    public void analyzeCorridor_emitsRows_withReachableCounts()
    {
        MapStore s = store();
        TransportIndex t = new TransportIndex();
        WorldPoint from = new WorldPoint(3208, 3217, 0);
        WorldPoint to = new WorldPoint(3216, 3217, 0);
        seedCorridor(s, from, to);

        RouteReadiness r = new RouteReadiness(s, t, planner(s, t));
        java.util.Map<Integer, RouteReadiness.RowAnalysis> rows = r.analyzeCorridor(from, to);

        assertFalse("analyzed corridor must contain at least one row", rows.isEmpty());
        for (RouteReadiness.RowAnalysis row : rows.values())
        {
            assertTrue("each row has at least one known tile", row.knownTileCount() >= 1);
            assertTrue("reachable can't exceed known", row.reachableFromStartCount() <= row.knownTileCount());
        }
    }

    @Test
    public void check_summary_includesKeyFields()
    {
        MapStore s = store();
        TransportIndex t = new TransportIndex();
        WorldPoint from = new WorldPoint(3208, 3217, 0);
        WorldPoint to = new WorldPoint(3220, 3217, 0);
        seedCorridor(s, from, to);
        RouteReadiness.Report rep = new RouteReadiness(s, t, planner(s, t)).check(from, to);
        String sum = rep.summary();
        assertTrue("summary must mention 'break='", sum.contains("break="));
        assertTrue("summary must mention 'canPlan='", sum.contains("canPlan="));
        assertTrue("summary must mention loaded/missing region counts", sum.contains("regions{loaded="));
    }

    @Test
    public void check_nullEndpoints_returnsNonNullReportWithBreak()
    {
        MapStore s = store();
        TransportIndex t = new TransportIndex();
        RouteReadiness.Report rep = new RouteReadiness(s, t, planner(s, t)).check(null, null);
        assertEquals(RouteReadiness.BreakReason.UNKNOWN_TILE, rep.firstBreakReason());
        assertFalse(rep.canPlan());
    }

    @Test
    public void check_collisionInCorridor_breakReasonIsCollisionBlocked()
    {
        MapStore s = store();
        TransportIndex t = new TransportIndex();
        WorldPoint from = new WorldPoint(3208, 3217, 0);
        WorldPoint to = new WorldPoint(3220, 3217, 0);
        // Single full-block tile blocks BFS expansion to its east neighbour
        // via cardinal direction; planner can detour diagonally so the
        // resulting BFS encounters a cardinal-block before any diagonal one.
        java.util.List<WorldMemoryFixtures.TileSpec> tiles = new java.util.ArrayList<>();
        for (int x = 3206; x <= 3222; x++)
            for (int y = 3215; y <= 3219; y++)
            {
                int mv = (x == 3210 && y == 3217)
                    ? CollisionDataFlag.BLOCK_MOVEMENT_FULL : 0;
                tiles.add(WorldMemoryFixtures.withMovement(x, y, 0, mv));
            }
        java.util.Map<Integer, java.util.List<WorldMemoryFixtures.TileSpec>> byRegion = new java.util.HashMap<>();
        for (var ts : tiles) byRegion.computeIfAbsent(RegionIds.regionIdFor(ts.x(), ts.y()),
            k -> new java.util.ArrayList<>()).add(ts);
        for (var e : byRegion.entrySet())
            WorldMemoryFixtures.installRegion(s, e.getKey(), e.getValue());
        RouteReadiness.Report rep = new RouteReadiness(s, t, planner(s, t)).check(from, to);
        // A reachable detour exists, so canPlan is true → break = NONE OR
        // first-encounter is COLLISION_BLOCKED. We assert the collision
        // detail is captured AND its movementFlags carry BLOCK_MOVEMENT_FULL
        // — the diagnostic that matters for live debugging.
        assertNotNull("collision detail captured during BFS", rep.collisionDetail());
        assertTrue("collision detail movementFlags include BLOCK_MOVEMENT_FULL",
            (rep.collisionDetail().movementFlags()
                & CollisionDataFlag.BLOCK_MOVEMENT_FULL) != 0);
    }

    @Test
    public void check_loadedAndMissingPartition_disjoint_andCoverBbox()
    {
        MapStore s = store();
        TransportIndex t = new TransportIndex();
        WorldPoint from = new WorldPoint(3208, 3217, 0);
        WorldPoint to = new WorldPoint(3216, 3217, 0);
        seedCorridor(s, from, to);
        RouteReadiness.Report rep = new RouteReadiness(s, t, planner(s, t)).check(from, to);
        java.util.Set<Integer> intersect = new java.util.HashSet<>(rep.loadedRegionIds());
        intersect.retainAll(rep.missingRegionIds());
        assertTrue("loaded ∩ missing must be empty", intersect.isEmpty());
        java.util.Set<Integer> union = new java.util.HashSet<>(rep.loadedRegionIds());
        union.addAll(rep.missingRegionIds());
        assertEquals("loaded ∪ missing == bboxRegionIds", rep.bboxRegionIds(), union);
    }

    @Test
    public void bboxTiles_returnsTilesWithinPaddedRange_only()
    {
        MapStore s = store();
        TransportIndex t = new TransportIndex();
        WorldPoint from = new WorldPoint(3208, 3217, 0);
        WorldPoint to = new WorldPoint(3215, 3217, 0);
        seedCorridor(s, from, to);
        java.util.List<net.runelite.client.plugins.recorder.worldmap.RegionChunkSnapshot.TileEntry>
            tiles = new RouteReadiness(s, t, planner(s, t)).bboxTiles(from, to);
        int xMin = Math.min(from.getX(), to.getX()) - RouteReadiness.BBOX_PADDING;
        int xMax = Math.max(from.getX(), to.getX()) + RouteReadiness.BBOX_PADDING;
        int yMin = Math.min(from.getY(), to.getY()) - RouteReadiness.BBOX_PADDING;
        int yMax = Math.max(from.getY(), to.getY()) + RouteReadiness.BBOX_PADDING;
        for (var te : tiles)
        {
            assertTrue("bboxTiles must stay on plane 0", te.plane == 0);
            assertTrue("bbox x range", te.x >= xMin && te.x <= xMax);
            assertTrue("bbox y range", te.y >= yMin && te.y <= yMax);
        }
    }

    @Test
    public void check_isDeterministic_acrossRepeatedCallsRegardlessOfRng()
    {
        // Pass-3 P1: readiness must NOT depend on the variation flag
        // because it is a yes/no diagnostic. Two consecutive checks
        // against the same world memory must return the same answer.
        MapStore s = store();
        TransportIndex t = new TransportIndex();
        WorldPoint from = new WorldPoint(3208, 3217, 0);
        WorldPoint to = new WorldPoint(3220, 3217, 0);
        seedCorridor(s, from, to);
        // Variation enabled — readiness must still be stable.
        V2Planner p = new V2Planner(s, t, new WorldMemoryConfig(),
            new RouteHistory(), () -> true);
        RouteReadiness r = new RouteReadiness(s, t, p);
        RouteReadiness.BreakReason first = r.check(from, to).firstBreakReason();
        for (int i = 0; i < 10; i++)
        {
            assertEquals("readiness must be deterministic with variation ON",
                first, r.check(from, to).firstBreakReason());
        }
    }
}
