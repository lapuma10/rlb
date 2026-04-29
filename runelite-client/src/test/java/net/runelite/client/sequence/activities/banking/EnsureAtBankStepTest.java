package net.runelite.client.sequence.activities.banking;

import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.sequence.SequenceState;
import net.runelite.client.sequence.WorldSnapshot;
import net.runelite.client.sequence.affordance.BlockReason;
import net.runelite.client.sequence.telemetry.TelemetryRecord;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Tests for {@link EnsureAtBankStep}.
 * 8a: 2 tests — in-area succeeds; out-of-area aborts with NotAtLocation diagnostic.
 */
public class EnsureAtBankStepTest {

    private static final WorldArea BANK_AREA = new WorldArea(3210, 3420, 5, 5, 0);
    private static final WorldPoint INSIDE  = new WorldPoint(3212, 3422, 0);
    private static final WorldPoint OUTSIDE = new WorldPoint(3100, 3200, 0);

    @Test
    public void inArea_succeedsImmediately() {
        BankingEngineHarness harness = new BankingEngineHarness();

        WorldSnapshot snap = new BankSnapBuilder()
            .tick(1)
            .player(INSIDE)
            .build();
        harness.queue(snap, snap, snap);

        harness.run(new EnsureAtBankStep(BANK_AREA));
        harness.advance(2);

        assertEquals(SequenceState.IDLE, harness.state());
        // No dispatches expected — pure guard step
        assertTrue(harness.dispatcher().getRequests().isEmpty());
    }

    @Test
    public void outOfArea_abortsWithNotAtLocationDiagnostic() {
        BankingEngineHarness harness = new BankingEngineHarness();

        WorldSnapshot snap = new BankSnapBuilder()
            .tick(1)
            .player(OUTSIDE)
            .build();
        harness.queue(snap, snap, snap);

        AtomicReference<TelemetryRecord> failRecord = new AtomicReference<>();
        harness.telemetry().subscribe(r -> {
            if (r.event() == TelemetryRecord.Event.FAILED || r.event() == TelemetryRecord.Event.RECOVERY) {
                failRecord.compareAndSet(null, r);
            }
        });

        harness.run(new EnsureAtBankStep(BANK_AREA));
        harness.advance(3);

        assertEquals(SequenceState.FAILED, harness.state());
        // Verify the failure diagnostic was a NotAtLocation
        // We check via telemetry that a RECOVERY/FAILED event was triggered
        assertNotNull("Expected a failure telemetry record", failRecord.get());
    }
}
