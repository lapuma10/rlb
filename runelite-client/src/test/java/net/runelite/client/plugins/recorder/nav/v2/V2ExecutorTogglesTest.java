package net.runelite.client.plugins.recorder.nav.v2;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import javax.annotation.Nullable;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Phase-13 sub-step toggle tests. Each click-improvement modality has
 *  a kill switch on {@link V2Executor.Toggles}; flipping one off must
 *  produce the documented degenerate behavior without breaking the
 *  others. */
public class V2ExecutorTogglesTest
{
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

    private static V2Path eastPath(int n)
    {
        List<WorldPoint> tiles = new ArrayList<>();
        for (int i = 0; i < n; i++) tiles.add(new WorldPoint(3208 + i, 3217, 0));
        return new V2Path(List.<V2Leg>of(new V2Leg.Walk(0, tiles)), n - 1);
    }

    private static V2Executor exec(FakeEnv env, V2Executor.Toggles t)
    {
        return new V2Executor(env, new CanvasTilePicker(),
            new InvalidationClassifier(), new Random(7), t);
    }

    @Test
    public void variableDistanceOff_picksShortBucketOnly()
    {
        FakeEnv env = new FakeEnv();
        env.player = new WorldPoint(3208, 3217, 0);
        V2Executor x = exec(env, new V2Executor.Toggles()
        {
            @Override public boolean variableDistance() { return false; }
        });
        x.setPath(eastPath(20));
        x.tick();
        assertEquals("variable distance OFF still produces a dispatch", 1, env.walkDispatches.size());
        // Short bucket = 2..3 ahead.
        WorldPoint pick = env.walkDispatches.get(0);
        int delta = pick.getX() - 3208;
        assertTrue("variable distance OFF must pick from short bucket (2..3); got delta=" + delta,
            delta >= CanvasTilePicker.SHORT_MIN && delta <= CanvasTilePicker.SHORT_MAX);
    }

    @Test
    public void variableDistanceDefault_canPickLongBucket()
    {
        // Default toggles produce variable distance; over many runs we
        // should see at least one mid+ pick. Sanity check that the
        // default path still exercises long buckets.
        FakeEnv env = new FakeEnv();
        env.player = new WorldPoint(3208, 3217, 0);
        boolean sawNonShort = false;
        for (int seed = 0; seed < 30 && !sawNonShort; seed++)
        {
            FakeEnv e2 = new FakeEnv();
            e2.player = env.player;
            V2Executor x = new V2Executor(e2, new CanvasTilePicker(),
                new InvalidationClassifier(), new Random(seed));   // default toggles
            x.setPath(eastPath(20));
            x.tick();
            int delta = e2.walkDispatches.get(0).getX() - 3208;
            if (delta > CanvasTilePicker.SHORT_MAX) sawNonShort = true;
        }
        assertTrue("variable distance default must pick beyond short over 30 seeds", sawNonShort);
    }

    @Test
    public void minimapModalityOff_canvasExhausted_failsImmediately()
    {
        FakeEnv env = new FakeEnv();
        env.player = new WorldPoint(3208, 3217, 0);
        V2Path p = eastPath(20);
        // Block every path tile from the canvas filter.
        for (V2Leg leg : p.legs())
            if (leg instanceof V2Leg.Walk w) for (WorldPoint t : w.tiles()) env.uncleanTiles.add(t);

        V2Executor x = exec(env, new V2Executor.Toggles()
        {
            @Override public boolean minimapModality() { return false; }
        });
        x.setPath(p);
        x.tick();

        assertEquals("minimap OFF + canvas exhausted → no minimap dispatch",
            0, env.minimapDispatches.size());
        assertEquals("status FAILED so navigator can replan",
            V2Executor.Status.FAILED, x.status());
        assertEquals(V2Executor.FailureReason.UNSAFE_CANVAS_CLICK_EXHAUSTED, x.lastFailureReason());
    }

    @Test
    public void minimapModalityDefault_canvasExhausted_fallsToMinimap()
    {
        FakeEnv env = new FakeEnv();
        env.player = new WorldPoint(3208, 3217, 0);
        V2Path p = eastPath(20);
        for (V2Leg leg : p.legs())
            if (leg instanceof V2Leg.Walk w) for (WorldPoint t : w.tiles()) env.uncleanTiles.add(t);

        V2Executor x = new V2Executor(env, new CanvasTilePicker(),
            new InvalidationClassifier(), new Random(42));  // default toggles
        x.setPath(p);
        x.tick();
        assertEquals("default minimap-on falls through to minimap",
            1, env.minimapDispatches.size());
    }

    @Test
    public void catchupClicksOff_stalledLeg_failsOnFirstStallEvent()
    {
        FakeEnv env = new FakeEnv();
        env.player = new WorldPoint(3208, 3217, 0);
        V2Executor x = exec(env, new V2Executor.Toggles()
        {
            @Override public boolean catchupClicks() { return false; }
        });
        x.setPath(eastPath(20));
        x.tick();   // initial dispatch
        for (int i = 0; i < V2Executor.STALL_TICKS + 1; i++)
        {
            env.busy = false;
            x.tick();
        }
        assertEquals("catchup OFF FAILs immediately on first stall classification",
            V2Executor.Status.FAILED, x.status());
        assertEquals(V2Executor.FailureReason.CATCHUP_EXHAUSTED, x.lastFailureReason());
        assertEquals("only the initial dispatch — no catch-up re-clicks",
            1, env.walkDispatches.size());
    }

    @Test
    public void allTogglesOff_executorStillFunctional_butFailsLeg()
    {
        // Sanity: with everything off, V2 still doesn't crash; it just
        // fails the leg quickly so the navigator falls back / replans.
        FakeEnv env = new FakeEnv();
        env.player = new WorldPoint(3208, 3217, 0);
        V2Path p = eastPath(20);
        for (V2Leg leg : p.legs())
            if (leg instanceof V2Leg.Walk w) for (WorldPoint t : w.tiles()) env.uncleanTiles.add(t);
        V2Executor x = exec(env, new V2Executor.Toggles()
        {
            @Override public boolean variableDistance() { return false; }
            @Override public boolean minimapModality() { return false; }
            @Override public boolean catchupClicks() { return false; }
        });
        x.setPath(p);
        x.tick();
        assertEquals(V2Executor.Status.FAILED, x.status());
        assertNotNull(x.lastFailureReason());
    }
}
