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
package net.runelite.client.plugins.recorder.transport;

import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class WaypointTest
{
    @Test
    public void walkAreaCarriesAreaAndOptionalName()
    {
        WorldArea a = new WorldArea(3091, 3243, 7, 5, 2);
        Waypoint w = Waypoint.walkArea("lumbridge_bank", a);
        assertEquals(Waypoint.Kind.WALK_AREA, w.kind());
        assertEquals(a, w.area());
        assertEquals("lumbridge_bank", w.name());
    }

    @Test
    public void walkSingleTileExposesAreaAsOneByOne()
    {
        Waypoint w = Waypoint.walk(new WorldPoint(3208, 3220, 2));
        assertEquals(Waypoint.Kind.WALK, w.kind());
        // Single-tile walk still exposes a 1x1 area for unified consumers.
        WorldArea a = w.area();
        assertNotNull(a);
        assertEquals(1, a.getWidth());
        assertEquals(1, a.getHeight());
        assertEquals(3208, a.getX());
        assertEquals(3220, a.getY());
        assertEquals(2, a.getPlane());
    }

    @Test
    public void transportRetainsTileAndVerb()
    {
        Waypoint w = Waypoint.transport(
            new WorldPoint(3239, 3295, 0),
            Waypoint.TransportKind.OPEN, "Open");
        assertEquals(Waypoint.Kind.TRANSPORT, w.kind());
        assertEquals(3239, w.tile().getX());
        assertEquals("Open", w.verb());
        // Transport waypoints have no name unless caller supplied one.
        assertNull(w.name());
    }

    @Test
    public void namedTransportFactoryAttachesName()
    {
        Waypoint w = Waypoint.transportNamed(
            "pen_gate",
            new WorldPoint(3239, 3295, 0),
            Waypoint.TransportKind.OPEN, "Open");
        assertEquals("pen_gate", w.name());
        assertEquals("Open", w.verb());
    }
}
