package net.runelite.client.plugins.recorder.afk;

/**
 * Tunable timings for {@link BreakScheduler}.  All durations in
 * milliseconds.  Values stay below the OSRS 5-minute auto-kick
 * threshold so v1 doesn't need logout/login plumbing — longer
 * pauses are a separate (deferred) feature.
 *
 * <p>If a value is wrong for the script in front of you, change it
 * here and rebuild — the scheduler reads these statically.  Per-script
 * overrides are out of scope until a second script adopts the
 * scheduler.
 */
public final class BreakConfig
{
    private BreakConfig() {}

    /** Lower bound for a fresh activity period (the time after a
     *  break ends and before the next break is allowed). */
    public static final long ACTIVITY_MIN_MS = 25L * 60L * 1000L;          // 25 min

    /** Upper bound for a fresh activity period. */
    public static final long ACTIVITY_MAX_MS = 60L * 60L * 1000L;          // 60 min

    /** "Phone glance" — short attention break.  Lower bound. */
    public static final long MICRO_MIN_MS    = 30L * 1000L;                // 30 s

    /** "Phone glance" — short attention break.  Upper bound. */
    public static final long MICRO_MAX_MS    = 90L * 1000L;                // 90 s

    /** "Bathroom / drink" — medium step-away.  Lower bound. */
    public static final long MEDIUM_MIN_MS   = 150L * 1000L;               // 2.5 min

    /** "Bathroom / drink" — medium step-away.  Upper bound.  Stays
     *  below the ~5min OSRS auto-kick. */
    public static final long MEDIUM_MAX_MS   = 270L * 1000L;               // 4.5 min

    /** Probability (in percent, 0..100) that a rolled break is the
     *  MICRO tier.  Complement is the MEDIUM tier — keep these two
     *  summing to 100 unless a third tier is added. */
    public static final int  MICRO_PROB      = 70;
}
