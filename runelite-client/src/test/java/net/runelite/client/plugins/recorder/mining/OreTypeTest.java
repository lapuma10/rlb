/*
 * Copyright (c) 2026, RuneLite. All rights reserved.
 */
package net.runelite.client.plugins.recorder.mining;

import net.runelite.api.AnimationID;
import org.junit.Test;
import static org.junit.Assert.*;

public class OreTypeTest
{
    @Test
    public void everyOreHasNonZeroItemIdAndPositiveLevel()
    {
        for (OreType o : OreType.values())
        {
            assertNotNull(o.displayName());
            assertFalse("name not empty: " + o, o.displayName().isBlank());
            assertTrue("oreItemId > 0: " + o, o.oreItemId() > 0);
            assertTrue("levelReq >= 1: " + o, o.levelReq() >= 1);
            assertTrue("baseXp > 0: " + o, o.baseXp() > 0);
        }
    }

    @Test
    public void miningAnimationsContainsKnownPickaxes()
    {
        assertTrue(OreType.MINING_ANIMATIONS.contains(AnimationID.MINING_BRONZE_PICKAXE));
        assertTrue(OreType.MINING_ANIMATIONS.contains(AnimationID.MINING_RUNE_PICKAXE));
        assertTrue(OreType.MINING_ANIMATIONS.contains(AnimationID.MINING_DRAGON_PICKAXE));
        assertFalse("unrelated animation should not be in set",
            OreType.MINING_ANIMATIONS.contains(0));
        assertFalse(OreType.MINING_ANIMATIONS.contains(-1));
    }

    @Test
    public void isMiningAnimationDelegates()
    {
        assertTrue(OreType.isMiningAnimation(AnimationID.MINING_RUNE_PICKAXE));
        assertFalse(OreType.isMiningAnimation(0));
        assertFalse(OreType.isMiningAnimation(-1));
    }
}
