package net.runelite.client.plugins.recorder.combat;

import net.runelite.api.Skill;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class TrainingPlanTest
{
    private static final List<SkillTarget> SIMPLE = List.of(
        new SkillTarget(Skill.ATTACK, 20),
        new SkillTarget(Skill.STRENGTH, 20)
    );

    @Test
    public void basicFactory_producesExpectedDefaults()
    {
        TrainingPlan plan = TrainingPlan.basic(SIMPLE, true);
        assertEquals(2, plan.minLevelsBeforeSwitch());
        assertEquals(5, plan.maxLevelsBeforeSwitch());
        assertEquals(TrainingPlan.DEFAULT_XP_HOVER_MS, plan.xpHoverEveryMs());
        assertTrue(plan.autoRetaliateOn());
        assertFalse(plan.autoRetaliateLeaveAlone());
        assertEquals(2, plan.targets().size());
    }

    @Test
    public void targets_areDefensivelyCopied()
    {
        List<SkillTarget> mutable = new ArrayList<>();
        mutable.add(new SkillTarget(Skill.ATTACK, 30));
        TrainingPlan plan = TrainingPlan.basic(mutable, false);
        // Mutating the source list must NOT affect the plan.
        mutable.add(new SkillTarget(Skill.DEFENCE, 30));
        assertEquals(1, plan.targets().size());
    }

    @Test(expected = UnsupportedOperationException.class)
    public void targets_returnedListIsImmutable()
    {
        TrainingPlan plan = TrainingPlan.basic(SIMPLE, false);
        plan.targets().add(new SkillTarget(Skill.DEFENCE, 20));
    }

    @Test(expected = IllegalArgumentException.class)
    public void emptyTargets_throws()
    {
        TrainingPlan.basic(List.of(), false);
    }

    @Test(expected = IllegalArgumentException.class)
    public void nullTargets_throws()
    {
        TrainingPlan.basic(null, false);
    }

    @Test(expected = IllegalArgumentException.class)
    public void minGreaterThanMax_throws()
    {
        new TrainingPlan(SIMPLE, true, false, 5, 2, TrainingPlan.DEFAULT_XP_HOVER_MS);
    }

    @Test(expected = IllegalArgumentException.class)
    public void minLessThanOne_throws()
    {
        new TrainingPlan(SIMPLE, true, false, 0, 5, TrainingPlan.DEFAULT_XP_HOVER_MS);
    }

    @Test(expected = IllegalArgumentException.class)
    public void negativeHoverMs_throws()
    {
        new TrainingPlan(SIMPLE, true, false, 2, 5, -1L);
    }

    @Test
    public void autoRetaliateLeaveAlone_storedCorrectly()
    {
        TrainingPlan plan = new TrainingPlan(SIMPLE, false, true, 2, 5, TrainingPlan.DEFAULT_XP_HOVER_MS);
        assertTrue(plan.autoRetaliateLeaveAlone());
        assertFalse(plan.autoRetaliateOn());
    }
}
