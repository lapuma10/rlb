package net.runelite.client.plugins.recorder.npc;

import java.util.concurrent.atomic.AtomicInteger;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link NpcInteraction#talkTo}. Drives the talk lifecycle
 * end-to-end against a mocked dispatcher and a stubbed dialogue-widget
 * visibility flow. Does NOT exercise the real
 * {@link HumanizedInputDispatcher#npcClickOnWorker} click chain — that's
 * covered indirectly by the live cooks-assistant smoke run after Task 2
 * lands.
 *
 * <p>Threading-guard convention: every test stubs
 * {@code client.isClientThread() == false} so the worker-thread guard
 * inside talkTo allows the call (the assertion is "not on client
 * thread" — Mockito's default false satisfies it but we set it
 * explicitly to be honest about intent). The single
 * {@link #throwsWhenOnClientThread} test does the inverse.
 */
public class NpcInteractionTalkToTest
{
    private Client client;
    private ClientThread clientThread;
    private HumanizedInputDispatcher dispatcher;

    @Before
    public void setUp()
    {
        client = mock(Client.class);
        clientThread = mock(ClientThread.class);
        dispatcher = mock(HumanizedInputDispatcher.class);

        // Default: caller is OFF the OSRS client thread (i.e. on the
        // dispatcher worker — talkTo's required calling context).
        when(client.isClientThread()).thenReturn(false);

        // ClientThread.invokeLater runs the runnable inline — used by
        // NpcInteraction.inDialogue's onClient marshaling.
        doAnswer(inv -> { ((Runnable) inv.getArgument(0)).run(); return null; })
            .when(clientThread).invokeLater(any(Runnable.class));
    }

    /** Stub a widget at {@code widgetId} as visible (true) or hidden (false). */
    private void stubDialogueWidget(int widgetId, boolean visible)
    {
        Widget w = mock(Widget.class);
        when(w.isHidden()).thenReturn(!visible);
        when(client.getWidget(widgetId)).thenReturn(w);
    }

    /** Stub all dialogue widgets as hidden so {@code inDialogue()} returns false. */
    private void stubNoDialogue()
    {
        stubDialogueWidget(InterfaceID.ChatLeft.UNIVERSE,        false);
        stubDialogueWidget(InterfaceID.ChatRight.UNIVERSE,       false);
        stubDialogueWidget(InterfaceID.Chatmenu.UNIVERSE,        false);
        stubDialogueWidget(InterfaceID.LevelupDisplay.UNIVERSE,  false);
    }

    @Test
    public void happyPath_returnsOpenedAndCompleted() throws InterruptedException
    {
        // Dialogue widgets all hidden initially, ChatLeft "appears"
        // (becomes visible) on the first poll inside talkTo.
        Widget chatLeft = mock(Widget.class);
        AtomicInteger pollCount = new AtomicInteger(0);
        when(chatLeft.isHidden()).thenAnswer(inv -> pollCount.incrementAndGet() < 1);
        when(client.getWidget(InterfaceID.ChatLeft.UNIVERSE)).thenReturn(chatLeft);
        stubDialogueWidget(InterfaceID.ChatRight.UNIVERSE,      false);
        stubDialogueWidget(InterfaceID.Chatmenu.UNIVERSE,       false);
        stubDialogueWidget(InterfaceID.LevelupDisplay.UNIVERSE, false);

        NpcInteraction npc = new NpcInteraction(client, clientThread, dispatcher);

        NpcInteraction.TalkResult r = npc.talkTo(42, "Talk-to", 5_000L, "Yes");

        assertEquals(NpcInteraction.TalkResult.OPENED_AND_COMPLETED, r);
        verify(dispatcher).npcClickOnWorker(eq(42), eq("Talk-to"));
    }

    @Test
    public void neverOpens_returnsNeverOpened() throws InterruptedException
    {
        // Click "succeeds" (mock void method is no-op), but no dialogue
        // widget ever becomes visible. talkTo polls at 250ms cadence —
        // a 600ms timeout fires within a couple of polls.
        stubNoDialogue();
        NpcInteraction npc = new NpcInteraction(client, clientThread, dispatcher);

        NpcInteraction.TalkResult r = npc.talkTo(42, "Talk-to", 600L, "Yes");

        assertEquals(NpcInteraction.TalkResult.NEVER_OPENED, r);
        verify(dispatcher).npcClickOnWorker(eq(42), eq("Talk-to"));
    }

    @Test
    public void dialogueOpensLate_butWithinTimeout_returnsOpenedAndCompleted()
        throws InterruptedException
    {
        // Hide ChatLeft for the first two polls, then reveal it.
        Widget chatLeft = mock(Widget.class);
        AtomicInteger calls = new AtomicInteger(0);
        when(chatLeft.isHidden()).thenAnswer(inv -> calls.incrementAndGet() < 3);
        when(client.getWidget(InterfaceID.ChatLeft.UNIVERSE)).thenReturn(chatLeft);
        stubDialogueWidget(InterfaceID.ChatRight.UNIVERSE,      false);
        stubDialogueWidget(InterfaceID.Chatmenu.UNIVERSE,       false);
        stubDialogueWidget(InterfaceID.LevelupDisplay.UNIVERSE, false);

        NpcInteraction npc = new NpcInteraction(client, clientThread, dispatcher);

        // 5_000ms window covers the ~750ms it takes for three 250ms polls.
        NpcInteraction.TalkResult r = npc.talkTo(42, "Talk-to", 5_000L);
        assertEquals(NpcInteraction.TalkResult.OPENED_AND_COMPLETED, r);
    }

    @Test
    public void emptyOptions_completesAdvanceOnly() throws InterruptedException
    {
        // ChatLeft visible (NPC continue) → completeDialogue clicks the
        // continue widget once, then dialogue closes.
        Widget chatLeft = mock(Widget.class);
        AtomicInteger isHiddenCalls = new AtomicInteger(0);
        // First call (inDialogue check) → visible. Second call (inside
        // completeDialogue's dialogueState) → still visible — picks
        // NPC_CONTINUES, dispatcher does the click. Third call → hidden
        // (dialogue closed).
        when(chatLeft.isHidden()).thenAnswer(inv -> isHiddenCalls.incrementAndGet() > 2);
        when(client.getWidget(InterfaceID.ChatLeft.UNIVERSE)).thenReturn(chatLeft);
        stubDialogueWidget(InterfaceID.ChatRight.UNIVERSE,      false);
        stubDialogueWidget(InterfaceID.Chatmenu.UNIVERSE,       false);
        stubDialogueWidget(InterfaceID.LevelupDisplay.UNIVERSE, false);

        NpcInteraction npc = new NpcInteraction(client, clientThread, dispatcher);

        NpcInteraction.TalkResult r = npc.talkTo(42, "Talk-to", 5_000L);
        assertEquals(NpcInteraction.TalkResult.OPENED_AND_COMPLETED, r);
        // Continue was clicked at least once.
        verify(dispatcher, atLeastOnce()).widgetClickOnWorker(InterfaceID.ChatLeft.CONTINUE);
    }

    @Test(expected = IllegalStateException.class)
    public void throwsWhenOnClientThread() throws InterruptedException
    {
        when(client.isClientThread()).thenReturn(true);
        NpcInteraction npc = new NpcInteraction(client, clientThread, dispatcher);
        npc.talkTo(42, "Talk-to", 5_000L);
    }
}
