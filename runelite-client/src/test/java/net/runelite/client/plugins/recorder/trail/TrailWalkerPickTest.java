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
        // Long leg with 24 ahead tiles all in minimap range → with the
        // wider near/far jitter the bot should produce many distinct
        // picks over 50 trials. We expect ≥ 8 distinct tiles to break
        // the "always same hop sequence" pattern the user complained
        // about. Old narrow-window code only produced 3-4.
        java.util.List<WorldPoint> tiles = new java.util.ArrayList<>();
        for (int i = 0; i < 25; i++) tiles.add(new WorldPoint(0, i, 0));
        Leg.Walk leg = new Leg.Walk(tiles);
        Random rng = new Random(42);
        Set<WorldPoint> seen = new HashSet<>();
        for (int i = 0; i < 50; i++)
        {
            seen.add(TrailWalker.pickAheadTile(leg, new WorldPoint(0, 0, 0), rng));
        }
        assertTrue("only " + seen.size() + " distinct picks — pattern too tight",
            seen.size() >= 8);
    }

    @Test
    public void pickAheadTileShortLegPicksWithinAheadWindow()
    {
        // Short leg (5 tiles) — every tile is within MAX_HOP_TILES. The
        // pick must be ahead of the player (idx >= 1) and never beyond
        // the leg's last tile (idx <= 4). The previous "always pick
        // last tile" rule was relaxed because it produced a recognizable
        // pattern; we tolerate any in-window pick.
        Leg.Walk leg = new Leg.Walk(List.of(
            new WorldPoint(0, 0, 0), new WorldPoint(0, 1, 0), new WorldPoint(0, 2, 0),
            new WorldPoint(0, 3, 0), new WorldPoint(0, 4, 0)));
        Random rng = new Random(42);
        for (int i = 0; i < 50; i++)
        {
            WorldPoint pick = TrailWalker.pickAheadTile(leg, new WorldPoint(0, 0, 0), rng);
            int idx = leg.tiles().indexOf(pick);
            assertTrue("pick idx " + idx + " out of ahead window [1..4]",
                idx >= 1 && idx <= 4);
        }
    }

    @Test
    public void pickAheadTileShortLegEventuallyReachesLastTile()
    {
        // Variety is good but the walker must still RELIABLY advance —
        // over enough trials the last tile must get picked, otherwise
        // the leg never advances. ~30% of picks should be in the far
        // half (last 2 tiles for a 5-tile leg) so the last tile shows
        // up in a 50-trial window.
        Leg.Walk leg = new Leg.Walk(List.of(
            new WorldPoint(0, 0, 0), new WorldPoint(0, 1, 0), new WorldPoint(0, 2, 0),
            new WorldPoint(0, 3, 0), new WorldPoint(0, 4, 0)));
        Random rng = new Random(42);
        WorldPoint last = leg.tiles().get(leg.tiles().size() - 1);
        boolean reachedLast = false;
        for (int i = 0; i < 100; i++)
        {
            if (last.equals(TrailWalker.pickAheadTile(leg, new WorldPoint(0, 0, 0), rng)))
            {
                reachedLast = true;
                break;
            }
        }
        assertTrue("100 picks never returned the last tile — walker would never advance",
            reachedLast);
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
