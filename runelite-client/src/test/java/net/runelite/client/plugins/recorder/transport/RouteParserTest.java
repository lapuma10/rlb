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

import org.junit.Test;
import static org.junit.Assert.*;

public class RouteParserTest
{
    @Test
    public void plainTileIsWalkWaypoint()
    {
        Waypoint w = RouteParser.parseLine("3208,3217");
        assertEquals(Waypoint.Kind.WALK, w.kind());
        assertEquals(3208, w.tile().getX());
        assertEquals(3217, w.tile().getY());
        assertEquals(0, w.tile().getPlane());
    }

    @Test
    public void plainTileWithPlane()
    {
        Waypoint w = RouteParser.parseLine("3208, 3217, 2");
        assertEquals(Waypoint.Kind.WALK, w.kind());
        assertEquals(2, w.tile().getPlane());
    }

    @Test
    public void openPrefixProducesTransport()
    {
        Waypoint w = RouteParser.parseLine("open:3208,3217");
        assertEquals(Waypoint.Kind.TRANSPORT, w.kind());
        assertEquals(Waypoint.TransportKind.OPEN, w.transportKind());
        assertEquals("Open", w.verb());
    }

    @Test
    public void climbUpProducesTransport()
    {
        Waypoint w = RouteParser.parseLine("climb-up:3208,3220,1");
        assertEquals(Waypoint.TransportKind.CLIMB_UP, w.transportKind());
        assertEquals("Climb-up", w.verb());
        assertEquals(1, w.tile().getPlane());
    }

    @Test
    public void climbDownProducesTransport()
    {
        Waypoint w = RouteParser.parseLine("climb-down:3208,3217");
        assertEquals(Waypoint.TransportKind.CLIMB_DOWN, w.transportKind());
        assertEquals("Climb-down", w.verb());
    }

    @Test
    public void interactRequiresVerb()
    {
        Waypoint w = RouteParser.parseLine("interact:3208,3217:Use");
        assertEquals(Waypoint.TransportKind.INTERACT, w.transportKind());
        assertEquals("Use", w.verb());
    }

    @Test(expected = IllegalArgumentException.class)
    public void interactWithoutVerbFails()
    {
        RouteParser.parseLine("interact:3208,3217");
    }

    @Test(expected = IllegalArgumentException.class)
    public void unknownPrefixFails()
    {
        RouteParser.parseLine("teleport:3208,3217");
    }

    @Test
    public void multipleLinesParseInOrder()
    {
        String src = "3200,3200\n"
            + "open:3208,3217\n"
            + "climb-up:3208,3217,2\n"
            + "# comment line\n"
            + "\n"
            + "3208,3220,2\n";
        RouteParser.Result r = RouteParser.parse(src);
        assertFalse(r.hasErrors());
        assertEquals(4, r.waypoints().size());
        assertEquals(Waypoint.Kind.WALK, r.waypoints().get(0).kind());
        assertEquals(Waypoint.TransportKind.OPEN, r.waypoints().get(1).transportKind());
        assertEquals(Waypoint.TransportKind.CLIMB_UP, r.waypoints().get(2).transportKind());
        assertEquals(Waypoint.Kind.WALK, r.waypoints().get(3).kind());
    }

    @Test
    public void invalidLinesAccumulateErrors()
    {
        String src = "3200,3200\n"
            + "open:not,a,tile\n"
            + "interact:3208,3217\n";
        RouteParser.Result r = RouteParser.parse(src);
        assertEquals(1, r.waypoints().size());
        assertEquals(2, r.errors().size());
    }
}
