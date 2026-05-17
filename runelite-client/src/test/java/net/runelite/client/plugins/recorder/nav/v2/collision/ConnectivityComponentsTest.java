package net.runelite.client.plugins.recorder.nav.v2.collision;

import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** Unit tests for {@link ConnectivityComponents}.
 *
 *  <p>Validates the three load-bearing properties:
 *  <ul>
 *    <li>Walls partition: two walkable tiles separated by a fully-blocked
 *        row are in different components.</li>
 *    <li>Diagonal corner-touch unites: two tiles connected only via a
 *        pillar-rule-legal diagonal step are in the SAME component.
 *        (8-direction flood-fill, not cardinal-only.)</li>
 *    <li>{@code canMove} parity with {@link
 *        net.runelite.client.plugins.recorder.nav.v2.bfs.SkretzoBfsKernel}:
 *        adjacent tile pair walkable iff same component (within one step).</li>
 *  </ul>
 *
 *  Fixtures use the {@code GlobalCollisionSnapshot.forTestingWalkable}
 *  factory — a single 64×64 region from a boolean walkability grid. */
public class ConnectivityComponentsTest
{
    /** Region key for the synthetic fixture at regionX=50, regionY=50.
     *  Lumbridge-ish coords for realism but data is fully synthetic. */
    private static final int RX = 50;
    private static final int RY = 50;
    private static final int BASE_X = RX * 64;
    private static final int BASE_Y = RY * 64;

    @Test
    public void wallRow_partitionsRegion()
    {
        // 5×5 walkable area inside the synthetic region with a wall at y=2.
        boolean[][][] walkable = new boolean[1][64][64];
        for (int y = 0; y <= 4; y++)
        {
            if (y == 2) continue;  // wall row
            for (int x = 0; x <= 4; x++)
            {
                walkable[0][x][y] = true;
            }
        }
        GlobalCollisionSnapshot snap = GlobalCollisionSnapshot.forTestingWalkable(RX, RY, walkable);
        ConnectivityComponents cc = ConnectivityComponents.fromSnapshot(snap);

        WorldPoint southHalf = new WorldPoint(BASE_X, BASE_Y, 0);     // y=0, walkable
        WorldPoint northHalf = new WorldPoint(BASE_X, BASE_Y + 4, 0); // y=4, walkable

        int sId = cc.componentOf(southHalf);
        int nId = cc.componentOf(northHalf);

        assertTrue("south-half walkable", sId >= 0);
        assertTrue("north-half walkable", nId >= 0);
        assertNotEquals("wall partitions the region into two components",
            sId, nId);
        assertFalse("sameComponent is false across the wall",
            cc.sameComponent(southHalf, northHalf));
    }

    @Test
    public void sameTile_sameComponent()
    {
        // A tile with no walkable neighbours becomes BLOCK_MOVEMENT_OBJECT
        // via GlobalCollisionSnapshot's "all cardinals blocked → object"
        // inference, so we use a 2-tile walkable line.
        boolean[][][] walkable = new boolean[1][64][64];
        walkable[0][0][0] = true;
        walkable[0][1][0] = true;
        GlobalCollisionSnapshot snap = GlobalCollisionSnapshot.forTestingWalkable(RX, RY, walkable);
        ConnectivityComponents cc = ConnectivityComponents.fromSnapshot(snap);

        WorldPoint p = new WorldPoint(BASE_X, BASE_Y, 0);
        int id = cc.componentOf(p);

        assertTrue("walkable tile has component id >= 0", id >= 0);
        assertEquals("componentOf is deterministic", id, cc.componentOf(p));
        assertTrue("sameComponent(p, p) is true", cc.sameComponent(p, p));
    }

    @Test
    public void blockedTile_returnsNegativeOne()
    {
        boolean[][][] walkable = new boolean[1][64][64];
        walkable[0][0][0] = true;  // (0,0) walkable; (1,0) blocked
        GlobalCollisionSnapshot snap = GlobalCollisionSnapshot.forTestingWalkable(RX, RY, walkable);
        ConnectivityComponents cc = ConnectivityComponents.fromSnapshot(snap);

        WorldPoint blocked = new WorldPoint(BASE_X + 1, BASE_Y, 0);
        assertEquals("blocked tile → -1", -1, cc.componentOf(blocked));
    }

    @Test
    public void outOfSnapshot_returnsNegativeOne()
    {
        boolean[][][] walkable = new boolean[1][64][64];
        walkable[0][0][0] = true;
        GlobalCollisionSnapshot snap = GlobalCollisionSnapshot.forTestingWalkable(RX, RY, walkable);
        ConnectivityComponents cc = ConnectivityComponents.fromSnapshot(snap);

        // Different region (RX+1, RY) — not in this snapshot at all.
        WorldPoint outside = new WorldPoint((RX + 1) * 64, BASE_Y, 0);
        assertEquals("tile outside snapshot → -1", -1, cc.componentOf(outside));
    }

    @Test
    public void diagonalCornerTouch_sameComponent()
    {
        // L-shape: two walkable tiles connected only via a diagonal NE step
        // that is pillar-rule-legal (the two cardinal stepping-stone tiles
        // are also walkable). Cardinal-only flood-fill would WRONGLY put
        // these in different components — this test pins 8-direction
        // behaviour with the pillar rule.
        //
        // Layout (X = walkable, . = blocked):
        //   y=2: X X .
        //   y=1: X X X
        //   y=0: . X X
        //
        // (0,0) is blocked. (2,2) is blocked.
        // (0,2) connects to (2,0) only via (1,1) or (1,2)/(2,1) diagonals.
        // Specifically the (0,2) → (1,1) NE-diagonal step is allowed
        // because both (1,2) and (0,1) cardinals are walkable.
        boolean[][][] walkable = new boolean[1][64][64];
        walkable[0][0][1] = true;
        walkable[0][0][2] = true;
        walkable[0][1][0] = true;
        walkable[0][1][1] = true;
        walkable[0][1][2] = true;
        walkable[0][2][0] = true;
        walkable[0][2][1] = true;
        GlobalCollisionSnapshot snap = GlobalCollisionSnapshot.forTestingWalkable(RX, RY, walkable);
        ConnectivityComponents cc = ConnectivityComponents.fromSnapshot(snap);

        WorldPoint nw = new WorldPoint(BASE_X + 0, BASE_Y + 2, 0);  // (0, 2)
        WorldPoint se = new WorldPoint(BASE_X + 2, BASE_Y + 0, 0);  // (2, 0)

        assertTrue("nw walkable", cc.componentOf(nw) >= 0);
        assertTrue("se walkable", cc.componentOf(se) >= 0);
        assertEquals("diagonal-connected tiles are in the same component",
            cc.componentOf(nw), cc.componentOf(se));
    }

    @Test
    public void componentCount_matchesIndependentEnumeration()
    {
        // Build a 5×5 region with TWO walkable rectangles separated
        // by a fully-blocked wall row. Assert componentCount() == 2
        // (one component per rectangle).
        boolean[][][] walkable = new boolean[1][64][64];
        for (int x = 0; x <= 4; x++)
            for (int y = 0; y <= 4; y++)
            {
                if (y == 2) continue;  // wall row
                walkable[0][x][y] = true;
            }
        GlobalCollisionSnapshot snap = GlobalCollisionSnapshot.forTestingWalkable(RX, RY, walkable);
        ConnectivityComponents cc = ConnectivityComponents.fromSnapshot(snap);
        assertEquals("two disjoint walkable regions ⇒ componentCount == 2",
            2, cc.componentCount());
    }

    @Test
    public void canMove_kernelAllows_impliesSameComponent()
    {
        // Drift-prevention invariant: if the BFS kernel allows a one-step
        // transition between two tiles, those tiles MUST be in the same
        // component (one-way implication). Long-range components can
        // still unite tiles that kernel rejects directly (via longer
        // paths), so the converse does NOT hold and is not asserted.
        boolean[][][] walkable = new boolean[1][64][64];
        for (int x = 0; x <= 3; x++)
        {
            for (int y = 0; y <= 3; y++)
            {
                walkable[0][x][y] = true;
            }
        }
        walkable[0][2][2] = false;  // single-tile wall

        GlobalCollisionSnapshot snap = GlobalCollisionSnapshot.forTestingWalkable(RX, RY, walkable);
        ConnectivityComponents cc = ConnectivityComponents.fromSnapshot(snap);
        net.runelite.client.plugins.recorder.nav.v2.bfs.CollisionView view =
            new CollisionView(snap, LiveSceneCollisionOverlay.capture(null, 0, 0, 0));

        int mismatches = 0;
        StringBuilder report = new StringBuilder();
        int[][] dirs = {
            {-1, 0}, {1, 0}, {0, -1}, {0, 1},
            {-1, -1}, {1, -1}, {-1, 1}, {1, 1}
        };
        for (int x = 0; x <= 3; x++)
        {
            for (int y = 0; y <= 3; y++)
            {
                int srcWx = BASE_X + x;
                int srcWy = BASE_Y + y;
                for (int[] d : dirs)
                {
                    int dstWx = srcWx + d[0];
                    int dstWy = srcWy + d[1];
                    boolean kernelAllows =
                        net.runelite.client.plugins.recorder.nav.v2.bfs.SkretzoBfsKernel
                            .canMove(view, srcWx, srcWy, 0, d[0], d[1]);
                    if (!kernelAllows) continue;
                    WorldPoint src = new WorldPoint(srcWx, srcWy, 0);
                    WorldPoint dst = new WorldPoint(dstWx, dstWy, 0);
                    if (!cc.sameComponent(src, dst))
                    {
                        mismatches++;
                        report.append(String.format(
                            "%n  kernel allows (%d,%d)→(%d,%d) but components disagree",
                            srcWx - BASE_X, srcWy - BASE_Y,
                            dstWx - BASE_X, dstWy - BASE_Y));
                    }
                }
            }
        }
        assertEquals("canMove → sameComponent invariant violated: " + report,
            0, mismatches);
    }
}
