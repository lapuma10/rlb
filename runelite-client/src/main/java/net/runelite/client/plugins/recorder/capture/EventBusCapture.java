/*
 * Copyright (c) 2024, RuneLite
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
package net.runelite.client.plugins.recorder.capture;

import lombok.RequiredArgsConstructor;
import net.runelite.api.Client;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.recorder.buffer.RecordingBuffer;
import net.runelite.client.plugins.recorder.events.Events;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Subscribes to the RuneLite event bus and translates each interesting
 *  event into a RecordedEvent enqueued on the buffer. Only active when
 *  the buffer is non-null (set/cleared by RecorderManager start/stop). */
@RequiredArgsConstructor
public final class EventBusCapture
{
    private final Client client;
    private final ChatFilter chatFilter;
    private final ClickResolver clickResolver;
    private final NearbyResolver nearbyResolver;
    private final CameraSampler cameraSampler;

    private volatile RecordingBuffer buffer;
    private final Map<Integer, int[]> lastInvIds = new HashMap<>();
    private final Map<Integer, int[]> lastInvQtys = new HashMap<>();
    private final Map<Skill, Integer> lastXp = new EnumMap<>(Skill.class);
    /** Last observed walk-destination, used to detect changes per tick.
     *  Encoded as packed worldX*100000+worldY, or -1 if no destination. */
    private long lastWalkDestKey = -1L;

    public void setBuffer(RecordingBuffer buf)
    {
        this.buffer = buf;
        this.lastInvIds.clear();
        this.lastInvQtys.clear();
        this.lastXp.clear();
        this.lastWalkDestKey = -1L;
    }

    @Subscribe
    public void onGameTick(GameTick e)
    {
        RecordingBuffer b = buffer;
        if (b == null) return;
        var p = client.getLocalPlayer();
        if (p == null) return;
        int wx = p.getWorldLocation() == null ? 0 : p.getWorldLocation().getX();
        int wy = p.getWorldLocation() == null ? 0 : p.getWorldLocation().getY();
        int wp = p.getWorldLocation() == null ? 0 : p.getWorldLocation().getPlane();
        int anim = p.getAnimation();
        boolean idle = anim == -1;
        int run = client.getEnergy() / 100;
        boolean runOn = client.getVarpValue(173) == 1; // VARP_RUN
        int hp = client.getBoostedSkillLevel(net.runelite.api.Skill.HITPOINTS);
        int maxHp = client.getRealSkillLevel(net.runelite.api.Skill.HITPOINTS);
        int tick = client.getTickCount();
        b.enqueue((seq, tMs) -> new Events.Tick(seq, tMs, tick, wx, wy, wp, anim, idle, run, runOn, hp, maxHp));
        cameraSampler.sample(b, client, tick);
        emitWalkDestIfChanged(b, tick);
    }

    /** Source-of-truth walk capture. Whatever the user clicked (ground, minimap,
     *  even just dispatched via menuAction), the engine sets the player's
     *  localDestination to the target tile. We watch it per tick and emit a
     *  world_click "walk_dest" event the moment it changes. This catches walks
     *  even when MenuOptionClicked never fires (e.g. left-click minimap in
     *  resizable mode) or when param0/param1 aren't usable scene coords. */
    private void emitWalkDestIfChanged(RecordingBuffer b, int tick)
    {
        var p = client.getLocalPlayer();
        if (p == null) return;
        LocalPoint dest = client.getLocalDestinationLocation();
        long key;
        int wx, wy, plane;
        if (dest == null)
        {
            key = -1L; wx = 0; wy = 0; plane = client.getPlane();
        }
        else
        {
            WorldPoint wp = WorldPoint.fromLocal(client, dest);
            wx = wp.getX(); wy = wp.getY(); plane = wp.getPlane();
            key = ((long) wx) * 100_000L + (long) wy;
        }
        if (key == lastWalkDestKey) return;
        lastWalkDestKey = key;
        if (key == -1L) return;     // destination cleared (player arrived) — no event needed
        final int fwx = wx, fwy = wy, fplane = plane;
        b.enqueue((seq, tMs) -> new Events.WorldClick(seq, tMs, tick,
            "walk_dest", 0, "", fwx, fwy, fplane, 0, 0));
    }

    @Subscribe
    public void onMenuOpened(MenuOpened e)
    {
        RecordingBuffer b = buffer;
        if (b == null) return;
        int x = client.getMouseCanvasPosition().getX();
        int y = client.getMouseCanvasPosition().getY();
        List<Events.MenuOpen.MenuRow> rows = new ArrayList<>();
        for (var entry : e.getMenuEntries())
        {
            rows.add(new Events.MenuOpen.MenuRow(
                entry.getOption(), entry.getTarget(), entry.getIdentifier(),
                String.valueOf(entry.getType())
            ));
        }
        int tick = client.getTickCount();
        b.enqueue((seq, tMs) -> new Events.MenuOpen(seq, tMs, tick, x, y, rows));
    }

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked e)
    {
        RecordingBuffer b = buffer;
        if (b == null) return;
        int tick = client.getTickCount();
        var entry = e.getMenuEntry();
        int x = client.getMouseCanvasPosition().getX();
        int y = client.getMouseCanvasPosition().getY();
        b.enqueue((seq, tMs) -> new Events.MenuClick(seq, tMs, tick,
            0, x, y, 0L,
            entry.getOption() == null ? "" : entry.getOption(),
            entry.getTarget() == null ? "" : entry.getTarget(),
            entry.getIdentifier(),
            String.valueOf(entry.getType()),
            entry.getParam0(), entry.getParam1(), entry.getType().getId()));
        // Then resolve into widget_click or world_click; resolver may also enqueue nearby
        clickResolver.resolveAndEnqueue(b, client, e, tick);
        nearbyResolver.maybeEnqueue(b, client, e, tick);
    }

    @Subscribe
    public void onChatMessage(ChatMessage e)
    {
        RecordingBuffer b = buffer;
        if (b == null) return;
        if (!chatFilter.keep(e.getType())) return;
        int tick = client.getTickCount();
        String typeStr = e.getType().name();
        String sender = e.getName() == null ? "system" : e.getName();
        String msg = e.getMessage() == null ? "" : e.getMessage();
        b.enqueue((seq, tMs) -> new Events.Chat(seq, tMs, tick, typeStr, sender, msg));
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged e)
    {
        RecordingBuffer b = buffer;
        if (b == null) return;
        int containerId = e.getContainerId();
        boolean isInv = containerId == net.runelite.api.gameval.InventoryID.INV;
        boolean isEquip = containerId == net.runelite.api.gameval.InventoryID.WORN;
        boolean isBank = containerId == net.runelite.api.gameval.InventoryID.BANK;
        if (!isInv && !isEquip && !isBank) return;
        ItemContainer c = e.getItemContainer();
        if (c == null) return;
        Integer key = containerId;
        int[] beforeIds = lastInvIds.get(key);
        int[] beforeQtys = lastInvQtys.get(key);
        var items = c.getItems();
        int[] curIds = new int[items.length];
        int[] curQtys = new int[items.length];
        List<Events.InvChange.SlotDelta> deltas = new ArrayList<>();
        for (int i = 0; i < items.length; i++)
        {
            curIds[i] = items[i].getId();
            curQtys[i] = items[i].getQuantity();
            int bId = beforeIds == null || i >= beforeIds.length ? -1 : beforeIds[i];
            int bQ = beforeQtys == null || i >= beforeQtys.length ? 0 : beforeQtys[i];
            if (bId != curIds[i] || bQ != curQtys[i])
            {
                deltas.add(new Events.InvChange.SlotDelta(i, bId, bQ, curIds[i], curQtys[i]));
            }
        }
        lastInvIds.put(key, curIds);
        lastInvQtys.put(key, curQtys);
        if (deltas.isEmpty()) return;
        int tick = client.getTickCount();
        if (isInv)
        {
            b.enqueue((seq, tMs) -> new Events.InvChange(seq, tMs, tick, deltas));
        }
        else if (isEquip)
        {
            b.enqueue((seq, tMs) -> new Events.EquipChange(seq, tMs, tick, deltas));
        }
        else
        {
            b.enqueue((seq, tMs) -> new Events.BankChange(seq, tMs, tick, deltas));
        }
    }

    @Subscribe
    public void onStatChanged(StatChanged e)
    {
        RecordingBuffer b = buffer;
        if (b == null) return;
        int tick = client.getTickCount();
        Skill skillEnum = e.getSkill();
        String skill = skillEnum.name();
        int after = e.getXp();
        Integer prior = lastXp.put(skillEnum, after);
        int before = prior == null ? after : prior;   // first observation: no delta
        if (before == after) return;                  // no change, drop noisy event
        b.enqueue((seq, tMs) -> new Events.XpChange(seq, tMs, tick, skill, before, after));
    }
}
