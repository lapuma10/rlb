package net.runelite.client.plugins.recorder.nav.v21;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.trail.Trail;
import net.runelite.client.plugins.recorder.trail.TrailEvent;

/** Decomposes a recorded {@link Trail} into a corridor (the ordered
 *  {@code Tile} event tiles, used downstream as a soft proximity hint)
 *  and an ordered list of {@link InteractionAnchor}s (one per
 *  {@code Transport} event). Each anchor is enriched with the plane
 *  observed on the first {@code Tile} event after the transport, so the
 *  classifier can tell a local door from a plane-changing stair without
 *  relying on verb heuristics. */
public record TrailGuide(
	List<WorldPoint> corridor,
	List<InteractionAnchor> anchors
)
{
	public TrailGuide
	{
		corridor = corridor == null ? List.of() : List.copyOf(corridor);
		anchors  = anchors  == null ? List.of() : List.copyOf(anchors);
	}

	public static TrailGuide fromTrail(@Nullable Trail trail)
	{
		if (trail == null) return new TrailGuide(List.of(), List.of());
		List<TrailEvent> events = trail.events();
		if (events == null || events.isEmpty()) return new TrailGuide(List.of(), List.of());

		List<WorldPoint> corridor = new ArrayList<>();
		List<InteractionAnchor> anchors = new ArrayList<>();
		WorldPoint lastTile = null;

		for (int i = 0; i < events.size(); i++)
		{
			TrailEvent e = events.get(i);
			if (e instanceof TrailEvent.Tile t)
			{
				if (t.tile() != null)
				{
					corridor.add(t.tile());
					lastTile = t.tile();
				}
			}
			else if (e instanceof TrailEvent.Transport tr)
			{
				// Scan forward for the FIRST Tile event whose plane DIFFERS
				// from the transport's source plane. The recorder occasionally
				// captures a mid-walk Tile event BEFORE the plane change
				// propagates (e.g. pen→castle stair: Climb-up at (3204,3229,p=0)
				// → Tile (3205,3228,p=0) [mid-walk] → Tile (3206,3229,p=1)).
				// If we picked the FIRST Tile we'd mis-classify the stair as a
				// local door (observedDestPlane == source plane). Stops at the
				// next Transport event (no plane change observed before the
				// next click means same-plane all the way for this anchor) —
				// observedDestPlane stays null and isTransportAnchor() falls
				// back to the verb heuristic.
				int sourcePlane = tr.tile() != null ? tr.tile().getPlane() : 0;
				Integer observedDestPlane = null;
				for (int j = i + 1; j < events.size(); j++)
				{
					TrailEvent peek = events.get(j);
					if (peek instanceof TrailEvent.Transport) break;
					if (peek instanceof TrailEvent.Tile next
						&& next.tile() != null
						&& next.tile().getPlane() != sourcePlane)
					{
						observedDestPlane = next.tile().getPlane();
						break;
					}
				}
				anchors.add(InteractionAnchor.from(tr, lastTile, observedDestPlane));
			}
		}
		return new TrailGuide(corridor, anchors);
	}
}
