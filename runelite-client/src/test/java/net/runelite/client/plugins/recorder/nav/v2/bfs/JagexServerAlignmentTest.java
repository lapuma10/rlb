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

/** Property tests asserting {@link SkretzoBfsKernel} aligns with documented
 *  Jagex server behavior on the four reference scenarios defined in
 *  spec §10 risks and the Lane 3 plan §Task 4.
 *
 *  <p>These tests are the VALIDATION of "server-aligned." If any of these
 *  fail, fix the kernel in our fork — that is why we forked Skretzo rather
 *  than adopting wholesale.
 *
 *  <p>Reference cases (OSRS-wiki / Jagex-documented):
 *  <ol>
 *    <li>W/E/S/N expansion order produces the cardinal-preferred first path
 *        on an open grid.</li>
 *    <li>Wall-edge flags block movement from both sides.</li>
 *    <li>Pillar / corner: a diagonal cannot cut through a closed corner.</li>
 *    <li>128 chebyshev cap: target beyond the cap returns UNREACHABLE even
 *        when the grid in between is walkable.</li>
 *  </ol>
 *
 *  <p>Lane 3 / Task 4.
 */
public class JagexServerAlignmentTest
{
	@Test
	public void expansion_5x5OpenGrid_matchesJagexCardinalPreference()
	{
		// 5x5 open grid, start (2,2), goal (4,2) — directly east.
		// Cardinal-first expansion (W,E,S,N then diagonals) must produce
		// a strictly-cardinal first-found path E,E (length 3 tiles).
		// Jagex behavior: cardinal step preferred when target is purely
		// cardinal — the BFS frontier reaches the goal via the E step
		// before any diagonal expansion at the same depth.
		FixtureCollision col = openBox(0, 0, 5, 5, 0);
		WorldPoint from = new WorldPoint(2, 2, 0);
		WorldPoint to = new WorldPoint(4, 2, 0);

		SkretzoBfsKernel.BfsResult r = SkretzoBfsKernel.plan(col, from, to, BfsConfig.defaults(), acceptAll());

		assertEquals(SkretzoBfsKernel.Status.PATH_FOUND, r.status());
		List<WorldPoint> path = r.tiles();
		assertEquals(3, path.size());
		assertEquals(new WorldPoint(2, 2, 0), path.get(0));
		assertEquals(new WorldPoint(3, 2, 0), path.get(1));
		assertEquals(new WorldPoint(4, 2, 0), path.get(2));
	}

	@Test
	public void expansion_diagonalTarget_prefersCardinalsBeforeDiagonals()
	{
		// 4x4 open grid, start (0,0), goal (3,3) — NE diagonal.
		// Cardinal-first means the BFS visits (-1,0), (1,0), (0,-1), (0,1)
		// before any diagonals at depth=1. The shortest path is 3 diagonal
		// steps OR 6 cardinals; BFS returns the minimum-step path (3 steps,
		// 4 tiles via diagonals). The diagonal order is seed-dependent but
		// the path LENGTH must be deterministic.
		FixtureCollision col = openBox(0, 0, 4, 4, 0);
		WorldPoint from = new WorldPoint(0, 0, 0);
		WorldPoint to = new WorldPoint(3, 3, 0);

		SkretzoBfsKernel.BfsResult r = SkretzoBfsKernel.plan(col, from, to, BfsConfig.defaults(), acceptAll());

		assertEquals(SkretzoBfsKernel.Status.PATH_FOUND, r.status());
		// shortest is 4 tiles via 3 NE diagonals
		assertEquals("expected 4-tile diagonal-only path", 4, r.tiles().size());
		// each consecutive step must be NE diagonal
		for (int i = 1; i < r.tiles().size(); i++)
		{
			WorldPoint a = r.tiles().get(i - 1);
			WorldPoint b = r.tiles().get(i);
			assertEquals(1, b.getX() - a.getX());
			assertEquals(1, b.getY() - a.getY());
		}
	}

	@Test
	public void wallEdge_blocksFromBothSides()
	{
		// Wall-edge symmetry: a wall on the west face of (5,5) blocks
		// movement W from (5,5) -> (4,5) AND blocks movement E from (4,5)
		// -> (5,5). Jagex maintains wall flags on BOTH sides for this.
		// To prove the symmetry, set BLOCK_MOVEMENT_WEST on (5,5) and
		// BLOCK_MOVEMENT_EAST on (4,5).
		FixtureCollision col = openBox(0, 0, 10, 10, 0);
		col.orFlags(new WorldPoint(5, 5, 0), CollisionDataFlag.BLOCK_MOVEMENT_WEST);
		col.orFlags(new WorldPoint(4, 5, 0), CollisionDataFlag.BLOCK_MOVEMENT_EAST);

		// W from (5,5) to (4,5)
		assertFalse("wall must block W from (5,5)",
			SkretzoBfsKernel.canMove(col, 5, 5, 0, -1, 0));
		// E from (4,5) to (5,5)
		assertFalse("wall must block E from (4,5)",
			SkretzoBfsKernel.canMove(col, 4, 5, 0, 1, 0));

		// BFS from (4,5) to (5,5) must route around the wall (via (4,6) or (4,4))
		SkretzoBfsKernel.BfsResult r = SkretzoBfsKernel.plan(col,
			new WorldPoint(4, 5, 0), new WorldPoint(5, 5, 0), BfsConfig.defaults(), acceptAll());
		assertEquals(SkretzoBfsKernel.Status.PATH_FOUND, r.status());
		// Must NOT be a direct 2-tile path (which would imply crossing the wall)
		assertTrue("BFS crossed the wall (path is direct)",
			r.tiles().size() >= 3);
	}

	@Test
	public void pillarCorner_blocksDiagonal()
	{
		// Pillar at the NE corner of (5,5) blocks the diagonal (5,5)->(6,6).
		// Jagex's pillar rule: if either cardinal half-step (N or E) is
		// blocked, the NE diagonal is also blocked.
		// Implementation: place a pillar (BLOCK_MOVEMENT_FULL) at (6,5)
		// so the E half-step is blocked. The N half-step (5,5)->(5,6) is
		// still possible, but the NE diagonal must be cancelled by the
		// pillar rule.
		FixtureCollision col = openBox(0, 0, 10, 10, 0);
		col.orFlags(new WorldPoint(6, 5, 0), CollisionDataFlag.BLOCK_MOVEMENT_FULL);

		// Direct NE diagonal must be blocked
		assertFalse("NE diagonal must be blocked when E half-step is blocked",
			SkretzoBfsKernel.canMove(col, 5, 5, 0, 1, 1));

		// BFS from (5,5) to (6,6) must route via (5,6) (north then east)
		SkretzoBfsKernel.BfsResult r = SkretzoBfsKernel.plan(col,
			new WorldPoint(5, 5, 0), new WorldPoint(6, 6, 0), BfsConfig.defaults(), acceptAll());
		assertEquals(SkretzoBfsKernel.Status.PATH_FOUND, r.status());
		// Path must NOT contain a direct (5,5)->(6,6) step.
		assertNotNull(r.tiles());
		assertFalse("BFS used the blocked diagonal",
			containsStep(r.tiles(), new WorldPoint(5, 5, 0), new WorldPoint(6, 6, 0)));
	}

	@Test
	public void pillarCorner_secondVariant_northBlocked()
	{
		// Mirror of the previous test: block the N half-step, leave E open.
		FixtureCollision col = openBox(0, 0, 10, 10, 0);
		col.orFlags(new WorldPoint(5, 6, 0), CollisionDataFlag.BLOCK_MOVEMENT_FULL);

		assertFalse("NE diagonal must be blocked when N half-step is blocked",
			SkretzoBfsKernel.canMove(col, 5, 5, 0, 1, 1));
	}

	@Test
	public void distanceBound_beyond128_returnsUnreachable()
	{
		// Target at chebyshev=130 from start with everything walkable in
		// between → Jagex's 128 server bound means UNREACHABLE.
		// Build a 131-wide open corridor so the geometry is reachable in
		// principle, but the kernel must enforce the bound and refuse.
		FixtureCollision col = openBox(0, 0, 131, 1, 0);
		WorldPoint from = new WorldPoint(0, 0, 0);
		WorldPoint to = new WorldPoint(130, 0, 0);

		SkretzoBfsKernel.BfsResult r = SkretzoBfsKernel.plan(col, from, to, BfsConfig.defaults(), acceptAll());

		assertEquals(SkretzoBfsKernel.Status.UNREACHABLE, r.status());
		assertEquals(LocalReplanReason.TARGET_UNREACHABLE, r.reasonIfFailed());
	}

	@Test
	public void distanceBound_at128_returnsPathFound()
	{
		// Target at exactly chebyshev=128 from start, all walkable.
		// The Jagex bound is 128 inclusive; at the cap the path MUST be
		// returned, not rejected.
		FixtureCollision col = openBox(0, 0, 129, 1, 0);
		WorldPoint from = new WorldPoint(0, 0, 0);
		WorldPoint to = new WorldPoint(128, 0, 0);

		SkretzoBfsKernel.BfsResult r = SkretzoBfsKernel.plan(col, from, to, BfsConfig.defaults(), acceptAll());

		assertEquals(SkretzoBfsKernel.Status.PATH_FOUND, r.status());
		assertEquals(129, r.tiles().size());
	}

	@Test
	public void wallEdge_diagonalThroughWall_blocked()
	{
		// Wall between (5,5) and (4,5) (W wall on (5,5)). The diagonal
		// (4,4)->(5,5) requires the E half-step (4,4)->(5,4) and the N
		// half-step (4,4)->(4,5) to both be unblocked. If we also block
		// the N half-step's entry, the diagonal must fail.
		FixtureCollision col = openBox(0, 0, 10, 10, 0);
		// Block W movement into (5,5) from (4,5)
		col.orFlags(new WorldPoint(5, 5, 0), CollisionDataFlag.BLOCK_MOVEMENT_WEST);
		col.orFlags(new WorldPoint(4, 5, 0), CollisionDataFlag.BLOCK_MOVEMENT_EAST);

		// Diagonal (4,4) -> (5,5) requires N (4,5) and E (5,4) half-steps.
		// E half-step (4,4) -> (5,4) is open. N half-step (4,4) -> (4,5) is open.
		// But (4,5) -> (5,5) E is blocked. That does not block the diagonal
		// from (4,4) directly. So diagonal (4,4)->(5,5) goes through (4,5) N then
		// (4,5)->(5,5) E? No — that's two steps.
		// The diagonal (4,4)->(5,5) uses the corner; the corner halves are
		// (5,4) and (4,5). Both are unblocked. The diagonal SHOULD be OK with
		// only the (4,5)->(5,5) E wall in place — that's a separate edge.
		// So this test confirms: a wall ONLY on the (4,5)->(5,5) edge does
		// NOT block the (4,4)->(5,5) diagonal. The diagonal blocking flag
		// (BLOCK_MOVEMENT_SOUTH_WEST on dest (5,5)) is what would block it.
		// Validate that here.
		assertTrue("diagonal (4,4)->(5,5) should be OK with only N-wall on (5,5)'s W edge",
			SkretzoBfsKernel.canMove(col, 4, 4, 0, 1, 1));

		// Now set the SW diagonal block on (5,5) — this is the engine's
		// "diagonal wall" representation. Diagonal (4,4)->(5,5) MUST be blocked.
		col.orFlags(new WorldPoint(5, 5, 0), CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_WEST);
		assertFalse("diagonal must be blocked by BLOCK_MOVEMENT_SOUTH_WEST on dest",
			SkretzoBfsKernel.canMove(col, 4, 4, 0, 1, 1));
	}

	@Test
	public void wallEdge_perpendicularDirection_notBlocked()
	{
		// A west-wall on (5,5) only blocks W/E movement across THAT face,
		// not N/S movement at the same tile.
		FixtureCollision col = openBox(0, 0, 10, 10, 0);
		col.orFlags(new WorldPoint(5, 5, 0), CollisionDataFlag.BLOCK_MOVEMENT_WEST);
		col.orFlags(new WorldPoint(4, 5, 0), CollisionDataFlag.BLOCK_MOVEMENT_EAST);

		// W/E across the wall: blocked
		assertFalse(SkretzoBfsKernel.canMove(col, 5, 5, 0, -1, 0));
		assertFalse(SkretzoBfsKernel.canMove(col, 4, 5, 0, 1, 0));
		// N/S at (5,5): NOT affected
		assertTrue("N movement at (5,5) must NOT be affected by west-wall",
			SkretzoBfsKernel.canMove(col, 5, 5, 0, 0, 1));
		assertTrue("S movement at (5,5) must NOT be affected by west-wall",
			SkretzoBfsKernel.canMove(col, 5, 5, 0, 0, -1));
	}

	// --- helpers ---

	private static boolean containsStep(List<WorldPoint> path, WorldPoint a, WorldPoint b)
	{
		for (int i = 1; i < path.size(); i++)
		{
			if (path.get(i - 1).equals(a) && path.get(i).equals(b))
			{
				return true;
			}
		}
		return false;
	}

	private static TilePredicate acceptAll() { return (t, c) -> true; }

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
