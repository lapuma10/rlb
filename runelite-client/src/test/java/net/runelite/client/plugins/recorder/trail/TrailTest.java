package net.runelite.client.plugins.recorder.trail;

import java.util.List;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class TrailTest
{
    @Test
    public void preservesNameRecordedAtAndEvents()
    {
        TrailEvent a = new TrailEvent.Tile(0L,   new WorldPoint(3208, 3220, 2));
        TrailEvent b = new TrailEvent.Tile(600L, new WorldPoint(3208, 3221, 2));
        Trail t = new Trail("lumby-bank-to-pen", 1714247000000L, List.of(a, b));
        assertEquals("lumby-bank-to-pen", t.name());
        assertEquals(1714247000000L, t.recordedAt());
        assertEquals(2, t.events().size());
        assertSame(a, t.events().get(0));
    }

    @Test
    public void eventsAreUnmodifiable()
    {
        Trail t = new Trail("x", 0L, List.of(new TrailEvent.Tile(0L, new WorldPoint(1, 2, 0))));
        try { t.events().add(new TrailEvent.Tile(1L, new WorldPoint(1, 2, 0))); }
        catch (UnsupportedOperationException ok) { return; }
        fail("events list must be unmodifiable");
    }
}
