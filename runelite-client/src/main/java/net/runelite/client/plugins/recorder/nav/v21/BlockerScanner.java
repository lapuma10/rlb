package net.runelite.client.plugins.recorder.nav.v21;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import net.runelite.api.Client;
import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.GroundObject;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WallObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.nav.v2.bfs.CollisionView;
import net.runelite.client.plugins.recorder.nav.v2.bfs.SkretzoBfsKernel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Given a {@link PlanResult.BlockedEdge}, scan the live scene for an
 *  interactable object straddling that edge — door, gate, stair,
 *  ladder, trapdoor, agility shortcut.
 *
 *  <p><b>Edge-based, not proximity-based.</b> The scanner looks at the
 *  two tiles of the edge ({@code from} and {@code to}) and reads every
 *  TileObject on each. A naive "objects near the player" scanner
 *  produces false positives — clicking the wrong door, walking into
 *  NPCs. Anchoring to the blocked edge is what makes this safe.
 *
 *  <p>{@link #UNBLOCKING_VERBS} is intentionally short: only verbs
 *  that plausibly change collision (or change plane) are considered.
 *  "Use" is excluded — it's ambiguous and routinely belongs to an
 *  inventory-based interaction the navigator can't drive.
 *
 *  <p>Threading: must run on the client thread (reads {@code Scene}
 *  and {@code ObjectComposition}). The navigator marshals via
 *  {@link V21Env#onClient}. */
public final class BlockerScanner
{
	private static final Logger log = LoggerFactory.getLogger(BlockerScanner.class);

	/** Verbs that, when invoked on a game object, plausibly unblock the
	 *  bot's path. Ordered by frequency in OSRS. Case-insensitive match
	 *  against {@link ObjectComposition#getActions()}. */
	static final String[] UNBLOCKING_VERBS = {
		"Open", "Climb-up", "Climb-down", "Climb", "Climb-over",
		"Cross", "Pass", "Enter", "Exit", "Go-through", "Squeeze-through",
		"Push", "Pay-toll(10gp)"
	};

	/** Verbs preferred when descending a plane (player → goal-plane-1). */
	private static final String[] CLIMB_DOWN_VERBS = { "Climb-down", "Climb" };
	/** Verbs preferred when ascending a plane (player → goal-plane+1). */
	private static final String[] CLIMB_UP_VERBS = { "Climb-up", "Climb" };
	/** Plane-changing fallback verbs — trapdoors, cave entrances, holes. */
	private static final String[] CLIMB_FALLBACK_VERBS = {
		"Open", "Enter", "Exit", "Go-through", "Pass", "Climb-over"
	};

	/** Object name substrings that strongly imply the object teleports
	 *  the player to another plane (or another world coordinate
	 *  entirely). When same-plane routing hits a blocked edge, these
	 *  must be REJECTED: opening a trapdoor on the way to the chicken
	 *  pen sends the bot into a basement. Symmetrically, when
	 *  plane-mismatch falls back from strict {@code Climb-*} verbs to
	 *  generic {@code Open}/{@code Enter}, the object must MATCH one
	 *  of these names — so a random "Open Door" isn't confused for a
	 *  cave entrance.
	 *
	 *  <p>Case-insensitive substring match against
	 *  {@link ObjectComposition#getName()} (after impostor unwrap). */
	private static final String[] PLANE_CHANGER_NAME_HINTS = {
		"trapdoor", "manhole", "ladder",
		"staircase", "stairwell", "stairway", "stairs", "stair",
		"hatch", "hole", "tunnel", "cave entrance", "basement",
		"cellar"
	};
	/** Scene-wide scan radius for {@link #findClimbInScene}. Covers
	 *  most bank/store interiors and ground-level staircases without
	 *  running the full 104×104 scene. */
	public static final int SCENE_SCAN_RADIUS = 32;

	private final Client client;

	public BlockerScanner(Client client) { this.client = client; }

	/** Strict edge scan: look only on the {@code from} and {@code to}
	 *  tiles of the blocked edge. Best precision; preferred over the
	 *  wider neighborhood scan. Returns null when no unblocking object
	 *  is on the edge. */
	@Nullable
	public BlockerCandidate findOnEdge(WorldPoint from, WorldPoint to)
	{
		List<TileObject> bucket = new ArrayList<>();
		collectObjects(from, bucket);
		if (!from.equals(to)) collectObjects(to, bucket);
		return pickFirstUnblocking(bucket, from);
	}

	/** Wider scan: edge tiles + their 1-Chebyshev neighbors. Use when
	 *  {@link #findOnEdge} returns null — sometimes the door's WallObject
	 *  sits one tile off from where BFS reports the block, e.g. when
	 *  the object's render tile differs from its collision footprint
	 *  (recording quirk, impostor-unwrap off-by-one). */
	@Nullable
	public BlockerCandidate findNearEdge(WorldPoint from, WorldPoint to)
	{
		List<TileObject> bucket = new ArrayList<>();
		int plane = from.getPlane();
		for (int dx = -1; dx <= 1; dx++)
		{
			for (int dy = -1; dy <= 1; dy++)
			{
				collectObjects(new WorldPoint(from.getX() + dx, from.getY() + dy, plane), bucket);
				if (!from.equals(to))
				{
					collectObjects(new WorldPoint(to.getX() + dx, to.getY() + dy, plane), bucket);
				}
			}
		}
		return pickFirstUnblocking(bucket, from);
	}

	/** Scene-wide scan for a plane-changing object. Two-pass priority:
	 *  first looks for direction-matching stair verbs ({@code Climb-down}
	 *  or {@code Climb-up} depending on {@code planeDir}), then falls
	 *  back to other plane-changers ({@code Open} trapdoor, {@code Enter}
	 *  cave). Returns the nearest match — important when the bank floor
	 *  contains both a staircase and a bank-grille "Open" that doesn't
	 *  change plane.
	 *
	 *  <p>{@code planeDir}: {@code -1} = descending, {@code +1} = ascending,
	 *  {@code 0} = either (rare — used as a defensive fallback).
	 *
	 *  <p>The {@link BlockerCandidate#interactionTile} is the object's
	 *  own world tile — callers replace this with the player's current
	 *  position before dispatching, so the blacklist key reflects the
	 *  actual approach. */
	@Nullable
	public BlockerCandidate findClimbInScene(WorldPoint center, int radius, int planeDir)
	{
		return findClimbInScene(center, radius, planeDir, null);
	}

	/** Reachability-filtered variant. When {@code collision} is non-null,
	 *  the scan first floods walkable tiles from {@code center} (bounded
	 *  by {@code radius}) and rejects any candidate whose own tile is
	 *  outside that flood. This is the fix for "scanner sees the
	 *  trapdoor inside the hen-house" — the trapdoor is geometrically in
	 *  range but separated from the bot by walls. */
	@Nullable
	public BlockerCandidate findClimbInScene(WorldPoint center, int radius, int planeDir,
		@Nullable CollisionView collision)
	{
		String[] stairs = planeDir < 0 ? CLIMB_DOWN_VERBS
			: planeDir > 0 ? CLIMB_UP_VERBS
			: new String[]{ "Climb-down", "Climb-up", "Climb" };

		Set<Long> reachable = collision == null ? null : floodReachable(center, radius, collision);

		List<TileObject> bucket = new ArrayList<>();
		int plane = center.getPlane();
		for (int dx = -radius; dx <= radius; dx++)
		{
			for (int dy = -radius; dy <= radius; dy++)
			{
				int x = center.getX() + dx;
				int y = center.getY() + dy;
				if (reachable != null && !reachable.contains(packXY(x, y))) continue;
				collectObjects(new WorldPoint(x, y, plane), bucket);
			}
		}

		// Pass 1: strict stair verbs. Any object with Climb-down /
		// Climb-up / Climb IS a plane changer by construction, so no
		// name filter needed.
		BlockerCandidate best = pickNearestWithVerb(bucket, center, stairs, false);
		if (best != null) return best;
		// Pass 2: generic plane-changing verbs (Open / Enter / etc.)
		// — only when descending. Trapdoors, manholes, holes ALL go
		// down. Going up uses Climb-up on stairs/ladders (Pass 1).
		// Picking a "Open Trapdoor" while trying to go from the chicken
		// pen UP to the bank's P2 is what cost us a full HARD_STALL on
		// the return trip — the bot would dutifully click the trapdoor,
		// descend to a basement, then PlaneMismatch the other way.
		// {@code requirePlaneChangerName=true} additionally rejects
		// random "Open Door" objects that happen to be nearby.
		if (planeDir < 0)
		{
			return pickNearestWithVerb(bucket, center, CLIMB_FALLBACK_VERBS, true);
		}
		return null;
	}

	@Nullable
	private BlockerCandidate pickNearestWithVerb(List<TileObject> bucket,
		WorldPoint center, String[] preferredVerbs, boolean requirePlaneChangerName)
	{
		BlockerCandidate best = null;
		int bestVerbIdx = Integer.MAX_VALUE;
		int bestDist = Integer.MAX_VALUE;
		for (TileObject obj : bucket)
		{
			ObjectComposition def = unwrap(client.getObjectDefinition(obj.getId()));
			if (def == null) continue;
			String[] actions = def.getActions();
			if (actions == null) continue;
			if (requirePlaneChangerName && !isLikelyPlaneChanger(def.getName())) continue;
			WorldPoint objTile = obj.getWorldLocation();
			if (objTile == null) continue;
			int d = Math.max(Math.abs(objTile.getX() - center.getX()),
				Math.abs(objTile.getY() - center.getY()));
			// Find this object's best-priority matching verb (first hit wins).
			String matchedVerb = null;
			int matchedVerbIdx = Integer.MAX_VALUE;
			for (int i = 0; i < preferredVerbs.length; i++)
			{
				for (String action : actions)
				{
					if (action != null && action.equalsIgnoreCase(preferredVerbs[i]))
					{
						matchedVerb = preferredVerbs[i];
						matchedVerbIdx = i;
						break;
					}
				}
				if (matchedVerb != null) break;
			}
			if (matchedVerb == null) continue;
			// Prefer nearest; tie-break by verb priority.
			if (d < bestDist || (d == bestDist && matchedVerbIdx < bestVerbIdx))
			{
				bestDist = d;
				bestVerbIdx = matchedVerbIdx;
				best = new BlockerCandidate(obj, matchedVerb, objTile);
			}
		}
		if (best != null)
		{
			log.debug("v21.scan: nearest climb objectId={} verb={} dist={} at={}",
				best.objectId(), best.verb(), bestDist, best.objectTile());
		}
		return best;
	}

	/** Perimeter-exit solver. The component-level navigation primitive
	 *  for "bot is inside a walled enclosure with the BFS-reported
	 *  BlockedEdge being just a fence."
	 *
	 *  <p>Algorithm:
	 *  <ol>
	 *    <li>Flood-fill walkable tiles from {@code center} (bounded).</li>
	 *    <li>For each reachable tile, check if it's on the PERIMETER —
	 *        i.e., at least one cardinal neighbor is outside the
	 *        reachable set AND blocked by collision (not just out of
	 *        flood-fill range).</li>
	 *    <li>Read the tile's WallObject (where doors/gates live).
	 *        Require an unblocking verb and a non-plane-changer name.</li>
	 *    <li>Score by Chebyshev to the goal centroid — exits closer to
	 *        the goal rank higher.</li>
	 *  </ol>
	 *
	 *  <p>Generic by design: any reachable component with a perimeter
	 *  exit (chicken pen, cow pen, bank interior, courtyard, room) is
	 *  handled by the same code. Not "find any door" — find the door
	 *  on the perimeter pointed at the goal. */
	public List<BlockerCandidate> findPerimeterExits(WorldPoint center,
		WorldPoint goalCentroid, int radius, CollisionView collision)
	{
		return findPerimeterExits(center, goalCentroid, radius, collision, null);
	}

	/** Corridor-aware overload. When {@code corridor} is non-null and
	 *  two exits' Chebyshev-to-goal differ by ≤1 (a near-tie), the one
	 *  whose {@code objectTile} is closer (Chebyshev) to the nearest
	 *  tile in {@code corridor} wins. With {@code corridor == null} (or
	 *  empty), behavior matches the 4-arg overload verbatim. */
	public List<BlockerCandidate> findPerimeterExits(WorldPoint center,
		WorldPoint goalCentroid, int radius, CollisionView collision,
		@Nullable List<WorldPoint> corridor)
	{
		Set<Long> reachable = floodReachable(center, radius, collision);
		List<ScoredExit> scored = new ArrayList<>();
		int plane = center.getPlane();
		for (Long packed : reachable)
		{
			int x = (int) (packed >> 32);
			int y = (int) (packed & 0xFFFFFFFFL);
			if (!isOnPerimeter(x, y, plane, reachable, collision)) continue;
			WorldPoint at = new WorldPoint(x, y, plane);
			WallObject wall = wallObjectAt(at);
			if (wall == null) continue;
			ObjectComposition def = unwrap(client.getObjectDefinition(wall.getId()));
			if (def == null) continue;
			if (isLikelyPlaneChanger(def.getName())) continue;
			String[] actions = def.getActions();
			if (actions == null) continue;
			String matched = matchSamePlaneVerb(actions);
			if (matched == null) continue;
			int score = Math.max(
				Math.abs(x - goalCentroid.getX()),
				Math.abs(y - goalCentroid.getY()));
			scored.add(new ScoredExit(new BlockerCandidate(wall, matched, at), score));
		}
		// Primary key: Chebyshev distance to goal centroid.
		// Secondary key (only when corridor is non-null/non-empty): when two
		// candidates' primary keys differ by ≤1, prefer the one whose
		// objectTile is closer to the corridor. With a null/empty corridor,
		// the comparator reduces to primary-only, matching the old behavior.
		final List<WorldPoint> corridorRef = corridor;
		final boolean useCorridor = corridorRef != null && !corridorRef.isEmpty();
		Comparator<ScoredExit> cmp = (a, b) ->
		{
			int da = a.score;
			int db = b.score;
			if (Math.abs(da - db) > 1) return Integer.compare(da, db);
			if (!useCorridor) return Integer.compare(da, db);
			int ca = nearestCorridorChebyshev(a.candidate.objectTile(), corridorRef);
			int cb = nearestCorridorChebyshev(b.candidate.objectTile(), corridorRef);
			return Integer.compare(ca, cb);
		};
		scored.sort(cmp);
		List<BlockerCandidate> out = new ArrayList<>(scored.size());
		for (ScoredExit s : scored) out.add(s.candidate);
		return out;
	}

	/** Minimum Chebyshev distance from {@code p} to any tile in
	 *  {@code corridor}. Returns {@link Integer#MAX_VALUE} when the
	 *  corridor is empty (callers gate empty/null upstream, so this is
	 *  a defensive guard). */
	private static int nearestCorridorChebyshev(WorldPoint p, List<WorldPoint> corridor)
	{
		int best = Integer.MAX_VALUE;
		for (WorldPoint c : corridor)
		{
			int d = chebyshev(p, c);
			if (d < best) best = d;
		}
		return best;
	}

	private static int chebyshev(WorldPoint a, WorldPoint b)
	{
		return Math.max(Math.abs(a.getX() - b.getX()),
			Math.abs(a.getY() - b.getY()));
	}

	/** True iff at least one cardinal neighbor is outside the reachable
	 *  set AND collision-blocked (vs. just out of flood-fill range).
	 *  The latter check matters: a tile at the flood's outer edge has
	 *  unreachable neighbors purely from budget exhaustion, not from
	 *  actual walls, and is NOT a perimeter exit. */
	private static boolean isOnPerimeter(int x, int y, int plane,
		Set<Long> reachable, CollisionView collision)
	{
		int[][] cardinals = { {-1, 0}, {1, 0}, {0, -1}, {0, 1} };
		for (int[] d : cardinals)
		{
			int nx = x + d[0];
			int ny = y + d[1];
			if (reachable.contains(packXY(nx, ny))) continue;
			if (!SkretzoBfsKernel.canMove(collision, x, y, plane, d[0], d[1]))
			{
				return true;
			}
		}
		return false;
	}

	@Nullable
	private static String matchSamePlaneVerb(String[] actions)
	{
		for (String verb : UNBLOCKING_VERBS)
		{
			for (String action : actions)
			{
				if (action != null && action.equalsIgnoreCase(verb)) return verb;
			}
		}
		return null;
	}

	private record ScoredExit(BlockerCandidate candidate, int score) {}

	@Nullable
	private WallObject wallObjectAt(WorldPoint at)
	{
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null) return null;
		Scene scene = wv.getScene();
		if (scene == null) return null;
		int sx = at.getX() - wv.getBaseX();
		int sy = at.getY() - wv.getBaseY();
		Tile[][][] tiles = scene.getTiles();
		if (tiles == null) return null;
		int plane = at.getPlane();
		if (plane < 0 || plane >= tiles.length) return null;
		Tile[][] planeTiles = tiles[plane];
		if (sx < 0 || sy < 0 || sx >= planeTiles.length || sy >= planeTiles[0].length) return null;
		Tile t = planeTiles[sx][sy];
		if (t == null) return null;
		return t.getWallObject();
	}

	private void collectObjects(WorldPoint at, List<TileObject> out)
	{
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null) return;
		Scene scene = wv.getScene();
		if (scene == null) return;
		int sx = at.getX() - wv.getBaseX();
		int sy = at.getY() - wv.getBaseY();
		Tile[][][] tiles = scene.getTiles();
		if (tiles == null) return;
		int plane = at.getPlane();
		if (plane < 0 || plane >= tiles.length) return;
		Tile[][] planeTiles = tiles[plane];
		if (sx < 0 || sy < 0 || sx >= planeTiles.length || sy >= planeTiles[0].length) return;
		Tile t = planeTiles[sx][sy];
		if (t == null) return;
		WallObject wall = t.getWallObject();
		if (wall != null) out.add(wall);
		DecorativeObject deco = t.getDecorativeObject();
		if (deco != null) out.add(deco);
		GroundObject ground = t.getGroundObject();
		if (ground != null) out.add(ground);
		GameObject[] gos = t.getGameObjects();
		if (gos != null)
		{
			for (GameObject go : gos)
			{
				if (go != null) out.add(go);
			}
		}
	}

	@Nullable
	private BlockerCandidate pickFirstUnblocking(List<TileObject> candidates, WorldPoint approachFrom)
	{
		BlockerCandidate best = null;
		int bestVerbIdx = Integer.MAX_VALUE;
		for (TileObject obj : candidates)
		{
			ObjectComposition def = unwrap(client.getObjectDefinition(obj.getId()));
			if (def == null) continue;
			String[] actions = def.getActions();
			if (actions == null) continue;
			// Same-plane scan: refuse trapdoors / ladders / stairs even
			// when they expose an "Open" / "Climb-*" verb. Opening a
			// trapdoor between the bot and the chicken pen would send
			// it to a basement. Plane changes go through
			// {@link #findClimbInScene}, not this path.
			if (isLikelyPlaneChanger(def.getName())) continue;
			for (int i = 0; i < UNBLOCKING_VERBS.length; i++)
			{
				if (i >= bestVerbIdx) break;
				String want = UNBLOCKING_VERBS[i];
				for (String action : actions)
				{
					if (action != null && action.equalsIgnoreCase(want))
					{
						bestVerbIdx = i;
						best = new BlockerCandidate(obj, want, approachFrom);
						break;
					}
				}
			}
		}
		if (best != null)
		{
			log.debug("v21.scan: best blocker objectId={} verb={} approach={}",
				best.objectId(), best.verb(), approachFrom);
		}
		return best;
	}

	/** Case-insensitive substring check against {@link #PLANE_CHANGER_NAME_HINTS}.
	 *  Used to gate "Open" / "Climb" verb matches by object name so a
	 *  trapdoor and a regular door aren't treated identically. */
	private static boolean isLikelyPlaneChanger(String objectName)
	{
		if (objectName == null) return false;
		String lower = objectName.toLowerCase();
		for (String hint : PLANE_CHANGER_NAME_HINTS)
		{
			if (lower.contains(hint)) return true;
		}
		return false;
	}

	/** Bounded flood-fill from {@code start} returning the set of
	 *  walkable tiles reachable within {@code maxRadius} Chebyshev.
	 *  Used by {@link #findClimbInScene} to reject candidates that are
	 *  geometrically in scan range but separated from the bot by walls
	 *  (trapdoor inside a sealed hen-house, ladder behind a locked
	 *  fence, etc).
	 *
	 *  <p>Budget-capped at ~4 k tiles — covers a 32-radius region with
	 *  headroom but won't blow up on unbounded scenes. Same step rule
	 *  as the main BFS so reachability and pathing agree. */
	private static Set<Long> floodReachable(WorldPoint start, int maxRadius, CollisionView col)
	{
		Set<Long> visited = new HashSet<>();
		int sx = start.getX();
		int sy = start.getY();
		int plane = start.getPlane();
		Deque<long[]> q = new ArrayDeque<>();
		visited.add(packXY(sx, sy));
		q.add(new long[]{ sx, sy });
		int budget = 4096;
		final int[][] STEPS = {
			{-1,  0}, { 1,  0}, { 0, -1}, { 0,  1},
			{-1, -1}, { 1, -1}, {-1,  1}, { 1,  1}
		};
		while (!q.isEmpty() && budget-- > 0)
		{
			long[] h = q.poll();
			int x = (int) h[0];
			int y = (int) h[1];
			for (int[] s : STEPS)
			{
				int nx = x + s[0];
				int ny = y + s[1];
				if (Math.max(Math.abs(nx - sx), Math.abs(ny - sy)) > maxRadius) continue;
				long nk = packXY(nx, ny);
				if (visited.contains(nk)) continue;
				if (!SkretzoBfsKernel.canMove(col, x, y, plane, s[0], s[1])) continue;
				visited.add(nk);
				q.add(new long[]{ nx, ny });
			}
		}
		return visited;
	}

	private static long packXY(int x, int y)
	{
		return ((long) x << 32) | (y & 0xFFFFFFFFL);
	}

	private static ObjectComposition unwrap(ObjectComposition def)
	{
		if (def == null) return null;
		if (def.getImpostorIds() != null)
		{
			try
			{
				ObjectComposition imp = def.getImpostor();
				if (imp != null) return imp;
			}
			catch (Throwable ignored) { /* fall through to base */ }
		}
		return def;
	}
}
