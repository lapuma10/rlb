package net.runelite.client.plugins.recorder.farm;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression test for the Phase B P0 deadlock: when a sequence-engine tick
 * drives a banking step on the client thread (as Phase B GE bank-prep does
 * via {@code clientThread.invokeLater(advanceTick)}), the inner
 * {@code BankInteraction.onClient(...)} helper must NOT queue + latch on
 * the client thread — that would deadlock since the queue can only drain
 * after the current invoke loop returns.
 *
 * <p>The fix short-circuits {@code onClient(...)} when
 * {@code client.isClientThread()} is true, mirroring the patterns in
 * {@code HumanizedInputDispatcher.onClient} and
 * {@code ClientObserver.onClient}. This test verifies the short-circuit by
 * driving a public method that funnels through the helper from a thread
 * where {@code isClientThread()} returns true: the supplier runs inline and
 * the call returns within the 2s latch timeout (we use 1s to make a
 * deadlock surface as a test failure rather than hanging the whole suite).
 */
public class BankInteractionClientThreadTest {

    @Test(timeout = 1000)
    public void onClient_shortCircuits_whenAlreadyOnClientThread() throws Exception {
        Client client = mock(Client.class);
        when(client.isClientThread()).thenReturn(true);

        ClientThread clientThread = mock(ClientThread.class);   // would never drain queue
        HumanizedInputDispatcher dispatcher = mock(HumanizedInputDispatcher.class);

        BankInteraction bank = new BankInteraction(client, clientThread, dispatcher);

        // Drive the private onClient helper via reflection with a known supplier
        // that returns "OK" — verifies the helper runs the supplier inline rather
        // than queuing via clientThread.invokeLater (which would never drain since
        // the mock ClientThread does nothing).
        Method onClient = BankInteraction.class.getDeclaredMethod(
            "onClient", java.util.function.Supplier.class);
        onClient.setAccessible(true);
        AtomicBoolean ran = new AtomicBoolean();
        java.util.function.Supplier<String> supplier = () -> { ran.set(true); return "OK"; };
        Object result = onClient.invoke(bank, supplier);
        assertNotNull("onClient must return inline result on client thread", result);
        org.junit.Assert.assertEquals("OK", result);
        assertFalse("test would have hung if helper queued via invokeLater",
            !ran.get());
    }

    @Test(timeout = 1000)
    public void onClient_returnsNull_whenSupplierThrowsInline() throws Exception {
        Client client = mock(Client.class);
        when(client.isClientThread()).thenReturn(true);
        ClientThread clientThread = mock(ClientThread.class);
        HumanizedInputDispatcher dispatcher = mock(HumanizedInputDispatcher.class);
        BankInteraction bank = new BankInteraction(client, clientThread, dispatcher);

        Method onClient = BankInteraction.class.getDeclaredMethod(
            "onClient", java.util.function.Supplier.class);
        onClient.setAccessible(true);
        java.util.function.Supplier<String> thrower = () -> {
            throw new RuntimeException("oops");
        };
        Object result = onClient.invoke(bank, thrower);
        org.junit.Assert.assertNull(
            "inline supplier exceptions must be swallowed (warn-logged) and yield null",
            result);
    }
}
