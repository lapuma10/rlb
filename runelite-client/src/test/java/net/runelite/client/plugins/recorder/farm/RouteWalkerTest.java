package net.runelite.client.plugins.recorder.farm;

import java.util.HashSet;
import java.util.Set;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class RouteWalkerTest
{
    private static Set<WorldPoint> fillRect(WorldArea a)
    {
        Set<WorldPoint> out = new HashSet<>();
        for (int dx = 0; dx < a.getWidth(); dx++)
            for (int dy = 0; dy < a.getHeight(); dy++)
                out.add(new WorldPoint(a.getX() + dx, a.getY() + dy, a.getPlane()));
        return out;
    }

    @Test
    public void sampleTileReturnsTileInsideTheArea()
    {
        WorldArea a = new WorldArea(3091, 3243, 7, 5, 2);
        java.util.Random rng = new java.util.Random(42);
        WorldPoint t = RouteWalker.sampleTile(a, fillRect(a), rng, p -> true);
        assertNotNull(t);
        assertTrue(t.getX() >= 3091 && t.getX() <= 3097);
        assertTrue(t.getY() >= 3243 && t.getY() <= 3247);
        assertEquals(2, t.getPlane());
    }

    @Test
    public void sampleTileSkipsRejectedTiles()
    {
        WorldArea a = new WorldArea(0, 0, 3, 3, 0);
        java.util.Random rng = new java.util.Random(0);
        // Reject every tile except (1,1).
        WorldPoint t = RouteWalker.sampleTile(a, fillRect(a), rng, p -> p.getX() == 1 && p.getY() == 1);
        assertNotNull(t);
        assertEquals(1, t.getX());
        assertEquals(1, t.getY());
    }

    @Test
    public void sampleTileReturnsNullWhenAllTilesRejected()
    {
        WorldArea a = new WorldArea(0, 0, 3, 3, 0);
        java.util.Random rng = new java.util.Random(0);
        assertNull(RouteWalker.sampleTile(a, fillRect(a), rng, p -> false));
    }
}
