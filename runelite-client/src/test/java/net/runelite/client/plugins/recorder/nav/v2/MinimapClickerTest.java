package net.runelite.client.plugins.recorder.nav.v2;

import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import net.runelite.client.sequence.internal.ActionRequest;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MinimapClickerTest
{
    private static final WorldPoint TARGET = new WorldPoint(3210, 3220, 0);

    private static Client clientWithMenuOpen(boolean open)
    {
        Client client = mock(Client.class);
        when(client.isMenuOpen()).thenReturn(open);
        return client;
    }

    @Test
    public void canClick_allConditionsOk_returnsTrue()
    {
        Client client = clientWithMenuOpen(false);
        MinimapClicker.MinimapAccess resolver = mock(MinimapClicker.MinimapAccess.class);
        Point onMinimap = new Point(700, 100);
        when(resolver.resolveMinimapOnly(TARGET)).thenReturn(onMinimap);
        when(resolver.isMinimapPixel(onMinimap)).thenReturn(true);

        MinimapClicker mc = new MinimapClicker(client, resolver, mock(HumanizedInputDispatcher.class));
        assertTrue(mc.canClick(TARGET));
    }

    @Test
    public void canClick_modalOpen_returnsFalse()
    {
        Client client = clientWithMenuOpen(true);
        MinimapClicker.MinimapAccess resolver = mock(MinimapClicker.MinimapAccess.class);
        // The check should short-circuit BEFORE consulting the resolver —
        // a modal blocks the click regardless of bounds.
        MinimapClicker mc = new MinimapClicker(client, resolver, mock(HumanizedInputDispatcher.class));
        assertFalse(mc.canClick(TARGET));
    }

    @Test
    public void canClick_targetOutOfMinimapRange_returnsFalse()
    {
        Client client = clientWithMenuOpen(false);
        MinimapClicker.MinimapAccess resolver = mock(MinimapClicker.MinimapAccess.class);
        when(resolver.resolveMinimapOnly(TARGET)).thenReturn(null);

        MinimapClicker mc = new MinimapClicker(client, resolver, mock(HumanizedInputDispatcher.class));
        assertFalse(mc.canClick(TARGET));
    }

    @Test
    public void canClick_pixelNotInMinimapBounds_returnsFalse()
    {
        // resolveMinimapOnly returned a point but the widget is hidden,
        // so isMinimapPixel returns false.
        Client client = clientWithMenuOpen(false);
        MinimapClicker.MinimapAccess resolver = mock(MinimapClicker.MinimapAccess.class);
        Point p = new Point(700, 100);
        when(resolver.resolveMinimapOnly(TARGET)).thenReturn(p);
        when(resolver.isMinimapPixel(p)).thenReturn(false);

        MinimapClicker mc = new MinimapClicker(client, resolver, mock(HumanizedInputDispatcher.class));
        assertFalse(mc.canClick(TARGET));
    }

    @Test
    public void canClick_targetNull_returnsFalse()
    {
        Client client = clientWithMenuOpen(false);
        MinimapClicker mc = new MinimapClicker(client,
            mock(MinimapClicker.MinimapAccess.class),
            mock(HumanizedInputDispatcher.class));
        assertFalse(mc.canClick(null));
    }

    @Test
    public void dispatch_resolveSucceeded_dispatchesClickBounds()
    {
        Client client = clientWithMenuOpen(false);
        MinimapClicker.MinimapAccess resolver = mock(MinimapClicker.MinimapAccess.class);
        Point p = new Point(700, 100);
        when(resolver.resolveMinimapOnly(TARGET)).thenReturn(p);
        when(resolver.isMinimapPixel(p)).thenReturn(true);
        HumanizedInputDispatcher dispatcher = mock(HumanizedInputDispatcher.class);

        MinimapClicker mc = new MinimapClicker(client, resolver, dispatcher);
        boolean dispatched = mc.dispatch(TARGET);
        assertTrue(dispatched);

        ArgumentCaptor<ActionRequest> cap = ArgumentCaptor.forClass(ActionRequest.class);
        verify(dispatcher).dispatch(cap.capture());
        ActionRequest req = cap.getValue();
        assertEquals(ActionRequest.Kind.CLICK_BOUNDS, req.getKind());
        assertEquals(ActionRequest.Channel.MOUSE, req.getChannel());
        assertTrue("bounds must contain the resolved pixel",
            req.getBounds().contains(p.getX(), p.getY()));
    }

    @Test
    public void dispatch_resolveFailed_returnsFalse_doesNotDispatch()
    {
        Client client = clientWithMenuOpen(false);
        MinimapClicker.MinimapAccess resolver = mock(MinimapClicker.MinimapAccess.class);
        when(resolver.resolveMinimapOnly(TARGET)).thenReturn(null);
        HumanizedInputDispatcher dispatcher = mock(HumanizedInputDispatcher.class);

        MinimapClicker mc = new MinimapClicker(client, resolver, dispatcher);
        assertFalse(mc.dispatch(TARGET));
        verify(dispatcher, never()).dispatch(any());
    }
}
