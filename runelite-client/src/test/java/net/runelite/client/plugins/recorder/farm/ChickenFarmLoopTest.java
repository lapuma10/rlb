/*
 * Copyright (c) 2026, RuneLite
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
package net.runelite.client.plugins.recorder.farm;

import java.util.List;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.transport.Waypoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class ChickenFarmLoopTest
{
    private static final WorldArea BANK = new WorldArea(3091, 3243, 7, 5, 2);
    private static final WorldArea PEN  = new WorldArea(3232, 3293, 8, 8, 0);
    private static final List<Waypoint> ROUTE = List.of(
        Waypoint.walkArea("step1", new WorldArea(3204, 3208, 8, 8, 0)),
        Waypoint.transport(new WorldPoint(3239, 3295, 0),
            Waypoint.TransportKind.OPEN, "Open"));

    @Test
    public void inPenWithFreeSlotsKills()
    {
        assertEquals(ChickenFarmLoop.State.KILLING,
            ChickenFarmLoop.decideResume(BANK, PEN, ROUTE,
                new WorldPoint(3236, 3296, 0), 5));
    }

    @Test
    public void inPenWithFullInvWalksToBank()
    {
        assertEquals(ChickenFarmLoop.State.WALKING_TO_BANK,
            ChickenFarmLoop.decideResume(BANK, PEN, ROUTE,
                new WorldPoint(3236, 3296, 0), 0));
    }

    @Test
    public void inBankWithItemsBanks()
    {
        assertEquals(ChickenFarmLoop.State.BANKING,
            ChickenFarmLoop.decideResume(BANK, PEN, ROUTE,
                new WorldPoint(3094, 3245, 2), 0));
    }

    @Test
    public void inBankWithEmptyInvWalksToPen()
    {
        assertEquals(ChickenFarmLoop.State.WALKING_TO_PEN,
            ChickenFarmLoop.decideResume(BANK, PEN, ROUTE,
                new WorldPoint(3094, 3245, 2), 28));
    }

    @Test
    public void onPathFullInvHeadsToBank()
    {
        assertEquals(ChickenFarmLoop.State.WALKING_TO_BANK,
            ChickenFarmLoop.decideResume(BANK, PEN, ROUTE,
                new WorldPoint(3208, 3210, 0), 0));
    }

    @Test
    public void onPathEmptyInvHeadsToPen()
    {
        assertEquals(ChickenFarmLoop.State.WALKING_TO_PEN,
            ChickenFarmLoop.decideResume(BANK, PEN, ROUTE,
                new WorldPoint(3208, 3210, 0), 28));
    }

    @Test
    public void unknownLocationAborts()
    {
        assertEquals(ChickenFarmLoop.State.ABORTED,
            ChickenFarmLoop.decideResume(BANK, PEN, ROUTE,
                new WorldPoint(2000, 2000, 0), 28));
    }
}
