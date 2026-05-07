package net.runelite.client.plugins.recorder.nav.v2;

import org.junit.Test;
import static org.junit.Assert.*;

public class RouteHistoryTest
{
    @Test
    public void emptyHistory_returnsBaselinePenalty()
    {
        RouteHistory h = new RouteHistory();
        assertEquals(1.0, h.penaltyFor("route-A"), 1e-9);
    }

    @Test
    public void recordAndPenalty_sameRouteOnce_mildPenalty()
    {
        RouteHistory h = new RouteHistory();
        h.record("route-A");
        double p = h.penaltyFor("route-A");
        assertTrue("once-used route penalised above baseline (got " + p + ")",
            p > 1.0);
        assertTrue("once-used penalty stays below the strong threshold",
            p < RouteHistory.STRONG_PENALTY);
    }

    @Test
    public void recordAndPenalty_sameRouteThreeTimes_strongPenalty()
    {
        RouteHistory h = new RouteHistory();
        h.record("route-A");
        h.record("route-A");
        h.record("route-A");
        assertEquals(RouteHistory.STRONG_PENALTY,
            h.penaltyFor("route-A"), 1e-9);
    }

    @Test
    public void recordAndPenalty_differentRoute_zeroPenalty()
    {
        RouteHistory h = new RouteHistory();
        h.record("route-A");
        h.record("route-A");
        h.record("route-A");
        assertEquals("untouched routes never incur a penalty",
            1.0, h.penaltyFor("route-B"), 1e-9);
    }

    @Test
    public void history_isBounded_toLastFiveEntries()
    {
        RouteHistory h = new RouteHistory();
        // Push 6 routes; the oldest must roll out.
        h.record("route-A");
        h.record("route-B");
        h.record("route-C");
        h.record("route-D");
        h.record("route-E");
        h.record("route-F");
        assertEquals("oldest route rolled out of the ring",
            1.0, h.penaltyFor("route-A"), 1e-9);
        assertTrue("newest route still in the ring",
            h.penaltyFor("route-F") > 1.0);
    }

    @Test
    public void recentEntries_returnsLastFiveInInsertionOrder()
    {
        RouteHistory h = new RouteHistory();
        h.record("a");
        h.record("b");
        h.record("c");
        assertEquals(java.util.List.of("a", "b", "c"), h.recentEntries());
    }
}
