/*
 * Copyright (c) 2025, Mantas <https://github.com/mantasarm>
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

import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.plugins.recorder.buffer.RecordingBuffer;
import net.runelite.client.plugins.recorder.events.Events;
import java.util.ArrayList;
import java.util.List;

/** On a world-target click, scan the loaded scene around the player and emit
 *  a nearby event listing every NPC / player within RADIUS tiles.
 *  This is the analyst's window into "what was the user choosing between?". */
public final class NearbyResolver
{
    private static final int RADIUS = 12;

    public void maybeEnqueue(RecordingBuffer buf, Client client, MenuOptionClicked e, int tick)
    {
        MenuAction t = e.getMenuEntry().getType();
        boolean worldTarget = t == MenuAction.NPC_FIRST_OPTION || t == MenuAction.NPC_SECOND_OPTION
            || t == MenuAction.NPC_THIRD_OPTION || t == MenuAction.NPC_FOURTH_OPTION
            || t == MenuAction.NPC_FIFTH_OPTION
            || t == MenuAction.GAME_OBJECT_FIRST_OPTION || t == MenuAction.GAME_OBJECT_SECOND_OPTION
            || t == MenuAction.GAME_OBJECT_THIRD_OPTION || t == MenuAction.GAME_OBJECT_FOURTH_OPTION
            || t == MenuAction.GAME_OBJECT_FIFTH_OPTION
            || t == MenuAction.WALK
            || t == MenuAction.SET_HEADING;
        if (!worldTarget) return;
        Player local = client.getLocalPlayer();
        if (local == null || local.getWorldLocation() == null) return;
        WorldPoint here = local.getWorldLocation();
        List<Events.Nearby.NearbyEntity> out = new ArrayList<>();
        for (NPC npc : client.getNpcs())
        {
            if (npc == null || npc.getWorldLocation() == null) continue;
            if (npc.getWorldLocation().distanceTo(here) <= RADIUS)
            {
                out.add(new Events.Nearby.NearbyEntity("npc", npc.getId(),
                    npc.getName() == null ? "" : npc.getName(),
                    npc.getWorldLocation().getX(), npc.getWorldLocation().getY(),
                    npc.getWorldLocation().getPlane()));
            }
        }
        for (Player p : client.getPlayers())
        {
            if (p == null || p == local || p.getWorldLocation() == null) continue;
            if (p.getWorldLocation().distanceTo(here) <= RADIUS)
            {
                out.add(new Events.Nearby.NearbyEntity("player", p.getId(),
                    p.getName() == null ? "" : p.getName(),
                    p.getWorldLocation().getX(), p.getWorldLocation().getY(),
                    p.getWorldLocation().getPlane()));
            }
        }
        if (out.isEmpty()) return;
        buf.enqueue((seq, tMs) -> new Events.Nearby(seq, tMs, tick, out));
    }
}
