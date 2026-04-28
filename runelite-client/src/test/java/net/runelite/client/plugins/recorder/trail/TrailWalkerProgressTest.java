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
}
