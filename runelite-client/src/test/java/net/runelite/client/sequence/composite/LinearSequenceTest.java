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
package net.runelite.client.sequence.composite;

import net.runelite.client.sequence.*;
import net.runelite.client.sequence.blackboard.Blackboard;
import net.runelite.client.sequence.blackboard.ScopedBlackboard;
import net.runelite.client.sequence.dispatch.MockInputDispatcher;
import net.runelite.client.sequence.internal.*;
import net.runelite.client.sequence.telemetry.RingBufferTelemetry;
import net.runelite.client.sequence.telemetry.TelemetryRecord;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class LinearSequenceTest {

    @Test
    public void runsTwoChildrenInOrder() {
        WorldSnapshot fixed = new WorldSnapshot() {
            public int tick() { return 0; }
            public PlayerView player() { return null; }
        };
        FixtureObserver obs = new FixtureObserver(List.of(fixed, fixed, fixed, fixed, fixed));
        RingBufferTelemetry tel = new RingBufferTelemetry(64);

        ImmediateStep a = new ImmediateStep("A");
        ImmediateStep b = new ImmediateStep("B");

        StateDrivenEngine engine = new StateDrivenEngine(
            obs, new PriorityPlanner(), new MockInputDispatcher(), tel, new ScopedBlackboard());

        engine.start(new LinearSequence("seq").then(a).then(b));
        engine.advanceTick();   // A's check returns Succeeded
        engine.advanceTick();   // B's check returns Succeeded
        engine.advanceTick();   // sequence done, engine idle

        List<String> seen = tel.tail(64).stream()
            .filter(r -> r.event() == TelemetryRecord.Event.SUCCEEDED)
            .map(TelemetryRecord::stepName).toList();
        assertEquals(List.of("A", "B", "seq"), seen);
    }

    private static class ImmediateStep implements Step {
        private final String n;
        ImmediateStep(String n) { this.n = n; }
        public String name() { return n; }
        public int priority() { return 50; }
        public int timeoutTicks() { return 50; }
        public PreemptionPolicy preemptionPolicy() { return PreemptionPolicy.ALWAYS; }
        public boolean isSafeToPause(WorldSnapshot s, Blackboard b) { return true; }
        public boolean canStart(WorldSnapshot s, Blackboard b) { return true; }
        public void onStart(StepContext c) {}
        public void onEvent(Object e, StepContext c) {}
        public void tick(StepContext c) {}
        public Completion check(WorldSnapshot s, Blackboard b) { return new Completion.Succeeded("done"); }
        public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard b) { return new Recovery.Abort(""); }
    }
}
