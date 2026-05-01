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

import net.runelite.api.NPC;
import net.runelite.api.Player;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class CombatStateTrackerTest
{
    private static NPC mockNpcWith(int idx, int hp, Player interacting)
    {
        NPC n = mock(NPC.class);
        when(n.getIndex()).thenReturn(idx);
        when(n.getHealthRatio()).thenReturn(hp);
        when(n.getInteracting()).thenReturn(interacting);
        return n;
    }

    @Test
    public void engagedThenDeath_flipsThroughExpectedFlags()
    {
        Player self = mock(Player.class);
        CombatStateTracker t = new CombatStateTracker(7);
        // Tick 1: engaged, full HP
        t.observe(mockNpcWith(7, 30, self), self);
        assertTrue("should be engaged with us after first observe", t.isEngagedWithUs());
        assertTrue(t.isAlive());
        assertFalse(t.isDead());
        // Tick 2: HP zero — kill animation; tracker flips dead.
        t.observe(mockNpcWith(7, 0, self), self);
        assertTrue(t.isDead());
        assertFalse(t.isAlive());
    }

    @Test
    public void playerTargetingLockedNpc_countsAsEngaged()
    {
        Player self = mock(Player.class);
        NPC lockedNpc = mockNpcWith(23, 30, null);
        when(self.getInteracting()).thenReturn(lockedNpc);

        CombatStateTracker t = new CombatStateTracker(23);
        t.observe(lockedNpc, self);

        assertTrue("player-side interaction alone should count as engagement",
            t.isEngagedWithUs());
        assertTrue(t.wasEngaged());
        assertFalse(t.isDead());
    }

    @Test
    public void engagementBroken_lessThanThreshold_isNotBroken()
    {
        Player self = mock(Player.class);
        CombatStateTracker t = new CombatStateTracker(5);
        t.observe(mockNpcWith(5, 30, self), self);
        // Lose interacting for 1 tick — should not yet flag broken (>2 spec).
        t.observe(mockNpcWith(5, 30, null), self);
        assertFalse(t.isEngagementBroken(2));
        // Restore interaction — counter resets.
        t.observe(mockNpcWith(5, 30, self), self);
        assertTrue(t.isEngagedWithUs());
        assertFalse(t.isEngagementBroken(2));
    }

    @Test
    public void engagementBroken_overThreshold_flagsBroken()
    {
        Player self = mock(Player.class);
        CombatStateTracker t = new CombatStateTracker(9);
        t.observe(mockNpcWith(9, 30, self), self);
        // Three ticks without engagement after first engagement — exceeds the
        // ">2 ticks" rule.
        t.observe(mockNpcWith(9, 30, null), self);
        t.observe(mockNpcWith(9, 30, null), self);
        t.observe(mockNpcWith(9, 30, null), self);
        assertTrue(t.isEngagementBroken(2));
        assertFalse(t.isDead());
    }

    @Test
    public void playerOnlyEngagement_thenLostTarget_overThreshold_flagsBroken()
    {
        Player self = mock(Player.class);
        NPC lockedNpc = mockNpcWith(25, 30, null);
        when(self.getInteracting()).thenReturn(lockedNpc, null, null, null);

        CombatStateTracker t = new CombatStateTracker(25);
        t.observe(lockedNpc, self);
        t.observe(mockNpcWith(25, 30, null), self);
        t.observe(mockNpcWith(25, 30, null), self);
        t.observe(mockNpcWith(25, 30, null), self);

        assertTrue("one-sided engagement must still break after >2 lost ticks",
            t.isEngagementBroken(2));
        assertFalse(t.isDead());
    }

    @Test
    public void vanishedAfterEngagement_isDead()
    {
        Player self = mock(Player.class);
        CombatStateTracker t = new CombatStateTracker(11);
        t.observe(mockNpcWith(11, 30, self), self);
        // NPC no longer in WorldView — treated as killed (someone else KO'd it
        // or it despawned mid-combat).
        t.observe(null, self);
        assertTrue(t.isDead());
    }

    @Test
    public void neverEngaged_npcLockedOnAnotherPlayer_flagsStolenAfterThreshold()
    {
        Player self = mock(Player.class);
        Player otherPlayer = mock(Player.class);
        CombatStateTracker t = new CombatStateTracker(13);
        // Tick 1: chicken is interacting with another player. We've never
        // engaged it. One observation is below threshold — don't bail yet.
        t.observe(mockNpcWith(13, 30, otherPlayer), self);
        assertFalse("one observation below threshold should not flag stolen",
            t.isStolen(1));
        // Tick 2: still locked onto the other player. Threshold passed.
        t.observe(mockNpcWith(13, 30, otherPlayer), self);
        assertTrue("two consecutive engaged-by-other observations should flag stolen",
            t.isStolen(1));
    }

    @Test
    public void engagedThenObservedOtherPlayer_doesNotFlagStolen()
    {
        Player self = mock(Player.class);
        Player otherPlayer = mock(Player.class);
        CombatStateTracker t = new CombatStateTracker(17);
        // Mutual engagement first — this is OUR chicken.
        t.observe(mockNpcWith(17, 30, self), self);
        // Now the chicken's interaction transiently shows another player
        // (e.g. multi-combat AoE, or stale tick). We've already engaged so
        // this is not a kill steal — must not bail.
        t.observe(mockNpcWith(17, 30, otherPlayer), self);
        t.observe(mockNpcWith(17, 30, otherPlayer), self);
        assertFalse("post-engagement other-player observations are not stolen",
            t.isStolen(1));
    }

    @Test
    public void mutualEngagementResetsEngagedByOtherCounter()
    {
        Player self = mock(Player.class);
        Player otherPlayer = mock(Player.class);
        CombatStateTracker t = new CombatStateTracker(21);
        // One tick of "other player has it" — counter at 1.
        t.observe(mockNpcWith(21, 30, otherPlayer), self);
        // Mutual engagement establishes — must clear the engaged-by-other
        // counter so subsequent ticks don't accumulate as if the chicken
        // were still being stolen.
        t.observe(mockNpcWith(21, 30, self), self);
        t.observe(mockNpcWith(21, 30, otherPlayer), self);
        assertFalse("mutual engagement clears engaged-by-other counter",
            t.isStolen(1));
    }
}
