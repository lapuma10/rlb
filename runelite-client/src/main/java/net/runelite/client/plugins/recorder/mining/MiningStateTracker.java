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
package net.runelite.client.plugins.recorder.mining;

import javax.annotation.Nullable;

/**
 * Tracks mining state for a locked rock across ticks.
 *
 * <p>The mining loop polls this tracker per tick to decide state transitions.
 * It's a tiny finite state machine — it remembers the last few ticks of
 * animation/composition observations so a transient hiccup (one tick of
 * "no action visible" while the engine swaps the comp) doesn't bounce the
 * loop between SWINGING and SELECTING.
 *
 * <p>Inputs are fed in by the loop on the client thread; the tracker does
 * not call into the client API itself, which makes it trivial to unit-test
 * with hand-built scenarios.
 *
 * <p>See {@code 2026-04-26-mining-routine.md} for the design rationale —
 * notably "animation as the source of truth" and the >=2-tick debounce on
 * "animation dropped + rock still alive" before re-clicking.
 */
public final class MiningStateTracker
{
    private final int initialObjectId;
    private int currentObjectId;
    private int animationDroppedTicks = 0;
    private boolean everSwung = false;
    private boolean depleted = false;
    private boolean inventoryFull = false;

    public MiningStateTracker(int initialObjectId)
    {
        this.initialObjectId = initialObjectId;
        this.currentObjectId = initialObjectId;
    }

    /**
     * Update with a fresh per-tick snapshot.
     *
     * @param playerAnimationId  the local player's current animation id, or -1
     *                           if no animation. Compared against
     *                           {@link OreType#MINING_ANIMATIONS}.
     * @param liveObjectId       the object id currently on the rock's tile,
     *                           or -1 / null if no GameObject is on the tile
     *                           (rock vanished). The caller resolves this via
     *                           {@code TransportResolver.findTransport(tile, "Mine")}
     *                           — a non-null match means the rock is alive
     *                           (its composition still advertises "Mine").
     * @param verbStillAvailable whether the tile's GameObject still has the
     *                           "Mine" verb in its composition. False after
     *                           the engine swaps to a depleted variant.
     * @param invFull            true when {@code inventory.count() >= 28}.
     */
    public void observe(int playerAnimationId, int liveObjectId,
                        boolean verbStillAvailable, boolean invFull)
    {
        if (depleted)
        {
            // sticky — a depleted rock stays depleted from this tracker's POV.
            // The loop should release the lock after seeing depleted=true and
            // create a fresh tracker for the next target.
            inventoryFull = invFull;
            return;
        }
        boolean swinging = OreType.isMiningAnimation(playerAnimationId);
        if (swinging)
        {
            everSwung = true;
            animationDroppedTicks = 0;
        }
        else
        {
            animationDroppedTicks++;
        }

        if (liveObjectId > 0) currentObjectId = liveObjectId;

        // Two depletion signals — either is sufficient:
        //   1. The verb "Mine" is gone from the tile's composition.
        //   2. The object id flipped from the initial — engine swapped the
        //      full variant out. Some rocks share an id family (initial+1 =
        //      depleted) but we don't care about the specifics; any change
        //      from the initial is a state transition.
        if (!verbStillAvailable) depleted = true;
        else if (currentObjectId != initialObjectId) depleted = true;

        inventoryFull = invFull;
    }

    /** True if the locked rock is currently being mined (player is in a
     *  recognised mining swing animation). */
    public boolean isMining(int playerAnimationId)
    {
        return !depleted && OreType.isMiningAnimation(playerAnimationId);
    }

    /** True if the rock is depleted (verb gone or object id flipped). Sticky.
     *  When true, the loop should release the lock and reselect. */
    public boolean isDepleted() { return depleted; }

    /** True if the inventory was full as of the last observation. */
    public boolean isInventoryFull() { return inventoryFull; }

    /** True if the player's animation has been off for at least
     *  {@code threshold} ticks AND the rock is still alive — interpreted as
     *  "engine stopped us mid-swing" (line of sight broken, walked off,
     *  another player got the ore on the same tick, etc.). The mining loop
     *  re-clicks the same rock when this flips. */
    public boolean isAnimationDropped(int threshold)
    {
        return everSwung && !depleted && animationDroppedTicks >= threshold;
    }

    /** True if we've ever observed a mining swing on this lock — used by the
     *  loop to discriminate "engine never engaged" from "engine engaged then
     *  stopped". Matters because a fresh click might not produce an animation
     *  on the same tick we click; we shouldn't immediately re-click. */
    public boolean hasEverSwung() { return everSwung; }

    public int currentObjectId() { return currentObjectId; }
    public int initialObjectId() { return initialObjectId; }

    @Nullable
    public String describe()
    {
        return "MiningStateTracker{init=" + initialObjectId + ",cur=" + currentObjectId
            + ",depleted=" + depleted + ",everSwung=" + everSwung
            + ",animDroppedTicks=" + animationDroppedTicks
            + ",invFull=" + inventoryFull + "}";
    }
}
