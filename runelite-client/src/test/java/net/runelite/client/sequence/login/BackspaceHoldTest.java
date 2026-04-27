package net.runelite.client.sequence.login;

import org.junit.Test;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Random;
import java.util.function.Supplier;
import java.util.function.Predicate;
import static org.junit.Assert.*;

public class BackspaceHoldTest
{
    @Test
    public void holdUntilEmpty_stopsWhenFieldEmpty() throws InterruptedException
    {
        AtomicInteger remaining = new AtomicInteger(10);
        Supplier<String> read = () -> {
            int n = remaining.get();
            return n > 0 ? "x".repeat(n) : "";
        };
        AtomicInteger eventsFired = new AtomicInteger(0);
        Runnable onBackspace = () -> remaining.decrementAndGet();
        int fired = HumanizedTyping.holdBackspaceUntilEmpty(
            read, onBackspace, eventsFired::incrementAndGet, 3000, new Random(0));
        assertEquals("field should be empty", 0, remaining.get());
        assertTrue("at least 10 events fired", fired >= 10);
        assertTrue("at most some grace events fired", fired <= 15);
    }

    @Test
    public void holdUntilEmpty_capsAtMaxMs() throws InterruptedException
    {
        AtomicInteger remaining = new AtomicInteger(1000); // never drains
        Supplier<String> read = () -> "x".repeat(remaining.get());
        Runnable onBackspace = () -> {}; // doesn't drain
        long start = System.currentTimeMillis();
        HumanizedTyping.holdBackspaceUntilEmpty(read, onBackspace, () -> {}, 500, new Random(0));
        long elapsed = System.currentTimeMillis() - start;
        assertTrue("respects 500ms cap", elapsed < 800);
    }

    @Test
    public void holdForDuration_runsAtLeastBaseMs() throws InterruptedException
    {
        AtomicInteger fired = new AtomicInteger(0);
        long start = System.currentTimeMillis();
        HumanizedTyping.holdBackspaceForDuration(
            () -> fired.incrementAndGet(), null, 300, 0, new Random(0));
        long elapsed = System.currentTimeMillis() - start;
        assertTrue("ran at least 280ms (allow 20ms slop)", elapsed >= 280);
        assertTrue("fired some events", fired.get() > 3);
    }

    @Test
    public void holdForDuration_abortsWhenGuardTrue() throws InterruptedException
    {
        AtomicBoolean abort = new AtomicBoolean(false);
        Predicate<Void> guard = v -> abort.get();
        // schedule abort after 200ms
        new Thread(() -> {
            try { Thread.sleep(200); } catch (InterruptedException ie) { return; }
            abort.set(true);
        }).start();
        long start = System.currentTimeMillis();
        HumanizedTyping.holdBackspaceForDuration(() -> {}, guard, 2000, 0, new Random(0));
        long elapsed = System.currentTimeMillis() - start;
        assertTrue("aborted before full duration", elapsed < 700);
    }

    @Test(expected = InterruptedException.class)
    public void holdUntilEmpty_propagatesInterruption() throws InterruptedException
    {
        Thread.currentThread().interrupt();
        HumanizedTyping.holdBackspaceUntilEmpty(
            () -> "xxxxx", () -> {}, () -> {}, 3000, new Random(0));
    }
}
