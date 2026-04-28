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

import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.WallObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class TransportResolverTest
{
    /** Build a minimal client/scene with a single tile at world (3208, 3217)
     *  whose wall object has a "closed door" composition with action "Open". */
    private Client buildDoorClient(int doorObjectId)
    {
        Client client = mock(Client.class);
        WorldView wv = mock(WorldView.class);
        Scene scene = mock(Scene.class);
        when(client.getTopLevelWorldView()).thenReturn(wv);
        when(wv.getScene()).thenReturn(scene);
        when(wv.getBaseX()).thenReturn(3200);
        when(wv.getBaseY()).thenReturn(3200);

        // Create a sparse 1x1x1 tile array offset to (sceneX=8, sceneY=17).
        Tile[][][] tiles = new Tile[2][104][104];
        Tile tile = mock(Tile.class);
        WallObject wall = mock(WallObject.class);
        when(wall.getId()).thenReturn(doorObjectId);
        when(tile.getWallObject()).thenReturn(wall);
        when(tile.getGameObjects()).thenReturn(new GameObject[0]);
        tiles[0][8][17] = tile;
        when(scene.getTiles()).thenReturn(tiles);

        ObjectComposition comp = mock(ObjectComposition.class);
        when(comp.getActions()).thenReturn(new String[]{"Open", null, null, null, null});
        when(comp.getImpostorIds()).thenReturn(null);
        when(client.getObjectDefinition(doorObjectId)).thenReturn(comp);
        return client;
    }

    @Test
    public void findsWallObjectByVerb()
    {
        Client client = buildDoorClient(1530);
        TransportResolver tr = new TransportResolver(client);
        TransportResolver.Match m = tr.findTransport(new WorldPoint(3208, 3217, 0), "Open");
        assertTrue(m.isSuccess());
        assertNotNull(m.wallObject());
        assertEquals(1530, m.matchedObjectId());
        assertEquals("Open", m.matchedVerb());
    }

    @Test
    public void rejectsTileMissingVerb()
    {
        Client client = buildDoorClient(1530);
        TransportResolver tr = new TransportResolver(client);
        TransportResolver.Match m = tr.findTransport(new WorldPoint(3208, 3217, 0), "Climb-up");
        assertFalse(m.isSuccess());
        assertTrue(m.failure().contains("Climb-up"));
    }

    @Test
    public void outOfSceneTileFails()
    {
        Client client = buildDoorClient(1530);
        TransportResolver tr = new TransportResolver(client);
        // tile far outside the loaded scene
        TransportResolver.Match m = tr.findTransport(new WorldPoint(9999, 9999, 0), "Open");
        assertFalse(m.isSuccess());
        assertTrue(m.failure().contains("out of loaded scene"));
    }

    @Test
    public void findAnyTransportPicksFirstKnownVerb()
    {
        // Mock a tile holding a WallObject whose composition advertises "Open".
        Client client = mock(Client.class);
        Tile tile = mock(Tile.class);
        WorldView wv = mock(WorldView.class);
        Scene scene = mock(Scene.class);
        when(client.getTopLevelWorldView()).thenReturn(wv);
        when(wv.getScene()).thenReturn(scene);
        when(wv.getBaseX()).thenReturn(3200);
        when(wv.getBaseY()).thenReturn(3200);
        Tile[][][] tiles = new Tile[4][104][104];
        int sx = 3239 - 3200, sy = 3295 - 3200;
        tiles[0][sx][sy] = tile;
        when(scene.getTiles()).thenReturn(tiles);
        WallObject wall = mock(WallObject.class);
        when(wall.getId()).thenReturn(1551);
        ObjectComposition comp = mock(ObjectComposition.class);
        when(comp.getActions()).thenReturn(new String[]{"Open", null, null, "Examine", null});
        when(client.getObjectDefinition(1551)).thenReturn(comp);
        when(tile.getWallObject()).thenReturn(wall);

        TransportResolver tr = new TransportResolver(client);
        TransportResolver.AnyMatch any = tr.findAnyTransport(new WorldPoint(3239, 3295, 0));
        assertNotNull(any);
        assertEquals(Waypoint.TransportKind.OPEN, any.kind());
        assertEquals("Open", any.verb());
        assertEquals(1551, any.objectId());
    }
}
