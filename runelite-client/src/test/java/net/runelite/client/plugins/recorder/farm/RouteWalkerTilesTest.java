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
        // 5x5 bbox, ALL 25 tiles allowed, so the random loop hits `allowed`
        // on every one of its n*3 = 75 rolls. Reject the first 75 predicate
        // calls via a counter; the 76th call is the fallback enumeration,
        // which finally accepts. This guarantees the fallback path is taken.
        WorldArea bbox = new WorldArea(0, 0, 5, 5, 0);
        Set<WorldPoint> allowed = new HashSet<>();
        for (int x = 0; x < 5; x++)
            for (int y = 0; y < 5; y++)
                allowed.add(new WorldPoint(x, y, 0));
        int[] calls = { 0 };
        WorldPoint t = RouteWalker.sampleTile(bbox, allowed, new Random(0),
            p -> ++calls[0] > 75);
        assertNotNull("fallback should have found a tile", t);
        // The fallback was reached: predicate was invoked at call 76+.
        assertTrue("fallback not reached (calls=" + calls[0] + ")", calls[0] > 75);
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
