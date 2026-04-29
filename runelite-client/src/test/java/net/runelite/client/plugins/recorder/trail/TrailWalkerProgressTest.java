package net.runelite.client.plugins.recorder.trail;

import java.util.List;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class TrailWalkerProgressTest
{
    @Test
    public void chooseLegAdvancesPastWalkLegPlayerHasPassed()
    {
        Leg.Walk a = new Leg.Walk(List.of(
            new WorldPoint(0,0,0), new WorldPoint(0,1,0), new WorldPoint(0,2,0)));
        Leg.Walk b = new Leg.Walk(List.of(
            new WorldPoint(0,2,0), new WorldPoint(0,3,0), new WorldPoint(0,4,0)));
        TrailPath p = new TrailPath(List.of(a, b));
        // Player at (0,3,0) — already in leg b's tile-set.
        assertEquals(1, TrailWalker.chooseLegIndex(p, 0, new WorldPoint(0, 3, 0)));
    }

    @Test
    public void chooseLegStaysOnLegWhenNotYetPassed()
    {
        Leg.Walk a = new Leg.Walk(List.of(
            new WorldPoint(0,0,0), new WorldPoint(0,1,0)));
        Leg.Walk b = new Leg.Walk(List.of(
            new WorldPoint(0,1,0), new WorldPoint(0,2,0)));
        TrailPath p = new TrailPath(List.of(a, b));
        assertEquals(0, TrailWalker.chooseLegIndex(p, 0, new WorldPoint(0, 0, 0)));
    }

    @Test
    public void chooseLegMonotonicForward()
    {
        // Even if the player drifts back into leg-0's bbox, do not roll
        // back to leg 0 — start the search from minIdx.
        Leg.Walk a = new Leg.Walk(List.of(
            new WorldPoint(0,0,0), new WorldPoint(0,1,0)));
        Leg.Walk b = new Leg.Walk(List.of(
            new WorldPoint(0,2,0), new WorldPoint(0,3,0)));
        TrailPath p = new TrailPath(List.of(a, b));
        // Already on leg 1; player drifted back to (0,1,0). Should NOT
        // return 0.
        assertEquals(1, TrailWalker.chooseLegIndex(p, 1, new WorldPoint(0, 1, 0)));
    }

    @Test
    public void chooseLegAdvancesFromWalkEndToTransport()
    {
        // The TrailPath.fromTrail output places a TRANSPORT leg right
        // after a WALK leg whose final tile is the staircase approach.
        // The transport's tile is the staircase OBJECT — not a tile in
        // the walk leg — so the old "advance only when next leg contains
        // pos" logic stalled at the end of the walk. Verify the new
        // logic advances when player is on the walk leg's last tile.
        Leg.Walk approach = new Leg.Walk(List.of(
            new WorldPoint(3206, 3224, 2), new WorldPoint(3206, 3226, 2)));
        Leg.Transport stairs = new Leg.Transport(
            new WorldPoint(3205, 3229, 2), "Climb-down", 56231, "GameObject", 45, 61);
        TrailPath p = new TrailPath(List.of(approach, stairs));
        // Player on the last tile of the walk leg → advance to the
        // transport leg even though player is not on the staircase tile.
        assertEquals(1,
            TrailWalker.chooseLegIndex(p, 0, new WorldPoint(3206, 3226, 2)));
    }

    @Test
    public void chooseLegRecoversFromUnexpectedPlaneChangeMidWalkLeg()
    {
        // Real failure mode (2026-04-29 17:40 stuck loop): bot was walking
        // pen-to-lumby-bank on a plane=0 walk leg when the player ended up
        // on plane=2 (manual stair-click during a script idle window).
        // The current leg's tiles were all p=0; the next leg was a p=0
        // transport; nothing in `idx + 1` matched the player. Walker sat
        // for over a minute hammering minimap clicks at the leg's last
        // p=0 tile, never advancing.
        //
        // Recovery: when the current leg has NO tiles on the player's
        // plane, hop to the first future leg that does.
        Leg.Walk plane0Walk = new Leg.Walk(List.of(
            new WorldPoint(3215, 3219, 0), new WorldPoint(3207, 3228, 0)));
        Leg.Transport stairsUp0 = new Leg.Transport(
            new WorldPoint(3204, 3229, 0), "Climb-up", 56230, "GameObject", 36, 61);
        Leg.Walk midPlane = new Leg.Walk(List.of(
            new WorldPoint(3205, 3228, 0), new WorldPoint(3206, 3229, 1)));
        Leg.Transport stairsUp1 = new Leg.Transport(
            new WorldPoint(3204, 3229, 1), "Climb-up", 16672, "GameObject", 36, 61);
        Leg.Walk plane2Walk = new Leg.Walk(List.of(
            new WorldPoint(3206, 3229, 2), new WorldPoint(3208, 3220, 2)));
        TrailPath p = new TrailPath(List.of(
            plane0Walk, stairsUp0, midPlane, stairsUp1, plane2Walk));
        // Player drifted to (3207, 3228, p=2) — not in any leg's tile
        // list, but plane-based fallback should hop to leg 4 (the only
        // leg with p=2 tiles).
        assertEquals(4,
            TrailWalker.chooseLegIndex(p, 0, new WorldPoint(3207, 3228, 2)));
    }

    @Test
    public void chooseLegForwardScansFromWalkLegToFutureWalkLeg()
    {
        // Tighter case: forward-scan should fire from a WALK leg too, not
        // just from a TRANSPORT leg. Previously the scan was gated on
        // `cur instanceof Leg.Transport` which masked the plane-drift bug.
        Leg.Walk legA = new Leg.Walk(List.of(
            new WorldPoint(0, 0, 0), new WorldPoint(0, 5, 0)));
        Leg.Walk legB = new Leg.Walk(List.of(
            new WorldPoint(0, 5, 0), new WorldPoint(0, 10, 0)));
        Leg.Walk legC = new Leg.Walk(List.of(
            new WorldPoint(0, 10, 0), new WorldPoint(0, 12, 0), new WorldPoint(0, 15, 0)));
        TrailPath p = new TrailPath(List.of(legA, legB, legC));
        // Player skipped past leg B entirely and is on a leg-C tile that
        // is not in leg A or leg B's tile list.
        assertEquals(2,
            TrailWalker.chooseLegIndex(p, 0, new WorldPoint(0, 12, 0)));
    }

    @Test
    public void chooseLegForwardScansAfterTransportToReachActiveLeg()
    {
        // After a stair Climb-down, the engine teleports the player past
        // the trivial post-stair tile to a position that lives on a later
        // walk leg. Without forward-scan, the walker would sit on the
        // transport leg waiting for the player to land on the next leg's
        // first tile (the trivial 1-tile post-stair leg) — which it
        // already skipped past. Verify forward-scan locates the actual
        // leg the player is on.
        Leg.Walk preStair = new Leg.Walk(List.of(
            new WorldPoint(3206, 3226, 2)));
        Leg.Transport climbDown = new Leg.Transport(
            new WorldPoint(3205, 3229, 2), "Climb-down", 56231, "GameObject", 45, 61);
        Leg.Walk postStair = new Leg.Walk(List.of(
            new WorldPoint(3205, 3228, 1)));
        Leg.Transport climbDown2 = new Leg.Transport(
            new WorldPoint(3204, 3229, 1), "Climb-down", 16672, "GameObject", 44, 61);
        Leg.Walk plane0Walk = new Leg.Walk(List.of(
            new WorldPoint(3205, 3228, 0), new WorldPoint(3210, 3228, 0)));
        TrailPath p = new TrailPath(List.of(
            preStair, climbDown, postStair, climbDown2, plane0Walk));
        // Walker just fired Climb-down on the p=2 stair, player teleports
        // to (3210, 3228, p=0) skipping the trivial p=1 tile — entry leg
        // should be 4 (the plane-0 walk).
        assertEquals(4,
            TrailWalker.chooseLegIndex(p, 1, new WorldPoint(3210, 3228, 0)));
    }
}
