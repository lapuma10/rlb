package net.runelite.client.sequence.activities.banking;

import net.runelite.client.sequence.SequenceState;
import net.runelite.client.sequence.WorldSnapshot;
import net.runelite.client.sequence.affordance.BlockReason;
import net.runelite.client.sequence.telemetry.TelemetryRecord;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Tests for {@link WaitForBankReadyStep}.
 * - ready arrives: succeeds
 * - widget never opens (stale container from prior visit): waits up
 *   to the typed timeout, then fails with BankNotOpen
 * - widget opens but ready never arrives: typed timeout BankNotReady
 */
public class WaitForBankReadyStepTest {

    @Test
    public void bankReady_succeeds() {
        BankingEngineHarness harness = new BankingEngineHarness();

        WorldSnapshot openNotReady = new BankSnapBuilder().tick(1).bankOpen(true).bankReady(false).build();
        WorldSnapshot openReady    = new BankSnapBuilder().tick(2).bankOpen(true).bankReady(true).build();
        harness.queue(openNotReady, openNotReady, openReady, openReady);

        harness.run(new WaitForBankReadyStep());
        harness.advance(4);

        assertEquals(SequenceState.IDLE, harness.state());
    }

    /** Regression: a fresh booth click hasn't yet rendered the bank
     *  widget, but the BANK ItemContainer is sticky from a previous
     *  visit (CooksAssistant withdraws → close, then GE bank-prep
     *  re-opens). Old logic returned SUCCEEDED on tick 1 because
     *  ready=true (stale container), and the next step then read
     *  open=false and failed with BankNotOpen. New logic gates on
     *  open() FIRST and only succeeds when the widget is actually up. */
    @Test
    public void staleContainerWithoutOpenWidget_doesNotShortCircuit() {
        BankingEngineHarness harness = new BankingEngineHarness();

        // Container is sticky (ready=true) but widget hasn't rendered yet
        // for the new session. Then the widget appears.
        WorldSnapshot stuckClosed = new BankSnapBuilder().tick(1).bankOpen(false).bankReady(true).build();
        WorldSnapshot openReady   = new BankSnapBuilder().tick(2).bankOpen(true).bankReady(true).build();
        harness.queue(stuckClosed, stuckClosed, openReady, openReady);

        harness.run(new WaitForBankReadyStep());
        harness.advance(4);

        // We should still succeed eventually (when the widget appears),
        // not bail with BankNotOpen immediately on the stale-container tick.
        assertEquals(SequenceState.IDLE, harness.state());
    }

    @Test
    public void bankClosedMidWait_failsWithBankNotOpenAtTimeout() {
        BankingEngineHarness harness = new BankingEngineHarness();

        // Bank opens but then closes before ready, and never re-opens.
        // New logic waits the full typed timeout (10 ticks) before
        // failing — gives the widget a chance to come back, then
        // surfaces the typed BankNotOpen reason.
        WorldSnapshot openSnap   = new BankSnapBuilder().tick(1).bankOpen(true).bankReady(false).build();
        List<WorldSnapshot> snaps = new ArrayList<>();
        snaps.add(openSnap);
        for (int i = 2; i <= 14; i++) {
            snaps.add(new BankSnapBuilder().tick(i).bankOpen(false).bankReady(false).build());
        }
        harness.queue(snaps.toArray(new WorldSnapshot[0]));

        List<TelemetryRecord> records = new ArrayList<>();
        harness.telemetry().subscribe(records::add);

        harness.run(new WaitForBankReadyStep());
        harness.advance(13);

        assertEquals(SequenceState.FAILED, harness.state());
        boolean foundBankNotOpen = records.stream()
            .filter(r -> r.event() == TelemetryRecord.Event.RECOVERY || r.event() == TelemetryRecord.Event.FAILED)
            .anyMatch(r -> r.payload().contains("BankNotOpen") || r.payload().contains("Abort"));
        assertTrue("Expected BankNotOpen failure path", foundBankNotOpen);
    }

    @Test
    public void neverReady_typedTimeoutBankNotReady_notEngineGeneric() {
        BankingEngineHarness harness = new BankingEngineHarness();

        // Bank stays open but never becomes ready — should produce typed BankNotReady,
        // not the engine's generic Failure.timeout (engine timeout is also 10 ticks)
        // The step fires its typed timeout at timeoutTicks=10, same tick — check fires first.
        WorldSnapshot openNotReady = new BankSnapBuilder().tick(1).bankOpen(true).bankReady(false).build();

        // We need to feed enough ticks: startTick=1, then tick 11 → tick-startTick=10 >= 10
        List<WorldSnapshot> snaps = new ArrayList<>();
        for (int i = 0; i <= 12; i++) {
            snaps.add(new BankSnapBuilder().tick(i + 1).bankOpen(true).bankReady(false).build());
        }
        harness.queue(snaps.toArray(new WorldSnapshot[0]));

        List<TelemetryRecord> records = new ArrayList<>();
        harness.telemetry().subscribe(records::add);

        harness.run(new WaitForBankReadyStep());
        harness.advance(12);

        assertEquals(SequenceState.FAILED, harness.state());
        // Assert that the failure carries the typed BankNotReady diagnostic, not the generic timeout
        boolean foundTypedTimeout = records.stream()
            .anyMatch(r -> r.payload() != null && r.payload().contains("BankNotReady"));
        assertTrue("Expected typed BankNotReady diagnostic (not engine generic timeout)", foundTypedTimeout);
    }
}
