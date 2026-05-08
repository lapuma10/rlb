package net.runelite.client.plugins.recorder.nav.v2;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.nav.BehaviorMode;
import net.runelite.client.plugins.recorder.worldmap.MapStore;
import net.runelite.client.plugins.recorder.worldmap.RegionIds;
import net.runelite.client.plugins.recorder.worldmap.TransportIndex;
import net.runelite.client.plugins.recorder.worldmap.WorldMemoryConfig;
import net.runelite.client.plugins.recorder.worldmap.WorldMemoryFixtures;
import org.junit.Test;
import static net.runelite.client.plugins.recorder.worldmap.WorldMemoryFixtures.walkable;
import static org.junit.Assert.*;

public class V2PlannerTest
{
    private final WorldMemoryConfig wm = new WorldMemoryConfig();

    @Test
    public void plan_withAlternates_picksFromTopKAndRecordsHistory()
    {
        // Same parallel-corridors fixture as TopKRouterTest.
        MapStore store = new MapStore(wm);
        int regionId = RegionIds.regionIdFor(3208, 3213);
        List<WorldMemoryFixtures.TileSpec> tiles = parallelCorridorsFixture();
        WorldMemoryFixtures.installRegion(store, regionId, tiles);

        MultiRegionAStar a = new MultiRegionAStar(store, new TransportIndex(), wm);
        RouteHistory history = new RouteHistory();
        TopKRouter topK = new TopKRouter(a, history);
        V2Planner planner = new V2Planner(a, topK, history, new Random(42));

        Set<String> chosenIds = new HashSet<>();
        for (int i = 0; i < 30; i++)
        {
            V2Path p = planner.plan(new WorldPoint(3208, 3213, 0),
                new WorldPoint(3220, 3213, 0), BehaviorMode.VARIED);
            assertFalse(p.isEmpty());
            chosenIds.add(p.routeId());
        }
        assertTrue("with two viable corridors and recent-route penalty, both alternates must be picked across 30 iterations (got "
            + chosenIds.size() + ")",
            chosenIds.size() >= 2);
    }

    @Test
    public void plan_singleRoute_fallsThroughToNoisyAStar()
    {
        MapStore store = new MapStore(wm);
        int regionId = RegionIds.regionIdFor(3208, 3213);
        List<WorldMemoryFixtures.TileSpec> tiles = new ArrayList<>();
        for (int x = 3208; x <= 3215; x++) tiles.add(walkable(x, 3213, 0));
        WorldMemoryFixtures.installRegion(store, regionId, tiles);

        MultiRegionAStar a = new MultiRegionAStar(store, new TransportIndex(), wm);
        RouteHistory history = new RouteHistory();
        V2Planner planner = new V2Planner(a, new TopKRouter(a, history), history, new Random(7));

        V2Path p = planner.plan(new WorldPoint(3208, 3213, 0),
            new WorldPoint(3215, 3213, 0), BehaviorMode.VARIED);
        assertFalse(p.isEmpty());
        // History should have recorded the chosen route.
        assertEquals(1, history.size());
    }

    @Test
    public void plan_unreachable_returnsEmpty_doesNotRecord()
    {
        // Permissive planner crosses unknown tiles, so "unreachable"
        // requires KNOWN-blocked tiles surrounding the start. Wall the
        // start tile in on all 8 sides with BLOCK_MOVEMENT_FULL so no
        // walkable / unknown step is possible.
        MapStore store = new MapStore(wm);
        int regionId = RegionIds.regionIdFor(3208, 3213);
        java.util.List<WorldMemoryFixtures.TileSpec> tiles = new java.util.ArrayList<>();
        tiles.add(walkable(3208, 3213, 0));
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                if (!(dx == 0 && dy == 0))
                    tiles.add(WorldMemoryFixtures.withMovement(3208 + dx, 3213 + dy, 0,
                        net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_FULL));
        WorldMemoryFixtures.installRegion(store, regionId, tiles);

        MultiRegionAStar a = new MultiRegionAStar(store, new TransportIndex(), wm);
        RouteHistory history = new RouteHistory();
        V2Planner planner = new V2Planner(a, new TopKRouter(a, history), history, new Random(0));

        V2Path p = planner.plan(new WorldPoint(3208, 3213, 0),
            new WorldPoint(3299, 3213, 0), BehaviorMode.VARIED);
        assertTrue("walled-in start has no walkable / unknown neighbours",
            p.isEmpty());
        assertEquals(0, history.size());
    }

    @Test
    public void plan_nonVariedMode_logsAndFallsThrough()
    {
        MapStore store = new MapStore(wm);
        int regionId = RegionIds.regionIdFor(3208, 3213);
        List<WorldMemoryFixtures.TileSpec> tiles = new ArrayList<>();
        for (int x = 3208; x <= 3212; x++) tiles.add(walkable(x, 3213, 0));
        WorldMemoryFixtures.installRegion(store, regionId, tiles);

        MultiRegionAStar a = new MultiRegionAStar(store, new TransportIndex(), wm);
        RouteHistory history = new RouteHistory();
        V2Planner planner = new V2Planner(a, new TopKRouter(a, history), history, new Random(0));

        // Should still return a usable path even with unsupported mode.
        V2Path p = planner.plan(new WorldPoint(3208, 3213, 0),
            new WorldPoint(3212, 3213, 0), BehaviorMode.EFFICIENT);
        assertFalse(p.isEmpty());
    }

    private static List<WorldMemoryFixtures.TileSpec> parallelCorridorsFixture()
    {
        List<WorldMemoryFixtures.TileSpec> tiles = new ArrayList<>();
        for (int x = 3208; x <= 3220; x++)
        {
            tiles.add(walkable(x, 3213, 0));
            tiles.add(walkable(x, 3210, 0));
        }
        tiles.add(walkable(3208, 3211, 0));
        tiles.add(walkable(3208, 3212, 0));
        tiles.add(walkable(3220, 3211, 0));
        tiles.add(walkable(3220, 3212, 0));
        return tiles;
    }
}
