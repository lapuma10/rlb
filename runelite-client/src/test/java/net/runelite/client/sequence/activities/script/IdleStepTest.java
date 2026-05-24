package net.runelite.client.sequence.activities.script;

import java.util.ArrayList;
import java.util.List;
import net.runelite.client.plugins.recorder.analyse.StepEvent;
import net.runelite.client.plugins.recorder.session.AccountRng;
import net.runelite.client.plugins.recorder.session.SessionShape;
import net.runelite.client.sequence.Completion;
import net.runelite.client.sequence.StepContext;
import net.runelite.client.sequence.WorldSnapshot;
import net.runelite.client.sequence.artemis.Artemis;
import net.runelite.client.sequence.artemis.session.IdlePolicy;
import net.runelite.client.sequence.blackboard.ScopedBlackboard;
import net.runelite.client.sequence.dispatch.MockInputDispatcher;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link IdleStep}. Pure tick-counting maintenance Step —
 * exercises construct-time sampling, the session-gate bypass, the
 * no-dispatch invariant, and the policy-validation failures.
 */
public class IdleStepTest
{
	private static StepContext ctxAtTick(int tick, ScopedBlackboard bb, MockInputDispatcher disp)
	{
		StepContext ctx = mock(StepContext.class);
		when(ctx.bb()).thenReturn(bb);
		when(ctx.currentTick()).thenReturn(tick);
		when(ctx.dispatcher()).thenReturn(disp);
		return ctx;
	}

	private static WorldSnapshot snapAtTick(int tick)
	{
		WorldSnapshot s = mock(WorldSnapshot.class);
		when(s.tick()).thenReturn(tick);
		return s;
	}

	private static Artemis artemisWithSession(boolean shouldContinue)
	{
		Artemis a = mock(Artemis.class);
		SessionShape session = new SessionShape(
			() -> 0L, () -> 0L, shouldContinue ? Long.MAX_VALUE : 0L);
		when(a.session()).thenReturn(session);
		return a;
	}

	private static final class RecordingRecorder implements java.util.function.Consumer<StepEvent>
	{
		final List<StepEvent> events = new ArrayList<>();
		@Override public void accept(StepEvent ev) { events.add(ev); }
	}

	// ── Maintenance bypass ──────────────────────────────────────────

	@Test
	public void bypassesSessionGateWhenSessionExhausted()
	{
		Artemis artemis = artemisWithSession(/* shouldContinue */ false);
		RecordingRecorder rec = new RecordingRecorder();
		IdleStep step = new IdleStep(artemis, rec, new IdlePolicy(600, 1200, false),
			new AccountRng(null));

		ScopedBlackboard bb = new ScopedBlackboard();
		MockInputDispatcher disp = new MockInputDispatcher();
		StepContext ctx = ctxAtTick(0, bb, disp);
		step.onStart(ctx);

		// Should NOT have pinned a SESSION_EXHAUSTED failure — maintenance
		// Steps bypass the gate. Confirm via: started emitted, no failed,
		// check() returns RUNNING (still counting ticks).
		assertEquals("started", rec.events.get(0).phase());
		assertTrue("maintenance Step should not have failed on session gate",
			rec.events.stream().noneMatch(e -> "failed".equals(e.phase())));
		Completion c = step.check(snapAtTick(0), bb);
		assertEquals(Completion.RUNNING, c);
	}

	// ── Construct-time sampling ─────────────────────────────────────

	@Test
	public void samplesDeterministicallyForSameAccountSeed()
	{
		Artemis artemis = artemisWithSession(true);
		IdlePolicy policy = new IdlePolicy(30_000, 90_000, false);
		IdleStep a = new IdleStep(artemis, null, policy, new AccountRng(null));
		IdleStep b = new IdleStep(artemis, null, policy, new AccountRng(null));
		assertEquals("same seed + same policy must produce same sample",
			a.sampledMs(), b.sampledMs());
	}

	@Test
	public void samplesWithinPolicyWindow()
	{
		Artemis artemis = artemisWithSession(true);
		IdlePolicy policy = new IdlePolicy(100, 500, false);
		// Multiple constructions with the same AccountRng all draw the
		// same value (deterministic) — the per-account seed itself
		// determines what's in the window, so this asserts the boundary
		// holds for OUR seed. Combined with the deterministic test
		// above, this pins the contract: sampled ∈ [min, max].
		for (int i = 0; i < 20; i++)
		{
			IdleStep step = new IdleStep(artemis, null, policy, new AccountRng(null));
			int s = step.sampledMs();
			assertTrue("sampled " + s + " below min=" + policy.minMs(), s >= policy.minMs());
			assertTrue("sampled " + s + " above max=" + policy.maxMs(), s <= policy.maxMs());
		}
	}

	// ── Tick-counting success ───────────────────────────────────────

	@Test
	public void succeedsAfterTargetTicksElapsed()
	{
		Artemis artemis = artemisWithSession(true);
		// min == max == 600 ms → targetTicks = ceil(600/600) = 1
		IdlePolicy zeroSpan = new IdlePolicy(600, 600, false);
		RecordingRecorder rec = new RecordingRecorder();
		IdleStep step = new IdleStep(artemis, rec, zeroSpan, new AccountRng(null));
		assertEquals(600, step.sampledMs());
		assertEquals(1L, step.targetTicks());

		ScopedBlackboard bb = new ScopedBlackboard();
		MockInputDispatcher disp = new MockInputDispatcher();
		step.onStart(ctxAtTick(0, bb, disp));

		// Tick 0 (elapsed=0): still running
		assertEquals(Completion.RUNNING, step.check(snapAtTick(0), bb));
		// Tick 1 (elapsed=1 ≥ targetTicks=1): succeeded
		Completion done = step.check(snapAtTick(1), bb);
		assertTrue("expected Succeeded but got " + done, done instanceof Completion.Succeeded);
		// StepEvent stream: started, succeeded
		assertEquals("started", rec.events.get(0).phase());
		assertEquals("succeeded", rec.events.get(rec.events.size() - 1).phase());
	}

	// ── No-dispatch invariant ───────────────────────────────────────

	@Test
	public void doesNotDispatchAnything()
	{
		Artemis artemis = artemisWithSession(true);
		IdleStep step = new IdleStep(artemis, null, new IdlePolicy(600, 600, false),
			new AccountRng(null));

		ScopedBlackboard bb = new ScopedBlackboard();
		MockInputDispatcher disp = new MockInputDispatcher();
		step.onStart(ctxAtTick(0, bb, disp));

		// Drive through to success — dispatcher must remain untouched.
		step.check(snapAtTick(0), bb);
		step.check(snapAtTick(1), bb);

		assertEquals("IdleStep must NOT dispatch any input",
			0, disp.getRequests().size());
	}

	// ── Construct-time validation ───────────────────────────────────

	@Test
	public void failsLoudOnLogoutPreferredTrue()
	{
		Artemis artemis = artemisWithSession(true);
		try
		{
			new IdleStep(artemis, null,
				new IdlePolicy(60_000, 90_000, /* logoutPreferred */ true),
				new AccountRng(null));
			fail("expected IllegalArgumentException for logoutPreferred=true (v1.0 not supported)");
		}
		catch (IllegalArgumentException expected)
		{
			assertNotNull(expected.getMessage());
			assertTrue("message should mention logoutPreferred / v1.3, was: " + expected.getMessage(),
				expected.getMessage().contains("logoutPreferred"));
		}
	}
}
