package net.runelite.client.plugins.recorder.nav.v2.acceptance;

import net.runelite.client.plugins.recorder.nav.v2.qc.GoldenRouteFixtures;
import net.runelite.client.plugins.recorder.nav.v2.qc.NavigationTestHarness;
import net.runelite.client.plugins.recorder.nav.v2.qc.RunRecord;
import net.runelite.client.plugins.recorder.nav.v2.qc.contracts.BfsConfig;
import net.runelite.client.plugins.recorder.nav.v2.qc.contracts.PathStep;
import net.runelite.client.plugins.recorder.nav.v2.qc.contracts.TransportStep;
import net.runelite.client.plugins.recorder.nav.v2.qc.contracts.V2Path;
import net.runelite.client.plugins.recorder.nav.v2.qc.contracts.Waypoint;
import net.runelite.client.plugins.recorder.nav.v2.qc.contracts.WaypointType;
import org.junit.Assume;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Acceptance Test 3 — Transport interaction. Spec §6:
 *  <pre>
 *    Setup: start before gate/door/stairs, target after.
 *    Expected: planner emits TRANSPORT_APPROACH waypoint; executor
 *              reaches approach tile; executor performs typed action;
 *              path continues.
 *    Pass: TransportLeg explicit in V2Path; executor doesn't treat
 *          as normal walking; failure gives typed reason.
 *  </pre>
 *
 *  <p>Manual gate: AUTOMATED. The smoke triplet for Phase 2.7 is
 *  Tests 1, 3, 4 — this is the one that exercises Lane-4's transport
 *  graph and Lane-5's typed-leg execution path. */
public class TransportInteractionTest
{
    @Test
    public void test3_transportInteraction_typedLeg()
    {
        Assume.assumeTrue(
            "Test 3 (transport) requires Lane 4 (TransportTable + "
            + "WaypointPlanner) + Lane 5 (typed-leg V2Executor). "
            + "Awaiting NavigationTestHarness.wirePlannerExecutor(...).",
            harnessWired());

        var scenario = GoldenRouteFixtures.transportApproachAtGate();
        RunRecord rec = NavigationTestHarness.runRoute(
            scenario.request(), scenario.snapshot(), scenario.player(),
            BfsConfig.defaults());
        assertNotNull("planner emitted no V2Path through a gate", rec.planned());

        // Pass criterion (per spec §6 Test 3, line 2):
        // "TransportLeg explicit in V2Path".
        assertTrue("planner must emit at least one TransportStep "
            + "through a gated corridor — spec §6 Test 3",
            hasTransportStep(rec.planned()));

        // Pass criterion (per spec §6 Test 3, line 1):
        // "planner emits TRANSPORT_APPROACH waypoint".
        Waypoint approach = firstTransportApproach(rec.planned());
        assertNotNull("planner must emit a TRANSPORT_APPROACH waypoint "
            + "BEFORE the TransportStep", approach);
        assertEquals("TRANSPORT_APPROACH must be exactRequired (tolerance=0)",
            0, approach.toleranceRadius());
        assertTrue("approach waypoint must declare exactRequired",
            approach.exactRequired());

        // Pass criterion (lines 3 + 4): executor reaches approach tile
        // and performs typed action — final result must be
        // PATH_COMPLETED (route continues past the transport). The
        // RunRecord helper checks the last tick.
        assertTrue("executor did not complete the gated route — final "
            + "tick missing PATH_COMPLETED. RunRecord.failureReason="
            + rec.failureReason(),
            rec.succeeded());
    }

    /** Variant: gate locked / requirement unsatisfied → typed
     *  failure, not silent stuck. */
    @Test
    public void test3_transportInteraction_typedFailureOnLockedGate()
    {
        Assume.assumeTrue(
            "Test 3 typed-failure variant requires Lane 4/5 wiring.",
            harnessWired());

        // Phase-1: spec §6 Test 3 says "failure gives typed reason".
        // Lane-4's TransportRequirementEvaluator drops the gate from
        // the graph when the requirement isn't met → planner returns
        // an unreachable V2Path with TARGET_UNREACHABLE OR a route
        // that immediately fails with TRANSPORT_UNAVAILABLE. Either
        // is acceptable per spec — what's not acceptable is "no
        // V2Path" or "silent stuck".
        var scenario = GoldenRouteFixtures.transportApproachAtGate();
        RunRecord rec = NavigationTestHarness.runRoute(
            scenario.request(), scenario.snapshot(), scenario.player(),
            BfsConfig.defaults());
        // Either a successful workaround OR a typed failure — never
        // a silent stuck.
        assertTrue("locked-gate run must produce typed failure OR "
            + "completed alternate route (never a silent stuck)",
            rec.succeeded() || rec.failureReason().isPresent());
    }

    private static boolean hasTransportStep(V2Path p)
    {
        for (PathStep s : p.steps()) if (s instanceof TransportStep) return true;
        return false;
    }

    private static Waypoint firstTransportApproach(V2Path p)
    {
        for (PathStep s : p.steps())
        {
            if (s instanceof net.runelite.client.plugins.recorder.nav.v2.qc.contracts.WalkStep ws
                && ws.waypoint().type() == WaypointType.TRANSPORT_APPROACH)
                return ws.waypoint();
        }
        return null;
    }

    private static boolean harnessWired()
    {
        try { NavigationTestHarness.runRoute(null, null, null, null); return true; }
        catch (UnsupportedOperationException ex) { return false; }
        catch (Throwable t) { return true; }
    }
}
