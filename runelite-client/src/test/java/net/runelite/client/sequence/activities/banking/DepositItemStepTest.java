package net.runelite.client.sequence.activities.banking;

import net.runelite.client.sequence.SequenceState;
import net.runelite.client.sequence.WorldSnapshot;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for {@link DepositItemStep}.
 * 8e: 3 tests.
 * - zero-count already satisfied: no dispatch
 * - deposit dispatches and succeeds when inventory cleared
 * - bank closed: fatal, no dispatch
 */
public class DepositItemStepTest {

    private static final int ITEM_ID = 314; // raw shrimp

    @Test
    public void zeroCount_alreadySatisfied_noDispatch() {
        BankingEngineHarness harness = new BankingEngineHarness();
        RecordingBankActions bank = new RecordingBankActions();

        // Inventory has no raw shrimp → already satisfied
        WorldSnapshot snap = new BankSnapBuilder()
            .tick(1)
            .bankOpen(true)
            .build();  // no invItem for ITEM_ID
        harness.queue(snap, snap, snap);

        harness.run(new DepositItemStep(ITEM_ID, bank));
        harness.advance(2);

        assertEquals(SequenceState.IDLE, harness.state());
        assertTrue("No dispatch when already satisfied", bank.calls().isEmpty());
    }

    @Test
    public void depositDispatches_succeedsWhenInventoryCleared() {
        BankingEngineHarness harness = new BankingEngineHarness();
        RecordingBankActions bank = new RecordingBankActions();

        // Tick 1: has item in inventory, bank open
        WorldSnapshot hasItem  = new BankSnapBuilder()
            .tick(1)
            .bankOpen(true)
            .invItem(ITEM_ID, 5)
            .build();
        // Tick 2: item deposited (count now 0)
        WorldSnapshot itemGone = new BankSnapBuilder()
            .tick(2)
            .bankOpen(true)
            .build();  // no ITEM_ID in inventory
        harness.queue(hasItem, hasItem, itemGone, itemGone);

        harness.run(new DepositItemStep(ITEM_ID, bank));
        harness.advance(4);

        assertEquals(SequenceState.IDLE, harness.state());
        assertEquals("Expected depositAll dispatch", 1, bank.calls().size());
        assertEquals("depositAll(" + ITEM_ID + ")", bank.calls().get(0));
    }

    @Test
    public void bankNotOpen_fatalNoDispatch() {
        BankingEngineHarness harness = new BankingEngineHarness();
        RecordingBankActions bank = new RecordingBankActions();

        // Bank is closed but item is in inventory
        WorldSnapshot snap = new BankSnapBuilder()
            .tick(1)
            .bankOpen(false)
            .invItem(ITEM_ID, 3)
            .build();
        harness.queue(snap, snap, snap);

        harness.run(new DepositItemStep(ITEM_ID, bank));
        harness.advance(3);

        assertEquals(SequenceState.FAILED, harness.state());
        assertTrue("No dispatch when bank not open", bank.calls().isEmpty());
    }
}
