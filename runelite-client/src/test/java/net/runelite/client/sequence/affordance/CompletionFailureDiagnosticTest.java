package net.runelite.client.sequence.affordance;

import net.runelite.client.sequence.Completion;
import net.runelite.client.sequence.Failure;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class CompletionFailureDiagnosticTest {

    @Test
    public void completionFailedStaticFactoryRoundTrips() {
        DiagnosticReason r = new BlockReason.BankNotOpen();
        Completion.Failed failed = Completion.failed(r);
        assertSame(r, failed.diagnostic());
    }

    @Test
    public void completionFailedStaticFactoryUsesReasonToString() {
        DiagnosticReason r = new BlockReason.BankNotOpen();
        Completion.Failed failed = Completion.failed(r);
        assertEquals(r.toString(), failed.reason());
    }

    @Test
    public void legacyCompletionFailedStringConstructorHasNullDiagnostic() {
        Completion.Failed failed = new Completion.Failed("some error");
        assertNull(failed.diagnostic());
        assertEquals("some error", failed.reason());
    }

    @Test
    public void completionFailedTwoArgConstructorWorks() {
        DiagnosticReason r = new BlockReason.BankNotReady();
        Completion.Failed failed = new Completion.Failed("custom msg", r);
        assertSame(r, failed.diagnostic());
        assertEquals("custom msg", failed.reason());
    }

    @Test
    public void failureFromDiagnosticRoundTrips() {
        DiagnosticReason r = new BlockReason.InventoryFull(2);
        Failure f = Failure.fromDiagnostic(r, 7);
        assertSame(r, f.diagnostic());
        assertEquals(7, f.ticksElapsed());
        assertNull(f.cause());
    }

    @Test
    public void failureFromDiagnosticUsesReasonToString() {
        DiagnosticReason r = new BlockReason.InventoryFull(2);
        Failure f = Failure.fromDiagnostic(r, 7);
        assertEquals(r.toString(), f.reason());
    }

    @Test
    public void legacyTimeoutFactoryHasNullDiagnostic() {
        Failure f = Failure.timeout(5);
        assertNull(f.diagnostic());
        assertEquals("timeout", f.reason());
        assertEquals(5, f.ticksElapsed());
    }

    @Test
    public void legacyFromCheckFactoryHasNullDiagnostic() {
        Failure f = Failure.fromCheck("bad state", 3);
        assertNull(f.diagnostic());
        assertEquals("bad state", f.reason());
    }

    @Test
    public void legacyFromExceptionFactoryHasNullDiagnostic() {
        RuntimeException e = new RuntimeException("oops");
        Failure f = Failure.fromException(e, 2);
        assertNull(f.diagnostic());
        assertSame(e, f.cause());
    }

    @Test
    public void diagnosticCanBeBlockReason() {
        // Verify that a BlockReason flows through Failure correctly
        DiagnosticReason r = new BlockReason.BankMissingItem(995, "Coins", 1000);
        Failure f = Failure.fromDiagnostic(r, 1);
        assertTrue(f.diagnostic() instanceof BlockReason);
        assertTrue(f.diagnostic() instanceof BlockReason.BankMissingItem);
    }
}
