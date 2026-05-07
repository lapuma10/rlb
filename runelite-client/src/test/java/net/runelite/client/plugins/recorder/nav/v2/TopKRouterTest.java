package net.runelite.client.plugins.recorder.nav.v2;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.worldmap.MapStore;
import net.runelite.client.plugins.recorder.worldmap.RegionIds;
import net.runelite.client.plugins.recorder.worldmap.TransportIndex;
import net.runelite.client.plugins.recorder.worldmap.WorldMemoryConfig;
import net.runelite.client.plugins.recorder.worldmap.WorldMemoryFixtures;
import org.junit.Test;
import static net.runelite.client.plugins.recorder.worldmap.WorldMemoryFixtures.walkable;
import static org.junit.Assert.*;

public class TopKRouterTest
{
    private final WorldMemoryConfig wm = new WorldMemoryConfig();

    @Test
    public void topK_returnsUpToKDistinctRoutes()
    {
        // Two parallel walkable corridors between (3208,3213) and
        // (3220,3213) — north corridor at y=3213, south corridor at
        // y=3210, joined at the endpoints. Top-K should find both.
        MapStore store = new MapStore(wm);
        int regionId = RegionIds.regionIdFor(3208, 3213);
        List<WorldMemoryFixtures.TileSpec> tiles = new ArrayList<>();
        for (int x = 3208; x <= 3220; x++)
        {
            tiles.add(walkable(x, 3213, 0));
            tiles.add(walkable(x, 3210, 0));
        }
        // Vertical connectors at the ends.
        tiles.add(walkable(3208, 3211, 0));
        tiles.add(walkable(3208, 3212, 0));
        tiles.add(walkable(3220, 3211, 0));
        tiles.add(walkable(3220, 3212, 0));
        WorldMemoryFixtures.installRegion(store, regionId, tiles);

        MultiRegionAStar a = new MultiRegionAStar(store, new TransportIndex(), wm);
        TopKRouter router = new TopKRouter(a, new RouteHistory());

        List<V2Path> routes = router.findTopK(
            new WorldPoint(3208, 3213, 0), new WorldPoint(3220, 3213, 0));

        assertTrue("at least 2 distinct routes when alternates exist",
            routes.size() >= 2);
        assertTrue("never more than K", routes.size() <= TopKRouter.K);
        Set<String> ids = new HashSet<>();
        for (V2Path p : routes) ids.add(p.routeId());
        assertEquals("returned routes must be distinct", routes.size(), ids.size());
    }

    @Test
    public void topK_rejectsRoutesAboveCostThreshold()
    {
        // Two-corridor fixture with a wildly long (50-tile detour) third
        // option spliced in. The detour must be rejected as too expensive
        // even if A* finds it.
        MapStore store = new MapStore(wm);
        int regionId = RegionIds.regionIdFor(3208, 3213);
        List<WorldMemoryFixtures.TileSpec> tiles = new ArrayList<>();
        // Direct corridor: (3208..3220, 3213, 0) — cost 12.
        for (int x = 3208; x <= 3220; x++) tiles.add(walkable(x, 3213, 0));
        // Long detour via (3208..3220, 3253, 0) — adds 80 tiles north + back south.
        for (int x = 3208; x <= 3220; x++) tiles.add(walkable(x, 3253, 0));
        for (int y = 3213; y <= 3253; y++) tiles.add(walkable(3208, y, 0));
        for (int y = 3213; y <= 3253; y++) tiles.add(walkable(3220, y, 0));
        WorldMemoryFixtures.installRegion(store, regionId, tiles);

        MultiRegionAStar a = new MultiRegionAStar(store, new TransportIndex(), wm);
        TopKRouter router = new TopKRouter(a, new RouteHistory());

        List<V2Path> routes = router.findTopK(
            new WorldPoint(3208, 3213, 0), new WorldPoint(3220, 3213, 0));

        // Cheapest route should be cost 12. Anything > 12 * 1.75 = 21
        // must NOT appear.
        int cheapest = routes.get(0).totalCost();
        double cap = cheapest * TopKRouter.COST_REJECT_MULTIPLIER;
        for (V2Path p : routes)
        {
            assertTrue("route cost " + p.totalCost() + " > " + cap + " — should be rejected",
                p.totalCost() <= cap);
        }
    }

    @Test
    public void topK_returnsCheapestOnly_whenNoAlternatesExist()
    {
        MapStore store = new MapStore(wm);
        int regionId = RegionIds.regionIdFor(3208, 3213);
        List<WorldMemoryFixtures.TileSpec> tiles = new ArrayList<>();
        // Single straight corridor — only one route exists.
        for (int x = 3208; x <= 3215; x++) tiles.add(walkable(x, 3213, 0));
        WorldMemoryFixtures.installRegion(store, regionId, tiles);

        MultiRegionAStar a = new MultiRegionAStar(store, new TransportIndex(), wm);
        TopKRouter router = new TopKRouter(a, new RouteHistory());

        List<V2Path> routes = router.findTopK(
            new WorldPoint(3208, 3213, 0), new WorldPoint(3215, 3213, 0));

        assertEquals(1, routes.size());
        assertEquals(7, routes.get(0).totalCost());
    }

    @Test
    public void pickWeighted_prefersLowerCost_butNotAlways()
    {
        // Two routes, both viable. Run picks 200 times; both should
        // appear at least 10% of the time so it isn't degenerate.
        MapStore store = new MapStore(wm);
        int regionId = RegionIds.regionIdFor(3208, 3213);
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
        WorldMemoryFixtures.installRegion(store, regionId, tiles);

        MultiRegionAStar a = new MultiRegionAStar(store, new TransportIndex(), wm);
        TopKRouter router = new TopKRouter(a, new RouteHistory());
        List<V2Path> routes = router.findTopK(
            new WorldPoint(3208, 3213, 0), new WorldPoint(3220, 3213, 0));
        assertTrue("test fixture sanity: ≥2 routes for weighted-pick", routes.size() >= 2);

        Random rng = new Random(1234);
        int[] counts = new int[routes.size()];
        for (int trial = 0; trial < 200; trial++)
        {
            V2Path picked = router.pickWeighted(routes, rng);
            int idx = -1;
            for (int i = 0; i < routes.size(); i++)
                if (routes.get(i).routeId().equals(picked.routeId())) idx = i;
            counts[idx]++;
        }
        for (int i = 0; i < counts.length; i++)
        {
            assertTrue("route #" + i + " picked " + counts[i] + "/200 — weighted-pick is degenerate",
                counts[i] >= 20);
        }
    }
}
