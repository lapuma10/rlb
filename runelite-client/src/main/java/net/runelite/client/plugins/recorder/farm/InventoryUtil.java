package net.runelite.client.plugins.recorder.farm;

import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;

/** Inventory snapshot helpers. Reads the player inventory item container
 *  and counts empty slots; works on the client thread. */
public final class InventoryUtil
{
    public static final int INVENTORY_SIZE = 28;

    private InventoryUtil() {}

    public static int freeSlotCount(Client client)
    {
        ItemContainer inv = client.getItemContainer(InventoryID.INV);
        if (inv == null) return INVENTORY_SIZE;
        Item[] items = inv.getItems();
        if (items == null) return INVENTORY_SIZE;
        int free = INVENTORY_SIZE - items.length;
        for (Item it : items)
        {
            if (it == null || it.getId() == -1) free++;
        }
        return Math.min(free, INVENTORY_SIZE);
    }

    public static boolean isInventoryFull(Client client)
    {
        return freeSlotCount(client) == 0;
    }
}
