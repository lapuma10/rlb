package net.runelite.client.plugins.recorder.combat;

import net.runelite.api.Skill;
import org.junit.Test;
import static org.junit.Assert.*;

public class SkillTargetTest
{
    @Test
    public void validAttackTarget_constructsOk()
    {
        SkillTarget t = new SkillTarget(Skill.ATTACK, 20);
        assertEquals(Skill.ATTACK, t.skill());
        assertEquals(20, t.targetLevel());
    }

    @Test
    public void validStrengthTarget_constructsOk()
    {
        SkillTarget t = new SkillTarget(Skill.STRENGTH, 99);
        assertEquals(Skill.STRENGTH, t.skill());
        assertEquals(99, t.targetLevel());
    }

    @Test
    public void validDefenceTarget_constructsOk()
    {
        SkillTarget t = new SkillTarget(Skill.DEFENCE, 2);
        assertEquals(Skill.DEFENCE, t.skill());
        assertEquals(2, t.targetLevel());
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidSkill_prayer_throws()
    {
        new SkillTarget(Skill.PRAYER, 20);
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidSkill_hitpoints_throws()
    {
        new SkillTarget(Skill.HITPOINTS, 20);
    }

    @Test(expected = IllegalArgumentException.class)
    public void levelTooLow_throws()
    {
        new SkillTarget(Skill.ATTACK, 1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void levelTooHigh_throws()
    {
        new SkillTarget(Skill.ATTACK, 100);
    }

    @Test(expected = IllegalArgumentException.class)
    public void levelZero_throws()
    {
        new SkillTarget(Skill.STRENGTH, 0);
    }
}
