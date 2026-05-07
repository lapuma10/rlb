package net.runelite.client.plugins.recorder.nav.v2;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.worldmap.MapStore;
import net.runelite.client.plugins.recorder.worldmap.RegionIds;
import net.runelite.client.plugins.recorder.worldmap.TransportIndex;
import net.runelite.client.plugins.recorder.worldmap.WorldMemoryConfig;
import net.runelite.client.plugins.recorder.worldmap.WorldMemoryFixtures;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Phase-15 secondary-route readiness — Lumby↔Draynor and Lumby↔GE.
 *  Live capture is the morning workflow; offline these tests confirm
 *  the readiness diagnostic surfaces a clean reason for unseeded data
 *  (REGION_MISSING / UNKNOWN_TILE) so the user gets an actionable
 *  message instead of "watch the bot and guess." Marked
 *  LIVE_VERIFY_REQUIRED — the fixture only proves the API path, not
 *  the live world. */
public class SecondaryRouteReadinessTest
{
    /** Lumby castle exit toward Draynor. Anchors line up with the panel
     *  presets in {@code RecorderPanel.V2_PRESETS}. */
    private static final WorldPoint LUMBY = new WorldPoint(3221, 3219, 0);
    private static final WorldPoint DRAYNOR = new WorldPoint(3092, 3245, 0);
    private static final WorldPoint GE = new WorldPoint(3164, 3486, 0);

    private static V2Planner planner(MapStore s, TransportIndex t)
    {
        return new V2Planner(s, t, new WorldMemoryConfig(), new RouteHistory());
    }

    @Test
    public void lumbyToDraynor_emptyWorldmap_reportsRegionMissing()
    {
        MapStore s = new MapStore(new WorldMemoryConfig());
        TransportIndex t = new TransportIndex();
        RouteReadiness r = new RouteReadiness(s, t, planner(s, t));
        RouteReadiness.Report rep = r.check(LUMBY, DRAYNOR);

        assertFalse("empty worldmap → cannot plan", rep.canPlan());
        // No regions loaded means the bbox is entirely missing.
        assertTrue("missingRegionIds covers both endpoints",
            rep.missingRegionIds().contains(RegionIds.regionIdFor(LUMBY.getX(), LUMBY.getY())));
        assertTrue(rep.missingRegionIds().contains(RegionIds.regionIdFor(DRAYNOR.getX(), DRAYNOR.getY())));
        assertEquals(RouteReadiness.BreakReason.REGION_MISSING, rep.firstBreakReason());
    }

    @Test
    public void lumbyToGE_emptyWorldmap_reportsRegionMissing()
    {
        MapStore s = new MapStore(new WorldMemoryConfig());
        TransportIndex t = new TransportIndex();
        RouteReadiness r = new RouteReadiness(s, t, planner(s, t));
        RouteReadiness.Report rep = r.check(LUMBY, GE);

        assertFalse(rep.canPlan());
        assertEquals(RouteReadiness.BreakReason.REGION_MISSING, rep.firstBreakReason());
    }

    @Test
    public void lumbyEndpointSeeded_GoalRegionMissing_stillSurfacesRegionMissing()
    {
        MapStore s = new MapStore(new WorldMemoryConfig());
        TransportIndex t = new TransportIndex();
        // Seed only the Lumby end; GE end is unseeded.
        seedSquare(s, LUMBY, 8);
        RouteReadiness r = new RouteReadiness(s, t, planner(s, t));
        RouteReadiness.Report rep = r.check(LUMBY, GE);

        assertFalse(rep.canPlan());
        assertTrue(rep.fromWalkable());
        assertFalse(rep.toWalkable());
        assertTrue("at least the GE region must be in the missing set",
            rep.missingRegionIds().contains(RegionIds.regionIdFor(GE.getX(), GE.getY())));
    }

    @Test
    public void presetCoordinatesProduceDistinctRegions()
    {
        // Sanity check: Lumby / Draynor / GE all live in different
        // region IDs. Concrete values are derived from RegionIds; we
        // only assert distinctness so the test won't break if RuneLite
        // changes the encoding.
        int rLumby = RegionIds.regionIdFor(LUMBY.getX(), LUMBY.getY());
        int rDraynor = RegionIds.regionIdFor(DRAYNOR.getX(), DRAYNOR.getY());
        int rGe = RegionIds.regionIdFor(GE.getX(), GE.getY());
        assertTrue("Lumby and Draynor must be in different regions",
            rLumby != rDraynor);
        assertTrue("Lumby and GE must be in different regions",
            rLumby != rGe);
        assertTrue("Draynor and GE must be in different regions",
            rDraynor != rGe);
    }

    private static void seedSquare(MapStore s, WorldPoint center, int half)
    {
        List<WorldMemoryFixtures.TileSpec> ts = new ArrayList<>();
        for (int dx = -half; dx <= half; dx++)
            for (int dy = -half; dy <= half; dy++)
                ts.add(WorldMemoryFixtures.walkable(center.getX() + dx, center.getY() + dy, 0));
        Map<Integer, List<WorldMemoryFixtures.TileSpec>> byRegion = new HashMap<>();
        for (var ts1 : ts)
            byRegion.computeIfAbsent(RegionIds.regionIdFor(ts1.x(), ts1.y()),
                k -> new ArrayList<>()).add(ts1);
        for (var e : byRegion.entrySet())
            WorldMemoryFixtures.installRegion(s, e.getKey(), e.getValue());
    }
}
