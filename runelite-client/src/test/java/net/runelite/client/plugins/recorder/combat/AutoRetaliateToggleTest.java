package net.runelite.client.plugins.recorder.combat;

import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.sequence.internal.ActionRequest;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class AutoRetaliateToggleTest
{
    private static final int VARP = VarPlayerID.OPTION_NODEF;

    private Client mockClient(int retaliateVarpValue)
    {
        Client client = mock(Client.class);
        when(client.getVarpValue(VARP)).thenReturn(retaliateVarpValue);
        return client;
    }

    // ---- ensureOn ----

    @Test
    public void ensureOn_alreadyOn_returnsTrue_noDispatch()
    {
        // VarPlayer 172 = 0 means ON — no click needed.
        Client client = mockClient(0);
        CombatDispatcher dispatcher = mock(CombatDispatcher.class);
        AutoRetaliateToggle toggle = new AutoRetaliateToggle(client, dispatcher);

        boolean result = toggle.ensureOn();

        assertTrue(result);
        verify(dispatcher, never()).dispatch(any());
    }

    @Test
    public void ensureOn_currentlyOff_dispatchesClick()
    {
        // VarPlayer 172 = 1 means OFF — need to click to toggle ON.
        Client client = mockClient(1);
        CombatDispatcher dispatcher = mock(CombatDispatcher.class);
        AutoRetaliateToggle toggle = new AutoRetaliateToggle(client, dispatcher);

        boolean result = toggle.ensureOn();

        assertFalse(result);
        ArgumentCaptor<ActionRequest> captor = ArgumentCaptor.forClass(ActionRequest.class);
        verify(dispatcher).dispatch(captor.capture());
        ActionRequest req = captor.getValue();
        assertEquals(ActionRequest.Kind.CLICK_WIDGET, req.getKind());
        assertEquals(InterfaceID.CombatInterface.RETALIATE, req.getWidgetId());
    }

    // ---- ensureOff ----

    @Test
    public void ensureOff_alreadyOff_returnsTrue_noDispatch()
    {
        // VarPlayer 172 = 1 means OFF — already correct.
        Client client = mockClient(1);
        CombatDispatcher dispatcher = mock(CombatDispatcher.class);
        AutoRetaliateToggle toggle = new AutoRetaliateToggle(client, dispatcher);

        boolean result = toggle.ensureOff();

        assertTrue(result);
        verify(dispatcher, never()).dispatch(any());
    }

    @Test
    public void ensureOff_currentlyOn_dispatchesClick()
    {
        // VarPlayer 172 = 0 means ON — need to click to toggle OFF.
        Client client = mockClient(0);
        CombatDispatcher dispatcher = mock(CombatDispatcher.class);
        AutoRetaliateToggle toggle = new AutoRetaliateToggle(client, dispatcher);

        boolean result = toggle.ensureOff();

        assertFalse(result);
        ArgumentCaptor<ActionRequest> captor = ArgumentCaptor.forClass(ActionRequest.class);
        verify(dispatcher).dispatch(captor.capture());
        assertEquals(ActionRequest.Kind.CLICK_WIDGET, captor.getValue().getKind());
        assertEquals(InterfaceID.CombatInterface.RETALIATE, captor.getValue().getWidgetId());
    }

    // ---- throttle ----

    @Test
    public void ensureOn_throttlesRapidCalls()
    {
        // First call dispatches; second call within 2s must NOT dispatch again.
        Client client = mockClient(1);   // OFF — would dispatch
        CombatDispatcher dispatcher = mock(CombatDispatcher.class);
        AutoRetaliateToggle toggle = new AutoRetaliateToggle(client, dispatcher);

        toggle.ensureOn();   // first call — dispatches
        toggle.ensureOn();   // second call — throttled

        verify(dispatcher, times(1)).dispatch(any());
    }

    @Test
    public void ensureOff_throttlesRapidCalls()
    {
        Client client = mockClient(0);   // ON — would dispatch to toggle OFF
        CombatDispatcher dispatcher = mock(CombatDispatcher.class);
        AutoRetaliateToggle toggle = new AutoRetaliateToggle(client, dispatcher);

        toggle.ensureOff();
        toggle.ensureOff();

        verify(dispatcher, times(1)).dispatch(any());
    }
}
