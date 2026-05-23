package net.runelite.client.sequence.artemis.view;

import net.runelite.api.coords.WorldPoint;

/**
 * Immutable view of a ground item at the moment {@code findItem}
 * returned. Spec §8.
 */
public record GroundItemRef(
	int itemId,
	String name,
	int quantity,
	WorldPoint originalLoc,
	long observedTick
)
{
}
