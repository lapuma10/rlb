package net.runelite.client.plugins.recorder.trail;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.runelite.api.Client;
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

/** v2 transport-leg behaviour: opportunistic CLICK_GAME_OBJECT once
 *  the target is visible + reachable + within {@link
 *  TrailWalker#MAX_TRANSPORT_CLICK_DISTANCE}, with one camera rotation
 *  per leg before falling back to walking closer. */
public class TrailWalkerTransportTest
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
        // Settled pose by default.
        when(local.getPoseAnimation()).thenReturn(7);
        when(local.getIdlePoseAnimation()).thenReturn(7);
        // Synchronous client thread.
        doAnswer(inv -> { ((Runnable) inv.getArgument(0)).run(); return null; })
            .when(clientThread).invokeLater(any(Runnable.class));
    }

    /** Path that starts directly on the Transport leg — no leading
     *  WALK leg to advance past. */
    private static TrailPath bareTransport(WorldPoint pos, String verb)
    {
        return new TrailPath(List.of(
            new Leg.Transport(pos, verb, 5678, "GameObject", 1, 2),
            new Leg.Walk(List.of(new WorldPoint(pos.getX(), pos.getY(), pos.getPlane() + 1)))));
    }

    /** Mock the scene so {@link net.runelite.client.plugins.recorder.transport.TransportResolver}
     *  resolves {@code verb} on a GameObject at {@code worldTile}.
     *  Mirrors {@code TrailWalkerHandoffTest.mockSceneWithGameObject}. */
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
    public void clicksTransportFromAFewTilesAwayWhenAlwaysVisible() throws InterruptedException
    {
        // Player at (0,0,0); transport at (5,0,0) — Chebyshev=5, well
        // inside MAX_TRANSPORT_CLICK_DISTANCE=12. Verb PRESENT (mocked
        // scene), AlwaysVisible stub means visibility check passes.
        WorldPoint trans = new WorldPoint(5, 0, 0);
        playerPos.set(new WorldPoint(0, 0, 0));
        mockSceneWithGameObject(trans, 5678, "Climb-down");
        TrailPath path = bareTransport(trans, "Climb-down");
        TrailWalker w = new TrailWalker(client, clientThread, dispatcher,
            ObjectVisibility.alwaysVisible());
        w.tick(path);

        ArgumentCaptor<ActionRequest> cap = ArgumentCaptor.forClass(ActionRequest.class);
        verify(dispatcher).dispatch(cap.capture());
        assertEquals("v2 should click the transport opportunistically, not walk closer",
            ActionRequest.Kind.CLICK_GAME_OBJECT, cap.getValue().getKind());
        assertEquals(trans, cap.getValue().getTile());
    }

    @Test
    public void walksCloserWhenBeyondMaxClickDistance() throws InterruptedException
    {
        // Transport 20 tiles away — exceeds MAX_TRANSPORT_CLICK_DISTANCE=12.
        // Verb PRESENT but distance gate forces walk-closer.
        WorldPoint trans = new WorldPoint(20, 0, 0);
        playerPos.set(new WorldPoint(0, 0, 0));
        mockSceneWithGameObject(trans, 5678, "Climb-down");
        TrailPath path = bareTransport(trans, "Climb-down");
        TrailWalker w = new TrailWalker(client, clientThread, dispatcher,
            ObjectVisibility.alwaysVisible());
        w.tick(path);

        ArgumentCaptor<ActionRequest> cap = ArgumentCaptor.forClass(ActionRequest.class);
        verify(dispatcher).dispatch(cap.capture());
        assertEquals("beyond MAX_TRANSPORT_CLICK_DISTANCE, walker must walk closer first",
            ActionRequest.Kind.WALK, cap.getValue().getKind());
    }

    @Test
    public void rotatesCameraOnceWhenVisibilityFailsForOffCanvas() throws InterruptedException
    {
        // Verb PRESENT, within click range, but ObjectVisibility says
        // OFF_CANVAS (rotation-fixable). First tick: rotation, no click.
        // Second tick: still OFF_CANVAS but rotatedThisLeg latched — no
        // re-rotate; walker walks closer instead.
        WorldPoint trans = new WorldPoint(5, 0, 0);
        playerPos.set(new WorldPoint(0, 0, 0));
        mockSceneWithGameObject(trans, 5678, "Climb-down");
        TrailPath path = bareTransport(trans, "Climb-down");
        ObjectVisibility offCanvas = (tile, hull, self, reach) ->
            ObjectVisibility.Reason.OFF_CANVAS;
        TrailWalker w = new TrailWalker(client, clientThread, dispatcher, offCanvas);
        w.tick(path);
        verify(dispatcher, times(1)).rotateCameraToward(any(), eq(true));
        // No click yet.
        verify(dispatcher, never()).dispatch(argThat(r ->
            r != null && r.getKind() == ActionRequest.Kind.CLICK_GAME_OBJECT));

        w.tick(path);
        // Should still only be one rotation (clamp to once per leg).
        verify(dispatcher, times(1)).rotateCameraToward(any(), eq(true));
    }

    @Test
    public void doesNotRotateWhenVisibilityFailIsHudOrMenu() throws InterruptedException
    {
        // UNDER_HUD is not rotation-fixable — walker walks closer
        // immediately rather than rotating.
        WorldPoint trans = new WorldPoint(5, 0, 0);
        playerPos.set(new WorldPoint(0, 0, 0));
        mockSceneWithGameObject(trans, 5678, "Climb-down");
        TrailPath path = bareTransport(trans, "Climb-down");
        ObjectVisibility hudHidden = (tile, hull, self, reach) ->
            ObjectVisibility.Reason.UNDER_HUD;
        TrailWalker w = new TrailWalker(client, clientThread, dispatcher, hudHidden);
        w.tick(path);
        verify(dispatcher, never()).rotateCameraToward(any(), anyBoolean());
        ArgumentCaptor<ActionRequest> cap = ArgumentCaptor.forClass(ActionRequest.class);
        verify(dispatcher).dispatch(cap.capture());
        assertEquals(ActionRequest.Kind.WALK, cap.getValue().getKind());
    }

    @Test
    public void adjacentTrustFallbackClicksWhenVerbStateUnknown() throws InterruptedException
    {
        // Player adjacent (Chebyshev=1), no scene mock so verbState is
        // UNKNOWN. v1 fallback: click anyway, skip the visibility gate.
        // This preserves bot behavior when scene streaming hasn't
        // caught up but the player is right at the transport.
        WorldPoint trans = new WorldPoint(0, 1, 0);
        playerPos.set(new WorldPoint(0, 0, 0));
        TrailPath path = bareTransport(trans, "Climb-down");
        TrailWalker w = new TrailWalker(client, clientThread, dispatcher);
        w.tick(path);
        ArgumentCaptor<ActionRequest> cap = ArgumentCaptor.forClass(ActionRequest.class);
        verify(dispatcher).dispatch(cap.capture());
        assertEquals("adjacent + UNKNOWN must still click via v1 fallback",
            ActionRequest.Kind.CLICK_GAME_OBJECT, cap.getValue().getKind());
    }

    @Test
    public void busyDispatcherDeferralOnTransport() throws InterruptedException
    {
        WorldPoint trans = new WorldPoint(5, 0, 0);
        playerPos.set(new WorldPoint(0, 0, 0));
        mockSceneWithGameObject(trans, 5678, "Climb-down");
        TrailPath path = bareTransport(trans, "Climb-down");
        when(dispatcher.isBusy()).thenReturn(true);
        TrailWalker w = new TrailWalker(client, clientThread, dispatcher,
            ObjectVisibility.alwaysVisible());
        w.tick(path);
        verify(dispatcher, never()).dispatch(any());
        verify(dispatcher, never()).rotateCameraToward(any(), anyBoolean());
    }
}
