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
        // We run the loop via the package-private hooks, not via start() —
        // start() spawns a thread which would call doEngage with a 5s wait
        // for engagement; we want a synchronous unit test.
        // Phase 1 (doSelect): self & chicken both un-targeted so
        // detectActiveChickenCombat falls through to a fresh pick.
        when(self.getInteracting()).thenReturn(null);
        when(chicken.getInteracting()).thenReturn(null);

        ChickenCombatLoop loop = new ChickenCombatLoop(dispatcher, client, null,
            null,
            new NpcSelector(ChickenCombatLoop.CHICKEN_NAME),
            TargetVisibility.alwaysVisible(), s -> {});
        boolean picked = loop.doSelect();
        assertTrue("doSelect should pick the available chicken", picked);
        assertEquals(ChickenCombatLoop.State.ENGAGING, loop.state());
        assertNotNull(loop.currentTarget());
        assertEquals(42, loop.currentTarget().npcIndex());

        // Phase 2 (doEngage): pre-flight reads npc.getInteracting() ONCE
        // and would short-circuit to ALREADY_OURS if it already saw mutual,
        // skipping the click. Sequence: null (preflight → READY → dispatch)
        // then self (wait loop sees mutual → IN_COMBAT). doEngage's wait
        // loop now requires this strict mutual signal (was lenient: accepted
        // self.getInteracting()==npc alone, which is sticky and caused
        // 2-minute IN_COMBAT hangs).
        when(chicken.getInteracting()).thenReturn(null, self);
        loop.doEngage();
        ArgumentCaptor<ActionRequest> cap = ArgumentCaptor.forClass(ActionRequest.class);
        verify(dispatcher, times(1)).dispatch(cap.capture());
        ActionRequest req = cap.getValue();
        assertEquals(ActionRequest.Kind.CLICK_NPC, req.getKind());
        assertEquals(42, req.getNpcIndex());
        assertEquals(ChickenCombatLoop.State.IN_COMBAT, loop.state());
    }

    @Test
    public void doSelect_oneSidedSelfTargeting_doesNotAdopt_fallsThroughToPick()
    {
        // self.getInteracting()==chicken with chicken.getInteracting()==null is
        // the post-kill stale-pointer case. Earlier behaviour adopted that as
        // active combat and skipped straight to IN_COMBAT, which deadlocked
        // the bot for 60+s on a chicken it had never actually attacked. We
        // now require mutual engagement to adopt; one-sided self-pointing
        // falls through to the normal selector pick → ENGAGING (and the
        // attack click goes out from doEngage, not from here).
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
        assertEquals("must NOT adopt one-sided self-targeting — sticky pointer",
            ChickenCombatLoop.State.ENGAGING, loop.state());
        assertNotNull(loop.currentTarget());
        assertEquals(44, loop.currentTarget().npcIndex());
        verify(dispatcher, never()).dispatch(any());
    }

    @Test
    public void doSelect_mutualEngagement_adoptsCombatWithoutDispatch()
    {
        // Both directions set: self.getInteracting()==chicken AND
        // chicken.getInteracting()==self. This is real, live combat that
        // should be adopted without an extra Attack click.
        Player self = mock(Player.class);
        NPC chicken = mockChicken(44, 3231, 3296, 0);
        when(self.getInteracting()).thenReturn(chicken);
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
        assertEquals(44, loop.currentTarget().npcIndex());
        verify(dispatcher, never()).dispatch(any());
    }

    @Test
    public void doSelect_chickenAlreadyTargetingPlayer_adoptsCombat()
    {
        // Engine-side flipped first: chicken.getInteracting()==self even though
        // self.getInteracting() hasn't caught up yet. Adoption is still safe
        // here because the chicken-side pointer is the truth-tell — it only
        // gets set when an NPC has acquired a target server-side.
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
    public void inCombatTick_phantomCombat_releasesLockAndReSelects()
    {
        // The doEngage strict-mutual fix gates IN_COMBAT entry on
        // npc.getInteracting()==self, but the FIRST tracker observe runs
        // ~600ms later — by then the live pointer may have already cleared.
        // If self.getInteracting() is still sticky on the chicken AND
        // chicken-side never confirms in subsequent ticks, the existing
        // tracker logic suppresses brokenTicks (sticky-self branch keyed off
        // !chickenEverTargetedUs), so engagement-broken would never fire.
        // Phantom-combat catches this: 5 ticks of no-mutual + no-anim +
        // no-HP-bar releases the lock and re-selects.
        Player self = mock(Player.class);
        NPC chicken = mockChicken(60, 3231, 3296, 0);
        Client client = clientWith(self, new WorldPoint(3230, 3296, 0), chicken);
        CombatDispatcher dispatcher = mock(CombatDispatcher.class);
        ChickenCombatLoop loop = new ChickenCombatLoop(dispatcher, client, null,
            null,
            new NpcSelector(ChickenCombatLoop.CHICKEN_NAME),
            TargetVisibility.alwaysVisible(), s -> {});
        loop.setTargetForTesting(new CombatTarget(60, "Chicken", 0));
        loop.setStateForTesting(ChickenCombatLoop.State.IN_COMBAT);
        CombatStateTracker tracker = new CombatStateTracker(60);
        CombatTarget ct = loop.currentTarget();

        // Sticky self-pointer at the chicken (post-engage residue) but the
        // chicken never targets us back, never gets hit, never plays an
        // attack animation. This is the exact phantom-IN_COMBAT scenario.
        when(self.getInteracting()).thenReturn(chicken);
        when(chicken.getInteracting()).thenReturn(null);
        when(chicken.getHealthRatio()).thenReturn(-1);
        when(self.getAnimation()).thenReturn(-1);

        // 6 ticks of phantom signals — phantom threshold is >5, so the 6th
        // tick is the first that should fire.
        for (int i = 0; i < 5; i++)
        {
            assertFalse("tick " + (i + 1) + " of silence — should not yet fire",
                loop.combatTick(ct, tracker));
            assertEquals(ChickenCombatLoop.State.IN_COMBAT, loop.state());
        }
        assertTrue("phantom-combat must fire after threshold", loop.combatTick(ct, tracker));
        assertEquals(ChickenCombatLoop.State.SELECTING, loop.state());
        assertNull("lock must be released on phantom combat", loop.currentTarget());
        assertEquals("phantom does NOT credit a kill", 0, loop.killCount());
        verify(dispatcher, never()).dispatch(any());
    }

    @Test
    public void inCombatTick_phantomReset_byPlayerAnimation_doesNotFire()
    {
        // If the player swings even once, the phantom counter resets — we ARE
        // in combat, just between server ticks. Same sticky-self-pointer
        // setup as the firing test, but a single non-idle animation reading
        // mid-stretch keeps the counter under the threshold indefinitely.
        Player self = mock(Player.class);
        NPC chicken = mockChicken(61, 3231, 3296, 0);
        Client client = clientWith(self, new WorldPoint(3230, 3296, 0), chicken);
        CombatDispatcher dispatcher = mock(CombatDispatcher.class);
        ChickenCombatLoop loop = new ChickenCombatLoop(dispatcher, client, null,
            null,
            new NpcSelector(ChickenCombatLoop.CHICKEN_NAME),
            TargetVisibility.alwaysVisible(), s -> {});
        loop.setTargetForTesting(new CombatTarget(61, "Chicken", 0));
        loop.setStateForTesting(ChickenCombatLoop.State.IN_COMBAT);
        CombatStateTracker tracker = new CombatStateTracker(61);
        CombatTarget ct = loop.currentTarget();

        when(self.getInteracting()).thenReturn(chicken);
        when(chicken.getInteracting()).thenReturn(null);
        when(chicken.getHealthRatio()).thenReturn(-1);
        // -1, -1, 422 (swing!), -1, -1, -1, -1 — single reset at tick 3.
        when(self.getAnimation()).thenReturn(-1, -1, 422, -1, -1, -1, -1);

        for (int i = 0; i < 7; i++)
        {
            assertFalse("animation reset keeps phantom counter under threshold (tick " + i + ")",
                loop.combatTick(ct, tracker));
        }
        assertEquals(ChickenCombatLoop.State.IN_COMBAT, loop.state());
        assertNotNull(loop.currentTarget());
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
    public void doEngage_menuMissingAttack_appliesCooldownAndSkipsOnNextSelect()
    {
        // Engine refused to offer 'Attack' for this chicken — closed gate /
        // fence / occlusion. Without the cooldown the next SELECTING tick
        // re-picks the same NPC and we loop forever (this is exactly the
        // 17:10:01–17:10:09 cascade in the production logs).
        Player self = mock(Player.class);
        NPC unreachable = mockChicken(2878, 3234, 3296, 0);
        NPC alternate = mockChicken(2879, 3232, 3296, 0);
        Client client = clientWith(self, new WorldPoint(3236, 3297, 0), unreachable, alternate);
        CombatDispatcher dispatcher = mock(CombatDispatcher.class);
        when(dispatcher.isBusy()).thenReturn(false);
        when(dispatcher.lastErrorMessage())
            .thenReturn("menu missing 'Attack' on npc 2878");

        ChickenCombatLoop loop = new ChickenCombatLoop(dispatcher, client, null,
            null,
            new NpcSelector(ChickenCombatLoop.CHICKEN_NAME),
            TargetVisibility.alwaysVisible(), s -> {});
        loop.setTargetForTesting(new CombatTarget(2878, "Chicken", 0));
        loop.setStateForTesting(ChickenCombatLoop.State.ENGAGING);

        loop.doEngage();
        assertEquals(ChickenCombatLoop.State.SELECTING, loop.state());
        assertNull("lock must be released after engage failure", loop.currentTarget());

        // Next selection must NOT pick the unreachable chicken even though
        // it's slightly closer — the cooldown excludes it.
        when(dispatcher.lastErrorMessage()).thenReturn(null);
        assertTrue(loop.doSelect());
        assertNotNull(loop.currentTarget());
        assertEquals("should skip the unreachable chicken on cooldown",
            2879, loop.currentTarget().npcIndex());
    }

    @Test
    public void doEngage_menuMissingAttack_onWrongNpcIndex_doesNotCooldown()
    {
        // Defensive: a stale error from a previous click chain must not
        // cooldown the currently-locked NPC. Pin the matcher to the
        // expected index — different index = different chain, ignore.
        Player self = mock(Player.class);
        NPC ours = mockChicken(2878, 3234, 3296, 0);
        Client client = clientWith(self, new WorldPoint(3236, 3297, 0), ours);
        CombatDispatcher dispatcher = mock(CombatDispatcher.class);
        when(dispatcher.isBusy()).thenReturn(false);
        when(dispatcher.lastErrorMessage())
            .thenReturn("menu missing 'Attack' on npc 9999");

        ChickenCombatLoop loop = new ChickenCombatLoop(dispatcher, client, null,
            null,
            new NpcSelector(ChickenCombatLoop.CHICKEN_NAME),
            TargetVisibility.alwaysVisible(), s -> {});
        loop.setTargetForTesting(new CombatTarget(2878, "Chicken", 0));
        loop.setStateForTesting(ChickenCombatLoop.State.ENGAGING);

        loop.doEngage();
        assertEquals(ChickenCombatLoop.State.SELECTING, loop.state());

        when(dispatcher.lastErrorMessage()).thenReturn(null);
        assertTrue(loop.doSelect());
        assertEquals("stale error for a different NPC must not cool down ours",
            2878, loop.currentTarget().npcIndex());
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
