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
    public void routeId_differs_forDifferentLegs()
    {
        V2Path a = new V2Path(List.of(walkLeg()), 10);
        V2Path b = new V2Path(List.of(walkLegAlt()), 10);
        assertNotEquals(a.routeId(), b.routeId());
    }

    @Test
    public void routeId_ignores_intermediateTiles_keepsStartAndEnd()
    {
        WorldPoint a = new WorldPoint(3208, 3213, 0);
        WorldPoint b = new WorldPoint(3209, 3213, 0);
        WorldPoint c = new WorldPoint(3210, 3213, 0);
        WorldPoint c2 = new WorldPoint(3210, 3213, 0);
        V2Path direct = new V2Path(List.of(new V2Leg.Walk(12850, List.of(a, c))), 1);
        V2Path detour = new V2Path(List.of(new V2Leg.Walk(12850, List.of(a, b, c2))), 2);
        assertEquals("intermediate tiles do not change routeId",
            direct.routeId(), detour.routeId());
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

    private static V2Leg.Walk walkLegAlt()
    {
        return new V2Leg.Walk(12850, List.of(
            new WorldPoint(3208, 3213, 0),
            new WorldPoint(3208, 3214, 0)));
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
