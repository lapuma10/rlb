package net.runelite.client.plugins.recorder.nav.v2.qc;

import java.util.Optional;
import net.runelite.client.plugins.recorder.nav.v2.qc.contracts.ExecutorResult;
import net.runelite.client.plugins.recorder.nav.v2.qc.contracts.ReplanReason;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Self-test for LiveAcceptanceChecklist — Test-8 driver. */
public class LiveAcceptanceChecklistTest
{
    @Test
    public void tenPassesMeetGate()
    {
        var c = new LiveAcceptanceChecklist("bank↔pen 10", 10);
        for (int i = 0; i < 10; i++)
            c.recordCycle(LiveAcceptanceChecklist.CycleResult.pass(
                ExecutorResult.PATH_COMPLETED, "t-" + i));
        assertTrue("10/10 passes must clear the gate",
            c.allRequiredCyclesPassed());
        assertEquals(10, c.successfulCycles());
    }

    @Test
    public void singleFailFailsGate()
    {
        var c = new LiveAcceptanceChecklist("bank↔pen 10", 10);
        for (int i = 0; i < 9; i++)
            c.recordCycle(LiveAcceptanceChecklist.CycleResult.pass(
                ExecutorResult.PATH_COMPLETED, "t-" + i));
        c.recordCycle(LiveAcceptanceChecklist.CycleResult.fail(
            ExecutorResult.NEEDS_REPLAN,
            Optional.of(ReplanReason.NO_LOCAL_WALKABLE_TILE),
            "blocked at gate", "t-9"));
        assertFalse("any failure must fail the gate",
            c.allRequiredCyclesPassed());
        assertEquals(9, c.successfulCycles());
    }

    @Test
    public void incompleteCyclesFailGate()
    {
        var c = new LiveAcceptanceChecklist("bank↔pen 10", 10);
        for (int i = 0; i < 5; i++)
            c.recordCycle(LiveAcceptanceChecklist.CycleResult.pass(
                ExecutorResult.PATH_COMPLETED, "t-" + i));
        assertFalse("5/10 must fail the gate", c.allRequiredCyclesPassed());
    }

    @Test
    public void markdownSummaryIncludesPerCycleRows()
    {
        var c = new LiveAcceptanceChecklist("bank↔pen 10", 2);
        c.recordCycle(LiveAcceptanceChecklist.CycleResult.pass(
            ExecutorResult.PATH_COMPLETED, "t-0"));
        c.recordCycle(LiveAcceptanceChecklist.CycleResult.fail(
            ExecutorResult.STUCK, Optional.empty(),
            "stuck on gate", "t-1"));
        String md = c.toMarkdown();
        assertTrue("markdown must list passing cycle 1", md.contains("| 1 | PASS"));
        assertTrue("markdown must list failing cycle 2", md.contains("| 2 | FAIL"));
        assertTrue("markdown must include failure reason text",
            md.contains("stuck on gate"));
    }
}
