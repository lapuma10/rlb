package net.runelite.client.plugins.recorder.nav.v2.qc;

import java.util.List;
import java.util.Optional;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.nav.v2.qc.contracts.ExecutorResult;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Self-test for the Lane-6 RouteReplayValidator. Phase-1 default
 *  validator (chebyshev=1, same plane) is exercised here; Lane 3 swaps
 *  in the real RouteValidator at hand-off. */
public class RouteReplayValidatorTest
{
    @After
    public void reset()
    {
        RouteReplayValidator.wireValidator(null);
    }

    @Test
    public void validatesContiguousAdjacentSteps()
    {
        List<RouteTrace> traces = List.of(
            traceAt(new WorldPoint(3200, 3200, 0)),
            traceAt(new WorldPoint(3201, 3200, 0)),
            traceAt(new WorldPoint(3201, 3201, 0)),
            traceAt(new WorldPoint(3202, 3201, 0)));
        var v = RouteReplayValidator.validate(traces, null);
        assertTrue("contiguous adjacent steps must validate",
            v.allValid());
    }

    @Test
    public void rejectsLeapBetweenTiles()
    {
        List<RouteTrace> traces = List.of(
            traceAt(new WorldPoint(3200, 3200, 0)),
            // Leap of 4 tiles east — Phase-1 default validator rejects.
            traceAt(new WorldPoint(3204, 3200, 0)));
        var v = RouteReplayValidator.validate(traces, null);
        assertFalse("4-tile leap should be flagged invalid", v.allValid());
        assertTrue("rejection reason should explain the leap",
            v.perTick().get(1).reason().contains("step too large"));
    }

    @Test
    public void rejectsPlaneChangeWithoutTransport()
    {
        List<RouteTrace> traces = List.of(
            traceAt(new WorldPoint(3200, 3200, 0)),
            // Phantom plane change.
            traceAt(new WorldPoint(3200, 3200, 2)));
        var v = RouteReplayValidator.validate(traces, null);
        assertFalse("phantom plane change must fail validation", v.allValid());
        assertTrue(v.perTick().get(1).reason().contains("plane change"));
    }

    @Test
    public void tolerantOfStationaryTicks()
    {
        // Player did not move on tick 1 — waiting on dispatcher, etc.
        List<RouteTrace> traces = List.of(
            traceAt(new WorldPoint(3200, 3200, 0)),
            traceAt(new WorldPoint(3200, 3200, 0)),
            traceAt(new WorldPoint(3201, 3200, 0)));
        var v = RouteReplayValidator.validate(traces, null);
        assertTrue("stationary ticks must validate as no-op", v.allValid());
    }

    @Test
    public void wiredValidatorIsCalled()
    {
        boolean[] called = {false};
        RouteReplayValidator.wireValidator((prev, next, snap) -> {
            called[0] = true;
            return RouteReplayValidator.Verdict.accept();
        });
        List<RouteTrace> traces = List.of(
            traceAt(new WorldPoint(3200, 3200, 0)),
            traceAt(new WorldPoint(3201, 3201, 0)));
        RouteReplayValidator.validate(traces, null);
        assertTrue("wired Lane-3 validator must be called when set",
            called[0]);
    }

    private static RouteTrace traceAt(WorldPoint p)
    {
        return new RouteTrace(
            "t-" + p.getX() + "_" + p.getY() + "_" + p.getPlane(),
            0L, p,
            Optional.empty(), Optional.empty(),
            List.of(), List.of(),
            Optional.empty(), false,
            ExecutorResult.WAYPOINT_REACHED, Optional.empty());
    }
}
