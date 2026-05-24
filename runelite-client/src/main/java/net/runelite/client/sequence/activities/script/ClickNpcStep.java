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
import net.runelite.client.sequence.artemis.query.NpcQuery;
import net.runelite.client.sequence.artemis.view.NpcRef;
import net.runelite.client.sequence.blackboard.Blackboard;
import net.runelite.client.sequence.blackboard.BlackboardScope;
import net.runelite.client.sequence.internal.ActionRequest;

/**
 * Step backing {@code Artemis.click(NpcRef, verb)} and
 * {@code Artemis.click(NpcRef, verb, OutcomeCheck)}.
 *
 * <p>{@code onStart} re-resolves the {@link NpcRef} per spec §8
 * (age ≤ {@link #STALE_REF_BUDGET_TICKS}, definition-id match, drift
 * ≤ {@link #STALE_REF_MAX_DRIFT_TILES} from {@code originalLoc}, plane
 * match) and dispatches {@link ActionRequest.Kind#CLICK_NPC} with the
 * <em>live</em> index — the ref's index may have been reused after
 * despawn.
 *
 * <p>{@code check} returns {@link Completion#RUNNING} while the
 * pinned dispatcher reports busy, then {@link Completion.Succeeded}
 * once the dispatcher worker is idle (spec §11 base contract:
 * "menu verb verified before press AND dispatcher reported completion").
 * With an {@link OutcomeCheck}, the OutcomeCheck must also report
 * {@code true} within its tick budget. Per-Step tick timeout is 8.
 */
public final class ClickNpcStep extends ArtemisActionStep
{
	private static final int TIMEOUT_TICKS = 8;

	private final NpcRef ref;
	private final String verb;
	@Nullable private final OutcomeCheck outcome;

	public ClickNpcStep(Artemis artemis, Consumer<StepEvent> stepEventSink, NpcRef ref, String verb)
	{
		this(artemis, stepEventSink, ref, verb, null);
	}

	public ClickNpcStep(Artemis artemis, Consumer<StepEvent> stepEventSink, NpcRef ref, String verb,
		@Nullable OutcomeCheck outcome)
	{
		super(artemis, stepEventSink, false /* gameplay — session gate applies */);
		if (ref == null)
		{
			throw new IllegalArgumentException("NpcRef must not be null");
		}
		if (verb == null || verb.isBlank())
		{
			throw new IllegalArgumentException("verb must not be null/blank");
		}
		this.ref = ref;
		this.verb = verb;
		this.outcome = outcome;
	}

	@Override
	public String name()
	{
		return "ClickNpc(" + (ref.name() != null ? ref.name() : ("id=" + ref.id())) + ", " + verb + ")";
	}

	@Override public int timeoutTicks()       { return TIMEOUT_TICKS; }
	@Override protected String targetType()   { return "npc"; }
	@Override protected String targetId()     { return "npc:" + ref.id() + "/" + ref.index(); }
	@Override protected String targetName()   { return ref.name(); }
	@Override protected String verb()         { return verb; }

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

		// Re-resolve by id with a tight range around the ref's original
		// location; the rotation policy is irrelevant since we apply an
		// identity check after.
		NpcQuery query = NpcQuery.byId(ref.id()).within(STALE_REF_REFIND_RANGE_TILES);
		if (ref.originalLoc() != null)
		{
			query = query.onPlane(ref.originalLoc().getPlane());
		}
		Optional<NpcRef> live = artemis.findNpc(query);
		if (live.isEmpty())
		{
			failOnStart(ctx, REASON_TARGET_NOT_FOUND, "no NPC id=" + ref.id() + " within "
				+ STALE_REF_REFIND_RANGE_TILES + "t");
			return;
		}
		NpcRef fresh = live.get();
		if (!identityMatches(ref, fresh))
		{
			failOnStart(ctx, REASON_STALE_REF, "identity mismatch ref="
				+ ref.originalLoc() + " live=" + fresh.originalLoc());
			return;
		}

		// Snapshot the current player animation so an OutcomeCheck of
		// PlayerAnimChanged can detect post-click change. We pin this
		// under the SHARED key (ArtemisActionStep.K_BASELINE_PLAYER_ANIM)
		// so OutcomeChecks reads our pre-click baseline, not its own
		// post-click self-pinned value (the round-1 1A.3 bug).
		Blackboard step = ctx.bb().scope(BlackboardScope.STEP);
		int baselineAnim = artemis.player() == null ? -1 : artemis.player().animation();
		step.put(K_BASELINE_PLAYER_ANIM, baselineAnim);

		ActionRequest req = ActionRequest.builder()
			.kind(ActionRequest.Kind.CLICK_NPC)
			.channel(ActionRequest.Channel.MOUSE)
			.npcIndex(fresh.index())
			.verb(verb)
			.build();
		ctx.dispatcher().dispatch(req);
	}

	@Override
	protected Completion doCheck(WorldSnapshot s, Blackboard bb, long elapsed)
	{
		if (elapsed >= TIMEOUT_TICKS)
		{
			return Completion.failed(new DiagnosticReason.ActionTimedOut(name(), (int) elapsed));
		}
		// Spec §11 base contract: "menu verb verified before press AND
		// dispatcher reported completion." The pinned dispatcher's
		// isBusy() flag is the engine's completion signal — flips to
		// false in the worker's finally{} after the press chain ends.
		if (!dispatcherIdle())
		{
			return Completion.RUNNING;
		}
		if (outcome == null)
		{
			return new Completion.Succeeded("click dispatched (verb=" + verb + ")");
		}
		return OutcomeChecks.evaluate(outcome, artemis, s, bb, elapsed);
	}

	private static boolean identityMatches(NpcRef ref, NpcRef live)
	{
		if (ref.id() != live.id())
		{
			return false;
		}
		if (ref.originalLoc() != null && live.originalLoc() != null)
		{
			if (ref.originalLoc().getPlane() != live.originalLoc().getPlane())
			{
				return false;
			}
			int dx = Math.abs(ref.originalLoc().getX() - live.originalLoc().getX());
			int dy = Math.abs(ref.originalLoc().getY() - live.originalLoc().getY());
			if (Math.max(dx, dy) > STALE_REF_MAX_DRIFT_TILES)
			{
				return false;
			}
		}
		return true;
	}
}
