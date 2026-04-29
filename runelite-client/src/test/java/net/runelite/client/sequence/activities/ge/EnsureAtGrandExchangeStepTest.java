package net.runelite.client.sequence.activities.ge;

import net.runelite.api.coords.WorldArea;
import net.runelite.client.sequence.SequenceState;
import net.runelite.client.sequence.WorldSnapshot;
import net.runelite.client.sequence.affordance.GeBlockReason;
import net.runelite.client.sequence.telemetry.TelemetryRecord;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class EnsureAtGrandExchangeStepTest {

    /** Varrock GE rough rectangle (close enough for tests). */
    private static final WorldArea GE_AREA = new WorldArea(3140, 3470, 30, 30, 0);

    @Test
    public void inAreaSucceeds() {
        WorldSnapshot s = new GeSnapBuilder().tick(0).player(3160, 3490, 0).build();
        GeEngineHarness h = new GeEngineHarness().queue(s, s);
        h.run(new EnsureAtGrandExchangeStep(GE_AREA));
        h.advance(2);
        assertEquals("inside the GE area should succeed", SequenceState.IDLE, h.state());
        assertEquals("no actions dispatched", 0, h.dispatcher().getRequests().size());
    }

    @Test
    public void outOfAreaAbortsWithDiagnostic() {
        WorldSnapshot s = new GeSnapBuilder().tick(0).player(3200, 3200, 0).build();
        GeEngineHarness h = new GeEngineHarness().queue(s, s, s);
        h.run(new EnsureAtGrandExchangeStep(GE_AREA));
        h.advance(3);

        assertEquals(SequenceState.FAILED, h.state());
        boolean foundReason = false;
        for (TelemetryRecord r : h.recentTelemetry()) {
            if (r.payload() != null && r.payload().contains("NotAtGrandExchange")) {
                foundReason = true;
                break;
            }
        }
        assertTrue("expected NotAtGrandExchange in telemetry, got: " + h.recentTelemetry(),
            foundReason);
    }

    @Test
    public void wrongPlaneAborts() {
        WorldSnapshot s = new GeSnapBuilder().tick(0).player(3160, 3490, 1).build();
        GeEngineHarness h = new GeEngineHarness().queue(s, s, s);
        h.run(new EnsureAtGrandExchangeStep(GE_AREA));
        h.advance(3);
        assertEquals("plane mismatch is out-of-area", SequenceState.FAILED, h.state());
    }

    @Test
    public void nullPlayerAborts() {
        // No player view (e.g., not yet logged in fully) → cannot verify location.
        WorldSnapshot s = new GeSnapBuilder().tick(0).build();
        GeEngineHarness h = new GeEngineHarness().queue(s, s);
        h.run(new EnsureAtGrandExchangeStep(GE_AREA));
        h.advance(2);
        assertEquals(SequenceState.FAILED, h.state());
    }
}
