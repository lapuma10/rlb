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
				Integer observedDestPlane = null;
				for (int j = i + 1; j < events.size(); j++)
				{
					if (events.get(j) instanceof TrailEvent.Tile next)
					{
						if (next.tile() != null)
						{
							observedDestPlane = next.tile().getPlane();
						}
						break;
					}
				}
				anchors.add(InteractionAnchor.from(tr, lastTile, observedDestPlane));
			}
		}
		return new TrailGuide(corridor, anchors);
	}
}
