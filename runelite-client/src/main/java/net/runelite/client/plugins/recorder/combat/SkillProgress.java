package net.runelite.client.plugins.recorder.combat;

import net.runelite.api.Client;
import net.runelite.api.Experience;
import net.runelite.api.Skill;

/**
 * Pure-data helper: takes a single snapshot of a skill's current level, total
 * XP, and XP remaining to the next level.
 *
 * <p>Usage:
 * <pre>
 *   SkillProgress.Snapshot snap = SkillProgress.read(client, Skill.ATTACK);
 *   int level = snap.level();
 *   int xpLeft = snap.xpToNextLevel();
 * </pre>
 *
 * <p>At level 99 (or above) {@code xpToNextLevel} is 0 — there is no next level.
 *
 * <p>XP values follow the standard OSRS table via {@link Experience#getXpForLevel(int)}.
 */
public final class SkillProgress
{
    private SkillProgress() {}   // utility class

    /**
     * Immutable snapshot of a skill's training state at a point in time.
     *
     * @param skill           the skill this snapshot describes
     * @param level           current real (not boosted) level
     * @param xp              total accumulated XP
     * @param xpToNextLevel   XP remaining to the next level; 0 when at or above 99
     */
    public record Snapshot(Skill skill, int level, int xp, int xpToNextLevel) {}

    /**
     * Reads the current state of {@code skill} from the RuneLite {@link Client}.
     *
     * @param client RuneLite client; must be on the client thread or during a
     *               tick callback for accurate values
     * @param skill  the skill to snapshot
     * @return an immutable {@link Snapshot}
     */
    public static Snapshot read(Client client, Skill skill)
    {
        int xp   = client.getSkillExperience(skill);
        int level = client.getRealSkillLevel(skill);
        int xpToNext;
        if (level >= 99)
        {
            xpToNext = 0;
        }
        else
        {
            int xpForNext = Experience.getXpForLevel(level + 1);
            xpToNext = Math.max(0, xpForNext - xp);
        }
        return new Snapshot(skill, level, xp, xpToNext);
    }
}
