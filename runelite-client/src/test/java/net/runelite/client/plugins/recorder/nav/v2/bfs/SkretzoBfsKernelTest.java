package net.runelite.client.plugins.recorder.nav.v2.bfs;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** RED tests for {@link SkretzoBfsKernel}.
 *  Pure-function BFS over a {@link CollisionView}; cardinal-first expansion,
 *  wall-edge + pillar handling, 128×128 bounded. Lane 3 / Task 2.
 *
 *  <p>All collision fixtures are hand-built via {@link FixtureCollision}; no
 *  RuneLite {@code Client} dependency.
 */
public class SkretzoBfsKernelTest
{
	// All collision flags=0 = no wall edges + walkable from anywhere; any tile not in the map is BLOCK_MOVEMENT_FULL.
	private static final int FULL_BLOCK = CollisionDataFlag.BLOCK_MOVEMENT_FULL;

	@Test
	public void plan_straightCorridor_returnsDirectPath()
	{
		// 5-tile open corridor on plane 0 from (0,0) to (4,0); all clear.
		FixtureCollision col = openBox(0, 0, 5, 1, 0);
		WorldPoint from = new WorldPoint(0, 0, 0);
		WorldPoint to = new WorldPoint(4, 0, 0);

		SkretzoBfsKernel.BfsResult r = SkretzoBfsKernel.plan(col, from, to, BfsConfig.defaults(), acceptAll());

		assertEquals(SkretzoBfsKernel.Status.PATH_FOUND, r.status());
		assertNotNull(r.tiles());
		assertEquals("path starts at from", from, r.tiles().get(0));
		assertEquals("path ends at to", to, r.tiles().get(r.tiles().size() - 1));
		// 5 tiles: (0,0)..(4,0)
		assertEquals(5, r.tiles().size());
		for (int i = 1; i < r.tiles().size(); i++)
		{
			assertAdjacent(r.tiles().get(i - 1), r.tiles().get(i));
		}
	}

	@Test
	public void plan_wallBetweenTwoTiles_doesNotCrossWall()
	{
		// 3x3 open box; place a W-wall on tile (1,1) (and matching E-wall on (0,1)).
		// Movement (0,1)<->(1,1) is blocked east/west; BFS must go around via (0,0)/(0,2).
		FixtureCollision col = openBox(0, 0, 3, 3, 0);
		// Wall on (1,1)'s WEST face — blocks W movement FROM (1,1) and E movement FROM (0,1).
		col.orFlags(new WorldPoint(1, 1, 0), CollisionDataFlag.BLOCK_MOVEMENT_WEST);
		col.orFlags(new WorldPoint(0, 1, 0), CollisionDataFlag.BLOCK_MOVEMENT_EAST);

		WorldPoint from = new WorldPoint(0, 1, 0);
		WorldPoint to = new WorldPoint(1, 1, 0);

		SkretzoBfsKernel.BfsResult r = SkretzoBfsKernel.plan(col, from, to, BfsConfig.defaults(), acceptAll());

		assertEquals(SkretzoBfsKernel.Status.PATH_FOUND, r.status());
		// Path must not contain a direct (0,1) -> (1,1) step.
		for (int i = 1; i < r.tiles().size(); i++)
		{
			WorldPoint prev = r.tiles().get(i - 1);
			WorldPoint cur = r.tiles().get(i);
			boolean isForbiddenStep = prev.equals(new WorldPoint(0, 1, 0)) && cur.equals(new WorldPoint(1, 1, 0));
			assertFalse("BFS crossed wall edge (0,1)->(1,1)", isForbiddenStep);
		}
	}

	@Test
	public void plan_diagonalNearBlockedCorner_routesAround()
	{
		// 3x3 open box; block direct diagonal (0,0)->(1,1) by closing the corner.
		// Approach: tile (1,1) has BLOCK_MOVEMENT_SOUTH_WEST (diagonal block from SW).
		// AND we close the (0,0)->(1,0) east step (so the pillar-rule kicks in:
		// even if NE-from-(0,0) is permitted by the diagonal flag alone, the
		// diagonal must be cancelled because BOTH cardinal half-steps are blocked).
		// To force the pillar rule: keep diagonal flag clear, block (0,0)->E and (0,0)->N.
		FixtureCollision col = openBox(0, 0, 3, 3, 0);
		// Close (0,0)'s E exit, (1,0)'s W entry
		col.orFlags(new WorldPoint(0, 0, 0), CollisionDataFlag.BLOCK_MOVEMENT_EAST);
		col.orFlags(new WorldPoint(1, 0, 0), CollisionDataFlag.BLOCK_MOVEMENT_WEST);
		// Close (0,0)'s N exit, (0,1)'s S entry
		col.orFlags(new WorldPoint(0, 0, 0), CollisionDataFlag.BLOCK_MOVEMENT_NORTH);
		col.orFlags(new WorldPoint(0, 1, 0), CollisionDataFlag.BLOCK_MOVEMENT_SOUTH);

		WorldPoint from = new WorldPoint(0, 0, 0);
		WorldPoint to = new WorldPoint(1, 1, 0);

		SkretzoBfsKernel.BfsResult r = SkretzoBfsKernel.plan(col, from, to, BfsConfig.defaults(), acceptAll());

		// Both cardinal half-steps from (0,0) are blocked; pillar rule must cancel
		// the diagonal (0,0)->(1,1) too. With this fixture there's no path: target unreachable.
		assertEquals(SkretzoBfsKernel.Status.UNREACHABLE, r.status());
	}

	@Test
	public void plan_pillarCornerObstacle_routesAround_notThrough()
	{
		// 5x5 open box; place a pillar at (2,2) that completely blocks.
		// BFS from (0,0) to (4,4) must route around, not cut diagonal corners through.
		FixtureCollision col = openBox(0, 0, 5, 5, 0);
		col.orFlags(new WorldPoint(2, 2, 0), FULL_BLOCK);
		// Also block all neighbors' movement into (2,2)
		col.orFlags(new WorldPoint(1, 2, 0), CollisionDataFlag.BLOCK_MOVEMENT_EAST);
		col.orFlags(new WorldPoint(3, 2, 0), CollisionDataFlag.BLOCK_MOVEMENT_WEST);
		col.orFlags(new WorldPoint(2, 1, 0), CollisionDataFlag.BLOCK_MOVEMENT_NORTH);
		col.orFlags(new WorldPoint(2, 3, 0), CollisionDataFlag.BLOCK_MOVEMENT_SOUTH);

		WorldPoint from = new WorldPoint(0, 0, 0);
		WorldPoint to = new WorldPoint(4, 4, 0);

		SkretzoBfsKernel.BfsResult r = SkretzoBfsKernel.plan(col, from, to, BfsConfig.defaults(), acceptAll());

		assertEquals(SkretzoBfsKernel.Status.PATH_FOUND, r.status());
		// Path must NOT include (2,2)
		for (WorldPoint p : r.tiles())
		{
			assertFalse("Path goes through blocked pillar (2,2)", p.equals(new WorldPoint(2, 2, 0)));
		}
		// Verify all adjacencies
		for (int i = 1; i < r.tiles().size(); i++)
		{
			assertAdjacent(r.tiles().get(i - 1), r.tiles().get(i));
		}
	}

	@Test
	public void plan_fullyBlockedTarget_returnsUnreachable()
	{
		// Target is fully blocked from all sides; no way to enter.
		FixtureCollision col = openBox(0, 0, 5, 5, 0);
		// Block every neighbor of (3,3) from entering it.
		WorldPoint t = new WorldPoint(3, 3, 0);
		col.orFlags(t, FULL_BLOCK);  // make target itself unwalkable
		WorldPoint from = new WorldPoint(0, 0, 0);

		SkretzoBfsKernel.BfsResult r = SkretzoBfsKernel.plan(col, from, t, BfsConfig.defaults(), acceptAll());

		assertEquals(SkretzoBfsKernel.Status.UNREACHABLE, r.status());
		assertEquals(LocalReplanReason.TARGET_UNREACHABLE, r.reasonIfFailed());
	}

	@Test
	public void plan_sameInputSameSeed_byteIdenticalOutput()
	{
		FixtureCollision col = openBox(0, 0, 10, 10, 0);
		WorldPoint from = new WorldPoint(0, 0, 0);
		WorldPoint to = new WorldPoint(9, 9, 0);
		BfsConfig cfg = BfsConfig.defaults().withSeed(42L);

		SkretzoBfsKernel.BfsResult r1 = SkretzoBfsKernel.plan(col, from, to, cfg, acceptAll());
		SkretzoBfsKernel.BfsResult r2 = SkretzoBfsKernel.plan(col, from, to, cfg, acceptAll());

		assertEquals(SkretzoBfsKernel.Status.PATH_FOUND, r1.status());
		assertEquals(SkretzoBfsKernel.Status.PATH_FOUND, r2.status());
		assertEquals("same seed must produce byte-identical path", r1.tiles(), r2.tiles());
	}

	@Test
	public void plan_sameInputDifferentSeed_pathStillValid()
	{
		FixtureCollision col = openBox(0, 0, 10, 10, 0);
		WorldPoint from = new WorldPoint(0, 0, 0);
		WorldPoint to = new WorldPoint(9, 9, 0);

		SkretzoBfsKernel.BfsResult r1 = SkretzoBfsKernel.plan(col, from, to, BfsConfig.defaults().withSeed(0L), acceptAll());
		SkretzoBfsKernel.BfsResult r2 = SkretzoBfsKernel.plan(col, from, to, BfsConfig.defaults().withSeed(1L), acceptAll());

		// Both must succeed
		assertEquals(SkretzoBfsKernel.Status.PATH_FOUND, r1.status());
		assertEquals(SkretzoBfsKernel.Status.PATH_FOUND, r2.status());
		// Both must reach from->to and only contain adjacent steps
		for (List<WorldPoint> p : List.of(r1.tiles(), r2.tiles()))
		{
			assertEquals(from, p.get(0));
			assertEquals(to, p.get(p.size() - 1));
			for (int i = 1; i < p.size(); i++)
			{
				assertAdjacent(p.get(i - 1), p.get(i));
			}
		}
	}

	@Test
	public void plan_budgetExhausted_returnsTypedFailure()
	{
		// Tiny budget; large maze. Force budget exhaustion.
		FixtureCollision col = openBox(0, 0, 50, 50, 0);
		WorldPoint from = new WorldPoint(0, 0, 0);
		WorldPoint to = new WorldPoint(49, 49, 0);
		BfsConfig cfg = BfsConfig.defaults().withMaxExpandedTiles(3); // far less than path length

		SkretzoBfsKernel.BfsResult r = SkretzoBfsKernel.plan(col, from, to, cfg, acceptAll());

		assertEquals(SkretzoBfsKernel.Status.BUDGET_EXHAUSTED, r.status());
		assertEquals(LocalReplanReason.EXECUTOR_TIMEOUT, r.reasonIfFailed());
	}

	@Test
	public void plan_fromEqualsTo_returnsTrivialPath()
	{
		FixtureCollision col = openBox(0, 0, 5, 5, 0);
		WorldPoint p = new WorldPoint(2, 2, 0);

		SkretzoBfsKernel.BfsResult r = SkretzoBfsKernel.plan(col, p, p, BfsConfig.defaults(), acceptAll());

		assertEquals(SkretzoBfsKernel.Status.PATH_FOUND, r.status());
		assertEquals(1, r.tiles().size());
		assertEquals(p, r.tiles().get(0));
	}

	@Test
	public void plan_predicateRejectsTile_avoidsIt()
	{
		FixtureCollision col = openBox(0, 0, 3, 3, 0);
		WorldPoint from = new WorldPoint(0, 1, 0);
		WorldPoint to = new WorldPoint(2, 1, 0);
		WorldPoint forbidden = new WorldPoint(1, 1, 0);

		TilePredicate predicate = (tile, ctx) -> !tile.equals(forbidden);
		SkretzoBfsKernel.BfsResult r = SkretzoBfsKernel.plan(col, from, to, BfsConfig.defaults(), predicate);

		assertEquals(SkretzoBfsKernel.Status.PATH_FOUND, r.status());
		for (WorldPoint p : r.tiles())
		{
			assertFalse("Path went through tile rejected by predicate", p.equals(forbidden));
		}
	}

	@Test
	public void plan_planeMismatchOnTarget_returnsUnreachable()
	{
		// BFS is per-plane only; target on different plane must be unreachable.
		FixtureCollision col = openBox(0, 0, 3, 3, 0);
		WorldPoint from = new WorldPoint(0, 0, 0);
		WorldPoint to = new WorldPoint(2, 2, 1); // plane 1

		SkretzoBfsKernel.BfsResult r = SkretzoBfsKernel.plan(col, from, to, BfsConfig.defaults(), acceptAll());

		assertEquals(SkretzoBfsKernel.Status.UNREACHABLE, r.status());
	}

	// --- fixture helpers ---

	private static void assertAdjacent(WorldPoint a, WorldPoint b)
	{
		int dx = Math.abs(a.getX() - b.getX());
		int dy = Math.abs(a.getY() - b.getY());
		int dp = Math.abs(a.getPlane() - b.getPlane());
		assertTrue("non-adjacent step " + a + " -> " + b,
			dp == 0 && dx <= 1 && dy <= 1 && (dx + dy) > 0);
	}

	private static FixtureCollision openBox(int x0, int y0, int w, int h, int plane)
	{
		FixtureCollision c = new FixtureCollision();
		for (int x = x0; x < x0 + w; x++)
		{
			for (int y = y0; y < y0 + h; y++)
			{
				c.setFlags(new WorldPoint(x, y, plane), 0);
			}
		}
		return c;
	}

	private static TilePredicate acceptAll()
	{
		return (tile, ctx) -> true;
	}

	/** Lane-3-local mock CollisionView. Tiles not in the map are treated as
	 *  fully blocked (off-scene). Direction-blocking flags are honored. */
	private static final class FixtureCollision implements CollisionView
	{
		private final Map<WorldPoint, Integer> flags = new HashMap<>();

		void setFlags(WorldPoint p, int f) { flags.put(p, f); }
		void orFlags(WorldPoint p, int f) { flags.merge(p, f, (a, b) -> a | b); }

		@Override
		public int flagsAt(WorldPoint p)
		{
			Integer v = flags.get(p);
			return v == null ? CollisionDataFlag.BLOCK_MOVEMENT_FULL : v;
		}
	}
}
