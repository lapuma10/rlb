package net.runelite.client.plugins.recorder.nav.v2;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.recorder.worldmap.MapStore;
import net.runelite.client.plugins.recorder.worldmap.WorldMemoryConfig;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.mockito.junit.MockitoJUnitRunner;
import org.junit.runner.RunWith;

/** Pass-3 P0 fix: when {@link V2ExecutorEnv#onClient} is invoked from a
 *  caller that is already on the client thread, it must run the supplier
 *  inline (NOT marshal via {@link ClientThread#invokeLater}) — otherwise
 *  the caller self-deadlocks for {@code ONCLIENT_TIMEOUT_MS} per read.
 *  This is the silent-freeze class CLAUDE.md flags as the most common
 *  threading regression. */
@RunWith(MockitoJUnitRunner.class)
public class V2ExecutorEnvFastPathTest
{
    @Test
    public void onClient_fromClientThread_runsInline_doesNotMarshal()
    {
        Client client = mock(Client.class);
        ClientThread clientThread = mock(ClientThread.class);
        HumanizedInputDispatcher dispatcher = mock(HumanizedInputDispatcher.class);
        MapStore store = new MapStore(new WorldMemoryConfig());

        when(client.isClientThread()).thenReturn(true);
        // Confirm the supplier runs by reading state that only it knows.
        WorldPoint expected = new WorldPoint(3208, 3217, 0);
        net.runelite.api.Player self = mock(net.runelite.api.Player.class);
        when(client.getLocalPlayer()).thenReturn(self);
        when(self.getWorldLocation()).thenReturn(expected);

        // Dispatcher / filters are unused by playerLoc but the env needs
        // them for construction.
        EmptyTileFilter filter = new EmptyTileFilter(client);
        // MinimapClicker constructor requires a real PixelResolver — skip
        // by creating one with a fake access seam.
        net.runelite.api.Point dummyPoint = new net.runelite.api.Point(0, 0);
        MinimapClicker.MinimapAccess access = new MinimapClicker.MinimapAccess()
        {
            @Override public net.runelite.api.Point resolveMinimapOnly(WorldPoint t) { return dummyPoint; }
            @Override public boolean isMinimapPixel(net.runelite.api.Point p) { return true; }
        };
        // Reach the package-private MinimapClicker ctor for fake access.
        MinimapClicker minimap = new MinimapClicker(client, access, dispatcher);

        V2ExecutorEnv env = new V2ExecutorEnv(client, clientThread, dispatcher,
            filter, minimap, store);

        AtomicInteger invokeLaterCalls = new AtomicInteger(0);
        lenient().doAnswer(inv -> { invokeLaterCalls.incrementAndGet(); return null; })
            .when(clientThread).invokeLater(org.mockito.ArgumentMatchers.any(Runnable.class));

        WorldPoint result = env.playerLoc();

        assertEquals("client-thread fast path returns the supplier result", expected, result);
        assertEquals("client-thread caller MUST NOT marshal via invokeLater",
            0, invokeLaterCalls.get());
    }

    @Test
    public void onClient_fromWorkerThread_marshalsViaInvokeLater()
    {
        // Counter-test: when the caller is NOT on the client thread, the
        // marshal path runs (proves the fast-path doesn't fire incorrectly).
        Client client = mock(Client.class);
        ClientThread clientThread = mock(ClientThread.class);
        HumanizedInputDispatcher dispatcher = mock(HumanizedInputDispatcher.class);
        MapStore store = new MapStore(new WorldMemoryConfig());
        when(client.isClientThread()).thenReturn(false);

        EmptyTileFilter filter = new EmptyTileFilter(client);
        MinimapClicker.MinimapAccess access = new MinimapClicker.MinimapAccess()
        {
            @Override public net.runelite.api.Point resolveMinimapOnly(WorldPoint t)
            { return new net.runelite.api.Point(0, 0); }
            @Override public boolean isMinimapPixel(net.runelite.api.Point p) { return true; }
        };
        MinimapClicker minimap = new MinimapClicker(client, access, dispatcher);
        V2ExecutorEnv env = new V2ExecutorEnv(client, clientThread, dispatcher,
            filter, minimap, store);

        AtomicBoolean invokeLaterCalled = new AtomicBoolean(false);
        WorldPoint expected = new WorldPoint(3208, 3217, 0);
        net.runelite.api.Player self = mock(net.runelite.api.Player.class);
        when(client.getLocalPlayer()).thenReturn(self);
        when(self.getWorldLocation()).thenReturn(expected);
        // Drive the marshalled runnable by invoking it inline when invokeLater is called.
        lenient().doAnswer(inv -> {
            invokeLaterCalled.set(true);
            Runnable r = inv.getArgument(0);
            r.run();
            return null;
        }).when(clientThread).invokeLater(org.mockito.ArgumentMatchers.any(Runnable.class));

        WorldPoint result = env.playerLoc();
        assertTrue("worker caller MUST marshal", invokeLaterCalled.get());
        assertEquals(expected, result);
    }
}
