package net.runelite.client.plugins.recorder.nav.v2.acceptance;

import java.io.File;
import java.util.Optional;
import net.runelite.client.plugins.recorder.nav.v2.qc.GoldenRouteFixtures;
import net.runelite.client.plugins.recorder.nav.v2.qc.LiveAcceptanceChecklist;
import net.runelite.client.plugins.recorder.nav.v2.qc.NavigationTestHarness;
import net.runelite.client.plugins.recorder.nav.v2.qc.RunRecord;
import net.runelite.client.plugins.recorder.nav.v2.qc.contracts.BfsConfig;
import net.runelite.client.plugins.recorder.nav.v2.qc.contracts.ExecutorResult;
import org.junit.Assume;
import org.junit.Test;
import static org.junit.Assert.assertTrue;

/** Acceptance Test 8 — bank↔pen regression. Spec §6:
 *  <pre>
 *    Setup: the existing problematic route; V2BankPenLiveDataTest
 *           un-skipped + hardened.
 *    Expected: 10 consecutive successful cycles; no wrong-object
 *              interaction; no stuck loop; no repeated failure point.
 *    Pass: 10/10 cycles; no manual intervention; every cycle produces
 *          a debug trace; no untyped failure.
 *  </pre>
 *
 *  <p>Manual gate: PARTIAL. The legacy {@code V2BankPenLiveDataTest}
 *  is gated by {@code Assume} on {@code ~/.runelite/recorder/worldmap/}
 *  presence; this acceptance test un-skips that file by wrapping it
 *  in a 10-cycle loop driven by {@link LiveAcceptanceChecklist}. On
 *  CI machines without the recorder data the {@link Assume} skips
 *  the test (no false-positive failures). On a developer workstation
 *  with the data, all 10 cycles must complete.
 *
 *  <p>Per master plan Phase 3: this is the final merge gate before
 *  Phase 4 default-flip. Lane-6 manifest records the cycle results;
 *  10/10 is GO, anything else is NO-GO and the responsible lane
 *  (typically Lane 4 planner or Lane 5 executor) is surfaced. */
public class BankPenRegressionTest
{
    private static final File WORLDMAP_ROOT = new File(
        System.getProperty("user.home") + "/.runelite/recorder/worldmap");

    private static final int REQUIRED_CYCLES = 10;

    @Test
    public void test8_bankPen_tenConsecutiveSuccessfulCycles()
    {
        // Phase-1 / CI skip: live recorded data not present.
        Assume.assumeTrue(
            "Test 8 (bank↔pen regression) requires "
            + WORLDMAP_ROOT.getPath() + " to be populated by a real "
            + "recorder session. CI machines skip cleanly. Manual "
            + "gate: developer runs Phase 3 with the data present.",
            WORLDMAP_ROOT.isDirectory());

        // Phase-1 / no-binding skip: Lane 4 + Lane 5 haven't wired.
        Assume.assumeTrue(
            "Test 8 requires Lane 4 (planner) + Lane 5 (executor) "
            + "integration. Awaiting "
            + "NavigationTestHarness.wirePlannerExecutor(...).",
            harnessWired());

        Optional<GoldenRouteFixtures.Scenario> maybeScenario =
            GoldenRouteFixtures.bankPenStart();
        Assume.assumeTrue("bankPenStart() returned empty — recorded "
            + "data may be malformed", maybeScenario.isPresent());
        var scenario = maybeScenario.get();

        LiveAcceptanceChecklist checklist =
            new LiveAcceptanceChecklist("bank↔pen 10-cycle", REQUIRED_CYCLES);

        for (int i = 0; i < REQUIRED_CYCLES; i++)
        {
            RunRecord rec;
            try
            {
                rec = NavigationTestHarness.runRoute(
                    scenario.request(), scenario.snapshot(), scenario.player(),
                    BfsConfig.defaults());
            }
            catch (UnsupportedOperationException ex)
            {
                throw new AssertionError("harness binding disappeared "
                    + "mid-test on cycle " + (i + 1), ex);
            }

            String traceId = rec.ticks().isEmpty()
                ? "no-ticks-" + i
                : rec.ticks().get(rec.ticks().size() - 1).debugTraceId();

            if (rec.succeeded())
            {
                checklist.recordCycle(
                    LiveAcceptanceChecklist.CycleResult.pass(
                        ExecutorResult.PATH_COMPLETED, traceId));
            }
            else
            {
                ExecutorResult finalRes = rec.ticks().isEmpty()
                    ? ExecutorResult.FAILED
                    : rec.ticks().get(rec.ticks().size() - 1).result();
                checklist.recordCycle(
                    LiveAcceptanceChecklist.CycleResult.fail(
                        finalRes, rec.failureReason(),
                        "cycle " + (i + 1) + " did not complete the route",
                        traceId));
                // Spec §6 Test 8 says "no repeated failure point" —
                // stop at first failure so the manifest sees the
                // exact cycle that broke. Otherwise a perpetual fail
                // would emit 10 identical bad rows.
                break;
            }
        }

        assertTrue("bank↔pen regression: required "
            + REQUIRED_CYCLES + " successful cycles, got "
            + checklist.successfulCycles() + "/" + checklist.completedCycles()
            + ". Manifest excerpt:\n" + checklist.toMarkdown(),
            checklist.allRequiredCyclesPassed());
    }

    private static boolean harnessWired()
    {
        try { NavigationTestHarness.runRoute(null, null, null, null); return true; }
        catch (UnsupportedOperationException ex) { return false; }
        catch (Throwable t) { return true; }
    }
}
