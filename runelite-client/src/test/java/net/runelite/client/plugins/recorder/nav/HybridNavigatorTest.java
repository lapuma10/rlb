package net.runelite.client.plugins.recorder.nav;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.RecorderConfig.NavigatorMode;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class HybridNavigatorTest
{
    /** Recording stub: returns a queued sequence of NavStatuses; counts
     *  ticks/cancels. */
    private static final class RecorderNavigator implements Navigator
    {
        final String name;
        final List<NavStatus> statuses;
        int idx;
        int tickCount;
        int cancelCount;
        final List<NavRequest> seenRequests = new ArrayList<>();

        RecorderNavigator(String name, NavStatus... statuses)
        {
            this.name = name;
            this.statuses = new ArrayList<>(List.of(statuses));
        }

        @Override
        public NavStatus tick(NavRequest request)
        {
            tickCount++;
            seenRequests.add(request);
            if (statuses.isEmpty()) return NavStatus.RUNNING;
            NavStatus s = statuses.get(Math.min(idx, statuses.size() - 1));
            idx++;
            return s;
        }

        @Override public void cancel() { cancelCount++; }
        @Override public boolean isBusy() { return false; }
        @Override public String name() { return name; }
    }

    private static NavRequest req()
    {
        return NavRequest.compose("trail", new WorldPoint(3208, 3217, 0), BehaviorMode.VARIED);
    }

    @Test
    public void v1Only_alwaysDelegatesToV1_neverToV2() throws Exception
    {
        RecorderNavigator v1 = new RecorderNavigator("trail-v1", NavStatus.RUNNING, NavStatus.ARRIVED);
        RecorderNavigator v2 = new RecorderNavigator("worldmap-v2", NavStatus.FAILED);
        HybridNavigator hybrid = new HybridNavigator(v1, v2, () -> NavigatorMode.V1_ONLY);

        hybrid.tick(req());
        hybrid.tick(req());

        assertEquals("V1_ONLY must drive V1 every tick", 2, v1.tickCount);
        assertEquals("V1_ONLY must never call V2", 0, v2.tickCount);
    }

    @Test
    public void v2Strict_delegatesToV2_andReturnsItsStatus() throws Exception
    {
        RecorderNavigator v1 = new RecorderNavigator("trail-v1", NavStatus.ARRIVED);
        RecorderNavigator v2 = new RecorderNavigator("worldmap-v2", NavStatus.RUNNING, NavStatus.ARRIVED);
        HybridNavigator hybrid = new HybridNavigator(v1, v2, () -> NavigatorMode.V2_STRICT);

        assertEquals(NavStatus.RUNNING, hybrid.tick(req()));
        assertEquals(NavStatus.ARRIVED, hybrid.tick(req()));
        assertEquals(2, v2.tickCount);
        assertEquals("strict mode never calls V1 even on success", 0, v1.tickCount);
    }

    @Test
    public void v2Strict_v2Failed_returnsFailed_andDoesNotFallBack() throws Exception
    {
        // Empty worldmap → V2 returns FAILED. Strict mode must propagate
        // FAILED without ever calling V1.
        RecorderNavigator v1 = new RecorderNavigator("trail-v1", NavStatus.ARRIVED);
        RecorderNavigator v2 = new RecorderNavigator("worldmap-v2", NavStatus.FAILED);
        HybridNavigator hybrid = new HybridNavigator(v1, v2, () -> NavigatorMode.V2_STRICT);

        NavStatus s = hybrid.tick(req());
        assertEquals(NavStatus.FAILED, s);
        assertEquals(1, v2.tickCount);
        assertEquals("V2_STRICT must NOT fall back on V2 failure", 0, v1.tickCount);
    }

    @Test
    public void v2Strict_v2Null_returnsFailed_cleanly() throws Exception
    {
        RecorderNavigator v1 = new RecorderNavigator("trail-v1", NavStatus.ARRIVED);
        HybridNavigator hybrid = new HybridNavigator(v1, null, () -> NavigatorMode.V2_STRICT);

        NavStatus s = hybrid.tick(req());
        assertEquals("V2 unavailable in strict mode → FAILED, no fallback",
            NavStatus.FAILED, s);
        assertEquals(0, v1.tickCount);
    }

    @Test
    public void v2WithV1Fallback_v2Succeeds_v1NotInvoked() throws Exception
    {
        RecorderNavigator v1 = new RecorderNavigator("trail-v1", NavStatus.ARRIVED);
        RecorderNavigator v2 = new RecorderNavigator("worldmap-v2",
            NavStatus.RUNNING, NavStatus.ARRIVED);
        HybridNavigator hybrid = new HybridNavigator(v1, v2, () -> NavigatorMode.V2_WITH_V1_FALLBACK);

        assertEquals(NavStatus.RUNNING, hybrid.tick(req()));
        assertEquals(NavStatus.ARRIVED, hybrid.tick(req()));
        assertEquals(2, v2.tickCount);
        assertEquals("V2 succeeded — V1 must not be called", 0, v1.tickCount);
    }

    @Test
    public void v2WithV1Fallback_v2Failed_fallsBackToV1ForSameRequest() throws Exception
    {
        // Empty worldmap: V2 returns FAILED on the first tick. The hybrid
        // must hand the SAME request to V1 in the same tick and return
        // V1's status.
        RecorderNavigator v1 = new RecorderNavigator("trail-v1", NavStatus.RUNNING);
        RecorderNavigator v2 = new RecorderNavigator("worldmap-v2", NavStatus.FAILED);
        HybridNavigator hybrid = new HybridNavigator(v1, v2, () -> NavigatorMode.V2_WITH_V1_FALLBACK);

        NavStatus s = hybrid.tick(req());
        assertEquals("Hybrid must surface V1's RUNNING after V2 fallback",
            NavStatus.RUNNING, s);
        assertEquals(1, v2.tickCount);
        assertEquals("V1 must run once during fallback in the same tick", 1, v1.tickCount);
        assertTrue("V2 must be cancelled when fallback engages (>= 1 cancel call)",
            v2.cancelCount >= 1);
    }

    @Test
    public void v2WithV1Fallback_stickyOnceFallenBack_forSameRequest() throws Exception
    {
        // After fallback engages, subsequent ticks for the SAME request
        // must keep going to V1 — re-trying V2 mid-walk would re-plan
        // and double-issue clicks.
        RecorderNavigator v1 = new RecorderNavigator("trail-v1",
            NavStatus.RUNNING, NavStatus.RUNNING, NavStatus.ARRIVED);
        RecorderNavigator v2 = new RecorderNavigator("worldmap-v2", NavStatus.FAILED);
        HybridNavigator hybrid = new HybridNavigator(v1, v2, () -> NavigatorMode.V2_WITH_V1_FALLBACK);

        NavRequest r = req();
        hybrid.tick(r);   // V2 FAILED → V1 RUNNING
        hybrid.tick(r);   // sticky V1 → RUNNING
        NavStatus s = hybrid.tick(r);   // sticky V1 → ARRIVED

        assertEquals(NavStatus.ARRIVED, s);
        assertEquals("V2 ticked exactly once — fallback is sticky", 1, v2.tickCount);
        assertEquals(3, v1.tickCount);
    }

    @Test
    public void v2WithV1Fallback_v2Null_silentlyDelegatesToV1() throws Exception
    {
        RecorderNavigator v1 = new RecorderNavigator("trail-v1", NavStatus.RUNNING, NavStatus.ARRIVED);
        HybridNavigator hybrid = new HybridNavigator(v1, null, () -> NavigatorMode.V2_WITH_V1_FALLBACK);

        assertEquals(NavStatus.RUNNING, hybrid.tick(req()));
        assertEquals(NavStatus.ARRIVED, hybrid.tick(req()));
        assertEquals(2, v1.tickCount);
    }

    @Test
    public void newRequest_resetsFallbackFlag_v2TriedFromScratch() throws Exception
    {
        // Once a different request comes in, V2 should be tried again —
        // the sticky flag is per-request, not session-wide.
        RecorderNavigator v1 = new RecorderNavigator("trail-v1", NavStatus.RUNNING);
        RecorderNavigator v2 = new RecorderNavigator("worldmap-v2",
            NavStatus.FAILED,                  // first request — fail
            NavStatus.RUNNING                  // second request — succeed
        );
        HybridNavigator hybrid = new HybridNavigator(v1, v2, () -> NavigatorMode.V2_WITH_V1_FALLBACK);

        NavRequest first = NavRequest.compose("trail-a", new WorldPoint(3208, 3217, 0),
            BehaviorMode.VARIED);
        NavRequest second = NavRequest.compose("trail-b", new WorldPoint(3220, 3260, 0),
            BehaviorMode.VARIED);

        hybrid.tick(first);    // V2 FAILED → fallback → V1 RUNNING
        hybrid.tick(second);   // new request — try V2 again

        assertEquals(2, v2.tickCount);
        assertEquals("V1 ran only for the first request's fallback",
            1, v1.tickCount);
    }

    @Test
    public void modeChangeBetweenTicks_resetsActiveHandler() throws Exception
    {
        AtomicReference<NavigatorMode> mode = new AtomicReference<>(NavigatorMode.V2_WITH_V1_FALLBACK);
        RecorderNavigator v1 = new RecorderNavigator("trail-v1", NavStatus.RUNNING);
        RecorderNavigator v2 = new RecorderNavigator("worldmap-v2", NavStatus.RUNNING);
        HybridNavigator hybrid = new HybridNavigator(v1, v2, mode::get);

        hybrid.tick(req());   // V2 RUNNING (mode = fallback, V2 succeeds)
        assertEquals(1, v2.tickCount);
        assertEquals(0, v1.tickCount);

        // User flips the panel to V1_ONLY mid-walk. Hybrid must reset
        // and route to V1 on the next tick.
        mode.set(NavigatorMode.V1_ONLY);
        hybrid.tick(req());
        assertEquals("mode change must reroute to V1", 1, v1.tickCount);
    }

    @Test
    public void cancel_cancelsBothImpls_andClearsActiveState() throws Exception
    {
        RecorderNavigator v1 = new RecorderNavigator("trail-v1", NavStatus.RUNNING);
        RecorderNavigator v2 = new RecorderNavigator("worldmap-v2", NavStatus.RUNNING);
        HybridNavigator hybrid = new HybridNavigator(v1, v2, () -> NavigatorMode.V2_WITH_V1_FALLBACK);

        hybrid.tick(req());
        hybrid.cancel();

        assertTrue("V1 must be cancelled", v1.cancelCount >= 1);
        assertTrue("V2 must be cancelled", v2.cancelCount >= 1);
        assertFalse("after cancel, hybrid is not busy", hybrid.isBusy());
    }

    @Test
    public void name_returnsHybrid()
    {
        RecorderNavigator v1 = new RecorderNavigator("trail-v1");
        HybridNavigator hybrid = new HybridNavigator(v1, null, () -> NavigatorMode.V1_ONLY);
        assertEquals("hybrid", hybrid.name());
    }

    @Test
    public void nullRequest_returnsFailed_cleanly() throws Exception
    {
        RecorderNavigator v1 = new RecorderNavigator("trail-v1");
        HybridNavigator hybrid = new HybridNavigator(v1, null, () -> NavigatorMode.V2_WITH_V1_FALLBACK);
        assertEquals(NavStatus.FAILED, hybrid.tick(null));
        assertEquals("null request must not reach the impl", 0, v1.tickCount);
    }

    @Test
    public void modeSupplierThrows_defaultsToV1Only()  throws Exception
    {
        RecorderNavigator v1 = new RecorderNavigator("trail-v1", NavStatus.ARRIVED);
        RecorderNavigator v2 = new RecorderNavigator("worldmap-v2");
        HybridNavigator hybrid = new HybridNavigator(v1, v2, () -> {
            throw new RuntimeException("config bad");
        });

        assertEquals(NavStatus.ARRIVED, hybrid.tick(req()));
        assertEquals(1, v1.tickCount);
        assertEquals("on supplier failure, default V1_ONLY — V2 not consulted",
            0, v2.tickCount);
    }
}
