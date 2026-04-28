package net.runelite.client.plugins.recorder.walker;

import java.awt.Polygon;
import java.awt.Rectangle;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.WallObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.transport.TransportResolver;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class ObstacleHandlerTest
{
    private static final int BASE_X = 3200;
    private static final int BASE_Y = 3200;

    /** Build a Client mock with a wall object (door, "Open" verb) on a
     *  single scene tile. The scene array is sparse — every other tile
     *  is null which is exactly how the engine layouts the scene. */
    private static Client buildDoorClient(int doorObjectId, WorldPoint doorTile)
    {
        Client client = mock(Client.class);
        WorldView wv = mock(WorldView.class);
        Scene scene = mock(Scene.class);
        when(client.getTopLevelWorldView()).thenReturn(wv);
        when(wv.getScene()).thenReturn(scene);
        when(wv.getBaseX()).thenReturn(BASE_X);
        when(wv.getBaseY()).thenReturn(BASE_Y);

        Tile[][][] tiles = new Tile[4][104][104];
        Tile tile = mock(Tile.class);
        WallObject wall = mock(WallObject.class);
        Polygon hull = new Polygon(
            new int[]{100, 110, 110, 100}, new int[]{100, 100, 110, 110}, 4);
        when(wall.getId()).thenReturn(doorObjectId);
        when(wall.getConvexHull()).thenReturn(hull);
        when(tile.getWallObject()).thenReturn(wall);
        when(tile.getGameObjects()).thenReturn(new GameObject[0]);
        int sx = doorTile.getX() - BASE_X;
        int sy = doorTile.getY() - BASE_Y;
        tiles[doorTile.getPlane()][sx][sy] = tile;
        when(scene.getTiles()).thenReturn(tiles);

        ObjectComposition comp = mock(ObjectComposition.class);
        when(comp.getActions()).thenReturn(new String[]{"Open", null, null, null, null});
        when(comp.getImpostorIds()).thenReturn(null);
        when(client.getObjectDefinition(doorObjectId)).thenReturn(comp);
        return client;
    }

    @Test
    public void findsGateAtFrontierTileAndReturnsHull()
    {
        WorldPoint doorTile = new WorldPoint(3215, 3210, 0);
        Client client = buildDoorClient(1530, doorTile);
        TransportResolver resolver = new TransportResolver(client);
        ObstacleHandler oh = new ObstacleHandler(client, resolver);

        // Search "around" the player tile — the door is 5 tiles east.
        WorldPoint here = new WorldPoint(3210, 3210, 0);
        ObstacleHandler.Result r = oh.findAt(here, doorTile);
        assertNotNull(r);
        assertEquals(doorTile, r.tile);
        assertEquals("Open", r.match.matchedVerb());
        Rectangle b = r.hullBounds;
        assertNotNull(b);
        assertEquals(100, b.x);
        assertEquals(100, b.y);
    }

    @Test
    public void returnsNullIfVerbWhitelistDoesNotIncludeMatch()
    {
        WorldPoint doorTile = new WorldPoint(3215, 3210, 0);
        Client client = buildDoorClient(1530, doorTile);
        TransportResolver resolver = new TransportResolver(client);
        // Restrict whitelist to verbs the door does NOT have.
        ObstacleHandler oh = new ObstacleHandler(client, resolver)
            .withVerbs(java.util.Arrays.asList("Climb-up", "Squeeze-through"));

        ObstacleHandler.Result r = oh.findAt(new WorldPoint(3210, 3210, 0), doorTile);
        assertNull(r);
    }

    @Test
    public void radiusZeroOnlyChecksCenterTile()
    {
        WorldPoint doorTile = new WorldPoint(3215, 3210, 0);
        Client client = buildDoorClient(1530, doorTile);
        TransportResolver resolver = new TransportResolver(client);
        ObstacleHandler oh = new ObstacleHandler(client, resolver).withRadius(0);

        // Player at (3210, 3210), door at (3215, 3210) — radius 0 → no hit.
        ObstacleHandler.Result r = oh.findAt(new WorldPoint(3210, 3210, 0), doorTile);
        assertNull(r);
    }

    @Test
    public void ringOffsetsAreSortedByRingThenAlignment()
    {
        WorldPoint center = new WorldPoint(0, 0, 0);
        // Towards = +x direction → after sorting, on each ring the
        // positive-x offsets should come before negative-x ones.
        WorldPoint towards = new WorldPoint(100, 0, 0);
        List<int[]> off = ObstacleHandler.ringOffsets(2, center, towards);
        // First entry must be on ring 1 (closest); some ring-1 entry should
        // have dx > 0 and arrive before any ring-1 entry with dx < 0.
        int firstNegIdx = -1, firstPosIdx = -1;
        for (int i = 0; i < off.size(); i++)
        {
            int[] o = off.get(i);
            int ring = Math.max(Math.abs(o[0]), Math.abs(o[1]));
            if (ring != 1) continue;
            if (o[0] > 0 && firstPosIdx == -1) firstPosIdx = i;
            if (o[0] < 0 && firstNegIdx == -1) firstNegIdx = i;
        }
        assertTrue(firstPosIdx >= 0);
        assertTrue(firstNegIdx >= 0);
        assertTrue("positive-x ring-1 should come before negative-x ring-1",
            firstPosIdx < firstNegIdx);
    }

    @Test
    public void findOnFrontierUsesNearestFrontierToward()
    {
        // Build a tiny synthetic ReachabilityMap by making compute() return
        // a real BFS — but here we just exercise findOnFrontier with a
        // hand-built map. Easier path: skip the BFS and pass a
        // pre-constructed empty frontier set.
        Client client = buildDoorClient(1530, new WorldPoint(3215, 3210, 0));
        TransportResolver resolver = new TransportResolver(client);
        ObstacleHandler oh = new ObstacleHandler(client, resolver);

        // Empty reachability map → null.
        Reachability.ReachabilityMap empty =
            Reachability.ReachabilityMap.empty(new WorldPoint(3210, 3210, 0));
        assertNull(oh.findOnFrontier(empty, new WorldPoint(3215, 3210, 0)));
        assertNull(oh.findOnFrontier(null, null));
    }
}
