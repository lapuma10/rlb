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
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.recorder.buffer.RecordingBuffer;
import net.runelite.client.plugins.recorder.events.Events;
import java.util.HashMap;
import java.util.Map;

/** Inspects a MenuOptionClicked and enqueues either a widget_click or a
 *  world_click depending on the menu type. */
public final class ClickResolver
{
    public void resolveAndEnqueue(RecordingBuffer buf, Client client, MenuOptionClicked e, int tick)
    {
        MenuEntry entry = e.getMenuEntry();
        MenuAction type = entry.getType();
        if (isWidgetAction(type))
        {
            int widgetId = entry.getParam1();
            Widget w = client.getWidget(widgetId);
            if (w == null) return;
            var bbox = w.getBounds();
            int bx = bbox == null ? 0 : bbox.x;
            int by = bbox == null ? 0 : bbox.y;
            int bw = bbox == null ? 0 : bbox.width;
            int bh = bbox == null ? 0 : bbox.height;
            int slot = entry.getParam0();
            int itemId = w.getItemId();
            int mx = client.getMouseCanvasPosition().getX();
            int my = client.getMouseCanvasPosition().getY();
            String kind = classifyWidgetKind(widgetId);
            buf.enqueue((seq, tMs) -> new Events.WidgetClick(seq, tMs, tick,
                widgetId, itemId, slot, bx, by, bw, bh, mx - bx, my - by, kind));
        }
        else if (type == MenuAction.NPC_FIRST_OPTION || type == MenuAction.NPC_SECOND_OPTION
                || type == MenuAction.NPC_THIRD_OPTION || type == MenuAction.NPC_FOURTH_OPTION
                || type == MenuAction.NPC_FIFTH_OPTION)
        {
            NPC npc = entry.getNpc();
            if (npc == null) return;
            WorldPoint wp = npc.getWorldLocation();
            int x = wp == null ? 0 : wp.getX();
            int y = wp == null ? 0 : wp.getY();
            int p = wp == null ? 0 : wp.getPlane();
            int sx = client.getMouseCanvasPosition().getX();
            int sy = client.getMouseCanvasPosition().getY();
            buf.enqueue((seq, tMs) -> new Events.WorldClick(seq, tMs, tick,
                "npc", npc.getId(), npc.getName() == null ? "" : npc.getName(),
                x, y, p, sx, sy));
        }
        else if (type == MenuAction.PLAYER_FIRST_OPTION || type == MenuAction.PLAYER_SECOND_OPTION
                || type == MenuAction.PLAYER_THIRD_OPTION || type == MenuAction.PLAYER_FOURTH_OPTION
                || type == MenuAction.PLAYER_FIFTH_OPTION || type == MenuAction.PLAYER_SIXTH_OPTION
                || type == MenuAction.PLAYER_SEVENTH_OPTION || type == MenuAction.PLAYER_EIGHTH_OPTION)
        {
            Player player = entry.getPlayer();
            if (player == null) return;
            WorldPoint wp = player.getWorldLocation();
            int x = wp == null ? 0 : wp.getX();
            int y = wp == null ? 0 : wp.getY();
            int p = wp == null ? 0 : wp.getPlane();
            int sx = client.getMouseCanvasPosition().getX();
            int sy = client.getMouseCanvasPosition().getY();
            buf.enqueue((seq, tMs) -> new Events.WorldClick(seq, tMs, tick,
                "player", player.getId(), player.getName() == null ? "" : player.getName(),
                x, y, p, sx, sy));
        }
        else if (type == MenuAction.GAME_OBJECT_FIRST_OPTION || type == MenuAction.GAME_OBJECT_SECOND_OPTION
                || type == MenuAction.GAME_OBJECT_THIRD_OPTION || type == MenuAction.GAME_OBJECT_FOURTH_OPTION
                || type == MenuAction.GAME_OBJECT_FIFTH_OPTION)
        {
            int id = entry.getIdentifier();
            int param0 = entry.getParam0();
            int param1 = entry.getParam1();
            int sx = client.getMouseCanvasPosition().getX();
            int sy = client.getMouseCanvasPosition().getY();
            int bx = client.getBaseX() + param0;
            int by = client.getBaseY() + param1;
            int p = client.getPlane();
            buf.enqueue((seq, tMs) -> new Events.WorldClick(seq, tMs, tick,
                "object", id, "", bx, by, p, sx, sy));
        }
        else if (type == MenuAction.WALK || type == MenuAction.SET_HEADING)
        {
            // WALK = click on the game world ("Walk here"); SET_HEADING = click
            // on the minimap. param0/param1 are NOT scene-tile coords for these
            // actions — they're echoes of the mouse screen pixel position. The
            // engine resolves the actual destination tile and stamps it on
            // either the selected-scene-tile or the player's localDestination.
            int sx = client.getMouseCanvasPosition().getX();
            int sy = client.getMouseCanvasPosition().getY();
            int wx = 0, wy = 0, p = client.getPlane();
            Tile selected = client.getSelectedSceneTile();
            if (selected != null && selected.getWorldLocation() != null)
            {
                WorldPoint wp = selected.getWorldLocation();
                wx = wp.getX(); wy = wp.getY(); p = wp.getPlane();
            }
            else
            {
                Player local = client.getLocalPlayer();
                LocalPoint dest = local == null ? null : local.getLocalLocation();
                if (dest != null)
                {
                    WorldPoint wp = WorldPoint.fromLocal(client, dest);
                    wx = wp.getX(); wy = wp.getY(); p = wp.getPlane();
                }
            }
            int worldX = wx, worldY = wy, plane = p;
            String kind = type == MenuAction.SET_HEADING ? "minimap_walk" : "ground";
            buf.enqueue((seq, tMs) -> new Events.WorldClick(seq, tMs, tick,
                kind, 0, "", worldX, worldY, plane, sx, sy));
        }
    }

    private static boolean isWidgetAction(MenuAction t)
    {
        return t == MenuAction.CC_OP || t == MenuAction.CC_OP_LOW_PRIORITY
            || t == MenuAction.WIDGET_TARGET_ON_WIDGET
            || t == MenuAction.WIDGET_FIRST_OPTION || t == MenuAction.WIDGET_SECOND_OPTION
            || t == MenuAction.WIDGET_THIRD_OPTION || t == MenuAction.WIDGET_FOURTH_OPTION
            || t == MenuAction.WIDGET_FIFTH_OPTION || t == MenuAction.WIDGET_CONTINUE
            || t == MenuAction.WIDGET_USE_ON_ITEM;
    }

    /** Map widget id to a stable categorical name. The keys are top-level
     *  InterfaceID parents (widgetId >>> 16). Anything not listed falls
     *  through to "widget_<parent>" so it's still observable, just nameless. */
    private static final Map<Integer, String> WIDGET_KIND = buildWidgetKindMap();

    private static Map<Integer, String> buildWidgetKindMap()
    {
        Map<Integer, String> m = new HashMap<>();
        m.put(InterfaceID.INVENTORY, "inventory");
        m.put(InterfaceID.INVENTORY_NOOPS, "inventory_noops");
        m.put(InterfaceID.TOPLEVEL_PRE_EOC, "toplevel_pre_eoc");
        m.put(InterfaceID.TOPLEVEL, "toplevel");
        m.put(InterfaceID.BANKMAIN, "bank");
        m.put(InterfaceID.BANKSIDE, "bank_side");
        m.put(InterfaceID.BANK_DEPOSITBOX, "deposit_box");
        m.put(InterfaceID.EQUIPMENT, "equipment");
        m.put(InterfaceID.EQUIPMENT_SIDE, "equipment_side");
        m.put(InterfaceID.MAGIC_SPELLBOOK, "magic_spellbook");
        m.put(InterfaceID.PRAYERBOOK, "prayerbook");
        m.put(InterfaceID.QUICKPRAYER, "quickprayer");
        m.put(InterfaceID.COMBAT_INTERFACE, "combat");
        m.put(InterfaceID.WORLDMAP, "worldmap");
        m.put(InterfaceID.CHATBOX, "chatbox");
        m.put(InterfaceID.LOGOUT, "logout");
        m.put(InterfaceID.SETTINGS, "settings");
        m.put(InterfaceID.SETTINGS_SIDE, "settings_side");
        m.put(InterfaceID.ACCOUNT, "account");
        m.put(InterfaceID.FRIENDS, "friends");
        m.put(InterfaceID.IGNORE, "ignore");
        m.put(InterfaceID.EMOTE, "emote");
        m.put(InterfaceID.MUSIC, "music");
        m.put(InterfaceID.GE_OFFERS, "ge_offers");
        m.put(InterfaceID.GE_OFFERS_SIDE, "ge_offers_side");
        m.put(InterfaceID.GE_PRICELIST, "ge_pricelist");
        m.put(InterfaceID.GE_VIEWONLY, "ge_view_only");
        m.put(InterfaceID.TRADEMAIN, "trade_main");
        m.put(InterfaceID.TRADESIDE, "trade_side");
        m.put(InterfaceID.TRADECONFIRM, "trade_confirm");
        return m;
    }

    private static String classifyWidgetKind(int widgetId)
    {
        int parent = widgetId >>> 16;
        int child = widgetId & 0xFFFF;
        // Inside the resizable-classic top-level layout (164), the same parent
        // hosts the minimap, the orbs, and ~28 side-tab elements. Tagging
        // them all "toplevel_pre_eoc" would lump tab switches in with map
        // clicks. Drill into known children so the summary tells the truth.
        if (parent == InterfaceID.TOPLEVEL_PRE_EOC)
        {
            if (child == 0x1e) return "minimap";
            if (child == 0x1f) return "compass";
            if (child == 0x21) return "hud_orbs";
            if ((child >= 0x22 && child <= 0x2b) || (child >= 0x34 && child <= 0x3a)) return "side_tab";
            if ((child >= 0x2c && child <= 0x31) || (child >= 0x3b && child <= 0x41)) return "side_tab_icon";
        }
        String name = WIDGET_KIND.get(parent);
        return name == null ? "widget_" + parent : name;
    }
}
