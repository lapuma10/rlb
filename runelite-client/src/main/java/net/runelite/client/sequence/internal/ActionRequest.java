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
import net.runelite.client.sequence.dispatch.BlockingTask;
import javax.annotation.Nullable;

@Value
@lombok.Builder(toBuilder = true)
public class ActionRequest {
    public enum Kind { WALK, CLICK_TILE, CLICK_NPC, CLICK_GAME_OBJECT, CLICK_GROUND_ITEM, CLICK_WIDGET, CLICK_INV_ITEM, CLICK_BOUNDS, TYPE_CHATBOX, PICK_GE_SEARCH_RESULT, KEY,
        /** Run a {@link BlockingTask} on the dispatcher worker thread.
         *  Bridges multi-step blocking flows (banking, GE order setup) into
         *  the engine without freezing the client thread. The dispatcher
         *  holds its busy flag for the entire task so the task is the sole
         *  in-flight chain — inside the task, use the {@code *OnWorker}
         *  helpers and other caller-thread primitives, never call
         *  {@code dispatcher.dispatch(...)} recursively. */
        RUN_TASK }
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
    /** Text payload for {@link Kind#TYPE_CHATBOX}. The dispatcher's worker
     *  thread waits for the chatbox prompt to open, dwells, types char by
     *  char, then optionally submits with Enter. NEVER call the dispatcher's
     *  blocking type helpers from the client thread — use this kind so the
     *  work runs off-thread. */
    @Nullable String typeText;
    /** {@code true} = press Enter after typing (numeric prompts: bank
     *  Withdraw-X, GE Set quantity / Set price). {@code false} = leave the
     *  search prompt open (GE search: caller follows up by clicking a
     *  result row). */
    boolean typePressEnter;
    /** Safety-net cap on chatbox-prompt detection polling. */
    long typeAwaitMs;
    /** Randomized human-dwell range applied AFTER prompt detection and
     *  BEFORE the first keystroke. */
    long typeDwellMinMs;
    long typeDwellMaxMs;
    /** Canonical item name for {@link Kind#PICK_GE_SEARCH_RESULT} — the
     *  worker thread polls the GE search result list for a row whose
     *  Widget.getName() / getText() matches and clicks it. {@link #itemId}
     *  is also set so the worker can fall back to icon-id matching. */
    @Nullable String pickName;
    /** Payload for {@link Kind#RUN_TASK}: the blocking work the dispatcher
     *  worker thread runs. Captured at the call site (typically by an
     *  engine step's {@code onStart}) so the multi-step flow runs off the
     *  client thread. Must not be {@code null} when {@code kind ==
     *  RUN_TASK}. */
    @Nullable BlockingTask task;
    /** Human-readable label for {@link Kind#RUN_TASK} — used in dispatcher
     *  logs to identify which task is running, since the {@link #task}
     *  itself is opaque. e.g. {@code "BANK_WITHDRAW_X(995,2684)"}. */
    @Nullable String taskName;
}
