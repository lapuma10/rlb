package net.runelite.client.plugins.recorder.trail;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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

/**
 * Tests for {@link TrailWalker#walkRouteUntil}. The wrapper checks the
 * predicate before delegating to {@link TrailWalker#walkRoute} — true
 * yields {@link TrailWalker.Status#ARRIVED} without dispatching, false
 * delegates and returns whatever the underlying walker returned.
 *
 * <p>Fixture pattern lifted from {@code TrailWalkerHandoffTest} +
 * {@code TrailWalkerTickTest}. We don't drive multi-leg trails here —
 * the wrapper's job is the predicate check, not the walker mechanics
 * (those are exercised in the existing test files).
 */
public class TrailWalkerWalkUntilTest
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
        doAnswer(inv -> { ((Runnable) inv.getArgument(0)).run(); return null; })
            .when(clientThread).invokeLater(any(Runnable.class));
    }

    /** Build a minimal one-trail Route whose single trail's TrailPath
     *  has one walk leg. Caller drives `playerPos` to control whether
     *  the walker sees ARRIVED or IN_PROGRESS. */
    private Route singleTileRoute(WorldPoint at)
    {
        // Route.fromTrails wraps a list of Trails and picks weighted-random.
        // For a single-trail route the pick is deterministic.
        Trail trail = new Trail("single-tile", 0L, List.of(
            new TrailEvent.Tile(0L, at)));
        return Route.builder().trail(trail, 1).build();
    }

    @Test
    public void predicateTrueReturnsArrivedWithoutDispatch() throws InterruptedException
    {
        playerPos.set(new WorldPoint(0, 0, 0));
        Route route = singleTileRoute(new WorldPoint(99, 99, 0));
        TrailWalker w = new TrailWalker(client, clientThread, dispatcher);

        TrailWalker.Status s = w.walkRouteUntil(route, () -> true);

        assertEquals(TrailWalker.Status.ARRIVED, s);
        // Walker MUST NOT have dispatched anything — predicate fired
        // before any walk click.
        verify(dispatcher, never()).dispatch(any(ActionRequest.class));
    }

    @Test
    public void predicateFalseDelegatesToWalkRoute() throws InterruptedException
    {
        // Player at (0,0,0); trail goes to (5,5,0). walker dispatches WALK.
        playerPos.set(new WorldPoint(0, 0, 0));
        Route route = singleTileRoute(new WorldPoint(5, 5, 0));
        TrailWalker w = new TrailWalker(client, clientThread, dispatcher);

        TrailWalker.Status s = w.walkRouteUntil(route, () -> false);

        // Single-tile leg from (0,0) ≠ player tile → IN_PROGRESS, dispatches
        // a WALK click toward (5,5).
        assertEquals(TrailWalker.Status.IN_PROGRESS, s);
        ArgumentCaptor<ActionRequest> cap = ArgumentCaptor.forClass(ActionRequest.class);
        verify(dispatcher, atLeastOnce()).dispatch(cap.capture());
        assertEquals(ActionRequest.Kind.WALK, cap.getValue().getKind());
    }

    @Test
    public void predicateRunsOncePerCall() throws InterruptedException
    {
        playerPos.set(new WorldPoint(0, 0, 0));
        Route route = singleTileRoute(new WorldPoint(5, 5, 0));
        TrailWalker w = new TrailWalker(client, clientThread, dispatcher);

        AtomicInteger calls = new AtomicInteger();
        w.walkRouteUntil(route, () -> { calls.incrementAndGet(); return false; });
        assertEquals("predicate must be evaluated exactly once per walkRouteUntil call",
            1, calls.get());
    }

    @Test
    public void predicateThrowingPropagates()
    {
        playerPos.set(new WorldPoint(0, 0, 0));
        Route route = singleTileRoute(new WorldPoint(5, 5, 0));
        TrailWalker w = new TrailWalker(client, clientThread, dispatcher);

        try
        {
            w.walkRouteUntil(route, () -> { throw new IllegalStateException("boom"); });
            fail("expected IllegalStateException to propagate");
        }
        catch (IllegalStateException expected)
        {
            assertEquals("boom", expected.getMessage());
        }
        catch (InterruptedException ie)
        {
            fail("did not expect InterruptedException: " + ie);
        }
        // Walker must not have dispatched if the predicate threw.
        verify(dispatcher, never()).dispatch(any(ActionRequest.class));
    }

    @Test
    public void predicateTrueAllowsFreshTrailPickOnNextCall() throws InterruptedException
    {
        // After ARRIVED via short-circuit, walkRouteUntil must reset
        // activeRoutePath so the next call picks a fresh trail (matches
        // walkRoute's post-arrival contract). Verify by issuing a second
        // call with predicate=false — it should run normally without
        // crashing on stale state.
        playerPos.set(new WorldPoint(0, 0, 0));
        Route route = singleTileRoute(new WorldPoint(5, 5, 0));
        TrailWalker w = new TrailWalker(client, clientThread, dispatcher);

        assertEquals(TrailWalker.Status.ARRIVED, w.walkRouteUntil(route, () -> true));

        TrailWalker.Status s2 = w.walkRouteUntil(route, () -> false);
        // Tick after a "fresh" pick: WALK leg with player off-tile →
        // IN_PROGRESS + WALK dispatch.
        assertEquals(TrailWalker.Status.IN_PROGRESS, s2);
        verify(dispatcher, atLeastOnce()).dispatch(any(ActionRequest.class));
    }
}
