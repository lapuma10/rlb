package net.runelite.client.plugins.recorder.combat;

import net.runelite.api.Client;
import net.runelite.api.Skill;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class SkillProgressTest
{
    // OSRS XP thresholds (from Experience.getXpForLevel):
    //   Level 1 → 0 XP
    //   Level 2 → 83 XP
    //   Level 3 → 174 XP
    //   Level 99 → 13 034 431 XP

    private Client mockClient(Skill skill, int level, int xp)
    {
        Client client = mock(Client.class);
        when(client.getSkillExperience(skill)).thenReturn(xp);
        when(client.getRealSkillLevel(skill)).thenReturn(level);
        return client;
    }

    @Test
    public void read_level1_xpZero()
    {
        // Player starts at level 1 with 0 XP.
        Client client = mockClient(Skill.ATTACK, 1, 0);
        SkillProgress.Snapshot snap = SkillProgress.read(client, Skill.ATTACK);

        assertEquals(Skill.ATTACK, snap.skill());
        assertEquals(1, snap.level());
        assertEquals(0, snap.xp());
        // Need 83 XP to reach level 2.
        assertEquals(83, snap.xpToNextLevel());
    }

    @Test
    public void read_level2_xp83()
    {
        // Exactly 83 XP → level 2, 91 XP to reach level 3 (174 − 83 = 91).
        Client client = mockClient(Skill.ATTACK, 2, 83);
        SkillProgress.Snapshot snap = SkillProgress.read(client, Skill.ATTACK);

        assertEquals(2, snap.level());
        assertEquals(83, snap.xp());
        assertEquals(91, snap.xpToNextLevel());
    }

    @Test
    public void read_level2_midProgress()
    {
        // 100 XP → still level 2 (needs 174 for level 3); 74 XP to go.
        Client client = mockClient(Skill.ATTACK, 2, 100);
        SkillProgress.Snapshot snap = SkillProgress.read(client, Skill.ATTACK);

        assertEquals(2, snap.level());
        assertEquals(100, snap.xp());
        assertEquals(74, snap.xpToNextLevel());
    }

    @Test
    public void read_level99_xpToNextIsZero()
    {
        // At level 99 there is no next level; xpToNextLevel must be 0.
        int xpAtLevel99 = 13_034_431;
        Client client = mockClient(Skill.STRENGTH, 99, xpAtLevel99);
        SkillProgress.Snapshot snap = SkillProgress.read(client, Skill.STRENGTH);

        assertEquals(99, snap.level());
        assertEquals(0, snap.xpToNextLevel());
    }

    @Test
    public void read_defenceSkill_returnsCorrectSkill()
    {
        Client client = mockClient(Skill.DEFENCE, 5, 388);
        SkillProgress.Snapshot snap = SkillProgress.read(client, Skill.DEFENCE);

        assertEquals(Skill.DEFENCE, snap.skill());
        assertEquals(5, snap.level());
    }
}
