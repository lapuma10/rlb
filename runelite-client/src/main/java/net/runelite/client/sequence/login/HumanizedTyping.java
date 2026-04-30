/*
 * Copyright (c) 2026, RuneLite
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.sequence.login;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import javax.annotation.Nullable;
import net.runelite.client.sequence.dispatch.SequenceSleep;

/**
 * Generates inter-keystroke delays that look like a human typing in chunks.
 * The contrast against uniform-spaced typing is what humans (and bot
 * detection) notice: real typing has bursts of fast same-finger / homerow
 * letters interrupted by 200-600ms "thinking" pauses between groups.
 *
 * <p>Sampling model: a per-session pace factor scales the whole distribution
 * (some sessions are typed faster, some slower). Within a session, pick a
 * group size (4-10 chars), emit fast intra-group delays for that many
 * letters, then a slower inter-group gap, then repeat.
 */
public final class HumanizedTyping
{
    /** Inter-keystroke delays inside a typing burst. The lower bound matches
     *  observed touch-typist same-row averages; the upper bound captures the
     *  "stretch" letters a touch-typist still hits in flow. */
    public static final int FAST_MIN_MS = 80;
    public static final int FAST_MAX_MS = 160;

    /** Inter-burst pauses — long enough to be visibly different from the
     *  fast delays so a uniform-detector flags them as outliers but not so
     *  long that the typing looks halting. */
    public static final int GAP_MIN_MS = 200;
    public static final int GAP_MAX_MS = 600;

    /** Burst length range. A 4-char burst is a single short word; 10 chars
     *  is a typical login username — having both means short usernames are
     *  often single-burst, longer ones break naturally. */
    public static final int BURST_MIN = 4;
    public static final int BURST_MAX = 10;

    /** Pace factor sampled per session: 0.8x means 20% faster than the
     *  baseline; 1.4x means 40% slower. Skewed slightly slow because users
     *  are typically careful with their username. */
    public static final double PACE_MIN = 0.8;
    public static final double PACE_MAX = 1.4;

    private final Random rng;
    private final double pace;
    private int charsLeftInBurst;

    public HumanizedTyping()
    {
        this(new Random());
    }

    public HumanizedTyping(Random rng)
    {
        this.rng = rng;
        this.pace = PACE_MIN + rng.nextDouble() * (PACE_MAX - PACE_MIN);
        this.charsLeftInBurst = sampleBurstSize();
    }

    /** Pace factor for this session — exposed for test assertions / logging. */
    public double getPaceFactor() { return pace; }

    /** Generate the delay (ms) to wait BEFORE the next character. The very
     *  first character of a typing session uses a small "ready to start"
     *  delay sampled from the fast band — calling code should sleep this
     *  before pressing the first key. */
    public int nextDelayMs()
    {
        if (charsLeftInBurst > 0)
        {
            charsLeftInBurst--;
            int raw = FAST_MIN_MS + rng.nextInt(FAST_MAX_MS - FAST_MIN_MS + 1);
            return scaled(raw);
        }
        // Burst boundary — emit a longer pause and reset.
        charsLeftInBurst = sampleBurstSize() - 1;   // -1 because this call uses one slot
        int raw = GAP_MIN_MS + rng.nextInt(GAP_MAX_MS - GAP_MIN_MS + 1);
        return scaled(raw);
    }

    /** Convenience: generate the full delay sequence for {@code n} characters,
     *  starting at index 0 (so the first delay is fast — i.e., the 'ready'
     *  beat — and burst boundaries appear thereafter). Used for tests and
     *  for callers that prefer to plan the schedule in one go. */
    public List<Integer> sample(int n)
    {
        List<Integer> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(nextDelayMs());
        return out;
    }

    private int sampleBurstSize()
    {
        return BURST_MIN + rng.nextInt(BURST_MAX - BURST_MIN + 1);
    }

    private int scaled(int raw)
    {
        return Math.max(1, (int) Math.round(raw * pace));
    }

    /**
     * Hold backspace until the polled field reads empty, or maxMs elapses.
     *
     * Inter-event delay 33-55ms with ±10% jitter (matches OS key-repeat).
     * Snap-stops when readField returns empty.
     *
     * @param readField     supplier that reads the current field text (must be safe to call from caller thread)
     * @param onBackspace   invoked once per simulated backspace event (typically dispatches a KEY_PRESSED+KEY_TYPED)
     * @param tickCallback  invoked once per inter-event sleep cycle (for test-counting)
     * @param maxMs         hard cap on total hold duration
     * @param rng           random source for jitter
     * @return number of backspace events fired
     * @throws InterruptedException if the calling thread is interrupted
     */
    public static int holdBackspaceUntilEmpty(java.util.function.Supplier<String> readField,
                                              Runnable onBackspace,
                                              Runnable tickCallback,
                                              long maxMs,
                                              Random rng) throws InterruptedException
    {
        long deadline = System.currentTimeMillis() + maxMs;
        int events = 0;
        while (System.currentTimeMillis() < deadline)
        {
            if (Thread.interrupted()) throw new InterruptedException();
            String current = readField.get();
            if (current == null || current.isEmpty()) return events;
            onBackspace.run();
            events++;
            long delay = nextBackspaceDelayMs(rng);
            SequenceSleep.sleep(null, delay);
            tickCallback.run();
        }
        return events;
    }

    /**
     * Hold backspace for a fixed-but-jittered duration. Used when we cannot
     * poll the field text (e.g., password). An optional abortGuard is checked
     * every ~200ms — if it returns true, we stop early.
     *
     * @param onBackspace  invoked once per simulated backspace event
     * @param abortGuard   optional; checked every ~200ms; null = no abort
     * @param baseMs       minimum total hold duration
     * @param varianceMs   added 0..varianceMs random ms to baseMs
     * @param rng          random source
     * @throws InterruptedException if the calling thread is interrupted
     */
    public static void holdBackspaceForDuration(Runnable onBackspace,
                                                @Nullable java.util.function.Predicate<Void> abortGuard,
                                                long baseMs,
                                                int varianceMs,
                                                Random rng) throws InterruptedException
    {
        long total = baseMs + (varianceMs > 0 ? rng.nextInt(varianceMs) : 0);
        long deadline = System.currentTimeMillis() + total;
        long nextGuardCheck = System.currentTimeMillis() + 200;
        while (System.currentTimeMillis() < deadline)
        {
            if (Thread.interrupted()) throw new InterruptedException();
            if (abortGuard != null && System.currentTimeMillis() >= nextGuardCheck)
            {
                if (abortGuard.test(null)) return;
                nextGuardCheck = System.currentTimeMillis() + 200;
            }
            onBackspace.run();
            SequenceSleep.sleep(null, nextBackspaceDelayMs(rng));
        }
    }

    /** 33-55ms with ±10% jitter. */
    private static long nextBackspaceDelayMs(Random rng)
    {
        long base = 33 + rng.nextInt(23);
        double jitter = 1.0 + (rng.nextDouble() - 0.5) * 0.20;
        return Math.max(20, (long) (base * jitter));
    }
}
