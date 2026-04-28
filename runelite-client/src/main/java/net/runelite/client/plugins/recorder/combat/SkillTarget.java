package net.runelite.client.plugins.recorder.combat;

import net.runelite.api.Skill;

/**
 * Immutable value class pairing a melee combat skill with a target level.
 *
 * <p>Valid skills: {@link Skill#ATTACK}, {@link Skill#STRENGTH}, {@link Skill#DEFENCE}.
 * Valid target level range: 2–99 (1 is the starting level so there is nothing to train toward).
 */
public record SkillTarget(Skill skill, int targetLevel)
{
    public SkillTarget
    {
        if (skill != Skill.ATTACK && skill != Skill.STRENGTH && skill != Skill.DEFENCE)
        {
            throw new IllegalArgumentException(
                "SkillTarget only supports ATTACK, STRENGTH, or DEFENCE; got: " + skill);
        }
        if (targetLevel < 2 || targetLevel > 99)
        {
            throw new IllegalArgumentException(
                "targetLevel must be in [2, 99]; got: " + targetLevel);
        }
    }
}
