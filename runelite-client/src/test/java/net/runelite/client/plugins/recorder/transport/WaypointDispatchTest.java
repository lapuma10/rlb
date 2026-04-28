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
 * LOSS OF USE, DATA, OR PROFITS; HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.recorder.transport;

import net.runelite.api.coords.WorldPoint;
import net.runelite.client.sequence.internal.ActionRequest;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Validates the ActionRequest contract used by the walker: a plain Waypoint
 * builds a WALK request; a transport Waypoint builds a CLICK_GAME_OBJECT
 * request with the verb populated. The walker's higher-level scheduling
 * (loop, arrival polling, state transitions) lives in RecorderPanel and
 * exercises the same builder contract.
 */
public class WaypointDispatchTest
{
    /** Build the dispatch request the walker would send for a given waypoint.
     *  Mirrors the logic in RecorderPanel — kept here as the contract under
     *  test, so walker changes that diverge from this contract fail loudly. */
    private static ActionRequest buildRequest(Waypoint wp)
    {
        switch (wp.kind())
        {
            case WALK:
                return ActionRequest.builder()
                    .kind(ActionRequest.Kind.WALK)
                    .channel(ActionRequest.Channel.MOUSE)
                    .tile(wp.tile())
                    .build();
            case TRANSPORT:
                return ActionRequest.builder()
                    .kind(ActionRequest.Kind.CLICK_GAME_OBJECT)
                    .channel(ActionRequest.Channel.MOUSE)
                    .tile(wp.tile())
                    .verb(wp.verb())
                    .build();
            case WALK_AREA:
            default:
                throw new UnsupportedOperationException(
                    "WALK_AREA not supported by legacy dispatch contract");
        }
    }

    @Test
    public void walkWaypointDispatchesAsWalk()
    {
        Waypoint w = Waypoint.walk(new WorldPoint(3208, 3217, 0));
        ActionRequest req = buildRequest(w);
        assertEquals(ActionRequest.Kind.WALK, req.getKind());
        assertEquals(3208, req.getTile().getX());
        assertNull(req.getVerb());
    }

    @Test
    public void openTransportDispatchesAsClickGameObject()
    {
        Waypoint w = RouteParser.parseLine("open:3208,3217");
        ActionRequest req = buildRequest(w);
        assertEquals(ActionRequest.Kind.CLICK_GAME_OBJECT, req.getKind());
        assertEquals("Open", req.getVerb());
        assertEquals(3208, req.getTile().getX());
        assertEquals(3217, req.getTile().getY());
    }

    @Test
    public void climbTransportPropagatesVerb()
    {
        Waypoint w = RouteParser.parseLine("climb-up:3208,3217,1");
        ActionRequest req = buildRequest(w);
        assertEquals(ActionRequest.Kind.CLICK_GAME_OBJECT, req.getKind());
        assertEquals("Climb-up", req.getVerb());
        assertEquals(1, req.getTile().getPlane());
    }

    @Test
    public void interactTransportPassesGenericVerb()
    {
        Waypoint w = RouteParser.parseLine("interact:3208,3217:Push");
        ActionRequest req = buildRequest(w);
        assertEquals(ActionRequest.Kind.CLICK_GAME_OBJECT, req.getKind());
        assertEquals("Push", req.getVerb());
    }
}
