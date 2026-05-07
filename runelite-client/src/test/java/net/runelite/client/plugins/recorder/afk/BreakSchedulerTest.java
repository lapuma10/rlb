package net.runelite.client.plugins.recorder.afk;

import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class BreakSchedulerTest
{
    private static final Pattern NEXT_BREAK = Pattern.compile("next break in \\d+m \\d+s");
    private static final Pattern IN_BREAK   = Pattern.compile("in (MICRO|MEDIUM) break, \\d+m \\d+s remaining");
    private static final Pattern OFF        = Pattern.compile("breaks: off");

    private static BreakScheduler fresh(AtomicLong clock, long seed)
    {
        return new BreakScheduler(clock::get, new Random(seed), true);
    }

    // ─── Task 2: fresh state ─────────────────────────────────────────────

    @Test
    public void freshSchedulerIsNotInBreak()
    {
        AtomicLong clock = new AtomicLong(0L);
        BreakScheduler s = fresh(clock, 42L);
        assertFalse(s.isInBreak(0L));
    }

    @Test
    public void freshSchedulerIsNotDueAtTimeZero()
    {
        AtomicLong clock = new AtomicLong(0L);
        BreakScheduler s = fresh(clock, 42L);
        // safe boundary true so we test only the activity-timer gate
        assertFalse(s.isBreakDue(0L, true));
    }

    @Test
    public void freshStatusLineShowsNextBreakCountdown()
    {
        AtomicLong clock = new AtomicLong(0L);
        BreakScheduler s = fresh(clock, 42L);
        String line = s.statusLine(0L);
        assertTrue("expected 'next break in Xm Ys' but got: " + line,
            NEXT_BREAK.matcher(line).matches());
    }

    // ─── Task 3: isBreakDue + startBreak ──────────────────────────────────

    @Test
    public void breakBecomesDueAfterActivityWindowAndAtBoundary()
    {
        AtomicLong clock = new AtomicLong(0L);
        BreakScheduler s = fresh(clock, 42L);
        clock.set(BreakConfig.ACTIVITY_MAX_MS + 1L);
        assertTrue(s.isBreakDue(clock.get(), true));
    }

    @Test
    public void breakNotDueWhenNotAtSafeBoundary()
    {
        AtomicLong clock = new AtomicLong(0L);
        BreakScheduler s = fresh(clock, 42L);
        clock.set(BreakConfig.ACTIVITY_MAX_MS + 1L);
        assertFalse(s.isBreakDue(clock.get(), false));
    }

    /** Fixture-fingerprint test: with seed 42 + 1000 trials, micro
     *  share is within ±2% of MICRO_PROB.  Catches algorithm changes
     *  that would shift this exact seed's outcome. */
    @Test
    public void startBreakTierDistributionIsTightForFixedSeed()
    {
        int[] tally = tally(42L, 1000);
        int micro = tally[0];
        double pct = micro / 10.0; // %
        double lo = BreakConfig.MICRO_PROB - 2.0;
        double hi = BreakConfig.MICRO_PROB + 2.0;
        assertTrue("seed 42, 1000 trials: micro=" + micro + " (" + pct + "%) outside [" + lo + ", " + hi + "]",
            pct >= lo && pct <= hi);
    }

    /** Distribution test: 5 distinct seeds × 1000 trials each.
     *  Catches actual bias without flaking on a single seed
     *  fingerprint. */
    @Test
    public void startBreakTierDistributionAcrossManySeeds()
    {
        long[] seeds = { 42L, 7L, 1234L, 99L, 314L };
        for (long seed : seeds)
        {
            int[] tally = tally(seed, 1000);
            double pct = tally[0] / 10.0;
            double lo = BreakConfig.MICRO_PROB - 5.0;
            double hi = BreakConfig.MICRO_PROB + 5.0;
            assertTrue("seed " + seed + ": micro%=" + pct + " outside [" + lo + ", " + hi + "]",
                pct >= lo && pct <= hi);
        }
    }

    @Test
    public void startBreakDurationsAlwaysInTierBand()
    {
        AtomicLong clock = new AtomicLong(0L);
        Random rng = new Random(42L);
        for (int i = 0; i < 500; i++)
        {
            BreakScheduler s = new BreakScheduler(clock::get, rng, true);
            clock.set(0L);
            s.startBreak(0L);
            BreakScheduler.Tier tier = s.currentTier();
            // remaining = breakEndMs - nowMs
            long rem = remainingMs(s, 0L);
            long min = tier == BreakScheduler.Tier.MICRO ? BreakConfig.MICRO_MIN_MS : BreakConfig.MEDIUM_MIN_MS;
            long max = tier == BreakScheduler.Tier.MICRO ? BreakConfig.MICRO_MAX_MS : BreakConfig.MEDIUM_MAX_MS;
            assertTrue("tier=" + tier + " rem=" + rem + " not in [" + min + ", " + max + "]",
                rem >= min && rem <= max);
        }
    }

    // ─── Task 4: isInBreak / endBreakIfDue / statusLine ──────────────────

    @Test
    public void inBreakAfterStartBreak()
    {
        AtomicLong clock = new AtomicLong(0L);
        BreakScheduler s = fresh(clock, 42L);
        s.startBreak(0L);
        assertTrue(s.isInBreak(0L));
        assertNotEquals(null, s.currentTier());
    }

    @Test
    public void endBreakIfDueClearsExpiredBreakAndRollsNewActivity()
    {
        AtomicLong clock = new AtomicLong(0L);
        BreakScheduler s = fresh(clock, 42L);
        s.startBreak(0L);
        // jump well past any tier max
        clock.set(BreakConfig.MEDIUM_MAX_MS + 1L);
        s.endBreakIfDue(clock.get());
        assertFalse(s.isInBreak(clock.get()));
        assertNull(s.currentTier());
        // next status should be a "next break in" countdown again
        assertTrue(NEXT_BREAK.matcher(s.statusLine(clock.get())).matches());
    }

    @Test
    public void endBreakIfDueIsNoOpDuringActiveBreak()
    {
        AtomicLong clock = new AtomicLong(0L);
        BreakScheduler s = fresh(clock, 42L);
        s.startBreak(0L);
        BreakScheduler.Tier before = s.currentTier();
        clock.set(10L);   // tiny step — break can't be over
        s.endBreakIfDue(clock.get());
        assertTrue(s.isInBreak(clock.get()));
        assertEquals(before, s.currentTier());
    }

    @Test
    public void endBreakIfDueIsNoOpWhenNoBreakStarted()
    {
        AtomicLong clock = new AtomicLong(0L);
        BreakScheduler s = fresh(clock, 42L);
        s.endBreakIfDue(0L);
        assertFalse(s.isInBreak(0L));
        assertNull(s.currentTier());
    }

    @Test
    public void statusLineShowsInBreakDuringBreak()
    {
        AtomicLong clock = new AtomicLong(0L);
        BreakScheduler s = fresh(clock, 42L);
        s.startBreak(0L);
        String line = s.statusLine(0L);
        assertTrue("expected 'in TIER break, ...' but got: " + line,
            IN_BREAK.matcher(line).matches());
    }

    // ─── Task 5: enable / disable ────────────────────────────────────────

    @Test
    public void disabledNeverFlagsBreakDue()
    {
        AtomicLong clock = new AtomicLong(0L);
        BreakScheduler s = fresh(clock, 42L);
        clock.set(BreakConfig.ACTIVITY_MAX_MS + 1L);
        s.disable();
        assertFalse(s.isBreakDue(clock.get(), true));
    }

    @Test
    public void disabledStatusLineShowsOff()
    {
        AtomicLong clock = new AtomicLong(0L);
        BreakScheduler s = fresh(clock, 42L);
        s.disable();
        assertTrue(OFF.matcher(s.statusLine(0L)).matches());
    }

    @Test
    public void disableMidBreakClearsTheBreak()
    {
        AtomicLong clock = new AtomicLong(0L);
        BreakScheduler s = fresh(clock, 42L);
        s.startBreak(0L);
        assertTrue(s.isInBreak(0L));
        s.disable();
        assertFalse(s.isInBreak(0L));
        assertNull(s.currentTier());
    }

    @Test
    public void reEnableRollsFreshActivityWindowFromNow()
    {
        AtomicLong clock = new AtomicLong(0L);
        BreakScheduler s = fresh(clock, 42L);
        s.disable();
        clock.set(10_000L);
        s.enable(clock.get());
        // activity window is at least ACTIVITY_MIN_MS into the future,
        // so isBreakDue must be false at clock.get() AND at
        // clock.get() + ACTIVITY_MIN_MS - 1ms
        assertFalse(s.isBreakDue(clock.get(), true));
        assertFalse(s.isBreakDue(clock.get() + BreakConfig.ACTIVITY_MIN_MS - 1L, true));
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    /** Returns {micro, medium} counts over `n` startBreak calls
     *  with the given seed. */
    private static int[] tally(long seed, int n)
    {
        AtomicLong clock = new AtomicLong(0L);
        Random rng = new Random(seed);
        int micro = 0, medium = 0;
        for (int i = 0; i < n; i++)
        {
            BreakScheduler s = new BreakScheduler(clock::get, rng, true);
            s.startBreak(0L);
            if (s.currentTier() == BreakScheduler.Tier.MICRO) micro++;
            else medium++;
        }
        return new int[] { micro, medium };
    }

    /** Read remaining-ms via the scheduler's status line — keeps
     *  breakEndMs encapsulated. */
    private static long remainingMs(BreakScheduler s, long nowMs)
    {
        // We don't expose breakEndMs publicly; reconstruct from
        // statusLine "in TIER break, Xm Ys remaining"
        String line = s.statusLine(nowMs);
        int comma = line.indexOf(',');
        int rem = line.indexOf(" remaining");
        String mmss = line.substring(comma + 2, rem);
        // "Xm Ys"
        int mIdx = mmss.indexOf('m');
        int sIdx = mmss.indexOf('s');
        long mins = Long.parseLong(mmss.substring(0, mIdx));
        long secs = Long.parseLong(mmss.substring(mIdx + 2, sIdx));
        // statusLine truncates to whole seconds — round-trip floor.
        // Caller compares to MIN/MAX which are also at second resolution.
        return (mins * 60L + secs) * 1000L;
    }
}
