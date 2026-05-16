package net.runelite.client.plugins.recorder.nav.v2.acceptance;

import net.runelite.api.coords.WorldPoint;
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

/** Acceptance Test 6 — Predicate denies tile. Spec §6:
 *  <pre>
 *    Setup: script marks tile X as disallowed via addTileCondition.
 *    Expected: BFS avoids X; executor also refuses X.
 *    Pass: tile X never in final accepted route; if no route exists
 *          without X, planner returns TARGET_UNREACHABLE.
 *  </pre>
 *
 *  <p>Manual gate: AUTOMATED. Exercises Lane 2 (PredicateRegistry) +
 *  Lane 3 (BFS predicate evaluation) + Lane 5 (executor predicate
 *  check) together — a predicate that denies tile X must be honored
 *  at BOTH planning time AND tile-pick time. */
public class PredicateTest
{
    /** Tile we declare disallowed for this run. */
    private static final WorldPoint DENIED = new WorldPoint(3225, 3200, 0);

    @Test
    public void test6_predicateDeniedTile_neverInRouteNeverPicked()
    {
        Assume.assumeTrue(
            "Test 6 (predicate) requires Lane 2 (PredicateRegistry) + "
            + "Lane 3 (BFS predicate eval) + Lane 5 (executor "
            + "predicate check). Awaiting "
            + "NavigationTestHarness.wirePlannerExecutor(...).",
            harnessWired());

        var scenario = GoldenRouteFixtures.corridorWithDeniedTile(DENIED);
        RunRecord rec = NavigationTestHarness.runRoute(
            scenario.request(), scenario.snapshot(), scenario.player(),
            BfsConfig.defaults());

        // Pass criterion (line 1): tile X not in the final accepted
        // V2Path. Two cases: (a) planner found an alternate route
        // around X — assert pathDoesNotContain(X); (b) planner
        // returned TARGET_UNREACHABLE — assert null path OR explicit
        // failure reason.
        if (rec.planned() == null)
        {
            // Case (b): planner explicitly returned unreachable. Acceptable
            // per spec §6 Test 6 pass criterion line 2.
            assertTrue("planner returned no V2Path but no failure reason "
                + "either — must surface TARGET_UNREACHABLE",
                rec.failureReason().isPresent());
            return;
        }

        // Case (a): planner found an alternate route. X must NOT be
        // in the planned V2Path waypoints.
        assertFalse("BFS path must not contain the denied tile X="
            + DENIED + " — spec §6 Test 6",
            GoldenRouteFixtures.pathContainsTile(rec.planned(), DENIED));

        // Pass criterion (line 1, second clause): executor never PICKS
        // X. Walk the trace; assert no candidateChosen == X.
        for (RouteTrace t : rec.traces())
        {
            if (t.candidateChosen.isPresent()
                && DENIED.equals(t.candidateChosen.get()))
            {
                throw new AssertionError("executor PICKED the denied "
                    + "tile X=" + DENIED + " at tick " + t.tickId
                    + " — Lane-5 must consult predicates at tile-pick "
                    + "time too (spec §3 TilePredicate doc)");
            }
        }

        // Sanity: planner emitted something usable.
        assertNotNull(rec.planned());
    }

    private static boolean harnessWired()
    {
        try { NavigationTestHarness.runRoute(null, null, null, null); return true; }
        catch (UnsupportedOperationException ex) { return false; }
        catch (Throwable t) { return true; }
    }
}
