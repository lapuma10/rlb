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
import net.runelite.client.sequence.composite.LinearSequence;
import net.runelite.client.sequence.dispatch.MockInputDispatcher;
import net.runelite.client.sequence.internal.FixtureObserver;
import net.runelite.client.sequence.telemetry.TelemetryRecord;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class AcceptanceTest {

    @Test
    public void buildAndRunLinearSequenceWalkStep_observesExpectedTelemetry() {
        WorldPoint target = new WorldPoint(3208, 3219, 0);
        FixtureObserver obs = new FixtureObserver(List.of(
            snap(new WorldPoint(3000, 3000, 0)),
            snap(new WorldPoint(3100, 3100, 0)),
            snap(target)));
        MockInputDispatcher mock = new MockInputDispatcher();

        SequenceManager mgr = SequenceManager.withDefaults();
        mgr.setObserver(obs);
        mgr.setDispatcher(mock);

        // Acceptance #3: build LinearSequence(WalkStep), run, walk happens
        mgr.run(new LinearSequence("user").then(new WalkStep(target)));

        // Acceptance #5: dispatcher swap captures requests
        mgr.getEngine().advanceTick();
        mgr.getEngine().advanceTick();
        mgr.getEngine().advanceTick();

        assertFalse("dispatcher should have received walk requests", mock.getRequests().isEmpty());

        // Acceptance #4: telemetry shows SELECTED -> STARTED -> CHECK -> SUCCEEDED
        List<TelemetryRecord.Event> events = mgr.getTelemetry().tail(64).stream()
            .map(TelemetryRecord::event).toList();
        int selected = events.indexOf(TelemetryRecord.Event.SELECTED);
        int started  = events.indexOf(TelemetryRecord.Event.STARTED);
        int check    = events.indexOf(TelemetryRecord.Event.CHECK);
        int succeed  = events.indexOf(TelemetryRecord.Event.SUCCEEDED);
        assertTrue("SELECTED present",  selected >= 0);
        assertTrue("STARTED after SELECTED", started > selected);
        assertTrue("CHECK after STARTED", check > started);
        assertTrue("SUCCEEDED after CHECK", succeed > check);
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
