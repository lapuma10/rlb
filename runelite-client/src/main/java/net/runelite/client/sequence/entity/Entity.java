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
import javax.annotation.Nullable;
import net.runelite.api.coords.WorldPoint;

/**
 * A clickable thing in the loaded scene — a ground item, an NPC, an object —
 * exposed to scripts as a stable handle even though the underlying engine
 * record may be a transient {@link net.runelite.api.NPC} or
 * {@link net.runelite.api.TileItem} that re-spawns each tick.
 *
 * <p><b>Identity.</b> An {@code Entity} stores the addressing fields needed to
 * re-find the live record on every call (e.g. NPC index for an NPC, tile +
 * item id for a ground drop). Each query goes back to the engine — the entity
 * holds no cached state. That makes entities cheap to keep around between
 * ticks without going stale.
 *
 * <p><b>Click resolution.</b> All click pixel selection routes through
 * {@code TileObject#getClickbox()} / {@link net.runelite.api.Actor#getConvexHull()}
 * (i.e. the engine's own hit-test shape), with documented fallbacks. Scripts
 * never have to compute their own hit area.
 *
 * <p><b>Threading.</b> All accessors and {@link #interact} must be called from
 * a worker thread; they internally hop onto the client thread for engine
 * reads. Returning {@link Shape} or {@link WorldPoint} is safe to read off
 * thread. {@link #interact} dispatches asynchronously through the humanized
 * input dispatcher; observe {@code dispatcher.isBusy()} to wait for the
 * click chain to finish.
 *
 * @see GroundItemEntity
 * @see NpcEntity
 * @see Entities
 */
public interface Entity
{
    /**
     * @return true if the underlying engine record is still in the loaded
     *         scene (tile still has the item, NPC index still resolves to a
     *         live actor, etc.). Cheap — does not allocate.
     */
    boolean exists();

    /**
     * @return the engine's own click shape for this entity, projected to
     *         canvas pixels. Used as the sample area for humanized clicks.
     *         May be {@code null} if the entity vanished mid-call or the
     *         renderable hasn't built yet (one-tick fresh spawns).
     */
    @Nullable
    Shape getClickbox();

    /**
     * @return the entity's world tile, or {@code null} if vanished. For
     *         multi-tile NPCs this is the south-west tile of their world
     *         area.
     */
    @Nullable
    WorldPoint getWorldLocation();

    /**
     * Schedule a humanized click that issues menu option {@code verb}
     * against this entity. Returns immediately; the click chain (camera
     * pan, cursor path, hover verify, press, optional right-click + menu
     * row pick) runs on a worker thread inside the dispatcher.
     *
     * <p>The dispatcher is single-flight — calling {@code interact} while
     * the previous click chain is still running silently drops the new
     * request. Either gate on {@code dispatcher.isBusy() == false} or use
     * a state machine that only dispatches one action per tick.
     */
    void interact(String verb);
}
