package net.runelite.client.sequence.artemis.view;

import net.runelite.api.coords.WorldPoint;

/**
 * Immutable view of an NPC at the moment {@code findNpc} returned.
 * Spec §8. Carries {@link #observedTick} so action Steps can detect
 * stale refs (engine reuses NPC indices after despawn) and fail-and-
 * requery via the re-resolution contract.
 */
public record NpcRef(
	int index,
	int id,
	String name,
	WorldPoint originalLoc,
	int healthRatio,
	long observedTick
)
{
	/** Sentinel: health ratio unknown / not yet observed. */
	public static final int HEALTH_RATIO_UNKNOWN = -1;
}
