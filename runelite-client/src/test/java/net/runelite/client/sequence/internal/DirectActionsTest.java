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
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class DirectActionsTest {

    @Test
    public void walkTo_queuesWalkRequest() {
        DirectActions.Sink sink = new DirectActions.Sink();
        DirectActions a = new DirectActions(sink, /* frameId */ 7, /* frameDepth */ 2, /* priority */ 50);
        a.walkTo(new WorldPoint(3208, 3219, 0));
        List<ActionRequest> queued = sink.drain();
        assertEquals(1, queued.size());
        ActionRequest r = queued.get(0);
        assertEquals(ActionRequest.Kind.WALK, r.getKind());
        assertEquals(7, r.getOwnerFrameId());
        assertEquals(50, r.getPriority());
        assertEquals(new WorldPoint(3208, 3219, 0), r.getTile());
    }

    @Test
    public void cancelPending_clearsThisOwnersRequests() {
        DirectActions.Sink sink = new DirectActions.Sink();
        DirectActions a = new DirectActions(sink, 7, 2, 50);
        DirectActions b = new DirectActions(sink, 8, 2, 50);
        a.walkTo(new WorldPoint(1, 2, 0));
        b.walkTo(new WorldPoint(3, 4, 0));
        a.cancelPending();
        List<ActionRequest> remaining = sink.drain();
        assertEquals(1, remaining.size());
        assertEquals(8, remaining.get(0).getOwnerFrameId());
    }

    @Test
    public void insertionOrder_isMonotonic() {
        DirectActions.Sink sink = new DirectActions.Sink();
        DirectActions a = new DirectActions(sink, 7, 2, 50);
        a.walkTo(new WorldPoint(1, 2, 0));
        a.walkTo(new WorldPoint(3, 4, 0));
        List<ActionRequest> queued = sink.drain();
        assertTrue(queued.get(0).getInsertionOrder() < queued.get(1).getInsertionOrder());
    }
}
