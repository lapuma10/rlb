package net.runelite.client.plugins.recorder.nav.v21;

import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import net.runelite.api.TileObject;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

/** Verifies {@link AnchorSelector#selectActive} picks the next anchor in
 *  trail order whose approachTile is on the player's plane, whose object
 *  is in scene, that isn't dead-end-flagged, AND whose approach BFS
 *  resolves to Success. Anything other than Success skips — the navigator
 *  pipeline opens any blocking doors on a later tick. */
public class AnchorSelectorTest
{
	private static final int GATE_ID = 1560;
	private static final int STAIR_ID = 56232;
	private static final WorldPoint PLAYER_P0 = new WorldPoint(3210, 3242, 0);

	// --- Helpers ----------------------------------------------------------

	/** Anchor #1: same-plane door at (3211,3242,0), approach (3210,3242,0). */
	private static InteractionAnchor gateAnchor()
	{
		return new InteractionAnchor(GATE_ID, "Open",
			new WorldPoint(3211, 3242, 0),
			new WorldPoint(3210, 3242, 0),
			"GameObject",
			0);
	}

	/** Anchor #2: stair at (3204, 3209, 0) with approach (3205, 3209, 0) on plane 0.
	 *  observedDestPlane = 1 (transports up). Used as the "second anchor" in
	 *  multi-anchor tests. */
	private static InteractionAnchor stairAnchor()
	{
		return new InteractionAnchor(STAIR_ID, "Climb-up",
			new WorldPoint(3204, 3209, 0),
			new WorldPoint(3205, 3209, 0),
			"GameObject",
			1);
	}

	/** Anchor whose approachTile is on plane 1 — should be skipped when the
	 *  player is on plane 0. */
	private static InteractionAnchor anchorOnPlane(int plane)
	{
		return new InteractionAnchor(GATE_ID, "Open",
			new WorldPoint(3211, 3242, plane),
			new WorldPoint(3210, 3242, plane),
			"GameObject",
			plane);
	}

	/** SceneFinder that returns a stub TileObject for any (objectId, tile) it
	 *  matches against; returns null for the rest. */
	private static AnchorSelector.SceneFinder sceneFinderWith(InteractionAnchor... inScene)
	{
		return (objectId, near, radius) ->
		{
			for (InteractionAnchor a : inScene)
			{
				if (a.objectId() == objectId && a.objectTile().equals(near))
				{
					return mock(TileObject.class);
				}
			}
			return null;
		};
	}

	/** Planner that always returns Success with a single-tile path. */
	private static BiFunction<WorldPoint, Goal, PlanResult> alwaysSuccess()
	{
		return (start, g) -> new PlanResult.Success(List.of(start), start, true);
	}

	/** Planner that always returns BlockedEdge. */
	private static BiFunction<WorldPoint, Goal, PlanResult> alwaysBlocked()
	{
		return (start, g) -> new PlanResult.BlockedEdge(
			start, new WorldPoint(start.getX() + 1, start.getY(), start.getPlane()),
			"STATIC_COLLISION_OR_UNKNOWN", List.of(start));
	}

	private static Predicate<InteractionAnchor> noDeadEnds() { return a -> false; }

	// --- Tests ------------------------------------------------------------

	@Test
	public void emptyGuideReturnsEmpty()
	{
		TrailGuide guide = new TrailGuide(List.of(), List.of());
		Optional<AnchorSelector.Active> result = AnchorSelector.selectActive(
			guide, 0, PLAYER_P0, alwaysSuccess(), noDeadEnds(),
			sceneFinderWith(gateAnchor()));
		assertFalse(result.isPresent());
	}

	@Test
	public void firstAnchorReachableReturnsIndexZero()
	{
		InteractionAnchor gate = gateAnchor();
		TrailGuide guide = new TrailGuide(List.of(), List.of(gate));
		Optional<AnchorSelector.Active> result = AnchorSelector.selectActive(
			guide, 0, PLAYER_P0, alwaysSuccess(), noDeadEnds(),
			sceneFinderWith(gate));
		assertTrue(result.isPresent());
		assertEquals(0, result.get().indexInGuide());
		assertSame(gate, result.get().anchor());
	}

	@Test
	public void firstAnchorOnDifferentPlaneIsSkipped()
	{
		// anchors[0] is on plane 1 (player is on plane 0) → skip.
		// anchors[1] is on plane 0 → return.
		InteractionAnchor onP1 = anchorOnPlane(1);
		InteractionAnchor reachable = stairAnchor();
		TrailGuide guide = new TrailGuide(List.of(), List.of(onP1, reachable));
		Optional<AnchorSelector.Active> result = AnchorSelector.selectActive(
			guide, 0, PLAYER_P0, alwaysSuccess(), noDeadEnds(),
			sceneFinderWith(onP1, reachable));
		assertTrue(result.isPresent());
		assertEquals(1, result.get().indexInGuide());
		assertSame(reachable, result.get().anchor());
	}

	@Test
	public void deadEndAnchorIsSkipped()
	{
		// anchors[0] dead-end-flagged → skip.
		// anchors[1] not flagged → return.
		InteractionAnchor first = gateAnchor();
		InteractionAnchor second = stairAnchor();
		TrailGuide guide = new TrailGuide(List.of(), List.of(first, second));
		Predicate<InteractionAnchor> deadEndOnFirst = a -> a == first;
		Optional<AnchorSelector.Active> result = AnchorSelector.selectActive(
			guide, 0, PLAYER_P0, alwaysSuccess(), deadEndOnFirst,
			sceneFinderWith(first, second));
		assertTrue(result.isPresent());
		assertEquals(1, result.get().indexInGuide());
		assertSame(second, result.get().anchor());
	}

	@Test
	public void anchorNotInSceneIsSkipped()
	{
		// anchors[0] not in scene → skip. anchors[1] in scene → return.
		InteractionAnchor first = gateAnchor();
		InteractionAnchor second = stairAnchor();
		TrailGuide guide = new TrailGuide(List.of(), List.of(first, second));
		// SceneFinder only resolves the second anchor.
		Optional<AnchorSelector.Active> result = AnchorSelector.selectActive(
			guide, 0, PLAYER_P0, alwaysSuccess(), noDeadEnds(),
			sceneFinderWith(second));
		assertTrue(result.isPresent());
		assertEquals(1, result.get().indexInGuide());
		assertSame(second, result.get().anchor());
	}

	@Test
	public void blockedEdgeIsSkippedNotReturned()
	{
		// Critical invariant: anchor is approachable ONLY when planner returns
		// Success. BlockedEdge does NOT count — the navigator pipeline opens
		// the blocking object on a later tick, and the selector picks the
		// anchor up once BFS reports Success.
		InteractionAnchor gate = gateAnchor();
		TrailGuide guide = new TrailGuide(List.of(), List.of(gate));
		Optional<AnchorSelector.Active> result = AnchorSelector.selectActive(
			guide, 0, PLAYER_P0, alwaysBlocked(), noDeadEnds(),
			sceneFinderWith(gate));
		assertFalse("BlockedEdge must not be returned as Active",
			result.isPresent());
	}

	@Test
	public void plannerSuccessReturnsActive()
	{
		// Pure-Success path: trivial happy case + verifies the returned
		// Active carries the live scene TileObject (not null).
		InteractionAnchor gate = gateAnchor();
		TrailGuide guide = new TrailGuide(List.of(), List.of(gate));
		Optional<AnchorSelector.Active> result = AnchorSelector.selectActive(
			guide, 0, PLAYER_P0, alwaysSuccess(), noDeadEnds(),
			sceneFinderWith(gate));
		assertTrue(result.isPresent());
		assertEquals(0, result.get().indexInGuide());
		assertSame(gate, result.get().anchor());
		// SceneFinder returns a Mockito mock for matched anchors; assert non-null.
		assertTrue("Active must carry the live scene object",
			result.get().sceneObject() != null);
	}

	@Test
	public void startSearchIndexPastEndReturnsEmpty()
	{
		InteractionAnchor gate = gateAnchor();
		TrailGuide guide = new TrailGuide(List.of(), List.of(gate));
		Optional<AnchorSelector.Active> result = AnchorSelector.selectActive(
			guide, 1, PLAYER_P0, alwaysSuccess(), noDeadEnds(),
			sceneFinderWith(gate));
		assertFalse(result.isPresent());
	}
}
