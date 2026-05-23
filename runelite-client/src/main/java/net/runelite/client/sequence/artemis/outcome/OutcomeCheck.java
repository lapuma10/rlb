package net.runelite.client.sequence.artemis.outcome;

import java.util.function.Predicate;
import net.runelite.client.sequence.WorldSnapshot;

/**
 * Optional post-condition for {@code click(target, verb, OutcomeCheck)}
 * variants. The base click contract is verb-verified-only (spec §11);
 * an OutcomeCheck adds state-change verification within its
 * {@code withinTicks()} budget. Caller picks the check that fits.
 *
 * <p>Spec §11.
 */
public sealed interface OutcomeCheck
{
	int withinTicks();

	record PlayerAnimChanged(int withinTicks) implements OutcomeCheck {}
	record TargetAnimChanged(int withinTicks) implements OutcomeCheck {}
	record WidgetVisible(int widgetId, int withinTicks) implements OutcomeCheck {}
	record InteractingWithMe(int withinTicks) implements OutcomeCheck {}
	record Custom(Predicate<WorldSnapshot> check, int withinTicks) implements OutcomeCheck {}

	// ─── ergonomic factories ────────────────────────────────────────

	static OutcomeCheck playerAnimChanged(int withinTicks)
	{
		return new PlayerAnimChanged(withinTicks);
	}

	static OutcomeCheck targetAnimChanged(int withinTicks)
	{
		return new TargetAnimChanged(withinTicks);
	}

	/** Default {@code withinTicks = 4} — one or two server ticks of
	 *  latency tolerance. */
	static OutcomeCheck widgetVisible(int widgetId)
	{
		return new WidgetVisible(widgetId, 4);
	}

	/** Default {@code withinTicks = 4}. */
	static OutcomeCheck interactingWithMe()
	{
		return new InteractingWithMe(4);
	}
}
