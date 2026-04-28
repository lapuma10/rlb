/*
 * Copyright (c) 2026, RuneLite
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
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
