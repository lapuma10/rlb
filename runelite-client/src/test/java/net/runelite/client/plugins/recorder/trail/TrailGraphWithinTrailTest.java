package net.runelite.client.plugins.recorder.trail;

import java.util.List;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class TrailGraphWithinTrailTest
{
    @Test
    public void consecutiveTilesGetCost1Edge()
    {
        Trail t = new Trail("t", 0L, List.of(
            new TrailEvent.Tile(0L,    new WorldPoint(100, 100, 0)),
            new TrailEvent.Tile(600L,  new WorldPoint(100, 101, 0)),
            new TrailEvent.Tile(1200L, new WorldPoint(100, 102, 0))));
        TrailGraph g = TrailGraph.build(List.of(t));
        WorldPoint a = new WorldPoint(100, 100, 0);
        WorldPoint b = new WorldPoint(100, 101, 0);
        WorldPoint c = new WorldPoint(100, 102, 0);
        assertEquals(1, g.edgeCost(a, b));
        assertEquals(1, g.edgeCost(b, c));
        // Bidirectional.
        assertEquals(1, g.edgeCost(b, a));
        // Non-adjacent in the trail (a -> c) — no within-trail edge.
        assertEquals(-1, g.edgeCost(a, c));
        // Reciprocal also -1 unless a junction edge added it.
        assertTrue(g.nodes().contains(a));
        assertTrue(g.nodes().contains(b));
        assertTrue(g.nodes().contains(c));
    }
}
