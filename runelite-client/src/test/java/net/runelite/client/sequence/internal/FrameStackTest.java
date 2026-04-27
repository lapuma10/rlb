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

import net.runelite.client.sequence.*;
import net.runelite.client.sequence.blackboard.Blackboard;
import org.junit.Test;
import static org.junit.Assert.*;

public class FrameStackTest {
    @Test
    public void pushPop_isLifo() {
        FrameStack s = new FrameStack();
        StepFrame a = new StepFrame(stub("A"), 0);
        StepFrame b = new StepFrame(stub("B"), 1);
        s.push(a);
        s.push(b);
        assertSame(b, s.top());
        s.pop();
        assertSame(a, s.top());
    }

    @Test
    public void empty_returnsTrueWhenNoFrames() {
        FrameStack s = new FrameStack();
        assertTrue(s.isEmpty());
        s.push(new StepFrame(stub("A"), 0));
        assertFalse(s.isEmpty());
    }

    @Test
    public void leaves_returnsTopFrame_forLinearStack() {
        FrameStack s = new FrameStack();
        StepFrame a = new StepFrame(stub("A"), 0);
        StepFrame b = new StepFrame(stub("B"), 1);
        s.push(a); s.push(b);
        assertEquals(java.util.List.of(b), s.leaves());
    }

    private static Step stub(String n) {
        return new Step() {
            public String name() { return n; }
            public int priority() { return 0; }
            public int timeoutTicks() { return 100; }
            public PreemptionPolicy preemptionPolicy() { return PreemptionPolicy.ALWAYS; }
            public boolean isSafeToPause(WorldSnapshot s, Blackboard b) { return true; }
            public boolean canStart(WorldSnapshot s, Blackboard b) { return true; }
            public void onStart(StepContext c) {}
            public void onEvent(Object e, StepContext c) {}
            public void tick(StepContext c) {}
            public Completion check(WorldSnapshot s, Blackboard b) { return Completion.RUNNING; }
            public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard b) { return new Recovery.Abort("none"); }
        };
    }
}
