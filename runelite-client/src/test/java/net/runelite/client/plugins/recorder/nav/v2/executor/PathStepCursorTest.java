package net.runelite.client.plugins.recorder.nav.v2.executor;

import java.util.ArrayList;
import java.util.List;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.nav.v2.PathStep;
import net.runelite.client.plugins.recorder.nav.v2.TransportStep;
import net.runelite.client.plugins.recorder.nav.v2.V2Leg;
import net.runelite.client.plugins.recorder.nav.v2.V2Path;
import net.runelite.client.plugins.recorder.nav.v2.WalkStep;
import net.runelite.client.plugins.recorder.worldmap.TransportEdge;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Lane 5 plan Task 2: cursor that tracks current PathStep index
 *  within a V2Path. */
public class PathStepCursorTest
{
    private static V2Path twoWalkOneTransport()
    {
        List<WorldPoint> leg1 = new ArrayList<>();
        for (int i = 0; i < 3; i++) leg1.add(new WorldPoint(3200 + i, 3210, 0));
        List<WorldPoint> leg2 = new ArrayList<>();
        for (int i = 0; i < 3; i++) leg2.add(new WorldPoint(3210 + i, 3210, 1));
        TransportEdge edge = new TransportEdge(
            new WorldPoint(3203, 3210, 0), new WorldPoint(3210, 3210, 1),
            1530, "Stair", "Climb-up", 0, 0, "GameObject",
            new WorldPoint(3203, 3210, 0), 12850, 1, 1L, 200L);
        return new V2Path(List.of(
            new V2Leg.Walk(12850, leg1),
            new V2Leg.Transport(edge),
            new V2Leg.Walk(12851, leg2)), 10);
    }

    @Test
    public void cursor_current_returnsCurrentStep()
    {
        V2Path path = twoWalkOneTransport();
        PathStepCursor cur = new PathStepCursor(path);
        assertTrue("first step present", cur.current().isPresent());
        assertTrue("first step is WalkStep", cur.current().get() instanceof WalkStep);
    }

    @Test
    public void cursor_advance_movesForward()
    {
        V2Path path = twoWalkOneTransport();
        PathStepCursor cur = new PathStepCursor(path);
        cur.advance();
        assertEquals(1, cur.currentIndex());
        PathStep s = cur.current().orElseThrow();
        assertTrue("second step is TransportStep", s instanceof TransportStep);
    }

    @Test
    public void cursor_peek_offset_returnsFutureStep()
    {
        V2Path path = twoWalkOneTransport();
        PathStepCursor cur = new PathStepCursor(path);
        assertTrue("peek(0) at start is the walk step",
            cur.peek(0).orElseThrow() instanceof WalkStep);
        assertTrue("peek(1) is the transport step",
            cur.peek(1).orElseThrow() instanceof TransportStep);
        assertTrue("peek(2) is the second walk step",
            cur.peek(2).orElseThrow() instanceof WalkStep);
    }

    @Test
    public void cursor_peek_beyondEnd_returnsEmpty()
    {
        V2Path path = twoWalkOneTransport();
        PathStepCursor cur = new PathStepCursor(path);
        assertFalse("peek beyond end is empty", cur.peek(99).isPresent());
        assertFalse("peek negative is empty", cur.peek(-1).isPresent());
    }

    @Test
    public void cursor_isAtEnd_afterAdvancePastLast()
    {
        V2Path path = twoWalkOneTransport();
        PathStepCursor cur = new PathStepCursor(path);
        cur.advance();
        cur.advance();
        cur.advance();
        assertTrue("3 advances on a 3-step path leaves cursor at end", cur.isAtEnd());
        assertFalse(cur.current().isPresent());
        assertEquals(0, cur.remainingSteps());
    }

    @Test
    public void cursor_remainingSteps_decreasesWithAdvance()
    {
        V2Path path = twoWalkOneTransport();
        PathStepCursor cur = new PathStepCursor(path);
        assertEquals(3, cur.remainingSteps());
        cur.advance();
        assertEquals(2, cur.remainingSteps());
        cur.advance();
        assertEquals(1, cur.remainingSteps());
    }

    @Test
    public void cursor_emptyPath_isAtEndImmediately()
    {
        PathStepCursor cur = new PathStepCursor(V2Path.EMPTY);
        assertTrue(cur.isAtEnd());
        assertFalse(cur.current().isPresent());
        assertEquals(0, cur.remainingSteps());
    }

    @Test
    public void cursor_nullPath_isAtEndImmediately()
    {
        PathStepCursor cur = new PathStepCursor(null);
        assertTrue(cur.isAtEnd());
        assertFalse(cur.current().isPresent());
    }
}
