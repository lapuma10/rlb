package net.runelite.client.plugins.recorder.afk;

import java.util.Random;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;

/**
 * Tracks "should the bot AFK now?" state for a single script.
 *
 * <p>Two tiers of breaks (MICRO / MEDIUM, both under the OSRS 5-minute
 * auto-kick threshold so the script can resume in-place when the
 * timer expires) interleaved with activity periods of
 * {@link BreakConfig#ACTIVITY_MIN_MS}..{@link BreakConfig#ACTIVITY_MAX_MS}.
 *
 * <p>Pure logic: takes a {@link LongSupplier} clock + a {@link Random}
 * for testability.  Production constructs with
 * {@code System::currentTimeMillis} + {@code ThreadLocalRandom.current()}.
 *
 * <p>Thread safety: the worker thread reads scheduler state every tick;
 * the EDT panel listener may flip {@link #disable()} / {@link #enable(long)}.
 * Mutable fields are {@code volatile} for cross-thread visibility — the
 * scheduler itself never compounds reads-then-writes from multiple threads,
 * so no locks are required.
 */
@Slf4j
public final class BreakScheduler
{
    public enum Tier
    {
        MICRO, MEDIUM
    }

    private final LongSupplier clockMs;
    private final Random rng;

    /** Wall-clock at which the next break is allowed to start (when
     *  the FSM is also at a safe boundary). */
    private volatile long activityEndMs;

    /** Wall-clock at which the current break ends; 0 when not in
     *  a break. */
    private volatile long breakEndMs;

    /** Tier of the currently-active break; null when not in a break. */
    private volatile Tier currentTier;

    /** True while the user has flipped the panel checkbox off; the
     *  scheduler short-circuits {@link #isBreakDue} and reports
     *  "breaks: off" via {@link #statusLine}. */
    private volatile boolean disabled;

    public BreakScheduler(LongSupplier clockMs, Random rng, boolean enabledAtStart)
    {
        this.clockMs = clockMs;
        this.rng = rng;
        this.disabled = !enabledAtStart;
        this.activityEndMs = clockMs.getAsLong() + rollActivityMs();
        this.breakEndMs = 0L;
        this.currentTier = null;
    }

    /** True when an enabled scheduler at a safe FSM boundary should
     *  start a break. */
    public boolean isBreakDue(long nowMs, boolean atSafeBoundary)
    {
        if (disabled) return false;
        if (breakEndMs > 0L) return false;            // already in break
        if (!atSafeBoundary) return false;
        return nowMs >= activityEndMs;
    }

    /** Roll a tier + duration; flip into "in break" state.  Caller
     *  is responsible for having checked {@link #isBreakDue} first. */
    public void startBreak(long nowMs)
    {
        Tier tier = rng.nextInt(100) < BreakConfig.MICRO_PROB ? Tier.MICRO : Tier.MEDIUM;
        long min = tier == Tier.MICRO ? BreakConfig.MICRO_MIN_MS : BreakConfig.MEDIUM_MIN_MS;
        long max = tier == Tier.MICRO ? BreakConfig.MICRO_MAX_MS : BreakConfig.MEDIUM_MAX_MS;
        long duration = min + (long) (rng.nextDouble() * (max - min + 1));
        currentTier = tier;
        breakEndMs = nowMs + duration;
        log.info("afk break starting — tier={} duration={}s",
            tier, duration / 1000L);
    }

    public boolean isInBreak(long nowMs)
    {
        return breakEndMs > nowMs;
    }

    /** No-op unless a break has expired.  When it has: roll a fresh
     *  activity period, clear the break fields, log. */
    public void endBreakIfDue(long nowMs)
    {
        if (breakEndMs <= 0L) return;
        if (nowMs < breakEndMs) return;
        long activity = rollActivityMs();
        Tier endedTier = currentTier;
        breakEndMs = 0L;
        currentTier = null;
        activityEndMs = nowMs + activity;
        log.info("afk break over — back to work (tier={} ended; next break in ~{}s)",
            endedTier, activity / 1000L);
    }

    /** Flip the kill switch off.  Live in any state — clears any
     *  active break too, so re-enable doesn't strand a half-finished
     *  timer. */
    public void disable()
    {
        disabled = true;
        breakEndMs = 0L;
        currentTier = null;
    }

    /** Flip the kill switch on, resetting the activity timer from
     *  {@code nowMs} so re-enabling mid-session doesn't fire an
     *  instant break. */
    public void enable(long nowMs)
    {
        disabled = false;
        activityEndMs = nowMs + rollActivityMs();
    }

    /** Single-line panel status. */
    public String statusLine(long nowMs)
    {
        if (disabled) return "breaks: off";
        if (breakEndMs > nowMs)
        {
            long rem = breakEndMs - nowMs;
            return "in " + currentTier + " break, " + formatMmSs(rem) + " remaining";
        }
        long until = Math.max(0L, activityEndMs - nowMs);
        return "next break in " + formatMmSs(until);
    }

    public Tier currentTier() { return currentTier; }

    private long rollActivityMs()
    {
        long span = BreakConfig.ACTIVITY_MAX_MS - BreakConfig.ACTIVITY_MIN_MS + 1L;
        return BreakConfig.ACTIVITY_MIN_MS + (long) (rng.nextDouble() * span);
    }

    private static String formatMmSs(long ms)
    {
        long total = ms / 1000L;
        long mins = total / 60L;
        long secs = total % 60L;
        return mins + "m " + (secs < 10 ? "0" : "") + secs + "s";
    }
}
