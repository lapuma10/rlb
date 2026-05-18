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
		// Same shape as the prior test, but P1 is fully blocked so the
		// forward-estimate's BFS from edgeA.toTile to edgeB.approachTile
		// has no walkable tile at all — the BFS queue empties immediately
		// (no neighbor passes canMove from the start tile) and returns
		// BFS_UNREACHABLE → +Infinity. With no other route to the goal
		// plane, the router returns empty.
		//
		// (A localized wall around b1 in an otherwise-passable plane
		// would NOT prove unreachable within budget — BFS would keep
		// exploring the open space and hit the expansion cap, which the
		// new sentinel logic correctly treats as "unknown" → Chebyshev
		// fallback, NOT +Infinity. To exercise the provably-blocked
		// branch we need a view where BFS provably empties its queue.)
		WorldPoint player = new WorldPoint(3200, 3200, 0);
		WorldPoint goal = new WorldPoint(3230, 3230, 2);
		WorldPoint a0 = new WorldPoint(3205, 3205, 0);
		WorldPoint a1 = new WorldPoint(3206, 3205, 1);
		WorldPoint b1 = new WorldPoint(3215, 3210, 1);
		WorldPoint b2 = new WorldPoint(3215, 3210, 2);
		TransportEdge edgeA = edge(STAIR_A, a0, a1);
		TransportEdge edgeB = edge(STAIR_B, b1, b2);
		// P1 fully blocked → BFS proves unreachable (queue empties).
		Optional<TransportCandidate> result = TransportRouter.findNext(
			player, goal, indexOf(edgeA, edgeB),
			planes(null, blocked(), null, null),
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

	// --- bfsDistance / bfsOrChebyshev sentinel behaviour ---------------
	//
	// These exercise the underlying primitives directly (both are
	// package-private). The sentinels distinguish "provably unreachable"
	// (queue emptied) from "budget exhausted" (cap hit before queue
	// empties); bfsOrChebyshev maps the former to +Infinity (prune) and
	// the latter to Chebyshev (don't false-negative skip the candidate).

	@Test
	public void bfsDistanceReachableReturnsDistance()
	{
		WorldPoint from = new WorldPoint(3200, 3200, 0);
		WorldPoint to = new WorldPoint(3205, 3205, 0);  // Chebyshev 5
		int d = TransportRouter.bfsDistance(from, to, passable(), TransportRouter.BFS_BUDGET);
		assertEquals("BFS in passable view returns true distance", 5, d);
	}

	@Test
	public void bfsDistanceFullyBlockedReturnsUnreachableSentinel()
	{
		// Whole plane is blocked → BFS from start has no valid neighbour;
		// queue empties on the first poll → BFS_UNREACHABLE.
		WorldPoint from = new WorldPoint(3200, 3200, 0);
		WorldPoint to = new WorldPoint(3205, 3205, 0);
		int d = TransportRouter.bfsDistance(from, to, blocked(), TransportRouter.BFS_BUDGET);
		assertEquals("queue empties without reach → BFS_UNREACHABLE (-1)",
			TransportRouter.BFS_UNREACHABLE, d);
	}

	@Test
	public void bfsDistanceBudgetExhaustedReturnsExhaustedSentinel()
	{
		// Passable view, but goal far beyond budget. With a tiny budget
		// of 4, BFS pops <= 4 cells from the queue and bails before
		// reaching anything > a couple tiles away.
		WorldPoint from = new WorldPoint(3200, 3200, 0);
		WorldPoint to = new WorldPoint(3300, 3300, 0);  // Chebyshev 100
		int d = TransportRouter.bfsDistance(from, to, passable(), 4);
		assertEquals("budget hit before queue empties → BFS_BUDGET_EXHAUSTED (-2)",
			TransportRouter.BFS_BUDGET_EXHAUSTED, d);
	}

	@Test
	public void bfsOrChebyshevPassableViewReturnsBfsDistance()
	{
		WorldPoint a = new WorldPoint(3200, 3200, 0);
		WorldPoint b = new WorldPoint(3205, 3205, 0);
		double v = TransportRouter.bfsOrChebyshev(a, b, passable());
		assertEquals("passable BFS reach → returns the BFS distance",
			5.0, v, 0.0);
	}

	@Test
	public void bfsOrChebyshevBlockedReturnsInfinity()
	{
		WorldPoint a = new WorldPoint(3200, 3200, 0);
		WorldPoint b = new WorldPoint(3205, 3205, 0);
		double v = TransportRouter.bfsOrChebyshev(a, b, blocked());
		assertTrue("fully-blocked view → +Infinity (prune the edge)",
			Double.isInfinite(v) && v > 0);
	}

	@Test
	public void bfsOrChebyshevBudgetExhaustedFallsBackToChebyshev()
	{
		// Goal at Chebyshev 100 on a passable plane is well beyond
		// BFS_BUDGET=8192 (which covers ~Chebyshev 45). Confirm
		// bfsOrChebyshev returns the Chebyshev estimate, NOT +Infinity.
		// This is the policy change: a BFS that runs out of expansion
		// budget must not false-negative skip the transport.
		WorldPoint a = new WorldPoint(3200, 3200, 0);
		WorldPoint b = new WorldPoint(3300, 3300, 0);  // Chebyshev 100
		double v = TransportRouter.bfsOrChebyshev(a, b, passable());
		assertFalse("budget exhausted must NOT collapse to +Infinity",
			Double.isInfinite(v));
		assertEquals("budget exhausted → Chebyshev fallback (100)",
			100.0, v, 0.0);
	}

	@Test
	public void budgetExhaustedFallsBackToChebyshev()
	{
		// End-to-end sanity check through findNext: place the second
		// hop's approach at a Chebyshev distance that exceeds BFS_BUDGET
		// reach (~45) but stays within MAX_INTER_TRANSPORT_WALK (=64).
		// On the previous code path (no sentinel distinction) BFS would
		// return Integer.MAX_VALUE → +Infinity, the chain would be
		// pruned, and the router would return empty. With the new policy
		// budget-exhausted falls back to Chebyshev, the chain stays
		// alive, and the router returns the immediate hop.
		//
		// Player P0, goal P2. edgeA P0→P1, edgeB P1→P2. On P1, a1 is
		// ~60 tiles from b1 — Chebyshev = 60 (< MAX_INTER_TRANSPORT_WALK
		// = 64) but BFS in an open plane can't enumerate that far within
		// BFS_BUDGET = 8192 (would need ~(2*60+1)^2 = 14641 expansions).
		WorldPoint player = new WorldPoint(3200, 3200, 0);
		WorldPoint goal = new WorldPoint(3270, 3270, 2);
		WorldPoint a0 = new WorldPoint(3205, 3205, 0);   // edgeA from
		WorldPoint a1 = new WorldPoint(3206, 3205, 1);   // edgeA to
		WorldPoint b1 = new WorldPoint(3266, 3265, 1);   // edgeB from (~60 from a1)
		WorldPoint b2 = new WorldPoint(3266, 3265, 2);   // edgeB to
		TransportEdge edgeA = edge(STAIR_A, a0, a1);
		TransportEdge edgeB = edge(STAIR_B, b1, b2);
		Optional<TransportCandidate> result = TransportRouter.findNext(
			player, goal, indexOf(edgeA, edgeB),
			planes(null, null, null, null),
			sceneWith(STAIR_A, STAIR_B),
			noBlacklist());
		assertTrue("budget-exhausted inter-transport walk must NOT silently"
				+ " prune the chain — Chebyshev fallback keeps it alive",
			result.isPresent());
		assertEquals("router picks the immediate hop edgeA",
			edgeA.key(), result.get().edge().key());
	}
}
