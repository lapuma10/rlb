package net.runelite.client.sequence.activities.banking;

import net.runelite.client.sequence.SequenceState;
import net.runelite.client.sequence.WorldSnapshot;
import net.runelite.client.sequence.affordance.BlockingInterface;
import org.junit.Test;

import java.util.Set;

import static org.junit.Assert.*;

/**
 * Tests for {@link OpenBankStep}.
 * 8c: 4 tests.
 * - already-open: no booth click dispatched, succeeds
 * - pin-keypad fatal: fails without dispatch
 * - non-closable blocker fatal: fails without dispatch
 * - closable+allowed blocker: canStart=false (waitable)
 */
public class OpenBankStepTest {

    private static final int DIALOG_WIDGET_ID = 233;
    private static final Set<Integer> CLOSABLE_ALLOW_LIST = Set.of(DIALOG_WIDGET_ID);

    @Test
    public void bankAlreadyOpen_noDispatch_succeeds() {
        BankingEngineHarness harness = new BankingEngineHarness();
        RecordingBankActions bank = new RecordingBankActions();

        WorldSnapshot openSnap = new BankSnapBuilder()
            .tick(1)
            .bankOpen(true)
            .build();
        harness.queue(openSnap, openSnap, openSnap);

        harness.run(new OpenBankStep(Set.of(), bank));
        harness.advance(2);

        assertEquals(SequenceState.IDLE, harness.state());
        assertTrue("No dispatch expected when bank already open", bank.calls().isEmpty());
    }

    @Test
    public void pinKeypad_fatalNoDispatch() {
        BankingEngineHarness harness = new BankingEngineHarness();
        RecordingBankActions bank = new RecordingBankActions();

        WorldSnapshot pinSnap = new BankSnapBuilder()
            .tick(1)
            .bankOpen(false)
            .bankPinUp(true)
            .build();
        harness.queue(pinSnap, pinSnap, pinSnap);

        harness.run(new OpenBankStep(Set.of(), bank));
        harness.advance(3);

        assertEquals(SequenceState.FAILED, harness.state());
        assertTrue("No dispatch expected for pin keypad fatal", bank.calls().isEmpty());
    }

    @Test
    public void nonClosableBlocker_fatalNoDispatch() {
        BankingEngineHarness harness = new BankingEngineHarness();
        RecordingBankActions bank = new RecordingBankActions();

        // blocksWorld=true, canBeClosed=false
        BlockingInterface nonClosable = new BlockingInterface("SomeModal", 999, true, false);
        WorldSnapshot snap = new BankSnapBuilder()
            .tick(1)
            .bankOpen(false)
            .blocker(nonClosable)
            .build();
        harness.queue(snap, snap, snap);

        harness.run(new OpenBankStep(Set.of(), bank));
        harness.advance(3);

        assertEquals(SequenceState.FAILED, harness.state());
        assertTrue("No dispatch expected for non-closable blocker fatal", bank.calls().isEmpty());
    }

    @Test
    public void closableAllowListedBlocker_canStartIsFalse() {
        // A closable, allow-listed blocker means canStart=false (waitable)
        BlockingInterface closableAllowed = new BlockingInterface("Dialog", DIALOG_WIDGET_ID, true, true);
        WorldSnapshot snap = new BankSnapBuilder()
            .tick(1)
            .bankOpen(false)
            .blocker(closableAllowed)
            .build();

        RecordingBankActions bank = new RecordingBankActions();
        OpenBankStep step = new OpenBankStep(CLOSABLE_ALLOW_LIST, bank);

        assertFalse("Closable allow-listed blocker → canStart must be false",
            step.canStart(snap, null));
    }
}
