package net.runelite.client.sequence.artemis.view;

import net.runelite.api.coords.WorldPoint;

/**
 * Immutable view of the local player at one tick. Spec §8.
 * Re-read each tick via {@code Artemis.player()} — never cached across
 * ticks by callers (the location and animation move).
 *
 * @param loc       server-side world position
 * @param plane     player's current plane (0..3); {@code -1} when not signed in
 * @param animation current animation id; {@code -1} when no animation
 * @param hp        current hitpoints (boosted level, not max)
 * @param prayer    current prayer points (boosted level, not max)
 * @param energy    run energy in <b>hundredths of a percent (0..10000)</b>,
 *                  matching {@code Client.getEnergy()} units. Divide by 100
 *                  for percentage. v1 leaves the raw scale to avoid losing
 *                  precision; consumers should normalise at their use site.
 * @param idle      <b>v1 approximation:</b> true when
 *                  {@code animation == -1 && getInteracting() == null}.
 *                  A more accurate "standing still" check uses
 *                  {@code Player.getPoseAnimation() == getIdlePoseAnimation()}
 *                  per CLAUDE.md; refine in v1.x when the first script
 *                  consumes {@code idle} for a decision.
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
