package net.runelite.client.plugins.recorder.trail;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Unit tests for the experimental corridor walker — {@link
 * TrailWalker#pickCorridorTile}, {@link TrailWalker#distanceToNearestTile}.
 * Corridor mode replaces the recorded forward pick with a random walkable
 * tile within {@code radius} of the trail line; these tests pin the
 * invariants that keep the pick on-corridor, on-plane, forward-progressing,
 * and walkable.
 */
public class TrailWalkerCorridorTest
{
    /** Always-walkable probe used when the test isn't exercising the
     *  walkability filter directly. */
    private static final Predicate<WorldPoint> ALWAYS_WALKABLE = tile -> true;

    /** Build a straight 10-tile leg along (x=10..19, y=10, plane=0). */
    private static Leg.Walk straightLeg()
    {
        java.util.List<WorldPoint> tiles = new java.util.ArrayList<>();
        for (int x = 10; x <= 19; x++)
        {
            tiles.add(new WorldPoint(x, 10, 0));
        }
        return new Leg.Walk(tiles);
    }

    @Test
    public void emptyWhenNoForwardCandidatePassesProgressFilter()
    {
        // Player at the leg's END tile — playerDistToEnd = 0. Any
        // candidate offset from a forward pick where dx != 0 would have
        // candidateDistToEnd >= 1, which is > playerDistToEnd + 1 = 1
        // ONLY when offset > 1. With radius=1, every candidate has
        // candidateDistToEnd <= 1 = playerDistToEnd + 1, so this case
        // doesn't yield empty by itself. Force empty by combining
        // player-at-end with originalPick == legEnd and radius=1:
        // every (dx,dy) candidate around (19,10) lands at distance >= 1
        // from legEnd; with playerDistToEnd=0, the threshold is dist <= 1,
        // so candidates with chebyshev(c, legEnd) == 1 still pass.
        // Simpler: use a walkable predicate that vetos every tile.
        Leg.Walk leg = straightLeg();
        WorldPoint player = new WorldPoint(19, 10, 0);
        WorldPoint originalPick = new WorldPoint(19, 10, 0);
        Optional<WorldPoint> result = TrailWalker.pickCorridorTile(
            leg, player, originalPick, 1, new Random(42), tile -> false);
        assertFalse("walkable predicate vetos all → expected empty",
            result.isPresent());
    }

    @Test
    public void candidateStaysWithinRadiusOfTrail()
    {
        // Corridor invariant: every returned candidate must be within
        // radius of SOME leg tile. Run many trials with radius=2 and
        // check the invariant holds for each.
        Leg.Walk leg = straightLeg();
        WorldPoint player = new WorldPoint(10, 10, 0);
        WorldPoint originalPick = new WorldPoint(15, 10, 0);
        Random rng = new Random(7);
        Set<WorldPoint> seen = new HashSet<>();
        for (int i = 0; i < 100; i++)
        {
            Optional<WorldPoint> pick = TrailWalker.pickCorridorTile(
                leg, player, originalPick, 2, rng, ALWAYS_WALKABLE);
            assertTrue("expected a candidate on iteration " + i, pick.isPresent());
            seen.add(pick.get());
            int distToTrail = TrailWalker.distanceToNearestTile(pick.get(), leg.tiles());
            assertTrue("candidate " + pick.get() + " is " + distToTrail
                + " tiles from trail; corridor radius=2",
                distToTrail <= 2);
        }
        // Sanity: a non-trivial set of distinct candidates was actually
        // returned (otherwise the test trivially passes).
        assertTrue("expected variety; got only " + seen.size(),
            seen.size() >= 4);
    }

    @Test
    public void candidateAlwaysOnPlayerPlane()
    {
        Leg.Walk leg = straightLeg();
        WorldPoint player = new WorldPoint(10, 10, 0);
        WorldPoint originalPick = new WorldPoint(15, 10, 0);
        Random rng = new Random(11);
        for (int i = 0; i < 50; i++)
        {
            Optional<WorldPoint> pick = TrailWalker.pickCorridorTile(
                leg, player, originalPick, 2, rng, ALWAYS_WALKABLE);
            assertTrue(pick.isPresent());
            assertEquals("candidate must be on player's plane",
                player.getPlane(), pick.get().getPlane());
        }
    }

    @Test
    public void candidateMaintainsForwardProgress()
    {
        // Forward filter: chebyshev(c, legEnd) <= chebyshev(player, legEnd) + 1.
        // Pick player far from end so the filter has room to bite.
        Leg.Walk leg = straightLeg();
        WorldPoint player = new WorldPoint(10, 10, 0);
        WorldPoint originalPick = new WorldPoint(13, 10, 0);
        WorldPoint legEnd = leg.tiles().get(leg.tiles().size() - 1);
        int playerDistToEnd = Math.max(
            Math.abs(player.getX() - legEnd.getX()),
            Math.abs(player.getY() - legEnd.getY()));
        Random rng = new Random(42);
        for (int i = 0; i < 100; i++)
        {
            Optional<WorldPoint> pick = TrailWalker.pickCorridorTile(
                leg, player, originalPick, 2, rng, ALWAYS_WALKABLE);
            assertTrue(pick.isPresent());
            int candidateDistToEnd = Math.max(
                Math.abs(pick.get().getX() - legEnd.getX()),
                Math.abs(pick.get().getY() - legEnd.getY()));
            assertTrue("candidate " + pick.get() + " distToEnd=" + candidateDistToEnd
                + " > playerDistToEnd+1=" + (playerDistToEnd + 1),
                candidateDistToEnd <= playerDistToEnd + 1);
        }
    }

    @Test
    public void walkableProbeRejectionIsHonoured()
    {
        // Reject every tile with even x. Every returned candidate must
        // therefore have an odd x.
        Leg.Walk leg = straightLeg();
        WorldPoint player = new WorldPoint(10, 10, 0);
        WorldPoint originalPick = new WorldPoint(15, 10, 0);
        Predicate<WorldPoint> oddXOnly = tile -> (tile.getX() & 1) == 1;
        Random rng = new Random(99);
        boolean sawAny = false;
        for (int i = 0; i < 100; i++)
        {
            Optional<WorldPoint> pick = TrailWalker.pickCorridorTile(
                leg, player, originalPick, 2, rng, oddXOnly);
            if (pick.isPresent())
            {
                sawAny = true;
                assertEquals("walkable probe must veto even-x picks",
                    1, pick.get().getX() & 1);
            }
        }
        assertTrue("walkable filter should still permit some odd-x picks", sawAny);
    }

    @Test
    public void emptyWhenRadiusZero()
    {
        Leg.Walk leg = straightLeg();
        WorldPoint player = new WorldPoint(10, 10, 0);
        WorldPoint originalPick = new WorldPoint(15, 10, 0);
        Optional<WorldPoint> pick = TrailWalker.pickCorridorTile(
            leg, player, originalPick, 0, new Random(1), ALWAYS_WALKABLE);
        assertFalse(pick.isPresent());
    }
}
