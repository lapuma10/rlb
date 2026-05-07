package net.runelite.client.plugins.recorder.nav.v2;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import javax.annotation.Nullable;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class V2ExecutorTest
{
    /** Fake Env with deterministic behavior; records dispatches for assertion. */
    private static final class FakeEnv implements V2Executor.Env
    {
        WorldPoint player;
        boolean busy;
        long now;
        final Set<WorldPoint> uncleanTiles = new HashSet<>();
        final Set<WorldPoint> minimapBlocked = new HashSet<>();
        final Set<WorldPoint> staticBlocked = new HashSet<>();    // snapshot=walkable, live=blocked
        final Set<WorldPoint> dynamicEntities = new HashSet<>();  // NPC standing on tile
        final List<WorldPoint> walkDispatches = new ArrayList<>();
        final List<WorldPoint> minimapDispatches = new ArrayList<>();

        @Override @Nullable public WorldPoint playerLoc() { return player; }
        @Override public boolean isPlausiblyClean(WorldPoint t) { return !uncleanTiles.contains(t); }
        @Override public boolean canMinimapClick(WorldPoint t) { return !minimapBlocked.contains(t); }
        @Override public boolean dispatchWalk(WorldPoint t)
        {
            if (busy) return false;
            walkDispatches.add(t);
            busy = true;
            return true;
        }
        @Override public boolean dispatchMinimap(WorldPoint t)
        {
            if (busy) return false;
            minimapDispatches.add(t);
            busy = true;
            return true;
        }
        @Override public boolean dispatcherBusy() { return busy; }
        @Override public long nowMs() { return now; }

        @Override public boolean snapshotSaysWalkable(WorldPoint t) { return true; }
        @Override public boolean liveCollisionAllows(WorldPoint t) { return !staticBlocked.contains(t); }
        @Override public boolean dynamicEntityOnTile(WorldPoint t) { return dynamicEntities.contains(t); }
    }

    private static V2Path eastPath(int n)
    {
        List<WorldPoint> tiles = new ArrayList<>();
        for (int i = 0; i < n; i++) tiles.add(new WorldPoint(3208 + i, 3217, 0));
        return new V2Path(List.<V2Leg>of(new V2Leg.Walk(0, tiles)), n - 1);
    }

    private static V2Executor newExecutor(FakeEnv env)
    {
        return new V2Executor(env, new CanvasTilePicker(),
            new InvalidationClassifier(), new Random(42));
    }

    @Test
    public void setPath_emptyPath_statusIdle()
    {
        FakeEnv env = new FakeEnv();
        env.player = new WorldPoint(0, 0, 0);
        V2Executor x = newExecutor(env);
        x.setPath(V2Path.EMPTY);
        assertEquals(V2Executor.Status.IDLE, x.status());
    }

    @Test
    public void setPath_nonEmpty_statusRunning()
    {
        FakeEnv env = new FakeEnv();
        env.player = new WorldPoint(3208, 3217, 0);
        V2Executor x = newExecutor(env);
        x.setPath(eastPath(20));
        assertEquals(V2Executor.Status.RUNNING, x.status());
    }

    @Test
    public void tick_dispatcherBusy_doesNotDispatch()
    {
        FakeEnv env = new FakeEnv();
        env.player = new WorldPoint(3208, 3217, 0);
        env.busy = true;
        V2Executor x = newExecutor(env);
        x.setPath(eastPath(20));
        x.tick();
        assertEquals(0, env.walkDispatches.size());
        assertEquals(0, env.minimapDispatches.size());
    }

    @Test
    public void tick_canvasModality_dispatchesWalk()
    {
        FakeEnv env = new FakeEnv();
        env.player = new WorldPoint(3208, 3217, 0);
        V2Executor x = newExecutor(env);
        x.setPath(eastPath(20));
        x.tick();
        assertEquals("canvas modality must produce a single walk dispatch",
            1, env.walkDispatches.size());
        assertEquals(0, env.minimapDispatches.size());
    }

    @Test
    public void tick_arrivedAtPathEnd_returnsArrived()
    {
        FakeEnv env = new FakeEnv();
        V2Path p = eastPath(5);
        env.player = new WorldPoint(3212, 3217, 0); // path end
        V2Executor x = newExecutor(env);
        x.setPath(p);
        V2Executor.Status s = x.tick();
        assertEquals(V2Executor.Status.ARRIVED, s);
    }

    @Test
    public void tick_filterRejectsAllCanvas_fallsBackToMinimap()
    {
        FakeEnv env = new FakeEnv();
        V2Path p = eastPath(20);
        env.player = new WorldPoint(3208, 3217, 0);
        // Block every path tile from the canvas filter — picker returns null,
        // executor falls through to minimap on the same tick.
        for (V2Leg leg : p.legs())
        {
            if (leg instanceof V2Leg.Walk w)
            {
                for (WorldPoint t : w.tiles()) env.uncleanTiles.add(t);
            }
        }
        V2Executor x = newExecutor(env);
        x.setPath(p);
        x.tick();
        assertEquals(0, env.walkDispatches.size());
        assertEquals("minimap dispatch when every canvas candidate filtered",
            1, env.minimapDispatches.size());
    }

    @Test
    public void tick_filterRejectsRepeatedly_modalityShiftsToMinimap()
    {
        // First few ticks: canvas filter has a 50%+ rejection rate. Executor
        // should bias toward minimap on subsequent ticks.
        FakeEnv env = new FakeEnv();
        V2Path p = eastPath(20);
        env.player = new WorldPoint(3208, 3217, 0);
        // Block ~all candidate tiles in mid+long buckets so most picks fail.
        for (V2Leg leg : p.legs())
        {
            if (leg instanceof V2Leg.Walk w)
            {
                for (WorldPoint t : w.tiles()) env.uncleanTiles.add(t);
            }
        }
        V2Executor x = newExecutor(env);
        x.setPath(p);
        // Drive several ticks with the dispatcher staying busy-free in between.
        for (int i = 0; i < 5; i++)
        {
            env.busy = false;
            x.tick();
        }
        // Either the executor preferred minimap directly or fell through
        // every tick — what matters is canvas was NOT preferred.
        assertEquals("filter blocks all canvas → executor must use minimap modality",
            0, env.walkDispatches.size());
        assertTrue("minimap modality should have produced ≥1 dispatch",
            env.minimapDispatches.size() >= 1);
    }

    @Test
    public void tick_stalledOnSameTile_dispatchesCatchupOnLastTile()
    {
        FakeEnv env = new FakeEnv();
        env.player = new WorldPoint(3208, 3217, 0);
        V2Executor x = newExecutor(env);
        x.setPath(eastPath(20));
        x.tick();
        assertEquals(1, env.walkDispatches.size());
        WorldPoint firstClick = env.walkDispatches.get(0);

        // Dispatcher finishes; player did not move; subsequent ticks should
        // accumulate stalled state and eventually issue a catch-up click.
        for (int i = 0; i < V2Executor.STALL_TICKS + 1; i++)
        {
            env.busy = false;
            x.tick();
        }
        assertTrue("stalled execution must issue at least one catch-up dispatch",
            env.walkDispatches.size() >= 2);
        assertSame("catch-up re-clicks the previously dispatched tile",
            firstClick, env.walkDispatches.get(env.walkDispatches.size() - 1));
    }

    @Test
    public void tick_catchupExhausted_returnsFailed()
    {
        FakeEnv env = new FakeEnv();
        env.player = new WorldPoint(3208, 3217, 0);
        V2Executor x = newExecutor(env);
        x.setPath(eastPath(20));

        // First click + repeated stalls until catch-up budget exhausts.
        x.tick();   // initial dispatch
        for (int i = 0; i < (V2Executor.MAX_CATCHUP_CLICKS_PER_LEG + 1) * (V2Executor.STALL_TICKS + 1); i++)
        {
            env.busy = false;
            x.tick();
        }
        assertEquals(V2Executor.Status.FAILED, x.status());
    }

    @Test
    public void cancel_resetsToIdle()
    {
        FakeEnv env = new FakeEnv();
        env.player = new WorldPoint(3208, 3217, 0);
        V2Executor x = newExecutor(env);
        x.setPath(eastPath(5));
        x.cancel();
        assertEquals(V2Executor.Status.IDLE, x.status());
    }

    @Test
    public void setRunMode_onOrOff_logsButFallsThrough()
    {
        // No exceptions, no behavior change — round-1 stub. Verifies the
        // method exists and tolerates any value.
        FakeEnv env = new FakeEnv();
        env.player = new WorldPoint(3208, 3217, 0);
        V2Executor x = newExecutor(env);
        x.setRunMode(V2Executor.RunMode.ON);
        x.setRunMode(V2Executor.RunMode.OFF);
        x.setRunMode(V2Executor.RunMode.UNCHANGED);
        x.setRunMode(null);   // tolerated
    }

    @Test
    public void tick_stalledWithStaticCollisionMismatch_failsImmediately()
    {
        // Snapshot says walkable, live collision flags say blocked → classifier
        // returns STATIC_COLLISION_MISMATCH. Executor must FAIL so navigator
        // replans with a fresh route, NOT spin on catch-up clicks.
        FakeEnv env = new FakeEnv();
        env.player = new WorldPoint(3208, 3217, 0);
        V2Executor x = newExecutor(env);
        V2Path p = eastPath(20);
        x.setPath(p);

        // Initial dispatch — pick a tile and dispatch to it.
        x.tick();
        assertTrue(env.walkDispatches.size() >= 1);
        WorldPoint clicked = env.walkDispatches.get(0);
        // Mark the clicked tile as live-collision-blocked AFTER dispatch
        // so the classify call sees the mismatch.
        env.staticBlocked.add(clicked);

        // Stall ticks — player static, dispatcher idle.
        for (int i = 0; i < V2Executor.STALL_TICKS + 1; i++)
        {
            env.busy = false;
            x.tick();
        }
        assertEquals("static collision mismatch must FAIL — replan, don't retry",
            V2Executor.Status.FAILED, x.status());
    }

    @Test
    public void tick_stalledWithDynamicBlocker_catchUpUsedFirst()
    {
        FakeEnv env = new FakeEnv();
        env.player = new WorldPoint(3208, 3217, 0);
        V2Executor x = newExecutor(env);
        V2Path p = eastPath(20);
        x.setPath(p);
        x.tick();
        WorldPoint clicked = env.walkDispatches.get(0);
        // Dynamic blocker — the live collision IS fine, but an entity
        // is standing there.
        env.dynamicEntities.add(clicked);

        // First stall pass — should issue a catch-up.
        for (int i = 0; i < V2Executor.STALL_TICKS + 1; i++)
        {
            env.busy = false;
            x.tick();
        }
        assertTrue("dynamic blocker should trigger a catch-up click, not immediate FAIL",
            env.walkDispatches.size() >= 2);
        assertEquals("status remains RUNNING during dynamic-blocker recovery",
            V2Executor.Status.RUNNING, x.status());
    }

    @Test
    public void tick_loggedOut_returnsCurrentStatus()
    {
        FakeEnv env = new FakeEnv();
        env.player = null;   // simulates logout
        V2Executor x = newExecutor(env);
        x.setPath(eastPath(5));
        V2Executor.Status s = x.tick();
        assertEquals("missing player loc must not advance state", V2Executor.Status.RUNNING, s);
        assertEquals("no dispatch when player loc unknown", 0, env.walkDispatches.size());
    }
}
