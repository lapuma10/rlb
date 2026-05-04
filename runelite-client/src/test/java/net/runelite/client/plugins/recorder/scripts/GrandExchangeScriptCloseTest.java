package net.runelite.client.plugins.recorder.scripts;

import java.awt.event.KeyEvent;
import java.util.concurrent.atomic.AtomicBoolean;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import net.runelite.client.sequence.dispatch.InputOwnership;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GrandExchangeScript#tryCloseGrandExchange()} —
 * the verified close primitive added 2026-05-04 after PieDishScript spent
 * 8.5 minutes failing to bank because the GE main widget was still open.
 *
 * <p>Same shape as {@code GeInteractionCollectTest}: stub
 * {@link HumanizedInputDispatcher#runOnClient} to run the supplier
 * synchronously, drive {@link Widget#isHidden()} return values via
 * sequential Mockito stubs, run {@link net.runelite.client.sequence.dispatch.SequenceSleep#sleep}
 * with a null client (passes through to {@link Thread#sleep}).
 */
public class GrandExchangeScriptCloseTest {

    @SuppressWarnings("unchecked")
    private static void wireRunOnClient(HumanizedInputDispatcher dispatcher) throws Exception {
        doAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(0)).get())
            .when(dispatcher).runOnClient(any());
    }

    private static GrandExchangeScript newScript(Client client,
                                                 HumanizedInputDispatcher dispatcher) {
        ClientThread ct = mock(ClientThread.class);
        // InputOwnership is final — construct a real one (no-arg, cheap).
        InputOwnership io = new InputOwnership();
        return new GrandExchangeScript(client, ct, dispatcher, io,
            new WorldArea(3140, 3470, 30, 30, 0));
    }

    @Test
    public void alreadyClosed_returnsTrueWithoutEscape() throws Exception {
        Client client = mock(Client.class);
        HumanizedInputDispatcher dispatcher = mock(HumanizedInputDispatcher.class);
        wireRunOnClient(dispatcher);

        Widget root = mock(Widget.class);
        when(root.isHidden()).thenReturn(true);
        when(client.getWidget(InterfaceID.GeOffers.UNIVERSE)).thenReturn(root);

        GrandExchangeScript script = newScript(client, dispatcher);

        assertTrue(script.tryCloseGrandExchange());
        verify(dispatcher, never()).tapKey(anyInt());
    }

    @Test
    public void widgetMissing_treatedAsClosed() throws Exception {
        Client client = mock(Client.class);
        HumanizedInputDispatcher dispatcher = mock(HumanizedInputDispatcher.class);
        wireRunOnClient(dispatcher);

        when(client.getWidget(InterfaceID.GeOffers.UNIVERSE)).thenReturn(null);

        GrandExchangeScript script = newScript(client, dispatcher);

        assertTrue(script.tryCloseGrandExchange());
        verify(dispatcher, never()).tapKey(anyInt());
    }

    @Test
    public void closesWithinDeadline_dispatchesEscapeAndReturnsTrue() throws Exception {
        Client client = mock(Client.class);
        HumanizedInputDispatcher dispatcher = mock(HumanizedInputDispatcher.class);
        wireRunOnClient(dispatcher);

        Widget root = mock(Widget.class);
        // Open on first read (the gate), then closes after the ESC tap.
        when(root.isHidden()).thenReturn(false, false, true);
        when(client.getWidget(InterfaceID.GeOffers.UNIVERSE)).thenReturn(root);

        GrandExchangeScript script = newScript(client, dispatcher);

        long t0 = System.currentTimeMillis();
        assertTrue(script.tryCloseGrandExchange());
        long elapsed = System.currentTimeMillis() - t0;

        // Single ESC tap dispatched.
        verify(dispatcher, times(1)).tapKey(eq(KeyEvent.VK_ESCAPE));
        // First poll fires after 80 ms, finds isHidden=true → return.
        // Allow generous slack for slow CI; main goal is "well under the
        // 1500 ms timeout".
        assertTrue("returned in " + elapsed + " ms", elapsed < 1_000);
    }

    @Test
    public void neverCloses_timesOutAndReturnsFalse() throws Exception {
        Client client = mock(Client.class);
        HumanizedInputDispatcher dispatcher = mock(HumanizedInputDispatcher.class);
        wireRunOnClient(dispatcher);

        Widget root = mock(Widget.class);
        when(root.isHidden()).thenReturn(false);
        when(client.getWidget(InterfaceID.GeOffers.UNIVERSE)).thenReturn(root);

        GrandExchangeScript script = newScript(client, dispatcher);

        long t0 = System.currentTimeMillis();
        assertFalse(script.tryCloseGrandExchange());
        long elapsed = System.currentTimeMillis() - t0;

        // ESC dispatched once at the top — no multi-tap retry.
        verify(dispatcher, times(1)).tapKey(eq(KeyEvent.VK_ESCAPE));
        // Honoured the 1500 ms deadline (with slack for poll-cadence rounding).
        assertTrue("elapsed=" + elapsed, elapsed >= 1_400);
        assertTrue("elapsed=" + elapsed, elapsed < 2_500);
    }

    @Test
    public void isGrandExchangeOpen_reflectsWidgetState() throws Exception {
        Client client = mock(Client.class);
        HumanizedInputDispatcher dispatcher = mock(HumanizedInputDispatcher.class);
        wireRunOnClient(dispatcher);

        Widget root = mock(Widget.class);
        when(client.getWidget(InterfaceID.GeOffers.UNIVERSE)).thenReturn(root);

        GrandExchangeScript script = newScript(client, dispatcher);

        when(root.isHidden()).thenReturn(false);
        assertTrue(script.isGrandExchangeOpen());

        when(root.isHidden()).thenReturn(true);
        assertFalse(script.isGrandExchangeOpen());

        // Treat missing widget as closed too.
        AtomicBoolean called = new AtomicBoolean();
        when(client.getWidget(InterfaceID.GeOffers.UNIVERSE))
            .thenAnswer(inv -> { called.set(true); return null; });
        assertFalse(script.isGrandExchangeOpen());
        assertTrue("getWidget should have been called", called.get());
    }
}
