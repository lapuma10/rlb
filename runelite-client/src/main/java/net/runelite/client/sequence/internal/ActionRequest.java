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
package net.runelite.client.sequence.internal;

import java.awt.Rectangle;
import lombok.Value;
import net.runelite.api.coords.WorldPoint;
import javax.annotation.Nullable;

@Value
@lombok.Builder(toBuilder = true)
public class ActionRequest {
    public enum Kind { WALK, CLICK_TILE, CLICK_NPC, CLICK_GAME_OBJECT, CLICK_GROUND_ITEM, CLICK_WIDGET, CLICK_INV_ITEM, CLICK_BOUNDS, KEY }
    public enum Channel { CLIENT, MOUSE, KEYBOARD }

    Kind kind;
    Channel channel;
    int ownerFrameId;     // which frame requested it (for arbitration)
    int frameDepth;       // for arbitration — deeper wins ties
    int priority;         // copied from owning Step at queue time
    long insertionOrder;  // monotonic — earlier wins ties

    // payload — only the fields relevant to `kind` are non-null
    @Nullable WorldPoint tile;
    int npcIndex;
    int objectId;
    int itemId;
    int widgetId;
    int childIndex;
    int slot;
    int keyCode;
    @Nullable String option;
    /** Menu option to match for CLICK_GAME_OBJECT / CLICK_GROUND_ITEM
     *  (e.g. "Open", "Climb-up", "Use"). Case-insensitive; whitespace and
     *  hyphen tolerant. Null means "first available action / left-click default". */
    @Nullable String verb;
    /** Pre-resolved canvas bounds for {@link Kind#CLICK_BOUNDS}. Caller computed
     *  the rectangle on the client thread (e.g. from a specific child Widget's
     *  {@code getBounds()}); the dispatcher samples a humanized pixel inside
     *  with edge margins. Use this when a packed widget id can't address the
     *  click target uniquely — e.g. dynamic children of a parent that all
     *  return the parent's packed id from {@code Widget.getId()}. */
    @Nullable Rectangle bounds;
}
