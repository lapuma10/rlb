package net.runelite.client.sequence.activities.script;

import java.util.Optional;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.runelite.client.plugins.recorder.analyse.StepEvent;
import net.runelite.client.sequence.Completion;
import net.runelite.client.sequence.StepContext;
import net.runelite.client.sequence.WorldSnapshot;
import net.runelite.client.sequence.affordance.DiagnosticReason;
import net.runelite.client.sequence.artemis.Artemis;
import net.runelite.client.sequence.artemis.outcome.OutcomeCheck;
import net.runelite.client.sequence.artemis.query.WidgetQuery;
import net.runelite.client.sequence.artemis.view.WidgetRef;
import net.runelite.client.sequence.blackboard.Blackboard;
import net.runelite.client.sequence.internal.ActionRequest;

/**
 * Step backing {@code Artemis.click(WidgetRef, verb)} and the
 * {@link OutcomeCheck} overload.
 *
 * <p>Re-resolution per spec §8: re-runs {@code findWidget} with
 * {@code requireVisible=true}, which walks the parent chain via
 * {@code Widget.isHidden()} (CLAUDE.md §1 ban on dispatching to hidden
 * widgets). Builds {@link ActionRequest.Kind#CLICK_WIDGET} with the
 * widget id + verb; for child-slot refs the dispatcher's
 * {@code widgetVerbClick} resolves the child via the engine's hover
 * geometry. Per-Step timeout is 6 ticks (spec §11).
 */
public final class ClickWidgetStep extends ArtemisActionStep
{
	private static final int TIMEOUT_TICKS = 6;

	private final WidgetRef ref;
	private final String verb;
	@Nullable private final OutcomeCheck outcome;

	public ClickWidgetStep(Artemis artemis, Consumer<StepEvent> stepEventSink, WidgetRef ref, String verb)
	{
		this(artemis, stepEventSink, ref, verb, null);
	}

	public ClickWidgetStep(Artemis artemis, Consumer<StepEvent> stepEventSink, WidgetRef ref, String verb,
		@Nullable OutcomeCheck outcome)
	{
		super(artemis, stepEventSink, false);
		if (ref == null)
		{
			throw new IllegalArgumentException("WidgetRef must not be null");
		}
		if (verb == null)
		{
			throw new IllegalArgumentException("verb must not be null (use \"\" for left-click default)");
		}
		this.ref = ref;
		this.verb = verb;
		this.outcome = outcome;
	}

	@Override
	public String name()
	{
		return "ClickWidget(" + ref.widgetId()
			+ (ref.childSlot() != null ? "/" + ref.childSlot() : "")
			+ ", " + (verb.isEmpty() ? "<left-click>" : verb) + ")";
	}

	@Override public int timeoutTicks()       { return TIMEOUT_TICKS; }
	@Override protected String targetType()   { return "widget"; }

	@Override
	protected String targetId()
	{
		return "widget:" + ref.widgetId()
			+ (ref.childSlot() != null ? "/" + ref.childSlot() : "");
	}

	@Override protected String targetName()   { return null; }
	@Override protected String verb()         { return verb.isEmpty() ? null : verb; }

	@Override
	protected void doStart(StepContext ctx)
	{
		long now = ctx.currentTick();
		if (isStaleByAge(ref.observedTick(), now))
		{
			failOnStart(ctx, REASON_STALE_REF,
				"age=" + ticksSince(ref.observedTick(), now) + "t > " + STALE_REF_BUDGET_TICKS);
			return;
		}

		// v1.0 limitation: the dispatcher's CLICK_WIDGET handler only
		// reads widgetId + verb (HumanizedInputDispatcher.handle case
		// CLICK_WIDGET ~ line 538-547). A WidgetRef with a non-null
		// childSlot needs the child's bounds via CLICK_BOUNDS, which
		// is a follow-up. Fail loud here so callers don't get silent
		// parent-bounds clicks.
		if (ref.childSlot() != null)
		{
			failOnStart(ctx, REASON_CHILD_SLOT_NOT_SUPPORTED_V1_0,
				"widget id=" + ref.widgetId() + " child=" + ref.childSlot()
					+ " — child-slot dispatch requires CLICK_BOUNDS (v1.x follow-up)");
			return;
		}

		WidgetQuery query = WidgetQuery.byId(ref.widgetId());
		Optional<WidgetRef> live = artemis.findWidget(query);
		if (live.isEmpty())
		{
			failOnStart(ctx, REASON_TARGET_NOT_FOUND, "widget id=" + ref.widgetId()
				+ " missing/hidden");
			return;
		}

		ActionRequest.ActionRequestBuilder builder = ActionRequest.builder()
			.kind(ActionRequest.Kind.CLICK_WIDGET)
			.channel(ActionRequest.Channel.MOUSE)
			.widgetId(ref.widgetId());
		if (!verb.isEmpty())
		{
			builder = builder.verb(verb);
		}
		ctx.dispatcher().dispatch(builder.build());
	}

	@Override
	protected Completion doCheck(WorldSnapshot s, Blackboard bb, long elapsed)
	{
		if (elapsed >= TIMEOUT_TICKS)
		{
			return Completion.failed(new DiagnosticReason.ActionTimedOut(name(), (int) elapsed));
		}
		if (!dispatcherIdle())
		{
			return Completion.RUNNING;
		}
		if (outcome == null)
		{
			return new Completion.Succeeded("widget click dispatched");
		}
		return OutcomeChecks.evaluate(outcome, artemis, s, bb, elapsed);
	}
}
