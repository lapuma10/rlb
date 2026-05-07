package net.runelite.client.plugins.recorder.nav.v2;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.nav.BehaviorMode;
import net.runelite.client.plugins.recorder.worldmap.MapStore;
import net.runelite.client.plugins.recorder.worldmap.RegionIds;
import net.runelite.client.plugins.recorder.worldmap.TransportIndex;
import net.runelite.client.plugins.recorder.worldmap.WorldMemoryConfig;
import net.runelite.client.plugins.recorder.worldmap.WorldMemoryFixtures;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Phase-11 acceptance: route variation is gated by a config flag.
 *  With variation OFF the planner returns a stable deterministic path;
 *  with variation ON it draws from top-K alternates over repeated calls.
 *
 *  <p>The fixture is a north + south corridor with a "wall" on y=3225
 *  forcing two distinct macro routes (one through y=3226, one through
 *  y=3224). Two distinct routeIds across runs proves alternation; a
 *  single repeated routeId proves stability when off. */
public class V2PlannerVariationFlagTest
{
    private static final WorldPoint START = new WorldPoint(3208, 3225, 0);
    private static final WorldPoint GOAL = new WorldPoint(3220, 3225, 0);

    /** Two parallel walkable corridors at y=3224 and y=3226 separated
     *  by a row of fully-blocked tiles at y=3225 (except endpoints). */
    private static MapStore twoCorridorFixture()
    {
        MapStore s = new MapStore(new WorldMemoryConfig());
        java.util.List<WorldMemoryFixtures.TileSpec> tiles = new java.util.ArrayList<>();
        for (int x = 3204; x <= 3224; x++)
        {
            for (int y = 3220; y <= 3230; y++)
            {
                int mv = (y == 3225 && x != 3208 && x != 3220)
                    ? net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_FULL
                    : 0;
                tiles.add(WorldMemoryFixtures.withMovement(x, y, 0, mv));
            }
        }
        java.util.Map<Integer, java.util.List<WorldMemoryFixtures.TileSpec>> byRegion =
            new java.util.HashMap<>();
        for (WorldMemoryFixtures.TileSpec ts : tiles)
            byRegion.computeIfAbsent(RegionIds.regionIdFor(ts.x(), ts.y()),
                k -> new java.util.ArrayList<>()).add(ts);
        for (var e : byRegion.entrySet())
            WorldMemoryFixtures.installRegion(s, e.getKey(), e.getValue());
        return s;
    }

    @Test
    public void variationOff_returnsStableRouteIdAcrossRuns()
    {
        MapStore s = twoCorridorFixture();
        TransportIndex t = new TransportIndex();
        RouteHistory h = new RouteHistory();
        MultiRegionAStar mr = new MultiRegionAStar(s, t, new WorldMemoryConfig());
        V2Planner planner = new V2Planner(mr, new TopKRouter(mr, h), h,
            new Random(42), () -> false);

        Set<String> routeIds = new HashSet<>();
        for (int i = 0; i < 8; i++)
        {
            V2Path p = planner.plan(START, GOAL, BehaviorMode.VARIED);
            assertFalse("variation off must still plan", p.isEmpty());
            routeIds.add(p.routeId());
        }
        assertEquals("variation OFF → exactly one stable routeId across 8 runs",
            1, routeIds.size());
    }

    @Test
    public void variationOn_picksMoreThanOneRouteAcrossRuns()
    {
        MapStore s = twoCorridorFixture();
        TransportIndex t = new TransportIndex();
        RouteHistory h = new RouteHistory();
        MultiRegionAStar mr = new MultiRegionAStar(s, t, new WorldMemoryConfig());
        V2Planner planner = new V2Planner(mr, new TopKRouter(mr, h), h,
            new Random(11), () -> true);

        Set<String> routeIds = new HashSet<>();
        for (int i = 0; i < 12; i++)
        {
            V2Path p = planner.plan(START, GOAL, BehaviorMode.VARIED);
            if (p.isEmpty()) continue;
            routeIds.add(p.routeId());
        }
        assertTrue("variation ON should produce more than one routeId over 12 runs ("
            + routeIds.size() + ")", routeIds.size() >= 2);
    }

    @Test
    public void variationOn_routeHistoryIsRecorded()
    {
        MapStore s = twoCorridorFixture();
        TransportIndex t = new TransportIndex();
        RouteHistory h = new RouteHistory();
        MultiRegionAStar mr = new MultiRegionAStar(s, t, new WorldMemoryConfig());
        V2Planner planner = new V2Planner(mr, new TopKRouter(mr, h), h,
            new Random(3), () -> true);

        V2Path p = planner.plan(START, GOAL, BehaviorMode.VARIED);
        assertFalse(p.isEmpty());
        assertTrue("variation ON must push the picked routeId into history",
            h.recentEntries().contains(p.routeId()));
    }

    @Test
    public void variationOff_routeHistoryIsNotRecorded()
    {
        MapStore s = twoCorridorFixture();
        TransportIndex t = new TransportIndex();
        RouteHistory h = new RouteHistory();
        MultiRegionAStar mr = new MultiRegionAStar(s, t, new WorldMemoryConfig());
        V2Planner planner = new V2Planner(mr, new TopKRouter(mr, h), h,
            new Random(3), () -> false);

        planner.plan(START, GOAL, BehaviorMode.VARIED);
        assertEquals("variation OFF must NOT push routeIds into recent-history",
            0, h.size());
    }

    @Test
    public void variationFlag_readLive_betweenCallsTakesEffect()
    {
        MapStore s = twoCorridorFixture();
        TransportIndex t = new TransportIndex();
        RouteHistory h = new RouteHistory();
        MultiRegionAStar mr = new MultiRegionAStar(s, t, new WorldMemoryConfig());
        AtomicBoolean flag = new AtomicBoolean(false);
        V2Planner planner = new V2Planner(mr, new TopKRouter(mr, h), h,
            new Random(7), flag::get);

        // Variation off — record stable routeId.
        V2Path off = planner.plan(START, GOAL, BehaviorMode.VARIED);
        assertFalse(off.isEmpty());
        // Flip variation on; subsequent runs may pick alternates.
        flag.set(true);
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 12; i++)
        {
            V2Path p = planner.plan(START, GOAL, BehaviorMode.VARIED);
            ids.add(p.routeId());
        }
        // We don't strictly assert > 1 here — RNG seed can produce the
        // same pick by chance. We assert the call sequence completed
        // without throwing and recent history grew. The two prior tests
        // cover the routeId-set assertion under deterministic seeds.
        assertTrue("variation ON must populate route history",
            h.size() >= 1);
    }
}
