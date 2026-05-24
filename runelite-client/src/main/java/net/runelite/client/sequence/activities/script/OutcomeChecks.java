package net.runelite.client.sequence.activities.script;

import net.runelite.client.sequence.Completion;
import net.runelite.client.sequence.WorldSnapshot;
import net.runelite.client.sequence.affordance.DiagnosticReason;
import net.runelite.client.sequence.artemis.Artemis;
import net.runelite.client.sequence.artemis.outcome.OutcomeCheck;
import net.runelite.client.sequence.artemis.query.WidgetQuery;
import net.runelite.client.sequence.blackboard.Blackboard;
import net.runelite.client.sequence.blackboard.BlackboardScope;

/**
 * Engine-internal evaluator for {@link OutcomeCheck} post-conditions
 * tied to a click {@link ArtemisActionStep}. Centralized so every click
 * variant uses identical semantics.
 *
 * <p>Returns:
 * <ul>
 *   <li>{@link Completion.Succeeded} when the check matches within its
 *       {@code withinTicks} budget.</li>
 *   <li>{@link Completion#RUNNING} while still within the budget and
 *       no match observed yet.</li>
 *   <li>{@link Completion.Failed} when the budget elapsed without a
 *       match (diagnostic = {@link ArtemisActionStep#REASON_OUTCOME_NOT_MET}).</li>
 * </ul>
 *
 * <p>v1 support matrix:
 * <ul>
 *   <li>{@link OutcomeCheck.PlayerAnimChanged} — compares
 *       {@code artemis.player().animation()} against the baseline
 *       captured by the click Step at dispatch time under the SHARED
 *       {@link ArtemisActionStep#K_BASELINE_PLAYER_ANIM} key. If the
 *       click Step did not pin a baseline (defensive — shouldn't
 *       happen in v1), the evaluator falls back to treating "no
 *       baseline yet" as "no change observed" and reports RUNNING
 *       until budget elapses.</li>
 *   <li>{@link OutcomeCheck.WidgetVisible} — re-runs
 *       {@code artemis.findWidget(WidgetQuery.byId(id))} (default
 *       visibility-required).</li>
 *   <li>{@link OutcomeCheck.Custom} — invokes the predicate against
 *       the engine snapshot.</li>
 *   <li>{@link OutcomeCheck.TargetAnimChanged} /
 *       {@link OutcomeCheck.InteractingWithMe} — <b>v1 limitation:</b>
 *       these need per-NPC state plumbing that Artemis v1 does not
 *       expose ({@code PlayerState}/{@code NpcRef} carry only
 *       static-at-find-time fields). They evaluate as RUNNING for the
 *       budget and Failed on budget expiry with diagnostic
 *       {@code OUTCOME_NOT_SUPPORTED_V1}. Callers wanting NPC-side
 *       observation should use {@code Custom} with a Predicate that
 *       reads from a domain view, or wait for v1.x to widen the
 *       {@link Artemis} read surface.</li>
 * </ul>
 *
 * <p>Package-private; the {@code Click*Step} subclasses are the only
 * call sites. Steps emit the StepEvent ({@code succeeded}/{@code failed})
 * automatically via {@link ArtemisActionStep#check} based on the
 * {@link Completion} returned here.
 */
final class OutcomeChecks
{
	/** Reason code for the v1 outcome-check limitation (see class
	 *  javadoc). Distinct from {@link ArtemisActionStep#REASON_OUTCOME_NOT_MET}
	 *  so callers / dashboards can disambiguate "we waited and the
	 *  outcome did not occur" from "the engine cannot observe this
	 *  outcome yet." */
	static final String REASON_OUTCOME_NOT_SUPPORTED_V1 = "OUTCOME_NOT_SUPPORTED_V1";

	private OutcomeChecks() {}

	static Completion evaluate(OutcomeCheck oc, Artemis artemis, WorldSnapshot s,
		Blackboard bb, long elapsed)
	{
		int budget = Math.max(0, oc.withinTicks());

		if (oc instanceof OutcomeCheck.PlayerAnimChanged)
		{
			return evalPlayerAnimChanged(artemis, bb, elapsed, budget);
		}
		if (oc instanceof OutcomeCheck.WidgetVisible wv)
		{
			return evalWidgetVisible(artemis, wv.widgetId(), elapsed, budget);
		}
		if (oc instanceof OutcomeCheck.Custom custom)
		{
			return evalCustom(custom, s, elapsed, budget);
		}
		if (oc instanceof OutcomeCheck.TargetAnimChanged
			|| oc instanceof OutcomeCheck.InteractingWithMe)
		{
			return unsupportedV1(elapsed, budget, oc.getClass().getSimpleName());
		}
		// Unknown sealed case (forward-compat) — treat as unsupported.
		return unsupportedV1(elapsed, budget, oc.getClass().getSimpleName());
	}

	private static Completion evalPlayerAnimChanged(Artemis artemis, Blackboard bb,
		long elapsed, int budget)
	{
		// Read the SHARED baseline pinned by the click Step at dispatch
		// time. NEVER self-pin here — by the time evalPlayerAnimChanged
		// runs (after dispatcher idle, typically ≥1 tick post-press),
		// the player animation has already moved into the post-click
		// state, and self-pinning would make every check false-negative.
		Blackboard step = bb.scope(BlackboardScope.STEP);
		Integer baseline = step.get(ArtemisActionStep.K_BASELINE_PLAYER_ANIM).orElse(null);
		int currentAnim = artemis.player() == null ? -1 : artemis.player().animation();
		if (baseline == null)
		{
			// Defensive — click Step should always pin. Without a
			// baseline we cannot decide; wait out the budget then fail.
			if (elapsed >= budget)
			{
				return Completion.failed(new DiagnosticReason.Unknown(
					ArtemisActionStep.REASON_OUTCOME_NOT_MET));
			}
			return Completion.RUNNING;
		}
		if (currentAnim != baseline)
		{
			return new Completion.Succeeded("player animation changed: " + baseline + " → " + currentAnim);
		}
		if (elapsed >= budget)
		{
			return Completion.failed(new DiagnosticReason.Unknown(
				ArtemisActionStep.REASON_OUTCOME_NOT_MET));
		}
		return Completion.RUNNING;
	}

	private static Completion evalWidgetVisible(Artemis artemis, int widgetId,
		long elapsed, int budget)
	{
		boolean visible = artemis.findWidget(WidgetQuery.byId(widgetId)).isPresent();
		if (visible)
		{
			return new Completion.Succeeded("widget " + widgetId + " visible");
		}
		if (elapsed >= budget)
		{
			return Completion.failed(new DiagnosticReason.Unknown(
				ArtemisActionStep.REASON_OUTCOME_NOT_MET));
		}
		return Completion.RUNNING;
	}

	private static Completion evalCustom(OutcomeCheck.Custom custom, WorldSnapshot s,
		long elapsed, int budget)
	{
		boolean matches;
		try
		{
			matches = custom.check().test(s);
		}
		catch (RuntimeException e)
		{
			return Completion.failed(new DiagnosticReason.Unknown(
				"OUTCOME_PREDICATE_THREW: " + e.getMessage()));
		}
		if (matches)
		{
			return new Completion.Succeeded("custom outcome matched");
		}
		if (elapsed >= budget)
		{
			return Completion.failed(new DiagnosticReason.Unknown(
				ArtemisActionStep.REASON_OUTCOME_NOT_MET));
		}
		return Completion.RUNNING;
	}

	private static Completion unsupportedV1(long elapsed, int budget, String caseName)
	{
		// Wait out the budget so a Step using an unsupported outcome
		// doesn't immediately short-circuit — gives the operator time
		// to notice during burner runs that the wrong outcome was
		// chosen, rather than failing silently before the click could
		// land. After budget: fail with a distinct diagnostic.
		if (elapsed >= budget)
		{
			return Completion.failed(new DiagnosticReason.Unknown(
				REASON_OUTCOME_NOT_SUPPORTED_V1 + ":" + caseName));
		}
		return Completion.RUNNING;
	}
}
