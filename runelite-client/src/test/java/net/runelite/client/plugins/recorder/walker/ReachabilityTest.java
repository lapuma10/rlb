package net.runelite.client.plugins.recorder.walker;

import java.util.List;
import net.runelite.api.CollisionData;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Pure-logic tests for {@link Reachability}. We construct a synthetic
 * {@link WorldView} with a 32x32 collision grid (tiles at world (3200, 3200,
 * plane=0)). Each test sets specific flags to force walls / chokepoints,
 * then asserts that BFS expansion respects them.
 */
public class ReachabilityTest
{
    private static final int BASE_X = 3200;
    private static final int BASE_Y = 3200;
    private static final int SIZE = 32;
    private static final int PLANE = 0;

    /** Build a {@link WorldView} mock backed by a {@code SIZE x SIZE} flag
     *  grid. The returned grid is mutable so each test can write the cells
     *  it cares about — defaults to all zeros (every tile open). */
    private static FlagGrid buildGrid()
    {
        int[][] flags = new int[104][104];
        WorldView wv = mock(WorldView.class);
        when(wv.getBaseX()).thenReturn(BASE_X);
        when(wv.getBaseY()).thenReturn(BASE_Y);
        when(wv.getSizeX()).thenReturn(104);
        when(wv.getSizeY()).thenReturn(104);

        CollisionData cd = mock(CollisionData.class);
        when(cd.getFlags()).thenReturn(flags);
        when(wv.getCollisionMaps()).thenReturn(new CollisionData[]{cd, null, null, null});
        return new FlagGrid(wv, flags);
    }

    /** Convert world (x, y) → scene (sceneX, sceneY) on the grid. */
    private static int sx(int wx) { return wx - BASE_X; }
    private static int sy(int wy) { return wy - BASE_Y; }

    private static WorldPoint wp(int x, int y) { return new WorldPoint(x, y, PLANE); }

    private static final class FlagGrid
    {
        final WorldView wv;
        final int[][] flags;
        FlagGrid(WorldView wv, int[][] flags) { this.wv = wv; this.flags = flags; }
        void block(int wx, int wy, int flag)
        {
            flags[sx(wx)][sy(wy)] |= flag;
        }
    }

    @Test
    public void emptyGridReachesAllTilesWithinDepth()
    {
        FlagGrid g = buildGrid();
        Reachability.ReachabilityMap m =
            Reachability.compute(g.wv, wp(BASE_X + 16, BASE_Y + 16), 4);
        assertTrue(m.isReachable(wp(BASE_X + 16, BASE_Y + 16)));
        // 8-connected, depth 4 → all tiles within Chebyshev 4 reachable.
        assertTrue(m.isReachable(wp(BASE_X + 16 + 4, BASE_Y + 16)));
        assertTrue(m.isReachable(wp(BASE_X + 16 - 4, BASE_Y + 16 + 4)));
        // Beyond depth 4 → unreachable.
        assertFalse(m.isReachable(wp(BASE_X + 16 + 5, BASE_Y + 16)));
    }

    @Test
    public void nullOriginReturnsEmpty()
    {
        FlagGrid g = buildGrid();
        Reachability.ReachabilityMap m = Reachability.compute(g.wv, null, 4);
        assertEquals(0, m.size());
        assertTrue(m.frontier().isEmpty());
    }

    @Test
    public void distanceMatchesChebyshevOnOpenGrid()
    {
        FlagGrid g = buildGrid();
        WorldPoint origin = wp(BASE_X + 10, BASE_Y + 10);
        Reachability.ReachabilityMap m = Reachability.compute(g.wv, origin, 6);
        assertEquals(0, m.distance(origin));
        // 8-connected → diagonal moves cost 1 step → Chebyshev distance.
        assertEquals(3, m.distance(wp(BASE_X + 13, BASE_Y + 13)));
        assertEquals(5, m.distance(wp(BASE_X + 15, BASE_Y + 10)));
        assertEquals(-1, m.distance(wp(BASE_X + 10, BASE_Y + 10 + 7))); // beyond depth
    }

    @Test
    public void wallBlocksExpansionAndShowsUpInFrontier()
    {
        FlagGrid g = buildGrid();
        // Build a vertical wall along world X=3215 (sceneX=15) by setting
        // BLOCK_MOVEMENT_FULL on every cell in that column. Tiles to the
        // east of the wall should NOT be reachable from the west side.
        for (int y = 0; y < 32; y++)
        {
            g.flags[sx(BASE_X + 15)][y] |= CollisionDataFlag.BLOCK_MOVEMENT_FULL;
        }
        WorldPoint origin = wp(BASE_X + 10, BASE_Y + 10);
        Reachability.ReachabilityMap m = Reachability.compute(g.wv, origin, 8);
        // Tile just west of the wall — reachable.
        assertTrue(m.isReachable(wp(BASE_X + 14, BASE_Y + 10)));
        // The wall tile itself — not reachable (BLOCK_MOVEMENT_FULL).
        assertFalse(m.isReachable(wp(BASE_X + 15, BASE_Y + 10)));
        // East of the wall — also not reachable from the west origin.
        assertFalse(m.isReachable(wp(BASE_X + 16, BASE_Y + 10)));
        // Frontier should include some tiles right against the wall — those
        // had at least one blocked direction.
        assertFalse(m.frontier().isEmpty());
        boolean foundOnFrontier = m.frontier().stream()
            .anyMatch(p -> p.getX() == BASE_X + 14);
        assertTrue("at least one wall-adjacent tile should be on the frontier",
            foundOnFrontier);
    }

    @Test
    public void pathToReconstructsContiguousChain()
    {
        FlagGrid g = buildGrid();
        WorldPoint origin = wp(BASE_X + 10, BASE_Y + 10);
        WorldPoint target = wp(BASE_X + 13, BASE_Y + 12);
        Reachability.ReachabilityMap m = Reachability.compute(g.wv, origin, 6);
        List<WorldPoint> path = m.pathTo(target);
        assertFalse(path.isEmpty());
        assertEquals(origin, path.get(0));
        assertEquals(target, path.get(path.size() - 1));
        // Every consecutive pair must be 8-neighbour adjacent.
        for (int i = 1; i < path.size(); i++)
        {
            WorldPoint a = path.get(i - 1), b = path.get(i);
            int dx = Math.abs(a.getX() - b.getX());
            int dy = Math.abs(a.getY() - b.getY());
            assertTrue("step " + i + " not 8-neighbour: " + a + " → " + b,
                Math.max(dx, dy) == 1);
        }
    }

    @Test
    public void pathToUnreachableReturnsEmpty()
    {
        FlagGrid g = buildGrid();
        WorldPoint origin = wp(BASE_X + 10, BASE_Y + 10);
        Reachability.ReachabilityMap m = Reachability.compute(g.wv, origin, 4);
        // 10 tiles away — beyond depth 4.
        assertTrue(m.pathTo(wp(BASE_X + 25, BASE_Y + 10)).isEmpty());
        // Different plane — never reachable.
        assertTrue(m.pathTo(new WorldPoint(BASE_X + 11, BASE_Y + 10, 1)).isEmpty());
    }

    @Test
    public void nullCollisionDataYieldsEmpty()
    {
        WorldView wv = mock(WorldView.class);
        when(wv.getCollisionMaps()).thenReturn(null);
        Reachability.ReachabilityMap m = Reachability.compute(wv, wp(0, 0), 4);
        assertEquals(0, m.size());
    }

    @Test
    public void differentPlaneIsAlwaysUnreachable()
    {
        FlagGrid g = buildGrid();
        Reachability.ReachabilityMap m = Reachability.compute(g.wv, wp(BASE_X, BASE_Y), 8);
        assertFalse(m.isReachable(new WorldPoint(BASE_X + 1, BASE_Y, 1)));
        assertEquals(-1, m.distance(new WorldPoint(BASE_X + 1, BASE_Y, 2)));
    }
}
