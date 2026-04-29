/*
 * Copyright (c) 2026, RuneLite. All rights reserved.
 */
package net.runelite.client.plugins.recorder.mining;

import net.runelite.api.AnimationID;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * State-machine tests for the SWINGING-state decision rule. The pure
 * helper {@code MiningLoop.nextSwingState} is what the mining loop calls
 * each tick to decide whether to keep swinging, release the lock, or
 * empty the inventory. Real-dispatcher integration is covered manually
 * via the panel; here we just verify the rule.
 */
public class MiningLoopTest
{
    private static final int RUNE = AnimationID.MINING_RUNE_PICKAXE;

    @Test
    public void swingingPersistsWhileMiningAndAlive()
    {
        MiningStateTracker t = new MiningStateTracker(11_000);
        t.observe(RUNE, 11_000, true, false);
        assertEquals("active swing must keep us in SWINGING",
            MiningLoop.State.SWINGING, MiningLoop.nextSwingState(t, 2));
    }

    @Test
    public void depletedRockTransitionsToDepleted()
    {
        MiningStateTracker t = new MiningStateTracker(11_000);
        t.observe(RUNE, 11_000, true, false);
        // Verb gone — depletion sticky.
        t.observe(-1, 11_000, false, false);
        assertEquals(MiningLoop.State.DEPLETED, MiningLoop.nextSwingState(t, 2));
    }

    @Test
    public void inventoryFullTransitionsToInventoryFull()
    {
        MiningStateTracker t = new MiningStateTracker(11_000);
        t.observe(RUNE, 11_000, true, true);
        assertEquals(MiningLoop.State.INVENTORY_FULL, MiningLoop.nextSwingState(t, 2));
    }

    @Test
    public void animationDroppedAndAliveTransitionsToSelecting()
    {
        MiningStateTracker t = new MiningStateTracker(11_000);
        t.observe(RUNE, 11_000, true, false);
        // Two ticks of no animation, rock still alive.
        t.observe(-1, 11_000, true, false);
        t.observe(-1, 11_000, true, false);
        assertEquals(MiningLoop.State.SELECTING, MiningLoop.nextSwingState(t, 2));
    }

    @Test
    public void powerMineStrategyLabel()
    {
        // Sanity — strategy interface contract. BankDepositStrategy ctor
        // grew real dependencies after the cooking-worktree merge so the
        // no-arg test removed; PowerMineStrategy is still parameterless.
        assertEquals("PowerMine", new PowerMineStrategy().label());
    }
}
