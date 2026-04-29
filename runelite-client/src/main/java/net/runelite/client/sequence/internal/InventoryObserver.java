package net.runelite.client.sequence.internal;

import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.sequence.views.InventoryView;
import net.runelite.client.sequence.views.ItemStack;

/**
 * Per-tick observer for the player's inventory container.
 * Tracks content hash to detect changes and report {@link #lastChangeTick()}.
 *
 * <p>All client reads happen on the caller's thread — callers must be on the
 * client thread (same invariant as {@link ClientObserver}).
 */
final class InventoryObserver {
    private final Client client;

    private long prevHash = Long.MIN_VALUE;
    private int lastChangeTick = -1;

    InventoryObserver(Client client) {
        this.client = client;
    }

    /** Returns the last tick on which inventory contents changed, or -1 if never. */
    int lastChangeTick() {
        return lastChangeTick;
    }

    /** Reads the current inventory state and updates change-tracking. */
    InventoryView snapshot(int currentTick) {
        ItemContainer container = client.getItemContainer(InventoryID.INV);
        if (container == null) {
            // No container — treat as empty inventory; don't update hash/tick so
            // a future container appearance correctly fires a change event.
            return InventoryView.empty();
        }

        Item[] raw = container.getItems();
        List<ItemStack> items = new ArrayList<>(raw.length);
        for (int slot = 0; slot < raw.length; slot++) {
            Item it = raw[slot];
            if (it != null && it.getId() != -1 && it.getQuantity() > 0) {
                items.add(new ItemStack(slot, it.getId(), it.getQuantity()));
            }
        }

        long hash = computeHash(items);
        if (hash != prevHash) {
            prevHash = hash;
            lastChangeTick = currentTick;
        }

        final List<ItemStack> snapshot = List.copyOf(items);
        final int totalSlots = container.size();

        return new InventoryView() {
            @Override public int size()               { return totalSlots; }
            @Override public int freeSlots()          { return totalSlots - snapshot.size(); }
            @Override public boolean isFull()         { return snapshot.size() >= totalSlots; }
            @Override public List<ItemStack> items()  { return snapshot; }
            @Override public boolean contains(int id) { return snapshot.stream().anyMatch(s -> s.itemId() == id); }
            @Override public int count(int id)        { return snapshot.stream().filter(s -> s.itemId() == id).mapToInt(ItemStack::quantity).sum(); }
        };
    }

    /** Stable hash of all (slot, itemId, quantity) triples in the list. */
    private static long computeHash(List<ItemStack> items) {
        long h = 1L;
        for (ItemStack s : items) {
            h = 31L * h + s.slot();
            h = 31L * h + s.itemId();
            h = 31L * h + s.quantity();
        }
        return h;
    }
}
