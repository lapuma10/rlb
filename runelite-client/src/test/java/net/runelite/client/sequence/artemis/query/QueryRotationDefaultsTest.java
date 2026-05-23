package net.runelite.client.sequence.artemis.query;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;

/**
 * Pins the per-query-type {@link RotationPolicy} defaults from spec §7.
 * Spec changes that flip a default should make this test fail loudly
 * so reviewers see the behavioural change before code lands.
 */
public class QueryRotationDefaultsTest
{
	@Test
	public void npcQueryByNameUsesClosestWithSlackTwo()
	{
		assertEquals(new RotationPolicy.ClosestWithSlack(2),
			NpcQuery.byName("Cow").rotation());
	}

	@Test
	public void npcQueryByIdUsesClosestWithSlackTwo()
	{
		assertEquals(new RotationPolicy.ClosestWithSlack(2),
			NpcQuery.byId(2693).rotation());
	}

	@Test
	public void objectQueryByNameUsesClosestWithSlackOne()
	{
		assertEquals(new RotationPolicy.ClosestWithSlack(1),
			ObjectQuery.byName("Bank booth").rotation());
	}

	@Test
	public void objectQueryByIdUsesClosestWithSlackOne()
	{
		assertEquals(new RotationPolicy.ClosestWithSlack(1),
			ObjectQuery.byId(25808).rotation());
	}

	@Test
	public void itemQueryByIdUsesClosestWithSlackOne()
	{
		assertEquals(new RotationPolicy.ClosestWithSlack(1),
			ItemQuery.byId(526).rotation());
	}

	@Test
	public void itemQueryByNameUsesClosestWithSlackOne()
	{
		assertEquals(new RotationPolicy.ClosestWithSlack(1),
			ItemQuery.byName("Bones").rotation());
	}

	// ─── WidgetQuery: NO RotationPolicy per spec §6/§7 ──────────────

	@Test
	public void widgetQueryHasNoRotationPolicyFieldOrComponent()
	{
		for (RecordComponent c : WidgetQuery.class.getRecordComponents())
		{
			assertNotEquals(
				"WidgetQuery must not declare a RotationPolicy record component (spec §6).",
				RotationPolicy.class, c.getType());
		}
	}

	@Test
	public void widgetQueryHasNoRotationMethod()
	{
		for (Method m : WidgetQuery.class.getDeclaredMethods())
		{
			assertFalse(
				"WidgetQuery must not declare a rotation(...) method (spec §6/§7).",
				m.getName().equals("rotation"));
		}
	}
}
