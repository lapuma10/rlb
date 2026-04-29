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
 * Reads {@link InventoryID#INV} on the client thread to produce an
 * immutable {@link InventoryView} snapshot. Caller marshals onto the client
 * thread via the engine's observer pipeline.
 */
public final class InventoryObserver {

    private static final int INVENTORY_SIZE = 28;

    private final Client client;

    public InventoryObserver(Client client) {
        this.client = client;
    }

    public InventoryView read(int tick) {
        ItemContainer inv = client.getItemContainer(InventoryID.INV);
        if (inv == null) return InventoryView.empty();

        Item[] raw = inv.getItems();
        if (raw == null) return InventoryView.empty();

        List<ItemStack> items = new ArrayList<>(raw.length);
        int filled = 0;
        for (int slot = 0; slot < raw.length; slot++) {
            Item it = raw[slot];
            if (it == null || it.getId() <= 0) continue;
            items.add(new ItemStack(slot, it.getId(), it.getQuantity()));
            filled++;
        }
        int captured = filled;
        List<ItemStack> immutable = List.copyOf(items);
        int free = Math.max(0, INVENTORY_SIZE - captured);
        return new SnapshotInventoryView(immutable, free, captured);
    }

    /** Immutable {@link InventoryView} backed by a copy of the inventory at snapshot time. */
    private static final class SnapshotInventoryView implements InventoryView {
        private final List<ItemStack> items;
        private final int freeSlots;
        private final int filled;

        SnapshotInventoryView(List<ItemStack> items, int freeSlots, int filled) {
            this.items = items;
            this.freeSlots = freeSlots;
            this.filled = filled;
        }

        @Override public int size()                    { return INVENTORY_SIZE; }
        @Override public int freeSlots()               { return freeSlots; }
        @Override public boolean isFull()              { return freeSlots == 0; }
        @Override public List<ItemStack> items()       { return items; }
        @Override public boolean contains(int itemId)  {
            for (ItemStack s : items) if (s.itemId() == itemId) return true;
            return false;
        }
        @Override public int count(int itemId) {
            int n = 0;
            for (ItemStack s : items) if (s.itemId() == itemId) n += s.quantity();
            return n;
        }
    }
}
