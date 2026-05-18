package net.runelite.client.plugins.recorder.nav.v21;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.nav.v2.bfs.CollisionView;
import net.runelite.client.plugins.recorder.nav.v2.bfs.SkretzoBfsKernel;

/** BFS from a start tile toward any {@link Goal} candidate tile.
 *
 *  <p>Returns one of:
 *  <ul>
 *    <li>{@link PlanResult.Success} — tile sequence that lands on a
 *        candidate.</li>
 *    <li>{@link PlanResult.BlockedEdge} — BFS exhausted without
 *        reaching a candidate; the planner picks the best-visited
 *        tile (closest in Chebyshev to the goal centroid) and reports
 *        the most goal-ward blocked step from there. This is the
 *        actionable failure type the reactive layer consumes.</li>
 *    <li>{@link PlanResult.PlaneMismatch} — goal has no candidates on
 *        the player's plane.</li>
 *    <li>{@link PlanResult.BudgetExhausted} — BFS hit the expansion
 *        cap. Soft fail: walk toward the centroid and replan.</li>
 *    <li>{@link PlanResult.NoCandidate} — empty candidate set or no
 *        inferrable blocked step. Hard fail.</li>
 *  </ul>
 *
 *  <p>Uses {@link SkretzoBfsKernel#canMove} so v2.1 sees collision
 *  the same way the engine does. <em>No</em> precomputed
 *  ConnectivityComponents — components are the v2 thing that turned
 *  "missing door data" into "unreachable." v2.1's contract: BFS
 *  reports the blocked edge; the reactive layer figures the rest out
 *  at runtime.
 *
 *  <p>Pure compute: safe on the worker thread. The {@link CollisionView}
 *  it consumes is captured once per tick by {@link V21Env}. */
public final class StaticPlanner
{
	/** Hard cap on BFS distance from the start tile. 64 covers the
	 *  whole loaded scene's interior (player is in the centre of a
	 *  104×104 scene). Replan handles longer-range goals incrementally
	 *  as the player moves. */
	static final int MAX_RADIUS = 64;
	/** Hard cap on expanded tiles. 64-radius circle ≈ 12k tiles; an
	 *  8192 cap is enough for any one BFS and the navigator replans
	 *  every tick anyway. */
	static final int MAX_EXPANSIONS = 8192;

	private static final int[][] STEPS = {
		{-1,  0}, { 1,  0}, { 0, -1}, { 0,  1},
		{-1, -1}, { 1, -1}, {-1,  1}, { 1,  1}
	};

	private final CollisionView collision;

	public StaticPlanner(CollisionView collision)
	{
		this.collision = collision;
	}

	public PlanResult plan(WorldPoint from, Goal goal)
	{
		if (from == null) return new PlanResult.NoCandidate("null start tile");
		List<WorldPoint> rawCandidates = goal.candidateTiles();
		if (rawCandidates == null || rawCandidates.isEmpty())
		{
			return new PlanResult.NoCandidate("goal has empty candidate set");
		}

		// Same-plane candidates only — cross-plane goals are reached via
		// stairs/ladders, which the reactive layer handles.
		Set<Long> candidateKeys = new HashSet<>();
		boolean anyOnOtherPlane = false;
		for (WorldPoint c : rawCandidates)
		{
			if (c == null) continue;
			if (c.getPlane() == from.getPlane()) candidateKeys.add(pack(c.getX(), c.getY()));
			else anyOnOtherPlane = true;
		}
		if (candidateKeys.isEmpty())
		{
			return new PlanResult.PlaneMismatch(from.getPlane(),
				anyOnOtherPlane ? goal.centroid().getPlane() : from.getPlane());
		}

		int plane = from.getPlane();
		int sx = from.getX();
		int sy = from.getY();
		int gx = goal.centroid().getX();
		int gy = goal.centroid().getY();

		if (candidateKeys.contains(pack(sx, sy)))
		{
			return new PlanResult.Success(List.of(from), from, true);
		}

		Map<Long, Long> parent = new HashMap<>();
		Set<Long> visited = new HashSet<>();
		Deque<long[]> queue = new ArrayDeque<>();
		long startKey = pack(sx, sy);
		queue.add(new long[]{sx, sy});
		visited.add(startKey);

		long bestVisitedKey = startKey;
		int bestVisitedDist = chebyshev(sx, sy, gx, gy);
		int expanded = 0;

		while (!queue.isEmpty())
		{
			if (expanded++ >= MAX_EXPANSIONS)
			{
				WorldPoint best = new WorldPoint(
					unpackX(bestVisitedKey), unpackY(bestVisitedKey), plane);
				List<WorldPoint> pathToBest = reconstruct(
					parent, sx, sy, best.getX(), best.getY(), plane);
				return new PlanResult.BudgetExhausted(expanded, best, pathToBest);
			}
			long[] head = queue.poll();
			int x = (int) head[0];
			int y = (int) head[1];

			int distHere = chebyshev(x, y, gx, gy);
			if (distHere < bestVisitedDist)
			{
				bestVisitedDist = distHere;
				bestVisitedKey = pack(x, y);
			}

			if (chebyshev(x, y, sx, sy) >= MAX_RADIUS) continue;

			for (int[] step : STEPS)
			{
				int dx = step[0], dy = step[1];
				int nx = x + dx, ny = y + dy;
				long nk = pack(nx, ny);
				if (visited.contains(nk)) continue;
				if (chebyshev(nx, ny, sx, sy) > MAX_RADIUS) continue;
				if (!SkretzoBfsKernel.canMove(collision, x, y, plane, dx, dy)) continue;
				visited.add(nk);
				parent.put(nk, pack(x, y));
				if (candidateKeys.contains(nk))
				{
					WorldPoint goalTile = new WorldPoint(nx, ny, plane);
					return new PlanResult.Success(
						reconstruct(parent, sx, sy, nx, ny, plane), goalTile, true);
				}
				queue.add(new long[]{nx, ny});
			}
		}

		return inferBlockedEdge(parent, sx, sy, bestVisitedKey, plane, gx, gy);
	}

	/** From the best-visited frontier tile, try goal-ward steps in
	 *  priority order until one comes back blocked. Returns a
	 *  BlockedEdge describing that step plus the BFS path from start
	 *  to the anchor tile (so the navigator can walk it incrementally
	 *  via {@link WalkExecutor#walkAlong}).
	 *
	 *  <p>"Most goal-ward" first ⇒ the blocked step is the one the
	 *  reactive layer most plausibly needs to interact through. */
	private PlanResult inferBlockedEdge(Map<Long, Long> parent, int sx, int sy,
		long anchorKey, int plane, int gx, int gy)
	{
		int x = unpackX(anchorKey);
		int y = unpackY(anchorKey);
		int dx = Integer.signum(gx - x);
		int dy = Integer.signum(gy - y);

		int[][] tryOrder = {
			{dx, dy}, {dx, 0}, {0, dy},
			{dx, -dy}, {-dx, dy}, {-dx, 0}, {0, -dy}, {-dx, -dy}
		};
		for (int[] s : tryOrder)
		{
			int sdx = s[0], sdy = s[1];
			if (sdx == 0 && sdy == 0) continue;
			if (!SkretzoBfsKernel.canMove(collision, x, y, plane, sdx, sdy))
			{
				WorldPoint a = new WorldPoint(x, y, plane);
				WorldPoint b = new WorldPoint(x + sdx, y + sdy, plane);
				List<WorldPoint> path = reconstruct(parent, sx, sy, x, y, plane);
				return new PlanResult.BlockedEdge(a, b, "STATIC_COLLISION_OR_UNKNOWN", path);
			}
		}
		// Frontier had no blocked goal-ward step — degenerate case.
		// Could mean the goal is just out of MAX_RADIUS reach; surface
		// as NoCandidate so the navigator stops fast instead of looping.
		return new PlanResult.NoCandidate("frontier had no blocked goal-ward step");
	}

	private static List<WorldPoint> reconstruct(Map<Long, Long> parent,
		int sx, int sy, int gx, int gy, int plane)
	{
		List<WorldPoint> reversed = new ArrayList<>();
		long cur = pack(gx, gy);
		long start = pack(sx, sy);
		while (cur != start)
		{
			reversed.add(new WorldPoint(unpackX(cur), unpackY(cur), plane));
			Long p = parent.get(cur);
			if (p == null) break;
			cur = p;
		}
		reversed.add(new WorldPoint(sx, sy, plane));
		Collections.reverse(reversed);
		return reversed;
	}

	private static int chebyshev(int x1, int y1, int x2, int y2)
	{
		return Math.max(Math.abs(x1 - x2), Math.abs(y1 - y2));
	}

	private static long pack(int x, int y)
	{
		return ((long) x << 32) | (y & 0xFFFFFFFFL);
	}

	private static int unpackX(long k) { return (int) (k >> 32); }
	private static int unpackY(long k) { return (int) (k & 0xFFFFFFFFL); }
}
