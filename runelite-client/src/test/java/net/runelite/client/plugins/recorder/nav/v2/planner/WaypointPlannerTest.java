package net.runelite.client.plugins.recorder.nav.v2.planner;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.nav.BehaviorMode;
import net.runelite.client.plugins.recorder.nav.NavRequest;
import net.runelite.client.plugins.recorder.nav.v2.bfs.BfsConfig;
import net.runelite.client.plugins.recorder.nav.v2.collision.CollisionFlags;
import net.runelite.client.plugins.recorder.nav.v2.collision.CollisionView;
import net.runelite.client.plugins.recorder.nav.v2.collision.WorldSnapshot;
import net.runelite.client.plugins.recorder.nav.v2.transport.PathStep;
import net.runelite.client.plugins.recorder.nav.v2.transport.ReplanReason;
import net.runelite.client.plugins.recorder.nav.v2.transport.TransportLink;
import net.runelite.client.plugins.recorder.nav.v2.transport.TransportRequirement;
import net.runelite.client.plugins.recorder.nav.v2.transport.TransportStep;
import net.runelite.client.plugins.recorder.nav.v2.transport.TransportTable;
import net.runelite.client.plugins.recorder.nav.v2.transport.TransportType;
import net.runelite.client.plugins.recorder.nav.v2.transport.V2Path;
import net.runelite.client.plugins.recorder.nav.v2.transport.WalkStep;
import net.runelite.client.plugins.recorder.nav.v2.transport.Waypoint;
import net.runelite.client.plugins.recorder.nav.v2.transport.WaypointType;
import org.junit.Test;

import static org.junit.Assert.*;

public class WaypointPlannerTest
{
	/** Fixture collision view: implements Lane 3's narrow
	 *  {@link net.runelite.client.plugins.recorder.nav.v2.bfs.CollisionView}
	 *  interface (single method {@code flagsAt}). Open by default
	 *  (flagsAt = 0); blockers added via {@link #block} for spot-blocking. */
	static final class FixtureCollision
		implements net.runelite.client.plugins.recorder.nav.v2.bfs.CollisionView
	{
		private final Map<Long, Integer> flags = new HashMap<>();

		void setFlags(WorldPoint p, int f) { flags.put(key(p), f); }
		void block(WorldPoint p) { flags.put(key(p), CollisionDataFlag.BLOCK_MOVEMENT_FULL); }

		@Override
		public int flagsAt(WorldPoint p) { return flags.getOrDefault(key(p), 0); }

		private static long key(WorldPoint p)
		{
			return (((long) p.getX()) << 32) | (p.getY() & 0xFFFFFFFFL) | ((long) p.getPlane() << 60);
		}
	}

	/** Fixture WorldSnapshot wrapping the collision + transport table.
	 *  Returns the Lane 3 narrow CollisionView interface directly — both
	 *  Lane 4 WaypointPlanner and Lane 3 BFS consume that interface. */
	static WorldSnapshot fixtureSnap(
		net.runelite.client.plugins.recorder.nav.v2.bfs.CollisionView cv,
		TransportTable table)
	{
		return new WorldSnapshot()
		{
			@Override public CollisionFlags collisionAt(WorldPoint p)
			{ return new CollisionFlags(cv.flagsAt(p), CollisionView.Source.GLOBAL_SNAPSHOT, p); }
			@Override public net.runelite.client.plugins.recorder.nav.v2.bfs.CollisionView collisionView() { return cv; }
			@Override public Set<WorldPoint> blockingActorTiles() { return Set.of(); }
			@Override public Set<WorldPoint> blockingObjectTiles() { return Set.of(); }
			@Override public TransportTable transports() { return table; }
			@Override public net.runelite.client.plugins.recorder.nav.v2.predicate.PredicateRegistry predicates() { return null; }
			@Override public long capturedAtMs() { return 0L; }
			@Override public WorldPoint playerPosition() { return null; }
		};
	}

	@Test
	public void plan_sameRegion_emitsWalkStepsOnly()
	{
		FixtureCollision cv = new FixtureCollision();
		TransportTable table = new TransportTable(java.util.Collections.emptyList(), 0);
		WorldPoint start = new WorldPoint(3200, 3200, 0);
		WorldPoint target = new WorldPoint(3210, 3210, 0);
		NavRequest req = NavRequest.toPoint(target, BehaviorMode.VARIED);
		V2Path path = WaypointPlanner.plan(req, start, fixtureSnap(cv, table), BfsConfig.defaults());
		assertFalse("planner must succeed for clear corridor", path.isFailed());
		// Only walk steps in the output.
		for (PathStep s : path.steps())
		{
			assertTrue("expected WalkStep only, got " + s.getClass(), s instanceof WalkStep);
		}
		// Some compression must have happened — fewer waypoints than tiles.
		assertTrue("compressed should be < 11 steps for a 10-tile corridor",
			path.steps().size() < 11);
		// Last waypoint targets the requested tile.
		PathStep last = path.steps().get(path.steps().size() - 1);
		assertEquals(target, ((WalkStep) last).waypoint().target());
	}

	@Test
	public void plan_crossPlane_emitsWalkAndTransportStepsInOrder()
	{
		FixtureCollision cv = new FixtureCollision();
		// Stair from (3205, 3205, 0) up to (3205, 3205, 1).
		WorldPoint stairBottom = new WorldPoint(3205, 3205, 0);
		WorldPoint stairTop = new WorldPoint(3205, 3205, 1);
		TransportLink stairs = new TransportLink(
			stairBottom, stairTop, TransportType.STAIRS_UP,
			Optional.of(16671), Optional.of("Climb-up Staircase"),
			TransportRequirement.NONE, 1, false, "test.tsv", 1);
		TransportTable table = new TransportTable(List.of(stairs), 0);
		WorldPoint start = new WorldPoint(3200, 3200, 0);
		WorldPoint target = new WorldPoint(3210, 3210, 1);
		NavRequest req = NavRequest.toPoint(target, BehaviorMode.VARIED);
		V2Path path = WaypointPlanner.plan(req, start, fixtureSnap(cv, table), BfsConfig.defaults());
		assertFalse("must succeed via stair", path.isFailed());
		// Must contain at least one TransportStep.
		boolean hasTransport = path.steps().stream().anyMatch(s -> s instanceof TransportStep);
		assertTrue("must emit a TransportStep for the stair", hasTransport);
		// Order: WalkStep(s) → TransportStep → WalkStep(s).
		int transportIdx = -1;
		for (int i = 0; i < path.steps().size(); i++)
		{
			if (path.steps().get(i) instanceof TransportStep)
			{
				transportIdx = i;
				break;
			}
		}
		assertTrue("transport must not be first or last", transportIdx > 0 && transportIdx < path.steps().size() - 1);
	}

	@Test
	public void plan_transportApproachWaypoint_exactTolerance()
	{
		FixtureCollision cv = new FixtureCollision();
		WorldPoint stairBottom = new WorldPoint(3205, 3205, 0);
		WorldPoint stairTop = new WorldPoint(3205, 3205, 1);
		TransportLink stairs = new TransportLink(
			stairBottom, stairTop, TransportType.STAIRS_UP,
			Optional.of(16671), Optional.of("Climb-up Staircase"),
			TransportRequirement.NONE, 1, false, "test.tsv", 1);
		TransportTable table = new TransportTable(List.of(stairs), 0);
		WorldPoint start = new WorldPoint(3200, 3200, 0);
		WorldPoint target = new WorldPoint(3210, 3210, 1);
		NavRequest req = NavRequest.toPoint(target, BehaviorMode.VARIED);
		V2Path path = WaypointPlanner.plan(req, start, fixtureSnap(cv, table), BfsConfig.defaults());
		// The last WalkStep BEFORE the TransportStep must have
		// type=TRANSPORT_APPROACH + tolerance=0.
		int tIdx = -1;
		for (int i = 0; i < path.steps().size(); i++)
		{
			if (path.steps().get(i) instanceof TransportStep) { tIdx = i; break; }
		}
		assertTrue(tIdx > 0);
		WalkStep approach = (WalkStep) path.steps().get(tIdx - 1);
		assertEquals(WaypointType.TRANSPORT_APPROACH, approach.waypoint().type());
		assertEquals(0, approach.waypoint().toleranceRadius());
		assertTrue(approach.waypoint().exactRequired());
		assertEquals(stairBottom, approach.waypoint().target());
	}

	@Test
	public void plan_normalWalking_emitsLooseTolerance()
	{
		FixtureCollision cv = new FixtureCollision();
		TransportTable table = new TransportTable(java.util.Collections.emptyList(), 0);
		WorldPoint start = new WorldPoint(3200, 3200, 0);
		WorldPoint target = new WorldPoint(3210, 3210, 0);
		NavRequest req = NavRequest.toPoint(target, BehaviorMode.VARIED);
		V2Path path = WaypointPlanner.plan(req, start, fixtureSnap(cv, table), BfsConfig.defaults());
		// At least one waypoint with tolerance >= 1.
		boolean hasLoose = path.steps().stream()
			.filter(s -> s instanceof WalkStep)
			.map(s -> ((WalkStep) s).waypoint())
			.anyMatch(w -> w.toleranceRadius() >= 1);
		assertTrue("expected at least one loose-tolerance waypoint", hasLoose);
	}

	@Test
	public void plan_requirementUnsatisfied_routesAround()
	{
		// A high-Agility shortcut. Player level 30. Planner should
		// route around (no transport step).
		FixtureCollision cv = new FixtureCollision();
		WorldPoint shortcutFrom = new WorldPoint(2870, 3400, 0);
		WorldPoint shortcutTo = new WorldPoint(2870, 3500, 0);
		TransportRequirement gate =
			net.runelite.client.plugins.recorder.nav.v2.transport.TransportRequirementEvaluator
				.requireSkill("Agility", 70);
		TransportLink shortcut = new TransportLink(
			shortcutFrom, shortcutTo, TransportType.AGILITY_SHORTCUT,
			Optional.of(17068), Optional.of("Grapple"),
			gate, 5, false, "test.tsv", 1);
		TransportTable table = new TransportTable(List.of(shortcut), 0);
		WorldPoint start = new WorldPoint(2870, 3400, 0);
		WorldPoint target = new WorldPoint(2870, 3420, 0);
		NavRequest req = NavRequest.toPoint(target, BehaviorMode.VARIED);
		V2Path path = WaypointPlanner.plan(req, start, fixtureSnap(cv, table), BfsConfig.defaults());
		assertFalse(path.isFailed());
		boolean usedTransport = path.steps().stream().anyMatch(s -> s instanceof TransportStep);
		assertFalse("requirement-gated transport must not be picked when unsatisfied",
			usedTransport);
	}

	@Test
	public void plan_unreachableCrossPlane_returnsTypedFailure()
	{
		FixtureCollision cv = new FixtureCollision();
		TransportTable table = new TransportTable(java.util.Collections.emptyList(), 0);
		WorldPoint start = new WorldPoint(3000, 3000, 0);
		WorldPoint target = new WorldPoint(3000, 3000, 1);
		NavRequest req = NavRequest.toPoint(target, BehaviorMode.VARIED);
		V2Path path = WaypointPlanner.plan(req, start, fixtureSnap(cv, table), BfsConfig.defaults());
		assertTrue("must fail with no plane bridge", path.isFailed());
		assertEquals(ReplanReason.TARGET_UNREACHABLE, path.failureReason().orElseThrow());
	}

	@Test
	public void plan_nullStart_returnsFailure()
	{
		FixtureCollision cv = new FixtureCollision();
		TransportTable table = new TransportTable(java.util.Collections.emptyList(), 0);
		NavRequest req = NavRequest.toPoint(new WorldPoint(3200, 3200, 0), BehaviorMode.VARIED);
		V2Path path = WaypointPlanner.plan(req, /*start*/ null, fixtureSnap(cv, table), BfsConfig.defaults());
		assertTrue(path.isFailed());
		// Integration: null start now maps to REGION_NOT_LOADED (the
		// snapshot's playerPosition() was null). Before integration this
		// was TARGET_UNREACHABLE.
		assertEquals(ReplanReason.REGION_NOT_LOADED, path.failureReason().orElseThrow());
	}

	@Test
	public void plan_nullTarget_returnsFailure()
	{
		FixtureCollision cv = new FixtureCollision();
		TransportTable table = new TransportTable(java.util.Collections.emptyList(), 0);
		// NavRequest with trail-only (no to) → planner needs a target.
		NavRequest req = NavRequest.byTrail("dummy", BehaviorMode.VARIED);
		V2Path path = WaypointPlanner.plan(req, new WorldPoint(3200, 3200, 0),
			fixtureSnap(cv, table), BfsConfig.defaults());
		assertTrue(path.isFailed());
		assertEquals(ReplanReason.TARGET_UNREACHABLE, path.failureReason().orElseThrow());
	}

	@Test
	public void plan_targetEqualsStart_emitsTrivialPath()
	{
		FixtureCollision cv = new FixtureCollision();
		TransportTable table = new TransportTable(java.util.Collections.emptyList(), 0);
		WorldPoint tile = new WorldPoint(3200, 3200, 0);
		NavRequest req = NavRequest.toPoint(tile, BehaviorMode.VARIED);
		V2Path path = WaypointPlanner.plan(req, tile, fixtureSnap(cv, table), BfsConfig.defaults());
		assertFalse(path.isFailed());
		assertFalse(path.steps().isEmpty());
		Waypoint w = ((WalkStep) path.steps().get(0)).waypoint();
		assertEquals(tile, w.target());
	}
}
