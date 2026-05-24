package net.runelite.client.sequence.activities.script;

import java.util.Random;
import java.util.function.Consumer;
import net.runelite.client.plugins.recorder.analyse.StepEvent;
import net.runelite.client.plugins.recorder.session.AccountRng;
import net.runelite.client.sequence.Completion;
import net.runelite.client.sequence.Failure;
import net.runelite.client.sequence.Recovery;
import net.runelite.client.sequence.StepContext;
import net.runelite.client.sequence.WorldSnapshot;
import net.runelite.client.sequence.artemis.Artemis;
import net.runelite.client.sequence.artemis.session.IdlePolicy;
import net.runelite.client.sequence.blackboard.Blackboard;

/**
 * Step backing {@code Artemis.idle(IdlePolicy)}. Pure tick-counting
 * idle: samples a duration at construction using a per-account RNG,
 * then succeeds once that many ticks have elapsed. <b>Does not
 * dispatch input. Does not enqueue a RUN_TASK. Does not sleep on any
 * worker thread.</b> Spec §10 — v1.0 in-game idle only.
 *
 * <p>Maintenance Step: bypasses {@code artemis.session().shouldContinue()}
 * per spec §3 principle 5, so an idle scheduled as part of session
 * wind-down can still run after the session is over.
 *
 * <p>Construction-time sampling is intentional: the base class emits
 * the {@code started} StepEvent before {@link #doStart} runs, and
 * {@link #name()} reads the sampled duration. Sampling later would
 * produce a misleading {@code started} event ({@code "Idle(?ms)"}).
 * It also means the sampled duration is deterministic per
 * ({@code AccountRng} seed, policy) — useful for reproducible tests.
 *
 * <p>Validation: all {@link IdlePolicy} errors (logoutPreferred=true,
 * negative or inverted min/max) throw {@link IllegalArgumentException}
 * at construction. The {@code IDLE_POLICY_INVALID} runtime diagnostic
 * was dropped from the vocab during 1A.4b implementation because
 * construct-time sampling makes runtime policy validation unreachable
 * — invalid policy can't reach {@link #doStart}.
 *
 * <p>Failure recovery: {@link Recovery.Abort}. The sampled duration is
 * fixed at construction; retrying with the same seed would produce
 * the same result. Idle failures are not transient.
 */
public final class IdleStep extends ArtemisActionStep
{
	/** Server tick rate in milliseconds — used to convert the sampled
	 *  duration to ticks via ceil. Matches the rest of the engine. */
	private static final int TICK_MS = 600;

	/** Defensive headroom on top of {@code targetTicks} for
	 *  {@link #timeoutTicks()}. The success criterion ({@code elapsed
	 *  >= targetTicks}) is deterministic; this only guards against an
	 *  engine bug where elapsed never increments. */
	private static final int TIMEOUT_HEADROOM_TICKS = 4;

	private final int sampledMs;
	private final long targetTicks;

	public IdleStep(Artemis artemis, Consumer<StepEvent> stepEventSink,
		IdlePolicy policy, AccountRng accountRng)
	{
		super(artemis, stepEventSink, /* maintenance */ true);
		if (policy == null)
		{
			throw new IllegalArgumentException("IdlePolicy must not be null");
		}
		if (policy.logoutPreferred())
		{
			throw new IllegalArgumentException(
				"IdlePolicy.logoutPreferred=true is v1.3 (NATURAL_BREAK / MEAL with relogin); "
					+ "v1.0 ships in-game idle only — pass logoutPreferred=false");
		}
		if (policy.minMs() < 0 || policy.maxMs() < 0 || policy.minMs() > policy.maxMs())
		{
			throw new IllegalArgumentException(
				"IdlePolicy window invalid: minMs=" + policy.minMs() + " maxMs=" + policy.maxMs()
					+ " (require 0 <= minMs <= maxMs)");
		}
		if (accountRng == null)
		{
			throw new IllegalArgumentException("AccountRng must not be null");
		}

		Random rng = accountRng.forAccount("artemis-idle");
		int span = policy.maxMs() - policy.minMs();
		// nextInt(bound) requires bound > 0 — for minMs == maxMs (zero
		// span) skip the draw and use minMs directly to avoid IAE.
		this.sampledMs = span == 0 ? policy.minMs() : policy.minMs() + rng.nextInt(span + 1);
		this.targetTicks = (long) Math.ceil(this.sampledMs / (double) TICK_MS);
	}

	@Override public String name()              { return "Idle(" + sampledMs + "ms)"; }
	@Override public int timeoutTicks()         { return Math.toIntExact(targetTicks + TIMEOUT_HEADROOM_TICKS); }
	@Override protected String targetType()     { return null; }
	@Override protected String targetId()       { return null; }
	@Override protected String targetName()     { return null; }
	@Override protected String verb()           { return null; }

	/** Sampled idle duration in milliseconds. Exposed for tests + the
	 *  StepEvent assertions; production code should not depend on it. */
	int sampledMs()                              { return sampledMs; }

	/** Target tick count derived from {@link #sampledMs()}. Exposed for
	 *  tests. */
	long targetTicks()                           { return targetTicks; }

	@Override
	protected void doStart(StepContext ctx)
	{
		// Nothing to do. The base class pins K_START_TICK before calling
		// us; check() polls elapsed against targetTicks. No dispatch, no
		// RUN_TASK, no worker sleep — pure tick-counting.
	}

	@Override
	protected Completion doCheck(WorldSnapshot s, Blackboard bb, long elapsed)
	{
		if (elapsed >= targetTicks)
		{
			return new Completion.Succeeded(
				"idle complete (" + sampledMs + "ms = " + targetTicks + "t)");
		}
		return Completion.RUNNING;
	}

	@Override
	public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard bb)
	{
		return new Recovery.Abort(
			"idle does not retry — sampled duration is fixed at construction (" + sampledMs + "ms)");
	}
}
