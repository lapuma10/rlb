package net.runelite.client.sequence.artemis.view;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pins {@link InventoryView}'s compact-constructor defensive-copy
 * contract for {@code slots}: a caller handing in a mutable list
 * cannot mutate the record's view afterwards, and the record's
 * accessor returns an unmodifiable list.
 */
public class InventoryViewDefensiveCopyTest
{
	@Test
	public void canonicalConstructorCopiesSlots()
	{
		List<InvSlot> mutable = new ArrayList<>();
		mutable.add(new InvSlot(0, 526, 5, "Bones"));
		mutable.add(new InvSlot(1, 1739, 3, "Cowhide"));

		InventoryView view = new InventoryView(mutable);

		// Pre-mutation count is exactly what we built.
		assertEquals(2, view.slots().size());
		assertEquals(5, view.count(526));

		// Mutate the original list after handoff.
		mutable.add(new InvSlot(2, 526, 99, "Bones"));

		// The view must NOT see the post-handoff additions.
		assertEquals("slots must be defensively copied on construction",
			2, view.slots().size());
		assertEquals(5, view.count(526));
	}

	@Test(expected = UnsupportedOperationException.class)
	public void slotsAccessorReturnsUnmodifiableList()
	{
		InventoryView view = new InventoryView(List.of(
			new InvSlot(0, 526, 5, "Bones")));
		view.slots().add(new InvSlot(1, 1739, 3, "Cowhide"));   // expected to throw
	}

	@Test
	public void emptyInventoryIsNotFull()
	{
		InventoryView empty = new InventoryView(List.of());
		assertFalse(empty.isFull());
		assertEquals(0, empty.count(526));
		assertFalse(empty.has(526));
	}

	@Test
	public void inventoryWithAllSlotsOccupiedIsFull()
	{
		List<InvSlot> slots = new ArrayList<>();
		for (int i = 0; i < InventoryView.SLOT_COUNT; i++)
		{
			slots.add(new InvSlot(i, 526, 1, "Bones"));
		}
		InventoryView full = new InventoryView(slots);
		assertTrue(full.isFull());
		assertEquals(28, full.count(526));
	}

	@Test
	public void inventoryWithOneEmptySlotIsNotFull()
	{
		List<InvSlot> slots = new ArrayList<>();
		for (int i = 0; i < InventoryView.SLOT_COUNT - 1; i++)
		{
			slots.add(new InvSlot(i, 526, 1, "Bones"));
		}
		// Last slot is empty (itemId = -1).
		slots.add(new InvSlot(27, InvSlot.EMPTY_ITEM_ID, 0, null));

		InventoryView almostFull = new InventoryView(slots);
		assertFalse(almostFull.isFull());
	}
}
