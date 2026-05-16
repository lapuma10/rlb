package net.runelite.client.plugins.recorder.nav.v2.acceptance;

import net.runelite.client.plugins.recorder.nav.v2.qc.GoldenRouteFixtures;
import net.runelite.client.plugins.recorder.nav.v2.qc.NavigationTestHarness;
import net.runelite.client.plugins.recorder.nav.v2.qc.RunRecord;
import net.runelite.client.plugins.recorder.nav.v2.qc.contracts.BfsConfig;
import net.runelite.client.plugins.recorder.nav.v2.qc.contracts.PathStep;
import net.runelite.client.plugins.recorder.nav.v2.qc.contracts.TransportStep;
import net.runelite.client.plugins.recorder.nav.v2.qc.contracts.V2Path;
import org.junit.Assume;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Acceptance Test 1 — Same-region walking. Spec §6:
 *  <pre>
 *    Setup: start tile A, target tile B, no transports needed.
 *    Expected: planner emits WALK waypoints only; BFS path validates;
 *              executor reaches target; no replan.
 *    Pass: 10/10 successful runs; no invalid collision steps;
 *          no exact-tile-replay requirement.
 *  </pre>
 *  <p>Phase-1 behavior: tests against the offline harness. When
 *  Lane-5 has not yet wired the planner+executor binding, the test
 *  throws {@link UnsupportedOperationException} on the first
 *  {@code runRoute(...)} call — Phase-1 deliverable.
 *  Once the binding is wired, every run must succeed.
 *
 *  <p>Manual gate: AUTOMATED (per spec §6 + master plan Phase 3). */
public class SameRegionRouteTest
{
    private static final int RUNS = 10;

    @Test
    public void test1_sameRegion_tenSuccessfulRuns()
    {
        // Phase-1: Skip cleanly when no binding wired — surfaces in the
        // Lane-6 manifest as "awaiting Lane 4/5". Once Lane 5 calls
        // NavigationTestHarness.wirePlannerExecutor(...), this test
        // executes the full 10 runs.
        Assume.assumeTrue(
            "Test 1 (same-region) requires Lane 4 (WaypointPlanner) "
            + "+ Lane 5 (V2Executor) integration. Awaiting "
            + "NavigationTestHarness.wirePlannerExecutor(...).",
            harnessWired());

        var scenario = GoldenRouteFixtures.straightCorridor();
        int successful = 0;
        for (int i = 0; i < RUNS; i++)
        {
            RunRecord rec = NavigationTestHarness.runRoute(
                scenario.request(), scenario.snapshot(), scenario.player(),
                BfsConfig.defaults());
            assertNotNull("planner emitted no V2Path on run " + i, rec.planned());
            assertWalkOnly(rec.planned(), i);
            assertFalse("run " + i + " requested replan on a clean corridor",
                rec.anyReplanRequested());
            assertTrue("run " + i + " did not reach target", rec.succeeded());
            successful++;
        }
        assertTrue("expected 10/10 successful runs, got " + successful + "/10",
            successful == RUNS);
    }

    /** Assert: every step is a {@code WalkStep}; no transport steps. */
    private static void assertWalkOnly(V2Path p, int run)
    {
        for (PathStep step : p.steps())
        {
            if (step instanceof TransportStep)
                throw new AssertionError("run " + run + ": planner emitted "
                    + "a TransportStep in a same-region corridor — spec §6 Test 1");
        }
    }

    /** Helper: introspect whether Lane-5 wired its binding without
     *  throwing. */
    private static boolean harnessWired()
    {
        try
        {
            NavigationTestHarness.runRoute(null, null, null, null);
            return true;
        }
        catch (UnsupportedOperationException ex) { return false; }
        catch (Throwable t) { return true; }
    }
}
