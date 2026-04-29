package net.runelite.client.plugins.recorder.combat;

import net.runelite.api.Skill;
import org.junit.Test;
import java.util.List;
import java.util.Map;
import java.util.Random;
import static org.junit.Assert.*;

public class SkillRotationTest
{
    private static TrainingPlan planWith(int minSwitch, int maxSwitch, SkillTarget... targets)
    {
        return new TrainingPlan(
            List.of(targets),
            true, false,
            minSwitch, maxSwitch,
            TrainingPlan.DEFAULT_XP_HOVER_MIN_MS,
            TrainingPlan.DEFAULT_XP_HOVER_MAX_MS
        );
    }

    @Test
    public void initialPickRespectsTargets()
    {
        // Both ATTACK and DEFENCE are below target — active skill must be one of them.
        TrainingPlan plan = planWith(2, 2,
            new SkillTarget(Skill.ATTACK, 20),
            new SkillTarget(Skill.DEFENCE, 20));
        SkillRotation rotation = new SkillRotation(plan);
        rotation.initialize(Map.of(Skill.ATTACK, 10, Skill.DEFENCE, 10));

        Skill active = rotation.activeSkill();
        assertTrue("active skill must be from plan",
            active == Skill.ATTACK || active == Skill.DEFENCE);
    }

    @Test
    public void noSwitchBeforeThreshold_oneLevel()
    {
        // Threshold = 2; gain 1 level → should NOT switch.
        TrainingPlan plan = planWith(2, 2,
            new SkillTarget(Skill.ATTACK, 20),
            new SkillTarget(Skill.STRENGTH, 20));
        SkillRotation rotation = new SkillRotation(plan, new Random(0));
        rotation.initialize(Map.of(Skill.ATTACK, 10, Skill.STRENGTH, 10));

        Skill before = rotation.activeSkill();
        rotation.recordLevelUp(before, 11);   // 1 level gained

        assertEquals("should stay on same skill after 1 level (threshold=2)",
            before, rotation.activeSkill());
    }

    @Test
    public void switchesAfterThreshold()
    {
        // Threshold = 2 (seeded so min==max==2); gain 2 levels → must switch.
        TrainingPlan plan = planWith(2, 2,
            new SkillTarget(Skill.ATTACK, 20),
            new SkillTarget(Skill.STRENGTH, 20));
        // Use seeded RNG so the active skill is deterministic.
        SkillRotation rotation = new SkillRotation(plan, new Random(42));
        rotation.initialize(Map.of(Skill.ATTACK, 10, Skill.STRENGTH, 10));

        Skill before = rotation.activeSkill();
        rotation.recordLevelUp(before, 11);   // level 1
        rotation.recordLevelUp(before, 12);   // level 2 → should switch

        Skill after = rotation.activeSkill();
        assertNotEquals("should switch after reaching threshold", before, after);
    }

    @Test
    public void staysOnLastSkill_whenOnlyOneRemains()
    {
        // ATTACK at target (20), STRENGTH below — only STRENGTH remains.
        TrainingPlan plan = planWith(2, 2,
            new SkillTarget(Skill.ATTACK, 20),
            new SkillTarget(Skill.STRENGTH, 20));
        SkillRotation rotation = new SkillRotation(plan, new Random(1));
        rotation.initialize(Map.of(Skill.ATTACK, 20, Skill.STRENGTH, 10));

        // Must be STRENGTH since ATTACK already at target.
        assertEquals(Skill.STRENGTH, rotation.activeSkill());

        Skill next = rotation.nextSkill();
        assertEquals("nextSkill must stay on last remaining skill", Skill.STRENGTH, next);
    }

    @Test
    public void completeWhenAllTargetsMet()
    {
        TrainingPlan plan = planWith(2, 5,
            new SkillTarget(Skill.ATTACK, 20),
            new SkillTarget(Skill.STRENGTH, 20),
            new SkillTarget(Skill.DEFENCE, 20));
        SkillRotation rotation = new SkillRotation(plan);
        rotation.initialize(Map.of(Skill.ATTACK, 19, Skill.STRENGTH, 19, Skill.DEFENCE, 19));

        assertFalse(rotation.isComplete());

        rotation.recordLevelUp(Skill.ATTACK, 20);
        rotation.recordLevelUp(Skill.STRENGTH, 20);
        rotation.recordLevelUp(Skill.DEFENCE, 20);

        assertTrue("isComplete() must be true when all skills hit target",
            rotation.isComplete());
    }

    @Test
    public void notCompleteUntilAllTargetsMet()
    {
        TrainingPlan plan = planWith(2, 2,
            new SkillTarget(Skill.ATTACK, 20),
            new SkillTarget(Skill.STRENGTH, 20));
        SkillRotation rotation = new SkillRotation(plan);
        rotation.initialize(Map.of(Skill.ATTACK, 19, Skill.STRENGTH, 10));

        rotation.recordLevelUp(Skill.ATTACK, 20);

        assertFalse("STRENGTH still below target — not complete", rotation.isComplete());
    }

    @Test
    public void levelUpOnNonActiveSkill_doesNotAdvanceCycleCounter()
    {
        // Threshold=2; level up a NON-active skill → should NOT trigger a switch.
        TrainingPlan plan = planWith(2, 2,
            new SkillTarget(Skill.ATTACK, 20),
            new SkillTarget(Skill.STRENGTH, 20));
        SkillRotation rotation = new SkillRotation(plan, new Random(7));
        rotation.initialize(Map.of(Skill.ATTACK, 10, Skill.STRENGTH, 10));

        Skill active = rotation.activeSkill();
        Skill other  = (active == Skill.ATTACK) ? Skill.STRENGTH : Skill.ATTACK;

        // Level up the non-active skill twice.
        rotation.recordLevelUp(other, 11);
        rotation.recordLevelUp(other, 12);

        // Active skill must NOT have switched.
        assertEquals("level-up on non-active skill must not cause a switch",
            active, rotation.activeSkill());
    }
}
