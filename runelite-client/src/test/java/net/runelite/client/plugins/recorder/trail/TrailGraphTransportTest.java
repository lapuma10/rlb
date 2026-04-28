package net.runelite.client.plugins.recorder.trail;

import java.util.List;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class TrailGraphTransportTest
{
    @Test
    public void transportEdgeBetweenPrevAndNextTile()
    {
        // Tile (10,10,0) -> TRANSPORT (Climb-down at (11,10,0)) -> Tile (11,10,1)
        // Should produce: walk edge (10,10,0)->(11,10,0) is NOT created;
        // instead a TRANSPORT edge between the two tiles flanking the
        // transport event. The transport tile itself is the click target.
        Trail t = new Trail("ladder", 0L, List.of(
            new TrailEvent.Tile(0L,    new WorldPoint(10, 10, 0)),
            new TrailEvent.Transport(600L, new WorldPoint(11, 10, 0),
                "Climb-down", "Ladder", 1234, "GameObject", 3, 12, 13, List.of()),
            new TrailEvent.Tile(1200L, new WorldPoint(11, 10, 1))));
        TrailGraph g = TrailGraph.build(List.of(t));
        WorldPoint a = new WorldPoint(10, 10, 0);
        WorldPoint c = new WorldPoint(11, 10, 1);
        // Edge present, cost 1.
        assertEquals(1, g.edgeCost(a, c));
        Leg.Transport tr = g.transportBetween(a, c);
        assertNotNull("transport payload missing", tr);
        assertEquals("Climb-down", tr.verb());
        assertEquals(1234, tr.objectId());
        assertEquals(new WorldPoint(11, 10, 0), tr.tile());
        // Bidirectional.
        assertEquals(1, g.edgeCost(c, a));
        assertNotNull(g.transportBetween(c, a));
    }
}
