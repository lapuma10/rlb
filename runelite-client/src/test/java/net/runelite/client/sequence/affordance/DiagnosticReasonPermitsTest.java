package net.runelite.client.sequence.affordance;

import net.runelite.api.coords.WorldArea;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class DiagnosticReasonPermitsTest {

    /**
     * Exhaustive switch — if the sealed hierarchy is correctly declared,
     * the compiler accepts this without a default branch and javac in pattern-
     * matching mode would (post-Java-21) catch an unhandled subtype. For the
     * Java 17 target we use instanceof patterns so the spirit is preserved:
     * every concrete reason is reachable as a {@link DiagnosticReason}.
     */
    @Test
    public void blockReasonAndGeBlockReasonRoutedAsDiagnosticReason() {
        DiagnosticReason a = new BlockReason.PinKeypadUp();
        DiagnosticReason b = new GeBlockReason.GeNotOpen();
        DiagnosticReason c = new DiagnosticReason.Loading();
        DiagnosticReason d = new DiagnosticReason.ActionTimedOut("OpenGE", 30);
        DiagnosticReason e = new DiagnosticReason.Unknown("oops");
        DiagnosticReason f = new BlockReason.NotAtLocation(new WorldArea(0, 0, 1, 1, 0));

        assertEquals("PinKeypadUp", route(a));
        assertEquals("GeNotOpen", route(b));
        assertEquals("Loading", route(c));
        assertEquals("ActionTimedOut(OpenGE,30)", route(d));
        assertEquals("Unknown(oops)", route(e));
        assertEquals("NotAtLocation", route(f));
    }

    private static String route(DiagnosticReason r) {
        if (r instanceof BlockReason.PinKeypadUp) return "PinKeypadUp";
        if (r instanceof BlockReason.WorldInteractionBlocked) return "WorldInteractionBlocked";
        if (r instanceof BlockReason.NotAtLocation) return "NotAtLocation";
        if (r instanceof GeBlockReason.GeNotOpen) return "GeNotOpen";
        if (r instanceof GeBlockReason) return "GeBlockReason(other)";
        if (r instanceof DiagnosticReason.Loading) return "Loading";
        if (r instanceof DiagnosticReason.ActionTimedOut at) {
            return "ActionTimedOut(" + at.stepName() + "," + at.ticks() + ")";
        }
        if (r instanceof DiagnosticReason.Unknown u) return "Unknown(" + u.detail() + ")";
        return "?";
    }
}
