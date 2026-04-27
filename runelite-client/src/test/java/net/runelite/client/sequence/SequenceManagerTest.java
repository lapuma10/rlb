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
package net.runelite.client.sequence;

import net.runelite.api.coords.WorldPoint;
import net.runelite.client.sequence.activities.WalkStep;
import net.runelite.client.sequence.dispatch.MockInputDispatcher;
import net.runelite.client.sequence.internal.FixtureObserver;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class SequenceManagerTest {

    @Test
    public void runsAStep_andSwapsDispatcher() {
        WorldPoint target = new WorldPoint(5, 5, 0);
        FixtureObserver obs = new FixtureObserver(List.of(
            snap(new WorldPoint(0, 0, 0)),
            snap(new WorldPoint(0, 0, 0)),
            snap(target)));

        MockInputDispatcher mock = new MockInputDispatcher();
        SequenceManager mgr = SequenceManager.withDefaults();
        mgr.setObserver(obs);
        mgr.setDispatcher(mock);

        mgr.run(new WalkStep(target));
        // tick 1: onStart deferred → fires on first advance, queues walk, drained → mock receives ≥1 request
        // tick 2: still walking
        // tick 3: arrived → SUCCEEDED, frame popped, engine becomes IDLE
        mgr.getEngine().advanceTicks(3);

        assertFalse(mock.getRequests().isEmpty());
        assertEquals(SequenceState.IDLE, mgr.state());
    }

    private static WorldSnapshot snap(WorldPoint p) {
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
