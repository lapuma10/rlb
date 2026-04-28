package net.runelite.client.plugins.recorder.trail;

import java.util.List;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class TrailPathTest
{
    @Test
    public void walkLegCarriesTileList()
    {
        Leg.Walk w = new Leg.Walk(List.of(
            new WorldPoint(1, 1, 0), new WorldPoint(1, 2, 0)));
        assertEquals(2, w.tiles().size());
        assertEquals("WALK", w.kind());
    }

    @Test
    public void transportLegCarriesVerbAndObjectMetadata()
    {
        Leg.Transport t = new Leg.Transport(
            new WorldPoint(3, 3, 0), "Climb-down", 16671, "GameObject", 53, 14);
        assertEquals("TRANSPORT", t.kind());
        assertEquals("Climb-down", t.verb());
        assertEquals(16671, t.objectId());
    }

    @Test
    public void trailPathExposesLegs()
    {
        Leg.Walk w = new Leg.Walk(List.of(new WorldPoint(0,0,0)));
        TrailPath p = new TrailPath(List.of(w));
        assertEquals(1, p.legs().size());
        assertSame(w, p.legs().get(0));
    }
}
