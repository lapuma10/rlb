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
import net.runelite.client.sequence.Completion;
import net.runelite.client.sequence.Failure;
import net.runelite.client.sequence.PlayerView;
import net.runelite.client.sequence.PreemptionPolicy;
import net.runelite.client.sequence.Recovery;
import net.runelite.client.sequence.SequenceState;
import net.runelite.client.sequence.Step;
import net.runelite.client.sequence.StepContext;
import net.runelite.client.sequence.WorldSnapshot;
import net.runelite.client.sequence.blackboard.Blackboard;
import net.runelite.client.sequence.blackboard.ScopedBlackboard;
import net.runelite.client.sequence.dispatch.MockInputDispatcher;
import net.runelite.client.sequence.telemetry.RingBufferTelemetry;
import net.runelite.client.sequence.telemetry.TelemetryRecord;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class StateDrivenEngineTest {

    @Test
    public void runsSingleStep_emitsExpectedTelemetry() {
        // Snapshot list — initially not at target, then arrives on tick 2
        List<WorldSnapshot> snaps = List.of(
            snap(0, new WorldPoint(0, 0, 0)),
            snap(1, new WorldPoint(0, 0, 0)),
            snap(2, new WorldPoint(5, 5, 0))
        );

        FixtureObserver obs = new FixtureObserver(snaps);
        MockInputDispatcher disp = new MockInputDispatcher();
        RingBufferTelemetry tel = new RingBufferTelemetry(64);
        ScopedBlackboard bb = new ScopedBlackboard();

        StateDrivenEngine engine = new StateDrivenEngine(obs, new PriorityPlanner(), disp, tel, bb);
        engine.start(new TestArrivedStep(new WorldPoint(5, 5, 0)));

        engine.advanceTick();   // pick + onStart, no completion yet
        engine.advanceTick();   // still RUNNING
        engine.advanceTick();   // arrives — SUCCEEDED

        List<TelemetryRecord.Event> events = tel.tail(64).stream().map(TelemetryRecord::event).toList();
        assertTrue(events.contains(TelemetryRecord.Event.SELECTED));
        assertTrue(events.contains(TelemetryRecord.Event.STARTED));
        assertTrue(events.contains(TelemetryRecord.Event.SUCCEEDED));
        assertEquals(SequenceState.IDLE, engine.state()); // popped, nothing else queued
    }

    private static WorldSnapshot snap(int tick, WorldPoint at) {
        return new WorldSnapshot() {
            public int tick() { return tick; }
            public PlayerView player() { return new PlayerView() {
                public WorldPoint worldLocation() { return at; }
                public int animation() { return -1; }
                public boolean isIdle() { return true; }
                public int health() { return 99; }
                public int maxHealth() { return 99; }
            }; }
        };
    }

    private static class TestArrivedStep implements Step {
        private final WorldPoint target;
        TestArrivedStep(WorldPoint t) { this.target = t; }
        public String name() { return "TestArrived"; }
        public int priority() { return 50; }
        public int timeoutTicks() { return 50; }
        public PreemptionPolicy preemptionPolicy() { return PreemptionPolicy.ALWAYS; }
        public boolean isSafeToPause(WorldSnapshot s, Blackboard b) { return true; }
        public boolean canStart(WorldSnapshot s, Blackboard b) { return true; }
        public void onStart(StepContext c) {}
        public void onEvent(Object e, StepContext c) {}
        public void tick(StepContext c) {}
        public Completion check(WorldSnapshot s, Blackboard b) {
            if (s.player().worldLocation().equals(target)) return new Completion.Succeeded("arrived");
            return Completion.RUNNING;
        }
        public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard b) { return new Recovery.Abort(""); }
    }
}
