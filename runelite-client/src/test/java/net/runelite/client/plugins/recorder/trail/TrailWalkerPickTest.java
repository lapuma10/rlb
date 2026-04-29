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
        // Long leg with 24 ahead tiles all in minimap range → jitter
        // window is 24/4 = 6 tiles → expect at least 4 distinct picks
        // over 50 trials.
        java.util.List<WorldPoint> tiles = new java.util.ArrayList<>();
        for (int i = 0; i < 25; i++) tiles.add(new WorldPoint(0, i, 0));
        Leg.Walk leg = new Leg.Walk(tiles);
        Random rng = new Random(42);
        Set<WorldPoint> seen = new HashSet<>();
        for (int i = 0; i < 50; i++)
        {
            seen.add(TrailWalker.pickAheadTile(leg, new WorldPoint(0, 0, 0), rng));
        }
        assertTrue("only " + seen.size() + " distinct picks", seen.size() >= 4);
    }

    @Test
    public void pickAheadTileClicksLastTileWhenWithinHopRange()
    {
        // Short leg (5 tiles) — every tile is within MAX_HOP_TILES.
        // The pick should always be the last tile (or close to it) so the
        // walker actually reaches the leg's end and advances.
        Leg.Walk leg = new Leg.Walk(List.of(
            new WorldPoint(0, 0, 0), new WorldPoint(0, 1, 0), new WorldPoint(0, 2, 0),
            new WorldPoint(0, 3, 0), new WorldPoint(0, 4, 0)));
        Random rng = new Random(42);
        WorldPoint last = leg.tiles().get(leg.tiles().size() - 1);
        for (int i = 0; i < 20; i++)
        {
            WorldPoint pick = TrailWalker.pickAheadTile(leg, new WorldPoint(0, 0, 0), rng);
            assertEquals("short leg should always pick the last tile", last, pick);
        }
    }

    @Test
    public void pickAheadTileNeverPicksBeyondHopRange()
    {
        // Leg of 60 tiles — only the first 16 are within minimap walk
        // range from the player at index 0. Pick must be one of those.
        java.util.List<WorldPoint> tiles = new java.util.ArrayList<>();
        for (int i = 0; i < 60; i++) tiles.add(new WorldPoint(0, i, 0));
        Leg.Walk leg = new Leg.Walk(tiles);
        Random rng = new Random(42);
        for (int trial = 0; trial < 100; trial++)
        {
            WorldPoint pick = TrailWalker.pickAheadTile(leg, new WorldPoint(0, 0, 0), rng);
            int dy = Math.abs(pick.getY() - 0);
            assertTrue("pick " + pick + " is " + dy + " tiles away — beyond minimap hop",
                dy <= TrailWalker.MAX_HOP_TILES);
        }
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
