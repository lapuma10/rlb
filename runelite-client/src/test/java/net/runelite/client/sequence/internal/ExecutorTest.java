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
import net.runelite.client.sequence.dispatch.MockInputDispatcher;
import org.junit.Test;
import static org.junit.Assert.*;

public class ExecutorTest {

    @Test
    public void drain_dispatchesUpToBudget() {
        DirectActions.Sink sink = new DirectActions.Sink();
        DirectActions a = new DirectActions(sink, 1, 1, 50);
        a.walkTo(new WorldPoint(1, 2, 0));
        a.walkTo(new WorldPoint(3, 4, 0));    // 2nd CLIENT-channel request

        MockInputDispatcher disp = new MockInputDispatcher();
        ActionBudget budget = new ActionBudget(); // maxClientActionsPerTick = 1
        Executor exec = new Executor(disp, budget);

        exec.drain(sink);
        assertEquals(1, disp.getRequests().size());
    }

    @Test
    public void drain_arbitratesByPriorityFrameDepthInsertionOrder() {
        DirectActions.Sink sink = new DirectActions.Sink();
        // Lower-priority but earlier-queued
        new DirectActions(sink, 1, 0, 10).walkTo(new WorldPoint(1, 1, 0));
        // Higher-priority — should win
        new DirectActions(sink, 2, 0, 90).walkTo(new WorldPoint(2, 2, 0));

        MockInputDispatcher disp = new MockInputDispatcher();
        Executor exec = new Executor(disp, new ActionBudget());

        exec.drain(sink);
        assertEquals(1, disp.getRequests().size());
        assertEquals(90, disp.getRequests().get(0).getPriority());
    }
}
