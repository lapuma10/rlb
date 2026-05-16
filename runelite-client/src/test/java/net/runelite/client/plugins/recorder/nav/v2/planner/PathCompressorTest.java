package net.runelite.client.plugins.recorder.nav.v2.planner;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.nav.v2.transport.PathStep;
import net.runelite.client.plugins.recorder.nav.v2.transport.TransportLeg;
import net.runelite.client.plugins.recorder.nav.v2.transport.TransportRequirement;
import net.runelite.client.plugins.recorder.nav.v2.transport.TransportStep;
import net.runelite.client.plugins.recorder.nav.v2.transport.TransportType;
import net.runelite.client.plugins.recorder.nav.v2.transport.WalkStep;
import net.runelite.client.plugins.recorder.nav.v2.transport.Waypoint;
import net.runelite.client.plugins.recorder.nav.v2.transport.WaypointType;
import org.junit.Test;

import static org.junit.Assert.*;

public class PathCompressorTest
{
	@Test
	public void compress_straightRunOf10Tiles_emitsTwoAnchors()
	{
		// All N-direction; no corners.
		List<WorldPoint> tiles = new ArrayList<>();
		for (int y = 3200; y < 3210; y++) tiles.add(new WorldPoint(3200, y, 0));
		List<WalkStep> out = PathCompressor.compressLeg(tiles, WaypointType.WALK, WaypointType.WALK);
		// Start + end only.
		assertEquals(2, out.size());
		assertEquals(new WorldPoint(3200, 3200, 0), out.get(0).waypoint().target());
		assertEquals(new WorldPoint(3200, 3209, 0), out.get(1).waypoint().target());
		// Both loose-tolerance.
		assertTrue(out.get(0).waypoint().toleranceRadius() >= 1);
		assertTrue(out.get(1).waypoint().toleranceRadius() >= 1);
	}

	@Test
	public void compress_singleCorner_emitsThreeAnchors()
	{
		// 5 N then 5 E.
		List<WorldPoint> tiles = new ArrayList<>();
		for (int y = 3200; y < 3205; y++) tiles.add(new WorldPoint(3200, y, 0));
		for (int x = 3201; x < 3206; x++) tiles.add(new WorldPoint(x, 3204, 0));
		List<WalkStep> out = PathCompressor.compressLeg(tiles, WaypointType.WALK, WaypointType.WALK);
		// Start + corner + end.
		assertEquals(3, out.size());
		// Middle anchor at the corner: (3200, 3204, 0).
		assertEquals(new WorldPoint(3200, 3204, 0), out.get(1).waypoint().target());
		// Corner type stays WALK; tolerance is the corner tolerance.
		assertEquals(WaypointType.WALK, out.get(1).waypoint().type());
		assertEquals(PathCompressor.CORNER_TOLERANCE, out.get(1).waypoint().toleranceRadius());
	}

	@Test
	public void compress_transportApproachLast_setsToleranceZero()
	{
		List<WorldPoint> tiles = new ArrayList<>();
		for (int y = 3200; y < 3206; y++) tiles.add(new WorldPoint(3200, y, 0));
		List<WalkStep> out = PathCompressor.compressLeg(
			tiles, WaypointType.WALK, WaypointType.TRANSPORT_APPROACH);
		// Last waypoint must be tolerance=0 + TRANSPORT_APPROACH.
		WalkStep last = out.get(out.size() - 1);
		assertEquals(WaypointType.TRANSPORT_APPROACH, last.waypoint().type());
		assertEquals(0, last.waypoint().toleranceRadius());
		assertTrue(last.waypoint().exactRequired());
	}

	@Test
	public void compress_planeChangeMidSequence_forcesAnchorsAroundJump()
	{
		// Walk on plane 0, then a tile on plane 1 (BFS shouldn't actually
		// produce this; we test the defensive branch in the compressor).
		List<WorldPoint> tiles = new ArrayList<>();
		tiles.add(new WorldPoint(3200, 3200, 0));
		tiles.add(new WorldPoint(3200, 3201, 0));
		tiles.add(new WorldPoint(3200, 3201, 1));
		tiles.add(new WorldPoint(3200, 3202, 1));
		List<WalkStep> out = PathCompressor.compressLeg(tiles, WaypointType.WALK, WaypointType.WALK);
		// Must include the plane-0 anchor at (3200, 3201, 0) and the
		// plane-1 anchor at (3200, 3201, 1) as bridge points.
		boolean sawBridge0 = false, sawBridge1 = false;
		for (WalkStep w : out)
		{
			if (w.waypoint().target().equals(new WorldPoint(3200, 3201, 0))) sawBridge0 = true;
			if (w.waypoint().target().equals(new WorldPoint(3200, 3201, 1))) sawBridge1 = true;
		}
		assertTrue("expected pre-plane-change anchor at (3200,3201,0)", sawBridge0);
		assertTrue("expected post-plane-change anchor at (3200,3201,1)", sawBridge1);
	}

	@Test
	public void compress_longSequence_compressesTo10orFewer()
	{
		// 300-tile straight + a few corners. Total compressed must be
		// in the spec §4 Lane 4 invariant range (~5-15 sparse).
		List<WorldPoint> tiles = new ArrayList<>();
		for (int y = 3200; y < 3300; y++) tiles.add(new WorldPoint(3200, y, 0));      // 100 N
		for (int x = 3201; x < 3301; x++) tiles.add(new WorldPoint(x, 3299, 0));      // 100 E
		for (int y = 3298; y >= 3199; y--) tiles.add(new WorldPoint(3300, y, 0));     // 100 S
		List<WalkStep> out = PathCompressor.compressLeg(tiles, WaypointType.WALK, WaypointType.WALK);
		assertTrue("expected compression to <= 15 waypoints, got " + out.size(), out.size() <= 15);
		assertTrue("expected at least 4 anchors (start, 2 corners, end), got " + out.size(),
			out.size() >= 4);
	}

	@Test
	public void compress_singleTileLeg_emitsOneWaypoint()
	{
		List<WalkStep> out = PathCompressor.compressLeg(
			List.of(new WorldPoint(3200, 3200, 0)),
			WaypointType.WALK, WaypointType.WALK);
		assertEquals(1, out.size());
	}

	@Test
	public void compress_emptyLeg_emitsNothing()
	{
		List<WalkStep> out = PathCompressor.compressLeg(
			java.util.Collections.emptyList(), WaypointType.WALK, WaypointType.WALK);
		assertTrue(out.isEmpty());
	}

	@Test
	public void compress_exactRequiredAnchorTypes_alwaysToleranceZero()
	{
		// First-anchor TRANSPORT_APPROACH → tolerance 0.
		List<WorldPoint> tiles = List.of(new WorldPoint(3200, 3200, 0));
		WalkStep w = PathCompressor.compressLeg(
			tiles, WaypointType.TRANSPORT_APPROACH, WaypointType.TRANSPORT_APPROACH).get(0);
		assertEquals(0, w.waypoint().toleranceRadius());
		assertTrue(w.waypoint().exactRequired());
	}

	@Test
	public void compress_invariants_examined()
	{
		// Spec §4 Lane 4 invariants enumerated:
		List<WorldPoint> tiles = new ArrayList<>();
		for (int y = 3200; y < 3210; y++) tiles.add(new WorldPoint(3200, y, 0));
		List<WalkStep> out = PathCompressor.compressLeg(
			tiles, WaypointType.WALK, WaypointType.TRANSPORT_APPROACH);

		// Invariant: TRANSPORT_APPROACH at end retains exact tolerance.
		Waypoint last = out.get(out.size() - 1).waypoint();
		assertEquals(WaypointType.TRANSPORT_APPROACH, last.type());
		assertTrue(last.exactRequired());

		// Invariant: no waypoint with TRANSPORT_APPROACH ever has tolerance > 0.
		for (WalkStep w : out)
		{
			if (w.waypoint().type() == WaypointType.TRANSPORT_APPROACH)
			{
				assertEquals("TRANSPORT_APPROACH must always be exact",
					0, w.waypoint().toleranceRadius());
			}
		}
	}

	@Test
	public void assemble_walksAndTransportsInterleavedInOrder()
	{
		WalkStep w1 = new WalkStep(new Waypoint(new WorldPoint(3200, 3200, 0), 2, WaypointType.WALK));
		WalkStep w2 = new WalkStep(new Waypoint(new WorldPoint(3210, 3210, 0), 2, WaypointType.WALK));
		WalkStep w3 = new WalkStep(new Waypoint(new WorldPoint(3220, 3220, 1), 2, WaypointType.WALK));

		TransportLeg t1 = new TransportLeg(
			new WorldPoint(3210, 3210, 0), new WorldPoint(3210, 3210, 1),
			TransportType.STAIRS_UP, Optional.of(16671), Optional.of("Climb-up"),
			TransportRequirement.NONE);

		List<List<WalkStep>> legs = new ArrayList<>();
		legs.add(List.of(w1, w2));
		legs.add(List.of(w3));
		List<TransportLeg> transports = List.of(t1);

		List<PathStep> out = PathCompressor.assemble(legs, transports);
		assertEquals(4, out.size());
		assertEquals(w1, out.get(0));
		assertEquals(w2, out.get(1));
		assertTrue(out.get(2) instanceof TransportStep);
		assertEquals(t1, ((TransportStep) out.get(2)).transport());
		assertEquals(w3, out.get(3));
	}
}
