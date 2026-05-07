package net.runelite.client.plugins.recorder.trail;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.GameObject;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import net.runelite.client.sequence.internal.ActionRequest;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for the WALK→TRANSPORT interaction-ready handoff in
 * {@link TrailWalker#tick}. The walker should skip a WALK leg's tail-end
 * micro-walks once the next leg's TRANSPORT verb is callable from the
 * player's current position. Without this gate, the engine routinely
 * lands the player adjacent to (not on) the recorded last walk tile and
 * the walker burns 2-6 ticks issuing 1-tile reposition clicks before
 * chooseLegIndex finally advances.
 */
public class TrailWalkerHandoffTest
{
    private Client client;
    private ClientThread clientThread;
    private HumanizedInputDispatcher dispatcher;
    private Player local;
    private final AtomicReference<WorldPoint> playerPos = new AtomicReference<>();

    @Before
    public void setUp()
    {
        client = mock(Client.class);
        clientThread = mock(ClientThread.class);
        dispatcher = mock(HumanizedInputDispatcher.class);
        local = mock(Player.class);
        when(client.getLocalPlayer()).thenReturn(local);
        when(local.getWorldLocation()).thenAnswer(i -> playerPos.get());
        when(local.getPoseAnimation()).thenReturn(7);
        when(local.getIdlePoseAnimation()).thenReturn(7);
        doAnswer(inv -> { ((Runnable) inv.getArgument(0)).run(); return null; })
            .when(clientThread).invokeLater(any(Runnable.class));
    }

    /** Wire up a minimal scene with one tile at {@code worldTile} hosting a
     *  GameObject whose composition advertises {@code verb}. Mirrors the
     *  fixture pattern in {@code TransportResolverTest}. */
    private void mockSceneWithGameObject(WorldPoint worldTile, int objectId, String verb)
    {
        WorldView wv = mock(WorldView.class);
        Scene scene = mock(Scene.class);
        when(client.getTopLevelWorldView()).thenReturn(wv);
        when(wv.getScene()).thenReturn(scene);
        when(wv.getBaseX()).thenReturn(0);
        when(wv.getBaseY()).thenReturn(0);
        Tile[][][] tiles = new Tile[4][104][104];
        Tile tile = mock(Tile.class);
        GameObject go = mock(GameObject.class);
        when(go.getId()).thenReturn(objectId);
        when(tile.getWallObject()).thenReturn(null);
        when(tile.getGameObjects()).thenReturn(new GameObject[]{go});
        when(tile.getDecorativeObject()).thenReturn(null);
        when(tile.getGroundObject()).thenReturn(null);
        tiles[worldTile.getPlane()][worldTile.getX()][worldTile.getY()] = tile;
        when(scene.getTiles()).thenReturn(tiles);

        ObjectComposition comp = mock(ObjectComposition.class);
        when(comp.getActions()).thenReturn(new String[]{verb, null, null, null, null});
        when(comp.getImpostorIds()).thenReturn(null);
        when(client.getObjectDefinition(objectId)).thenReturn(comp);
    }

    @Test
    public void handoffAdvancesWalkLegWhenNextTransportIsCallable() throws InterruptedException
    {
        // Player two tiles short of the WALK leg's recorded last tile —
        // the OSRS pathfinder routinely lands the player like this. The
        // recorded last tile (5, 5, 0) is itself one tile from the
        // staircase at (5, 6, 0). The transport's Climb-up is in scene
        // and within direct-click range, so the walker should skip
        // re-clicking the WALK leg and let the next tick fire CLICK_GAME_OBJECT.
        WorldPoint stairs = new WorldPoint(5, 6, 0);
        mockSceneWithGameObject(stairs, 56230, "Climb-up");

        playerPos.set(new WorldPoint(5, 3, 0));
        TrailPath path = new TrailPath(List.of(
            new Leg.Walk(List.of(
                new WorldPoint(5, 3, 0),
                new WorldPoint(5, 4, 0),
                new WorldPoint(5, 5, 0))),
            new Leg.Transport(stairs, "Climb-up", 56230, "GameObject", 36, 61),
            new Leg.Walk(List.of(new WorldPoint(5, 6, 1)))));

        TrailWalker w = new TrailWalker(client, clientThread, dispatcher,
            ObjectVisibility.alwaysVisible());
        // Pretend the staircase is on the visible canvas — the on-canvas
        // gate would otherwise fail under the mock Perspective state.
        w.setOnCanvasProbeForTest(tile -> true);
        // First tick advances the walker INTO the transport leg via
        // the handoff. We don't make assertions on dispatch yet — the
        // handoff itself doesn't dispatch, it just bumps legIdx.
        w.tick(path);
        assertEquals(1, w.currentLegIndex());

        // Second tick: walker is on the TRANSPORT leg, verb is PRESENT,
        // should dispatch CLICK_GAME_OBJECT.
        w.tick(path);
        ArgumentCaptor<ActionRequest> cap = ArgumentCaptor.forClass(ActionRequest.class);
        verify(dispatcher, atLeastOnce()).dispatch(cap.capture());
        boolean sawTransportClick = cap.getAllValues().stream()
            .anyMatch(r -> r.getKind() == ActionRequest.Kind.CLICK_GAME_OBJECT);
        assertTrue("expected CLICK_GAME_OBJECT after handoff; got "
            + cap.getAllValues(), sawTransportClick);
    }

    @Test
    public void handoffDefersWhenTransportTileNotOnCanvas() throws InterruptedException
    {
        // 2026-05-02 STUCK loop: handoff fired toward Lumbridge p=2 stairs
        // while the camera was still rotating, leaving the staircase tile
        // off-canvas. PixelResolver returned null on every retry. Now the
        // handoff should DEFER — staying on the WALK leg so the player
        // closes distance and the camera follow brings the object into
        // view.
        WorldPoint stairs = new WorldPoint(5, 6, 0);
        mockSceneWithGameObject(stairs, 56230, "Climb-up");

        playerPos.set(new WorldPoint(5, 3, 0));
        TrailPath path = new TrailPath(List.of(
            new Leg.Walk(List.of(
                new WorldPoint(5, 3, 0),
                new WorldPoint(5, 4, 0),
                new WorldPoint(5, 5, 0))),
            new Leg.Transport(stairs, "Climb-up", 56230, "GameObject", 36, 61),
            new Leg.Walk(List.of(new WorldPoint(5, 6, 1)))));

        TrailWalker w = new TrailWalker(client, clientThread, dispatcher,
            ObjectVisibility.alwaysVisible());
        // Object is NOT on canvas yet (camera mid-rotate).
        w.setOnCanvasProbeForTest(tile -> false);
        w.tick(path);
        assertEquals("handoff must defer until object is on-canvas",
            0, w.currentLegIndex());
        // It should fall through to handleWalkLeg which dispatches a WALK.
        ArgumentCaptor<ActionRequest> cap = ArgumentCaptor.forClass(ActionRequest.class);
        verify(dispatcher, atLeastOnce()).dispatch(cap.capture());
        assertEquals(ActionRequest.Kind.WALK, cap.getValue().getKind());
    }

    @Test
    public void handoffDoesNotFireWhenTransportOutOfDirectClickRange() throws InterruptedException
    {
        // Same setup but player is 14 tiles from the staircase — beyond
        // TRANSPORT_DIRECT_CLICK_TILES (13). Walker must stay on the
        // WALK leg and dispatch a WALK click toward the leg's tiles.
        WorldPoint stairs = new WorldPoint(5, 30, 0);
        mockSceneWithGameObject(stairs, 56230, "Climb-up");

        playerPos.set(new WorldPoint(5, 3, 0));
        // Build a WALK leg long enough that the player at (5,3) has
        // ahead tiles in range; the leg's last tile is the staging
        // square right next to the staircase.
        java.util.List<WorldPoint> approach = new java.util.ArrayList<>();
        for (int y = 3; y <= 29; y++) approach.add(new WorldPoint(5, y, 0));
        TrailPath path = new TrailPath(List.of(
            new Leg.Walk(approach),
            new Leg.Transport(stairs, "Climb-up", 56230, "GameObject", 36, 61),
            new Leg.Walk(List.of(new WorldPoint(5, 31, 1)))));

        TrailWalker w = new TrailWalker(client, clientThread, dispatcher,
            ObjectVisibility.alwaysVisible());
        w.tick(path);
        assertEquals("handoff must not fire — transport is 27 tiles away",
            0, w.currentLegIndex());
        ArgumentCaptor<ActionRequest> cap = ArgumentCaptor.forClass(ActionRequest.class);
        verify(dispatcher, atLeastOnce()).dispatch(cap.capture());
        assertEquals(ActionRequest.Kind.WALK, cap.getValue().getKind());
    }

    /** As {@link #mockSceneWithGameObject} but also wires up a collision-flag
     *  grid on the WorldView so {@link Reachability} BFS sees the same world
     *  the player would. {@code wallFlags} is a list of (x, y) world tiles
     *  to stamp BLOCK_MOVEMENT_FULL on; everything else is walkable. */
    private void mockSceneWithGameObjectAndWalls(WorldPoint worldTile, int objectId,
                                                 String verb, List<WorldPoint> wallFlags)
    {
        WorldView wv = mock(WorldView.class);
        Scene scene = mock(Scene.class);
        when(client.getTopLevelWorldView()).thenReturn(wv);
        when(wv.getScene()).thenReturn(scene);
        when(wv.getBaseX()).thenReturn(0);
        when(wv.getBaseY()).thenReturn(0);
        when(wv.getSizeX()).thenReturn(104);
        when(wv.getSizeY()).thenReturn(104);
        Tile[][][] tiles = new Tile[4][104][104];
        Tile tile = mock(Tile.class);
        GameObject go = mock(GameObject.class);
        when(go.getId()).thenReturn(objectId);
        when(tile.getWallObject()).thenReturn(null);
        when(tile.getGameObjects()).thenReturn(new GameObject[]{go});
        when(tile.getDecorativeObject()).thenReturn(null);
        when(tile.getGroundObject()).thenReturn(null);
        tiles[worldTile.getPlane()][worldTile.getX()][worldTile.getY()] = tile;
        when(scene.getTiles()).thenReturn(tiles);

        ObjectComposition comp = mock(ObjectComposition.class);
        when(comp.getActions()).thenReturn(new String[]{verb, null, null, null, null});
        when(comp.getImpostorIds()).thenReturn(null);
        when(client.getObjectDefinition(objectId)).thenReturn(comp);

        int[][] flags = new int[104][104];
        for (WorldPoint w : wallFlags)
        {
            flags[w.getX()][w.getY()] |= CollisionDataFlag.BLOCK_MOVEMENT_FULL;
        }
        CollisionData cd = mock(CollisionData.class);
        when(cd.getFlags()).thenReturn(flags);
        when(wv.getCollisionMaps()).thenReturn(new CollisionData[]{cd, null, null, null});
    }

    @Test
    public void handoffDefersWhenBfsPathRunsAroundWall() throws InterruptedException
    {
        // Reproduces the pen→bank Lumbridge-castle bug: player is at chebyshev
        // 4 from the staircase, but a long wall sits between them. The
        // recorded WALK leg threads a doorway off-screen of this fixture; if
        // the handoff fires here, the walker abandons the entry path and
        // dispatches a WALK to the staircase tile, which the engine then
        // routes around the wall.  BFS sees the wall and forces the walker
        // to keep walking the WALK leg's tiles.
        WorldPoint stairs = new WorldPoint(8, 10, 0);
        // 21-tile vertical wall at x=10, blocking every path between the
        // player at x=12 and the stairs at x=8 within BFS depth 16.
        java.util.List<WorldPoint> wall = new java.util.ArrayList<>();
        for (int y = 0; y <= 25; y++) wall.add(new WorldPoint(10, y, 0));
        mockSceneWithGameObjectAndWalls(stairs, 56230, "Climb-up", wall);

        playerPos.set(new WorldPoint(12, 10, 0));
        // WALK leg's last tile is the recorded standing tile next to the
        // staircase — 4 tiles west, on the OTHER side of the wall.
        TrailPath path = new TrailPath(List.of(
            new Leg.Walk(List.of(
                new WorldPoint(12, 10, 0),
                new WorldPoint(11, 10, 0),
                new WorldPoint(9, 10, 0))),
            new Leg.Transport(stairs, "Climb-up", 56230, "GameObject", 36, 61,
                new WorldPoint(9, 10, 0)),
            new Leg.Walk(List.of(new WorldPoint(8, 10, 1)))));

        TrailWalker w = new TrailWalker(client, clientThread, dispatcher,
            ObjectVisibility.alwaysVisible());
        w.setOnCanvasProbeForTest(tile -> true);
        w.tick(path);
        assertEquals("handoff must defer — BFS path detours around the wall",
            0, w.currentLegIndex());
    }

    @Test
    public void handoffFiresWhenBfsPathIsClear() throws InterruptedException
    {
        // Same fixture but no wall — BFS should agree with chebyshev and the
        // existing handoff behavior must be preserved.
        WorldPoint stairs = new WorldPoint(5, 6, 0);
        mockSceneWithGameObjectAndWalls(stairs, 56230, "Climb-up", java.util.List.of());

        playerPos.set(new WorldPoint(5, 3, 0));
        TrailPath path = new TrailPath(List.of(
            new Leg.Walk(List.of(
                new WorldPoint(5, 3, 0),
                new WorldPoint(5, 4, 0),
                new WorldPoint(5, 5, 0))),
            new Leg.Transport(stairs, "Climb-up", 56230, "GameObject", 36, 61,
                new WorldPoint(5, 5, 0)),
            new Leg.Walk(List.of(new WorldPoint(5, 6, 1)))));

        TrailWalker w = new TrailWalker(client, clientThread, dispatcher,
            ObjectVisibility.alwaysVisible());
        w.setOnCanvasProbeForTest(tile -> true);
        w.tick(path);
        assertEquals("BFS clear of walls — handoff should fire as before",
            1, w.currentLegIndex());
    }

    @Test
    public void handoffDoesNotFireWhenVerbMissingFromScene() throws InterruptedException
    {
        // Object is in scene but advertises a different verb (e.g.
        // "Climb-down" on stairs that the trail recorded as "Climb-up").
        // checkVerbPresence returns MISSING — handoff stays put.
        WorldPoint stairs = new WorldPoint(5, 6, 0);
        mockSceneWithGameObject(stairs, 56230, "Climb-down");

        playerPos.set(new WorldPoint(5, 3, 0));
        TrailPath path = new TrailPath(List.of(
            new Leg.Walk(List.of(
                new WorldPoint(5, 3, 0),
                new WorldPoint(5, 4, 0),
                new WorldPoint(5, 5, 0))),
            new Leg.Transport(stairs, "Climb-up", 56230, "GameObject", 36, 61),
            new Leg.Walk(List.of(new WorldPoint(5, 6, 1)))));

        TrailWalker w = new TrailWalker(client, clientThread, dispatcher,
            ObjectVisibility.alwaysVisible());
        w.tick(path);
        assertEquals("handoff must not fire — recorded verb absent from scene",
            0, w.currentLegIndex());
    }
}
