package net.runelite.client.sequence.views;

/**
 * One slot of inventory. {@code itemId} is from
 * {@link net.runelite.api.gameval.ItemID}; {@code quantity} is the stack
 * size (1 for non-stackable items).
 */
public record ItemStack(int slot, int itemId, int quantity) {}
