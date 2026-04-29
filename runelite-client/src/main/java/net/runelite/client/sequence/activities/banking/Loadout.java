package net.runelite.client.sequence.activities.banking;

import java.util.List;

/**
 * Describes a desired inventory loadout as a list of slots.
 *
 * <p>Used by {@link EnsureInventoryMatchesLoadoutStep} to verify the inventory
 * contains the expected items after a banking sequence.
 */
public final class Loadout {

    /**
     * A single required inventory slot.
     *
     * @param itemId the item that must be present
     * @param qty    the required quantity
     * @param exact  if true, {@code inv.count(itemId) == qty} must hold;
     *               if false, {@code inv.count(itemId) >= 1} is sufficient
     */
    public record Slot(int itemId, int qty, boolean exact) {}

    private final List<Slot> slots;

    public Loadout(List<Slot> slots) {
        this.slots = List.copyOf(slots);
    }

    public List<Slot> slots() {
        return slots;
    }
}
