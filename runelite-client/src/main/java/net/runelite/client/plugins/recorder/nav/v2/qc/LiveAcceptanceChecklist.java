package net.runelite.client.plugins.recorder.nav.v2.qc;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.runelite.client.plugins.recorder.nav.v2.qc.contracts.ExecutorResult;
import net.runelite.client.plugins.recorder.nav.v2.qc.contracts.ReplanReason;

/** Driver class for *manual* live runs (Phase 4 of master plan dispatch
 *  order). Phase-1 surface: a YAML-shaped checklist isn't necessary
 *  yet; the checklist is built up programmatically by the manual-gate
 *  acceptance tests (Test 2 cross-region; Test 8 bank↔pen 10-cycle).
 *
 *  <p>For Test 8 (bank↔pen 10 cycles) this is the runner: it drives
 *  {@link NavigationTestHarness#runRouteLive(net.runelite.client.plugins.recorder.nav.NavRequest, Object, Object)}
 *  cycle-by-cycle and tracks per-cycle pass/fail.
 *
 *  <p>Per spec memory {@code feedback_no_evasion_framing}: framing is
 *  route ROBUSTNESS, never detection-evasion. Manual gates exist
 *  because a real bot session is required for cross-region routing
 *  (Test 2) and 10-cycle regression (Test 8), not because the engine
 *  is mimicking a human. */
public final class LiveAcceptanceChecklist
{
    private final String name;
    private final int requiredSuccessfulCycles;
    private final List<CycleResult> cycles = new ArrayList<>();

    public LiveAcceptanceChecklist(String name, int requiredSuccessfulCycles)
    {
        this.name = name;
        this.requiredSuccessfulCycles = requiredSuccessfulCycles;
    }

    public String name() { return name; }

    public int requiredSuccessfulCycles() { return requiredSuccessfulCycles; }

    public int completedCycles() { return cycles.size(); }

    public int successfulCycles()
    {
        int n = 0;
        for (CycleResult c : cycles) if (c.passed) n++;
        return n;
    }

    /** True iff every cycle attempted so far passed AND the count meets
     *  the requirement. Final merge gate for Test 8. */
    public boolean allRequiredCyclesPassed()
    {
        return cycles.size() >= requiredSuccessfulCycles
            && successfulCycles() == requiredSuccessfulCycles;
    }

    /** Append a completed cycle. */
    public void recordCycle(CycleResult c) { cycles.add(c); }

    public List<CycleResult> cycles() { return List.copyOf(cycles); }

    /** Build a markdown summary suitable for {@code lane6-manifest.md}. */
    public String toMarkdown()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("### ").append(name).append("\n\n");
        sb.append("Required cycles: ").append(requiredSuccessfulCycles).append("\n");
        sb.append("Completed: ").append(cycles.size()).append("\n");
        sb.append("Passed: ").append(successfulCycles()).append("\n\n");
        sb.append("| # | Result | Reason | Trace ID |\n");
        sb.append("|---|--------|--------|----------|\n");
        for (int i = 0; i < cycles.size(); i++)
        {
            CycleResult c = cycles.get(i);
            sb.append("| ").append(i + 1).append(" | ")
              .append(c.passed ? "PASS" : "FAIL").append(" | ")
              .append(c.reason == null ? "" : c.reason).append(" | ")
              .append(c.traceId == null ? "" : c.traceId).append(" |\n");
        }
        return sb.toString();
    }

    /** Cycle outcome. Pass criterion is decided by the calling test:
     *  Test 8 requires {@code finalResult == PATH_COMPLETED} AND no
     *  manual intervention; Test 2 requires the same plus geographic
     *  evidence (debug log mentions destination region). */
    public static final class CycleResult
    {
        public final boolean passed;
        public final ExecutorResult finalResult;
        public final Optional<ReplanReason> failureReason;
        public final String reason;
        public final String traceId;

        public CycleResult(boolean passed, ExecutorResult finalResult,
                           Optional<ReplanReason> failureReason,
                           String reason, String traceId)
        {
            this.passed = passed;
            this.finalResult = finalResult;
            this.failureReason = failureReason;
            this.reason = reason;
            this.traceId = traceId;
        }

        public static CycleResult pass(ExecutorResult finalResult, String traceId)
        {
            return new CycleResult(true, finalResult, Optional.empty(), "", traceId);
        }

        public static CycleResult fail(ExecutorResult finalResult,
                                       Optional<ReplanReason> failureReason,
                                       String reason, String traceId)
        {
            return new CycleResult(false, finalResult, failureReason, reason, traceId);
        }
    }
}
