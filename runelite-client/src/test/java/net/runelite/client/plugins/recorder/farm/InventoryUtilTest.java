package net.runelite.client.plugins.recorder.farm;

import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class InventoryUtilTest
{
    @Test
    public void emptyInventoryHas28FreeSlots()
    {
        Client c = mock(Client.class);
        ItemContainer inv = mock(ItemContainer.class);
        when(c.getItemContainer(InventoryID.INV)).thenReturn(inv);
        Item[] items = new Item[28];
        for (int i = 0; i < 28; i++)
        {
            items[i] = new Item(-1, 0);
        }
        when(inv.getItems()).thenReturn(items);
        assertEquals(28, InventoryUtil.freeSlotCount(c));
        assertFalse(InventoryUtil.isInventoryFull(c));
    }

    @Test
    public void fullInventoryIsFull()
    {
        Client c = mock(Client.class);
        ItemContainer inv = mock(ItemContainer.class);
        when(c.getItemContainer(InventoryID.INV)).thenReturn(inv);
        Item[] items = new Item[28];
        for (int i = 0; i < 28; i++)
        {
            items[i] = new Item(314, 1);
        }
        when(inv.getItems()).thenReturn(items);
        assertEquals(0, InventoryUtil.freeSlotCount(c));
        assertTrue(InventoryUtil.isInventoryFull(c));
    }

    @Test
    public void nullContainerTreatedAsFullyEmpty()
    {
        Client c = mock(Client.class);
        when(c.getItemContainer(InventoryID.INV)).thenReturn(null);
        assertEquals(28, InventoryUtil.freeSlotCount(c));
    }

    @Test
    public void partialInventoryReportsCorrectFreeCount()
    {
        Client c = mock(Client.class);
        ItemContainer inv = mock(ItemContainer.class);
        when(c.getItemContainer(InventoryID.INV)).thenReturn(inv);
        Item[] items = new Item[28];
        // 5 occupied (id 314), 23 empty (id -1).
        for (int i = 0; i < 28; i++)
        {
            items[i] = i < 5 ? new Item(314, 1) : new Item(-1, 0);
        }
        when(inv.getItems()).thenReturn(items);
        assertEquals(23, InventoryUtil.freeSlotCount(c));
        assertFalse(InventoryUtil.isInventoryFull(c));
    }

    @Test
    public void shortItemsArrayCountsTrailingSlotsAsFree()
    {
        // Engine sometimes returns a short array when only the first N slots
        // have ever been written. The util must treat the missing trailing
        // slots as empty.
        Client c = mock(Client.class);
        ItemContainer inv = mock(ItemContainer.class);
        when(c.getItemContainer(InventoryID.INV)).thenReturn(inv);
        Item[] items = new Item[10];
        for (int i = 0; i < 10; i++) items[i] = new Item(314, 1); // all occupied
        when(inv.getItems()).thenReturn(items);
        // 18 trailing slots not in the array, plus 0 free in the array → 18.
        assertEquals(18, InventoryUtil.freeSlotCount(c));
        assertFalse(InventoryUtil.isInventoryFull(c));
    }
}
