package net.runelite.client.plugins.recorder.combat;

import net.runelite.api.Skill;
import org.junit.Test;
import java.util.Optional;
import static org.junit.Assert.*;

public class CombatStyleIndexTest
{
    @Test
    public void widgetIndices_matchSlotNumbers()
    {
        assertEquals(0, CombatStyleIndex.ATTACK_PRIMARY.widgetIndex());
        assertEquals(1, CombatStyleIndex.STRENGTH_PRIMARY.widgetIndex());
        assertEquals(2, CombatStyleIndex.DEFENCE_PRIMARY.widgetIndex());
        assertEquals(3, CombatStyleIndex.SHARED_CONTROLLED.widgetIndex());
    }

    @Test
    public void forSkill_attack_returnsAttackPrimary()
    {
        Optional<CombatStyleIndex> result = CombatStyleIndex.forSkill(Skill.ATTACK);
        assertTrue(result.isPresent());
        assertEquals(CombatStyleIndex.ATTACK_PRIMARY, result.get());
    }

    @Test
    public void forSkill_strength_returnsStrengthPrimary()
    {
        Optional<CombatStyleIndex> result = CombatStyleIndex.forSkill(Skill.STRENGTH);
        assertTrue(result.isPresent());
        assertEquals(CombatStyleIndex.STRENGTH_PRIMARY, result.get());
    }

    @Test
    public void forSkill_defence_returnsDefencePrimary()
    {
        Optional<CombatStyleIndex> result = CombatStyleIndex.forSkill(Skill.DEFENCE);
        assertTrue(result.isPresent());
        assertEquals(CombatStyleIndex.DEFENCE_PRIMARY, result.get());
    }

    @Test
    public void forSkill_prayer_returnsEmpty()
    {
        assertTrue(CombatStyleIndex.forSkill(Skill.PRAYER).isEmpty());
    }

    @Test
    public void forSkill_hitpoints_returnsEmpty()
    {
        assertTrue(CombatStyleIndex.forSkill(Skill.HITPOINTS).isEmpty());
    }

    @Test
    public void forSkill_magic_returnsEmpty()
    {
        assertTrue(CombatStyleIndex.forSkill(Skill.MAGIC).isEmpty());
    }

    @Test
    public void sharedControlled_hasWidgetIndex3()
    {
        // SHARED_CONTROLLED is accessible and maps to slot 3.
        assertEquals(3, CombatStyleIndex.SHARED_CONTROLLED.widgetIndex());
    }
}
