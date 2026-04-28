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

import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class RouteWalkerTest
{
    @Test
    public void sampleTileReturnsTileInsideTheArea()
    {
        WorldArea a = new WorldArea(3091, 3243, 7, 5, 2);
        java.util.Random rng = new java.util.Random(42);
        WorldPoint t = RouteWalker.sampleTile(a, rng, p -> true);
        assertNotNull(t);
        assertTrue(t.getX() >= 3091 && t.getX() <= 3097);
        assertTrue(t.getY() >= 3243 && t.getY() <= 3247);
        assertEquals(2, t.getPlane());
    }

    @Test
    public void sampleTileSkipsRejectedTiles()
    {
        WorldArea a = new WorldArea(0, 0, 3, 3, 0);
        java.util.Random rng = new java.util.Random(0);
        // Reject every tile except (1,1).
        WorldPoint t = RouteWalker.sampleTile(a, rng, p -> p.getX() == 1 && p.getY() == 1);
        assertNotNull(t);
        assertEquals(1, t.getX());
        assertEquals(1, t.getY());
    }

    @Test
    public void sampleTileReturnsNullWhenAllTilesRejected()
    {
        WorldArea a = new WorldArea(0, 0, 3, 3, 0);
        java.util.Random rng = new java.util.Random(0);
        assertNull(RouteWalker.sampleTile(a, rng, p -> false));
    }
}
