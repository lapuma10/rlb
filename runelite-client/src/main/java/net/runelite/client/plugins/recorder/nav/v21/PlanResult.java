package net.runelite.client.plugins.recorder.nav.v21;

import java.util.List;
import net.runelite.api.coords.WorldPoint;

/** Sealed result of {@link StaticPlanner#plan}. The whole point of
 *  v2.1 is that failure is <em>typed</em> — the navigator must know
 *  WHICH edge BFS couldn't cross before it asks the reactive layer
 *  to do something about it.
 *
 *  <p>v2's single {@code TARGET_UNREACHABLE} was the blackbox v2.1
 *  exists to escape. */
public sealed interface PlanResult
	permits PlanResult.Success, PlanResult.BlockedEdge, PlanResult.PlaneMismatch,
		PlanResult.BudgetExhausted, PlanResult.NoCandidate
{
	/** Successful plan. {@code tiles} is the BFS tile sequence;
	 *  {@code approach} is the candidate tile reached. */
	record Success(List<WorldPoint> tiles, WorldPoint approach, boolean reachedGoal)
		implements PlanResult {}

	/** BFS exhausted without reaching a candidate; the most goal-ward
	 *  blocked step is reported here so the BlockerScanner can look
	 *  for an interactable on that edge. {@code from} is reachable;
	 *  {@code to} is the next tile we couldn't step into.
	 *
	 *  <p>{@code pathToFrom} is the BFS path from the start tile to
	 *  {@code from}. The navigator walks this incrementally (8 tiles
	 *  at a time) — a blocked edge 48 tiles away is off-screen, the
	 *  dispatcher can't resolve a canvas pixel for it, so we must
	 *  approach it the same way a Success path is followed. */
	record BlockedEdge(WorldPoint from, WorldPoint to, String reason,
		List<WorldPoint> pathToFrom)
		implements PlanResult {}

	/** All candidate tiles are on a different plane than the player.
	 *  Reactive layer scans for "Climb" verbs to bridge. */
	record PlaneMismatch(int fromPlane, int toPlane) implements PlanResult {}

	/** BFS hit the radius/expansion cap. Soft fail — walk along the
	 *  recorded best-visited frontier path, replan from new position
	 *  next tick.
	 *
	 *  <p>{@code bestVisited} is the BFS tile with the lowest Chebyshev
	 *  distance to the goal centroid that we managed to reach before
	 *  the budget ran out. {@code pathToBestVisited} is the reconstructed
	 *  BFS path from the start tile to it. If no improvement was found
	 *  (i.e. the start tile is still the best-visited), the path is
	 *  {@code [start]} and the navigator should treat this as no-progress. */
	record BudgetExhausted(
		int expanded,
		WorldPoint bestVisited,
		List<WorldPoint> pathToBestVisited
	) implements PlanResult
	{
		public BudgetExhausted
		{
			pathToBestVisited = pathToBestVisited == null
				? List.of() : List.copyOf(pathToBestVisited);
		}
	}

	/** Goal has no candidate tiles on the player's plane, or BFS
	 *  exhausted with no inferrable blocked step. Hard fail. */
	record NoCandidate(String why) implements PlanResult {}
}
