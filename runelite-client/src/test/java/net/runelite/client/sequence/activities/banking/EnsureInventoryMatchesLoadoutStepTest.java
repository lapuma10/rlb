package net.runelite.client.sequence.activities.banking;

import net.runelite.client.sequence.SequenceState;
import net.runelite.client.sequence.WorldSnapshot;
import net.runelite.client.sequence.telemetry.TelemetryRecord;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Tests for {@link EnsureInventoryMatchesLoadoutStep}.
 * 8g: 2 tests.
 * - matching loadout: succeeds
 * - missing tinderbox: LoadoutMismatch diagnostic
 */
public class EnsureInventoryMatchesLoadoutStepTest {

    private static final int RAW_SHRIMP_ID = 317;
    private static final int TINDERBOX_ID  = 590;

    @Test
    public void matchingLoadout_succeeds() {
        BankingEngineHarness harness = new BankingEngineHarness();

        // Loadout: at least 1 tinderbox (non-exact) + exactly 5 raw shrimp
        Loadout loadout = new Loadout(List.of(
            new Loadout.Slot(TINDERBOX_ID, 1, false),  // non-exact: have >= 1
            new Loadout.Slot(RAW_SHRIMP_ID, 5, true)   // exact: have == 5
        ));

        WorldSnapshot snap = new BankSnapBuilder()
            .tick(1)
            .invItem(TINDERBOX_ID, 1)
            .invItem(RAW_SHRIMP_ID, 5)
            .build();
        harness.queue(snap, snap, snap);

        harness.run(new EnsureInventoryMatchesLoadoutStep(loadout));
        harness.advance(2);

        assertEquals(SequenceState.IDLE, harness.state());
    }

    @Test
    public void missingTinderbox_loadoutMismatchDiagnostic() {
        BankingEngineHarness harness = new BankingEngineHarness();

        // Loadout requires tinderbox but inventory has none
        Loadout loadout = new Loadout(List.of(
            new Loadout.Slot(TINDERBOX_ID, 1, false)
        ));

        WorldSnapshot snap = new BankSnapBuilder()
            .tick(1)
            .build();  // no tinderbox in inventory
        harness.queue(snap, snap, snap);

        List<TelemetryRecord> records = new ArrayList<>();
        harness.telemetry().subscribe(records::add);

        harness.run(new EnsureInventoryMatchesLoadoutStep(loadout));
        harness.advance(3);

        assertEquals(SequenceState.FAILED, harness.state());
        boolean hasLoadoutMismatch = records.stream()
            .anyMatch(r -> r.payload() != null && r.payload().contains("LoadoutMismatch"));
        assertTrue("Expected LoadoutMismatch diagnostic for missing tinderbox", hasLoadoutMismatch);
    }
}
