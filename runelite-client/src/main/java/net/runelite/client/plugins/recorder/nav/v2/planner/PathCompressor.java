package net.runelite.client.plugins.recorder.nav.v2.planner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.nav.v2.transport.PathStep;
import net.runelite.client.plugins.recorder.nav.v2.transport.TransportLeg;
import net.runelite.client.plugins.recorder.nav.v2.transport.TransportStep;
import net.runelite.client.plugins.recorder.nav.v2.transport.WalkStep;
import net.runelite.client.plugins.recorder.nav.v2.transport.Waypoint;
import net.runelite.client.plugins.recorder.nav.v2.transport.WaypointType;

/** Collapses tile-level walk sequences into sparse waypoints
 *  preserving exact anchors. Spec §4 Lane 4 compression invariants:
 *  <ul>
 *    <li>Long straight runs collapse into a single {@link WalkStep}
 *        with loose tolerance.</li>
 *    <li>Preserve exact tiles for {@code TRANSPORT_APPROACH},
 *        plane-change anchors, {@code REGION_BRIDGE}, predicate-edge
 *        anchors.</li>
 *    <li>Direction changes (turn corners) become anchor waypoints
 *        with mid tolerance.</li>
 *  </ul>
 *
 *  <p><b>Compression algorithm</b> (per-leg):
 *  <ul>
 *    <li>For the i-th tile in the leg, compute the direction vector
 *        {@code (dx, dy)} to the (i-1)-th tile. If the direction
 *        changes from the previous step, mark (i-1) as a "corner"
 *        anchor with tolerance 1.</li>
 *    <li>The first tile in the leg is always preserved (it's where the
 *        previous transport or the start placed us; for plane-change
 *        anchors it must be exact).</li>
 *    <li>The last tile in the leg is always preserved (the
 *        {@code TRANSPORT_APPROACH} for the next transport, or the
 *        final target). The caller (planner) sets its
 *        {@link WaypointType} appropriately.</li>
 *    <li>Between anchors, tiles are dropped — the executor's bucket
 *        sidestep picks any walkable tile within tolerance.</li>
 *  </ul>
 *
 *  <p>The compressor never crosses transport boundaries — it operates
 *  on one walk leg at a time. Transport legs are passed through to
 *  the output unchanged.
 *
 *  <p>Default loose-tolerance is 2 tiles. Corner anchors get tolerance
 *  1 (the executor needs to be close enough to see the next leg
 *  direction). Exact anchors get tolerance 0. */
public final class PathCompressor
{
	private PathCompressor() {}

	/** Default tolerance for the body of a straight run. */
	public static final int LOOSE_TOLERANCE = 2;

	/** Tolerance assigned to mid-leg corner anchors. */
	public static final int CORNER_TOLERANCE = 1;

	/** Compress one walk leg from {@code tiles}. {@code firstAnchorType}
	 *  and {@code lastAnchorType} drive the type assigned to the first
	 *  and last waypoint respectively — they're {@code WALK} for the
	 *  start of a route, {@code TRANSPORT_APPROACH} when the next step
	 *  is a transport, etc.
	 *
	 *  <p>If {@code lastAnchorType.exactRequired()} is true (the type
	 *  enforces tolerance 0), the last waypoint's tolerance is 0
	 *  regardless of the corner heuristic.
	 *
	 *  <p>Empty / single-tile legs return one waypoint (the tile). */
	public static List<WalkStep> compressLeg(List<WorldPoint> tiles,
											  WaypointType firstAnchorType,
											  WaypointType lastAnchorType)
	{
		if (tiles == null || tiles.isEmpty()) return Collections.emptyList();
		if (tiles.size() == 1)
		{
			int tol = (lastAnchorType == WaypointType.TRANSPORT_APPROACH
				|| lastAnchorType == WaypointType.OBJECT_INTERACTION
				|| lastAnchorType == WaypointType.SAFETY_ANCHOR)
				? 0 : LOOSE_TOLERANCE;
			return List.of(new WalkStep(new Waypoint(tiles.get(0), tol, lastAnchorType)));
		}

		List<Integer> anchorIdx = new ArrayList<>();
		anchorIdx.add(0);
		// Detect direction changes.
		int prevDx = signum(tiles.get(1).getX() - tiles.get(0).getX());
		int prevDy = signum(tiles.get(1).getY() - tiles.get(0).getY());
		for (int i = 2; i < tiles.size(); i++)
		{
			int dx = signum(tiles.get(i).getX() - tiles.get(i - 1).getX());
			int dy = signum(tiles.get(i).getY() - tiles.get(i - 1).getY());
			if (dx != prevDx || dy != prevDy)
			{
				anchorIdx.add(i - 1);
				prevDx = dx;
				prevDy = dy;
			}
			// Plane change inside one BFS leg shouldn't happen (BFS is
			// plane-bound), but as a safety: if it did, force an anchor.
			if (tiles.get(i).getPlane() != tiles.get(i - 1).getPlane())
			{
				anchorIdx.add(i - 1);
				anchorIdx.add(i);
			}
		}
		anchorIdx.add(tiles.size() - 1);

		// Emit one WalkStep per anchor index.
		List<WalkStep> out = new ArrayList<>(anchorIdx.size());
		for (int k = 0; k < anchorIdx.size(); k++)
		{
			int idx = anchorIdx.get(k);
			WorldPoint p = tiles.get(idx);
			boolean isFirst = (k == 0);
			boolean isLast = (k == anchorIdx.size() - 1);
			WaypointType type;
			int tolerance;
			if (isFirst)
			{
				type = firstAnchorType;
				tolerance = toleranceFor(firstAnchorType, LOOSE_TOLERANCE);
			}
			else if (isLast)
			{
				type = lastAnchorType;
				tolerance = toleranceFor(lastAnchorType, LOOSE_TOLERANCE);
			}
			else
			{
				type = WaypointType.WALK;
				tolerance = CORNER_TOLERANCE;
			}
			out.add(new WalkStep(new Waypoint(p, tolerance, type)));
		}
		// Dedupe: adjacent waypoints at the same tile collapse.
		List<WalkStep> deduped = new ArrayList<>(out.size());
		WorldPoint last = null;
		for (WalkStep w : out)
		{
			if (last != null && w.waypoint().target().equals(last))
			{
				// Same tile — keep the stricter (lower tolerance) version,
				// or the explicit-typed version.
				WalkStep prev = deduped.remove(deduped.size() - 1);
				WalkStep stricter = w.waypoint().toleranceRadius() < prev.waypoint().toleranceRadius() ? w : prev;
				deduped.add(stricter);
			}
			else
			{
				deduped.add(w);
				last = w.waypoint().target();
			}
		}
		return deduped;
	}

	/** Compose multiple legs into one ordered list of {@link PathStep}.
	 *  Transports are interleaved by the caller in the
	 *  {@link WaypointPlanner}; this method just wires walk-legs into
	 *  the output stream. */
	public static List<PathStep> assemble(List<List<WalkStep>> walkLegs,
										   List<TransportLeg> transports)
	{
		// Walk legs and transports alternate: walk_0, transport_0,
		// walk_1, transport_1, ..., walk_N. transports.size() ==
		// walkLegs.size() - 1.
		List<PathStep> out = new ArrayList<>();
		for (int i = 0; i < walkLegs.size(); i++)
		{
			out.addAll(walkLegs.get(i));
			if (i < transports.size())
			{
				out.add(new TransportStep(transports.get(i)));
			}
		}
		return out;
	}

	private static int toleranceFor(WaypointType type, int defaultTol)
	{
		switch (type)
		{
			case TRANSPORT_APPROACH:
			case OBJECT_INTERACTION:
			case SAFETY_ANCHOR:
				return 0;
			case REGION_BRIDGE:
				return 0;
			default:
				return defaultTol;
		}
	}

	private static int signum(int v) { return Integer.compare(v, 0); }
}
