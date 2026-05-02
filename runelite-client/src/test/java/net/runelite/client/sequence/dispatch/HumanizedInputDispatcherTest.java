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
package net.runelite.client.sequence.dispatch;

import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.IndexedObjectSet;
import net.runelite.api.MenuAction;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.menus.TestMenuEntry;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class HumanizedInputDispatcherTest
{
    @Test
    public void isTakeOnGroundItemEntry_requiresGroundItemAction()
    {
        TestMenuEntry takeItem = new TestMenuEntry();
        takeItem.setOption("Take");
        takeItem.setIdentifier(314);
        takeItem.setType(MenuAction.GROUND_ITEM_FIRST_OPTION);
        TestMenuEntry attackNpc = new TestMenuEntry();
        attackNpc.setOption("Take");
        attackNpc.setIdentifier(314);
        attackNpc.setType(MenuAction.NPC_FIRST_OPTION);

        assertTrue(HumanizedInputDispatcher.isTakeOnGroundItemEntry(takeItem, 314));
        assertFalse(HumanizedInputDispatcher.isTakeOnGroundItemEntry(attackNpc, 314));
    }

    @Test
    public void groundItemMenuAction_onlyAcceptsGroundItemTypes()
    {
        assertTrue(HumanizedInputDispatcher.isGroundItemMenuAction(MenuAction.GROUND_ITEM_FIRST_OPTION));
        assertTrue(HumanizedInputDispatcher.isGroundItemMenuAction(MenuAction.GROUND_ITEM_FIFTH_OPTION));
        assertFalse(HumanizedInputDispatcher.isGroundItemMenuAction(MenuAction.NPC_FIRST_OPTION));
        assertFalse(HumanizedInputDispatcher.isGroundItemMenuAction(MenuAction.WALK));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void tileHasConflictingActor_ignoresSelf_butFlagsNpcAndOtherPlayer()
    {
        Client client = mock(Client.class);
        WorldView wv = mock(WorldView.class);
        Player self = mock(Player.class);
        Player other = mock(Player.class);
        NPC chicken = mock(NPC.class);
        IndexedObjectSet npcSet = mock(IndexedObjectSet.class);
        IndexedObjectSet playerSet = mock(IndexedObjectSet.class);
        WorldPoint tile = new WorldPoint(3236, 3296, 0);

        when(client.getTopLevelWorldView()).thenReturn(wv);
        when(client.getLocalPlayer()).thenReturn(self);
        when(npcSet.iterator()).thenAnswer(inv -> List.of(chicken).iterator());
        when(playerSet.iterator()).thenAnswer(inv -> List.of(self, other).iterator());
        when(wv.npcs()).thenReturn(npcSet);
        when(wv.players()).thenReturn(playerSet);
        when(chicken.getWorldLocation()).thenReturn(tile);
        when(other.getWorldLocation()).thenReturn(new WorldPoint(3200, 3200, 0));
        when(self.getWorldLocation()).thenReturn(tile);

        HumanizedInputDispatcher dispatcher = new HumanizedInputDispatcher(client, null);
        assertTrue(dispatcher.tileHasConflictingActor(tile));

        when(npcSet.iterator()).thenAnswer(inv -> List.<NPC>of().iterator());
        when(other.getWorldLocation()).thenReturn(tile);
        assertTrue(dispatcher.tileHasConflictingActor(tile));

        when(playerSet.iterator()).thenAnswer(inv -> List.of(self).iterator());
        assertFalse(dispatcher.tileHasConflictingActor(tile));
    }
}
