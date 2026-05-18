package net.runelite.client.plugins.recorder.nav.v21;

import net.runelite.api.TileObject;
import net.runelite.api.coords.WorldPoint;

/** A scene object the reactive solver can click to unblock a path.
 *
 *  <p>{@link #verb} is the action label that appears in the engine's
 *  right-click menu ("Open", "Climb-up", "Pass", etc.).
 *  {@link #interactionTile} is where the player should be standing
 *  when the click is dispatched — usually one of the BlockedEdge's
 *  tiles. The combination of (objectId, verb, interactionTile) is
 *  used as the blacklist key — re-clicking the same door from the
 *  same side after a recent failure is the loop we're preventing. */
public record BlockerCandidate(TileObject object, String verb, WorldPoint interactionTile)
{
	public int objectId() { return object.getId(); }

	/** Best-effort: the tile the object renders on. Falls back to the
	 *  interaction tile if the engine returns null for an exotic
	 *  TileObject subtype. The dispatcher's CLICK_GAME_OBJECT path
	 *  uses tile + verb + objectId together — the tile is a hint, the
	 *  verb is the real selector. */
	public WorldPoint objectTile()
	{
		WorldPoint w = object.getWorldLocation();
		return w == null ? interactionTile : w;
	}

	/** Stable string key for {@link ReactiveSolver}'s recent-failure
	 *  blacklist. Includes the approach tile because the same door
	 *  clicked from a different side is a different attempt. */
	public String blacklistKey()
	{
		return objectId() + ":" + verb + "@"
			+ interactionTile.getX() + "," + interactionTile.getY()
			+ "," + interactionTile.getPlane();
	}
}
