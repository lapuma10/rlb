package net.runelite.client.plugins.recorder.trail;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class TrailWalkerPickTest
{
    @Test
    public void pickAheadTileIsAheadOfPlayerInLeg()
    {
        Leg.Walk leg = new Leg.Walk(List.of(
            new WorldPoint(0, 0, 0), new WorldPoint(0, 1, 0), new WorldPoint(0, 2, 0),
            new WorldPoint(0, 3, 0), new WorldPoint(0, 4, 0)));
        // Player at index 1 → ahead = indices 2..4. Each call must return
        // a tile in that ahead window.
        Random rng = new Random(42);
        for (int i = 0; i < 50; i++)
        {
            WorldPoint pick = TrailWalker.pickAheadTile(leg, new WorldPoint(0, 1, 0), rng);
            int idx = leg.tiles().indexOf(pick);
            assertTrue("pick " + pick + " not ahead (idx=" + idx + ")",
                idx >= 2 && idx <= 4);
        }
    }

    @Test
    public void pickAheadTileVariesAcrossCalls()
    {
        // Long leg → multiple distinct picks across many calls.
        Leg.Walk leg = new Leg.Walk(List.of(
            new WorldPoint(0,0,0), new WorldPoint(0,1,0), new WorldPoint(0,2,0),
            new WorldPoint(0,3,0), new WorldPoint(0,4,0), new WorldPoint(0,5,0),
            new WorldPoint(0,6,0), new WorldPoint(0,7,0), new WorldPoint(0,8,0)));
        Random rng = new Random(42);
        Set<WorldPoint> seen = new HashSet<>();
        for (int i = 0; i < 50; i++)
        {
            seen.add(TrailWalker.pickAheadTile(leg, new WorldPoint(0, 0, 0), rng));
        }
        // With a 6-tile ahead window we expect at least 4 distinct picks
        // over 50 trials (would be 6 with perfect uniform sampling, leave
        // headroom for the smallest-window-bias rule).
        assertTrue("only " + seen.size() + " distinct picks", seen.size() >= 4);
    }

    @Test
    public void pickAheadTilePlayerNotInLegFallsBackToFarthest()
    {
        Leg.Walk leg = new Leg.Walk(List.of(
            new WorldPoint(0,0,0), new WorldPoint(0,1,0), new WorldPoint(0,2,0)));
        Random rng = new Random(0);
        // Player at (5,5,0) — not in leg. Pick should be farthest tile.
        WorldPoint pick = TrailWalker.pickAheadTile(leg, new WorldPoint(5, 5, 0), rng);
        assertEquals(new WorldPoint(0, 2, 0), pick);
    }
}
