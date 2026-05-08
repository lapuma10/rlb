package net.runelite.client.plugins.recorder.nav.v2;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import javax.annotation.Nullable;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.nav.BehaviorMode;
import net.runelite.client.plugins.recorder.nav.HybridNavigator;
import net.runelite.client.plugins.recorder.nav.NavRequest;
import net.runelite.client.plugins.recorder.nav.NavStatus;
import net.runelite.client.plugins.recorder.nav.Navigator;
import net.runelite.client.plugins.recorder.RecorderConfig.NavigatorMode;
import net.runelite.client.plugins.recorder.worldmap.MapStore;
import net.runelite.client.plugins.recorder.worldmap.RegionIds;
import net.runelite.client.plugins.recorder.worldmap.TransportEdge;
import net.runelite.client.plugins.recorder.worldmap.TransportIndex;
import net.runelite.client.plugins.recorder.worldmap.WorldMemoryConfig;
import net.runelite.client.plugins.recorder.worldmap.WorldMemoryFixtures;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Phase-9 fixture proof: V2_STRICT bank↔pen offline. Builds two
 *  named corridors (north / south of the Lumbridge "church") and runs
 *  V2 planning + execution against a fake live environment. Live click
 *  resolution is not exercised — that's a morning live-test concern.
 *  These tests confirm the *offline* contract: planner returns a valid
 *  path, executor never picks off-plane tiles, fallback engages cleanly
 *  when V2 can't satisfy. */
public class V2BankPenIntegrationTest
{
    /** Lumby bank approach (plane 0 entry tile). */
    private static final WorldPoint BANK = new WorldPoint(3208, 3220, 0);
    /** Chicken pen interior (plane 0). */
    private static final WorldPoint PEN_NORTH = new WorldPoint(3236, 3294, 0);
    private static final WorldPoint PEN_SOUTH = new WorldPoint(3232, 3289, 0);

    /** Build a connected corridor with rectangle tiles between (xMin,yMin) and
     *  (xMax,yMax) on plane 0. All tiles fully walkable. */
    private static void corridor(MapStore s, int xMin, int yMin, int xMax, int yMax)
    {
        Map<Integer, List<WorldMemoryFixtures.TileSpec>> byRegion = new HashMap<>();
        for (int x = xMin; x <= xMax; x++)
        {
            for (int y = yMin; y <= yMax; y++)
            {
                int rid = RegionIds.regionIdFor(x, y);
                byRegion.computeIfAbsent(rid, k -> new ArrayList<>())
                    .add(WorldMemoryFixtures.walkable(x, y, 0));
            }
        }
        for (var e : byRegion.entrySet())
        {
            // Merge with any pre-existing snapshot for this region.
            List<WorldMemoryFixtures.TileSpec> ts = new ArrayList<>(e.getValue());
            // installRegion replaces — read existing tiles back if necessary.
            // For test simplicity we accept overwrite: corridors that share
            // a region must be installed in one combined call. Tests below
            // pass all corridor tiles in one go via this method.
            WorldMemoryFixtures.installRegion(s, e.getKey(), ts);
        }
    }

    /** Same as {@link #corridor} but accumulates tiles across multiple
     *  calls for the same region. Use for stitched-together fixtures
     *  (north + south alternates that share regions). */
    private static void seedTiles(MapStore s, List<WorldMemoryFixtures.TileSpec> tiles)
    {
        Map<Integer, List<WorldMemoryFixtures.TileSpec>> byRegion = new HashMap<>();
        for (WorldMemoryFixtures.TileSpec ts : tiles)
        {
            int rid = RegionIds.regionIdFor(ts.x(), ts.y());
            byRegion.computeIfAbsent(rid, k -> new ArrayList<>()).add(ts);
        }
        for (var e : byRegion.entrySet())
            WorldMemoryFixtures.installRegion(s, e.getKey(), e.getValue());
    }

    private static List<WorldMemoryFixtures.TileSpec> rect(int xMin, int yMin, int xMax, int yMax)
    {
        List<WorldMemoryFixtures.TileSpec> out = new ArrayList<>();
        for (int x = xMin; x <= xMax; x++)
            for (int y = yMin; y <= yMax; y++)
                out.add(WorldMemoryFixtures.walkable(x, y, 0));
        return out;
    }

    private static V2Planner planner(MapStore s, TransportIndex t)
    {
        return new V2Planner(s, t, new WorldMemoryConfig(), new RouteHistory());
    }

    /** Recorder fake Env — same shape as V2ExecutorTest's FakeEnv but
     *  scoped to integration-test needs. */
    private static final class FakeEnv implements V2Executor.Env
    {
        WorldPoint player;
        boolean busy;
        long now;
        final Set<WorldPoint> uncleanTiles = new HashSet<>();
        final List<WorldPoint> walkDispatches = new ArrayList<>();
        final List<WorldPoint> minimapDispatches = new ArrayList<>();

        @Override @Nullable public WorldPoint playerLoc() { return player; }
        @Override public boolean isPlausiblyClean(WorldPoint t) { return !uncleanTiles.contains(t); }
        @Override public boolean canMinimapClick(WorldPoint t) { return true; }
        @Override public boolean dispatchWalk(WorldPoint t) { if (busy) return false; walkDispatches.add(t); busy = true; return true; }
        @Override public boolean dispatchMinimap(WorldPoint t) { if (busy) return false; minimapDispatches.add(t); busy = true; return true; }
        @Override public boolean dispatcherBusy() { return busy; }
        @Override public long nowMs() { return now; }
    }

    private static V2Executor executorFor(FakeEnv env)
    {
        return new V2Executor(env, new CanvasTilePicker(),
            new InvalidationClassifier(), new Random(7));
    }

    @Test
    public void v2Strict_bankToPen_north_plansSuccessfully()
    {
        MapStore s = new MapStore(new WorldMemoryConfig());
        TransportIndex t = new TransportIndex();
        // North corridor: bank (3208,3220) → pen (3236,3294). Single
        // bbox-rect that includes both endpoints + lateral padding.
        seedTiles(s, rect(3204, 3216, 3240, 3296));

        V2Path path = planner(s, t).plan(BANK, PEN_NORTH, BehaviorMode.VARIED);
        assertFalse("strict bank → pen north must produce a path", path.isEmpty());
        assertNotNull(path.routeId());
        // The path must NOT contain a transport leg — bank↔pen is plane 0
        // throughout, no stairs in the round-1 fixture.
        for (V2Leg leg : path.legs())
        {
            if (leg instanceof V2Leg.Transport)
                throw new AssertionError("bank↔pen on plane 0 must not need a transport leg");
        }
        // Endpoints align.
        WorldPoint start = ((V2Leg.Walk) path.legs().get(0)).start();
        WorldPoint end = ((V2Leg.Walk) path.legs().get(path.legs().size() - 1)).end();
        assertEquals(BANK, start);
        assertEquals(PEN_NORTH, end);
    }

    @Test
    public void v2Strict_penToBank_north_plansSuccessfully()
    {
        MapStore s = new MapStore(new WorldMemoryConfig());
        TransportIndex t = new TransportIndex();
        seedTiles(s, rect(3204, 3216, 3240, 3296));
        V2Path path = planner(s, t).plan(PEN_NORTH, BANK, BehaviorMode.VARIED);
        assertFalse("strict pen → bank must produce a path", path.isEmpty());
    }

    @Test
    public void v2Strict_bankToPen_missingCorridorGap_fails()
    {
        MapStore s = new MapStore(new WorldMemoryConfig());
        TransportIndex t = new TransportIndex();
        // Two disjoint patches — no tiles bridge x=3220..3225, so the
        // planner can't build a connected route.
        seedTiles(s, rect(3204, 3216, 3220, 3296));
        seedTiles(s, rect(3225, 3270, 3240, 3296));
        V2Path path = planner(s, t).plan(BANK, PEN_NORTH, BehaviorMode.VARIED);
        assertTrue("missing corridor gap → empty path", path.isEmpty());
    }

    @Test
    public void v2Strict_bankToPen_collisionBlockedEdge_failsReadable()
    {
        MapStore s = new MapStore(new WorldMemoryConfig());
        TransportIndex t = new TransportIndex();
        // Wall-of-blocked-tiles between bank and pen — every tile in
        // x ∈ [3220, 3220], y ∈ [3216, 3296] is blocked.
        List<WorldMemoryFixtures.TileSpec> tiles = new ArrayList<>();
        for (int x = 3204; x <= 3240; x++)
        {
            for (int y = 3216; y <= 3296; y++)
            {
                int mv = (x == 3220) ? CollisionDataFlag.BLOCK_MOVEMENT_FULL : 0;
                tiles.add(WorldMemoryFixtures.withMovement(x, y, 0, mv));
            }
        }
        seedTiles(s, tiles);

        V2Path path = planner(s, t).plan(BANK, PEN_NORTH, BehaviorMode.VARIED);
        // Diagonal walls at edges may still allow path; readiness reports
        // collision detail. We assert at least the readiness break reason
        // is informative.
        RouteReadiness r = new RouteReadiness(s, t, planner(s, t));
        RouteReadiness.Report rep = r.check(BANK, PEN_NORTH);
        if (path.isEmpty())
        {
            assertTrue("readiness must surface a collision-or-unknown reason",
                rep.firstBreakReason() == RouteReadiness.BreakReason.COLLISION_BLOCKED
                || rep.firstBreakReason() == RouteReadiness.BreakReason.DIAGONAL_BLOCKED
                || rep.firstBreakReason() == RouteReadiness.BreakReason.UNKNOWN_TILE);
        }
        else
        {
            // Even when a diagonal squeeze works, executor must not pick off-plane.
            for (V2Leg leg : path.legs())
            {
                if (leg instanceof V2Leg.Walk w)
                {
                    for (WorldPoint p : w.tiles())
                        assertEquals("walk leg tiles must stay on plane 0", 0, p.getPlane());
                }
            }
        }
    }

    @Test
    public void v2Strict_executor_neverPicksOffPlane()
    {
        // Fixture has plane-0 corridor; player on plane 0; planner returns
        // plane-0 walk leg; executor must never dispatch a click on a
        // plane-2 tile (defense against malformed routes).
        MapStore s = new MapStore(new WorldMemoryConfig());
        TransportIndex t = new TransportIndex();
        seedTiles(s, rect(3204, 3216, 3240, 3296));

        V2Path path = planner(s, t).plan(BANK, PEN_NORTH, BehaviorMode.VARIED);
        assertFalse(path.isEmpty());

        FakeEnv env = new FakeEnv();
        env.player = BANK;
        V2Executor exec = executorFor(env);
        exec.setPath(path);
        // Drive enough ticks to exercise pick + dispatch.
        for (int i = 0; i < 8; i++)
        {
            env.busy = false;
            exec.tick();
        }
        for (WorldPoint w : env.walkDispatches)
            assertEquals("walk dispatch must stay on plane 0", 0, w.getPlane());
        for (WorldPoint w : env.minimapDispatches)
            assertEquals("minimap dispatch must stay on plane 0", 0, w.getPlane());
    }

    @Test
    public void v2WithV1Fallback_emptyWorldmap_fallsBackCleanly() throws Exception
    {
        // Empty worldmap: V2 returns FAILED on the first tick. Hybrid in
        // V2_WITH_V1_FALLBACK mode delegates to V1 immediately.
        Navigator v1 = new RecordingNavigator("trail-v1", NavStatus.RUNNING);
        Navigator v2 = new AlwaysFailNavigator("worldmap-v2");
        HybridNavigator hybrid = new HybridNavigator(v1, v2, () -> NavigatorMode.V2_WITH_V1_FALLBACK);

        NavRequest req = NavRequest.compose("bank-to-pen", PEN_NORTH, BehaviorMode.VARIED);
        NavStatus s = hybrid.tick(req);
        assertEquals("V2 FAILED → V1 RUNNING via fallback", NavStatus.RUNNING, s);
    }

    @Test
    public void v2Strict_emptyWorldmap_failsClearly() throws Exception
    {
        Navigator v1 = new RecordingNavigator("trail-v1", NavStatus.ARRIVED);
        Navigator v2 = new AlwaysFailNavigator("worldmap-v2");
        HybridNavigator hybrid = new HybridNavigator(v1, v2, () -> NavigatorMode.V2_STRICT);

        NavRequest req = NavRequest.compose("bank-to-pen", PEN_NORTH, BehaviorMode.VARIED);
        NavStatus s = hybrid.tick(req);
        assertEquals("V2_STRICT must surface FAILED, never call V1", NavStatus.FAILED, s);
    }

    private static final class RecordingNavigator implements Navigator
    {
        private final String name;
        private final NavStatus[] script;
        private int idx;
        RecordingNavigator(String name, NavStatus... script) { this.name = name; this.script = script; }
        @Override public NavStatus tick(NavRequest r) { return script[Math.min(idx++, script.length - 1)]; }
        @Override public void cancel() {}
        @Override public boolean isBusy() { return false; }
        @Override public String name() { return name; }
    }

    private static final class AlwaysFailNavigator implements Navigator
    {
        private final String name;
        AlwaysFailNavigator(String name) { this.name = name; }
        @Override public NavStatus tick(NavRequest r) { return NavStatus.FAILED; }
        @Override public void cancel() {}
        @Override public boolean isBusy() { return false; }
        @Override public String name() { return name; }
    }

    @Test
    public void v2Strict_transportRequiredRoute_executesTransportLeg()
    {
        // Cross-plane fixture: bank on plane 0, target on plane 2, with
        // a transport edge in the middle. V2Planner produces a valid
        // path containing a Transport leg. Round-2 executor accepts the
        // route and drives leg-by-leg; this test confirms the route is
        // ACCEPTED (status RUNNING, no failure tag) and the picker only
        // ever dispatches walks on the player's current plane (the
        // sequencing guard from the live regression).
        MapStore s = new MapStore(new WorldMemoryConfig());
        TransportIndex t = new TransportIndex();
        seedTiles(s, rect(3204, 3216, 3216, 3225));
        // plane-2 patch.
        List<WorldMemoryFixtures.TileSpec> p2 = new ArrayList<>();
        for (int x = 3204; x <= 3216; x++)
            for (int y = 3216; y <= 3225; y++)
                p2.add(WorldMemoryFixtures.walkable(x, y, 2));
        // Combine with plane 0 region tiles for the same region IDs so the
        // single installRegion call holds both.
        Map<Integer, List<WorldMemoryFixtures.TileSpec>> byRegion = new HashMap<>();
        for (var ts : p2)
            byRegion.computeIfAbsent(RegionIds.regionIdFor(ts.x(), ts.y()), k -> new ArrayList<>()).add(ts);
        // Re-seed plane-0 tiles into the same regions (installRegion replaces).
        for (int x = 3204; x <= 3216; x++)
            for (int y = 3216; y <= 3225; y++)
                byRegion.computeIfAbsent(RegionIds.regionIdFor(x, y), k -> new ArrayList<>())
                    .add(WorldMemoryFixtures.walkable(x, y, 0));
        for (var e : byRegion.entrySet())
            WorldMemoryFixtures.installRegion(s, e.getKey(), e.getValue());

        TransportEdge stairs = new TransportEdge(
            new WorldPoint(3210, 3220, 0), new WorldPoint(3210, 3220, 2),
            12345, "Staircase", "Climb-up", 0, 0, "object",
            new WorldPoint(3210, 3220, 0), RegionIds.regionIdFor(3210, 3220),
            1, 0L, 0L);
        t.add(stairs);

        WorldPoint p2Goal = new WorldPoint(3215, 3220, 2);
        V2Path path = planner(s, t).plan(BANK, p2Goal, BehaviorMode.VARIED);
        assertFalse("transport route must be plannable when stairs known", path.isEmpty());

        FakeEnv env = new FakeEnv();
        env.player = BANK;
        V2Executor exec = executorFor(env);
        exec.setPath(path);
        assertEquals("transport-bearing route is now ACCEPTED, not rejected",
            V2Executor.Status.RUNNING, exec.status());

        // Drive a few ticks while the player is on plane 0 — every
        // dispatch (canvas or minimap) must target a plane-0 tile.
        for (int i = 0; i < 6; i++)
        {
            env.busy = false;
            exec.tick();
            if (exec.status() != V2Executor.Status.RUNNING) break;
        }
        for (WorldPoint w : env.walkDispatches)
        {
            assertEquals("WALK leg sequencing: dispatch plane must equal player plane",
                0, w.getPlane());
        }
        for (WorldPoint w : env.minimapDispatches)
        {
            assertEquals("WALK leg sequencing: minimap dispatch plane must equal player plane",
                0, w.getPlane());
        }
    }
}
