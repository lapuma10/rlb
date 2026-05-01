package net.runelite.client.sequence.views;

/**
 * One slot of inventory.
 *
 * <p>{@code itemId} is the actual item id present in the slot — including
 * the noted form for noted items (e.g. {@code 1936} for noted Jug).
 *
 * <p>{@code unnotedId} is the canonical (unnoted) item id this slot maps to —
 * resolved once at snapshot time in {@link
 * net.runelite.client.sequence.internal.InventoryObserver}. For unnoted
 * items this equals {@code itemId}; for noted stacks it is the unnoted
 * form's id (e.g. {@code 1935} for noted Jug). Steps that care about
 * "do I have any form of this item" should compare against {@code unnotedId},
 * not {@code itemId}, via {@link InventoryView#countAnyForm(int)} or
 * {@link InventoryView#findAnyForm(int)} — never re-derive noted/unnoted
 * mapping by calling {@code getNote()} (which returns {@code 799} as a
 * marker, not the linked id) or by using the {@code itemId+1} heuristic
 * (it doesn't hold for every item).
 */
public record ItemStack(int slot, int itemId, int quantity, int unnotedId) {
    /** Convenience constructor for callers that don't deal with noted items
     *  (tests, plain snapshots). Defaults {@code unnotedId} to {@code itemId}. */
    public ItemStack(int slot, int itemId, int quantity) {
        this(slot, itemId, quantity, itemId);
    }
}
