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
import net.runelite.client.sequence.artemis.query.ObjectQuery;
import net.runelite.client.sequence.artemis.view.GameObjRef;
import net.runelite.client.sequence.blackboard.Blackboard;
import net.runelite.client.sequence.blackboard.BlackboardScope;
import net.runelite.client.sequence.internal.ActionRequest;

/**
 * Step backing {@code Artemis.click(GameObjRef, verb)} and the
 * {@link OutcomeCheck} overload.
 *
 * <p>Re-resolves the {@link GameObjRef} per spec §8 before dispatching
 * {@link ActionRequest.Kind#CLICK_GAME_OBJECT} with the live tile +
 * verb. Game objects are static-tile (no per-tick drift) so re-resolution
 * tolerates zero drift — the location either matches or the object is
 * gone. Dispatches with {@code liveTracked=true} so the dispatcher
 * re-aims as the camera moves during the cursor path.
 *
 * <p>{@code check} returns {@link Completion#RUNNING} while the pinned
 * dispatcher reports busy, then {@link Completion.Succeeded} once the
 * worker is idle (spec §11 base contract — dispatcher reported
 * completion). Per-Step tick timeout is 8.
 */
public final class ClickGameObjStep extends ArtemisActionStep
{
	private static final int TIMEOUT_TICKS = 8;

	private final GameObjRef ref;
	private final String verb;
	@Nullable private final OutcomeCheck outcome;

	public ClickGameObjStep(Artemis artemis, Consumer<StepEvent> stepEventSink, GameObjRef ref, String verb)
	{
		this(artemis, stepEventSink, ref, verb, null);
	}

	public ClickGameObjStep(Artemis artemis, Consumer<StepEvent> stepEventSink, GameObjRef ref, String verb,
		@Nullable OutcomeCheck outcome)
	{
		super(artemis, stepEventSink, false);
		if (ref == null)
		{
			throw new IllegalArgumentException("GameObjRef must not be null");
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
		return "ClickGameObj(" + (ref.name() != null ? ref.name() : ("id=" + ref.id())) + ", " + verb + ")";
	}

	@Override public int timeoutTicks()       { return TIMEOUT_TICKS; }
	@Override protected String targetType()   { return "object"; }
	@Override protected String targetId()     { return "object:" + ref.id() + "@" + locKey(ref); }
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

		ObjectQuery query = ObjectQuery.byId(ref.id()).within(STALE_REF_REFIND_RANGE_TILES);
		if (ref.originalLoc() != null)
		{
			query = query.onPlane(ref.originalLoc().getPlane());
		}
		Optional<GameObjRef> live = artemis.findObject(query);
		if (live.isEmpty())
		{
			failOnStart(ctx, REASON_TARGET_NOT_FOUND, "no object id=" + ref.id() + " within "
				+ STALE_REF_REFIND_RANGE_TILES + "t");
			return;
		}
		GameObjRef fresh = live.get();
		if (!identityMatches(ref, fresh))
		{
			failOnStart(ctx, REASON_STALE_REF, "identity mismatch ref="
				+ ref.originalLoc() + " live=" + fresh.originalLoc());
			return;
		}

		// Shared baseline-anim key — see ClickNpcStep.doStart for
		// the rationale (avoid OutcomeChecks self-pinning a post-click
		// animation as its baseline).
		Blackboard step = ctx.bb().scope(BlackboardScope.STEP);
		int baselineAnim = artemis.player() == null ? -1 : artemis.player().animation();
		step.put(K_BASELINE_PLAYER_ANIM, baselineAnim);

		ActionRequest req = ActionRequest.builder()
			.kind(ActionRequest.Kind.CLICK_GAME_OBJECT)
			.channel(ActionRequest.Channel.MOUSE)
			.tile(fresh.originalLoc())
			.objectId(fresh.id())
			.verb(verb)
			.liveTracked(true)
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

	private static boolean identityMatches(GameObjRef ref, GameObjRef live)
	{
		if (ref.id() != live.id())
		{
			return false;
		}
		if (ref.originalLoc() != null && live.originalLoc() != null)
		{
			// Game objects are static — even one tile of drift is a
			// different object (or our query found a sibling of the
			// same id nearby).
			if (!ref.originalLoc().equals(live.originalLoc()))
			{
				return false;
			}
		}
		return true;
	}

	private static String locKey(GameObjRef ref)
	{
		return ref.originalLoc() == null ? "null"
			: (ref.originalLoc().getX() + "," + ref.originalLoc().getY() + "," + ref.originalLoc().getPlane());
	}
}
