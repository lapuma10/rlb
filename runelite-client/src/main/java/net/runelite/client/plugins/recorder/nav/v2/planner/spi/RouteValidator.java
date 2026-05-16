package net.runelite.client.plugins.recorder.nav.v2.planner.spi;

import java.util.Collections;
import java.util.List;
import net.runelite.api.coords.WorldPoint;

/** Spec §3 / Lane 3 contract: independent validator that re-runs
 *  collision + predicate + plane-change checks across a path.
 *
 *  <p><b>Local mock</b>: byte-identical to Lane 3's
 *  {@code nav/v2/bfs/RouteValidator}. Integration consolidates by
 *  deleting this and importing Lane 3's.
 *
 *  <p>Purpose per spec §7 rule 9: never trust the planner's own
 *  output. The validator catches kernel bugs as a loud rejection,
 *  not a silent miswalk. */
public final class RouteValidator
{
	private RouteValidator() {}

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

	public static ValidationResult validate(List<WorldPoint> path,
											CollisionView collision,
											TilePredicate predicate,
											List<? extends PlaneTransition> planeTransitions,
											PathContext ctx)
	{
		if (path == null || path.size() <= 1)
		{
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
												 PathContext ctx)
	{
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
			if (predicate != null && !predicate.accept(b, ctx))
			{
				return ValidationResult.failResult(idx, "predicate rejected post-transport tile " + b);
			}
			return ValidationResult.okResult();
		}

		int dx = b.getX() - a.getX();
		int dy = b.getY() - a.getY();
		int absdx = Math.abs(dx);
		int absdy = Math.abs(dy);
		if (absdx > 1 || absdy > 1 || (absdx + absdy) == 0)
		{
			return ValidationResult.failResult(idx, "non-adjacent step " + a + " -> " + b);
		}

		if (!SkretzoBfsKernel.canMove(collision, a.getX(), a.getY(), a.getPlane(),
			Integer.signum(dx), Integer.signum(dy)))
		{
			String desc = (absdx == 1 && absdy == 1)
				? "diagonal blocked (pillar/corner or diagonal flag) " + a + " -> " + b
				: "cardinal blocked (wall edge or full) " + a + " -> " + b;
			return ValidationResult.failResult(idx, desc);
		}

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
