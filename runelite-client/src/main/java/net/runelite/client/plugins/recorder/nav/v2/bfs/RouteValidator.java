package net.runelite.client.plugins.recorder.nav.v2.bfs;

import java.util.Collections;
import java.util.List;
import net.runelite.api.coords.WorldPoint;

/** Independent validator for BFS output (and any compressed/sparse path).
 *  Lane 3 / Task 3.
 *
 *  <p>The kernel's correctness is one piece of evidence; the validator is the
 *  second. They never share code — a bug in the kernel must surface as a
 *  loud validator rejection, not a silent miswalk.
 *
 *  <p>Checks every consecutive pair of tiles in the path for:
 *  <ul>
 *    <li>Chebyshev adjacency (single-tile step).</li>
 *    <li>Collision flags permit the move (cardinal half-step + diagonal
 *        pillar / corner rule + full-block on dest).</li>
 *    <li>Same plane, OR a {@link PlaneTransition} (an adapter for the
 *        spec §3 {@code TransportLeg}) explains the plane change.</li>
 *    <li>Predicate accepts the destination tile.</li>
 *  </ul>
 *
 *  <p>On failure, returns a typed {@link ValidationResult} carrying the
 *  first-failure index and a human-readable reason. Callers (planner)
 *  reject the path with reason {@code TARGET_UNREACHABLE}.
 */
public final class RouteValidator
{
	private RouteValidator() {}

	/** Result of a validate call. */
	public static final class ValidationResult
	{
		private final boolean ok;
		private final int firstFailureIndex;
		private final String reason;

		private ValidationResult(boolean ok, int firstFailureIndex, String reason)
		{
			this.ok = ok;
			this.firstFailureIndex = firstFailureIndex;
			this.reason = reason;
		}

		public boolean ok() { return ok; }
		public int firstFailureIndex() { return firstFailureIndex; }
		public String reason() { return reason; }

		static ValidationResult okResult() { return new ValidationResult(true, -1, null); }
		static ValidationResult failResult(int idx, String reason)
		{
			return new ValidationResult(false, idx, reason);
		}
	}

	/** Validate a tile sequence. {@code planeTransitions} is the (possibly
	 *  empty) list of plane-jumps explained by transports — the validator
	 *  permits a path step that exactly matches one such jump. */
	public static ValidationResult validate(List<WorldPoint> path,
											CollisionView collision,
											TilePredicate predicate,
											List<? extends PlaneTransition> planeTransitions,
											Object ctx)
	{
		if (path == null || path.size() <= 1)
		{
			// 0 or 1 tile is vacuously valid; no step to check.
			return ValidationResult.okResult();
		}
		List<? extends PlaneTransition> jumps = planeTransitions == null
			? Collections.emptyList() : planeTransitions;
		for (int i = 1; i < path.size(); i++)
		{
			WorldPoint a = path.get(i - 1);
			WorldPoint b = path.get(i);
			ValidationResult stepResult = validateStep(i, a, b, collision, predicate, jumps, ctx);
			if (!stepResult.ok())
			{
				return stepResult;
			}
		}
		return ValidationResult.okResult();
	}

	private static ValidationResult validateStep(int idx, WorldPoint a, WorldPoint b,
												 CollisionView collision,
												 TilePredicate predicate,
												 List<? extends PlaneTransition> jumps,
												 Object ctx)
	{
		// 1) plane change check
		if (a.getPlane() != b.getPlane())
		{
			boolean explained = false;
			for (PlaneTransition pt : jumps)
			{
				if (eq(pt.from(), a) && eq(pt.to(), b))
				{
					explained = true;
					break;
				}
			}
			if (!explained)
			{
				return ValidationResult.failResult(idx, "plane change " + a + " -> " + b
					+ " not explained by any transport leg");
			}
			// plane change with transport — accept without collision/adjacency check;
			// transports don't need to be adjacent tiles.
			if (predicate != null && !predicate.accept(b, ctx))
			{
				return ValidationResult.failResult(idx, "predicate rejected post-transport tile " + b);
			}
			return ValidationResult.okResult();
		}

		// 2) adjacency (chebyshev <= 1, > 0)
		int dx = b.getX() - a.getX();
		int dy = b.getY() - a.getY();
		int absdx = Math.abs(dx);
		int absdy = Math.abs(dy);
		if (absdx > 1 || absdy > 1 || (absdx + absdy) == 0)
		{
			return ValidationResult.failResult(idx, "non-adjacent step " + a + " -> " + b);
		}

		// 3) collision flags permit the move
		if (!SkretzoBfsKernel.canMove(collision, a.getX(), a.getY(), a.getPlane(),
			Integer.signum(dx), Integer.signum(dy)))
		{
			String desc = (absdx == 1 && absdy == 1)
				? "diagonal blocked (pillar/corner or diagonal flag) " + a + " -> " + b
				: "cardinal blocked (wall edge or full) " + a + " -> " + b;
			return ValidationResult.failResult(idx, desc);
		}

		// 4) predicate
		if (predicate != null && !predicate.accept(b, ctx))
		{
			return ValidationResult.failResult(idx, "predicate rejected tile " + b);
		}
		return ValidationResult.okResult();
	}

	private static boolean eq(WorldPoint p, WorldPoint q)
	{
		if (p == null || q == null) return false;
		return p.getX() == q.getX() && p.getY() == q.getY() && p.getPlane() == q.getPlane();
	}
}
