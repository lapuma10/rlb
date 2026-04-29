package net.runelite.client.sequence.activities.banking;

import net.runelite.client.sequence.SequenceState;
import net.runelite.client.sequence.WorldSnapshot;
import net.runelite.client.sequence.views.InteractionMode;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for {@link CloseBankStep}.
 * 8h: 2 tests.
 * - already-closed: satisfied immediately, no dispatch
 * - waits for world available before succeeding
 */
public class CloseBankStepTest {

    @Test
    public void bankAlreadyClosed_noDispatch_succeedsImmediately() {
        BankingEngineHarness harness = new BankingEngineHarness();
        RecordingBankActions bank = new RecordingBankActions();

        WorldSnapshot snap = new BankSnapBuilder()
            .tick(1)
            .bankOpen(false)
            .worldAvailable(true)
            .build();
        harness.queue(snap, snap, snap);

        harness.run(new CloseBankStep(bank));
        harness.advance(2);

        assertEquals(SequenceState.IDLE, harness.state());
        assertTrue("No dispatch when bank already closed", bank.calls().isEmpty());
    }

    @Test
    public void banksCloseThenWorldAvailable_succeeds() {
        BankingEngineHarness harness = new BankingEngineHarness();
        RecordingBankActions bank = new RecordingBankActions();

        // Tick 1: bank open → step dispatches closeBank
        WorldSnapshot openSnap = new BankSnapBuilder()
            .tick(1)
            .bankOpen(true)
            .worldAvailable(false)
            .mode(InteractionMode.BANKING)
            .build();

        // Tick 2: bank closed but world still not available (e.g. brief transition)
        WorldSnapshot closedNotAvailable = new BankSnapBuilder()
            .tick(2)
            .bankOpen(false)
            .worldAvailable(false)
            .build();

        // Tick 3: bank closed and world available → should succeed
        WorldSnapshot closedAvailable = new BankSnapBuilder()
            .tick(3)
            .bankOpen(false)
            .worldAvailable(true)
            .build();

        harness.queue(openSnap, openSnap, closedNotAvailable, closedAvailable, closedAvailable);

        harness.run(new CloseBankStep(bank));
        harness.advance(5);

        assertEquals(SequenceState.IDLE, harness.state());
        assertEquals("Expected closeBank dispatch", 1, bank.calls().size());
        assertEquals("closeBank()", bank.calls().get(0));
    }
}
