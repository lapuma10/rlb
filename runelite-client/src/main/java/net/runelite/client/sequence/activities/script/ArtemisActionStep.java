package net.runelite.client.sequence.activities.script;

import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.recorder.analyse.StepEvent;
import net.runelite.client.sequence.Completion;
import net.runelite.client.sequence.Failure;
import net.runelite.client.sequence.PreemptionPolicy;
import net.runelite.client.sequence.Recovery;
import net.runelite.client.sequence.Step;
import net.runelite.client.sequence.StepContext;
import net.runelite.client.sequence.WorldSnapshot;
import net.runelite.client.sequence.affordance.DiagnosticReason;
import net.runelite.client.sequence.artemis.Artemis;
import net.runelite.client.sequence.blackboard.Blackboard;
import net.runelite.client.sequence.blackboard.BlackboardKey;
import net.runelite.client.sequence.blackboard.BlackboardScope;
import net.runelite.client.sequence.dispatch.InputDispatcher;

/**
 * Engine-internal base for every Artemis-returned action {@link Step}.
 * Centralises three concerns the spec requires every action Step to
 * honour identically:
 *
 * <ol>
 *   <li><b>Session gate.</b> Gameplay Steps refuse to start when
 *       {@code artemis.session().shouldContinue()} is false (spec §3
 *       principle 5). Maintenance Steps (constructed with
 *       {@code maintenance=true}) bypass the gate so {@code logout()}
 *       can still run after the session ends.</li>
 *   <li><b>Structured {@link StepEvent} emission.</b> {@code started}
 *       on dispatch, {@code succeeded}/{@code failed} on completion,
 *       always populating the typed fields ({@code targetType},
 *       {@code targetId}, {@code targetName}, {@code verb},
 *       {@code ticksElapsed}, {@code diagnosticReason}) and leaving
 *       {@code detail} for what those cannot carry. Phase 7's diversity
 *       dashboard consumes these.</li>
 *   <li><b>Failure pinning.</b> A failure surfaced inside {@code onStart}
 *       (stale ref, missing target, session exhausted) is pinned into
 *       the step-scope blackboard so {@code check()} reports
 *       {@link Completion.Failed} on its first invocation rather than
 *       hanging in {@link Completion.Running} forever waiting for a
 *       dispatch that never happened.</li>
 * </ol>
 *
 * <p>Subclasses implement the action-shape:
 * <ul>
 *   <li>{@link #name()} / {@link #timeoutTicks()} — Step identity</li>
 *   <li>{@link #targetType()} / {@link #targetId()} / {@link #targetName()}
 *       / {@link #verb()} — StepEvent payload</li>
 *   <li>{@link #doStart(StepContext)} — re-resolve the target, build the
 *       {@code ActionRequest}, dispatch via {@code ctx.dispatcher()}.
 *       Call {@link #failOnStart(StepContext, String, String)} to record
 *       a failure (e.g. stale ref) without dispatching.</li>
 *   <li>{@link #doCheck(WorldSnapshot, Blackboard, long)} — poll for
 *       completion. Returning {@link Completion.Succeeded} /
 *       {@link Completion.Failed} triggers the corresponding
 *       {@code StepEvent} automatically — do not emit from the
 *       subclass.</li>
 * </ul>
 *
 * <p>Default recovery is {@link Recovery.Retry} with three attempts
 * (matches spec §12.6 table for STALE_REF / verb-not-on-menu /
 * dispatcher error). Subclasses override {@link #onFailure} when a
 * different policy applies (e.g. {@code take} aborts on inventory
 * full).
 *
 * <p>Package-private — script callers see the {@link Step} interface
 * returned from {@code Artemis}, never this base class. New
 * Artemis-action Steps extend this class.
 */
@Slf4j
abstract class ArtemisActionStep implements Step
{
	// ── Re-resolution constants (spec §8) ───────────────────────────

	/** Maximum age of a view ref (ticks) before it is considered stale
	 *  and the Step refuses to dispatch on it. Spec §8 default. */
	protected static final long STALE_REF_BUDGET_TICKS = 8L;

	/** Re-find range (tiles) used during re-resolution. Spec §8 allows
	 *  the live entity to be ≤2 tiles from {@code originalLoc}; we query
	 *  with a 4-tile radius and apply the 2-tile post-filter so the
	 *  candidate set is small. */
	protected static final int STALE_REF_REFIND_RANGE_TILES = 4;

	/** Max tile delta between the live entity and the ref's
	 *  {@code originalLoc} for the ref to still count as "the same
	 *  thing." Spec §8 sets this at 2 tiles. */
	protected static final int STALE_REF_MAX_DRIFT_TILES = 2;

	// ── Diagnostic reason codes (StepEvent.diagnosticReason) ────────

	/** Re-resolution rejected the ref (age, identity mismatch, drift). */
	protected static final String REASON_STALE_REF = "STALE_REF";

	/** Live re-resolution returned no candidate. */
	protected static final String REASON_TARGET_NOT_FOUND = "TARGET_NOT_FOUND";

	/** Session shape gate refused start (gameplay Step, session over). */
	protected static final String REASON_SESSION_EXHAUSTED = "SESSION_EXHAUSTED";

	/** Inventory has no free slot for a {@code take(...)} action. */
	protected static final String REASON_INVENTORY_FULL = "INVENTORY_FULL";

	/** Per-Step tick timeout elapsed with no Succeeded/Failed observed. */
	protected static final String REASON_TIMEOUT = "TIMEOUT";

	/** {@code onStart} threw an exception while attempting to dispatch. */
	protected static final String REASON_ONSTART_EXCEPTION = "ONSTART_EXCEPTION";

	/** OutcomeCheck did not observe the expected post-condition within
	 *  its tick budget. */
	protected static final String REASON_OUTCOME_NOT_MET = "OUTCOME_NOT_MET";

	/** {@code doCheck} threw an exception while polling completion.
	 *  Surfaced as a typed Failed so callers see the failure instead
	 *  of the engine treating it as a runtime crash. */
	protected static final String REASON_DOCHECK_EXCEPTION = "DOCHECK_EXCEPTION";

	/** v1.0 limitation: dispatcher's {@code CLICK_WIDGET} ignores
	 *  {@code childIndex} (only honors {@code widgetId} + {@code verb}).
	 *  A {@link net.runelite.client.sequence.artemis.view.WidgetRef}
	 *  with a non-null {@code childSlot} therefore cannot be dispatched
	 *  via plain {@code CLICK_WIDGET} — the proper path is the
	 *  child's bounds via {@code CLICK_BOUNDS}, plumbed in a follow-up.
	 *  Until then, {@link ClickWidgetStep} fails loud with this
	 *  diagnostic rather than silently clicking the parent. */
	protected static final String REASON_CHILD_SLOT_NOT_SUPPORTED_V1_0 = "CHILD_SLOT_NOT_SUPPORTED_V1_0";

	// ── Internal blackboard keys ────────────────────────────────────

	private static final BlackboardKey<Long> K_START_TICK =
		BlackboardKey.of("artemisStep.startTick", Long.class);
	private static final BlackboardKey<String> K_FAIL_REASON =
		BlackboardKey.of("artemisStep.failReason", String.class);
	private static final BlackboardKey<String> K_FAIL_DETAIL =
		BlackboardKey.of("artemisStep.failDetail", String.class);
	/** Shared baseline animation pinned by click Steps at dispatch
	 *  time, read by {@link OutcomeChecks#evalPlayerAnimChanged} —
	 *  single source of truth so the outcome evaluator never
	 *  self-pins a post-click animation as its baseline. */
	static final BlackboardKey<Integer> K_BASELINE_PLAYER_ANIM =
		BlackboardKey.of("artemisStep.baselinePlayerAnim", Integer.class);

	// ── Fields ──────────────────────────────────────────────────────

	protected final Artemis artemis;
	/** StepEvent sink. Bound at construction to
	 *  {@code RecorderManager::recordStepEvent} in production
	 *  (ArtemisImpl converts its {@code RecorderManager} to a
	 *  {@code Consumer<StepEvent>} via the method-reference at
	 *  construction time) and to a list-collector in unit tests.
	 *  {@code null} disables emission. */
	protected final Consumer<StepEvent> stepEventSink;
	/** When {@code true} the Step bypasses
	 *  {@code artemis.session().shouldContinue()} on start. Reserved
	 *  for maintenance Steps ({@code idle}, {@code logout}); gameplay
	 *  Steps construct with {@code false} (spec §3 principle 5). */
	protected final boolean maintenance;
	/** Engine dispatcher pinned in {@link #onStart} so {@link #doCheck}
	 *  can observe completion via {@link InputDispatcher#isBusy()} —
	 *  {@code check()} does not receive a {@link StepContext} and the
	 *  spec §11 base success contract requires "dispatcher reported
	 *  completion", not a wall-clock proxy.
	 *
	 *  <p>The pin is read-only for {@code doCheck}; Steps still
	 *  dispatch only from {@code doStart}. {@link UseOnStep}'s legacy
	 *  pattern of dispatching from {@code check()} via a pinned
	 *  dispatcher is forbidden — multi-click flows compose via
	 *  {@link net.runelite.client.sequence.composite.LinearSequence}
	 *  instead (one Step per dispatch). */
	protected transient InputDispatcher pinnedDispatcher;

	protected ArtemisActionStep(Artemis artemis, Consumer<StepEvent> stepEventSink, boolean maintenance)
	{
		this.artemis = artemis;
		this.stepEventSink = stepEventSink;
		this.maintenance = maintenance;
	}

	/** True when {@link #pinnedDispatcher} has reported its worker
	 *  thread is idle (the dispatch chain finished). False when the
	 *  pin is null (pre-onStart safety) or the worker is still
	 *  resolving / pressing.
	 *
	 *  <p>Subclasses use this as the "dispatcher reported completion"
	 *  signal in {@code doCheck} per spec §11 base click contract,
	 *  instead of an elapsed-tick proxy that would race the busy
	 *  guard on the next dispatch. */
	protected final boolean dispatcherIdle()
	{
		return pinnedDispatcher != null && !pinnedDispatcher.isBusy();
	}

	// ── Step defaults ───────────────────────────────────────────────

	@Override public int priority()                                                    { return 50; }
	@Override public PreemptionPolicy preemptionPolicy()                               { return PreemptionPolicy.WHEN_SAFE; }
	@Override public boolean isSafeToPause(WorldSnapshot s, Blackboard bb)             { return true; }
	@Override public boolean canStart(WorldSnapshot s, Blackboard bb)                  { return true; }
	@Override public void onEvent(Object event, StepContext ctx)                       {}
	@Override public void tick(StepContext ctx)                                        {}

	@Override
	public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard bb)
	{
		return new Recovery.Retry(3);
	}

	// ── Lifecycle (final — subclasses extend via doStart/doCheck) ───

	@Override
	public final void onStart(StepContext ctx)
	{
		// Pin the engine dispatcher BEFORE doStart so doCheck has it
		// available on subsequent ticks. This is read-only — doStart is
		// still the only dispatch site.
		this.pinnedDispatcher = ctx.dispatcher();

		Blackboard step = ctx.bb().scope(BlackboardScope.STEP);
		long tickNow = ctx.currentTick();
		step.put(K_START_TICK, tickNow);

		// Session gate. Gameplay Steps refuse start when the session is
		// over; maintenance Steps proceed (per spec §3 principle 5).
		if (!maintenance && artemis.session() != null && !artemis.session().shouldContinue())
		{
			pinFailure(step, REASON_SESSION_EXHAUSTED, "session.shouldContinue() == false");
			emit("started");
			emit("failed", 0L, REASON_SESSION_EXHAUSTED, "session.shouldContinue() == false");
			return;
		}

		emit("started");

		try
		{
			doStart(ctx);
		}
		catch (RuntimeException e)
		{
			log.warn("{} onStart threw: {}", name(), e.toString());
			pinFailure(step, REASON_ONSTART_EXCEPTION, e.toString());
			emit("failed", 0L, REASON_ONSTART_EXCEPTION, e.toString());
		}
	}

	@Override
	public final Completion check(WorldSnapshot s, Blackboard bb)
	{
		Blackboard step = bb.scope(BlackboardScope.STEP);

		String pinnedReason = step.get(K_FAIL_REASON).orElse(null);
		if (pinnedReason != null)
		{
			// onStart already emitted the failed StepEvent; just report
			// the completion to the engine.
			return Completion.failed(new DiagnosticReason.Unknown(pinnedReason));
		}

		long startTick = step.get(K_START_TICK).orElse((long) s.tick());
		long elapsed = Math.max(0L, s.tick() - startTick);

		Completion result;
		try
		{
			result = doCheck(s, bb, elapsed);
		}
		catch (RuntimeException e)
		{
			log.warn("{} doCheck threw: {}", name(), e.toString());
			emit("failed", elapsed, REASON_DOCHECK_EXCEPTION, e.toString());
			return Completion.failed(new DiagnosticReason.Unknown(REASON_DOCHECK_EXCEPTION));
		}

		if (result instanceof Completion.Succeeded)
		{
			emit("succeeded", elapsed, null, null);
		}
		else if (result instanceof Completion.Failed failed)
		{
			String reason = mapDiagnostic(failed);
			emit("failed", elapsed, reason, failed.reason());
		}
		return result;
	}

	// ── Subclass hooks ──────────────────────────────────────────────

	/** Subclass: re-resolve the target ref, build an
	 *  {@link net.runelite.client.sequence.internal.ActionRequest} and
	 *  dispatch via {@code ctx.dispatcher().dispatch(...)}. To fail
	 *  before dispatch (e.g. stale ref), call
	 *  {@link #failOnStart(StepContext, String, String)} and return —
	 *  do NOT throw and do NOT dispatch a click. */
	protected abstract void doStart(StepContext ctx);

	/** Subclass: poll completion. {@code elapsed} is ticks since
	 *  {@link #onStart}. Return {@link Completion#RUNNING},
	 *  {@link Completion.Succeeded}, or {@link Completion.Failed} —
	 *  the base class emits the matching {@link StepEvent}
	 *  automatically. Do NOT emit from the subclass. */
	protected abstract Completion doCheck(WorldSnapshot s, Blackboard bb, long elapsed);

	/** Subclass: StepEvent target descriptor. {@code "npc"},
	 *  {@code "object"}, {@code "item"}, {@code "widget"},
	 *  {@code "tile"} — or {@code null} for Steps without a typed
	 *  target. */
	protected abstract String targetType();

	/** Subclass: structured target identity, e.g. {@code "cow:2693"} or
	 *  {@code "widget:786456/12"}. Null when {@link #targetType()} is
	 *  null. */
	protected abstract String targetId();

	/** Subclass: human-readable target name, e.g. {@code "Cow"} or
	 *  {@code "Bank booth"}. Null when not known. */
	protected abstract String targetName();

	/** Subclass: menu verb dispatched against the target, e.g.
	 *  {@code "Attack"} or {@code "Use"}. Null for Steps that don't
	 *  dispatch a verb. */
	protected abstract String verb();

	// ── Helpers exposed to subclasses ───────────────────────────────

	/** Mark the Step as failed without an exception. Called from
	 *  {@code doStart} when a pre-dispatch check (stale ref, missing
	 *  target) decides we cannot proceed. Emits the {@code failed}
	 *  {@link StepEvent} immediately; {@code check()} on the next tick
	 *  picks up the pinned reason and returns {@link Completion.Failed}. */
	protected final void failOnStart(StepContext ctx, String reason, String detail)
	{
		Blackboard step = ctx.bb().scope(BlackboardScope.STEP);
		pinFailure(step, reason, detail);
		long startTick = step.get(K_START_TICK).orElse((long) ctx.currentTick());
		long elapsed = Math.max(0L, ctx.currentTick() - startTick);
		emit("failed", elapsed, reason, detail);
	}

	/** Returns the ref-age in ticks given the current world tick. */
	protected final long ticksSince(long observedTick, long nowTick)
	{
		return Math.max(0L, nowTick - observedTick);
	}

	/** True when the ref's {@code observedTick} is older than
	 *  {@link #STALE_REF_BUDGET_TICKS}. */
	protected final boolean isStaleByAge(long observedTick, long nowTick)
	{
		return ticksSince(observedTick, nowTick) > STALE_REF_BUDGET_TICKS;
	}

	private void pinFailure(Blackboard step, String reason, String detail)
	{
		step.put(K_FAIL_REASON, reason == null ? REASON_ONSTART_EXCEPTION : reason);
		if (detail != null)
		{
			step.put(K_FAIL_DETAIL, detail);
		}
	}

	private String mapDiagnostic(Completion.Failed failed)
	{
		DiagnosticReason d = failed.diagnostic();
		if (d == null)
		{
			return REASON_TIMEOUT;
		}
		if (d instanceof DiagnosticReason.Unknown unk)
		{
			return unk.detail() == null ? REASON_TIMEOUT : unk.detail();
		}
		if (d instanceof DiagnosticReason.ActionTimedOut)
		{
			return REASON_TIMEOUT;
		}
		return d.toString();
	}

	// ── StepEvent emission ──────────────────────────────────────────

	private void emit(String phase)
	{
		emit(phase, null, null, null);
	}

	private void emit(String phase, Long ticksElapsed, String reason, String detail)
	{
		if (stepEventSink == null)
		{
			return;
		}
		stepEventSink.accept(new StepEvent(
			name(),
			phase,
			targetType(),
			targetId(),
			targetName(),
			verb(),
			ticksElapsed,
			reason,
			null,   // clickX — dispatcher-internal in v1; Phase 7 may plumb through
			null,   // clickY — same
			detail));
	}
}
