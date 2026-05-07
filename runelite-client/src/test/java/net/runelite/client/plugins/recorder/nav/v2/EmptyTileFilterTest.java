package net.runelite.client.plugins.recorder.nav.v2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.IndexedObjectSet;
import net.runelite.api.NPC;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class EmptyTileFilterTest
{
    private static final WorldPoint TARGET = new WorldPoint(3208, 3217, 0);

    /** Build a minimal client + a single tile at TARGET with the supplied
     *  per-tile fixtures. */
    private static Client buildClient(GameObject[] gos, List<TileItem> items, List<NPC> npcs)
    {
        Client client = mock(Client.class);
        WorldView wv = mock(WorldView.class);
        Scene scene = mock(Scene.class);
        when(client.getTopLevelWorldView()).thenReturn(wv);
        when(wv.getScene()).thenReturn(scene);
        when(wv.getBaseX()).thenReturn(3200);
        when(wv.getBaseY()).thenReturn(3200);

        Tile[][][] tiles = new Tile[4][104][104];
        Tile tile = mock(Tile.class);
        when(tile.getGameObjects()).thenReturn(gos);
        when(tile.getGroundItems()).thenReturn(items);
        tiles[0][8][17] = tile;
        when(scene.getTiles()).thenReturn(tiles);

        IndexedObjectSet<NPC> npcSet = mock(IndexedObjectSet.class);
        when(npcSet.iterator()).thenReturn(npcs.iterator());
        when(wv.npcs()).thenReturn((IndexedObjectSet) npcSet);
        return client;
    }

    private static GameObject objectWithFirstAction(Client client, int objectId, String firstAction)
    {
        GameObject go = mock(GameObject.class);
        when(go.getId()).thenReturn(objectId);
        ObjectComposition def = mock(ObjectComposition.class);
        when(def.getActions()).thenReturn(new String[]{firstAction, null, null, null, null});
        when(def.getImpostorIds()).thenReturn(null);
        when(client.getObjectDefinition(objectId)).thenReturn(def);
        return go;
    }

    @Test
    public void tileWithNoEntities_passes()
    {
        Client client = buildClient(new GameObject[0], Collections.emptyList(), Collections.emptyList());
        EmptyTileFilter f = new EmptyTileFilter(client);
        assertTrue("empty tile should be plausibly clean", f.isPlausiblyClean(TARGET));
    }

    @Test
    public void tileWithNpcStanding_rejected()
    {
        NPC npc = mock(NPC.class);
        when(npc.getWorldLocation()).thenReturn(TARGET);
        Client client = buildClient(new GameObject[0], Collections.emptyList(), List.of(npc));
        EmptyTileFilter f = new EmptyTileFilter(client);
        assertFalse("NPC on tile must reject", f.isPlausiblyClean(TARGET));
    }

    @Test
    public void tileWithNpcOnDifferentTile_passes()
    {
        NPC npc = mock(NPC.class);
        when(npc.getWorldLocation()).thenReturn(new WorldPoint(3210, 3220, 0));
        Client client = buildClient(new GameObject[0], Collections.emptyList(), List.of(npc));
        EmptyTileFilter f = new EmptyTileFilter(client);
        assertTrue("NPC on a different tile is irrelevant", f.isPlausiblyClean(TARGET));
    }

    @Test
    public void tileWithGroundItem_rejected()
    {
        TileItem ti = mock(TileItem.class);
        Client client = buildClient(new GameObject[0], List.of(ti), Collections.emptyList());
        EmptyTileFilter f = new EmptyTileFilter(client);
        assertFalse("ground item on tile must reject", f.isPlausiblyClean(TARGET));
    }

    @Test
    public void tileWithGameObject_FirstVerbWalkHere_passes()
    {
        Client client = buildClient(new GameObject[0], Collections.emptyList(), Collections.emptyList());
        GameObject go = objectWithFirstAction(client, 1234, null); // null first action = no extra menu verb
        // Re-bind tile object array with the GameObject present.
        WorldView wv = client.getTopLevelWorldView();
        Scene scene = wv.getScene();
        Tile tile = scene.getTiles()[0][8][17];
        when(tile.getGameObjects()).thenReturn(new GameObject[]{go});
        EmptyTileFilter f = new EmptyTileFilter(client);
        assertTrue("object with no menu verb (e.g. decorative scenery) must pass",
            f.isPlausiblyClean(TARGET));
    }

    @Test
    public void tileWithDoor_FirstVerbOpen_rejected()
    {
        Client client = buildClient(new GameObject[0], Collections.emptyList(), Collections.emptyList());
        GameObject door = objectWithFirstAction(client, 1530, "Open");
        WorldView wv = client.getTopLevelWorldView();
        Scene scene = wv.getScene();
        Tile tile = scene.getTiles()[0][8][17];
        when(tile.getGameObjects()).thenReturn(new GameObject[]{door});
        EmptyTileFilter f = new EmptyTileFilter(client);
        assertFalse("door (first verb 'Open') must reject — click would open, not walk",
            f.isPlausiblyClean(TARGET));
    }

    @Test
    public void tileWithLadder_FirstVerbClimbUp_rejected()
    {
        Client client = buildClient(new GameObject[0], Collections.emptyList(), Collections.emptyList());
        GameObject ladder = objectWithFirstAction(client, 11616, "Climb-up");
        WorldView wv = client.getTopLevelWorldView();
        Scene scene = wv.getScene();
        Tile tile = scene.getTiles()[0][8][17];
        when(tile.getGameObjects()).thenReturn(new GameObject[]{ladder});
        EmptyTileFilter f = new EmptyTileFilter(client);
        assertFalse(f.isPlausiblyClean(TARGET));
    }

    @Test
    public void tileOutsideLoadedScene_rejected()
    {
        // Out of base+sceneSize; getTiles() lookup misses.
        Client client = buildClient(new GameObject[0], Collections.emptyList(), Collections.emptyList());
        EmptyTileFilter f = new EmptyTileFilter(client);
        assertFalse("tile outside loaded scene cannot be verified — conservative reject",
            f.isPlausiblyClean(new WorldPoint(9999, 9999, 0)));
    }

    @Test
    public void tileOnDifferentPlane_rejected()
    {
        // Same scene coords but plane=1 instead of 0; the tile fixture only
        // installs plane 0, so plane 1 is null and the filter must reject.
        Client client = buildClient(new GameObject[0], Collections.emptyList(), Collections.emptyList());
        EmptyTileFilter f = new EmptyTileFilter(client);
        assertFalse(f.isPlausiblyClean(new WorldPoint(3208, 3217, 1)));
    }
}
