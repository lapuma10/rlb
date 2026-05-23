package net.runelite.client.sequence.artemis.view;

import net.runelite.api.coords.WorldPoint;

/**
 * Immutable view of the local player at one tick. Spec §8.
 * Re-read each tick via {@code Artemis.player()} — never cached across
 * ticks by callers (the location and animation move).
 */
public record PlayerState(
	WorldPoint loc,
	int plane,
	int animation,
	int hp,
	int prayer,
	int energy,
	boolean idle
)
{
}
