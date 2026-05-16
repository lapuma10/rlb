package net.runelite.client.plugins.recorder.nav.v2.qc;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.runelite.client.plugins.recorder.nav.NavRequest;
import net.runelite.client.plugins.recorder.nav.v2.qc.contracts.BfsConfig;
import net.runelite.client.plugins.recorder.nav.v2.qc.contracts.ExecutorTickResult;
import net.runelite.client.plugins.recorder.nav.v2.qc.contracts.PlayerState;
import net.runelite.client.plugins.recorder.nav.v2.qc.contracts.ReplanReason;
import net.runelite.client.plugins.recorder.nav.v2.qc.contracts.V2Path;
import net.runelite.client.plugins.recorder.nav.v2.qc.contracts.WorldSnapshot;

/** Programmatic harness for running WaypointPlanner + V2Executor +
 *  V2Navigator end-to-end against fixture inputs. Phase-1 surface only:
 *  this class is the *integration entry point* that every acceptance
 *  test calls. Lanes 2-5 wire their concrete implementations into the
 *  static factories below as they land; until then the runRoute(...)
 *  methods throw {@link UnsupportedOperationException} with a message
 *  pointing at the missing lane.
 *
 *  <p>Two run modes:
 *  <ul>
 *    <li>{@link #runRoute(NavRequest, WorldSnapshot, PlayerState, BfsConfig)}
 *        — offline; planner + simulated executor. Used by the seven
 *        automated acceptance tests.</li>
 *    <li>{@link #runRouteLive(NavRequest, Object, Object)}
 *        — live; connects to a real {@code Client} + {@code ClientThread}.
 *        Used by manual-gated Tests 2 (cross-region) and 8 (bank↔pen).</li>
 *  </ul>
 *
 *  <p>{@link RunRecord} carries everything the acceptance assertions
 *  need: the planned path, every executor tick, every emitted
 *  RouteTrace, the failure reason if any, and the total wall-clock
 *  duration. The shape is stable; Lane 5 / Lane 4 do NOT need to know
 *  about it. */
public final class NavigationTestHarness
{
    /** Set by Lane 5 wiring (via {@link #wirePlannerExecutor}) once
     *  Phase 2.7 smoke integration begins. Until then null —
     *  {@link #runRoute} throws. */
    private static volatile PlannerExecutorBinding BINDING;

    /** Set by Lane 5 wiring once a live {@code Client} can be threaded
     *  through. Until then null — {@link #runRouteLive} throws. */
    private static volatile LivePlannerExecutorBinding LIVE_BINDING;

    private NavigationTestHarness() {}

    /** Phase-2.7 hand-off: Lane 5 (or whichever lane wires the runtime)
     *  registers the planner+executor entry points here. Lane 6 calls
     *  {@code runRoute(...)} which delegates through this binding. */
    public static void wirePlannerExecutor(PlannerExecutorBinding b)
    {
        BINDING = b;
    }

    public static void wireLivePlannerExecutor(LivePlannerExecutorBinding b)
    {
        LIVE_BINDING = b;
    }

    /** Reset wiring between tests so an offline test does not see a
     *  stale live binding. */
    public static void resetWiring()
    {
        BINDING = null;
        LIVE_BINDING = null;
    }

    /** Run a route offline through the wired planner+executor. Throws
     *  {@link UnsupportedOperationException} until Lane 5 wires its
     *  binding. The exception message names the missing lane so the
     *  Lane-6 manifest can read it back. */
    public static RunRecord runRoute(NavRequest req,
                                     WorldSnapshot snap,
                                     PlayerState ps,
                                     BfsConfig cfg)
    {
        PlannerExecutorBinding b = BINDING;
        if (b == null)
        {
            throw new UnsupportedOperationException(
                "NavigationTestHarness: no PlannerExecutorBinding wired. "
                + "Awaiting Lane 4 (WaypointPlanner) + Lane 5 (V2Executor) "
                + "to call NavigationTestHarness.wirePlannerExecutor(...) at "
                + "Phase 2.7 hand-off.");
        }
        return b.run(req, snap, ps, cfg);
    }

    /** Run a route against a live {@code Client}. Throws until a live
     *  binding is wired (typically only on a developer's bot-runner
     *  workstation, per Phase 3 manual gate for Tests 2 and 8). */
    public static RunRecord runRouteLive(NavRequest req,
                                         Object client,
                                         Object clientThread)
    {
        LivePlannerExecutorBinding b = LIVE_BINDING;
        if (b == null)
        {
            throw new UnsupportedOperationException(
                "NavigationTestHarness: no LivePlannerExecutorBinding "
                + "wired. Live runs require a developer workstation "
                + "(see docs/superpowers/plans/2026-05-16-nav-engine-master.md "
                + "Phase 3 — Tests 2 + 8 are manual gates).");
        }
        return b.run(req, client, clientThread);
    }

    /** Tick-driven binding for offline planner+executor runs.
     *  Implementations cap their own tick budget (default 256) and
     *  return a {@link RunRecord} once the executor signals path
     *  completion or unrecoverable failure. */
    @FunctionalInterface
    public interface PlannerExecutorBinding
    {
        RunRecord run(NavRequest req, WorldSnapshot snap, PlayerState ps, BfsConfig cfg);
    }

    @FunctionalInterface
    public interface LivePlannerExecutorBinding
    {
        RunRecord run(NavRequest req, Object client, Object clientThread);
    }

    /** Mutable record builder for binding implementations. Hides the
     *  immutable {@link RunRecord} until the run is complete. */
    public static final class Builder
    {
        private V2Path planned;
        private final List<ExecutorTickResult> ticks = new ArrayList<>();
        private final List<RouteTrace> traces = new ArrayList<>();
        private Optional<ReplanReason> failureReason = Optional.empty();
        private final long startedAtMs = System.currentTimeMillis();

        public Builder planned(V2Path v2Path) { this.planned = v2Path; return this; }
        public Builder addTick(ExecutorTickResult t) { ticks.add(t); return this; }
        public Builder addTrace(RouteTrace t) { traces.add(t); return this; }
        public Builder failure(ReplanReason r) { failureReason = Optional.ofNullable(r); return this; }

        public RunRecord build()
        {
            long totalMs = System.currentTimeMillis() - startedAtMs;
            return new RunRecord(planned, List.copyOf(ticks), List.copyOf(traces),
                failureReason, totalMs);
        }
    }
}
