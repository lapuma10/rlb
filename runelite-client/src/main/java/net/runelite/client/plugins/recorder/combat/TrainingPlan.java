package net.runelite.client.plugins.recorder.combat;

import java.util.List;

/**
 * Immutable specification for a training session.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code targets} — which skills to train and to what level.</li>
 *   <li>{@code autoRetaliateOn} — whether the bot should enable auto-retaliate
 *       before starting. Ignored when {@code autoRetaliateLeaveAlone} is true.</li>
 *   <li>{@code autoRetaliateLeaveAlone} — when true the bot does not touch the
 *       auto-retaliate toggle at all (useful when an external UI already controls
 *       it).</li>
 *   <li>{@code minLevelsBeforeSwitch} / {@code maxLevelsBeforeSwitch} — the
 *       rotation threshold is drawn uniformly at random from this range each
 *       cycle.</li>
 *   <li>{@code xpHoverMinMs} / {@code xpHoverMaxMs} — anti-detection cadence
 *       for the periodic Skills-tab hover. Each cycle the bot picks a fresh
 *       value uniformly at random from this range so checks never land on
 *       the same offset twice. Defaults: 5 min to 20 min.</li>
 * </ul>
 */
public record TrainingPlan(
    List<SkillTarget> targets,
    boolean autoRetaliateOn,
    boolean autoRetaliateLeaveAlone,
    int minLevelsBeforeSwitch,
    int maxLevelsBeforeSwitch,
    long xpHoverMinMs,
    long xpHoverMaxMs)
{
    /** Lower bound of the randomised XP-check hover interval (5 min). */
    public static final long DEFAULT_XP_HOVER_MIN_MS =  5L * 60 * 1000;
    /** Upper bound of the randomised XP-check hover interval (20 min). */
    public static final long DEFAULT_XP_HOVER_MAX_MS = 20L * 60 * 1000;

    /** Compact constructor — defensively copies the targets list. */
    public TrainingPlan
    {
        if (targets == null || targets.isEmpty())
        {
            throw new IllegalArgumentException("TrainingPlan requires at least one SkillTarget");
        }
        targets = List.copyOf(targets);   // immutable defensive copy
        if (minLevelsBeforeSwitch < 1)
        {
            throw new IllegalArgumentException("minLevelsBeforeSwitch must be >= 1");
        }
        if (maxLevelsBeforeSwitch < minLevelsBeforeSwitch)
        {
            throw new IllegalArgumentException(
                "maxLevelsBeforeSwitch (" + maxLevelsBeforeSwitch
                    + ") must be >= minLevelsBeforeSwitch (" + minLevelsBeforeSwitch + ")");
        }
        if (xpHoverMinMs <= 0)
        {
            throw new IllegalArgumentException("xpHoverMinMs must be positive");
        }
        if (xpHoverMaxMs < xpHoverMinMs)
        {
            throw new IllegalArgumentException(
                "xpHoverMaxMs (" + xpHoverMaxMs
                    + ") must be >= xpHoverMinMs (" + xpHoverMinMs + ")");
        }
    }

    /**
     * Convenience factory with sensible defaults:
     * <ul>
     *   <li>minLevelsBeforeSwitch = 2</li>
     *   <li>maxLevelsBeforeSwitch = 5</li>
     *   <li>xpHoverMinMs = {@value #DEFAULT_XP_HOVER_MIN_MS} (5 min)</li>
     *   <li>xpHoverMaxMs = {@value #DEFAULT_XP_HOVER_MAX_MS} (20 min)</li>
     *   <li>autoRetaliateLeaveAlone = false</li>
     * </ul>
     *
     * @param targets       skills and level targets (at least one required)
     * @param autoRetaliate true to ensure auto-retaliate is ON at session start
     */
    public static TrainingPlan basic(List<SkillTarget> targets, boolean autoRetaliate)
    {
        return new TrainingPlan(targets, autoRetaliate, false, 2, 5,
            DEFAULT_XP_HOVER_MIN_MS, DEFAULT_XP_HOVER_MAX_MS);
    }
}
