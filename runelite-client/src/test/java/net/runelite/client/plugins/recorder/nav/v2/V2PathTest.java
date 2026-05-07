package net.runelite.client.plugins.recorder.nav.v2;

import java.util.List;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.worldmap.TransportEdge;
import org.junit.Test;
import static org.junit.Assert.*;

public class V2PathTest
{
    @Test
    public void routeId_isStable_forSameLegs()
    {
        V2Path a = new V2Path(List.of(walkLeg()), 10);
        V2Path b = new V2Path(List.of(walkLeg()), 99);  // cost ignored in hash
        assertEquals("routeId stable across instances", a.routeId(), b.routeId());
    }

    @Test
    public void routeId_isStable_withinSameMidpointBucket()
    {
        // Two routes between the same start + end whose midpoints fall
        // in the same 4-tile bucket — should hash to the same id (noise
        // variants of one macro corridor).
        WorldPoint start = new WorldPoint(3208, 3213, 0);
        WorldPoint end = new WorldPoint(3216, 3213, 0);
        WorldPoint mid1 = new WorldPoint(3212, 3213, 0);   // bucket (803, 803)
        WorldPoint mid2 = new WorldPoint(3213, 3213, 0);   // also bucket (803, 803)
        V2Path a = new V2Path(List.of(new V2Leg.Walk(12850, List.of(start, mid1, end))), 1);
        V2Path b = new V2Path(List.of(new V2Leg.Walk(12850, List.of(start, mid2, end))), 2);
        assertEquals("midpoints in same bucket → same routeId",
            a.routeId(), b.routeId());
    }

    @Test
    public void routeId_differs_whenMidpointBucketsDiffer()
    {
        // Two genuinely different corridors between the same endpoints —
        // their midpoints are in different 4-tile buckets, so the
        // routeIds must differ (top-K relies on this to dedup).
        WorldPoint start = new WorldPoint(3208, 3213, 0);
        WorldPoint end = new WorldPoint(3216, 3213, 0);
        WorldPoint midNorth = new WorldPoint(3212, 3213, 0);   // bucket (803, 803)
        WorldPoint midSouth = new WorldPoint(3212, 3210, 0);   // bucket (803, 802)
        V2Path north = new V2Path(List.of(new V2Leg.Walk(12850, List.of(start, midNorth, end))), 8);
        V2Path south = new V2Path(List.of(new V2Leg.Walk(12850, List.of(start, midSouth, end))), 14);
        assertNotEquals("different macro corridor midpoints → different routeId",
            north.routeId(), south.routeId());
    }

    @Test
    public void allTiles_inlinesWalksAndTransportEndpoints()
    {
        V2Path path = new V2Path(List.of(
            new V2Leg.Walk(12850, List.of(
                new WorldPoint(3208, 3213, 0),
                new WorldPoint(3209, 3213, 0))),
            new V2Leg.Transport(staircase()),
            new V2Leg.Walk(12850, List.of(
                new WorldPoint(3205, 3228, 1),
                new WorldPoint(3204, 3228, 1)))), 5);
        // walk(2) + transport(2) + walk(2) = 6
        assertEquals(6, path.allTiles().size());
        assertEquals(0, path.allTiles().get(0).getPlane());
        assertEquals(1, path.allTiles().get(path.allTiles().size() - 1).getPlane());
    }

    @Test
    public void empty_isEmptyPath_stableId()
    {
        assertTrue(V2Path.EMPTY.isEmpty());
        assertEquals(V2Path.EMPTY.routeId(), new V2Path(List.of(), 0).routeId());
    }

    private static V2Leg.Walk walkLeg()
    {
        return new V2Leg.Walk(12850, List.of(
            new WorldPoint(3208, 3213, 0),
            new WorldPoint(3209, 3213, 0)));
    }

    private static TransportEdge staircase()
    {
        WorldPoint from = new WorldPoint(3206, 3229, 2);
        WorldPoint to = new WorldPoint(3205, 3228, 1);
        return new TransportEdge(from, to,
            16671, "Staircase", "Climb-down",
            53, 14, "GAME_OBJECT_FIRST_OPTION",
            from, from.getRegionID(), 1, 1L, 1200L);
    }
}
