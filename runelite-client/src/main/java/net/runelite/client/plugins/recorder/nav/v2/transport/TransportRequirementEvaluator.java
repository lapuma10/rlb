package net.runelite.client.plugins.recorder.nav.v2.transport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.client.plugins.recorder.nav.v2.collision.PlayerState;
import net.runelite.client.plugins.recorder.nav.v2.predicate.NavigationContext;

/** Composable builders for {@link TransportRequirement} instances.
 *
 *  <p>Spec §3 defines the {@code TransportRequirement} contract; this
 *  class provides the canonical builder set used by
 *  {@link TransportTableLoader} when parsing TSV rows, and exposes
 *  composers ({@link #requireAll}, {@link #requireAny}) for the
 *  Lane-4 planner.
 *
 *  <p>All builders return pure-function evaluators. They consult
 *  the {@link NavigationContext}'s {@link PlayerState} and never
 *  mutate it.
 *
 *  <p><b>Skill name parsing</b>: Skretzo writes skills as "{@code N
 *  SkillName}". The name is mapped to a {@link Skill} via
 *  {@link Skill#valueOf(String)} with toupper. Unknown skill names
 *  produce a requirement that is always unsatisfied (loud at
 *  evaluation, not at load — the TSV evolves faster than the runelite
 *  Skill enum). */
public final class TransportRequirementEvaluator
{
	private TransportRequirementEvaluator() {}

	/** Real (un-boosted) skill level ≥ required. */
	public static TransportRequirement requireSkill(String skillName, int level)
	{
		final Skill skill;
		try
		{
			skill = Skill.valueOf(skillName.toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException ex)
		{
			// Unknown skill — always-unsatisfied so the link is excluded.
			return ctx -> false;
		}
		return ctx -> {
			PlayerState p = ctx.player();
			if (p == null) return false;
			return p.skillLevel(skill) >= level;
		};
	}

	/** Boosted skill level ≥ required (use for click-once / agility
	 *  shortcuts that accept boosted levels). */
	public static TransportRequirement requireBoostedSkill(String skillName, int level)
	{
		final Skill skill;
		try
		{
			skill = Skill.valueOf(skillName.toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException ex)
		{
			return ctx -> false;
		}
		return ctx -> {
			PlayerState p = ctx.player();
			if (p == null) return false;
			return p.boostedLevel(skill) >= level;
		};
	}

	/** Varbit equals/lt/gt comparison with {@code value}. */
	public static TransportRequirement requireVarbit(int id, int value, char operator)
	{
		switch (operator)
		{
			case '=':
				return ctx -> ctx.player() != null && ctx.player().varbit(id) == value;
			case '<':
				return ctx -> ctx.player() != null && ctx.player().varbit(id) < value;
			case '>':
				return ctx -> ctx.player() != null && ctx.player().varbit(id) > value;
			default:
				throw new IllegalArgumentException("unknown varbit operator: " + operator);
		}
	}

	/** Varbit must equal value. */
	public static TransportRequirement requireVarbit(int id, int value)
	{
		return requireVarbit(id, value, '=');
	}

	/** Varplayer must equal value. */
	public static TransportRequirement requireVarplayer(int id, int value)
	{
		return requireVarplayer(id, value, '=');
	}

	/** Varplayer equals/lt/gt comparison with {@code value}. */
	public static TransportRequirement requireVarplayer(int id, int value, char operator)
	{
		switch (operator)
		{
			case '=':
				return ctx -> ctx.player() != null && ctx.player().varplayer(id) == value;
			case '<':
				return ctx -> ctx.player() != null && ctx.player().varplayer(id) < value;
			case '>':
				return ctx -> ctx.player() != null && ctx.player().varplayer(id) > value;
			default:
				throw new IllegalArgumentException("unknown varplayer operator: " + operator);
		}
	}

	/** Inventory must contain ≥ {@code quantity} of {@code itemId}. */
	public static TransportRequirement requireItem(int itemId, int quantity)
	{
		return ctx -> {
			PlayerState p = ctx.player();
			if (p == null) return false;
			ItemContainer inv = p.inventory();
			if (inv == null) return false;
			return inv.count(itemId) >= quantity;
		};
	}

	/** Equipment must contain {@code itemId} (slot agnostic). */
	public static TransportRequirement requireEquipped(int itemId)
	{
		return ctx -> {
			PlayerState p = ctx.player();
			if (p == null) return false;
			ItemContainer eq = p.equipment();
			if (eq == null) return false;
			return eq.contains(itemId);
		};
	}

	/** Player is on a members' world. */
	public static TransportRequirement requireMember()
	{
		return ctx -> ctx.player() != null && ctx.player().isMember();
	}

	/** Conjunction (all must hold). Empty list ⇒
	 *  {@link TransportRequirement#NONE}. */
	public static TransportRequirement requireAll(List<? extends TransportRequirement> reqs)
	{
		if (reqs == null || reqs.isEmpty()) return TransportRequirement.NONE;
		final List<TransportRequirement> copy = new ArrayList<>(reqs);
		return ctx -> {
			for (TransportRequirement r : copy)
			{
				if (!r.satisfiedBy(ctx)) return false;
			}
			return true;
		};
	}

	/** Conjunction varargs. */
	public static TransportRequirement requireAll(TransportRequirement... reqs)
	{
		return requireAll(Arrays.asList(reqs));
	}

	/** Disjunction (any must hold). Empty list ⇒ always-false. */
	public static TransportRequirement requireAny(List<? extends TransportRequirement> reqs)
	{
		if (reqs == null || reqs.isEmpty()) return ctx -> false;
		final List<TransportRequirement> copy = new ArrayList<>(reqs);
		return ctx -> {
			for (TransportRequirement r : copy)
			{
				if (r.satisfiedBy(ctx)) return true;
			}
			return false;
		};
	}

	/** Disjunction varargs. */
	public static TransportRequirement requireAny(TransportRequirement... reqs)
	{
		return requireAny(Arrays.asList(reqs));
	}

	/** Parse Skretzo's item-alternation form "{@code id=qty||id=qty||...}"
	 *  or single-pipe "{@code id=qty|id=qty}" into a disjunction. Used
	 *  by the loader for rows like "{@code 13121=1||13122=1||13123=1}"
	 *  and "{@code SHANTAY_PASS=1|COINS=5}". Invalid sub-entries are
	 *  silently dropped from the disjunction. If every arm is invalid
	 *  (e.g. all symbolic), the resulting requirement is {@link
	 *  TransportRequirement#NONE} so the link is still loadable —
	 *  runtime usage will surface what's missing. */
	public static TransportRequirement requireAnyItemFromList(String spec)
	{
		List<TransportRequirement> reqs = new ArrayList<>();
		// Split on one-or-more '|' to cover both "||" and "|" forms.
		for (String alt : spec.split("\\|+"))
		{
			String a = alt.trim();
			if (a.isEmpty()) continue;
			String[] kv = a.split("=");
			if (kv.length != 2) continue;
			try
			{
				int id = Integer.parseInt(kv[0].trim());
				int qty = Integer.parseInt(kv[1].trim());
				reqs.add(requireItem(id, qty));
			}
			catch (NumberFormatException ignored)
			{
				// symbolic constant in this arm — drop and continue
			}
		}
		if (reqs.isEmpty()) return TransportRequirement.NONE;
		return requireAny(reqs);
	}
}
