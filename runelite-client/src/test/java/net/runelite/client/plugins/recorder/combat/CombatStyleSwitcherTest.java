package net.runelite.client.plugins.recorder.combat;

import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.sequence.internal.ActionRequest;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class CombatStyleSwitcherTest
{
    private Client mockClient(int varpAttackStyle)
    {
        Client client = mock(Client.class);
        when(client.getVarpValue(VarPlayerID.COM_MODE)).thenReturn(varpAttackStyle);
        return client;
    }

    @Test
    public void ensureStyleSkipsWhenAlreadyCorrect()
    {
        // VarPlayer returns 0 (ATTACK_PRIMARY), ensureStyle(ATTACK_PRIMARY) → no dispatch
        Client client = mockClient(0);
        CombatDispatcher dispatcher = mock(CombatDispatcher.class);
        CombatStyleSwitcher switcher = new CombatStyleSwitcher(client, dispatcher);

        boolean result = switcher.ensureStyle(CombatStyleIndex.ATTACK_PRIMARY);

        assertTrue("should return true when already correct", result);
        verify(dispatcher, never()).dispatch(any());
    }

    @Test
    public void ensureStyleClicksWhenWrong()
    {
        // VarPlayer returns 1 (STRENGTH_PRIMARY is active), want ATTACK_PRIMARY (idx 0)
        Client client = mockClient(1);
        CombatDispatcher dispatcher = mock(CombatDispatcher.class);
        CombatStyleSwitcher switcher = new CombatStyleSwitcher(client, dispatcher);

        boolean result = switcher.ensureStyle(CombatStyleIndex.ATTACK_PRIMARY);

        assertFalse("should return false when a dispatch was issued", result);
        ArgumentCaptor<ActionRequest> captor = ArgumentCaptor.forClass(ActionRequest.class);
        verify(dispatcher).dispatch(captor.capture());
        ActionRequest req = captor.getValue();
        assertEquals(ActionRequest.Kind.CLICK_WIDGET, req.getKind());
        assertEquals(InterfaceID.CombatInterface._0, req.getWidgetId());
    }

    @Test
    public void ensureStyleClicksStrengthWidget()
    {
        // VarPlayer returns 0 (ATTACK_PRIMARY), want STRENGTH_PRIMARY (idx 1)
        Client client = mockClient(0);
        CombatDispatcher dispatcher = mock(CombatDispatcher.class);
        CombatStyleSwitcher switcher = new CombatStyleSwitcher(client, dispatcher);

        switcher.ensureStyle(CombatStyleIndex.STRENGTH_PRIMARY);

        ArgumentCaptor<ActionRequest> captor = ArgumentCaptor.forClass(ActionRequest.class);
        verify(dispatcher).dispatch(captor.capture());
        assertEquals(InterfaceID.CombatInterface._1, captor.getValue().getWidgetId());
    }

    @Test
    public void ensureStyleClicksDefenceWidget()
    {
        Client client = mockClient(0);
        CombatDispatcher dispatcher = mock(CombatDispatcher.class);
        CombatStyleSwitcher switcher = new CombatStyleSwitcher(client, dispatcher);

        switcher.ensureStyle(CombatStyleIndex.DEFENCE_PRIMARY);

        ArgumentCaptor<ActionRequest> captor = ArgumentCaptor.forClass(ActionRequest.class);
        verify(dispatcher).dispatch(captor.capture());
        assertEquals(InterfaceID.CombatInterface._2, captor.getValue().getWidgetId());
    }

    @Test
    public void ensureStyleThrottlesRapidCalls()
    {
        // First call dispatches; second call within 2s is throttled (no second dispatch).
        Client client = mockClient(1);   // wrong style → would normally dispatch
        CombatDispatcher dispatcher = mock(CombatDispatcher.class);
        CombatStyleSwitcher switcher = new CombatStyleSwitcher(client, dispatcher);

        // First call — should dispatch.
        switcher.ensureStyle(CombatStyleIndex.ATTACK_PRIMARY);
        // Second call immediately — throttled.
        // VarPlayer still returns 1 (game hasn't committed the VarPlayer update).
        switcher.ensureStyle(CombatStyleIndex.ATTACK_PRIMARY);

        // Only ONE dispatch should have been made.
        verify(dispatcher, times(1)).dispatch(any());
    }
}
