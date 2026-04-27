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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nullable;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;

/**
 * Factory + finder for {@link Entity} instances. Scripts get one of these
 * via Guice ({@code @Inject Entities entities}) and use it as the entry
 * point for everything entity-related.
 *
 * <p>All finder methods must be called on the client thread (they read the
 * live world view). The returned entities are safe to keep around — each
 * accessor on them goes back to the engine.
 *
 * <p><b>Threading recap.</b>
 * <ul>
 *   <li>{@code Entities.*} finders → client thread.</li>
 *   <li>{@code entity.exists() / getClickbox() / getWorldLocation() /
 *       npc() / tileItem()} → client thread.</li>
 *   <li>{@code entity.interact(verb)} → any thread; dispatches async.</li>
 * </ul>
 */
public final class Entities
{
    private final Client client;
    private final HumanizedInputDispatcher dispatcher;

    @Inject
    public Entities(Client client, HumanizedInputDispatcher dispatcher)
    {
        this.client = Objects.requireNonNull(client, "client");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    }

    // ---------- Ground items ----------

    /** All ground items currently on {@code tile}. Each returned entity is
     *  bound to one (tile, itemId) pair; if the same item id occurs twice
     *  (different stacks) only one entity is returned for it — the engine
     *  collapses them when looking up by id. */
    public List<GroundItemEntity> groundItemsOn(WorldPoint tile)
    {
        if (tile == null) return List.of();
        Tile sceneTile = sceneTileAt(tile);
        if (sceneTile == null) return List.of();
        List<TileItem> items = sceneTile.getGroundItems();
        if (items == null || items.isEmpty()) return List.of();
        java.util.LinkedHashSet<Integer> seenIds = new java.util.LinkedHashSet<>();
        for (TileItem it : items)
        {
            if (it != null) seenIds.add(it.getId());
        }
        List<GroundItemEntity> out = new ArrayList<>(seenIds.size());
        for (int id : seenIds)
        {
            out.add(new GroundItemEntity(client, dispatcher, tile, id));
        }
        return out;
    }

    /** Specific ground item on {@code tile} by item id, or empty if no
     *  drop with that id is currently there. */
    public Optional<GroundItemEntity> groundItem(int itemId, WorldPoint tile)
    {
        if (tile == null) return Optional.empty();
        Tile sceneTile = sceneTileAt(tile);
        if (sceneTile == null) return Optional.empty();
        List<TileItem> items = sceneTile.getGroundItems();
        if (items == null) return Optional.empty();
        for (TileItem it : items)
        {
            if (it != null && it.getId() == itemId)
            {
                return Optional.of(new GroundItemEntity(client, dispatcher, tile, itemId));
            }
        }
        return Optional.empty();
    }

    // ---------- NPCs ----------

    /** All NPCs in the loaded scene. Order matches the engine iteration. */
    public List<NpcEntity> npcs()
    {
        List<NpcEntity> out = new ArrayList<>();
        try
        {
            for (NPC n : client.getTopLevelWorldView().npcs())
            {
                if (n != null) out.add(new NpcEntity(client, dispatcher, n.getIndex()));
            }
        }
        catch (Throwable th) { /* return what we have */ }
        return out;
    }

    /** All NPCs whose composition name matches {@code name}, case-
     *  insensitive and markup-stripped (so {@code "Chicken"} matches
     *  {@code "<col=...>Chicken</col>"}). */
    public List<NpcEntity> npcsByName(String name)
    {
        if (name == null) return List.of();
        String want = name.trim();
        if (want.isEmpty()) return List.of();
        List<NpcEntity> out = new ArrayList<>();
        try
        {
            for (NPC n : client.getTopLevelWorldView().npcs())
            {
                if (n == null) continue;
                NPCComposition c = n.getComposition();
                String nm = c == null ? n.getName() : c.getName();
                if (nm == null) continue;
                if (want.equalsIgnoreCase(stripMarkup(nm)))
                {
                    out.add(new NpcEntity(client, dispatcher, n.getIndex()));
                }
            }
        }
        catch (Throwable th) { /* return what we have */ }
        return out;
    }

    /** Nearest NPC by composition name to the local player. Returns empty
     *  if no match is in the scene or the player isn't loaded. */
    public Optional<NpcEntity> nearestNpc(String name)
    {
        Player self = client.getLocalPlayer();
        if (self == null) return Optional.empty();
        WorldPoint playerLoc = self.getWorldLocation();
        if (playerLoc == null) return Optional.empty();
        return npcsByName(name).stream()
            .filter(e -> e.getWorldLocation() != null)
            .min(Comparator.comparingInt(e -> e.getWorldLocation().distanceTo(playerLoc)));
    }

    /** Wrap an existing {@link NPC} as an entity. Useful when a plugin
     *  already has the live reference (e.g. from an event subscription)
     *  and wants the entity API on top of it. */
    public NpcEntity wrap(NPC npc)
    {
        Objects.requireNonNull(npc, "npc");
        return new NpcEntity(client, dispatcher, npc.getIndex());
    }

    // ---------- helpers ----------

    @Nullable
    private Tile sceneTileAt(WorldPoint world)
    {
        try
        {
            LocalPoint lp = LocalPoint.fromWorld(client.getTopLevelWorldView(), world);
            if (lp == null) return null;
            return client.getScene().getTiles()
                [world.getPlane()][lp.getSceneX()][lp.getSceneY()];
        }
        catch (Throwable th) { return null; }
    }

    private static String stripMarkup(String s)
    {
        return s.replaceAll("<[^>]+>", "").trim();
    }
}
