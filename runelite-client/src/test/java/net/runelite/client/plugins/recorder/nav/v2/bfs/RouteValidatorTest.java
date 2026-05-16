package net.runelite.client.plugins.recorder.nav.v2.bfs;

import java.util.Arrays;
import java.util.Collections;
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

/** RED tests for {@link RouteValidator}. The validator re-checks every BFS
 *  output independently; bugs in the kernel must surface as loud failures,
 *  not silent miswalks. Lane 3 / Task 3.
 */
public class RouteValidatorTest
{
	@Test
	public void validate_validBfsOutput_passes()
	{
		FixtureCollision col = openBox(0, 0, 5, 5, 0);
		List<WorldPoint> path = Arrays.asList(
			new WorldPoint(0, 0, 0),
			new WorldPoint(1, 0, 0),
			new WorldPoint(2, 0, 0),
			new WorldPoint(3, 0, 0),
			new WorldPoint(4, 0, 0));

		RouteValidator.ValidationResult r = RouteValidator.validate(path, col, acceptAll(), Collections.emptyList(), null);

		assertTrue("expected ok=true, got " + r.reason(), r.ok());
		assertEquals(-1, r.firstFailureIndex());
	}

	@Test
	public void validate_handCraftedInvalidPath_failsLoudly()
	{
		// Path with a non-adjacent gap (chebyshev=2)
		FixtureCollision col = openBox(0, 0, 5, 5, 0);
		List<WorldPoint> path = Arrays.asList(
			new WorldPoint(0, 0, 0),
			new WorldPoint(2, 0, 0)); // jump of 2

		RouteValidator.ValidationResult r = RouteValidator.validate(path, col, acceptAll(), Collections.emptyList(), null);

		assertFalse("expected validation failure for non-adjacent step", r.ok());
		assertEquals(1, r.firstFailureIndex());
		assertNotNull(r.reason());
		assertTrue("reason must explain adjacency violation",
			r.reason().toLowerCase().contains("adjac"));
	}

	@Test
	public void validate_diagonalThroughBlockedCorner_fails()
	{
		// 3x3 grid; close both cardinal half-steps from (0,0) to (1,1) so the diagonal
		// is forbidden by the pillar rule.
		FixtureCollision col = openBox(0, 0, 3, 3, 0);
		col.orFlags(new WorldPoint(0, 0, 0), CollisionDataFlag.BLOCK_MOVEMENT_EAST);
		col.orFlags(new WorldPoint(1, 0, 0), CollisionDataFlag.BLOCK_MOVEMENT_WEST);
		col.orFlags(new WorldPoint(0, 0, 0), CollisionDataFlag.BLOCK_MOVEMENT_NORTH);
		col.orFlags(new WorldPoint(0, 1, 0), CollisionDataFlag.BLOCK_MOVEMENT_SOUTH);

		// Hand-crafted invalid: diagonal (0,0) -> (1,1) via blocked corner
		List<WorldPoint> path = Arrays.asList(
			new WorldPoint(0, 0, 0),
			new WorldPoint(1, 1, 0));

		RouteValidator.ValidationResult r = RouteValidator.validate(path, col, acceptAll(), Collections.emptyList(), null);

		assertFalse(r.ok());
		assertEquals(1, r.firstFailureIndex());
		assertNotNull(r.reason());
	}

	@Test
	public void validate_planeJumpWithoutTransport_fails()
	{
		// Path that jumps from plane 0 to plane 1 without a TransportLeg covering it.
		FixtureCollision col = new FixtureCollision();
		col.setFlags(new WorldPoint(0, 0, 0), 0);
		col.setFlags(new WorldPoint(0, 0, 1), 0);
		List<WorldPoint> path = Arrays.asList(
			new WorldPoint(0, 0, 0),
			new WorldPoint(0, 0, 1));

		RouteValidator.ValidationResult r = RouteValidator.validate(path, col, acceptAll(), Collections.emptyList(), null);

		assertFalse(r.ok());
		assertNotNull(r.reason());
		assertTrue("reason must mention plane",
			r.reason().toLowerCase().contains("plane"));
	}

	@Test
	public void validate_planeJumpWithTransport_passes()
	{
		FixtureCollision col = new FixtureCollision();
		col.setFlags(new WorldPoint(0, 0, 0), 0);
		col.setFlags(new WorldPoint(0, 0, 1), 0);
		List<WorldPoint> path = Arrays.asList(
			new WorldPoint(0, 0, 0),
			new WorldPoint(0, 0, 1));

		PlaneTransition leg = new PlaneTransition()
		{
			@Override public WorldPoint from() { return new WorldPoint(0, 0, 0); }
			@Override public WorldPoint to() { return new WorldPoint(0, 0, 1); }
		};
		RouteValidator.ValidationResult r = RouteValidator.validate(path, col, acceptAll(), Arrays.asList(leg), null);

		assertTrue("expected ok=true, got " + r.reason(), r.ok());
	}

	@Test
	public void validate_predicateRejectsTile_fails()
	{
		FixtureCollision col = openBox(0, 0, 3, 3, 0);
		List<WorldPoint> path = Arrays.asList(
			new WorldPoint(0, 0, 0),
			new WorldPoint(1, 0, 0),
			new WorldPoint(2, 0, 0));
		WorldPoint forbidden = new WorldPoint(1, 0, 0);

		TilePredicate predicate = (t, ctx) -> !t.equals(forbidden);
		RouteValidator.ValidationResult r = RouteValidator.validate(path, col, predicate, Collections.emptyList(), null);

		assertFalse(r.ok());
		assertEquals(1, r.firstFailureIndex());
		assertTrue("reason must mention predicate", r.reason().toLowerCase().contains("predicate"));
	}

	@Test
	public void validate_empty_passes()
	{
		// An empty path is vacuously valid (or at least not an error).
		RouteValidator.ValidationResult r = RouteValidator.validate(
			Collections.emptyList(), openBox(0, 0, 1, 1, 0), acceptAll(), Collections.emptyList(), null);
		assertTrue(r.ok());
	}

	@Test
	public void validate_singleTile_passes()
	{
		FixtureCollision col = openBox(0, 0, 1, 1, 0);
		List<WorldPoint> path = Arrays.asList(new WorldPoint(0, 0, 0));
		RouteValidator.ValidationResult r = RouteValidator.validate(path, col, acceptAll(), Collections.emptyList(), null);
		assertTrue(r.ok());
	}

	@Test
	public void validate_blockedWallStep_fails()
	{
		// Path tries to step through a wall edge.
		FixtureCollision col = openBox(0, 0, 3, 3, 0);
		col.orFlags(new WorldPoint(1, 1, 0), CollisionDataFlag.BLOCK_MOVEMENT_WEST);
		col.orFlags(new WorldPoint(0, 1, 0), CollisionDataFlag.BLOCK_MOVEMENT_EAST);
		List<WorldPoint> path = Arrays.asList(
			new WorldPoint(0, 1, 0),
			new WorldPoint(1, 1, 0));

		RouteValidator.ValidationResult r = RouteValidator.validate(path, col, acceptAll(), Collections.emptyList(), null);

		assertFalse(r.ok());
		assertEquals(1, r.firstFailureIndex());
	}

	@Test
	public void validate_fullyBlockedDestination_fails()
	{
		FixtureCollision col = openBox(0, 0, 3, 3, 0);
		col.orFlags(new WorldPoint(1, 0, 0), CollisionDataFlag.BLOCK_MOVEMENT_FULL);
		List<WorldPoint> path = Arrays.asList(
			new WorldPoint(0, 0, 0),
			new WorldPoint(1, 0, 0));

		RouteValidator.ValidationResult r = RouteValidator.validate(path, col, acceptAll(), Collections.emptyList(), null);

		assertFalse(r.ok());
		assertEquals(1, r.firstFailureIndex());
	}

	// --- helpers ---

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
