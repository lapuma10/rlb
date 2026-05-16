package net.runelite.client.plugins.recorder.nav.v2.qc;

import java.util.List;
import java.util.Optional;
import net.runelite.client.plugins.recorder.nav.v2.qc.contracts.ExecutorTickResult;
import net.runelite.client.plugins.recorder.nav.v2.qc.contracts.ReplanReason;
import net.runelite.client.plugins.recorder.nav.v2.qc.contracts.V2Path;

/** End-to-end record of one harness run. Acceptance tests assert
 *  against this. Field semantics match the Lane-6 plan §Task-1:
 *  <ul>
 *    <li>{@code planned} — the {@code V2Path} the planner emitted (null
 *        if planning failed before producing one).</li>
 *    <li>{@code ticks} — every {@code ExecutorTickResult} the executor
 *        emitted, in order.</li>
 *    <li>{@code traces} — JSONL-shaped per-tick log entries. Correlated
 *        with {@code ticks} by {@link ExecutorTickResult#debugTraceId()}.</li>
 *    <li>{@code failureReason} — populated iff the run aborted with a
 *        typed replan reason that exceeded the navigator's budget.</li>
 *    <li>{@code totalMs} — wall-clock duration of the run.</li>
 *  </ul>
 */
public record RunRecord(V2Path planned,
                        List<ExecutorTickResult> ticks,
                        List<RouteTrace> traces,
                        Optional<ReplanReason> failureReason,
                        long totalMs)
{
    /** True if the executor produced at least one tick AND the final
     *  tick's result was {@code PATH_COMPLETED}. */
    public boolean succeeded()
    {
        if (ticks.isEmpty()) return false;
        ExecutorTickResult last = ticks.get(ticks.size() - 1);
        return last.result()
            == net.runelite.client.plugins.recorder.nav.v2.qc.contracts.ExecutorResult.PATH_COMPLETED;
    }

    /** True if any tick reports {@code NEEDS_REPLAN}. Acceptance Test 4
     *  (single-tile blocker → sidestep, NOT replan) asserts this is
     *  false; Test 5 (corridor blocked → typed replan) asserts true. */
    public boolean anyReplanRequested()
    {
        for (ExecutorTickResult t : ticks)
            if (t.result()
                == net.runelite.client.plugins.recorder.nav.v2.qc.contracts.ExecutorResult.NEEDS_REPLAN)
                return true;
        return false;
    }
}
