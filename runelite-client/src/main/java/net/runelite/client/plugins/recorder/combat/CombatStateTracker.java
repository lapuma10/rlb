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
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.recorder.combat;

import net.runelite.api.Actor;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import javax.annotation.Nullable;

/**
 * Tracks combat state for a locked NPC across ticks.
 *
 * <p>The combat loop polls this tracker per tick to decide state transitions.
 * It's a tiny finite state machine itself — it remembers the last few ticks
 * so it can detect "engagement broken for &gt;2 ticks" and "HP bar dropped to
 * zero" without bouncing between states on transient hiccups.
 *
 * <p>Inputs are passed in by the loop on the client thread; the tracker does
 * not call into the client API itself, which makes it trivial to unit-test
 * with hand-built mocks.
 */
public final class CombatStateTracker
{
    private final int npcIndex;
    private int brokenTicks = 0;
    private int zeroHpTicks = 0;
    private boolean everEngaged = false;
    private boolean dead = false;
    /** Set when {@link #observe} sees the locked NPC vanish from the world view
     *  (interpreted as "killed by someone else / despawned mid-combat"). */
    private boolean vanished = false;

    public CombatStateTracker(int npcIndex)
    {
        this.npcIndex = npcIndex;
    }

    /**
     * Update with a fresh per-tick snapshot. Pass {@code lockedNpc=null} when
     * the locked NPC is no longer present in the world view (vanished /
     * despawned). All callers must invoke this on the client thread.
     */
    public void observe(@Nullable NPC lockedNpc, @Nullable Player self)
    {
        if (dead) return;   // sticky once we've decided
        if (lockedNpc == null)
        {
            // NPC no longer in WorldView. If we never engaged, it might just
            // have despawned out of range — treat as vanished but only flag
            // dead if we actually started fighting it.
            vanished = true;
            if (everEngaged) dead = true;
            return;
        }
        if (lockedNpc.getIndex() != npcIndex)
        {
            // Index mismatch — defensive; the loop should never pass a
            // different NPC, but if it does, ignore the observation.
            return;
        }
        Actor interacting = lockedNpc.getInteracting();
        boolean engagedWithUs = interacting != null && self != null && interacting == self;
        if (engagedWithUs) everEngaged = true;

        if (engagedWithUs) brokenTicks = 0;
        else if (everEngaged) brokenTicks++;

        int hp = lockedNpc.getHealthRatio();
        if (hp == 0) zeroHpTicks++;
        else zeroHpTicks = 0;

        // Death rule: HP bar empty for at least one tick (the kill animation
        // plays on the same tick the bar drains). The +1 buffer matches the
        // spec ("wait one more tick after healthRatio==0 to let the kill
        // animation play"). Kill counts only when we ever engaged.
        if (zeroHpTicks >= 1 && everEngaged) dead = true;
    }

    /** True when we have observed at least one tick of mutual engagement. */
    public boolean wasEngaged() { return everEngaged; }

    /** True if the NPC is engaged with the local player right now. Equivalent
     *  to "engagedWithUs" from the most recent {@link #observe}. */
    public boolean isEngagedWithUs()
    {
        return everEngaged && brokenTicks == 0 && !dead && !vanished;
    }

    /** True if the NPC was once engaged with us but engagement has been lost
     *  for more than {@code threshold} ticks. Default threshold is 2 (the
     *  spec). Used to decide "re-attack the same chicken". */
    public boolean isEngagementBroken(int threshold)
    {
        return everEngaged && !dead && !vanished && brokenTicks > threshold;
    }

    /** True if the NPC is alive and present in the world. */
    public boolean isAlive()
    {
        return !dead && !vanished;
    }

    /** True if the NPC is confirmed dead (HP bar drained, or vanished from the
     *  world after we engaged). The combat loop transitions on this. */
    public boolean isDead()
    {
        return dead;
    }

    public int npcIndex() { return npcIndex; }
}
