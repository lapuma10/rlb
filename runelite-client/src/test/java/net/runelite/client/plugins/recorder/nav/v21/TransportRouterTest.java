package net.runelite.client.plugins.recorder.nav.v21;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.TileObject;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.nav.v2.bfs.CollisionView;
import net.runelite.client.plugins.recorder.worldmap.TransportEdge;
import net.runelite.client.plugins.recorder.worldmap.TransportIndex;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

/** Verifies {@link TransportRouter#findNext} picks the lowest-estimated-cost
 *  reachable transport edge whose:
 *  <ul>
 *    <li>fromTile is on the player's plane,</li>
 *    <li>isn't blacklisted,</li>
 *    <li>has a live scene object,</li>
 *    <li>has an approach tile BFS-reachable from the player on the
 *        player-plane collision view, AND</li>
 *    <li>has a non-infinite forward cost to the goal (possibly chained
 *        through more transports up to {@link
 *        TransportRouter#MAX_CHAIN_DEPTH}).</li>
 *  </ul>
 *
 *  <p>The router's reachability primitive is collision-BFS on each
 *  plane's {@link CollisionView}. When a plane's view is unloaded
 *  ({@code null} or {@link LiveCollisionView#EMPTY}), the router falls
 *  back to Chebyshev only on segments between transports — the
 *  player-plane approach check stays strictly BFS, so we never click a
 *  transport whose approach BFS isn't reachable. */
public class TransportRouterTest
{
	private static final int STAIR_A = 100;   // P0 -> P1 (chain hop 1)
	private static final int STAIR_B = 101;   // P1 -> P2 (chain hop 2)
	private static final int LADDER  = 102;   // alternate edge on player plane
	private static final int GATE    = 103;   // edge with blocked approach

	// --- Collision view stubs --------------------------------------------

	/** Returns 0 everywhere — every step passes {@link
	 *  net.runelite.client.plugins.recorder.nav.v2.bfs.SkretzoBfsKernel#canMove}. */
	private static CollisionView passable()
	{
		return p -> 0;
	}

	/** Returns BLOCK_MOVEMENT_FULL everywhere — every step is blocked. */
	private static CollisionView blocked()
	{
		return p -> CollisionDataFlag.BLOCK_MOVEMENT_FULL;
	}

	/** Returns 0 for most tiles but BLOCK_MOVEMENT_FULL for a fenced set
	 *  of "wall" tiles. Used to make one specific edge's approach tile
	 *  unreachable while leaving the rest of the plane passable. */
	private static CollisionView wallAround(WorldPoint wallTile)
	{
		Set<WorldPoint> walls = new HashSet<>();
		// Block the approach tile itself AND every tile in a 1-tile ring
		// around it, so BFS can't reach it from any neighbor.
		walls.add(wallTile);
		for (int dx = -1; dx <= 1; dx++)
		{
			for (int dy = -1; dy <= 1; dy++)
			{
				walls.add(new WorldPoint(wallTile.getX() + dx,
					wallTile.getY() + dy, wallTile.getPlane()));
			}
		}
		return p -> walls.contains(p) ? CollisionDataFlag.BLOCK_MOVEMENT_FULL : 0;
	}

	/** Build a 4-element plane array; planes not provided default to
	 *  passable. */
	private static CollisionView[] planes(CollisionView p0, CollisionView p1,
		CollisionView p2, CollisionView p3)
	{
		return new CollisionView[]{
			p0 == null ? passable() : p0,
			p1 == null ? passable() : p1,
			p2 == null ? passable() : p2,
			p3 == null ? passable() : p3,
		};
	}

	// --- Fixture builders ------------------------------------------------

	/** Edge with explicit fromTile/toTile, default fields elsewhere. */
	private static TransportEdge edge(int objectId, WorldPoint from, WorldPoint to)
	{
		return edge(objectId, from, to, from);
	}

	private static TransportEdge edge(int objectId, WorldPoint from, WorldPoint to,
		WorldPoint approach)
	{
		return new TransportEdge(
			from, to, objectId, "Stair", "Climb",
			-1, -1, "GameObject",
			approach, 0, 1, 0L, 0L);
	}

	private static TransportIndex indexOf(TransportEdge... edges)
	{
		TransportIndex ix = new TransportIndex();
		for (TransportEdge e : edges) ix.add(e);
		return ix;
	}

	/** SceneFinder that returns a mock TileObject for the given objectIds. */
	private static TransportRouter.SceneFinder sceneWith(int... objectIds)
	{
		Set<Integer> known = new HashSet<>();
		for (int id : objectIds) known.add(id);
		return (objectId, near, radius) -> known.contains(objectId) ? mock(TileObject.class) : null;
	}

	private static Predicate<TransportEdge> noBlacklist() { return e -> false; }

	// --- Tests -----------------------------------------------------------

	@Test
	public void emptyIndexReturnsEmpty()
	{
		WorldPoint player = new WorldPoint(3200, 3200, 0);
		WorldPoint goal = new WorldPoint(3250, 3250, 0);
		Optional<TransportCandidate> result = TransportRouter.findNext(
			player, goal, indexOf(),
			planes(null, null, null, null),
			sceneWith(),
			noBlacklist());
		assertFalse(result.isPresent());
	}

	@Test
	public void edgeOnDifferentSourcePlaneIsSkipped()
	{
		WorldPoint player = new WorldPoint(3200, 3200, 0);
		WorldPoint goal = new WorldPoint(3210, 3210, 0);
		// Stair on plane 1 (player is on plane 0) → skip.
		TransportEdge wrongPlane = edge(STAIR_A,
			new WorldPoint(3205, 3205, 1),
			new WorldPoint(3206, 3206, 0));
		Optional<TransportCandidate> result = TransportRouter.findNext(
			player, goal, indexOf(wrongPlane),
			planes(null, null, null, null),
			sceneWith(STAIR_A),
			noBlacklist());
		assertFalse(result.isPresent());
	}

	@Test
	public void singleEdgeReachesGoalPlaneReturnsCandidate()
	{
		// Player on P0, goal on P1. One stair P0→P1 with reachable approach.
		WorldPoint player = new WorldPoint(3200, 3200, 0);
		WorldPoint goal = new WorldPoint(3210, 3210, 1);
		TransportEdge stair = edge(STAIR_A,
			new WorldPoint(3205, 3205, 0),
			new WorldPoint(3205, 3205, 1));
		Optional<TransportCandidate> result = TransportRouter.findNext(
			player, goal, indexOf(stair),
			planes(null, null, null, null),
			sceneWith(STAIR_A),
			noBlacklist());
		assertTrue(result.isPresent());
		assertEquals(stair.key(), result.get().edge().key());
		assertNotNull(result.get().executable());
		assertNotNull(result.get().executable().object());
		assertEquals("Climb", result.get().executable().verb());
	}

	@Test
	public void unreachableApproachIsSkippedInFavorOfReachable()
	{
		// Two edges both reach plane 1; ladder's approach is BFS-blocked,
		// stair's is reachable. Router must pick the stair.
		WorldPoint player = new WorldPoint(3200, 3200, 0);
		WorldPoint goal = new WorldPoint(3210, 3210, 1);
		WorldPoint stairApproach = new WorldPoint(3205, 3205, 0);
		WorldPoint ladderApproach = new WorldPoint(3220, 3220, 0);
		TransportEdge stair = edge(STAIR_A, stairApproach,
			new WorldPoint(3205, 3205, 1));
		TransportEdge ladder = edge(LADDER, ladderApproach,
			new WorldPoint(3220, 3220, 1));
		// Wall around ladder approach makes it unreachable; rest passable.
		CollisionView p0View = wallAround(ladderApproach);
		Optional<TransportCandidate> result = TransportRouter.findNext(
			player, goal, indexOf(stair, ladder),
			planes(p0View, null, null, null),
			sceneWith(STAIR_A, LADDER),
			noBlacklist());
		assertTrue(result.isPresent());
		assertEquals("must pick the BFS-reachable approach",
			stair.key(), result.get().edge().key());
	}

	@Test
	public void twoHopChainReachesGoalPlane()
	{
		// Player P0, goal P2. edgeA: P0→P1, edgeB: P1→P2.
		// edgeB's approach (on P1) is within MAX_INTER_TRANSPORT_WALK
		// of edgeA's toTile. Router picks edgeA as the immediate hop.
		WorldPoint player = new WorldPoint(3200, 3200, 0);
		WorldPoint goal = new WorldPoint(3230, 3230, 2);
		WorldPoint a0 = new WorldPoint(3205, 3205, 0);  // edgeA from
		WorldPoint a1 = new WorldPoint(3206, 3205, 1);  // edgeA to (lands on P1)
		WorldPoint b1 = new WorldPoint(3215, 3210, 1);  // edgeB from (P1)
		WorldPoint b2 = new WorldPoint(3215, 3210, 2);  // edgeB to (P2)
		TransportEdge edgeA = edge(STAIR_A, a0, a1);
		TransportEdge edgeB = edge(STAIR_B, b1, b2);
		// All planes passable: player→a0 BFS reaches; forward estimate
		// chains P1 walk a1→b1, then b2→goal same-plane BFS.
		Optional<TransportCandidate> result = TransportRouter.findNext(
			player, goal, indexOf(edgeA, edgeB),
			planes(null, null, null, null),
			sceneWith(STAIR_A, STAIR_B),
			noBlacklist());
		assertTrue("two-hop chain should produce a candidate", result.isPresent());
		assertEquals("router picks edgeA (the immediate hop)",
			edgeA.key(), result.get().edge().key());
	}

	@Test
	public void chainBlockedOnIntermediatePlaneIsRejected()
	{
		// Same shape as the prior test, but P1 has a wall around edgeB's
		// approach so the forward-estimate's BFS from edgeA.toTile to
		// edgeB.approachTile returns +Infinity. With no other route to
		// the goal plane, the router returns empty.
		WorldPoint player = new WorldPoint(3200, 3200, 0);
		WorldPoint goal = new WorldPoint(3230, 3230, 2);
		WorldPoint a0 = new WorldPoint(3205, 3205, 0);
		WorldPoint a1 = new WorldPoint(3206, 3205, 1);
		WorldPoint b1 = new WorldPoint(3215, 3210, 1);
		WorldPoint b2 = new WorldPoint(3215, 3210, 2);
		TransportEdge edgeA = edge(STAIR_A, a0, a1);
		TransportEdge edgeB = edge(STAIR_B, b1, b2);
		// P1 blocked around edgeB's approach so chain can't bridge.
		CollisionView p1Wall = wallAround(b1);
		Optional<TransportCandidate> result = TransportRouter.findNext(
			player, goal, indexOf(edgeA, edgeB),
			planes(null, p1Wall, null, null),
			sceneWith(STAIR_A, STAIR_B),
			noBlacklist());
		assertFalse("chain whose P1 segment is BFS-blocked must be rejected",
			result.isPresent());
	}

	@Test
	public void chainOverEmptyPlaneFallsBackToChebyshev()
	{
		// Same chain as the prior tests, but the intermediate plane (P1)
		// has an EMPTY collision view. The router's BFS would be useless
		// there, so it falls back to Chebyshev; the edges are close
		// enough (~10 tiles) so the chain is accepted.
		WorldPoint player = new WorldPoint(3200, 3200, 0);
		WorldPoint goal = new WorldPoint(3230, 3230, 2);
		WorldPoint a0 = new WorldPoint(3205, 3205, 0);
		WorldPoint a1 = new WorldPoint(3206, 3205, 1);
		WorldPoint b1 = new WorldPoint(3215, 3210, 1);
		WorldPoint b2 = new WorldPoint(3215, 3210, 2);
		TransportEdge edgeA = edge(STAIR_A, a0, a1);
		TransportEdge edgeB = edge(STAIR_B, b1, b2);
		CollisionView[] views = planes(null, null, null, null);
		// Override P1 with the EMPTY sentinel.
		views[1] = LiveCollisionView.EMPTY;
		Optional<TransportCandidate> result = TransportRouter.findNext(
			player, goal, indexOf(edgeA, edgeB),
			views,
			sceneWith(STAIR_A, STAIR_B),
			noBlacklist());
		assertTrue("EMPTY intermediate plane falls back to Chebyshev",
			result.isPresent());
		assertEquals(edgeA.key(), result.get().edge().key());
	}

	@Test
	public void blacklistedEdgeIsSkipped()
	{
		// One edge, blacklisted → empty.
		WorldPoint player = new WorldPoint(3200, 3200, 0);
		WorldPoint goal = new WorldPoint(3210, 3210, 1);
		TransportEdge stair = edge(STAIR_A,
			new WorldPoint(3205, 3205, 0),
			new WorldPoint(3205, 3205, 1));
		Predicate<TransportEdge> blacklistStair = e -> e.objectId() == STAIR_A;
		Optional<TransportCandidate> result = TransportRouter.findNext(
			player, goal, indexOf(stair),
			planes(null, null, null, null),
			sceneWith(STAIR_A),
			blacklistStair);
		assertFalse(result.isPresent());
	}

	@Test
	public void edgeWithObjectNotInSceneIsSkipped()
	{
		// One edge, but the SceneFinder doesn't resolve its objectId.
		WorldPoint player = new WorldPoint(3200, 3200, 0);
		WorldPoint goal = new WorldPoint(3210, 3210, 1);
		TransportEdge gate = edge(GATE,
			new WorldPoint(3205, 3205, 0),
			new WorldPoint(3205, 3205, 1));
		// SceneFinder returns null for everything.
		Optional<TransportCandidate> result = TransportRouter.findNext(
			player, goal, indexOf(gate),
			planes(null, null, null, null),
			sceneWith(),
			noBlacklist());
		assertFalse(result.isPresent());
	}
}
