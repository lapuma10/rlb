/*
 * Copyright (c) 2026, RuneLite
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.recorder.combat;

import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.IndexedObjectSet;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.sequence.internal.ActionRequest;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.util.List;

public class ChickenCombatLoopTest
{
    @Test
    public void doSelectThenDoEngage_dispatchesOneClickNpcRequest()
    {
        Player self = mock(Player.class);
        NPC chicken = mockChicken(42, 3231, 3296, 0);
        Client client = clientWith(self, new WorldPoint(3230, 3296, 0), chicken);
        CombatDispatcher dispatcher = mock(CombatDispatcher.class);
        when(dispatcher.isBusy()).thenReturn(false);
        when(dispatcher.lastErrorMessage()).thenReturn(null);
        // Engagement check: pretend the player is interacting with the chicken
        // immediately. We run the loop via the package-private hooks, not via
        // start() — start() spawns a thread which would call doEngage which
        // includes a 5s wait for engagement; we want a synchronous unit test.
        when(self.getInteracting()).thenReturn(null, null, chicken);

        ChickenCombatLoop loop = new ChickenCombatLoop(dispatcher, client, null,
            null,
            new NpcSelector(ChickenCombatLoop.CHICKEN_NAME),
            TargetVisibility.alwaysVisible(), s -> {});
        boolean picked = loop.doSelect();
        assertTrue("doSelect should pick the available chicken", picked);
        assertEquals(ChickenCombatLoop.State.ENGAGING, loop.state());
        assertNotNull(loop.currentTarget());
        assertEquals(42, loop.currentTarget().npcIndex());

        loop.doEngage();
        ArgumentCaptor<ActionRequest> cap = ArgumentCaptor.forClass(ActionRequest.class);
        verify(dispatcher, times(1)).dispatch(cap.capture());
        ActionRequest req = cap.getValue();
        assertEquals(ActionRequest.Kind.CLICK_NPC, req.getKind());
        assertEquals(42, req.getNpcIndex());
        assertEquals(ChickenCombatLoop.State.IN_COMBAT, loop.state());
    }

    @Test
    public void doSelect_playerAlreadyTargetingChicken_adoptsCombat()
    {
        Player self = mock(Player.class);
        NPC chicken = mockChicken(44, 3231, 3296, 0);
        when(self.getInteracting()).thenReturn(chicken);
        Client client = clientWith(self, new WorldPoint(3230, 3296, 0), chicken);
        CombatDispatcher dispatcher = mock(CombatDispatcher.class);

        ChickenCombatLoop loop = new ChickenCombatLoop(dispatcher, client, null,
            null,
            new NpcSelector(ChickenCombatLoop.CHICKEN_NAME),
            TargetVisibility.alwaysVisible(), s -> {});

        assertTrue(loop.doSelect());
        assertEquals(ChickenCombatLoop.State.IN_COMBAT, loop.state());
        assertNotNull(loop.currentTarget());
        assertEquals(44, loop.currentTarget().npcIndex());
        verify(dispatcher, never()).dispatch(any());
    }

    @Test
    public void doSelect_chickenAlreadyTargetingPlayer_adoptsCombat()
    {
        Player self = mock(Player.class);
        NPC chicken = mockChicken(45, 3231, 3296, 0);
        when(chicken.getInteracting()).thenReturn(self);
        Client client = clientWith(self, new WorldPoint(3230, 3296, 0), chicken);
        CombatDispatcher dispatcher = mock(CombatDispatcher.class);

        ChickenCombatLoop loop = new ChickenCombatLoop(dispatcher, client, null,
            null,
            new NpcSelector(ChickenCombatLoop.CHICKEN_NAME),
            TargetVisibility.alwaysVisible(), s -> {});

        assertTrue(loop.doSelect());
        assertEquals(ChickenCombatLoop.State.IN_COMBAT, loop.state());
        assertNotNull(loop.currentTarget());
        assertEquals(45, loop.currentTarget().npcIndex());
        verify(dispatcher, never()).dispatch(any());
    }

    @Test
    public void inCombatTick_doesNotDispatch_andTransitionsToKilledOnHpZero()
    {
        Player self = mock(Player.class);
        NPC chicken = mockChicken(7, 3231, 3296, 0);
        Client client = clientWith(self, new WorldPoint(3230, 3296, 0), chicken);
        CombatDispatcher dispatcher = mock(CombatDispatcher.class);
        ChickenCombatLoop loop = new ChickenCombatLoop(dispatcher, client, null,
            null,
            new NpcSelector(ChickenCombatLoop.CHICKEN_NAME),
            TargetVisibility.alwaysVisible(), s -> {});
        loop.setTargetForTesting(new CombatTarget(7, "Chicken", 0));
        loop.setStateForTesting(ChickenCombatLoop.State.IN_COMBAT);
        CombatStateTracker tracker = new CombatStateTracker(7);
        CombatTarget ct = loop.currentTarget();

        // Tick 1: engaged (chicken's interacting target = local player),
        // full HP — no transition.
        when(chicken.getInteracting()).thenReturn(self);
        when(chicken.getHealthRatio()).thenReturn(30);
        assertFalse(loop.combatTick(ct, tracker));
        assertEquals(ChickenCombatLoop.State.IN_COMBAT, loop.state());
        // Tick 2: HP drained — KILLED.
        when(chicken.getHealthRatio()).thenReturn(0);
        assertTrue(loop.combatTick(ct, tracker));
        assertEquals(ChickenCombatLoop.State.KILLED, loop.state());
        // Critical: never dispatched anything during IN_COMBAT polling.
        verify(dispatcher, never()).dispatch(any());
    }

    @Test
    public void inCombatTick_playerOnlyEngagementThatBreaks_reAttacksInsteadOfHanging()
    {
        Player self = mock(Player.class);
        NPC chicken = mockChicken(15, 3231, 3296, 0);
        Client client = clientWith(self, new WorldPoint(3230, 3296, 0), chicken);
        CombatDispatcher dispatcher = mock(CombatDispatcher.class);
        ChickenCombatLoop loop = new ChickenCombatLoop(dispatcher, client, null,
            null,
            new NpcSelector(ChickenCombatLoop.CHICKEN_NAME),
            TargetVisibility.alwaysVisible(), s -> {});
        loop.setTargetForTesting(new CombatTarget(15, "Chicken", 0));
        loop.setStateForTesting(ChickenCombatLoop.State.IN_COMBAT);
        CombatStateTracker tracker = new CombatStateTracker(15);
        CombatTarget ct = loop.currentTarget();

        when(self.getInteracting()).thenReturn(chicken, null, null, null);
        when(chicken.getInteracting()).thenReturn(null);
        when(chicken.getHealthRatio()).thenReturn(30);

        assertFalse("first poll should establish one-sided engagement",
            loop.combatTick(ct, tracker));
        assertEquals(ChickenCombatLoop.State.IN_COMBAT, loop.state());
        assertFalse(loop.combatTick(ct, tracker));
        assertFalse(loop.combatTick(ct, tracker));
        assertTrue("after >2 lost ticks the loop should re-attack",
            loop.combatTick(ct, tracker));
        assertEquals(ChickenCombatLoop.State.ENGAGING, loop.state());
        verify(dispatcher, never()).dispatch(any());
    }

    @Test
    public void inCombatTick_chickenLockedOnAnotherPlayer_releasesLockAndReSelects()
    {
        Player self = mock(Player.class);
        Player otherPlayer = mock(Player.class);
        NPC chicken = mockChicken(13, 3231, 3296, 0);
        Client client = clientWith(self, new WorldPoint(3230, 3296, 0), chicken);
        CombatDispatcher dispatcher = mock(CombatDispatcher.class);
        ChickenCombatLoop loop = new ChickenCombatLoop(dispatcher, client, null,
            null,
            new NpcSelector(ChickenCombatLoop.CHICKEN_NAME),
            TargetVisibility.alwaysVisible(), s -> {});
        loop.setTargetForTesting(new CombatTarget(13, "Chicken", 0));
        loop.setStateForTesting(ChickenCombatLoop.State.IN_COMBAT);
        CombatStateTracker tracker = new CombatStateTracker(13);
        CombatTarget ct = loop.currentTarget();

        // Chicken is locked onto another player and we have never engaged.
        // Two consecutive observations crosses the steal threshold.
        when(chicken.getInteracting()).thenReturn(otherPlayer);
        when(chicken.getHealthRatio()).thenReturn(30);
        assertFalse("first stolen observation below threshold — keep polling",
            loop.combatTick(ct, tracker));
        assertEquals(ChickenCombatLoop.State.IN_COMBAT, loop.state());
        // Second observation — bail.
        assertTrue("second stolen observation should bail", loop.combatTick(ct, tracker));
        assertEquals(ChickenCombatLoop.State.SELECTING, loop.state());
        assertNull("lock must be released after kill steal", loop.currentTarget());
        assertEquals("must NOT credit a stolen kill", 0, loop.killCount());
        // Critical: never dispatched anything during the bail.
        verify(dispatcher, never()).dispatch(any());
    }

    @Test
    public void doKilled_releasesLockAndReturnsToSelecting()
    {
        Client client = mock(Client.class);
        CombatDispatcher dispatcher = mock(CombatDispatcher.class);
        ChickenCombatLoop loop = new ChickenCombatLoop(dispatcher, client, null,
            null,
            new NpcSelector(ChickenCombatLoop.CHICKEN_NAME),
            TargetVisibility.alwaysVisible(), s -> {});
        loop.setTargetForTesting(new CombatTarget(99, "Chicken", 0));
        loop.setStateForTesting(ChickenCombatLoop.State.KILLED);

        loop.doKilled();
        assertNull("lock must be released after KILLED", loop.currentTarget());
        assertEquals(1, loop.killCount());
        assertEquals(ChickenCombatLoop.State.SELECTING, loop.state());
    }

    @Test
    public void doEngage_chatRejectsAlreadyFighting_targetReleasesImmediately()
    {
        Player self = mock(Player.class);
        NPC chicken = mockChicken(46, 3231, 3296, 0);
        Client client = clientWith(self, new WorldPoint(3230, 3296, 0), chicken);
        CombatDispatcher dispatcher = mock(CombatDispatcher.class);
        when(dispatcher.lastErrorMessage()).thenReturn(null);

        ChickenCombatLoop loop = new ChickenCombatLoop(dispatcher, client, null,
            null,
            new NpcSelector(ChickenCombatLoop.CHICKEN_NAME),
            TargetVisibility.alwaysVisible(), s -> {});
        loop.setTargetForTesting(new CombatTarget(46, "Chicken", 0));
        loop.setStateForTesting(ChickenCombatLoop.State.ENGAGING);
        when(dispatcher.isBusy()).thenAnswer(inv -> {
            loop.onChatMessage(new ChatMessage(null, ChatMessageType.GAMEMESSAGE, null,
                "Someone else is fighting that.", null, 0));
            return false;
        });

        loop.doEngage();

        assertEquals(ChickenCombatLoop.State.SELECTING, loop.state());
        assertNull(loop.currentTarget());
        verify(dispatcher, times(1)).dispatch(any());
    }

    @Test
    public void doEngage_chickenClaimedBeforeClick_releasesWithoutDispatch()
    {
        Player self = mock(Player.class);
        Player otherPlayer = mock(Player.class);
        NPC chicken = mockChicken(47, 3231, 3296, 0);
        when(chicken.getInteracting()).thenReturn(otherPlayer);
        Client client = clientWith(self, new WorldPoint(3230, 3296, 0), chicken);
        CombatDispatcher dispatcher = mock(CombatDispatcher.class);

        ChickenCombatLoop loop = new ChickenCombatLoop(dispatcher, client, null,
            null,
            new NpcSelector(ChickenCombatLoop.CHICKEN_NAME),
            TargetVisibility.alwaysVisible(), s -> {});
        loop.setTargetForTesting(new CombatTarget(47, "Chicken", 0));
        loop.setStateForTesting(ChickenCombatLoop.State.ENGAGING);

        loop.doEngage();

        assertEquals(ChickenCombatLoop.State.SELECTING, loop.state());
        assertNull(loop.currentTarget());
        verify(dispatcher, never()).dispatch(any());
    }

    @Test
    public void doSelect_skipsRecentlyServerRejectedChicken()
    {
        Player self = mock(Player.class);
        NPC rejected = mockChicken(48, 3231, 3296, 0);
        NPC alternate = mockChicken(49, 3233, 3296, 0);
        Client client = clientWith(self, new WorldPoint(3230, 3296, 0), rejected, alternate);
        CombatDispatcher dispatcher = mock(CombatDispatcher.class);
        final ChickenCombatLoop[] loopHolder = new ChickenCombatLoop[1];
        when(dispatcher.lastErrorMessage()).thenReturn(null);
        when(dispatcher.isBusy()).thenAnswer(inv -> {
            loopHolder[0].onChatMessage(new ChatMessage(null, ChatMessageType.GAMEMESSAGE, null,
                "Someone else is fighting that.", null, 0));
            return false;
        });

        ChickenCombatLoop loop = new ChickenCombatLoop(dispatcher, client, null,
            null,
            new NpcSelector(ChickenCombatLoop.CHICKEN_NAME),
            TargetVisibility.alwaysVisible(), s -> {});
        loopHolder[0] = loop;
        loop.setTargetForTesting(new CombatTarget(48, "Chicken", 0));
        loop.setStateForTesting(ChickenCombatLoop.State.ENGAGING);

        loop.doEngage();
        assertEquals(ChickenCombatLoop.State.SELECTING, loop.state());

        assertTrue(loop.doSelect());
        assertNotNull(loop.currentTarget());
        assertEquals("should avoid immediately re-picking the rejected chicken",
            49, loop.currentTarget().npcIndex());
    }

    // ----- helpers -----

    private static NPC mockChicken(int idx, int x, int y, int plane)
    {
        NPC n = mock(NPC.class);
        when(n.getIndex()).thenReturn(idx);
        when(n.getName()).thenReturn("Chicken");
        NPCComposition c = mock(NPCComposition.class);
        when(c.getName()).thenReturn("Chicken");
        when(n.getComposition()).thenReturn(c);
        when(n.getWorldLocation()).thenReturn(new WorldPoint(x, y, plane));
        when(n.getHealthRatio()).thenReturn(30);
        when(n.getInteracting()).thenReturn(null);
        return n;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Client clientWith(Player self, WorldPoint pos, NPC... npcs)
    {
        Client client = mock(Client.class);
        when(client.getLocalPlayer()).thenReturn(self);
        when(self.getWorldLocation()).thenReturn(pos);
        WorldView wv = mock(WorldView.class);
        IndexedObjectSet npcSet = mock(IndexedObjectSet.class);
        when(npcSet.iterator()).thenAnswer(inv -> List.of(npcs).iterator());
        when(wv.npcs()).thenReturn(npcSet);
        when(client.getTopLevelWorldView()).thenReturn(wv);
        when(client.isClientThread()).thenReturn(true);
        return client;
    }
}
