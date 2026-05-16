package net.runelite.client.plugins.recorder.nav.v2.collision;

import java.util.EnumSet;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Tests for {@link PlayerStateBuilder}. Uses Mockito to fake the
 *  {@link Client} so we can drive deterministic state without a live
 *  client. */
public class PlayerStateBuilderTest
{
    @Test
    public void build_capturesSkills()
    {
        Client client = Mockito.mock(Client.class);
        int[] real = new int[Skill.values().length];
        int[] boosted = new int[Skill.values().length];
        real[Skill.ATTACK.ordinal()] = 50;
        boosted[Skill.ATTACK.ordinal()] = 55;
        real[Skill.HITPOINTS.ordinal()] = 60;
        boosted[Skill.HITPOINTS.ordinal()] = 60;
        Mockito.when(client.getRealSkillLevels()).thenReturn(real);
        Mockito.when(client.getBoostedSkillLevels()).thenReturn(boosted);
        Mockito.when(client.isClientThread()).thenReturn(true);
        Mockito.when(client.getWorldType()).thenReturn(EnumSet.noneOf(WorldType.class));

        PlayerState ps = PlayerStateBuilder.fromClient(client, null);

        assertEquals(50, ps.skillLevel(Skill.ATTACK));
        assertEquals(55, ps.boostedLevel(Skill.ATTACK));
        assertEquals(60, ps.skillLevel(Skill.HITPOINTS));
        assertEquals(60, ps.boostedLevel(Skill.HITPOINTS));
    }

    @Test
    public void build_capturesVarbits_byId()
    {
        Client client = Mockito.mock(Client.class);
        Mockito.when(client.getRealSkillLevels()).thenReturn(new int[Skill.values().length]);
        Mockito.when(client.getBoostedSkillLevels()).thenReturn(new int[Skill.values().length]);
        Mockito.when(client.isClientThread()).thenReturn(true);
        Mockito.when(client.getWorldType()).thenReturn(EnumSet.noneOf(WorldType.class));
        Mockito.when(client.getVarbitValue(123)).thenReturn(7);
        Mockito.when(client.getVarpValue(99)).thenReturn(42);

        PlayerState ps = PlayerStateBuilder.fromClient(client, null);

        assertEquals(7, ps.varbit(123));
        assertEquals(42, ps.varplayer(99));
    }

    @Test
    public void build_isMember_membersWorld()
    {
        Client client = Mockito.mock(Client.class);
        Mockito.when(client.getRealSkillLevels()).thenReturn(new int[Skill.values().length]);
        Mockito.when(client.getBoostedSkillLevels()).thenReturn(new int[Skill.values().length]);
        Mockito.when(client.isClientThread()).thenReturn(true);
        Mockito.when(client.getWorldType()).thenReturn(EnumSet.of(WorldType.MEMBERS));

        PlayerState ps = PlayerStateBuilder.fromClient(client, null);

        assertTrue(ps.isMember());
    }

    @Test
    public void build_isMember_freeWorld()
    {
        Client client = Mockito.mock(Client.class);
        Mockito.when(client.getRealSkillLevels()).thenReturn(new int[Skill.values().length]);
        Mockito.when(client.getBoostedSkillLevels()).thenReturn(new int[Skill.values().length]);
        Mockito.when(client.isClientThread()).thenReturn(true);
        Mockito.when(client.getWorldType()).thenReturn(EnumSet.noneOf(WorldType.class));

        PlayerState ps = PlayerStateBuilder.fromClient(client, null);

        assertFalse(ps.isMember());
    }

    @Test
    public void build_inventory_returnsContainer()
    {
        Client client = Mockito.mock(Client.class);
        ItemContainer inv = Mockito.mock(ItemContainer.class);
        ItemContainer eq = Mockito.mock(ItemContainer.class);
        Mockito.when(client.getRealSkillLevels()).thenReturn(new int[Skill.values().length]);
        Mockito.when(client.getBoostedSkillLevels()).thenReturn(new int[Skill.values().length]);
        Mockito.when(client.isClientThread()).thenReturn(true);
        Mockito.when(client.getWorldType()).thenReturn(EnumSet.noneOf(WorldType.class));
        Mockito.when(client.getItemContainer(InventoryID.INVENTORY)).thenReturn(inv);
        Mockito.when(client.getItemContainer(InventoryID.EQUIPMENT)).thenReturn(eq);

        PlayerState ps = PlayerStateBuilder.fromClient(client, null);

        assertNotNull(ps.inventory());
        assertNotNull(ps.equipment());
    }

    @Test
    public void build_offClientThread_throwsBecauseClientThreadNotSupplied()
    {
        Client client = Mockito.mock(Client.class);
        Mockito.when(client.isClientThread()).thenReturn(false);

        try
        {
            PlayerStateBuilder.fromClient(client, null);
            org.junit.Assert.fail("expected IllegalStateException when off-client-thread + no marshaller");
        }
        catch (IllegalStateException expected)
        {
            // ok
        }
    }
}
