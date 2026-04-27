/*
 * Copyright (c) 2026, RuneLite. All rights reserved.
 */
package net.runelite.client.plugins.recorder.mining;

import net.runelite.api.AnimationID;
import org.junit.Test;
import static org.junit.Assert.*;

public class MiningStateTrackerTest
{
    private static final int RUNE_ANIM = AnimationID.MINING_RUNE_PICKAXE;
    private static final int IDLE_ANIM = -1;

    @Test
    public void miningAnimationFlipsIsMining()
    {
        MiningStateTracker t = new MiningStateTracker(11_000);
        t.observe(RUNE_ANIM, 11_000, true, false);
        assertTrue(t.isMining(RUNE_ANIM));
        assertFalse(t.isDepleted());
    }

    @Test
    public void verbDisappearsMarksDepleted()
    {
        MiningStateTracker t = new MiningStateTracker(11_000);
        t.observe(RUNE_ANIM, 11_000, true, false);
        // Engine swaps the comp; verb no longer present.
        t.observe(IDLE_ANIM, 11_000, false, false);
        assertTrue(t.isDepleted());
    }

    @Test
    public void objectIdFlipMarksDepleted()
    {
        MiningStateTracker t = new MiningStateTracker(11_000);
        t.observe(RUNE_ANIM, 11_000, true, false);
        // Engine swaps the underlying object id (full → spent variant) but
        // we still see *some* object on the tile that briefly has the verb;
        // id change alone is sufficient to mark depleted.
        t.observe(IDLE_ANIM, 11_001, true, false);
        assertTrue(t.isDepleted());
    }

    @Test
    public void animationDroppedFlagsAfterThreshold()
    {
        MiningStateTracker t = new MiningStateTracker(11_000);
        // Tick 1: swinging.
        t.observe(RUNE_ANIM, 11_000, true, false);
        // 2 ticks of no animation, rock still alive — should flag at >=2.
        t.observe(IDLE_ANIM, 11_000, true, false);
        t.observe(IDLE_ANIM, 11_000, true, false);
        assertTrue("animation off >=2 ticks should flag",
            t.isAnimationDropped(2));
        assertFalse(t.isDepleted());
    }

    @Test
    public void inventoryFullFlagPropagates()
    {
        MiningStateTracker t = new MiningStateTracker(11_000);
        t.observe(RUNE_ANIM, 11_000, true, false);
        assertFalse(t.isInventoryFull());
        t.observe(RUNE_ANIM, 11_000, true, true);
        assertTrue(t.isInventoryFull());
    }
}
