package net.runelite.client.plugins.recorder.nav.v2.acceptance;

import net.runelite.client.plugins.recorder.nav.v2.qc.GoldenRouteFixtures;
import net.runelite.client.plugins.recorder.nav.v2.qc.NavigationTestHarness;
import net.runelite.client.plugins.recorder.nav.v2.qc.RouteTrace;
import net.runelite.client.plugins.recorder.nav.v2.qc.RunRecord;
import net.runelite.client.plugins.recorder.nav.v2.qc.contracts.BfsConfig;
import org.junit.Assume;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Acceptance Test 4 — Single-tile blocker (sidestep). Spec §6:
 *  <pre>
 *    Setup: place dynamic blocker on next ideal walking tile.
 *    Expected: planner path remains valid; executor chooses nearby
 *              valid tile; no full replan.
 *    Pass: executor logs sidestep=true; result is WAYPOINT_REACHED,
 *          not NEEDS_REPLAN; progress continues.
 *  </pre>
 *
 *  <p>Manual gate: AUTOMATED. Part of the Phase-2.7 smoke triplet
 *  with Tests 1 and 3. Lane 5's {@code SidestepResolver} is the unit
 *  under test here — this case differentiates "sidestep within
 *  tolerance" from "full replan". */
public class SingleTileBlockerTest
{
    @Test
    public void test4_singleTileBlocker_sidestepNotReplan()
    {
        Assume.assumeTrue(
            "Test 4 (single-tile blocker) requires Lane 4 (planner) + "
            + "Lane 5 (SidestepResolver) integration. Awaiting "
            + "NavigationTestHarness.wirePlannerExecutor(...).",
            harnessWired());

        var scenario = GoldenRouteFixtures.straightCorridorWithBlocker();
        RunRecord rec = NavigationTestHarness.runRoute(
            scenario.request(), scenario.snapshot(), scenario.player(),
            BfsConfig.defaults());
        assertNotNull("planner emitted no V2Path with one blocker", rec.planned());

        // Pass criterion (line 1): executor logs sidestep=true on at
        // least one tick.
        boolean anySidestep = false;
        for (RouteTrace t : rec.traces())
        {
            if (t.sidestepUsed) { anySidestep = true; break; }
        }
        assertTrue("executor must record sidestep=true for at least one "
            + "tick when a single-tile blocker is in the corridor — "
            + "spec §6 Test 4 pass criterion line 1", anySidestep);

        // Pass criterion (line 2): no NEEDS_REPLAN emitted.
        assertFalse("executor must NOT request replan for a single-tile "
            + "blocker — that's what sidestep is for. "
            + "Found a NEEDS_REPLAN tick in the run.",
            rec.anyReplanRequested());

        // Pass criterion (line 3): progress continues — final result
        // PATH_COMPLETED.
        assertTrue("single-tile blocker run did not complete the path "
            + "(progress did not continue). RunRecord.failureReason="
            + rec.failureReason(), rec.succeeded());
    }

    private static boolean harnessWired()
    {
        try { NavigationTestHarness.runRoute(null, null, null, null); return true; }
        catch (UnsupportedOperationException ex) { return false; }
        catch (Throwable t) { return true; }
    }
}
