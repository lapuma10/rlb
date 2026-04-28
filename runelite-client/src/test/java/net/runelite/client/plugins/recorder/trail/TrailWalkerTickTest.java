package net.runelite.client.plugins.recorder.trail;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.runelite.api.Client;
import net.runelite.api.Player;
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

public class TrailWalkerTickTest
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
        // ClientThread.invokeLater runs the runnable inline (synchronous
        // for tests).
        doAnswer(inv -> { ((Runnable) inv.getArgument(0)).run(); return null; })
            .when(clientThread).invokeLater(any(Runnable.class));
    }

    @Test
    public void walkLegDispatchesWALK() throws InterruptedException
    {
        playerPos.set(new WorldPoint(0, 0, 0));
        TrailPath path = new TrailPath(List.of(new Leg.Walk(List.of(
            new WorldPoint(0, 0, 0), new WorldPoint(0, 1, 0), new WorldPoint(0, 2, 0)))));
        TrailWalker w = new TrailWalker(client, clientThread, dispatcher);
        TrailWalker.Status s = w.tick(path);
        assertEquals(TrailWalker.Status.IN_PROGRESS, s);
        ArgumentCaptor<ActionRequest> cap = ArgumentCaptor.forClass(ActionRequest.class);
        verify(dispatcher).dispatch(cap.capture());
        assertEquals(ActionRequest.Kind.WALK, cap.getValue().getKind());
    }

    @Test
    public void singleTileWalkLegArrivesWhenPlayerOnTile() throws InterruptedException
    {
        playerPos.set(new WorldPoint(5, 5, 0));
        TrailPath path = new TrailPath(List.of(new Leg.Walk(List.of(
            new WorldPoint(5, 5, 0)))));
        TrailWalker w = new TrailWalker(client, clientThread, dispatcher);
        assertEquals(TrailWalker.Status.ARRIVED, w.tick(path));
    }

    @Test
    public void transportLegDispatchesCLICK_GAME_OBJECTwhenAdjacent() throws InterruptedException
    {
        playerPos.set(new WorldPoint(10, 10, 0));
        when(local.getPoseAnimation()).thenReturn(7);
        when(local.getIdlePoseAnimation()).thenReturn(7); // settled
        TrailPath path = new TrailPath(List.of(
            new Leg.Walk(List.of(new WorldPoint(10, 10, 0))),
            new Leg.Transport(new WorldPoint(10, 10, 0),
                "Climb-down", 5678, "GameObject", 1, 2),
            new Leg.Walk(List.of(new WorldPoint(10, 10, 1)))));
        TrailWalker w = new TrailWalker(client, clientThread, dispatcher);
        // First tick on leg 0: player at (10,10,0) — single-tile walk. The
        // walker should advance into leg 1 (transport) and dispatch a
        // CLICK_GAME_OBJECT.
        w.tick(path);
        ArgumentCaptor<ActionRequest> cap = ArgumentCaptor.forClass(ActionRequest.class);
        verify(dispatcher, atLeastOnce()).dispatch(cap.capture());
        boolean sawTransportClick = cap.getAllValues().stream()
            .anyMatch(r -> r.getKind() == ActionRequest.Kind.CLICK_GAME_OBJECT);
        assertTrue("expected at least one CLICK_GAME_OBJECT dispatch", sawTransportClick);
    }

    @Test
    public void emptyPathReturnsArrived() throws InterruptedException
    {
        TrailWalker w = new TrailWalker(client, clientThread, dispatcher);
        assertEquals(TrailWalker.Status.ARRIVED, w.tick(new TrailPath(List.of())));
    }
}
