package net.runelite.client.sequence.artemis.view;

/**
 * One inventory slot. Spec §8. {@code itemId == -1} or
 * {@code quantity == 0} means the slot is empty.
 */
public record InvSlot(
	int slotIdx,
	int itemId,
	int quantity,
	String name
)
{
	/** Sentinel for empty slot. */
	public static final int EMPTY_ITEM_ID = -1;

	public boolean isEmpty()
	{
		return itemId == EMPTY_ITEM_ID || quantity == 0;
	}
}
