package net.runelite.client.plugins.recorder.nav.v21;

import java.util.ArrayList;
import java.util.List;
import net.runelite.api.coords.WorldPoint;

/** What the bot is trying to reach. v2.1 talks in goals, not tiles —
 *  most "go to (3109,3365,2)" requests don't actually care about the
 *  exact tile, they care about being NEAR. v2 burned a lot of effort
 *  proving exact tiles that didn't matter.
 *
 *  <p>Three contracts:
 *  <ul>
 *    <li>{@link #candidateTiles()} — tiles BFS may terminate on. Empty
 *        means "no known terminator on this plane" — usually means the
 *        goal is on a different plane.</li>
 *    <li>{@link #isSatisfied(WorldPoint)} — true iff navigation should
 *        stop. Lenient: an Area is satisfied anywhere inside; a
 *        NearNpc is satisfied when within {@code interactRadius}.</li>
 *    <li>{@link #centroid()} — single anchor tile for direction-vector
 *        math (used by the planner's BlockedEdge inference and by the
 *        BlockerScanner). Always non-null.</li>
 *  </ul>
 *
 *  <p>Sealed so the navigator pattern-matches all variants. Adding a
 *  fifth type is a deliberate breakage point. */
public sealed interface Goal permits Goal.Tile, Goal.Area, Goal.NearNpc, Goal.NearObject
{
	List<WorldPoint> candidateTiles();
	boolean isSatisfied(WorldPoint here);
	WorldPoint centroid();

	/** Stand on exactly this tile. Mostly for scripts that have a hard
	 *  requirement (e.g. fairy ring centre). Prefer Area otherwise. */
	record Tile(WorldPoint at) implements Goal
	{
		@Override public List<WorldPoint> candidateTiles() { return List.of(at); }
		@Override public boolean isSatisfied(WorldPoint here) { return at.equals(here); }
		@Override public WorldPoint centroid() { return at; }
	}

	/** Any tile within Chebyshev {@code radius} of {@code center} counts
	 *  as arrived. {@code radius = 0} ⇒ same as {@link Tile}; {@code
	 *  radius = 1} is the default for {@link NavRequest#to} adaptation. */
	record Area(WorldPoint center, int radius) implements Goal
	{
		@Override public List<WorldPoint> candidateTiles()
		{
			int n = radius * 2 + 1;
			List<WorldPoint> out = new ArrayList<>(n * n);
			for (int dx = -radius; dx <= radius; dx++)
			{
				for (int dy = -radius; dy <= radius; dy++)
				{
					out.add(new WorldPoint(center.getX() + dx, center.getY() + dy, center.getPlane()));
				}
			}
			return out;
		}
		@Override public boolean isSatisfied(WorldPoint here)
		{
			if (here.getPlane() != center.getPlane()) return false;
			int dx = Math.abs(here.getX() - center.getX());
			int dy = Math.abs(here.getY() - center.getY());
			return Math.max(dx, dy) <= radius;
		}
		@Override public WorldPoint centroid() { return center; }
	}

	/** "Get adjacent to an NPC named X." Script provides last-known
	 *  anchor; reactive layer handles the case where the NPC has
	 *  wandered (re-request with the new anchor). */
	record NearNpc(String name, WorldPoint anchor, int interactRadius) implements Goal
	{
		@Override public List<WorldPoint> candidateTiles() { return ringAround(anchor, interactRadius); }
		@Override public boolean isSatisfied(WorldPoint here) { return withinRadius(here, anchor, interactRadius); }
		@Override public WorldPoint centroid() { return anchor; }
	}

	/** "Get within range of an interactable object named X." */
	record NearObject(String name, WorldPoint anchor, int interactRadius) implements Goal
	{
		@Override public List<WorldPoint> candidateTiles() { return ringAround(anchor, interactRadius); }
		@Override public boolean isSatisfied(WorldPoint here) { return withinRadius(here, anchor, interactRadius); }
		@Override public WorldPoint centroid() { return anchor; }
	}

	private static List<WorldPoint> ringAround(WorldPoint anchor, int r)
	{
		int n = r * 2 + 1;
		List<WorldPoint> out = new ArrayList<>(n * n);
		for (int dx = -r; dx <= r; dx++)
		{
			for (int dy = -r; dy <= r; dy++)
			{
				if (dx == 0 && dy == 0) continue;
				out.add(new WorldPoint(anchor.getX() + dx, anchor.getY() + dy, anchor.getPlane()));
			}
		}
		return out;
	}

	private static boolean withinRadius(WorldPoint here, WorldPoint anchor, int r)
	{
		if (here.getPlane() != anchor.getPlane()) return false;
		int dx = Math.abs(here.getX() - anchor.getX());
		int dy = Math.abs(here.getY() - anchor.getY());
		return Math.max(dx, dy) <= r;
	}
}
