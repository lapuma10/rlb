package net.runelite.client.plugins.recorder.nav.v21;

import java.util.List;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.trail.Trail;
import net.runelite.client.plugins.recorder.trail.TrailEvent;
import org.junit.Test;
import static org.junit.Assert.*;

/** Verifies {@link TrailGuide#fromTrail(Trail)} correctly slices a recorded
 *  trail into a corridor (Tile events) and ordered anchors (Transport events),
 *  and that {@link InteractionAnchor#isTransportAnchor()} classifies anchors
 *  by the observed post-transport plane when available, falling back to the
 *  LOCAL_VERBS set when the trail ends on a Transport. */
public class TrailGuideTest
{
	@Test
	public void emptyTrailProducesEmptyGuide()
	{
		Trail trail = new Trail("empty", 0L, List.of());
		TrailGuide guide = TrailGuide.fromTrail(trail);
		assertTrue(guide.corridor().isEmpty());
		assertTrue(guide.anchors().isEmpty());
	}

	@Test
	public void nullTrailProducesEmptyGuide()
	{
		TrailGuide guide = TrailGuide.fromTrail(null);
		assertTrue(guide.corridor().isEmpty());
		assertTrue(guide.anchors().isEmpty());
	}

	@Test
	public void walkOnlyTrailHasCorridorAndNoAnchors()
	{
		Trail trail = new Trail("walk-only", 0L, List.of(
			tile(0,    3206, 3220, 0),
			tile(600,  3206, 3221, 0),
			tile(1200, 3206, 3222, 0)));
		TrailGuide guide = TrailGuide.fromTrail(trail);
		assertEquals(3, guide.corridor().size());
		assertEquals(new WorldPoint(3206, 3220, 0), guide.corridor().get(0));
		assertEquals(new WorldPoint(3206, 3222, 0), guide.corridor().get(2));
		assertTrue(guide.anchors().isEmpty());
	}

	@Test
	public void samePlaneTransportClassifiedAsLocalDoor()
	{
		// Open Gate at (3211, 3242, p=0), all subsequent tiles still p=0.
		// Lookahead never finds a plane-differing Tile → observedDestPlane
		// stays null and the verb fallback ("Open" ∈ LOCAL_VERBS) classifies
		// the anchor as a local door.
		Trail trail = new Trail("gate", 0L, List.of(
			tile(0,    3210, 3242, 0),
			openGate(600, 3211, 3242, 0),
			tile(1200, 3212, 3242, 0)));
		TrailGuide guide = TrailGuide.fromTrail(trail);
		assertEquals(1, guide.anchors().size());
		InteractionAnchor a = guide.anchors().get(0);
		assertNull("same-plane transport: no plane-differing Tile to observe",
			a.observedDestPlane());
		assertFalse("same-plane transport must classify as local door (not transport)",
			a.isTransportAnchor());
	}

	@Test
	public void midWalkSamePlaneTileIsSkippedToFindRealPlaneChange()
	{
		// Regression: the pen→castle staircase records a mid-walk Tile event
		// BEFORE the plane change propagates:
		//   TRANSPORT (3204, 3229, p=0) Climb-up
		//   TILE      (3205, 3228, p=0)   ← mid-walk, still source plane
		//   TILE      (3206, 3229, p=1)   ← real plane-change tile
		// The OLD "first Tile after Transport wins" logic picked p=0 and
		// mis-classified the staircase as a local door. The fixed lookahead
		// scans forward for the first Tile whose plane DIFFERS from source.
		Trail trail = new Trail("stair-with-midwalk", 0L, List.of(
			tile(0,    3204, 3229, 0),
			climbUp(600, 3204, 3229, 0),    // Transport, source plane = 0
			tile(1200, 3205, 3228, 0),      // mid-walk, still p=0
			tile(1800, 3206, 3229, 1)));    // real plane-change tile
		TrailGuide guide = TrailGuide.fromTrail(trail);
		assertEquals(1, guide.anchors().size());
		InteractionAnchor a = guide.anchors().get(0);
		assertEquals("must skip the mid-walk same-plane Tile and observe p=1",
			Integer.valueOf(1), a.observedDestPlane());
		assertTrue("staircase must classify as transport anchor",
			a.isTransportAnchor());
	}

	@Test
	public void planeChangingTransportClassifiedAsTransport()
	{
		// Climb-up at (3211, 3242, p=0), next tile event on p=1.
		Trail trail = new Trail("stairs-up", 0L, List.of(
			tile(0,    3210, 3242, 0),
			climbUp(600, 3211, 3242, 0),
			tile(1200, 3211, 3243, 1)));
		TrailGuide guide = TrailGuide.fromTrail(trail);
		assertEquals(1, guide.anchors().size());
		InteractionAnchor a = guide.anchors().get(0);
		assertEquals(Integer.valueOf(1), a.observedDestPlane());
		assertTrue("plane-changing transport must classify as transport anchor",
			a.isTransportAnchor());
	}

	@Test
	public void transportAtEndOfTrailFallsBackToVerbHeuristic()
	{
		// Climb-up is the last event — no following Tile to observe plane.
		// "Climb-up" is NOT in LOCAL_VERBS → should classify as transport.
		Trail climbTrail = new Trail("climb-tail", 0L, List.of(
			tile(0,   3210, 3242, 0),
			climbUp(600, 3211, 3242, 0)));
		TrailGuide climbGuide = TrailGuide.fromTrail(climbTrail);
		assertEquals(1, climbGuide.anchors().size());
		InteractionAnchor climbAnchor = climbGuide.anchors().get(0);
		assertNull("trail ended on transport → observedDestPlane null",
			climbAnchor.observedDestPlane());
		assertTrue("Climb-up falls back to verb heuristic → transport anchor",
			climbAnchor.isTransportAnchor());

		// "Open" IS in LOCAL_VERBS → should classify as local door.
		Trail gateTrail = new Trail("gate-tail", 0L, List.of(
			tile(0,   3210, 3242, 0),
			openGate(600, 3211, 3242, 0)));
		TrailGuide gateGuide = TrailGuide.fromTrail(gateTrail);
		assertEquals(1, gateGuide.anchors().size());
		InteractionAnchor gateAnchor = gateGuide.anchors().get(0);
		assertNull(gateAnchor.observedDestPlane());
		assertFalse("Open falls back to verb heuristic → local door",
			gateAnchor.isTransportAnchor());
	}

	@Test
	public void multiTransportTrailObservesEachDestPlaneIndependently()
	{
		// Transport A → tile(p=1), tile(p=1) → Transport B → tile(p=2).
		// anchors[0].observedDestPlane == 1, anchors[1].observedDestPlane == 2.
		Trail trail = new Trail("multi", 0L, List.of(
			tile(0,    3210, 3242, 0),
			climbUp(600, 3211, 3242, 0),     // Transport A, src p=0
			tile(1200, 3211, 3243, 1),
			tile(1800, 3211, 3244, 1),
			climbUp(2400, 3212, 3244, 1),    // Transport B, src p=1
			tile(3000, 3212, 3245, 2)));
		TrailGuide guide = TrailGuide.fromTrail(trail);
		assertEquals(2, guide.anchors().size());
		assertEquals(Integer.valueOf(1), guide.anchors().get(0).observedDestPlane());
		assertEquals(Integer.valueOf(2), guide.anchors().get(1).observedDestPlane());
		assertTrue(guide.anchors().get(0).isTransportAnchor());
		assertTrue(guide.anchors().get(1).isTransportAnchor());
	}

	@Test
	public void approachTileSeededFromPreviousTileEvent()
	{
		// Sanity: the anchor's approachTile is the player's last standing
		// tile before the click — i.e. the previous Tile event tile.
		Trail trail = new Trail("approach", 0L, List.of(
			tile(0,    3210, 3242, 0),
			tile(600,  3211, 3242, 0),     // last standing tile before click
			climbUp(1200, 3212, 3242, 0),  // transport at a DIFFERENT tile
			tile(1800, 3212, 3243, 1)));
		TrailGuide guide = TrailGuide.fromTrail(trail);
		InteractionAnchor a = guide.anchors().get(0);
		assertEquals(new WorldPoint(3211, 3242, 0), a.approachTile());
		assertEquals(new WorldPoint(3212, 3242, 0), a.objectTile());
	}

	private static TrailEvent.Tile tile(long ms, int x, int y, int plane)
	{
		return new TrailEvent.Tile(ms, new WorldPoint(x, y, plane));
	}

	private static TrailEvent.Transport climbUp(long ms, int x, int y, int plane)
	{
		return new TrailEvent.Transport(ms, new WorldPoint(x, y, plane),
			"Climb-up", "Staircase", 56232, "GameObject", 3, 45, 61,
			List.of("Climb-up Staircase"));
	}

	private static TrailEvent.Transport openGate(long ms, int x, int y, int plane)
	{
		return new TrailEvent.Transport(ms, new WorldPoint(x, y, plane),
			"Open", "Gate", 1560, "GameObject", 3, 36, 79,
			List.of("Open Gate"));
	}
}
