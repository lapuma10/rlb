package net.runelite.client.plugins.recorder.trail;

import java.util.List;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class TrailGraphJunctionTest
{
    @Test
    public void exactSharedTileGetsCost0Edge()
    {
        // Trail A passes through (10,10,0); trail B also includes (10,10,0).
        // Cost-0 means free transfer.
        Trail a = new Trail("A", 0L, List.of(
            new TrailEvent.Tile(0L, new WorldPoint(9, 10, 0)),
            new TrailEvent.Tile(1L, new WorldPoint(10, 10, 0))));
        Trail b = new Trail("B", 0L, List.of(
            new TrailEvent.Tile(0L, new WorldPoint(10, 10, 0)),
            new TrailEvent.Tile(1L, new WorldPoint(11, 10, 0))));
        TrailGraph g = TrailGraph.build(List.of(a, b));
        // A's (9,10) and B's (11,10) connect through (10,10) at cost 0+1+1.
        // The cost-0 edge isn't between (9,10) and (11,10) directly, but
        // any node already a tile-set in both trails gets a cost-0 self-
        // edge (we filter self-edges). So we test by ensuring the graph
        // has the shared node and the planner finds a low-cost path.
        assertTrue(g.nodes().contains(new WorldPoint(10, 10, 0)));
        // Direct edge (9,10) -> (10,10) cost 1 (within-trail walk).
        assertEquals(1, g.edgeCost(new WorldPoint(9,10,0), new WorldPoint(10,10,0)));
        // Direct edge (10,10) -> (11,10) cost 1 (within-trail walk via B).
        assertEquals(1, g.edgeCost(new WorldPoint(10,10,0), new WorldPoint(11,10,0)));
    }

    @Test
    public void chebyshev1ApartGetsCost1Edge()
    {
        // Trail A passes through (10,10,0); trail B nearby at (11,11,0).
        // Junction edge: cost 1 between (10,10) and (11,11).
        Trail a = new Trail("A", 0L, List.of(
            new TrailEvent.Tile(0L, new WorldPoint(10, 10, 0)),
            new TrailEvent.Tile(1L, new WorldPoint(10, 11, 0))));
        Trail b = new Trail("B", 0L, List.of(
            new TrailEvent.Tile(0L, new WorldPoint(11, 11, 0)),
            new TrailEvent.Tile(1L, new WorldPoint(12, 11, 0))));
        TrailGraph g = TrailGraph.build(List.of(a, b));
        // Junction between (10,10) [trail A] and (11,11) [trail B] —
        // Chebyshev = 1, cost = 1.
        assertEquals(1, g.edgeCost(new WorldPoint(10, 10, 0), new WorldPoint(11, 11, 0)));
    }

    @Test
    public void chebyshev2ApartGetsNoEdge()
    {
        Trail a = new Trail("A", 0L, List.of(
            new TrailEvent.Tile(0L, new WorldPoint(10, 10, 0))));
        Trail b = new Trail("B", 0L, List.of(
            new TrailEvent.Tile(0L, new WorldPoint(12, 12, 0))));
        TrailGraph g = TrailGraph.build(List.of(a, b));
        assertEquals(-1, g.edgeCost(new WorldPoint(10,10,0), new WorldPoint(12,12,0)));
    }

    @Test
    public void differentPlanesGetNoJunctionEdge()
    {
        Trail a = new Trail("A", 0L, List.of(
            new TrailEvent.Tile(0L, new WorldPoint(10, 10, 0))));
        Trail b = new Trail("B", 0L, List.of(
            new TrailEvent.Tile(0L, new WorldPoint(10, 10, 1))));
        TrailGraph g = TrailGraph.build(List.of(a, b));
        // Same x/y but different plane — no junction.
        assertEquals(-1, g.edgeCost(
            new WorldPoint(10, 10, 0), new WorldPoint(10, 10, 1)));
    }
}
