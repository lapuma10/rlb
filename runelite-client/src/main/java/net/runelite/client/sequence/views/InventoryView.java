package net.runelite.client.sequence.views;

import java.util.List;
import java.util.Optional;

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

    /** Sum of quantities of every stack whose canonical {@link ItemStack#unnotedId()}
     *  equals {@code unnotedItemId} — i.e. counts both the unnoted form AND the
     *  noted form together. Use this when you don't care which form is held.
     *  Built on {@link ItemStack#unnotedId()}, which is the single source of
     *  truth for noted/unnoted resolution (set in
     *  {@link net.runelite.client.sequence.internal.InventoryObserver} via
     *  {@code ItemComposition.getLinkedNoteId()}) — robust for all OSRS items
     *  (does not rely on the {@code unnoted+1 = noted} heuristic). */
    default int countAnyForm(int unnotedItemId) {
        int total = 0;
        for (ItemStack s : items()) {
            if (s.unnotedId() == unnotedItemId) total += s.quantity();
        }
        return total;
    }

    /** First slot whose canonical {@link ItemStack#unnotedId()} equals
     *  {@code unnotedItemId}. Returns the actual {@link ItemStack} present
     *  (which may carry the noted-form {@code itemId}) so callers that need
     *  the on-screen item id (e.g. CLICK_INV_ITEM) can read it directly. */
    default Optional<ItemStack> findAnyForm(int unnotedItemId) {
        for (ItemStack s : items()) {
            if (s.unnotedId() == unnotedItemId) return Optional.of(s);
        }
        return Optional.empty();
    }

    /** Engine-default null-object: empty 28-slot inventory. */
    static InventoryView empty() { return EMPTY; }

    InventoryView EMPTY = new InventoryView() {
        @Override public int size()                  { return 28; }
        @Override public int freeSlots()             { return 28; }
        @Override public boolean isFull()            { return false; }
        @Override public List<ItemStack> items()     { return List.of(); }
        @Override public boolean contains(int id)    { return false; }
        @Override public int count(int id)           { return 0; }
    };
}
