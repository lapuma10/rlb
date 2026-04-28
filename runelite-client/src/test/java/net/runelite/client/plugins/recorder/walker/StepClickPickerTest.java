package net.runelite.client.plugins.recorder.walker;

import java.util.List;
import net.runelite.api.CollisionData;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the pure-logic parts of {@link StepClickPicker}. The
 * {@code pick} method depends on {@link net.runelite.api.Perspective} —
 * which reads live camera state and is essentially un-mockable — so we
 * exercise the static {@code candidatesInArea} helper that returns the
 * ordered list of reachable tiles inside an area, sorted by Chebyshev
 * distance to the area centre. That's the same ordering the live picker
 * uses to score canvas projections.
 */
public class StepClickPickerTest
{
    private static WorldView openWorldView()
    {
        WorldView wv = mock(WorldView.class);
        when(wv.getBaseX()).thenReturn(3200);
        when(wv.getBaseY()).thenReturn(3200);
        when(wv.getSizeX()).thenReturn(104);
        when(wv.getSizeY()).thenReturn(104);
        CollisionData cd = mock(CollisionData.class);
        when(cd.getFlags()).thenReturn(new int[104][104]);
        when(wv.getCollisionMaps()).thenReturn(new CollisionData[]{cd, null, null, null});
        return wv;
    }

    @Test
    public void candidatesInAreaIsOrderedByChebyshevToCentre()
    {
        WorldView wv = openWorldView();
        WorldPoint origin = new WorldPoint(3210, 3210, 0);
        Reachability.ReachabilityMap reach = Reachability.compute(wv, origin, 8);
        WorldArea target = new WorldArea(3215, 3215, 3, 3, 0); // centre = (3216, 3216)

        List<WorldPoint> sorted = StepClickPicker.candidatesInArea(reach, target);
        assertFalse(sorted.isEmpty());
        // Centre should appear at or near the front (distance 0).
        WorldPoint centre = new WorldPoint(3216, 3216, 0);
        assertEquals(centre, sorted.get(0));
        // Last should be at distance ≥ 1.
        WorldPoint last = sorted.get(sorted.size() - 1);
        int dlast = Math.max(Math.abs(last.getX() - 3216), Math.abs(last.getY() - 3216));
        assertTrue("last tile must be at the area edge", dlast >= 1);
    }

    @Test
    public void candidatesInAreaSkipsPlaneMismatch()
    {
        WorldView wv = openWorldView();
        WorldPoint origin = new WorldPoint(3210, 3210, 0);
        Reachability.ReachabilityMap reach = Reachability.compute(wv, origin, 8);
        // Area on plane 1 — origin is on plane 0, so no tile in reach
        // is on plane 1.
        WorldArea other = new WorldArea(3215, 3215, 2, 2, 1);
        assertTrue(StepClickPicker.candidatesInArea(reach, other).isEmpty());
    }

    @Test
    public void candidatesInAreaReturnsEmptyForNullInputs()
    {
        assertTrue(StepClickPicker.candidatesInArea(null, null).isEmpty());
    }

    @Test
    public void areaContainsCheckIsBboxAndPlaneAware()
    {
        WorldArea a = new WorldArea(10, 10, 3, 3, 0);
        assertTrue(StepClickPicker.areaContains(a, new WorldPoint(10, 10, 0)));
        assertTrue(StepClickPicker.areaContains(a, new WorldPoint(12, 12, 0)));
        assertFalse(StepClickPicker.areaContains(a, new WorldPoint(13, 12, 0)));
        assertFalse(StepClickPicker.areaContains(a, new WorldPoint(10, 10, 1)));
    }

    @Test
    public void chebyshevHelperHandlesNegativeDeltas()
    {
        assertEquals(5, StepClickPicker.chebyshev(0, 0, 5, -3));
        assertEquals(7, StepClickPicker.chebyshev(-7, 0, 0, 0));
        assertEquals(0, StepClickPicker.chebyshev(0, 0, 0, 0));
    }
}
