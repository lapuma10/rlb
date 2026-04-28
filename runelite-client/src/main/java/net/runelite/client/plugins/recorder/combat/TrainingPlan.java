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
 *   <li>{@code xpHoverEveryMs} — every this many milliseconds the bot should
 *       hover a skill icon in the stats tab to check remaining XP to the next
 *       level. Default 90 000 ms (≈90 s) for humanization.</li>
 * </ul>
 */
public record TrainingPlan(
    List<SkillTarget> targets,
    boolean autoRetaliateOn,
    boolean autoRetaliateLeaveAlone,
    int minLevelsBeforeSwitch,
    int maxLevelsBeforeSwitch,
    long xpHoverEveryMs)
{
    /** Default XP-check hover interval (90 s). */
    public static final long DEFAULT_XP_HOVER_MS = 90_000L;

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
        if (xpHoverEveryMs <= 0)
        {
            throw new IllegalArgumentException("xpHoverEveryMs must be positive");
        }
    }

    /**
     * Convenience factory with sensible defaults:
     * <ul>
     *   <li>minLevelsBeforeSwitch = 2</li>
     *   <li>maxLevelsBeforeSwitch = 5</li>
     *   <li>xpHoverEveryMs = {@value #DEFAULT_XP_HOVER_MS}</li>
     *   <li>autoRetaliateLeaveAlone = false</li>
     * </ul>
     *
     * @param targets       skills and level targets (at least one required)
     * @param autoRetaliate true to ensure auto-retaliate is ON at session start
     */
    public static TrainingPlan basic(List<SkillTarget> targets, boolean autoRetaliate)
    {
        return new TrainingPlan(targets, autoRetaliate, false, 2, 5, DEFAULT_XP_HOVER_MS);
    }
}
