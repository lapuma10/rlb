package net.runelite.client.plugins.recorder.walker;

import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.transport.Waypoint;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Pure-logic tests for {@link UniversalWalker} that don't require a
 * Client mock. The state-machine integration is exercised by the live
 * scripts that consume the walker (e.g. LumbridgeBankPenWalkerScript) —
 * a unit test here would over-mock the Client to the point of being
 * tautological.
 */
public class UniversalWalkerTest
{
    @Test
    public void arrivedAtWalkAreaWhenInsideBbox()
    {
        Waypoint w = Waypoint.walkArea("yard", new WorldArea(3219, 3217, 9, 4, 0));
        assertTrue(UniversalWalker.arrived(w, new WorldPoint(3220, 3218, 0)));
        assertFalse(UniversalWalker.arrived(w, new WorldPoint(3210, 3210, 0)));
        // Same coords, different plane — never arrived.
        assertFalse(UniversalWalker.arrived(w, new WorldPoint(3220, 3218, 1)));
    }

    @Test
    public void arrivedAtSingleTileWalk()
    {
        Waypoint w = Waypoint.walk(new WorldPoint(3220, 3218, 0));
        assertTrue(UniversalWalker.arrived(w, new WorldPoint(3220, 3218, 0)));
        assertFalse(UniversalWalker.arrived(w, new WorldPoint(3221, 3218, 0)));
    }

    @Test
    public void arrivedAtClimbUpRequiresPlaneChange()
    {
        Waypoint w = Waypoint.transport(
            new WorldPoint(3205, 3229, 0), Waypoint.TransportKind.CLIMB_UP, "Climb-up");
        // Same plane → not arrived.
        assertFalse(UniversalWalker.arrived(w, new WorldPoint(3205, 3229, 0)));
        // Different plane → arrived (climb completed).
        assertTrue(UniversalWalker.arrived(w, new WorldPoint(3205, 3229, 1)));
    }

    @Test
    public void arrivedAtOpenTransportWhenAdjacent()
    {
        Waypoint w = Waypoint.transport(
            new WorldPoint(3243, 3236, 0), Waypoint.TransportKind.OPEN, "Open");
        assertTrue(UniversalWalker.arrived(w, new WorldPoint(3243, 3236, 0)));
        assertTrue(UniversalWalker.arrived(w, new WorldPoint(3242, 3237, 0)));
        // 2 tiles away → not adjacent.
        assertFalse(UniversalWalker.arrived(w, new WorldPoint(3245, 3236, 0)));
    }

    @Test
    public void nullsAreNeverArrived()
    {
        assertFalse(UniversalWalker.arrived(null, new WorldPoint(0, 0, 0)));
        assertFalse(UniversalWalker.arrived(
            Waypoint.walk(new WorldPoint(0, 0, 0)), null));
    }
}
