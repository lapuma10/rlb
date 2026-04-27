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
package net.runelite.client.sequence.entity;

import java.awt.Shape;
import java.util.Objects;
import javax.annotation.Nullable;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import net.runelite.client.sequence.internal.ActionRequest;

/**
 * An NPC in the loaded scene, addressed by its engine NPC index.
 *
 * <p>The NPC index is stable for the lifetime of an NPC in the scene
 * (re-spawns get a new index). Storing the index instead of the {@link NPC}
 * reference itself keeps the entity safe to keep across ticks — every
 * accessor goes back to the live world view.
 *
 * <p><b>Click area.</b> {@link #getClickbox()} returns
 * {@link net.runelite.api.Actor#getConvexHull()} — the engine's projected
 * model hull. Clicks resolve through that, with a tile-poly fallback only
 * when the hull is unavailable mid-render. The dispatcher's NPC click flow
 * already does smart left-click vs right-click selection: if the engine's
 * hover-default is the requested verb on this NPC, it presses immediately;
 * otherwise it opens the right-click menu and picks the matching row.
 */
public final class NpcEntity implements Entity
{
    private final Client client;
    private final HumanizedInputDispatcher dispatcher;
    private final int index;

    public NpcEntity(Client client, HumanizedInputDispatcher dispatcher, int index)
    {
        this.client = Objects.requireNonNull(client, "client");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.index = index;
    }

    /** Engine NPC index — stable for this NPC's lifetime in the scene. */
    public int index() { return index; }

    /** @return the live {@link NPC}, or {@code null} if it despawned. Must be
     *  called on the client thread.
     */
    @Nullable
    public NPC npc()
    {
        try
        {
            for (NPC n : client.getTopLevelWorldView().npcs())
            {
                if (n != null && n.getIndex() == index) return n;
            }
        }
        catch (Throwable th) { /* fall through */ }
        return null;
    }

    /** Composition-name of the NPC (markup-stripped via composition where
     *  available), or {@code null} if despawned. Convenience for log/UI. */
    @Nullable
    public String name()
    {
        NPC n = npc();
        if (n == null) return null;
        NPCComposition c = n.getComposition();
        return c == null ? n.getName() : c.getName();
    }

    @Override
    public boolean exists() { return npc() != null; }

    @Override
    @Nullable
    public Shape getClickbox()
    {
        NPC n = npc();
        if (n == null) return null;
        try { return n.getConvexHull(); }
        catch (Throwable th) { return null; }
    }

    @Override
    @Nullable
    public WorldPoint getWorldLocation()
    {
        NPC n = npc();
        return n == null ? null : n.getWorldLocation();
    }

    /** Convenience for combat NPCs. Equivalent to {@code interact("Attack")}. */
    public void attack() { interact("Attack"); }

    @Override
    public void interact(String verb)
    {
        ActionRequest req = ActionRequest.builder()
            .kind(ActionRequest.Kind.CLICK_NPC)
            .channel(ActionRequest.Channel.MOUSE)
            .npcIndex(index)
            .verb(verb == null || verb.isBlank() ? "Attack" : verb)
            .build();
        dispatcher.dispatch(req);
    }

    @Override
    public String toString()
    {
        return "NpcEntity{index=" + index + "}";
    }
}
