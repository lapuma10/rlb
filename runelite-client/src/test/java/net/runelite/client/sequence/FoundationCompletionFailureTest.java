package net.runelite.client.sequence;

import net.runelite.client.sequence.affordance.DiagnosticReason;
import net.runelite.client.sequence.affordance.GeBlockReason;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** F1 — Completion / Failure diagnostic plumbing. */
public class FoundationCompletionFailureTest {

    @Test
    public void completionFailedFactoryCarriesDiagnostic() {
        DiagnosticReason r = new GeBlockReason.GeNotOpen();
        Completion.Failed f = Completion.failed(r);
        assertSame(r, f.diagnostic());
        assertNotNull(f.reason());                          // toString of diagnostic
    }

    @Test
    public void legacyCompletionFailedConstructorHasNullDiagnostic() {
        Completion.Failed f = new Completion.Failed("legacy");
        assertNull(f.diagnostic());
        assertEquals("legacy", f.reason());
    }

    @Test
    public void failureFromDiagnosticRoundTrips() {
        DiagnosticReason r = new GeBlockReason.InsufficientCoins(1000, 500);
        Failure f = Failure.fromDiagnostic(r, 3);
        assertSame(r, f.diagnostic());
        assertEquals(3, f.ticksElapsed());
        assertNotNull(f.reason());
    }

    @Test
    public void legacyFailureFactoriesHaveNullDiagnostic() {
        assertNull(Failure.timeout(5).diagnostic());
        assertNull(Failure.fromCheck("nope", 2).diagnostic());
        assertNull(Failure.fromException(new RuntimeException("x"), 1).diagnostic());
    }

    @Test
    public void completionFailedTwoArgConstructorPreservesDiagnostic() {
        DiagnosticReason r = new DiagnosticReason.Unknown("oops");
        Completion.Failed f = new Completion.Failed("oops", r);
        assertSame(r, f.diagnostic());
        assertTrue(f.reason().contains("oops"));
    }
}
