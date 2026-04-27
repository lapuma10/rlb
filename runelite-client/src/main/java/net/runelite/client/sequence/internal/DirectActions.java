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

import net.runelite.api.coords.WorldPoint;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public final class DirectActions implements Actions {

    /** Per-engine queue. One Sink shared by all DirectActions instances. */
    public static final class Sink {
        private final List<ActionRequest> queue = new ArrayList<>();
        private final AtomicLong counter = new AtomicLong();

        synchronized void enqueue(ActionRequest r) { queue.add(r); }

        public synchronized List<ActionRequest> drain() {
            List<ActionRequest> out = new ArrayList<>(queue);
            queue.clear();
            return out;
        }

        synchronized void cancelByOwner(int ownerFrameId) {
            queue.removeIf(r -> r.getOwnerFrameId() == ownerFrameId);
        }

        long nextInsertionOrder() { return counter.getAndIncrement(); }
    }

    private final Sink sink;
    private final int ownerFrameId;
    private final int frameDepth;
    private final int priority;

    public DirectActions(Sink sink, int ownerFrameId, int frameDepth, int priority) {
        this.sink = sink;
        this.ownerFrameId = ownerFrameId;
        this.frameDepth = frameDepth;
        this.priority = priority;
    }

    private ActionRequest base(ActionRequest.Kind k, ActionRequest.Channel ch) {
        return ActionRequest.builder()
            .kind(k).channel(ch)
            .ownerFrameId(ownerFrameId).frameDepth(frameDepth).priority(priority)
            .insertionOrder(sink.nextInsertionOrder())
            .build();
    }

    @Override
    public void walkTo(WorldPoint tile) {
        sink.enqueue(base(ActionRequest.Kind.WALK, ActionRequest.Channel.CLIENT).toBuilder().tile(tile).build());
    }

    @Override
    public void clickTile(WorldPoint tile) {
        sink.enqueue(base(ActionRequest.Kind.CLICK_TILE, ActionRequest.Channel.CLIENT).toBuilder().tile(tile).build());
    }

    @Override
    public void clickNpc(int npcIndex, String option) {
        sink.enqueue(base(ActionRequest.Kind.CLICK_NPC, ActionRequest.Channel.CLIENT).toBuilder()
            .npcIndex(npcIndex).option(option).build());
    }

    @Override
    public void clickGameObject(int objectId, WorldPoint at, String option) {
        sink.enqueue(base(ActionRequest.Kind.CLICK_GAME_OBJECT, ActionRequest.Channel.CLIENT).toBuilder()
            .objectId(objectId).tile(at).option(option).build());
    }

    @Override
    public void clickGroundItem(int itemId, WorldPoint at, String option) {
        sink.enqueue(base(ActionRequest.Kind.CLICK_GROUND_ITEM, ActionRequest.Channel.CLIENT).toBuilder()
            .itemId(itemId).tile(at).option(option).build());
    }

    @Override
    public void clickWidget(int widgetId, int childIndex, String option) {
        sink.enqueue(base(ActionRequest.Kind.CLICK_WIDGET, ActionRequest.Channel.CLIENT).toBuilder()
            .widgetId(widgetId).childIndex(childIndex).option(option).build());
    }

    @Override
    public void clickInventoryItem(int slot, String option) {
        sink.enqueue(base(ActionRequest.Kind.CLICK_INV_ITEM, ActionRequest.Channel.CLIENT).toBuilder()
            .slot(slot).option(option).build());
    }

    @Override
    public void sendKey(int keyCode) {
        sink.enqueue(base(ActionRequest.Kind.KEY, ActionRequest.Channel.KEYBOARD).toBuilder().keyCode(keyCode).build());
    }

    @Override
    public void cancelPending() {
        sink.cancelByOwner(ownerFrameId);
    }
}
