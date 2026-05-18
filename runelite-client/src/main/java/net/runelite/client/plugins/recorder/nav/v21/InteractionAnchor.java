package net.runelite.client.plugins.recorder.nav.v21;

import java.util.Set;
import javax.annotation.Nullable;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.trail.TrailEvent;

public record InteractionAnchor(
	int objectId,
	String verb,
	WorldPoint objectTile,
	WorldPoint approachTile,
	String targetKind,
	@Nullable Integer observedDestPlane    // null when trail data didn't capture a post-transport Tile
)
{
	private static final Set<String> LOCAL_VERBS = Set.of(
		"Open", "Pass", "Pay", "Push", "Pick-lock", "Close");

	/** Build from a {@link TrailEvent.Transport}. The transport event itself
	 *  doesn't carry an approach tile — callers must derive it from the
	 *  previous {@code Tile} event (the player's standing tile right before
	 *  the click) and pass it in. When the approach tile is null, falls back
	 *  to the object tile. The {@code observedDestPlane} comes from the
	 *  first chronological {@code Tile} event after this transport. */
	public static InteractionAnchor from(TrailEvent.Transport e,
	                                     @Nullable WorldPoint approachTile,
	                                     @Nullable Integer observedDestPlane)
	{
		return new InteractionAnchor(
			e.targetId(),
			e.option() != null ? e.option() : "",
			e.tile(),
			approachTile != null ? approachTile : e.tile(),
			e.targetKind() != null ? e.targetKind() : "",
			observedDestPlane);
	}

	/** True when this anchor is expected to change plane (a stair/ladder/portal).
	 *  False when it's a same-plane local interaction (door/gate). */
	public boolean isTransportAnchor()
	{
		if (observedDestPlane != null)
		{
			return !observedDestPlane.equals(objectTile.getPlane());
		}
		// Verb fallback when the trail didn't capture a post-transport Tile event.
		return !LOCAL_VERBS.contains(verb);
	}

	public boolean matches(int objectId, WorldPoint at)
	{
		return this.objectId == objectId && this.objectTile.equals(at);
	}
}
