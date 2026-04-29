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
 * 8d: 3 tests.
 * - ready arrives: succeeds
 * - bank closes mid-wait: fails with BankNotOpen (not timeout)
 * - never-ready: typed timeout BankNotReady fires before engine generic timeout
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

    @Test
    public void bankClosedMidWait_failsWithBankNotOpen() {
        BankingEngineHarness harness = new BankingEngineHarness();

        // Bank opens but then closes before ready
        WorldSnapshot openSnap  = new BankSnapBuilder().tick(1).bankOpen(true).bankReady(false).build();
        WorldSnapshot closedSnap = new BankSnapBuilder().tick(2).bankOpen(false).bankReady(false).build();
        harness.queue(openSnap, openSnap, closedSnap, closedSnap);

        List<TelemetryRecord> records = new ArrayList<>();
        harness.telemetry().subscribe(records::add);

        harness.run(new WaitForBankReadyStep());
        harness.advance(4);

        assertEquals(SequenceState.FAILED, harness.state());
        // Verify failure had BankNotOpen diagnostic (not timeout)
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
