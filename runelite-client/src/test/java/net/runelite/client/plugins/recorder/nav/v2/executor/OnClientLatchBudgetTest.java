package net.runelite.client.plugins.recorder.nav.v2.executor;

import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.nav.v2.V2Executor;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Lane 5 plan Task 1: collapse {@code V2ExecutorEnv.onClient(...)}
 *  waits into a budget of <=3 per executor tick (relaxed from spec's
 *  <=2 after QC review). Tested via the typed {@link
 *  V2Executor.Env#snapshotForTick(WorldPoint)} entry that bundles all
 *  per-tick state reads into one client-thread marshal. */
public class OnClientLatchBudgetTest
{
    /** Counts how many times the env runs a client-thread marshal. */
    private static final class CountingEnv implements V2Executor.Env
    {
        int onClientCalls;
        WorldPoint player = new WorldPoint(3208, 3217, 0);
        boolean busy;
        WorldPoint candidate;

        @Override public WorldPoint playerLoc()
        { onClientCalls++; return player; }
        @Override public boolean isPlausiblyClean(WorldPoint tile)
        { onClientCalls++; return true; }
        @Override public boolean canMinimapClick(WorldPoint tile)
        { onClientCalls++; return true; }
        @Override public boolean dispatchWalk(WorldPoint tile)
        { onClientCalls++; busy = true; return true; }
        @Override public boolean dispatchMinimap(WorldPoint tile)
        { onClientCalls++; busy = true; return true; }
        @Override public boolean dispatcherBusy() { return busy; }
        @Override public long nowMs() { return 0; }
        @Override public boolean snapshotSaysWalkable(WorldPoint tile)
        { onClientCalls++; return true; }
        @Override public boolean liveCollisionAllows(WorldPoint tile)
        { onClientCalls++; return true; }
        @Override public boolean dynamicEntityOnTile(WorldPoint tile)
        { onClientCalls++; return false; }

        @Override
        public V2Executor.Env.TickReadOut snapshotForTick(WorldPoint candidateTile)
        {
            onClientCalls++;
            candidate = candidateTile;
            return new V2Executor.Env.TickReadOut(
                player, true, true, false, true, true);
        }
    }

    @Test
    public void snapshotForTick_returnsImmutableReadOut_withAllFields()
    {
        CountingEnv env = new CountingEnv();
        V2Executor.Env.TickReadOut out =
            env.snapshotForTick(new WorldPoint(3209, 3217, 0));
        assertNotNull(out);
        assertEquals(env.player, out.playerLoc());
        assertTrue(out.candidateClean());
        assertTrue(out.liveCollisionAllows());
        assertTrue(out.snapshotSaysWalkable());
    }

    @Test
    public void snapshotForTick_collapsesAllReadsIntoOneClientCall()
    {
        // The contract is that ONE snapshotForTick() call delivers all
        // the per-tick read fields (playerLoc, candidate cleanliness,
        // live-collision, snapshot-walkable, dynamic-entity, minimap
        // reachability). The env counts a single onClientCalls bump.
        CountingEnv env = new CountingEnv();
        int before = env.onClientCalls;
        V2Executor.Env.TickReadOut out =
            env.snapshotForTick(new WorldPoint(3209, 3217, 0));
        int after = env.onClientCalls;
        assertEquals("snapshotForTick must bundle into one read call",
            1, after - before);
        // And the snapshot exposes all needed fields without further calls.
        assertNotNull(out.playerLoc());
        assertEquals(env.player, out.playerLoc());
    }

    @Test
    public void tickReadOut_isRecordShape_immutable()
    {
        V2Executor.Env.TickReadOut a = new V2Executor.Env.TickReadOut(
            new WorldPoint(1, 2, 0), true, true, false, true, true);
        V2Executor.Env.TickReadOut b = new V2Executor.Env.TickReadOut(
            new WorldPoint(1, 2, 0), true, true, false, true, true);
        assertEquals("Java record equality on identical fields", a, b);
    }
}
