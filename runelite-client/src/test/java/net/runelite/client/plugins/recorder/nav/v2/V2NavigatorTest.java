package net.runelite.client.plugins.recorder.nav.v2;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.nav.BehaviorMode;
import net.runelite.client.plugins.recorder.nav.NavRequest;
import net.runelite.client.plugins.recorder.nav.NavStatus;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class V2NavigatorTest
{
    private static final WorldPoint HERE = new WorldPoint(3208, 3217, 0);
    private static final WorldPoint THERE = new WorldPoint(3216, 3217, 0);

    private static V2Path samplePath()
    {
        List<WorldPoint> tiles = new ArrayList<>();
        for (int i = 0; i < 9; i++) tiles.add(new WorldPoint(3208 + i, 3217, 0));
        return new V2Path(List.<V2Leg>of(new V2Leg.Walk(0, tiles)), 8);
    }

    private static class FakePlanner implements V2Navigator.PlannerHook
    {
        int callCount;
        @Nullable WorldPoint lastFrom, lastTo;
        @Nullable BehaviorMode lastMode;
        V2Path planResult = samplePath();

        @Override public V2Path plan(WorldPoint from, WorldPoint to, BehaviorMode mode)
        {
            callCount++;
            lastFrom = from;
            lastTo = to;
            lastMode = mode;
            return planResult;
        }
    }

    private static class FakeExecutor implements V2Navigator.ExecutorHook
    {
        @Nullable V2Path setPathArg;
        int tickCount;
        int cancelCount;
        V2Executor.Status nextStatus = V2Executor.Status.IDLE;
        @Nullable net.runelite.client.plugins.recorder.nav.v2.ExecutorTickResult nextTickResult;

        @Override public void setPath(V2Path p) { setPathArg = p; }
        @Override public V2Executor.Status tick() { tickCount++; return nextStatus; }
        @Override public void cancel() { cancelCount++; nextStatus = V2Executor.Status.IDLE; nextTickResult = null; }
        @Override public V2Executor.Status status() { return nextStatus; }
        @Override
        @Nullable
        public net.runelite.client.plugins.recorder.nav.v2.ExecutorTickResult tickResult()
        { return nextTickResult; }
    }

    private static V2Navigator.PlayerLocSupplier locSupplier(WorldPoint p)
    {
        return () -> p;
    }

    @Test
    public void name_returnsWorldmapV2()
    {
        V2Navigator nav = new V2Navigator(new FakePlanner(), new FakeExecutor(), locSupplier(HERE));
        assertEquals("worldmap-v2", nav.name());
    }

    @Test
    public void firstTick_callsPlannerOnce_andSetsPathOnExecutor() throws InterruptedException
    {
        FakePlanner planner = new FakePlanner();
        FakeExecutor executor = new FakeExecutor();
        V2Navigator nav = new V2Navigator(planner, executor, locSupplier(HERE));
        nav.tick(NavRequest.toPoint(THERE, BehaviorMode.VARIED));
        assertEquals(1, planner.callCount);
        assertEquals(HERE, planner.lastFrom);
        assertEquals(THERE, planner.lastTo);
        assertEquals(BehaviorMode.VARIED, planner.lastMode);
        assertNotNull("executor must receive the planned path", executor.setPathArg);
    }

    @Test
    public void subsequentTicksWithSameRequest_doNotReplan_delegateToExecutor() throws InterruptedException
    {
        FakePlanner planner = new FakePlanner();
        FakeExecutor executor = new FakeExecutor();
        V2Navigator nav = new V2Navigator(planner, executor, locSupplier(HERE));
        NavRequest req = NavRequest.toPoint(THERE, BehaviorMode.VARIED);
        nav.tick(req);
        nav.tick(req);
        nav.tick(req);
        assertEquals("planner must run once for a stable destination",
            1, planner.callCount);
        assertEquals(3, executor.tickCount);
    }

    @Test
    public void differentTargetTriggersReplan() throws InterruptedException
    {
        FakePlanner planner = new FakePlanner();
        FakeExecutor executor = new FakeExecutor();
        V2Navigator nav = new V2Navigator(planner, executor, locSupplier(HERE));
        nav.tick(NavRequest.toPoint(THERE, BehaviorMode.VARIED));
        nav.tick(NavRequest.toPoint(new WorldPoint(3300, 3300, 0), BehaviorMode.VARIED));
        assertEquals("changing destination must trigger a new plan",
            2, planner.callCount);
    }

    @Test
    public void requestWithNullDestination_returnsFailed_doesNotCallPlanner() throws InterruptedException
    {
        FakePlanner planner = new FakePlanner();
        FakeExecutor executor = new FakeExecutor();
        V2Navigator nav = new V2Navigator(planner, executor, locSupplier(HERE));
        NavStatus s = nav.tick(NavRequest.byTrail("ignored", BehaviorMode.VARIED));
        assertEquals(NavStatus.FAILED, s);
        assertEquals("V2 needs a WorldPoint target — must not consult planner",
            0, planner.callCount);
    }

    @Test
    public void plannerEmptyResult_returnsFailed() throws InterruptedException
    {
        FakePlanner planner = new FakePlanner();
        planner.planResult = V2Path.EMPTY;
        FakeExecutor executor = new FakeExecutor();
        V2Navigator nav = new V2Navigator(planner, executor, locSupplier(HERE));
        NavStatus s = nav.tick(NavRequest.toPoint(THERE, BehaviorMode.VARIED));
        assertEquals("empty plan = no route = FAILED", NavStatus.FAILED, s);
        assertEquals("FailureReason must be NO_ROUTE",
            V2Navigator.FailureReason.NO_ROUTE, nav.lastFailureReason());
    }

    @Test
    public void nullPlayerLocAtPlanTime_returnsFailed_NO_PLAYER_LOC() throws InterruptedException
    {
        FakePlanner planner = new FakePlanner();
        FakeExecutor executor = new FakeExecutor();
        V2Navigator nav = new V2Navigator(planner, executor, locSupplier(null));
        NavStatus s = nav.tick(NavRequest.toPoint(THERE, BehaviorMode.VARIED));
        assertEquals(NavStatus.FAILED, s);
        assertEquals(V2Navigator.FailureReason.NO_PLAYER_LOC, nav.lastFailureReason());
        assertEquals("planner must not be called when player loc unknown",
            0, planner.callCount);
    }

    @Test
    public void requestWithNullDestination_failureReason_BAD_REQUEST() throws InterruptedException
    {
        FakePlanner planner = new FakePlanner();
        FakeExecutor executor = new FakeExecutor();
        V2Navigator nav = new V2Navigator(planner, executor, locSupplier(HERE));
        NavStatus s = nav.tick(NavRequest.byTrail("ignored", BehaviorMode.VARIED));
        assertEquals(NavStatus.FAILED, s);
        assertEquals(V2Navigator.FailureReason.BAD_REQUEST, nav.lastFailureReason());
    }

    @Test
    public void executorFailed_navigatorReportsEXECUTOR_FAILED() throws InterruptedException
    {
        FakePlanner planner = new FakePlanner();
        FakeExecutor executor = new FakeExecutor();
        executor.nextStatus = V2Executor.Status.FAILED;
        V2Navigator nav = new V2Navigator(planner, executor, locSupplier(HERE));
        nav.tick(NavRequest.toPoint(THERE, BehaviorMode.VARIED));
        assertEquals(V2Navigator.FailureReason.EXECUTOR_FAILED, nav.lastFailureReason());
    }

    @Test
    public void cancel_clearsLastFailureReason() throws InterruptedException
    {
        FakePlanner planner = new FakePlanner();
        FakeExecutor executor = new FakeExecutor();
        executor.nextStatus = V2Executor.Status.FAILED;
        V2Navigator nav = new V2Navigator(planner, executor, locSupplier(HERE));
        nav.tick(NavRequest.toPoint(THERE, BehaviorMode.VARIED));
        assertEquals(V2Navigator.FailureReason.EXECUTOR_FAILED, nav.lastFailureReason());
        nav.cancel();
        assertNull("cancel clears lastFailureReason", nav.lastFailureReason());
    }

    @Test
    public void executorArrived_afterPriorFailure_clearsLastFailureReason() throws InterruptedException
    {
        // Pass-3 P1: ARRIVED must clear a stale FailureReason left over
        // from a previous failed-then-replanned trip — otherwise the
        // panel surfaces "FAILED" tags on a successful trip.
        FakePlanner planner = new FakePlanner();
        FakeExecutor executor = new FakeExecutor();
        // First trip — fails.
        executor.nextStatus = V2Executor.Status.FAILED;
        V2Navigator nav = new V2Navigator(planner, executor, locSupplier(HERE));
        nav.tick(NavRequest.toPoint(THERE, BehaviorMode.VARIED));
        assertEquals(V2Navigator.FailureReason.EXECUTOR_FAILED, nav.lastFailureReason());
        // Second trip same target — executor reports ARRIVED. (Same
        // request reuses cached path; we only test status transition.)
        executor.nextStatus = V2Executor.Status.ARRIVED;
        nav.tick(NavRequest.toPoint(THERE, BehaviorMode.VARIED));
        assertNull("ARRIVED must clear lastFailureReason", nav.lastFailureReason());
    }

    @Test
    public void executorArrivedStatus_mapsToNavArrived() throws InterruptedException
    {
        FakePlanner planner = new FakePlanner();
        FakeExecutor executor = new FakeExecutor();
        executor.nextStatus = V2Executor.Status.ARRIVED;
        V2Navigator nav = new V2Navigator(planner, executor, locSupplier(HERE));
        NavStatus s = nav.tick(NavRequest.toPoint(THERE, BehaviorMode.VARIED));
        assertEquals(NavStatus.ARRIVED, s);
    }

    @Test
    public void executorFailedStatus_mapsToNavFailed() throws InterruptedException
    {
        FakePlanner planner = new FakePlanner();
        FakeExecutor executor = new FakeExecutor();
        executor.nextStatus = V2Executor.Status.FAILED;
        V2Navigator nav = new V2Navigator(planner, executor, locSupplier(HERE));
        NavStatus s = nav.tick(NavRequest.toPoint(THERE, BehaviorMode.VARIED));
        assertEquals(NavStatus.FAILED, s);
    }

    @Test
    public void cancel_cancelsExecutorAndClearsActivePlan() throws InterruptedException
    {
        FakePlanner planner = new FakePlanner();
        FakeExecutor executor = new FakeExecutor();
        executor.nextStatus = V2Executor.Status.RUNNING;
        V2Navigator nav = new V2Navigator(planner, executor, locSupplier(HERE));
        nav.tick(NavRequest.toPoint(THERE, BehaviorMode.VARIED));
        assertTrue("running navigator is busy after tick", nav.isBusy());
        nav.cancel();
        assertEquals(1, executor.cancelCount);
        assertFalse("isBusy must drop after cancel", nav.isBusy());
    }

    @Test
    public void isBusy_trueWhileExecutorRunning_falseWhenIdle() throws InterruptedException
    {
        FakePlanner planner = new FakePlanner();
        FakeExecutor executor = new FakeExecutor();
        V2Navigator nav = new V2Navigator(planner, executor, locSupplier(HERE));
        assertFalse("idle navigator is not busy", nav.isBusy());

        executor.nextStatus = V2Executor.Status.RUNNING;
        nav.tick(NavRequest.toPoint(THERE, BehaviorMode.VARIED));
        assertTrue("running navigator is busy", nav.isBusy());

        executor.nextStatus = V2Executor.Status.ARRIVED;
        nav.tick(NavRequest.toPoint(THERE, BehaviorMode.VARIED));
        assertFalse("arrived navigator drops busy", nav.isBusy());
    }

    // ─────────────────────────────────────────────────────────────────
    // Lane 5 plan Task 5: typed-result consumption tests.
    // ─────────────────────────────────────────────────────────────────

    private static net.runelite.client.plugins.recorder.nav.v2.ExecutorTickResult needsReplan(
        net.runelite.client.plugins.recorder.nav.v2.ReplanReason r)
    {
        return net.runelite.client.plugins.recorder.nav.v2.executor.ExecutorTickResultImpl.builder()
            .result(net.runelite.client.plugins.recorder.nav.v2.ExecutorResult.NEEDS_REPLAN)
            .replanReason(r)
            .debugTraceId("test-trace")
            .build();
    }

    @Test
    public void navigator_executorReturnsNeedsReplan_invokesPlannerOnce() throws InterruptedException
    {
        FakePlanner planner = new FakePlanner();
        FakeExecutor executor = new FakeExecutor();
        executor.nextStatus = V2Executor.Status.RUNNING;
        V2Navigator nav = new V2Navigator(planner, executor, locSupplier(HERE));

        // First tick: plans once.
        nav.tick(NavRequest.toPoint(THERE, BehaviorMode.VARIED));
        assertEquals(1, planner.callCount);

        // Set up tick-result that says NEEDS_REPLAN.
        executor.nextTickResult = needsReplan(
            net.runelite.client.plugins.recorder.nav.v2.ReplanReason.NO_LOCAL_WALKABLE_TILE);

        // Second tick: navigator sees NEEDS_REPLAN and re-plans.
        nav.tick(NavRequest.toPoint(THERE, BehaviorMode.VARIED));
        assertEquals("navigator replanned once after NEEDS_REPLAN",
            2, planner.callCount);
    }

    @Test
    public void navigator_replanBudgetExhausted_returnsFailed() throws InterruptedException
    {
        FakePlanner planner = new FakePlanner();
        FakeExecutor executor = new FakeExecutor();
        executor.nextStatus = V2Executor.Status.RUNNING;
        V2Navigator nav = new V2Navigator(planner, executor, locSupplier(HERE));
        NavRequest req = NavRequest.toPoint(THERE, BehaviorMode.VARIED);

        // First tick — initial plan.
        nav.tick(req);

        // Run replans up to budget. Each subsequent tick has NEEDS_REPLAN.
        for (int i = 0; i < V2Navigator.MAX_REPLANS_PER_REQUEST; i++)
        {
            executor.nextTickResult = needsReplan(
                net.runelite.client.plugins.recorder.nav.v2.ReplanReason.NO_LOCAL_WALKABLE_TILE);
            nav.tick(req);
        }
        // One more tick: budget exhausted → FAILED.
        executor.nextTickResult = needsReplan(
            net.runelite.client.plugins.recorder.nav.v2.ReplanReason.NO_LOCAL_WALKABLE_TILE);
        NavStatus s = nav.tick(req);
        assertEquals("budget exhausted → FAILED", NavStatus.FAILED, s);
    }

    @Test
    public void navigator_executorReturnsPathCompleted_returnsArrived() throws InterruptedException
    {
        // ExecutorResult.PATH_COMPLETED maps via Status.ARRIVED to NavStatus.ARRIVED.
        FakePlanner planner = new FakePlanner();
        FakeExecutor executor = new FakeExecutor();
        executor.nextStatus = V2Executor.Status.ARRIVED;
        executor.nextTickResult = net.runelite.client.plugins.recorder.nav.v2.executor
            .ExecutorTickResultImpl.builder()
            .result(net.runelite.client.plugins.recorder.nav.v2.ExecutorResult.PATH_COMPLETED)
            .debugTraceId("test-trace")
            .build();
        V2Navigator nav = new V2Navigator(planner, executor, locSupplier(HERE));
        NavStatus s = nav.tick(NavRequest.toPoint(THERE, BehaviorMode.VARIED));
        assertEquals(NavStatus.ARRIVED, s);
    }

    @Test
    public void navigator_appliesTransportCorrectionRequest() throws InterruptedException
    {
        // When ExecutorTickResult carries a TransportCorrectionRequest, the
        // navigator invokes its installed TransportCorrectionSink.
        FakePlanner planner = new FakePlanner();
        FakeExecutor executor = new FakeExecutor();
        executor.nextStatus = V2Executor.Status.RUNNING;
        V2Navigator nav = new V2Navigator(planner, executor, locSupplier(HERE));

        java.util.concurrent.atomic.AtomicInteger applyCalls = new java.util.concurrent.atomic.AtomicInteger();
        nav.setTransportCorrectionSink(req -> applyCalls.incrementAndGet());

        nav.tick(NavRequest.toPoint(THERE, BehaviorMode.VARIED));

        // Inject an ExecutorTickResult with a TransportCorrectionRequest.
        net.runelite.client.plugins.recorder.nav.v2.TransportCorrectionRequest req =
            new net.runelite.client.plugins.recorder.nav.v2.TransportCorrectionRequest()
            {
                @Override public WorldPoint plannedTo() { return new WorldPoint(1, 2, 0); }
                @Override public WorldPoint actualTo() { return new WorldPoint(1, 2, 1); }
                @Override
                public net.runelite.client.plugins.recorder.nav.v2.TransportLeg edge()
                {
                    return new net.runelite.client.plugins.recorder.nav.v2.TransportLeg()
                    {
                        @Override public WorldPoint from() { return new WorldPoint(1, 2, 0); }
                        @Override public WorldPoint to() { return new WorldPoint(1, 2, 1); }
                        @Override public net.runelite.client.plugins.recorder.nav.v2.TransportType type()
                        { return net.runelite.client.plugins.recorder.nav.v2.TransportType.OBJECT_VERB; }
                        @Override public java.util.Optional<Integer> objectId()
                        { return java.util.Optional.of(42); }
                        @Override public java.util.Optional<String> action()
                        { return java.util.Optional.of("Climb"); }
                    };
                }
                @Override
                public net.runelite.client.plugins.recorder.nav.v2.ReplanReason inferredReason()
                { return net.runelite.client.plugins.recorder.nav.v2.ReplanReason.TRANSPORT_UNAVAILABLE; }
            };
        executor.nextTickResult = net.runelite.client.plugins.recorder.nav.v2.executor
            .ExecutorTickResultImpl.builder()
            .result(net.runelite.client.plugins.recorder.nav.v2.ExecutorResult.NEEDS_REPLAN)
            .replanReason(net.runelite.client.plugins.recorder.nav.v2.ReplanReason.TRANSPORT_UNAVAILABLE)
            .transportCorrection(req)
            .debugTraceId("test-trace")
            .build();
        nav.tick(NavRequest.toPoint(THERE, BehaviorMode.VARIED));

        assertEquals("navigator invokes the correction sink once",
            1, applyCalls.get());
    }
}
