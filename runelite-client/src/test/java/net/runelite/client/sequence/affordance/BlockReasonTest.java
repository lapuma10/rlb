package net.runelite.client.sequence.affordance;

import net.runelite.api.coords.WorldArea;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class BlockReasonTest {

    @Test
    public void pinKeypadUpIsBlockReasonAndDiagnosticReason() {
        BlockReason r = new BlockReason.PinKeypadUp();
        assertTrue(r instanceof DiagnosticReason);
    }

    @Test
    public void worldInteractionBlockedCarriesBlockingInterface() {
        BlockingInterface by = new BlockingInterface("level-up", 233, true, true);
        BlockReason.WorldInteractionBlocked r = new BlockReason.WorldInteractionBlocked(by);
        assertTrue(r instanceof DiagnosticReason);
        assertNotNull(r.by());
        assertTrue(r.by().canBeClosed());
    }

    @Test
    public void notAtLocationCarriesArea() {
        WorldArea area = new WorldArea(3160, 3480, 5, 5, 0);
        BlockReason.NotAtLocation r = new BlockReason.NotAtLocation(area);
        assertTrue(r instanceof DiagnosticReason);
        assertNotNull(r.required());
    }
}
