package net.runelite.client.sequence.artemis.view;

import java.util.List;
import java.util.Optional;

/**
 * Immutable view of the player's inventory at one tick. Spec §8.
 * v1: {@link #firstSlotOf(int)} returns whatever slot the engine
 * iterates first; per-account {@code RotationPolicy} pickup is a
 * v1.x extension if a concrete need surfaces.
 */
public record InventoryView(List<InvSlot> slots)
{
	/** Total slots in an OSRS inventory. */
	public static final int SLOT_COUNT = 28;

	/** Defensive copy on input — caller can't mutate the slots list
	 *  after handing it to InventoryView. Records-as-truly-immutable. */
	public InventoryView
	{
		slots = List.copyOf(slots);
	}

	/** Stacked count of {@code itemId} across all slots. */
	public int count(int itemId)
	{
		int total = 0;
		for (InvSlot s : slots)
		{
			if (s.itemId() == itemId)
			{
				total += s.quantity();
			}
		}
		return total;
	}

	public boolean has(int itemId)
	{
		return count(itemId) > 0;
	}

	/** True when every one of the 28 slots is occupied by a non-empty
	 *  item. (Does not handle the stackable-with-room edge case;
	 *  refine in v1.x if a script needs it.) */
	public boolean isFull()
	{
		if (slots.size() < SLOT_COUNT)
		{
			return false;
		}
		for (InvSlot s : slots)
		{
			if (s.isEmpty())
			{
				return false;
			}
		}
		return true;
	}

	public Optional<InvSlot> firstSlotOf(int itemId)
	{
		for (InvSlot s : slots)
		{
			if (s.itemId() == itemId)
			{
				return Optional.of(s);
			}
		}
		return Optional.empty();
	}

	public List<InvSlot> allSlotsOf(int itemId)
	{
		return slots.stream().filter(s -> s.itemId() == itemId).toList();
	}
}
