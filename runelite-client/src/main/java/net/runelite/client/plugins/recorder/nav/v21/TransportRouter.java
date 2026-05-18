package net.runelite.client.plugins.recorder.nav.v21;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import net.runelite.api.TileObject;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.nav.v2.bfs.CollisionView;
import net.runelite.client.plugins.recorder.nav.v2.bfs.SkretzoBfsKernel;
import net.runelite.client.plugins.recorder.worldmap.TransportEdge;
import net.runelite.client.plugins.recorder.worldmap.TransportIndex;

/** Picks the next {@link TransportEdge} to use from the
 *  {@link TransportIndex}, given the player's current tile and the goal
 *  centroid.
 *
 *  <p>The router walks the index, filters edges by player-plane equality
 *  and the caller's blacklist, then checks each candidate by
 *  collision-BFS reachability of its approach tile on the player's
 *  plane. Edges that pass become anchors for a recursive forward-cost
 *  estimate: walking from the edge's destination toward either the
 *  goal (same plane, BFS) or another edge's approach tile (chained
 *  multi-hop, depth-bounded). Same-plane segments use BFS when the
 *  relevant plane's collision view is loaded; if it isn't, the router
 *  falls back to Chebyshev. Provably-blocked tiles on a loaded plane
 *  return {@code +Infinity} and rule the edge out; a BFS that runs out
 *  of expansion budget without proving either way falls back to
 *  Chebyshev so the candidate isn't a false-negative skip — see
 *  {@link #bfsOrChebyshev}.
 *
 *  <p>Pure compute. The scene lookup is injected as
 *  {@link SceneFinder} (same pattern as {@link AnchorSelector}); the
 *  caller is responsible for marshalling scene reads to the client
 *  thread. The router itself is safe on any worker thread. */
public final class TransportRouter
{
	private TransportRouter() {}

	/** Tile radius searched by the scene finder when locating an edge's
	 *  in-world object. The TransportIndex carries the recorded fromTile;
	 *  the live object may be drawn on an adjacent tile depending on
	 *  game-engine routing, so we widen the search a few tiles. */
	public static final int SCENE_PROBE_RADIUS = 8;

	/** Per-transport cost penalty added on top of the walk cost. A
	 *  transport step costs more than a tile of walking because it
	 *  involves an interaction (right-click, menu select, animation), so
	 *  the router avoids over-using chains. */
	public static final double TRANSPORT_STEP_COST = 4.0;

	/** Maximum number of chained transports the forward-cost estimator
	 *  will recurse through. Beyond this, the router gives up on the
	 *  branch and treats it as {@code +Infinity}. */
	public static final int MAX_CHAIN_DEPTH = 4;

	/** Maximum walk distance (in tiles, Chebyshev or BFS) the router
	 *  will consider between two transports in a chain. Long walks
	 *  between transports are unlikely to be the right route — usually
	 *  they indicate the chain isn't a real connection. */
	public static final int MAX_INTER_TRANSPORT_WALK = 64;

	/** BFS expansion budget for the approach-reachability check and for
	 *  same-plane segment-cost estimation. 8192 matches
	 *  {@code StaticPlanner.MAX_EXPANSIONS} and covers Chebyshev distance
	 *  up to ~45 in open 8-direction BFS ((2·45+1)² = 8281), comfortably
	 *  handling the {@link #MAX_INTER_TRANSPORT_WALK} = 64 gate for
	 *  realistic transport pairs. Walks beyond budget fall back to
	 *  Chebyshev rather than being silently ruled out — see
	 *  {@link #bfsOrChebyshev}. */
	public static final int BFS_BUDGET = 8192;

	/** Sentinel returned by {@link #bfsDistance} when the BFS queue
	 *  empties without reaching the goal — the goal is provably
	 *  unreachable on the given collision view. */
	static final int BFS_UNREACHABLE = -1;

	/** Sentinel returned by {@link #bfsDistance} when the BFS expansion
	 *  budget is hit before the queue empties — reachability is unknown
	 *  (the search may simply not have explored far enough). Callers
	 *  should fall back to a less-informed estimate rather than ruling
	 *  the goal out. */
	static final int BFS_BUDGET_EXHAUSTED = -2;

	/** Three-argument scene finder (same shape as
	 *  {@link AnchorSelector.SceneFinder}). Returns null when the
	 *  edge's object isn't currently rendered. */
	@FunctionalInterface
	public interface SceneFinder
	{
		TileObject find(int objectId, WorldPoint near, int radius);
	}

	/** Pick the lowest-estimated-cost transport whose use plausibly
	 *  reduces distance to the goal.
	 *
	 *  @param playerTile        current player tile (origin)
	 *  @param goalCentroid      target tile / goal centroid
	 *  @param index             store of fully-resolved transport edges
	 *  @param collisionByPlane  4-element array of plane collision views;
	 *                           {@link LiveCollisionView#EMPTY} for
	 *                           planes whose flag data isn't loaded
	 *  @param sceneFinder       live scene lookup; null means not rendered
	 *  @param isBlacklisted     predicate returning true for edges the
	 *                           reactive solver has recently failed on
	 *  @return                  best candidate, or empty if no edge passes
	 */
	public static Optional<TransportCandidate> findNext(
		WorldPoint playerTile,
		WorldPoint goalCentroid,
		TransportIndex index,
		CollisionView[] collisionByPlane,
		SceneFinder sceneFinder,
		Predicate<TransportEdge> isBlacklisted)
	{
		if (playerTile == null || goalCentroid == null || index == null) return Optional.empty();
		if (sceneFinder == null || isBlacklisted == null) return Optional.empty();

		TransportCandidate best = null;
		CollisionView playerPlaneView = planeView(collisionByPlane, playerTile.getPlane());

		for (TransportEdge e : index.getAll())
		{
			if (e.fromTile().getPlane() != playerTile.getPlane()) continue;
			if (isBlacklisted.test(e)) continue;
			TileObject obj = sceneFinder.find(e.objectId(), e.fromTile(), SCENE_PROBE_RADIUS);
			if (obj == null) continue;

			// Approach reachability check: BFS on player's plane only.
			// Approach tile must be on the player's plane (it's the
			// player-side tile by construction; sanity check anyway).
			if (e.approachTile().getPlane() != playerTile.getPlane()) continue;
			int walkCost = bfsDistance(playerTile, e.approachTile(), playerPlaneView, BFS_BUDGET);
			// Skip on both provably-blocked AND budget-exhausted — the
			// router won't click an approach it can't reason about. If
			// the budget is being hit on a real approach, raise it; the
			// fallback to Chebyshev only applies between transports, not
			// to the player's own first hop.
			if (walkCost < 0) continue;

			// Forward cost: estimate the cost from the edge's destination
			// to the goal, possibly through more transports.
			Set<String> visited = new HashSet<>();
			visited.add(e.key());
			double forwardCost = estimateForwardCost(
				e.toTile(), goalCentroid, index, collisionByPlane,
				isBlacklisted, visited, 0);
			if (Double.isInfinite(forwardCost)) continue;

			double total = walkCost + TRANSPORT_STEP_COST + forwardCost;
			if (best == null || total < best.estimatedTotalCost())
			{
				// chainLength=1 here is a diagnostic placeholder for the
				// "picked edge" — the meaningful number is the cost.
				best = TransportCandidate.of(e, obj, playerTile, total, 1);
			}
		}
		return Optional.ofNullable(best);
	}

	/** Recursive forward-cost estimate from {@code from} (typically a
	 *  transport's destination tile) to {@code goal}. Same-plane case
	 *  resolves immediately by collision BFS (or Chebyshev fallback);
	 *  cross-plane case considers additional transports on {@code
	 *  from}'s plane, depth-bounded by {@link #MAX_CHAIN_DEPTH}. */
	private static double estimateForwardCost(
		WorldPoint from,
		WorldPoint goal,
		TransportIndex index,
		CollisionView[] collisionByPlane,
		Predicate<TransportEdge> isBlacklisted,
		Set<String> visited,
		int depth)
	{
		if (from.getPlane() == goal.getPlane())
		{
			return bfsOrChebyshev(from, goal, planeView(collisionByPlane, goal.getPlane()));
		}
		if (depth >= MAX_CHAIN_DEPTH) return Double.POSITIVE_INFINITY;

		double best = Double.POSITIVE_INFINITY;
		for (TransportEdge e2 : index.getAll())
		{
			if (visited.contains(e2.key())) continue;
			if (e2.fromTile().getPlane() != from.getPlane()) continue;
			if (isBlacklisted.test(e2)) continue;

			double walk = bfsOrChebyshev(
				from, e2.approachTile(), planeView(collisionByPlane, from.getPlane()));
			if (walk > MAX_INTER_TRANSPORT_WALK) continue;

			// Branch-local visited: copy on descent so siblings don't
			// see each other's recursion-only entries.
			Set<String> nextVisited = new HashSet<>(visited);
			nextVisited.add(e2.key());
			double next = estimateForwardCost(
				e2.toTile(), goal, index, collisionByPlane,
				isBlacklisted, nextVisited, depth + 1);
			if (Double.isInfinite(next)) continue;

			double total = walk + TRANSPORT_STEP_COST + next;
			if (total < best) best = total;
		}
		return best;
	}

	private static CollisionView planeView(CollisionView[] byPlane, int plane)
	{
		if (byPlane == null || plane < 0 || plane >= byPlane.length) return null;
		return byPlane[plane];
	}

	/** Returns BFS distance in tiles, or one of the sentinels:
	 *  <ul>
	 *    <li>{@link #BFS_UNREACHABLE} — the BFS queue emptied without
	 *        finding the goal; the goal is provably unreachable from
	 *        {@code from} on this collision view.</li>
	 *    <li>{@link #BFS_BUDGET_EXHAUSTED} — the expansion budget was
	 *        hit before the queue emptied; reachability is unknown.</li>
	 *  </ul>
	 *  Uses {@link SkretzoBfsKernel#canMove} so the router and the runtime
	 *  walker apply the same step rule. The {@code view == null} and
	 *  cross-plane cases are caller-side bugs (the caller should check
	 *  the view and plane first), so they collapse to
	 *  {@link #BFS_UNREACHABLE} here; in practice {@link #bfsOrChebyshev}
	 *  already guards both. */
	static int bfsDistance(WorldPoint from, WorldPoint to, CollisionView view, int budget)
	{
		if (view == null) return BFS_UNREACHABLE;
		if (from.getPlane() != to.getPlane()) return BFS_UNREACHABLE;
		int sx = from.getX(), sy = from.getY();
		int gx = to.getX(),  gy = to.getY();
		int plane = from.getPlane();
		if (sx == gx && sy == gy) return 0;

		Deque<long[]> queue = new ArrayDeque<>();
		Map<Long, Integer> dist = new HashMap<>();
		long startKey = pack(sx, sy);
		queue.add(new long[]{sx, sy});
		dist.put(startKey, 0);
		int expanded = 0;
		int[][] steps = {
			{-1, 0}, {1, 0}, {0, -1}, {0, 1},
			{-1, -1}, {1, -1}, {-1, 1}, {1, 1}
		};
		while (!queue.isEmpty())
		{
			if (expanded++ >= budget) return BFS_BUDGET_EXHAUSTED;
			long[] head = queue.poll();
			int x = (int) head[0], y = (int) head[1];
			int d = dist.get(pack(x, y));
			for (int[] s : steps)
			{
				int nx = x + s[0], ny = y + s[1];
				if (nx == gx && ny == gy
					&& SkretzoBfsKernel.canMove(view, x, y, plane, s[0], s[1]))
				{
					return d + 1;
				}
				long nk = pack(nx, ny);
				if (dist.containsKey(nk)) continue;
				if (!SkretzoBfsKernel.canMove(view, x, y, plane, s[0], s[1])) continue;
				dist.put(nk, d + 1);
				queue.add(new long[]{nx, ny});
			}
		}
		return BFS_UNREACHABLE;
	}

	/** Reachability-aware distance. Three branches:
	 *  <ul>
	 *    <li>View is null or {@link LiveCollisionView#EMPTY} → Chebyshev
	 *        (we have no collision data, return the least-informed
	 *        estimate).</li>
	 *    <li>BFS returns a real distance (≥ 0) → return it directly.</li>
	 *    <li>BFS returns {@link #BFS_UNREACHABLE} → return {@code
	 *        +Infinity} so the router prunes the candidate.</li>
	 *    <li>BFS returns {@link #BFS_BUDGET_EXHAUSTED} → fall back to
	 *        Chebyshev. We cannot prove reachability either way, and
	 *        silently ruling out the transport would be a false-negative.
	 *        The Chebyshev estimate keeps the candidate in the running;
	 *        the runtime walker (or the next router call after the view
	 *        loads further) will discover the truth.</li>
	 *  </ul> */
	static double bfsOrChebyshev(WorldPoint a, WorldPoint b, CollisionView view)
	{
		if (view == null || view == LiveCollisionView.EMPTY)
		{
			return chebyshev(a, b);
		}
		int d = bfsDistance(a, b, view, BFS_BUDGET);
		if (d >= 0)
		{
			return d;
		}
		if (d == BFS_BUDGET_EXHAUSTED)
		{
			return chebyshev(a, b);
		}
		// d == BFS_UNREACHABLE
		return Double.POSITIVE_INFINITY;
	}

	static int chebyshev(WorldPoint a, WorldPoint b)
	{
		return Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getY() - b.getY()));
	}

	private static long pack(int x, int y)
	{
		return ((long) x << 32) | (y & 0xFFFFFFFFL);
	}
}
