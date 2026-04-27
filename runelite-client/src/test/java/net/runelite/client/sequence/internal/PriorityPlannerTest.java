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
import net.runelite.client.sequence.blackboard.ScopedBlackboard;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class PriorityPlannerTest {
    @Test
    public void picksHighestPriorityEligibleStep() {
        Step a = step("A", 10, true);
        Step b = step("B", 50, true);
        Step c = step("C", 100, false); // not eligible
        PriorityPlanner p = new PriorityPlanner();
        Step chosen = p.select(snapshot(), new ScopedBlackboard(), List.of(a, b, c));
        assertSame(b, chosen);
    }

    @Test
    public void returnsNullWhenNoneEligible() {
        Step a = step("A", 10, false);
        PriorityPlanner p = new PriorityPlanner();
        assertNull(p.select(snapshot(), new ScopedBlackboard(), List.of(a)));
    }

    private static WorldSnapshot snapshot() {
        return new WorldSnapshot() {
            public int tick() { return 0; }
            public PlayerView player() { return null; }
        };
    }

    private static Step step(String n, int prio, boolean eligible) {
        return new Step() {
            public String name() { return n; }
            public int priority() { return prio; }
            public int timeoutTicks() { return 100; }
            public PreemptionPolicy preemptionPolicy() { return PreemptionPolicy.ALWAYS; }
            public boolean isSafeToPause(WorldSnapshot s, Blackboard b) { return true; }
            public boolean canStart(WorldSnapshot s, Blackboard b) { return eligible; }
            public void onStart(StepContext c) {}
            public void onEvent(Object e, StepContext c) {}
            public void tick(StepContext c) {}
            public Completion check(WorldSnapshot s, Blackboard b) { return Completion.RUNNING; }
            public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard b) { return new Recovery.Abort(""); }
        };
    }
}
