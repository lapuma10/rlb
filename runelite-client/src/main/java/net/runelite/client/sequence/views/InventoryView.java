package net.runelite.client.sequence.views;

import java.util.List;

/**
 * Snapshot view of the player's inventory. Read-only; immutable per snapshot.
 *
 * <p>Steps consult this to decide whether a precondition is met (enough
 * coins, enough items to sell, free slot for a withdraw, etc.) without
 * touching the live RuneLite ItemContainer on a non-client thread.
 *
 * <p>{@link #empty()} returns the engine-default null-object so existing
 * {@code WorldSnapshot} implementations keep compiling without supplying
 * an inventory.
 */
public interface InventoryView {

    /** Total inventory capacity. Always 28 for player inventory. */
    int size();

    /** Number of empty slots. */
    int freeSlots();

    /** True iff every slot is occupied. */
    boolean isFull();

    /** All non-empty slots, in slot index order. */
    List<ItemStack> items();

    /** True iff at least one slot holds {@code itemId}. */
    boolean contains(int itemId);

    /** Sum of quantities of every stack of {@code itemId} in inventory. */
    int count(int itemId);

    /** Engine-default null-object: empty 28-slot inventory. */
    static InventoryView empty() { return EMPTY; }

    InventoryView EMPTY = new InventoryView() {
        public int size()                        { return 28; }
        public int freeSlots()                   { return 28; }
        public boolean isFull()                  { return false; }
        public List<ItemStack> items()           { return List.of(); }
        public boolean contains(int itemId)      { return false; }
        public int count(int itemId)             { return 0; }
    };
}
