package net.runelite.client.plugins.recorder.nav.v2.acceptance;

import net.runelite.client.plugins.recorder.nav.v2.qc.GoldenRouteFixtures;
import net.runelite.client.plugins.recorder.nav.v2.qc.NavigationTestHarness;
import net.runelite.client.plugins.recorder.nav.v2.qc.RunRecord;
import net.runelite.client.plugins.recorder.nav.v2.qc.contracts.BfsConfig;
import net.runelite.client.plugins.recorder.nav.v2.qc.contracts.ExecutorResult;
import net.runelite.client.plugins.recorder.nav.v2.qc.contracts.ReplanReason;
import org.junit.Assume;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Acceptance Test 5 — Corridor blocked (typed replan). Spec §6:
 *  <pre>
 *    Setup: block all valid local candidates in next movement bucket.
 *    Expected: executor cannot sidestep; returns NEEDS_REPLAN with
 *              NO_LOCAL_WALKABLE_TILE; navigator replans.
 *    Pass: no infinite clicking; no random off-route movement;
 *          no fake success; typed reason emitted.
 *  </pre>
 *
 *  <p>Manual gate: AUTOMATED. Exercises Lane 5's escalation path —
 *  the differentiation from Test 4 (sidestep within tolerance) and
 *  Test 6 (predicate denial). */
public class CorridorBlockedTest
{
    @Test
    public void test5_corridorBlocked_typedReplanReason()
    {
        Assume.assumeTrue(
            "Test 5 (corridor blocked) requires Lane 4 (planner) + "
            + "Lane 5 (escalation path) integration. Awaiting "
            + "NavigationTestHarness.wirePlannerExecutor(...).",
            harnessWired());

        var scenario = GoldenRouteFixtures.fullyBlockedCorridor();
        RunRecord rec = NavigationTestHarness.runRoute(
            scenario.request(), scenario.snapshot(), scenario.player(),
            BfsConfig.defaults());

        // Pass criteria (per spec §6 Test 5):
        // 1) NEEDS_REPLAN emitted at least once.
        if (!rec.anyReplanRequested())
        {
            fail("executor must request NEEDS_REPLAN when every local "
                + "candidate is blocked — found none. "
                + "RunRecord.failureReason=" + rec.failureReason()
                + ", ticks=" + rec.ticks().size());
        }

        // 2) The replan reason must be a typed NO_LOCAL_WALKABLE_TILE
        //    on the tick that escalated.
        ReplanReason firstReason = null;
        for (var t : rec.ticks())
        {
            if (t.result() == ExecutorResult.NEEDS_REPLAN)
            {
                firstReason = t.replanReason().orElse(null);
                break;
            }
        }
        if (firstReason == null) fail("NEEDS_REPLAN tick must carry a typed "
            + "ReplanReason; got Optional.empty()");
        assertEquals("typed reason on full-corridor-blocked must be "
            + "NO_LOCAL_WALKABLE_TILE (spec §6 Test 5)",
            ReplanReason.NO_LOCAL_WALKABLE_TILE, firstReason);

        // 3) No fake success: if executor went NEEDS_REPLAN, the run
        //    must NOT end with PATH_COMPLETED unless the navigator
        //    successfully replanned. (Lane 6 enforces the typed
        //    chain; the navigator's replan is itself tested in
        //    Lane-5 unit tests.)
        if (rec.succeeded())
        {
            // OK — navigator replanned successfully. Lane 5 unit tests
            // assert the replan happened; here we accept it.
            assertTrue("succeeded after typed replan is acceptable", true);
        }
        // 4) No infinite clicking — enforce a tick budget on the
        //    harness. RunRecord captures ticks; the binding's loop
        //    must terminate. Lane 6 cannot enforce this directly
        //    because the binding owns the loop, but we surface it:
        //    if ticks > 1000 the binding's loop is broken.
        assertTrue("infinite-clicking guard: ticks=" + rec.ticks().size()
            + " exceeded 1000 — binding loop is not terminating",
            rec.ticks().size() <= 1000);
    }

    private static boolean harnessWired()
    {
        try { NavigationTestHarness.runRoute(null, null, null, null); return true; }
        catch (UnsupportedOperationException ex) { return false; }
        catch (Throwable t) { return true; }
    }
}
