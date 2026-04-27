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
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;
import net.runelite.api.Client;
import net.runelite.api.ItemLayer;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import net.runelite.client.sequence.internal.ActionRequest;

/**
 * A ground-item drop on a specific tile, addressed by tile + item id.
 *
 * <p>The same tile can hold multiple stacked items (chickens drop feather +
 * raw chicken + bones); a {@code GroundItemEntity} represents one of them.
 * The pile's clickbox is shared across all items on the tile, so any of
 * them resolves to the same humanized cursor area — the
 * {@link #interact(String)} verb decides which menu entry actually gets
 * picked.
 *
 * <p><b>Click area.</b> {@link #getClickbox()} returns
 * {@code Tile.getItemLayer().getClickbox()} — the engine's own hit-test
 * shape for the whole pile. This is what makes right-click reliable: any
 * pixel inside the shape is guaranteed to surface a menu containing every
 * item on the tile.
 */
public final class GroundItemEntity implements Entity
{
    private final Client client;
    private final HumanizedInputDispatcher dispatcher;
    private final WorldPoint tile;
    private final int itemId;

    public GroundItemEntity(Client client, HumanizedInputDispatcher dispatcher,
                            WorldPoint tile, int itemId)
    {
        this.client = Objects.requireNonNull(client, "client");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.tile = Objects.requireNonNull(tile, "tile");
        this.itemId = itemId;
    }

    /** The world tile this drop sits on. */
    public WorldPoint tile() { return tile; }

    /** The item id of this drop (engine ItemID values). */
    public int itemId() { return itemId; }

    /** @return the {@link TileItem} record at this tile + id, or {@code null}
     *  if the drop is gone (picked up / despawned / left scene). Must be
     *  called on the client thread.
     */
    @Nullable
    public TileItem tileItem()
    {
        Tile sceneTile = sceneTile();
        if (sceneTile == null) return null;
        List<TileItem> items = sceneTile.getGroundItems();
        if (items == null) return null;
        for (TileItem it : items)
        {
            if (it != null && it.getId() == itemId) return it;
        }
        return null;
    }

    @Override
    public boolean exists()
    {
        return tileItem() != null;
    }

    @Override
    @Nullable
    public Shape getClickbox()
    {
        Tile sceneTile = sceneTile();
        if (sceneTile == null) return null;
        ItemLayer layer = sceneTile.getItemLayer();
        if (layer == null) return null;
        try { return layer.getClickbox(); }
        catch (Throwable th) { return null; }
    }

    @Override
    public WorldPoint getWorldLocation() { return tile; }

    /** Convenience for the common "Take" interaction. Equivalent to
     *  {@code interact("Take")}. */
    public void take() { interact("Take"); }

    @Override
    public void interact(String verb)
    {
        ActionRequest req = ActionRequest.builder()
            .kind(ActionRequest.Kind.CLICK_GROUND_ITEM)
            .channel(ActionRequest.Channel.MOUSE)
            .tile(tile)
            .itemId(itemId)
            .verb(verb == null || verb.isBlank() ? "Take" : verb)
            .build();
        dispatcher.dispatch(req);
    }

    @Nullable
    private Tile sceneTile()
    {
        try
        {
            LocalPoint lp = LocalPoint.fromWorld(client.getTopLevelWorldView(), tile);
            if (lp == null) return null;
            return client.getScene().getTiles()
                [tile.getPlane()][lp.getSceneX()][lp.getSceneY()];
        }
        catch (Throwable th) { return null; }
    }

    @Override
    public String toString()
    {
        return "GroundItemEntity{id=" + itemId + ", tile=" + tile + "}";
    }
}
