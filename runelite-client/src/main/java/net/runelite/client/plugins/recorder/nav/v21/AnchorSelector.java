package net.runelite.client.plugins.recorder.nav.v21;

import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import net.runelite.api.TileObject;
import net.runelite.api.coords.WorldPoint;

/** Picks the next unfinished {@link InteractionAnchor} in a {@link TrailGuide}
 *  whose approach tile is currently approachable from the player's position.
 *
 *  <p>"Currently approachable" means all four conditions hold:
 *  <ol>
 *    <li>{@code approachTile.plane == playerTile.plane} — anchors on other
 *        planes are unreachable until a transport bridges them.</li>
 *    <li>Anchor is not flagged as a dead-end by {@code isDeadEnd}.</li>
 *    <li>The anchor's object is in the loaded scene (resolved by
 *        {@code findInScene}).</li>
 *    <li>{@link StaticPlanner#plan} from the player tile to the approach
 *        tile returns {@link PlanResult.Success}.</li>
 *  </ol>
 *
 *  <p>{@link PlanResult.BlockedEdge}, {@link PlanResult.NoCandidate},
 *  {@link PlanResult.BudgetExhausted}, and {@link PlanResult.PlaneMismatch}
 *  all skip — the navigator's regular reactive pipeline will open any
 *  blocking doors on a subsequent tick. Once the door is open, BFS returns
 *  Success and this selector picks the anchor up. The selector itself does
 *  not orchestrate door-opening or replanning; it only answers "is this
 *  anchor approachable right this tick?".
 *
 *  <p>Pure compute. The planner and scene finder are injected as functional
 *  interfaces so this class is trivially testable without instantiating
 *  {@link StaticPlanner} or a {@link net.runelite.client.plugins.recorder.nav.v2.bfs.CollisionView}.
 *  In production, {@link V21Navigator} supplies
 *  {@code (start, g) -> new StaticPlanner(snap.collision()).plan(start, g)}.
 *  The {@link SceneFinder} is expected to marshal scene reads onto the client
 *  thread; this class itself is safe on any thread. */
public final class AnchorSelector
{
	private AnchorSelector() {}

	public static final int SCENE_PROBE_RADIUS = 8;

	/** Result of {@link #selectActive}: the chosen anchor, its index in the
	 *  guide, and the live {@link TileObject} the navigator should click. */
	public record Active(InteractionAnchor anchor, int indexInGuide, TileObject sceneObject) {}

	/** Three-argument scene finder. Takes {@code (objectId, near, radius)} and
	 *  returns the matching {@link TileObject} in scene, or null. The caller
	 *  is expected to marshal the lookup onto the client thread; the selector
	 *  itself is pure compute and runs on any thread. */
	@FunctionalInterface
	public interface SceneFinder
	{
		TileObject find(int objectId, WorldPoint near, int radius);
	}

	/** Pick the next anchor (in trail order, starting at
	 *  {@code startSearchIndex}) that satisfies all four approach-reachability
	 *  conditions described in the class javadoc.
	 *
	 *  @param guide            ordered list of anchors derived from a recorded trail
	 *  @param startSearchIndex first anchor index to consider (anchors before this
	 *                          are assumed already completed)
	 *  @param playerTile       current player tile
	 *  @param planFn           planner function — typically
	 *                          {@code (start, g) -> new StaticPlanner(col).plan(start, g)}
	 *  @param isDeadEnd        predicate that returns true if an anchor has been
	 *                          observed to dead-end recently (suppresses retry)
	 *  @param findInScene      live scene lookup; null result means the object is
	 *                          not currently rendered
	 *  @return                 the chosen anchor (and its index + scene object),
	 *                          or empty if no anchor passes all checks
	 */
	public static Optional<Active> selectActive(
		TrailGuide guide,
		int startSearchIndex,
		WorldPoint playerTile,
		BiFunction<WorldPoint, Goal, PlanResult> planFn,
		Predicate<InteractionAnchor> isDeadEnd,
		SceneFinder findInScene)
	{
		if (guide == null || guide.anchors().isEmpty()) return Optional.empty();
		if (playerTile == null) return Optional.empty();
		if (startSearchIndex < 0) startSearchIndex = 0;
		for (int i = startSearchIndex; i < guide.anchors().size(); i++)
		{
			InteractionAnchor a = guide.anchors().get(i);
			if (a.approachTile().getPlane() != playerTile.getPlane()) continue;
			if (isDeadEnd.test(a)) continue;
			TileObject obj = findInScene.find(a.objectId(), a.objectTile(), SCENE_PROBE_RADIUS);
			if (obj == null) continue;
			PlanResult pr = planFn.apply(playerTile, new Goal.Area(a.approachTile(), 1));
			if (!(pr instanceof PlanResult.Success)) continue;
			return Optional.of(new Active(a, i, obj));
		}
		return Optional.empty();
	}
}
