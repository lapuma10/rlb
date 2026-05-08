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
import net.runelite.client.plugins.recorder.worldmap.TransportEdge;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
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
        /** Live transport-object lookup result. Map key is the tile passed
         *  in by the executor (it iterates fromTile + 1-ring); the value is
         *  the resolved click tile (or {@code null} to mean "verb not on
         *  this tile"). When the key is absent, resolve returns null. */
        final Map<WorldPoint, WorldPoint> transportObjects = new HashMap<>();
        /** Records every dispatchTransport call as (tile, verb). */
        final List<TransportClick> transportClicks = new ArrayList<>();
        /** When non-null, dispatchTransport returns false (simulates dispatcher busy). */
        boolean refuseTransportDispatch;
        @Nullable String pendingDispatchError;

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

        @Override
        @Nullable
        public String lastDispatchError()
        {
            String e = pendingDispatchError;
            pendingDispatchError = null;
            return e;
        }

        @Override
        @Nullable
        public WorldPoint resolveTransportClickTile(int objectId, String verb, WorldPoint fromTile)
        {
            // Mimic production: try fromTile first, then 8-neighbor ring.
            if (transportObjects.containsKey(fromTile)) return transportObjects.get(fromTile);
            int[][] dirs = { {0,1}, {1,0}, {0,-1}, {-1,0},
                             {1,1}, {1,-1}, {-1,1}, {-1,-1} };
            for (int[] d : dirs)
            {
                WorldPoint near = new WorldPoint(
                    fromTile.getX() + d[0], fromTile.getY() + d[1], fromTile.getPlane());
                if (transportObjects.containsKey(near)) return transportObjects.get(near);
            }
            return null;
        }

        @Override
        public boolean dispatchTransport(WorldPoint clickTile, String verb)
        {
            if (refuseTransportDispatch) return false;
            if (busy) return false;
            transportClicks.add(new TransportClick(clickTile, verb));
            busy = true;
            return true;
        }

        // Round-2 hooks ----------------------------------------------------
        final List<EdgeCorrection> edgeCorrections = new ArrayList<>();
        final Map<WorldPoint, WorldPoint> openables = new HashMap<>();
        final List<WorldPoint> openClicks = new ArrayList<>();
        boolean refuseOpenDispatch;

        @Override
        public void correctTransportEdge(int objectId, String verb,
                                         WorldPoint fromTile,
                                         WorldPoint plannedToTile,
                                         WorldPoint actualToTile,
                                         WorldPoint approachTile,
                                         int regionId)
        {
            edgeCorrections.add(new EdgeCorrection(objectId, verb, fromTile,
                plannedToTile, actualToTile));
        }

        @Override
        @Nullable
        public WorldPoint findOpenableNear(WorldPoint blockedTile)
        {
            if (openables.containsKey(blockedTile)) return openables.get(blockedTile);
            int[][] dirs = { {0,1}, {1,0}, {0,-1}, {-1,0},
                             {1,1}, {1,-1}, {-1,1}, {-1,-1} };
            for (int[] d : dirs)
            {
                WorldPoint near = new WorldPoint(
                    blockedTile.getX() + d[0], blockedTile.getY() + d[1], blockedTile.getPlane());
                if (openables.containsKey(near)) return openables.get(near);
            }
            return null;
        }

        @Override
        public boolean dispatchOpen(WorldPoint clickTile)
        {
            if (refuseOpenDispatch) return false;
            if (busy) return false;
            openClicks.add(clickTile);
            busy = true;
            return true;
        }
    }

    private static final class EdgeCorrection
    {
        final int objectId;
        final String verb;
        final WorldPoint fromTile;
        final WorldPoint plannedToTile;
        final WorldPoint actualToTile;
        EdgeCorrection(int objectId, String verb, WorldPoint fromTile,
                       WorldPoint plannedToTile, WorldPoint actualToTile)
        {
            this.objectId = objectId; this.verb = verb;
            this.fromTile = fromTile; this.plannedToTile = plannedToTile;
            this.actualToTile = actualToTile;
        }
    }

    private static final class TransportClick
    {
        final WorldPoint tile;
        final String verb;
        TransportClick(WorldPoint t, String v) { this.tile = t; this.verb = v; }
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
    public void tick_catchupExhausted_signalsReplanInsteadOfFail()
    {
        // Round-2 stabilization: stall recovery exhaustion no longer
        // FAILs the script — it sets wantsReplanFromHere() so the
        // navigator can blacklist the bad tile and replan within its
        // budget. Live observation: bot reached 80% of bank → pen,
        // stalled on a single sentinel tile, gave up despite the rest
        // of the route being walkable.
        FakeEnv env = new FakeEnv();
        env.player = new WorldPoint(3208, 3217, 0);
        V2Executor x = newExecutor(env);
        x.setPath(eastPath(20));

        x.tick();   // initial dispatch
        for (int i = 0; i < (V2Executor.MAX_CATCHUP_CLICKS_PER_LEG + 1) * (V2Executor.STALL_TICKS + 1); i++)
        {
            env.busy = false;
            x.tick();
        }
        assertEquals("status remains RUNNING; navigator handles replan",
            V2Executor.Status.RUNNING, x.status());
        assertTrue("wantsReplanFromHere set so navigator can replan",
            x.wantsReplanFromHere());
        assertEquals("failure reason still tagged CATCHUP_EXHAUSTED for diagnostics",
            V2Executor.FailureReason.CATCHUP_EXHAUSTED, x.lastFailureReason());
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
    public void tick_strictWalkRejection_blacklistsTile_andPicksDifferentSameTick()
    {
        // The dispatcher rejected the press because the engine menu at
        // the click pixel said "Chop down" (tree overlay covering the
        // path tile). V2 must NOT wait for stall — it must blacklist
        // the rejected tile and dispatch a different tile this same
        // tick.
        FakeEnv env = new FakeEnv();
        env.player = new WorldPoint(3208, 3217, 0);
        V2Executor x = newExecutor(env);
        V2Path p = eastPath(20);
        x.setPath(p);
        x.tick();
        assertTrue(env.walkDispatches.size() >= 1);
        WorldPoint rejectedTile = env.walkDispatches.get(0);

        // Dispatcher chain finished, pre-press abort set lastError.
        env.busy = false;
        env.pendingDispatchError = "strict-walk: menu was 'Chop down Tree' — caller must pick a different tile";

        x.tick();

        // Same tick must produce a NEW dispatch (not wait, not catch-up).
        assertEquals("rejection must not block forward progress",
            2, env.walkDispatches.size());
        WorldPoint replacement = env.walkDispatches.get(1);
        assertNotEquals("replacement tile must differ from the rejected one",
            rejectedTile, replacement);
    }

    @Test
    public void tick_repeatedRejections_eventuallyFails_forNavigatorReplan()
    {
        // Cluster of trees: every consecutive canvas pick is rejected
        // by isLeftClickWalk. Bound at MAX_CLICK_REJECTS_PER_LEG so
        // the navigator can replan instead of looping.
        FakeEnv env = new FakeEnv();
        env.player = new WorldPoint(3208, 3217, 0);
        V2Executor x = newExecutor(env);
        x.setPath(eastPath(40));
        x.tick();   // initial dispatch
        for (int i = 0; i < V2Executor.MAX_CLICK_REJECTS_PER_LEG + 1; i++)
        {
            env.busy = false;
            env.pendingDispatchError = "strict-walk: menu was 'Chop down Tree'";
            x.tick();
        }
        assertEquals("after MAX_CLICK_REJECTS_PER_LEG consecutive rejections, FAILED",
            V2Executor.Status.FAILED, x.status());
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

    @Test
    public void tick_persistentNullPlayerLoc_eventuallyFails_PLAYER_LOC_LOST()
    {
        // Spec hard rule: never silently hang. After MAX_NO_PLAYER_LOC_TICKS
        // consecutive null reads, FAIL so V2_WITH_V1_FALLBACK can engage.
        FakeEnv env = new FakeEnv();
        env.player = null;
        V2Executor x = newExecutor(env);
        x.setPath(eastPath(5));
        for (int i = 0; i < V2Executor.MAX_NO_PLAYER_LOC_TICKS + 1; i++)
        {
            env.busy = false;
            x.tick();
        }
        assertEquals("persistent null playerLoc must FAIL after MAX_NO_PLAYER_LOC_TICKS",
            V2Executor.Status.FAILED, x.status());
        assertEquals(V2Executor.FailureReason.PLAYER_LOC_LOST, x.lastFailureReason());
    }

    @Test
    public void tick_noCandidateOnEitherModality_failsWithNO_CANDIDATE_AVAILABLE()
    {
        // Both canvas and minimap return null for many consecutive ticks
        // and no click was ever dispatched (so the stall branch can't
        // fire). Without an explicit cap, this would RUNNING-forever.
        FakeEnv env = new FakeEnv();
        env.player = new WorldPoint(3208, 3217, 0);
        V2Path p = eastPath(20);
        // Reject every canvas + minimap candidate by marking all path
        // tiles unclean and minimap-blocked.
        for (V2Leg leg : p.legs())
        {
            if (leg instanceof V2Leg.Walk w)
            {
                for (WorldPoint t : w.tiles())
                {
                    env.uncleanTiles.add(t);
                    env.minimapBlocked.add(t);
                }
            }
        }
        V2Executor x = newExecutor(env);
        x.setPath(p);
        for (int i = 0; i < V2Executor.MAX_NO_CANDIDATE_TICKS + 1; i++)
        {
            env.busy = false;
            x.tick();
        }
        assertEquals(V2Executor.Status.FAILED, x.status());
        assertEquals(V2Executor.FailureReason.NO_CANDIDATE_AVAILABLE, x.lastFailureReason());
    }

    @Test
    public void tick_stalledStaticCollision_openableBlockerNotFound_round2()
    {
        // Round-2 change: a static-collision stall first probes for an
        // openable on the blocked tile / 1-ring. If none exists, the
        // failure is now OPENABLE_BLOCKER_NOT_FOUND (was
        // STALL_CLASSIFIER_REPLAN in round-1). The classifier-replan
        // path remains for non-static-collision stalls.
        FakeEnv env = new FakeEnv();
        env.player = new WorldPoint(3208, 3217, 0);
        V2Executor x = newExecutor(env);
        V2Path p = eastPath(20);
        x.setPath(p);
        x.tick();
        WorldPoint clicked = env.walkDispatches.get(0);
        env.staticBlocked.add(clicked);
        // No openable wired — gate-detect should fail cleanly.
        for (int i = 0; i < V2Executor.STALL_TICKS + 1; i++)
        {
            env.busy = false;
            x.tick();
        }
        assertEquals(V2Executor.Status.FAILED, x.status());
        assertEquals(V2Executor.FailureReason.OPENABLE_BLOCKER_NOT_FOUND,
            x.lastFailureReason());
    }

    @Test
    public void tick_catchupExhausted_reasonTaggedCATCHUP_EXHAUSTED_andReplanRequested()
    {
        // Same as tick_catchupExhausted_signalsReplanInsteadOfFail but
        // pinning the diagnostic reason tag.
        FakeEnv env = new FakeEnv();
        env.player = new WorldPoint(3208, 3217, 0);
        V2Executor x = newExecutor(env);
        x.setPath(eastPath(20));
        x.tick();
        for (int i = 0; i < (V2Executor.MAX_CATCHUP_CLICKS_PER_LEG + 1) * (V2Executor.STALL_TICKS + 1); i++)
        {
            env.busy = false;
            x.tick();
        }
        assertEquals(V2Executor.Status.RUNNING, x.status());
        assertTrue(x.wantsReplanFromHere());
        assertEquals(V2Executor.FailureReason.CATCHUP_EXHAUSTED, x.lastFailureReason());
    }

    @Test
    public void tick_repeatedRejections_reasonTaggedUNSAFE_CANVAS_CLICK_EXHAUSTED()
    {
        FakeEnv env = new FakeEnv();
        env.player = new WorldPoint(3208, 3217, 0);
        V2Executor x = newExecutor(env);
        x.setPath(eastPath(40));
        x.tick();
        for (int i = 0; i < V2Executor.MAX_CLICK_REJECTS_PER_LEG + 1; i++)
        {
            env.busy = false;
            env.pendingDispatchError = "strict-walk: menu was 'Chop down Tree'";
            x.tick();
        }
        assertEquals(V2Executor.Status.FAILED, x.status());
        assertEquals(V2Executor.FailureReason.UNSAFE_CANVAS_CLICK_EXHAUSTED, x.lastFailureReason());
    }

    // ------------------------------------------------------------------
    // Round-2 transport execution: V2 walks WALK→TRANSPORT→WALK routes
    // for itself. Acceptance tests A–J below cover leg sequencing,
    // approach, click, success, missing object, missing action, timeout,
    // fallback (in HybridNavigatorTest), strict (also there), and the
    // cross-plane guard.
    // ------------------------------------------------------------------

    private static TransportEdge stairs(WorldPoint from, WorldPoint to, String verb)
    {
        return new TransportEdge(from, to, 56230, "Staircase", verb,
            0, 0, "object", from, from.getX() << 8, 1, 0L, 0L);
    }

    /** Composite WALK p0 → TRANSPORT → WALK p2 path matching the
     *  Lumbridge-castle staircase shape from the live failure log. */
    private static V2Path stairRoute()
    {
        List<WorldPoint> w0 = new ArrayList<>();
        for (int i = 0; i < 5; i++) w0.add(new WorldPoint(3204 + i, 3207, 0));
        // last walk tile = approach tile of the staircase.
        WorldPoint approach = w0.get(w0.size() - 1);
        TransportEdge edge = new TransportEdge(approach,
            new WorldPoint(3204, 3207, 2), 56230, "Staircase", "Top-floor",
            0, 0, "object", approach, 12850, 1, 0L, 0L);
        List<WorldPoint> w2 = new ArrayList<>();
        for (int i = 0; i < 5; i++) w2.add(new WorldPoint(3204 - i, 3207, 2));
        return new V2Path(List.of(
            new V2Leg.Walk(12850, w0),
            new V2Leg.Transport(edge),
            new V2Leg.Walk(12850, w2)
        ), 100);
    }

    /** A. Leg sequencing: while player is on plane 0, executor must
     *  not dispatch any tile on plane 2 (the second walk leg). */
    @Test
    public void transport_legSequencing_noPlane2DispatchWhilePlayerOnPlane0()
    {
        FakeEnv env = new FakeEnv();
        env.player = new WorldPoint(3204, 3207, 0);  // start of plane-0 walk
        V2Executor x = newExecutor(env);
        x.setPath(stairRoute());
        assertEquals(V2Executor.Status.RUNNING, x.status());

        // Drive several ticks WITHOUT advancing the player — only the
        // first walk leg should be eligible for clicks.
        for (int i = 0; i < 10; i++)
        {
            env.busy = false;
            x.tick();
            if (x.status() != V2Executor.Status.RUNNING) break;
        }
        for (WorldPoint w : env.walkDispatches)
        {
            assertEquals("walk dispatch on plane other than player's plane (sequencing leak)",
                0, w.getPlane());
        }
        for (WorldPoint w : env.minimapDispatches)
        {
            assertEquals("minimap dispatch on plane other than player's plane (sequencing leak)",
                0, w.getPlane());
        }
        assertTrue("at least one walk dispatch on plane 0",
            !env.walkDispatches.isEmpty() || !env.minimapDispatches.isEmpty());
    }

    /** B. Transport approach: when the player is far from approachTile,
     *  executor walks toward approach BEFORE attempting the verb-click. */
    @Test
    public void transport_approachWalk_beforeClick()
    {
        FakeEnv env = new FakeEnv();
        // Place player at start of walk leg, approach is at end (4 tiles east).
        env.player = new WorldPoint(3204, 3207, 0);
        // Wire a live object on the approach tile so click is possible
        // once we get there.
        env.transportObjects.put(new WorldPoint(3208, 3207, 0),
            new WorldPoint(3208, 3207, 0));
        V2Executor x = newExecutor(env);
        x.setPath(stairRoute());

        // First few ticks: should walk on the WALK leg.
        env.busy = false; x.tick();
        assertTrue("first tick must be a walk dispatch (still on WALK leg)",
            !env.walkDispatches.isEmpty());
        assertTrue("must not click transport before approach reached",
            env.transportClicks.isEmpty());
    }

    /** C. Transport click: with player adjacent to approach and live
     *  object present, executor dispatches the verb-click. */
    @Test
    public void transport_dispatchesVerbClick_whenAdjacent()
    {
        FakeEnv env = new FakeEnv();
        // Skip walk leg by making the executor see the player at the end of
        // the WALK leg already — the leg advances on first tick.
        env.player = new WorldPoint(3208, 3207, 0);
        env.transportObjects.put(new WorldPoint(3208, 3207, 0),
            new WorldPoint(3208, 3207, 0));
        V2Executor x = newExecutor(env);
        x.setPath(stairRoute());

        // Tick 1: walk leg should advance (player is at end tile).
        env.busy = false;
        x.tick();
        // Tick 2: transport leg — player adjacent, click dispatched.
        env.busy = false;
        x.tick();

        assertEquals("transport leg should issue exactly one verb-click",
            1, env.transportClicks.size());
        assertEquals("Top-floor", env.transportClicks.get(0).verb);
        assertEquals(new WorldPoint(3208, 3207, 0), env.transportClicks.get(0).tile);
        assertTrue("executor should be in WAITING_FOR_TRANSPORT after click",
            x.isWaitingForTransport());
    }

    /** D. Transport success: after the player's plane changes to the
     *  destination plane, executor advances to the next WALK leg. */
    @Test
    public void transport_advancesLeg_whenPlayerReachesDestPlane()
    {
        FakeEnv env = new FakeEnv();
        env.player = new WorldPoint(3208, 3207, 0);
        env.transportObjects.put(new WorldPoint(3208, 3207, 0),
            new WorldPoint(3208, 3207, 0));
        V2Executor x = newExecutor(env);
        x.setPath(stairRoute());

        // Run through walk-advance + transport-click.
        for (int i = 0; i < 3; i++) { env.busy = false; x.tick(); }
        assertTrue("transport must have been clicked",
            !env.transportClicks.isEmpty());
        assertEquals("legIdx should be 1 (transport leg)", 1, x.currentLegIndex());

        // Simulate plane change — player teleports to plane 2 destination tile.
        env.player = new WorldPoint(3204, 3207, 2);
        env.busy = false;
        x.tick();
        assertEquals("after plane change, legIdx should advance to walk-leg 2",
            2, x.currentLegIndex());
        assertTrue("transport flag must be cleared on advance",
            !x.isWaitingForTransport());
        // Subsequent tick should produce a WALK dispatch on plane 2 (or arrive).
        env.busy = false;
        x.tick();
        for (WorldPoint w : env.walkDispatches)
        {
            // Once the transport completed, plane-2 dispatches are now valid.
            assertTrue("dispatch plane should be 0 (pre-transport) or 2 (post-transport)",
                w.getPlane() == 0 || w.getPlane() == 2);
        }
    }

    /** E. Missing object: live object not on fromTile or 1-ring. */
    @Test
    public void transport_missingObject_failsWithTRANSPORT_OBJECT_NOT_FOUND()
    {
        FakeEnv env = new FakeEnv();
        env.player = new WorldPoint(3208, 3207, 0);
        // No transportObjects entry → resolve returns null.
        V2Executor x = newExecutor(env);
        x.setPath(stairRoute());

        for (int i = 0; i < 3; i++) { env.busy = false; x.tick(); }
        assertEquals(V2Executor.Status.FAILED, x.status());
        assertEquals(V2Executor.FailureReason.TRANSPORT_OBJECT_NOT_FOUND,
            x.lastFailureReason());
        assertEquals("no transport click should have been dispatched",
            0, env.transportClicks.size());
    }

    /** F. Missing action: the resolver mapping intentionally returns null
     *  for the recorded fromTile — same channel as missing-object since
     *  TransportResolver collapses both into Match.failure(); test confirms
     *  the FAILED reason is the canonical not-found tag. */
    @Test
    public void transport_actionAbsent_failsWithObjectNotFound()
    {
        FakeEnv env = new FakeEnv();
        env.player = new WorldPoint(3208, 3207, 0);
        // Map fromTile to null — resolver explicitly says "no verb here".
        env.transportObjects.put(new WorldPoint(3208, 3207, 0), null);
        V2Executor x = newExecutor(env);
        x.setPath(stairRoute());

        for (int i = 0; i < 3; i++) { env.busy = false; x.tick(); }
        assertEquals(V2Executor.Status.FAILED, x.status());
        assertEquals(V2Executor.FailureReason.TRANSPORT_OBJECT_NOT_FOUND,
            x.lastFailureReason());
    }

    /** G. Timeout: clicked but plane never changes. */
    @Test
    public void transport_timeout_failsWithTRANSPORT_TIMEOUT()
    {
        FakeEnv env = new FakeEnv();
        env.player = new WorldPoint(3208, 3207, 0);
        env.transportObjects.put(new WorldPoint(3208, 3207, 0),
            new WorldPoint(3208, 3207, 0));
        V2Executor x = newExecutor(env);
        x.setPath(stairRoute());

        // Drive through walk-advance + click + timeout polling.
        for (int i = 0; i < V2Executor.TRANSPORT_TIMEOUT_TICKS + 5; i++)
        {
            env.busy = false;
            x.tick();
            if (x.status() == V2Executor.Status.FAILED) break;
        }
        assertEquals(V2Executor.Status.FAILED, x.status());
        assertEquals(V2Executor.FailureReason.TRANSPORT_TIMEOUT,
            x.lastFailureReason());
    }

    /** Click-failed (dispatcher refused). */
    @Test
    public void transport_clickRefused_failsWithTRANSPORT_CLICK_FAILED()
    {
        FakeEnv env = new FakeEnv();
        env.player = new WorldPoint(3208, 3207, 0);
        env.transportObjects.put(new WorldPoint(3208, 3207, 0),
            new WorldPoint(3208, 3207, 0));
        env.refuseTransportDispatch = true;
        V2Executor x = newExecutor(env);
        x.setPath(stairRoute());

        for (int i = 0; i < 3; i++) { env.busy = false; x.tick(); }
        assertEquals(V2Executor.Status.FAILED, x.status());
        assertEquals(V2Executor.FailureReason.TRANSPORT_CLICK_FAILED,
            x.lastFailureReason());
    }

    /** Plane mismatch: player is on neither from nor to plane.
     *  Round-2: this is now reported as EDGE_DIRECTION_MISSING since
     *  the executor refuses to drive an edge whose from-plane doesn't
     *  match the player's plane. Both reasons mean "I'm not on the
     *  right side of the staircase to use this edge". */
    @Test
    public void transport_playerOnUnexpectedPlane_failsCleanly()
    {
        FakeEnv env = new FakeEnv();
        // Player on plane 1 — neither from (0) nor to (2).
        env.player = new WorldPoint(3208, 3207, 1);
        V2Executor x = newExecutor(env);
        TransportEdge edge = new TransportEdge(
            new WorldPoint(3208, 3207, 0), new WorldPoint(3204, 3207, 2),
            56230, "Staircase", "Top-floor", 0, 0, "object",
            new WorldPoint(3208, 3207, 0), 12850, 1, 0L, 0L);
        x.setPath(new V2Path(List.of(new V2Leg.Transport(edge)), 10));

        env.busy = false;
        x.tick();
        assertEquals(V2Executor.Status.FAILED, x.status());
        V2Executor.FailureReason r = x.lastFailureReason();
        assertTrue("expected PLANE_MISMATCH or EDGE_DIRECTION_MISSING, got " + r,
            r == V2Executor.FailureReason.TRANSPORT_PLANE_MISMATCH
            || r == V2Executor.FailureReason.TRANSPORT_EDGE_DIRECTION_MISSING);
    }

    /** J. Cross-plane walk candidate guard remains intact for transport
     *  routes — no walk dispatch should target a tile on a plane the
     *  player is not currently on. */
    @Test
    public void transport_route_walkDispatchPlaneAlwaysMatchesPlayer()
    {
        FakeEnv env = new FakeEnv();
        env.player = new WorldPoint(3204, 3207, 0);
        env.transportObjects.put(new WorldPoint(3208, 3207, 0),
            new WorldPoint(3208, 3207, 0));
        V2Executor x = newExecutor(env);
        x.setPath(stairRoute());

        for (int i = 0; i < 8; i++)
        {
            env.busy = false;
            x.tick();
            if (x.status() != V2Executor.Status.RUNNING) break;
        }
        for (WorldPoint w : env.walkDispatches)
        {
            assertEquals("walk dispatch must match player plane",
                env.player.getPlane(), w.getPlane());
        }
    }

    /** Empty path is IDLE, not FAILED — the failure reason must stay null. */
    @Test
    public void setPath_emptyPath_noFailureReason()
    {
        FakeEnv env = new FakeEnv();
        env.player = new WorldPoint(0, 0, 0);
        V2Executor x = newExecutor(env);
        x.setPath(V2Path.EMPTY);
        assertNull("empty path is IDLE, no reason", x.lastFailureReason());
    }

    // ------------------------------------------------------------------
    // Round-2 stabilization tests: progress monotonicity, transport
    // result correction, direction validation, openable blockers.
    // ------------------------------------------------------------------

    /** A1. Progress cursor: backward candidates rejected. The picker
     *  is called repeatedly while the player walks forward; no dispatch
     *  may target a tile with idx <= the high-water progress index. */
    @Test
    public void walk_progressCursor_neverDispatchesBackward()
    {
        FakeEnv env = new FakeEnv();
        V2Executor x = newExecutor(env);
        V2Path p = eastPath(20);
        env.player = ((V2Leg.Walk) p.legs().get(0)).tiles().get(0);
        x.setPath(p);

        List<WorldPoint> tiles = ((V2Leg.Walk) p.legs().get(0)).tiles();
        // Walk the player forward to idx 8.
        for (int i = 0; i <= 8; i++)
        {
            env.player = tiles.get(i);
            env.busy = false;
            x.tick();
        }
        // Reset the dispatch list so we only inspect picks AFTER reaching idx 8.
        env.walkDispatches.clear();
        // Place the player back at idx 5 (simulate getting bumped back).
        env.player = tiles.get(5);
        for (int i = 0; i < 5; i++)
        {
            env.busy = false;
            x.tick();
        }
        // No dispatch should have idx <= 8 (the high-water mark).
        for (WorldPoint w : env.walkDispatches)
        {
            int idx = tiles.indexOf(w);
            assertTrue("dispatch idx=" + idx + " is behind high-water (8)", idx > 8);
        }
    }

    /** A2. No-forward-candidate scenario: player mid-leg, every tile in
     *  the leg is blocked from canvas + minimap. Executor must fail with
     *  NO_FORWARD_CANDIDATE / NO_CANDIDATE_AVAILABLE rather than spin. */
    @Test
    public void walk_noForwardCandidate_failsCleanly()
    {
        FakeEnv env = new FakeEnv();
        V2Executor x = newExecutor(env);
        V2Path p = eastPath(20);
        List<WorldPoint> tiles = ((V2Leg.Walk) p.legs().get(0)).tiles();
        env.player = tiles.get(5);   // mid-leg, far from end (idx 19)
        for (WorldPoint t : tiles) { env.uncleanTiles.add(t); env.minimapBlocked.add(t); }
        x.setPath(p);

        for (int i = 0; i < V2Executor.MAX_NO_CANDIDATE_TICKS + 2; i++)
        {
            env.busy = false;
            x.tick();
            if (x.status() == V2Executor.Status.FAILED) break;
        }
        assertEquals(V2Executor.Status.FAILED, x.status());
        V2Executor.FailureReason r = x.lastFailureReason();
        assertTrue("expected NO_FORWARD_CANDIDATE or NO_CANDIDATE_AVAILABLE, got " + r,
            r == V2Executor.FailureReason.NO_FORWARD_CANDIDATE
            || r == V2Executor.FailureReason.NO_CANDIDATE_AVAILABLE);
    }

    /** B1. Transport result mismatch: planned p2→p0, actual p2→p1.
     *  Executor must call correctTransportEdge AND set wantsReplanFromHere. */
    @Test
    public void transport_resultMismatch_correctsEdgeAndRequestsReplan()
    {
        FakeEnv env = new FakeEnv();
        // Player on p2, click should land on p0 per planned edge but the
        // simulation will move them to p1 — the mismatch case.
        WorldPoint clickTile = new WorldPoint(3206, 3229, 2);
        env.player = clickTile;
        env.transportObjects.put(clickTile, clickTile);

        V2Executor x = newExecutor(env);
        TransportEdge wrongEdge = new TransportEdge(
            clickTile,                           // fromTile p2
            new WorldPoint(3205, 3228, 0),       // plannedTo p0 (WRONG)
            56231, "Staircase", "Climb-down",
            0, 0, "object", clickTile,
            12850, 1, 0L, 0L);
        x.setPath(new V2Path(List.of(new V2Leg.Transport(wrongEdge)), 10));

        // Tick 1: dispatch the click.
        env.busy = false;
        x.tick();
        assertTrue("transport must be clicked", !env.transportClicks.isEmpty());

        // Simulate plane change to p1 (mismatch).
        env.player = new WorldPoint(3206, 3229, 1);
        env.busy = false;
        x.tick();

        assertTrue("executor must request replan from here", x.wantsReplanFromHere());
        assertEquals("failure reason logged as TRANSPORT_RESULT_MISMATCH",
            V2Executor.FailureReason.TRANSPORT_RESULT_MISMATCH, x.lastFailureReason());
        assertEquals("edge correction must be recorded once",
            1, env.edgeCorrections.size());
        EdgeCorrection ec = env.edgeCorrections.get(0);
        assertEquals(56231, ec.objectId);
        assertEquals("Climb-down", ec.verb);
        assertEquals(clickTile, ec.fromTile);
        assertEquals(new WorldPoint(3205, 3228, 0), ec.plannedToTile);
        assertEquals(new WorldPoint(3206, 3229, 1), ec.actualToTile);
    }

    /** C. Direction-correct edge: an edge whose to-plane doesn't match
     *  the next WALK leg's plane is refused at execute time (defensive
     *  guard against a planner emitting a wrong-direction transport). */
    @Test
    public void transport_directionMismatch_failsWithEDGE_DIRECTION_MISSING()
    {
        FakeEnv env = new FakeEnv();
        env.player = new WorldPoint(3208, 3207, 0);
        V2Executor x = newExecutor(env);
        // Edge says fromPlane=0, toPlane=2. Next walk leg starts at plane=1
        // (mismatch — should be plane=2 to match the edge.toTile).
        TransportEdge edge = new TransportEdge(
            new WorldPoint(3208, 3207, 0),
            new WorldPoint(3204, 3207, 2), 56230, "Staircase", "Top-floor",
            0, 0, "object", new WorldPoint(3208, 3207, 0),
            12850, 1, 0L, 0L);
        V2Path p = new V2Path(List.of(
            new V2Leg.Transport(edge),
            new V2Leg.Walk(0, List.of(new WorldPoint(3204, 3207, 1)))   // wrong plane
        ), 10);
        x.setPath(p);
        env.busy = false;
        x.tick();
        assertEquals(V2Executor.Status.FAILED, x.status());
        assertEquals(V2Executor.FailureReason.TRANSPORT_EDGE_DIRECTION_MISSING,
            x.lastFailureReason());
    }

    /** D1. Openable blocker: snapshot says walkable, live says blocked,
     *  Open verb on adjacent tile → executor clicks Open. */
    @Test
    public void walk_openableBlocker_clicksOpen_whenAdjacent()
    {
        FakeEnv env = new FakeEnv();
        V2Path p = eastPath(20);
        List<WorldPoint> tiles = ((V2Leg.Walk) p.legs().get(0)).tiles();
        env.player = tiles.get(0);
        V2Executor x = newExecutor(env);
        x.setPath(p);

        // Tick 1: dispatch.
        env.busy = false;
        x.tick();
        assertTrue(!env.walkDispatches.isEmpty());
        WorldPoint clicked = env.walkDispatches.get(0);
        // Now mark the clicked tile as static-collision-blocked and
        // wire an openable on it (gate on the same tile).
        env.staticBlocked.add(clicked);
        env.openables.put(clicked, clicked);

        // Stall — need >= STALL_TICKS ticks of player static.
        for (int i = 0; i < V2Executor.STALL_TICKS + 1; i++)
        {
            env.busy = false;
            x.tick();
        }

        assertTrue("Open verb-click must have been dispatched at the gate",
            !env.openClicks.isEmpty());
    }

    /** D2. Openable blocker timeout: Open clicked but live collision
     *  never flips walkable. */
    @Test
    public void walk_openableBlocker_timeout()
    {
        FakeEnv env = new FakeEnv();
        V2Path p = eastPath(20);
        List<WorldPoint> tiles = ((V2Leg.Walk) p.legs().get(0)).tiles();
        env.player = tiles.get(0);
        V2Executor x = newExecutor(env);
        x.setPath(p);

        env.busy = false;
        x.tick();
        WorldPoint clicked = env.walkDispatches.get(0);
        env.staticBlocked.add(clicked);
        env.openables.put(clicked, clicked);

        for (int i = 0; i < V2Executor.STALL_TICKS + 1; i++)
        {
            env.busy = false;
            x.tick();
        }
        // Stall more after the Open click — player still blocked.
        for (int i = 0; i < V2Executor.OPENABLE_TIMEOUT_TICKS + 2; i++)
        {
            env.busy = false;
            x.tick();
        }
        assertEquals(V2Executor.Status.FAILED, x.status());
        assertEquals(V2Executor.FailureReason.OPENABLE_BLOCKER_TIMEOUT,
            x.lastFailureReason());
    }

    /** D3. Openable blocker: no openable found → fails clearly. */
    @Test
    public void walk_openableBlocker_notFound_fails()
    {
        FakeEnv env = new FakeEnv();
        V2Path p = eastPath(20);
        List<WorldPoint> tiles = ((V2Leg.Walk) p.legs().get(0)).tiles();
        env.player = tiles.get(0);
        V2Executor x = newExecutor(env);
        x.setPath(p);

        env.busy = false;
        x.tick();
        WorldPoint clicked = env.walkDispatches.get(0);
        env.staticBlocked.add(clicked);
        // No openable wired.

        for (int i = 0; i < V2Executor.STALL_TICKS + 1; i++)
        {
            env.busy = false;
            x.tick();
        }
        assertEquals(V2Executor.Status.FAILED, x.status());
        assertEquals(V2Executor.FailureReason.OPENABLE_BLOCKER_NOT_FOUND,
            x.lastFailureReason());
    }

    /** D4. Openable blocker recovery: passability flips back walkable
     *  after the Open click → executor resumes the walk leg. */
    @Test
    public void walk_openableBlocker_passabilityRestored_resumesWalk()
    {
        FakeEnv env = new FakeEnv();
        V2Path p = eastPath(20);
        List<WorldPoint> tiles = ((V2Leg.Walk) p.legs().get(0)).tiles();
        env.player = tiles.get(0);
        V2Executor x = newExecutor(env);
        x.setPath(p);

        env.busy = false;
        x.tick();
        WorldPoint clicked = env.walkDispatches.get(0);
        env.staticBlocked.add(clicked);
        env.openables.put(clicked, clicked);

        for (int i = 0; i < V2Executor.STALL_TICKS + 1; i++)
        {
            env.busy = false;
            x.tick();
        }
        assertTrue(!env.openClicks.isEmpty());
        // Now flip the gate to walkable.
        env.staticBlocked.remove(clicked);
        env.busy = false;
        x.tick();
        assertEquals("status remains RUNNING after passability restored",
            V2Executor.Status.RUNNING, x.status());
    }

    /** Round-2 regression guard: setPath of a transport-bearing route
     *  must NOT immediately FAIL with TRANSPORT_EXECUTOR_MISSING (the
     *  round-1 reject path). It enters RUNNING with no failure. */
    @Test
    public void setPath_withTransportLeg_acceptedRunning_noTransportExecutorMissing()
    {
        FakeEnv env = new FakeEnv();
        env.player = new WorldPoint(3208, 3217, 0);
        V2Executor x = newExecutor(env);

        TransportEdge edge = stairs(new WorldPoint(3209, 3217, 0),
            new WorldPoint(3210, 3220, 2), "Climb-up");
        V2Path p = new V2Path(List.of(
            new V2Leg.Walk(0, List.of(new WorldPoint(3208, 3217, 0),
                                      new WorldPoint(3209, 3217, 0))),
            new V2Leg.Transport(edge),
            new V2Leg.Walk(2, List.of(new WorldPoint(3210, 3220, 2)))
        ), 100);
        x.setPath(p);

        assertEquals("transport route must enter RUNNING",
            V2Executor.Status.RUNNING, x.status());
        assertNull("no failure reason on accepted route", x.lastFailureReason());
    }

    @Test
    public void tick_crossPlaneCandidate_neverDispatched_repeatedFailures_failTheLeg()
    {
        // Defense-in-depth plane guard: synthesize a Walk leg whose tiles
        // are on plane=2 while the player is on plane=0. This shape can
        // only arise from a malformed route or planner bug, but the
        // executor must NEVER click a tile on a plane the player can't
        // reach by walking — and after repeated cross-plane picks it
        // must FAIL the leg instead of stalling silently.
        FakeEnv env = new FakeEnv();
        env.player = new WorldPoint(3208, 3217, 0);   // plane 0
        V2Executor x = newExecutor(env);

        // 30 walk tiles all on plane=2 — every candidate is cross-plane.
        List<WorldPoint> badPlane = new ArrayList<>();
        for (int i = 0; i < 30; i++) badPlane.add(new WorldPoint(3208 + i, 3217, 2));
        V2Path p = new V2Path(List.of(new V2Leg.Walk(0, badPlane)), 29);
        x.setPath(p);

        // Drive enough ticks that the buggy code path would have produced
        // off-plane dispatches.
        for (int i = 0; i < 30; i++) {
            env.busy = false;
            x.tick();
            if (x.status() == V2Executor.Status.FAILED) break;
        }

        for (WorldPoint w : env.walkDispatches) {
            assertEquals("cross-plane walk dispatch leaked through guard",
                0, w.getPlane());
        }
        for (WorldPoint w : env.minimapDispatches) {
            assertEquals("cross-plane minimap dispatch leaked through guard",
                0, w.getPlane());
        }
        assertEquals("repeated cross-plane candidates must FAIL the leg",
            V2Executor.Status.FAILED, x.status());
        assertEquals("FailureReason for cross-plane exhaustion",
            V2Executor.FailureReason.CROSS_PLANE_CANDIDATES_EXHAUSTED,
            x.lastFailureReason());
    }
}
