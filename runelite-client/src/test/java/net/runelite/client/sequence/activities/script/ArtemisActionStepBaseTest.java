package net.runelite.client.sequence.activities.script;

import java.util.ArrayList;
import java.util.List;
import net.runelite.client.plugins.recorder.analyse.StepEvent;
import net.runelite.client.plugins.recorder.session.SessionShape;
import net.runelite.client.sequence.Completion;
import net.runelite.client.sequence.StepContext;
import net.runelite.client.sequence.WorldSnapshot;
import net.runelite.client.sequence.artemis.Artemis;
import net.runelite.client.sequence.blackboard.Blackboard;
import net.runelite.client.sequence.blackboard.BlackboardScope;
import net.runelite.client.sequence.blackboard.ScopedBlackboard;
import net.runelite.client.sequence.dispatch.MockInputDispatcher;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ArtemisActionStep} as the engine-internal base — the
 * session gate, the structured {@link StepEvent} schema, the
 * fail-pinning that surfaces in {@code check}, and the maintenance vs
 * gameplay split.
 *
 * <p>Uses a stub subclass so we can exercise the base without depending
 * on any specific concrete action Step.
 */
public class ArtemisActionStepBaseTest
{
	// ── Stub subclass used to probe the base ────────────────────────

	private static final class FakeStep extends ArtemisActionStep
	{
		final List<String> doStartTrace = new ArrayList<>();
		Completion checkResult = Completion.RUNNING;

		FakeStep(Artemis artemis, java.util.function.Consumer<StepEvent> sink, boolean maintenance)
		{
			super(artemis, sink, maintenance);
		}

		@Override public String name()                  { return "Fake(target)"; }
		@Override public int timeoutTicks()             { return 8; }
		@Override protected String targetType()         { return "npc"; }
		@Override protected String targetId()           { return "npc:1234/5"; }
		@Override protected String targetName()         { return "Cow"; }
		@Override protected String verb()               { return "Attack"; }

		@Override
		protected void doStart(StepContext ctx)
		{
			doStartTrace.add("doStart@" + ctx.currentTick());
		}

		@Override
		protected Completion doCheck(WorldSnapshot s, Blackboard bb, long elapsed)
		{
			return checkResult;
		}
	}

	/** List-backed sink that captures every {@link StepEvent} so tests
	 *  can assert phase + structured fields. Production uses
	 *  {@code RecorderManager::recordStepEvent}; ArtemisActionStep
	 *  depends only on the {@code Consumer<StepEvent>} contract. */
	private static final class RecordingRecorder implements java.util.function.Consumer<StepEvent>
	{
		final List<StepEvent> events = new ArrayList<>();

		@Override
		public void accept(StepEvent ev)
		{
			events.add(ev);
		}
	}

	private static StepContext ctxAtTick(int tick, Blackboard bb)
	{
		StepContext ctx = mock(StepContext.class);
		when(ctx.bb()).thenReturn(bb);
		when(ctx.currentTick()).thenReturn(tick);
		when(ctx.dispatcher()).thenReturn(new MockInputDispatcher());
		return ctx;
	}

	private static WorldSnapshot snapshotAtTick(int tick)
	{
		WorldSnapshot s = mock(WorldSnapshot.class);
		when(s.tick()).thenReturn(tick);
		return s;
	}

	private Artemis artemisWithSession(boolean shouldContinue)
	{
		Artemis a = mock(Artemis.class);
		// SessionShape is final → can't mock. Construct a real one with
		// a synthetic tick supplier: budget 0 ⇒ immediately exhausted,
		// budget huge ⇒ shouldContinue() forever true.
		SessionShape session = new SessionShape(
			() -> 0L, () -> 0L, shouldContinue ? Long.MAX_VALUE : 0L);
		when(a.session()).thenReturn(session);
		return a;
	}

	// ── Session gate ────────────────────────────────────────────────

	@Test
	public void gameplayStepRefusesStartWhenSessionExhausted()
	{
		Artemis artemis = artemisWithSession(false);
		RecordingRecorder rec = new RecordingRecorder();
		FakeStep step = new FakeStep(artemis, rec, /* maintenance */ false);

		ScopedBlackboard bb = new ScopedBlackboard();
		StepContext ctx = ctxAtTick(10, bb);
		step.onStart(ctx);

		assertTrue("doStart MUST NOT run for session-exhausted gameplay step",
			step.doStartTrace.isEmpty());

		// check() should report Failed on first tick — failure is pinned.
		Completion result = step.check(snapshotAtTick(10), bb);
		assertTrue("check should report Failed for session-exhausted step",
			result instanceof Completion.Failed);

		// StepEvent emission: started + failed with SESSION_EXHAUSTED reason.
		assertEquals(2, rec.events.size());
		assertEquals("started", rec.events.get(0).phase());
		assertEquals("failed", rec.events.get(1).phase());
		assertEquals("SESSION_EXHAUSTED", rec.events.get(1).diagnosticReason());
		// Structured fields must be populated, not stuffed in `detail`.
		assertEquals("npc", rec.events.get(1).targetType());
		assertEquals("npc:1234/5", rec.events.get(1).targetId());
		assertEquals("Cow", rec.events.get(1).targetName());
		assertEquals("Attack", rec.events.get(1).verb());
	}

	@Test
	public void maintenanceStepBypassesSessionGate()
	{
		Artemis artemis = artemisWithSession(false);
		RecordingRecorder rec = new RecordingRecorder();
		FakeStep step = new FakeStep(artemis, rec, /* maintenance */ true);

		ScopedBlackboard bb = new ScopedBlackboard();
		StepContext ctx = ctxAtTick(10, bb);
		step.onStart(ctx);

		assertEquals("maintenance step MUST run doStart despite session over",
			1, step.doStartTrace.size());
		// Started event was emitted; no failed event yet.
		assertEquals(1, rec.events.size());
		assertEquals("started", rec.events.get(0).phase());
	}

	// ── StepEvent structured-field discipline ──────────────────────

	@Test
	public void startedEventPopulatesStructuredFieldsAndNullElapsed()
	{
		Artemis artemis = artemisWithSession(true);
		RecordingRecorder rec = new RecordingRecorder();
		FakeStep step = new FakeStep(artemis, rec, false);

		ScopedBlackboard bb = new ScopedBlackboard();
		step.onStart(ctxAtTick(42, bb));

		StepEvent started = rec.events.get(0);
		assertEquals("started", started.phase());
		assertEquals("Fake(target)", started.name());
		assertEquals("npc", started.targetType());
		assertEquals("npc:1234/5", started.targetId());
		assertEquals("Cow", started.targetName());
		assertEquals("Attack", started.verb());
		assertNull("ticksElapsed must be null on started",
			started.ticksElapsed());
		assertNull("diagnosticReason must be null on started",
			started.diagnosticReason());
		assertNull("clickX must be null until Phase 7 plumbing", started.clickX());
		assertNull("clickY must be null until Phase 7 plumbing", started.clickY());
	}

	@Test
	public void succeededEventCarriesElapsedTicks()
	{
		Artemis artemis = artemisWithSession(true);
		RecordingRecorder rec = new RecordingRecorder();
		FakeStep step = new FakeStep(artemis, rec, false);
		step.checkResult = new Completion.Succeeded("ok");

		ScopedBlackboard bb = new ScopedBlackboard();
		step.onStart(ctxAtTick(100, bb));
		step.check(snapshotAtTick(103), bb);

		StepEvent succeeded = rec.events.get(rec.events.size() - 1);
		assertEquals("succeeded", succeeded.phase());
		assertEquals(Long.valueOf(3L), succeeded.ticksElapsed());
		assertNull(succeeded.diagnosticReason());
	}

	@Test
	public void failOnStartHelperPinsAndEmitsBoth()
	{
		Artemis artemis = artemisWithSession(true);
		RecordingRecorder rec = new RecordingRecorder();

		// Subclass that explicitly calls failOnStart from doStart to
		// simulate the stale-ref / target-not-found shape.
		ArtemisActionStep step = new ArtemisActionStep(artemis, rec, false)
		{
			@Override public String name()                  { return "Failing"; }
			@Override public int timeoutTicks()             { return 6; }
			@Override protected String targetType()         { return "item"; }
			@Override protected String targetId()           { return "item:526@x,y,0"; }
			@Override protected String targetName()         { return "Bones"; }
			@Override protected String verb()               { return "Take"; }

			@Override
			protected void doStart(StepContext ctx)
			{
				failOnStart(ctx, REASON_STALE_REF, "age=12t > 8");
			}

			@Override
			protected Completion doCheck(WorldSnapshot s, Blackboard bb, long elapsed)
			{
				return Completion.RUNNING;
			}
		};

		ScopedBlackboard bb = new ScopedBlackboard();
		step.onStart(ctxAtTick(7, bb));

		// onStart should have emitted started + failed.
		assertEquals(2, rec.events.size());
		assertEquals("started", rec.events.get(0).phase());
		assertEquals("failed", rec.events.get(1).phase());
		assertEquals("STALE_REF", rec.events.get(1).diagnosticReason());
		assertEquals("age=12t > 8", rec.events.get(1).detail());

		// check() must surface the pinned failure (not RUNNING forever).
		Completion result = step.check(snapshotAtTick(8), bb);
		assertTrue(result instanceof Completion.Failed);
	}

	@Test
	public void doStartExceptionIsTrappedAndEmittedAsFailure()
	{
		Artemis artemis = artemisWithSession(true);
		RecordingRecorder rec = new RecordingRecorder();
		ArtemisActionStep step = new ArtemisActionStep(artemis, rec, false)
		{
			@Override public String name()                  { return "Throwing"; }
			@Override public int timeoutTicks()             { return 6; }
			@Override protected String targetType()         { return null; }
			@Override protected String targetId()           { return null; }
			@Override protected String targetName()         { return null; }
			@Override protected String verb()               { return null; }

			@Override
			protected void doStart(StepContext ctx)
			{
				throw new IllegalStateException("boom");
			}

			@Override
			protected Completion doCheck(WorldSnapshot s, Blackboard bb, long elapsed)
			{
				return Completion.RUNNING;
			}
		};

		ScopedBlackboard bb = new ScopedBlackboard();
		step.onStart(ctxAtTick(1, bb));

		// Both events emitted; the failed one carries the typed reason.
		assertEquals(2, rec.events.size());
		assertEquals("failed", rec.events.get(1).phase());
		assertEquals("ONSTART_EXCEPTION", rec.events.get(1).diagnosticReason());
		assertTrue(rec.events.get(1).detail().contains("boom"));

		Completion result = step.check(snapshotAtTick(2), bb.scope(BlackboardScope.STEP));
		assertTrue(result instanceof Completion.Failed);
	}

	@Test
	public void doCheckExceptionIsTrappedAndEmittedAsFailure()
	{
		// Mirror of doStartExceptionIsTrappedAndEmittedAsFailure for the
		// check() trap added in round-2 (ArtemisActionStep.check now has
		// try/catch around doCheck; a NullPointerException from a
		// transient null inventory read used to propagate to the engine
		// without a `failed` StepEvent).
		Artemis artemis = artemisWithSession(true);
		RecordingRecorder rec = new RecordingRecorder();
		ArtemisActionStep step = new ArtemisActionStep(artemis, rec, false)
		{
			@Override public String name()                  { return "ThrowingCheck"; }
			@Override public int timeoutTicks()             { return 6; }
			@Override protected String targetType()         { return "npc"; }
			@Override protected String targetId()           { return "npc:1/1"; }
			@Override protected String targetName()         { return "Test"; }
			@Override protected String verb()               { return "Attack"; }

			@Override
			protected void doStart(StepContext ctx)
			{
				// no-op; dispatch happens elsewhere
			}

			@Override
			protected Completion doCheck(WorldSnapshot s, Blackboard bb, long elapsed)
			{
				throw new IllegalStateException("doCheck boom");
			}
		};

		ScopedBlackboard bb = new ScopedBlackboard();
		step.onStart(ctxAtTick(10, bb));
		// onStart succeeded → emits started, no failed yet.
		assertEquals(1, rec.events.size());
		assertEquals("started", rec.events.get(0).phase());

		// check() invocation triggers the trap.
		Completion result = step.check(snapshotAtTick(11), bb);

		assertTrue("Completion must be Failed when doCheck throws",
			result instanceof Completion.Failed);
		// Structured failed StepEvent emitted with the typed reason.
		assertEquals(2, rec.events.size());
		StepEvent failed = rec.events.get(1);
		assertEquals("failed", failed.phase());
		assertEquals("DOCHECK_EXCEPTION", failed.diagnosticReason());
		assertTrue("detail must surface the exception message, got: " + failed.detail(),
			failed.detail() != null && failed.detail().contains("doCheck boom"));
		// Structured fields still populated — not stuffed into detail.
		assertEquals("npc", failed.targetType());
		assertEquals("Attack", failed.verb());
		assertEquals(Long.valueOf(1L), failed.ticksElapsed());
	}
}
