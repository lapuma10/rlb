package net.runelite.client.plugins.recorder.nav.v2;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.nav.BehaviorMode;
import net.runelite.client.plugins.recorder.nav.EntityKind;
import net.runelite.client.plugins.recorder.nav.NavRequest;
import net.runelite.client.plugins.recorder.nav.NavStatus;
import net.runelite.client.plugins.recorder.worldmap.EntityIndex;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/** Phase-16 entity-targeted navigation. V2 resolves the entity name to
 *  a nearest sighting via {@link EntityIndex}; missing → FAILED with an
 *  ENTITY_NOT_FOUND log line; resolved → planner is called with the
 *  sighting tile as target. */
public class V2EntityNavigationTest
{
    /** Recording planner — captures every plan() call so tests can
     *  verify what target tile the navigator sent. */
    private static final class RecPlanner implements V2Navigator.PlannerHook
    {
        final List<WorldPoint> plannedTo = new ArrayList<>();
        @Nullable V2Path next;

        RecPlanner(@Nullable V2Path next) { this.next = next; }

        @Override
        public V2Path plan(WorldPoint from, WorldPoint to, BehaviorMode mode)
        {
            plannedTo.add(to);
            return next == null ? V2Path.EMPTY : next;
        }

        @Override public String diagnose(WorldPoint from, WorldPoint to) { return "(test)"; }
    }

    /** Recording executor — captures setPath calls. */
    private static final class RecExecutor implements V2Navigator.ExecutorHook
    {
        @Nullable V2Path lastPath;
        V2Executor.Status status = V2Executor.Status.RUNNING;

        @Override public void setPath(V2Path p) { this.lastPath = p; }
        @Override public V2Executor.Status tick() { return status; }
        @Override public void cancel() {}
        @Override public V2Executor.Status status() { return status; }
    }

    private static V2Path samplePath()
    {
        return new V2Path(List.<V2Leg>of(new V2Leg.Walk(0,
            List.of(new WorldPoint(3208, 3217, 0), new WorldPoint(3209, 3217, 0)))), 1);
    }

    private static V2Navigator.PlayerLocSupplier here(WorldPoint p)
    {
        return new V2Navigator.PlayerLocSupplier()
        {
            @Override public WorldPoint get() { return p; }
        };
    }

    @Test
    public void toEntity_resolvesNearestSighting_andPlansToIt() throws Exception
    {
        EntityIndex idx = new EntityIndex();
        WorldPoint cookTile = new WorldPoint(3210, 3214, 0);
        idx.recordNpcSighting(4626, "Cook", cookTile, 1L);

        RecPlanner planner = new RecPlanner(samplePath());
        RecExecutor exec = new RecExecutor();
        WorldPoint here = new WorldPoint(3208, 3217, 0);
        V2Navigator nav = new V2Navigator(planner, exec, here(here), idx);

        NavStatus s = nav.tick(NavRequest.toEntity("Cook", EntityKind.NPC, BehaviorMode.VARIED));

        assertEquals(NavStatus.RUNNING, s);
        assertEquals("planner must be called for the sighting tile",
            1, planner.plannedTo.size());
        assertEquals(cookTile, planner.plannedTo.get(0));
    }

    @Test
    public void toEntity_noSighting_returnsFailed_andPlannerNotCalled() throws Exception
    {
        EntityIndex idx = new EntityIndex();   // empty
        RecPlanner planner = new RecPlanner(samplePath());
        RecExecutor exec = new RecExecutor();
        V2Navigator nav = new V2Navigator(planner, exec, here(new WorldPoint(3208, 3217, 0)), idx);

        NavStatus s = nav.tick(NavRequest.toEntity("Cook", EntityKind.NPC, BehaviorMode.VARIED));

        assertEquals("missing entity sighting → FAILED", NavStatus.FAILED, s);
        assertEquals("planner must NOT be invoked when entity not resolvable",
            0, planner.plannedTo.size());
        assertEquals("FailureReason must be ENTITY_NOT_FOUND",
            V2Navigator.FailureReason.ENTITY_NOT_FOUND, nav.lastFailureReason());
    }

    @Test
    public void toEntity_object_resolvedViaObjectIndex() throws Exception
    {
        EntityIndex idx = new EntityIndex();
        WorldPoint boothTile = new WorldPoint(3185, 3438, 0);
        idx.recordObjectSighting(10583, "Bank booth", boothTile, 1L);

        RecPlanner planner = new RecPlanner(samplePath());
        RecExecutor exec = new RecExecutor();
        V2Navigator nav = new V2Navigator(planner, exec, here(new WorldPoint(3200, 3440, 0)), idx);

        NavStatus s = nav.tick(NavRequest.toEntity("Bank booth", EntityKind.OBJECT, BehaviorMode.VARIED));

        assertEquals(NavStatus.RUNNING, s);
        assertEquals(boothTile, planner.plannedTo.get(0));
    }

    @Test
    public void toEntity_areaKind_failsWithEntityNotFoundSemantics() throws Exception
    {
        // AREA reserved but not implemented round-1: V2Navigator returns
        // FAILED so HybridNavigator can fall back. (V1 also fails because
        // the request lacks a trail name → fallback chain is intact.)
        EntityIndex idx = new EntityIndex();
        RecPlanner planner = new RecPlanner(samplePath());
        RecExecutor exec = new RecExecutor();
        V2Navigator nav = new V2Navigator(planner, exec, here(new WorldPoint(0, 0, 0)), idx);

        NavStatus s = nav.tick(NavRequest.toEntity("Lumby Bank", EntityKind.AREA, BehaviorMode.VARIED));

        assertEquals(NavStatus.FAILED, s);
        assertEquals(0, planner.plannedTo.size());
    }

    @Test
    public void toEntity_noEntityIndex_failsCleanly() throws Exception
    {
        // V2Navigator constructed without an EntityIndex (legacy ctor)
        // must FAIL on entity requests, not throw. Defensive — keeps
        // older test setups working.
        RecPlanner planner = new RecPlanner(samplePath());
        RecExecutor exec = new RecExecutor();
        V2Navigator nav = new V2Navigator(planner, exec, here(new WorldPoint(0, 0, 0)));   // no index

        NavStatus s = nav.tick(NavRequest.toEntity("Cook", EntityKind.NPC, BehaviorMode.VARIED));
        assertEquals(NavStatus.FAILED, s);
        assertEquals(V2Navigator.FailureReason.ENTITY_NOT_FOUND, nav.lastFailureReason());
    }

    @Test
    public void toEntity_pickNearestAmongMultipleSightings() throws Exception
    {
        EntityIndex idx = new EntityIndex();
        WorldPoint farCook = new WorldPoint(3300, 3300, 0);
        WorldPoint nearCook = new WorldPoint(3210, 3214, 0);
        idx.recordNpcSighting(4626, "Cook", farCook, 1L);
        idx.recordNpcSighting(4626, "Cook", nearCook, 2L);

        RecPlanner planner = new RecPlanner(samplePath());
        RecExecutor exec = new RecExecutor();
        WorldPoint here = new WorldPoint(3208, 3217, 0);
        V2Navigator nav = new V2Navigator(planner, exec, here(here), idx);

        nav.tick(NavRequest.toEntity("Cook", EntityKind.NPC, BehaviorMode.VARIED));

        assertSame("nearest sighting wins", nearCook, planner.plannedTo.get(0));
    }

    @Test
    public void requestRoundTrip_includesEntityFields()
    {
        NavRequest r = NavRequest.toEntity("Cook", EntityKind.NPC, "Bank", BehaviorMode.VARIED);
        assertEquals("Cook", r.entity().name());
        assertEquals(EntityKind.NPC, r.entity().kind());
        assertEquals("Bank", r.entity().action());
        assertEquals(null, r.to());
        assertEquals(null, r.trailName());
    }
}
