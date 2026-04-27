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
package net.runelite.client.sequence.activities;

import net.runelite.api.coords.WorldPoint;
import net.runelite.client.sequence.*;
import net.runelite.client.sequence.blackboard.Blackboard;
import net.runelite.client.sequence.blackboard.ScopedBlackboard;
import net.runelite.client.sequence.dispatch.MockInputDispatcher;
import net.runelite.client.sequence.internal.*;
import net.runelite.client.sequence.telemetry.RingBufferTelemetry;
import net.runelite.client.sequence.telemetry.TelemetryRecord;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class WalkStepTest {

    @Test
    public void arrivedWithinRadius_returnsSucceeded() {
        WorldPoint target = new WorldPoint(3208, 3219, 0);
        WalkStep step = new WalkStep(target, 1);
        WorldSnapshot at = snapshotAt(new WorldPoint(3208, 3219, 0));
        Completion c = step.check(at, new ScopedBlackboard());
        assertTrue(c instanceof Completion.Succeeded);
    }

    @Test
    public void notArrived_returnsRunning() {
        WorldPoint target = new WorldPoint(3208, 3219, 0);
        WalkStep step = new WalkStep(target, 1);
        WorldSnapshot far = snapshotAt(new WorldPoint(3000, 3000, 0));
        assertTrue(step.check(far, new ScopedBlackboard()) instanceof Completion.Running);
    }

    @Test
    public void factoryBuildsStepFromArgs() {
        WalkStepFactory f = new WalkStepFactory();
        WorldPoint target = new WorldPoint(3208, 3219, 0);
        Step s = f.build(Map.of("target", target, "arrivalRadius", 2));
        assertTrue(s instanceof WalkStep);
        assertEquals(target, ((WalkStep) s).target());
        assertEquals(2, ((WalkStep) s).arrivalRadius());
    }

    private static WorldSnapshot snapshotAt(WorldPoint p) {
        return new WorldSnapshot() {
            public int tick() { return 0; }
            public PlayerView player() {
                return new PlayerView() {
                    public WorldPoint worldLocation() { return p; }
                    public int animation() { return -1; }
                    public boolean isIdle() { return true; }
                    public int health() { return 99; }
                    public int maxHealth() { return 99; }
                };
            }
        };
    }
}
