package net.runelite.client.plugins.recorder.combat;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * Stateful rotation manager that tracks the current training skill and decides
 * when to switch to the next one based on level-up events.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>On {@link #initialize(Map)}, set the starting level for each skill and
 *       choose the initial active skill and switch threshold randomly from
 *       {@code [minLevelsBeforeSwitch, maxLevelsBeforeSwitch]}.</li>
 *   <li>On each {@link #recordLevelUp(Skill, int)}, if the gained skill is the
 *       active one, increment the per-cycle counter. When the counter reaches the
 *       threshold, switch to the next skill (chosen randomly from the remaining
 *       incomplete ones, excluding the current skill if more than one remains).</li>
 *   <li>{@link #isComplete()} returns {@code true} when every skill has reached
 *       its target level.</li>
 * </ol>
 */
@Slf4j
public class SkillRotation
{
    private final TrainingPlan plan;
    private final Random rng;

    /** Which skill we are currently training. */
    private Skill activeSkill;

    /** Levels gained on the active skill since the last switch. */
    private int levelsGainedThisCycle;

    /** How many levels must be gained before the next switch. */
    private int switchThreshold;

    /** Current real level for each skill in the plan (updated on level-up). */
    private final Map<Skill, Integer> currentLevels = new HashMap<>();

    public SkillRotation(TrainingPlan plan)
    {
        this(plan, new Random());
    }

    /** Package-private for testing with a seeded RNG. */
    SkillRotation(TrainingPlan plan, Random rng)
    {
        this.plan = plan;
        this.rng  = rng;
    }

    /**
     * Initialise the rotation from the player's current levels.
     * Must be called once before {@link #activeSkill()} or {@link #recordLevelUp}.
     *
     * @param levels map of Skill → current real level for every skill in the plan
     */
    public void initialize(Map<Skill, Integer> levels)
    {
        currentLevels.clear();
        for (SkillTarget st : plan.targets())
        {
            currentLevels.put(st.skill(), levels.getOrDefault(st.skill(), 1));
        }
        switchThreshold = drawThreshold();
        levelsGainedThisCycle = 0;

        // Pick a starting skill from those that haven't met their target yet.
        List<Skill> candidates = incompleteCandidates(null);
        if (candidates.isEmpty())
        {
            // All targets already met at initialisation time — pick any.
            activeSkill = plan.targets().get(0).skill();
        }
        else
        {
            activeSkill = candidates.get(rng.nextInt(candidates.size()));
        }
        log.info("rotation initialised: activeSkill={} threshold={}",
            activeSkill, switchThreshold);
    }

    /** Returns the currently-active skill to train. */
    public Skill activeSkill()
    {
        return activeSkill;
    }

    /**
     * Records a level-up event. If the levelled skill is the active one and
     * the per-cycle counter reaches the switch threshold, chooses the next skill.
     *
     * @param skill    the skill that levelled up
     * @param newLevel the new level (already incremented)
     */
    public void recordLevelUp(Skill skill, int newLevel)
    {
        currentLevels.put(skill, newLevel);
        log.info("level-up: {} → {}", skill, newLevel);

        if (isComplete())
        {
            log.info("all targets met — rotation complete");
            return;
        }

        if (skill == activeSkill)
        {
            // If the active skill just reached its target, switch now —
            // no point waiting for the cycle threshold.
            if (hasMetTarget(activeSkill))
            {
                log.info("{} reached target — switching immediately", activeSkill);
                switchToNextSkill();
                return;
            }
            levelsGainedThisCycle++;
            log.info("cycle progress: {}/{} levels toward switch", levelsGainedThisCycle, switchThreshold);
            if (levelsGainedThisCycle >= switchThreshold)
            {
                switchToNextSkill();
            }
        }
    }

    /**
     * Returns {@code true} when every skill in the plan has reached its target level.
     */
    public boolean isComplete()
    {
        for (SkillTarget st : plan.targets())
        {
            int current = currentLevels.getOrDefault(st.skill(), 1);
            if (current < st.targetLevel()) return false;
        }
        return true;
    }

    /**
     * Picks the next skill in rotation: a random choice from incomplete skills,
     * excluding the current one if more than one incomplete skill remains.
     * If only one skill remains, we stay on it.
     */
    public Skill nextSkill()
    {
        List<Skill> candidates = incompleteCandidates(activeSkill);
        if (candidates.isEmpty())
        {
            // Only current skill left (or all done) — stay on it.
            return activeSkill;
        }
        return candidates.get(rng.nextInt(candidates.size()));
    }

    // ---- private helpers ----

    private void switchToNextSkill()
    {
        Skill next = nextSkill();
        log.info("switching rotation: {} → {}", activeSkill, next);
        activeSkill = next;
        levelsGainedThisCycle = 0;
        switchThreshold = drawThreshold();
        log.info("new cycle threshold: {}", switchThreshold);
    }

    /** Draws a random threshold in [min, max] inclusive. */
    private int drawThreshold()
    {
        int min = plan.minLevelsBeforeSwitch();
        int max = plan.maxLevelsBeforeSwitch();
        return min + rng.nextInt(max - min + 1);
    }

    private boolean hasMetTarget(Skill skill)
    {
        int current = currentLevels.getOrDefault(skill, 1);
        for (SkillTarget st : plan.targets())
        {
            if (st.skill() == skill) return current >= st.targetLevel();
        }
        return false;
    }

    /**
     * Returns the list of skills that have not yet reached their target level.
     * If {@code exclude} is non-null AND there are at least two such skills,
     * the excluded skill is removed from the candidate set.
     */
    private List<Skill> incompleteCandidates(Skill exclude)
    {
        List<Skill> all = plan.targets().stream()
            .filter(st -> currentLevels.getOrDefault(st.skill(), 1) < st.targetLevel())
            .map(SkillTarget::skill)
            .collect(Collectors.toCollection(ArrayList::new));

        if (exclude != null && all.size() > 1)
        {
            all.remove(exclude);
        }
        return all;
    }
}
