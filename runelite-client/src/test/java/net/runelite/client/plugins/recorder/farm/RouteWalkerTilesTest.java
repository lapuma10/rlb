package net.runelite.client.plugins.recorder.farm;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class RouteWalkerTilesTest
{
    @Test
    public void sampleTileOnlyReturnsTilesInAllowedSet()
    {
        // 3x3 bbox, but only the diagonal tiles are allowed.
        WorldArea bbox = new WorldArea(0, 0, 3, 3, 0);
        Set<WorldPoint> allowed = Set.of(
            new WorldPoint(0, 0, 0),
            new WorldPoint(1, 1, 0),
            new WorldPoint(2, 2, 0));
        Random rng = new Random(0);

        // Run many samples; every result must be in `allowed`.
        for (int i = 0; i < 100; i++)
        {
            WorldPoint t = RouteWalker.sampleTile(bbox, allowed, rng, p -> true);
            assertNotNull(t);
            assertTrue("tile " + t + " not in allowed", allowed.contains(t));
        }
    }

    @Test
    public void sampleTileFallsBackToEnumerationWhenAllowedIsTiny()
    {
        // 5x5 bbox, only ONE allowed tile. Random rolls have ~1/25 chance
        // of hitting it; the enumeration fallback guarantees we find it.
        WorldArea bbox = new WorldArea(0, 0, 5, 5, 0);
        Set<WorldPoint> allowed = Set.of(new WorldPoint(3, 4, 0));
        Random rng = new Random(0);

        WorldPoint t = RouteWalker.sampleTile(bbox, allowed, rng, p -> true);
        assertEquals(new WorldPoint(3, 4, 0), t);
    }

    @Test
    public void sampleTileReturnsNullWhenNoAllowedTilePassesPredicate()
    {
        WorldArea bbox = new WorldArea(0, 0, 3, 3, 0);
        Set<WorldPoint> allowed = new HashSet<>();
        allowed.add(new WorldPoint(0, 0, 0));
        // Predicate rejects everything.
        assertNull(RouteWalker.sampleTile(bbox, allowed, new Random(0), p -> false));
    }

    @Test
    public void sampleTileReturnsNullWhenAllowedSetIsEmpty()
    {
        assertNull(RouteWalker.sampleTile(new WorldArea(0, 0, 3, 3, 0),
            Set.of(), new Random(0), p -> true));
    }
}
