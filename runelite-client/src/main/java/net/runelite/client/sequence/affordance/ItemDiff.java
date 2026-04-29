package net.runelite.client.sequence.affordance;

/**
 * Describes a discrepancy between what the player has in inventory and what the loadout requires.
 * Used by {@link BlockReason.LoadoutMismatch}.
 */
public record ItemDiff(int itemId, String name, int have, int want) {}
