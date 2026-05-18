package net.runelite.client.plugins.recorder.quest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.runelite.client.callback.ClientThread;

/**
 * Threading helpers shared across quest step types.
 *
 * <p>Quest steps frequently need to read scene / widget / NPC state that
 * asserts client-thread access under {@code -ea}. The dispatcher worker
 * (where {@code RUN_TASK} bodies run) is NOT the client thread, so the
 * step has to marshal the read with a synchronous wait for the result.
 * This helper wraps the latch boilerplate.
 *
 * <p>NEVER call this from the client thread itself — it would deadlock
 * waiting for its own queued runnable.
 */
public final class QuestStepThreads {

    private QuestStepThreads() {}

    /** Default wait for scene / widget lookups. Two seconds is generous
     *  — the client thread should service the invokeLater within one
     *  600 ms server tick. */
    public static final long DEFAULT_MARSHAL_TIMEOUT_MS = 2_000L;

    public static <T> T onClient(ClientThread clientThread, Supplier<T> supplier) {
        return onClient(clientThread, DEFAULT_MARSHAL_TIMEOUT_MS, supplier);
    }

    public static <T> T onClient(ClientThread clientThread, long timeoutMs, Supplier<T> supplier) {
        AtomicReference<T> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        clientThread.invoke(() -> {
            try { ref.set(supplier.get()); }
            finally { latch.countDown(); }
        });
        try {
            return latch.await(timeoutMs, TimeUnit.MILLISECONDS) ? ref.get() : null;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
