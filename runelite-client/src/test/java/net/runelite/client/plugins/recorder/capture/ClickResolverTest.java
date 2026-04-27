/*
 * Copyright (c) 2025, https://github.com/runelite/runelite
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
 * LOSS OF USE, DATA, OR PROFITS; HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.recorder.capture;

import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Tile;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.recorder.buffer.RecordingBuffer;
import net.runelite.client.plugins.recorder.events.Events;
import net.runelite.client.plugins.recorder.events.RecordedEvent;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.util.ArrayList;
import java.util.List;

public class ClickResolverTest
{
    @Test
    public void widgetClickProducesWidgetClickEvent()
    {
        Client client = mock(Client.class);
        Widget w = mock(Widget.class);
        when(w.getId()).thenReturn(149 << 16 | 0);
        when(w.getBounds()).thenReturn(new java.awt.Rectangle(100, 200, 32, 32));
        when(w.getItemId()).thenReturn(1511);
        when(client.getWidget(anyInt())).thenReturn(w);
        when(client.getMouseCanvasPosition()).thenReturn(new net.runelite.api.Point(112, 215));

        MenuEntry entry = mock(MenuEntry.class);
        when(entry.getType()).thenReturn(MenuAction.CC_OP);
        when(entry.getParam1()).thenReturn(149 << 16 | 0);
        when(entry.getParam0()).thenReturn(0);
        when(entry.getOption()).thenReturn("Drop");
        when(entry.getTarget()).thenReturn("Coins");

        MenuOptionClicked e = mock(MenuOptionClicked.class);
        when(e.getMenuEntry()).thenReturn(entry);

        RecordingBuffer buf = new RecordingBuffer();
        new ClickResolver().resolveAndEnqueue(buf, client, e, 0);
        List<RecordedEvent> out = new ArrayList<>();
        buf.drainTo(out);
        assertEquals(1, out.size());
        assertTrue(out.get(0) instanceof Events.WidgetClick);
        Events.WidgetClick wc = (Events.WidgetClick) out.get(0);
        assertEquals(1511, wc.itemId());
        assertEquals(12, wc.offsetX());
        assertEquals(15, wc.offsetY());
    }

    @Test
    public void worldEntityClickProducesWorldClickEvent()
    {
        Client client = mock(Client.class);
        when(client.getMouseCanvasPosition()).thenReturn(new net.runelite.api.Point(400, 300));

        MenuEntry entry = mock(MenuEntry.class);
        when(entry.getType()).thenReturn(MenuAction.NPC_FIRST_OPTION);
        when(entry.getIdentifier()).thenReturn(2);
        NPC npc = mock(NPC.class);
        when(npc.getName()).thenReturn("Cow");
        when(npc.getId()).thenReturn(2805);
        when(npc.getWorldLocation()).thenReturn(new WorldPoint(3220, 3260, 0));
        when(entry.getNpc()).thenReturn(npc);
        when(entry.getOption()).thenReturn("Attack");
        when(entry.getTarget()).thenReturn("Cow");

        MenuOptionClicked e = mock(MenuOptionClicked.class);
        when(e.getMenuEntry()).thenReturn(entry);

        RecordingBuffer buf = new RecordingBuffer();
        new ClickResolver().resolveAndEnqueue(buf, client, e, 0);
        List<RecordedEvent> out = new ArrayList<>();
        buf.drainTo(out);
        assertEquals(1, out.size());
        assertTrue(out.get(0) instanceof Events.WorldClick);
        Events.WorldClick wc = (Events.WorldClick) out.get(0);
        assertEquals("npc", wc.entityKind());
        assertEquals(2805, wc.entityId());
        assertEquals(3220, wc.worldX());
    }

    @Test
    public void playerTradeProducesWorldClickWithEntityKindPlayer()
    {
        Client client = mock(Client.class);
        when(client.getMouseCanvasPosition()).thenReturn(new net.runelite.api.Point(420, 280));

        MenuEntry entry = mock(MenuEntry.class);
        when(entry.getType()).thenReturn(MenuAction.PLAYER_FOURTH_OPTION);
        Player player = mock(Player.class);
        when(player.getName()).thenReturn("Hatcholio");
        when(player.getId()).thenReturn(7);
        when(player.getWorldLocation()).thenReturn(new WorldPoint(3164, 3491, 0));
        when(entry.getPlayer()).thenReturn(player);
        when(entry.getOption()).thenReturn("Trade with");
        when(entry.getTarget()).thenReturn("Hatcholio");

        MenuOptionClicked e = mock(MenuOptionClicked.class);
        when(e.getMenuEntry()).thenReturn(entry);

        RecordingBuffer buf = new RecordingBuffer();
        new ClickResolver().resolveAndEnqueue(buf, client, e, 0);
        List<RecordedEvent> out = new ArrayList<>();
        buf.drainTo(out);
        assertEquals(1, out.size());
        assertTrue(out.get(0) instanceof Events.WorldClick);
        Events.WorldClick wc = (Events.WorldClick) out.get(0);
        assertEquals("player", wc.entityKind());
        assertEquals(7, wc.entityId());
        assertEquals("Hatcholio", wc.entityName());
        assertEquals(3164, wc.worldX());
        assertEquals(3491, wc.worldY());
    }

    @Test
    public void grandExchangeWidgetClickIsClassifiedAsGeOffers()
    {
        Client client = mock(Client.class);
        Widget w = mock(Widget.class);
        int widgetId = (465 << 16) | 1;
        when(w.getId()).thenReturn(widgetId);
        when(w.getBounds()).thenReturn(new java.awt.Rectangle(20, 30, 40, 40));
        when(w.getItemId()).thenReturn(-1);
        when(client.getWidget(anyInt())).thenReturn(w);
        when(client.getMouseCanvasPosition()).thenReturn(new net.runelite.api.Point(40, 50));

        MenuEntry entry = mock(MenuEntry.class);
        when(entry.getType()).thenReturn(MenuAction.CC_OP);
        when(entry.getParam1()).thenReturn(widgetId);
        when(entry.getParam0()).thenReturn(0);
        when(entry.getOption()).thenReturn("View");
        when(entry.getTarget()).thenReturn("Offer 1");

        MenuOptionClicked e = mock(MenuOptionClicked.class);
        when(e.getMenuEntry()).thenReturn(entry);

        RecordingBuffer buf = new RecordingBuffer();
        new ClickResolver().resolveAndEnqueue(buf, client, e, 0);
        List<RecordedEvent> out = new ArrayList<>();
        buf.drainTo(out);
        assertEquals(1, out.size());
        Events.WidgetClick wc = (Events.WidgetClick) out.get(0);
        assertEquals("ge_offers", wc.widgetKind());
    }

    @Test
    public void walkClickUsesSelectedSceneTileForWorldCoords()
    {
        // For MenuAction.WALK, param0/param1 are screen pixel coords, NOT
        // scene-tile coords — so the resolver must consult the engine's
        // selected scene tile to get the real world destination.
        Client client = mock(Client.class);
        when(client.getMouseCanvasPosition()).thenReturn(new net.runelite.api.Point(495, 189));
        when(client.getPlane()).thenReturn(0);
        Tile selected = mock(Tile.class);
        when(selected.getWorldLocation()).thenReturn(new WorldPoint(3185, 3442, 0));
        when(client.getSelectedSceneTile()).thenReturn(selected);

        MenuEntry entry = mock(MenuEntry.class);
        when(entry.getType()).thenReturn(MenuAction.WALK);
        when(entry.getParam0()).thenReturn(495);   // screen pixel echo, not scene coord
        when(entry.getParam1()).thenReturn(189);
        when(entry.getOption()).thenReturn("Walk here");
        when(entry.getTarget()).thenReturn("");

        MenuOptionClicked e = mock(MenuOptionClicked.class);
        when(e.getMenuEntry()).thenReturn(entry);

        RecordingBuffer buf = new RecordingBuffer();
        new ClickResolver().resolveAndEnqueue(buf, client, e, 0);
        List<RecordedEvent> out = new ArrayList<>();
        buf.drainTo(out);
        assertEquals(1, out.size());
        Events.WorldClick wc = (Events.WorldClick) out.get(0);
        assertEquals("ground", wc.entityKind());
        assertEquals(3185, wc.worldX());
        assertEquals(3442, wc.worldY());
    }

    @Test
    public void setHeadingClickIsTaggedMinimapWalk()
    {
        Client client = mock(Client.class);
        when(client.getMouseCanvasPosition()).thenReturn(new net.runelite.api.Point(700, 130));
        when(client.getPlane()).thenReturn(0);
        Tile selected = mock(Tile.class);
        when(selected.getWorldLocation()).thenReturn(new WorldPoint(3220, 3460, 0));
        when(client.getSelectedSceneTile()).thenReturn(selected);

        MenuEntry entry = mock(MenuEntry.class);
        when(entry.getType()).thenReturn(MenuAction.SET_HEADING);
        when(entry.getParam0()).thenReturn(0);
        when(entry.getParam1()).thenReturn(0);
        when(entry.getOption()).thenReturn("Walk here");
        when(entry.getTarget()).thenReturn("");

        MenuOptionClicked e = mock(MenuOptionClicked.class);
        when(e.getMenuEntry()).thenReturn(entry);

        RecordingBuffer buf = new RecordingBuffer();
        new ClickResolver().resolveAndEnqueue(buf, client, e, 0);
        List<RecordedEvent> out = new ArrayList<>();
        buf.drainTo(out);
        assertEquals(1, out.size());
        Events.WorldClick wc = (Events.WorldClick) out.get(0);
        assertEquals("minimap_walk", wc.entityKind());
        assertEquals(3220, wc.worldX());
        assertEquals(3460, wc.worldY());
    }
}
