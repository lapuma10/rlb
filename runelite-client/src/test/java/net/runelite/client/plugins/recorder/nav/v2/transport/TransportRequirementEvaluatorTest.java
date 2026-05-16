package net.runelite.client.plugins.recorder.nav.v2.transport;

import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.client.plugins.recorder.nav.NavRequest;
import net.runelite.client.plugins.recorder.nav.v2.planner.spi.NavigationContext;
import net.runelite.client.plugins.recorder.nav.v2.planner.spi.PlayerState;
import net.runelite.client.plugins.recorder.nav.v2.planner.spi.WorldSnapshot;
import org.junit.Test;

import static org.junit.Assert.*;

public class TransportRequirementEvaluatorTest
{
	/** Minimal stub PlayerState for unit-testing requirements. */
	static final class StubPlayer implements PlayerState
	{
		final Map<Skill, Integer> levels = new HashMap<>();
		final Map<Skill, Integer> boosted = new HashMap<>();
		final Map<Integer, Integer> varbits = new HashMap<>();
		final Map<Integer, Integer> varplayers = new HashMap<>();
		Map<Integer, Integer> invItems = new HashMap<>();
		Map<Integer, Integer> eqItems = new HashMap<>();
		boolean member = false;

		@Override public int skillLevel(Skill skill) { return levels.getOrDefault(skill, 1); }
		@Override public int boostedLevel(Skill skill) { return boosted.getOrDefault(skill, skillLevel(skill)); }
		@Override public int varbit(int id) { return varbits.getOrDefault(id, 0); }
		@Override public int varplayer(int id) { return varplayers.getOrDefault(id, 0); }
		@Override public boolean isMember() { return member; }

		@Override public ItemContainer inventory() { return stubContainer(invItems); }
		@Override public ItemContainer equipment() { return stubContainer(eqItems); }
	}

	static ItemContainer stubContainer(Map<Integer, Integer> contents)
	{
		// Use a hand-rolled stub. RuneLite's ItemContainer is an interface;
		// we implement only the methods our requirement builders consult.
		return new StubItemContainer(contents);
	}

	static final class StubItemContainer implements ItemContainer
	{
		private final Map<Integer, Integer> contents;
		StubItemContainer(Map<Integer, Integer> contents) { this.contents = new HashMap<>(contents); }

		@Override public int count(int itemId) { return contents.getOrDefault(itemId, 0); }
		@Override public boolean contains(int itemId) { return contents.containsKey(itemId) && contents.get(itemId) > 0; }
		@Override public int size() { return contents.size(); }
		@Override public int count() { return contents.size(); }
		@Override public Item[] getItems() { return new Item[0]; }
		@Override public Item getItem(int slot) { return null; }
		@Override public int find(int itemId) { return -1; }
		@Override public int getId() { return -1; }
		// Node interface (parent of ItemContainer)
		@Override public net.runelite.api.Node getNext() { return null; }
		@Override public net.runelite.api.Node getPrevious() { return null; }
		@Override public long getHash() { return 0L; }
	}

	/** Stub NavigationContext bundling a stub PlayerState. */
	static NavigationContext ctxWith(PlayerState p)
	{
		return new NavigationContext()
		{
			@Override public WorldSnapshot world() { return null; }
			@Override public PlayerState player() { return p; }
			@Override public NavRequest request() { return null; }
		};
	}

	@Test
	public void requireSkill_levelMet_passes()
	{
		StubPlayer p = new StubPlayer();
		p.levels.put(Skill.AGILITY, 25);
		TransportRequirement r = TransportRequirementEvaluator.requireSkill("Agility", 20);
		assertTrue(r.satisfiedBy(ctxWith(p)));
	}

	@Test
	public void requireSkill_levelNotMet_fails()
	{
		StubPlayer p = new StubPlayer();
		p.levels.put(Skill.AGILITY, 15);
		TransportRequirement r = TransportRequirementEvaluator.requireSkill("Agility", 20);
		assertFalse(r.satisfiedBy(ctxWith(p)));
	}

	@Test
	public void requireSkill_unknownSkillName_alwaysFails()
	{
		StubPlayer p = new StubPlayer();
		TransportRequirement r = TransportRequirementEvaluator.requireSkill("NotARealSkill", 1);
		assertFalse(r.satisfiedBy(ctxWith(p)));
	}

	@Test
	public void requireBoostedSkill_considersBoost()
	{
		StubPlayer p = new StubPlayer();
		p.levels.put(Skill.AGILITY, 18);
		p.boosted.put(Skill.AGILITY, 22);
		TransportRequirement real = TransportRequirementEvaluator.requireSkill("Agility", 20);
		TransportRequirement boost = TransportRequirementEvaluator.requireBoostedSkill("Agility", 20);
		assertFalse(real.satisfiedBy(ctxWith(p)));
		assertTrue(boost.satisfiedBy(ctxWith(p)));
	}

	@Test
	public void requireVarbit_exactValue_passes()
	{
		StubPlayer p = new StubPlayer();
		p.varbits.put(4070, 1);
		assertTrue(TransportRequirementEvaluator.requireVarbit(4070, 1).satisfiedBy(ctxWith(p)));
		assertFalse(TransportRequirementEvaluator.requireVarbit(4070, 2).satisfiedBy(ctxWith(p)));
	}

	@Test
	public void requireVarbit_ltGtOperators_work()
	{
		StubPlayer p = new StubPlayer();
		p.varbits.put(4070, 5);
		assertTrue(TransportRequirementEvaluator.requireVarbit(4070, 10, '<').satisfiedBy(ctxWith(p)));
		assertFalse(TransportRequirementEvaluator.requireVarbit(4070, 2, '<').satisfiedBy(ctxWith(p)));
		assertTrue(TransportRequirementEvaluator.requireVarbit(4070, 2, '>').satisfiedBy(ctxWith(p)));
		assertFalse(TransportRequirementEvaluator.requireVarbit(4070, 10, '>').satisfiedBy(ctxWith(p)));
	}

	@Test
	public void requireVarplayer_equalsAndCompare()
	{
		StubPlayer p = new StubPlayer();
		p.varplayers.put(65, 3);
		assertTrue(TransportRequirementEvaluator.requireVarplayer(65, 3).satisfiedBy(ctxWith(p)));
		assertFalse(TransportRequirementEvaluator.requireVarplayer(65, 4).satisfiedBy(ctxWith(p)));
		assertTrue(TransportRequirementEvaluator.requireVarplayer(65, 2, '>').satisfiedBy(ctxWith(p)));
		assertFalse(TransportRequirementEvaluator.requireVarplayer(65, 5, '>').satisfiedBy(ctxWith(p)));
	}

	@Test
	public void requireItem_inventoryHasIt_passes()
	{
		StubPlayer p = new StubPlayer();
		p.invItems.put(995, 250);  // coins
		assertTrue(TransportRequirementEvaluator.requireItem(995, 200).satisfiedBy(ctxWith(p)));
		assertFalse(TransportRequirementEvaluator.requireItem(995, 300).satisfiedBy(ctxWith(p)));
	}

	@Test
	public void requireEquipped_equipmentHasIt_passes()
	{
		StubPlayer p = new StubPlayer();
		p.eqItems.put(4097, 1);
		assertTrue(TransportRequirementEvaluator.requireEquipped(4097).satisfiedBy(ctxWith(p)));
		assertFalse(TransportRequirementEvaluator.requireEquipped(9999).satisfiedBy(ctxWith(p)));
	}

	@Test
	public void requireMember_respectsFlag()
	{
		StubPlayer p = new StubPlayer();
		p.member = false;
		assertFalse(TransportRequirementEvaluator.requireMember().satisfiedBy(ctxWith(p)));
		p.member = true;
		assertTrue(TransportRequirementEvaluator.requireMember().satisfiedBy(ctxWith(p)));
	}

	@Test
	public void requireAll_allMet_passes()
	{
		StubPlayer p = new StubPlayer();
		p.levels.put(Skill.AGILITY, 25);
		p.member = true;
		TransportRequirement r = TransportRequirementEvaluator.requireAll(
			TransportRequirementEvaluator.requireSkill("Agility", 20),
			TransportRequirementEvaluator.requireMember());
		assertTrue(r.satisfiedBy(ctxWith(p)));
	}

	@Test
	public void requireAll_oneFails_overall_fails()
	{
		StubPlayer p = new StubPlayer();
		p.levels.put(Skill.AGILITY, 25);
		p.member = false;
		TransportRequirement r = TransportRequirementEvaluator.requireAll(
			TransportRequirementEvaluator.requireSkill("Agility", 20),
			TransportRequirementEvaluator.requireMember());
		assertFalse(r.satisfiedBy(ctxWith(p)));
	}

	@Test
	public void requireAny_oneMet_passes()
	{
		StubPlayer p = new StubPlayer();
		p.invItems.put(995, 5);
		TransportRequirement r = TransportRequirementEvaluator.requireAny(
			TransportRequirementEvaluator.requireItem(995, 5),
			TransportRequirementEvaluator.requireItem(9999, 1));
		assertTrue(r.satisfiedBy(ctxWith(p)));
	}

	@Test
	public void requireAny_noneMet_fails()
	{
		StubPlayer p = new StubPlayer();
		TransportRequirement r = TransportRequirementEvaluator.requireAny(
			TransportRequirementEvaluator.requireItem(995, 1),
			TransportRequirementEvaluator.requireItem(9999, 1));
		assertFalse(r.satisfiedBy(ctxWith(p)));
	}

	@Test
	public void requireAll_emptyList_isAlwaysTrue()
	{
		assertSame(TransportRequirement.NONE,
			TransportRequirementEvaluator.requireAll(java.util.Collections.emptyList()));
	}

	@Test
	public void requireAny_emptyList_isAlwaysFalse()
	{
		TransportRequirement r = TransportRequirementEvaluator.requireAny(java.util.Collections.emptyList());
		assertFalse(r.satisfiedBy(ctxWith(new StubPlayer())));
	}

	@Test
	public void requireAnyItemFromList_parsesAlternation()
	{
		StubPlayer p = new StubPlayer();
		p.invItems.put(13122, 1);
		TransportRequirement r =
			TransportRequirementEvaluator.requireAnyItemFromList("13121=1||13122=1||13123=1");
		assertTrue(r.satisfiedBy(ctxWith(p)));
		p.invItems.clear();
		p.invItems.put(13124, 1);
		assertFalse(r.satisfiedBy(ctxWith(p)));
	}

	@Test
	public void requireAnyItemFromList_singlePipeAlternation()
	{
		StubPlayer p = new StubPlayer();
		p.invItems.put(995, 5);
		TransportRequirement r =
			TransportRequirementEvaluator.requireAnyItemFromList("4031=1|995=5");
		assertTrue(r.satisfiedBy(ctxWith(p)));
	}
}
