package net.runelite.client.sequence.activities.script;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javax.annotation.Nullable;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.analyse.StepEvent;
import net.runelite.client.plugins.recorder.nav.NavRequest;
import net.runelite.client.plugins.recorder.nav.NavStatus;
import net.runelite.client.plugins.recorder.nav.Navigator;
import net.runelite.client.plugins.recorder.session.SessionShape;
import net.runelite.client.sequence.Completion;
import net.runelite.client.sequence.Failure;
import net.runelite.client.sequence.PlayerView;
import net.runelite.client.sequence.Recovery;
import net.runelite.client.sequence.StepContext;
import net.runelite.client.sequence.WorldSnapshot;
import net.runelite.client.sequence.affordance.DiagnosticReason;
import net.runelite.client.sequence.artemis.Artemis;
import net.runelite.client.sequence.blackboard.ScopedBlackboard;
import net.runelite.client.sequence.dispatch.MockInputDispatcher;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link WalkToWorldPointStep}. Pins the daemon-worker
 * pattern: worker only calls navigator.tick + writes volatile status;
 * {@link WalkToWorldPointStep#doCheck} owns all success/failure
 * decisions; terminal paths call stopWorker.
 *
 * <p>Uses {@link LatchedNavigator} (Semaphore + BlockingQueue) to drive
 * the worker deterministically — no Thread.sleep-based race-prone
 * assertions. The only timing primitive in tests is a tight
 * {@code awaitCondition} polling helper that surfaces failures as
 * explicit timeouts rather than flake.
 */
public class WalkToWorldPointStepTest
{
	private static final WorldPoint TARGET = new WorldPoint(3200, 3200, 0);
	private static final WorldPoint IN_RANGE = new WorldPoint(3200, 3201, 0);   // 1 tile away
	private static final WorldPoint OUT_OF_RANGE = new WorldPoint(3210, 3210, 0); // far

	/** Steps under test in the current method — torn down after each
	 *  test so a leaked worker can't span tests. */
	private final List<WalkToWorldPointStep> live = new ArrayList<>();

	@After
	public void tearDown() throws Exception
	{
		// Force any still-running daemon worker to exit. stopWorker is
		// idempotent, so calling it on a Step that's already stopped is
		// safe. Don't fail tearDown if a worker leaked — that's what the
		// test assertions are for; we just want to clean up.
		for (WalkToWorldPointStep s : live)
		{
			try
			{
				java.lang.reflect.Method m = WalkToWorldPointStep.class
					.getDeclaredMethod("stopWorker", String.class);
				m.setAccessible(true);
				m.invoke(s, "tearDown");
			}
			catch (ReflectiveOperationException ignored) { /* best-effort cleanup */ }
		}
		live.clear();
	}

	// ── Latched test double ─────────────────────────────────────────

	/** Test Navigator that blocks each {@code tick()} call until the
	 *  test explicitly responds. Lets tests drive the worker
	 *  deterministically (one nav.tick step at a time) without timing
	 *  guesses. */
	private static final class LatchedNavigator implements Navigator
	{
		private final LinkedBlockingQueue<NavStatus> responses = new LinkedBlockingQueue<>();
		private final Semaphore tickStarted = new Semaphore(0);
		private final AtomicInteger tickCount = new AtomicInteger(0);
		private final AtomicInteger cancelCount = new AtomicInteger(0);

		@Override
		public NavStatus tick(NavRequest request) throws InterruptedException
		{
			tickCount.incrementAndGet();
			tickStarted.release();
			return responses.take();   // blocks until respondWith / interrupt
		}

		@Override public void cancel() { cancelCount.incrementAndGet(); }
		@Override public boolean isBusy() { return false; }
		@Override public String name() { return "latched"; }

		/** Wait until the worker calls {@code tick()}. Times out after 2s
		 *  with a clear failure rather than hanging the test. */
		void awaitTickEntered() throws InterruptedException
		{
			if (!tickStarted.tryAcquire(2, TimeUnit.SECONDS))
			{
				fail("worker did not enter navigator.tick() within 2s");
			}
		}

		/** Send the worker the given status; tick() returns this on the
		 *  next call. */
		void respondWith(NavStatus s) { responses.add(s); }

		int tickCalls()                 { return tickCount.get(); }
	}

	// ── Generic helpers ─────────────────────────────────────────────

	private static StepContext ctxAtTick(int tick, ScopedBlackboard bb, MockInputDispatcher disp,
		WorldSnapshot snap)
	{
		StepContext ctx = mock(StepContext.class);
		when(ctx.bb()).thenReturn(bb);
		when(ctx.currentTick()).thenReturn(tick);
		when(ctx.dispatcher()).thenReturn(disp);
		when(ctx.snapshot()).thenReturn(snap);
		return ctx;
	}

	private static WorldSnapshot snapAt(int tick, @Nullable WorldPoint loc)
	{
		WorldSnapshot s = mock(WorldSnapshot.class);
		when(s.tick()).thenReturn(tick);
		if (loc != null)
		{
			PlayerView p = mock(PlayerView.class);
			when(p.worldLocation()).thenReturn(loc);
			when(s.player()).thenReturn(p);
		}
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

	private WalkToWorldPointStep newStep(Artemis a, java.util.function.Consumer<StepEvent> sink,
		@Nullable Navigator nav)
	{
		WalkToWorldPointStep s = new WalkToWorldPointStep(a, sink, TARGET, nav);
		live.add(s);
		return s;
	}

	private static final class RecordingRecorder implements java.util.function.Consumer<StepEvent>
	{
		final List<StepEvent> events = new ArrayList<>();
		@Override public void accept(StepEvent ev) { events.add(ev); }
		Optional<StepEvent> lastFailed()
		{
			for (int i = events.size() - 1; i >= 0; i--)
			{
				if ("failed".equals(events.get(i).phase())) return Optional.of(events.get(i));
			}
			return Optional.empty();
		}
	}

	private static void awaitCondition(BooleanSupplier cond, long timeoutMs, String desc)
		throws InterruptedException
	{
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (!cond.getAsBoolean())
		{
			if (System.currentTimeMillis() >= deadline)
			{
				fail("did not observe " + desc + " within " + timeoutMs + "ms");
			}
			Thread.sleep(10);
		}
	}

	// ── 1. Session gate ─────────────────────────────────────────────

	@Test
	public void sessionExhaustedBlocksStart() throws Exception
	{
		Artemis artemis = artemisWithSession(/* shouldContinue */ false);
		RecordingRecorder rec = new RecordingRecorder();
		LatchedNavigator nav = new LatchedNavigator();
		WalkToWorldPointStep step = newStep(artemis, rec, nav);

		ScopedBlackboard bb = new ScopedBlackboard();
		MockInputDispatcher disp = new MockInputDispatcher();
		step.onStart(ctxAtTick(0, bb, disp, snapAt(0, IN_RANGE)));

		StepEvent failed = rec.lastFailed().orElseThrow();
		assertEquals("SESSION_EXHAUSTED", failed.diagnosticReason());
		assertFalse("no worker should be spawned when session is exhausted",
			step.workerAliveForTesting());
		assertEquals("navigator.tick should never be called",
			0, nav.tickCalls());
	}

	// ── 2. NAVIGATOR_MISSING ────────────────────────────────────────

	@Test
	public void navigatorMissingFailsLoud()
	{
		Artemis artemis = artemisWithSession(true);
		RecordingRecorder rec = new RecordingRecorder();
		WalkToWorldPointStep step = newStep(artemis, rec, /* navigator */ null);

		ScopedBlackboard bb = new ScopedBlackboard();
		MockInputDispatcher disp = new MockInputDispatcher();
		step.onStart(ctxAtTick(0, bb, disp, snapAt(0, OUT_OF_RANGE)));

		StepEvent failed = rec.lastFailed().orElseThrow();
		assertEquals(WalkToWorldPointStep.REASON_NAVIGATOR_MISSING, failed.diagnosticReason());
		assertFalse("no worker should be spawned when navigator is null",
			step.workerAliveForTesting());
	}

	// ── 3. Arrival debounce success ─────────────────────────────────

	@Test
	public void succeedsAfterArrivalDebounce() throws Exception
	{
		Artemis artemis = artemisWithSession(true);
		RecordingRecorder rec = new RecordingRecorder();
		LatchedNavigator nav = new LatchedNavigator();
		WalkToWorldPointStep step = newStep(artemis, rec, nav);

		ScopedBlackboard bb = new ScopedBlackboard();
		MockInputDispatcher disp = new MockInputDispatcher();
		step.onStart(ctxAtTick(0, bb, disp, snapAt(0, OUT_OF_RANGE)));

		// Worker enters tick(); let it keep RUNNING — the test drives
		// success via player snapshot, not via navigator ARRIVED.
		nav.awaitTickEntered();
		nav.respondWith(NavStatus.RUNNING);

		// Tick 1: in range — 1 consecutive in-range tick, not enough.
		Completion c1 = step.check(snapAt(1, IN_RANGE), bb);
		assertEquals(Completion.RUNNING, c1);

		// Tick 2: still in range — 2 consecutive, succeed.
		Completion c2 = step.check(snapAt(2, IN_RANGE), bb);
		assertTrue("expected Succeeded but got " + c2, c2 instanceof Completion.Succeeded);

		// Terminal path: worker must be stopped.
		assertTrue("stopWorker must have been called on Succeeded",
			step.stopFlagForTesting());
	}

	// ── 4. Arrival debounce: one tick is not enough ─────────────────

	@Test
	public void doesNotSucceedAfterOnlyOneInRangeTick() throws Exception
	{
		Artemis artemis = artemisWithSession(true);
		LatchedNavigator nav = new LatchedNavigator();
		WalkToWorldPointStep step = newStep(artemis, null, nav);

		ScopedBlackboard bb = new ScopedBlackboard();
		MockInputDispatcher disp = new MockInputDispatcher();
		step.onStart(ctxAtTick(0, bb, disp, snapAt(0, OUT_OF_RANGE)));

		nav.awaitTickEntered();
		nav.respondWith(NavStatus.RUNNING);

		// In range
		assertEquals(Completion.RUNNING, step.check(snapAt(1, IN_RANGE), bb));
		// Out of range — counter resets
		assertEquals(Completion.RUNNING, step.check(snapAt(2, OUT_OF_RANGE), bb));
		// In range again — only 1 consecutive
		assertEquals(Completion.RUNNING, step.check(snapAt(3, IN_RANGE), bb));
		// Still in range — now 2 consecutive, succeeds
		Completion c = step.check(snapAt(4, IN_RANGE), bb);
		assertTrue("expected Succeeded after debounce", c instanceof Completion.Succeeded);
	}

	// ── 5. Step timeout ─────────────────────────────────────────────

	@Test
	public void timeoutAfter60Ticks() throws Exception
	{
		Artemis artemis = artemisWithSession(true);
		RecordingRecorder rec = new RecordingRecorder();
		LatchedNavigator nav = new LatchedNavigator();
		WalkToWorldPointStep step = newStep(artemis, rec, nav);

		ScopedBlackboard bb = new ScopedBlackboard();
		MockInputDispatcher disp = new MockInputDispatcher();
		// Use a moving location each tick so stuck detection doesn't fire
		// before the timeout. We bump Y so distance(target) stays > 1.
		step.onStart(ctxAtTick(0, bb, disp, snapAt(0, new WorldPoint(3100, 3100, 0))));
		nav.awaitTickEntered();
		nav.respondWith(NavStatus.RUNNING);

		// Tick 59: still RUNNING
		Completion c59 = step.check(snapAt(59, new WorldPoint(3100, 3159, 0)), bb);
		assertEquals(Completion.RUNNING, c59);

		// Tick 60: TIMEOUT
		Completion c60 = step.check(snapAt(60, new WorldPoint(3100, 3160, 0)), bb);
		assertTrue("expected Failed", c60 instanceof Completion.Failed);
		StepEvent failed = rec.lastFailed().orElseThrow();
		assertEquals("TIMEOUT", failed.diagnosticReason());
		assertTrue("stopWorker must have been called on TIMEOUT",
			step.stopFlagForTesting());
	}

	// ── 6. STUCK detection ──────────────────────────────────────────

	@Test
	public void stuckDetectionAfter6Ticks() throws Exception
	{
		Artemis artemis = artemisWithSession(true);
		RecordingRecorder rec = new RecordingRecorder();
		LatchedNavigator nav = new LatchedNavigator();
		WalkToWorldPointStep step = newStep(artemis, rec, nav);

		ScopedBlackboard bb = new ScopedBlackboard();
		MockInputDispatcher disp = new MockInputDispatcher();
		WorldPoint frozen = new WorldPoint(3100, 3100, 0);   // far from target, never moves
		step.onStart(ctxAtTick(0, bb, disp, snapAt(0, frozen)));
		nav.awaitTickEntered();
		nav.respondWith(NavStatus.RUNNING);

		// Phase 1A.4d.1: stuck counter is gated on workerEverRunning.
		// Wait for the worker to observe RUNNING and publish the
		// volatile flag before counting ticks — otherwise the race
		// between respondWith() and the worker's write would make
		// "STUCK at exactly tick 6" assertion flaky.
		awaitCondition(step::workerEverRunningForTesting, 1000,
			"workerEverRunning=true after NavStatus.RUNNING");

		// Ticks 1..5: stuck counter increments but below threshold (6)
		for (int t = 1; t <= 5; t++)
		{
			Completion c = step.check(snapAt(t, frozen), bb);
			assertEquals("tick " + t + " should still be RUNNING", Completion.RUNNING, c);
		}
		// Tick 6: STUCK fires
		Completion c6 = step.check(snapAt(6, frozen), bb);
		assertTrue("expected Failed(STUCK)", c6 instanceof Completion.Failed);
		StepEvent failed = rec.lastFailed().orElseThrow();
		assertEquals(WalkToWorldPointStep.REASON_STUCK, failed.diagnosticReason());
		assertTrue("stopWorker must have been called on STUCK",
			step.stopFlagForTesting());
	}

	@Test
	public void stuckCounter_doesNotIncrement_beforeWorkerEverRunning() throws Exception
	{
		// Phase 1A.4d.1 gate: stuck counter must NOT fire while the
		// worker has been spawned but has not yet observed RUNNING.
		// This is the Run 03 F-D1 fix — long walks where the
		// dispatcher's humanized cursor + minimap click chain takes
		// 3+ seconds before the player physically moves used to fire
		// STUCK before the click had even landed.
		Artemis artemis = artemisWithSession(true);
		RecordingRecorder rec = new RecordingRecorder();
		LatchedNavigator nav = new LatchedNavigator();
		WalkToWorldPointStep step = newStep(artemis, rec, nav);

		ScopedBlackboard bb = new ScopedBlackboard();
		MockInputDispatcher disp = new MockInputDispatcher();
		WorldPoint frozen = new WorldPoint(3100, 3100, 0);
		step.onStart(ctxAtTick(0, bb, disp, snapAt(0, frozen)));
		nav.awaitTickEntered();

		// Deliberately respond with IDLE — the worker observes IDLE,
		// loops back to nav.tick(), and blocks waiting for the next
		// response. workerEverRunning stays false the entire time.
		nav.respondWith(NavStatus.IDLE);
		// Second awaitTickEntered confirms the worker processed IDLE
		// and looped back; if it had received RUNNING the flag would
		// be set by now.
		nav.awaitTickEntered();
		assertFalse("workerEverRunning must remain false when only IDLE has been seen",
			step.workerEverRunningForTesting());

		// Advance well past STUCK_THRESHOLD_TICKS (6) with location
		// frozen. Under the old code, STUCK would have fired at tick 6.
		// With the gate, it must NOT.
		for (int t = 1; t <= 20; t++)
		{
			Completion c = step.check(snapAt(t, frozen), bb);
			assertEquals("tick " + t + " must stay RUNNING — workerEverRunning gate not yet released",
				Completion.RUNNING, c);
		}
		assertFalse("stopWorker must NOT have been called — gate never released",
			step.stopFlagForTesting());
	}

	// ── 7. NO_ROUTE (first tick FAILED) ─────────────────────────────

	@Test
	public void noRouteWhenFirstNavTickFails() throws Exception
	{
		Artemis artemis = artemisWithSession(true);
		RecordingRecorder rec = new RecordingRecorder();
		LatchedNavigator nav = new LatchedNavigator();
		WalkToWorldPointStep step = newStep(artemis, rec, nav);

		ScopedBlackboard bb = new ScopedBlackboard();
		MockInputDispatcher disp = new MockInputDispatcher();
		step.onStart(ctxAtTick(0, bb, disp, snapAt(0, OUT_OF_RANGE)));

		// Worker's very first nav.tick returns FAILED. workerHadRunning
		// remained false → NO_ROUTE.
		nav.awaitTickEntered();
		nav.respondWith(NavStatus.FAILED);

		// Wait for worker to settle (write lastStatus + failureCode).
		awaitCondition(() -> step.lastStatusForTesting() == NavStatus.FAILED, 1000,
			"lastStatus=FAILED");

		// doCheck observes the worker FAILED via lastStatus and surfaces
		// the captured failureCode.
		Completion c = step.check(snapAt(1, OUT_OF_RANGE), bb);
		assertTrue("expected Failed", c instanceof Completion.Failed);
		StepEvent failed = rec.lastFailed().orElseThrow();
		assertEquals(WalkToWorldPointStep.REASON_NO_ROUTE, failed.diagnosticReason());
		assertTrue("stopWorker must have been called on NO_ROUTE",
			step.stopFlagForTesting());
	}

	// ── 8. NAVIGATOR_FAILED (mid-route) ─────────────────────────────

	@Test
	public void navigatorFailedWhenMidRouteFailure() throws Exception
	{
		Artemis artemis = artemisWithSession(true);
		RecordingRecorder rec = new RecordingRecorder();
		LatchedNavigator nav = new LatchedNavigator();
		WalkToWorldPointStep step = newStep(artemis, rec, nav);

		ScopedBlackboard bb = new ScopedBlackboard();
		MockInputDispatcher disp = new MockInputDispatcher();
		step.onStart(ctxAtTick(0, bb, disp, snapAt(0, OUT_OF_RANGE)));

		// 1st tick: RUNNING (workerHadRunning = true)
		nav.awaitTickEntered();
		nav.respondWith(NavStatus.RUNNING);
		// 2nd tick: FAILED → NAVIGATOR_FAILED (not NO_ROUTE)
		nav.awaitTickEntered();
		nav.respondWith(NavStatus.FAILED);

		awaitCondition(() -> step.lastStatusForTesting() == NavStatus.FAILED, 1000,
			"lastStatus=FAILED after RUNNING");

		Completion c = step.check(snapAt(2, OUT_OF_RANGE), bb);
		assertTrue("expected Failed", c instanceof Completion.Failed);
		StepEvent failed = rec.lastFailed().orElseThrow();
		assertEquals(WalkToWorldPointStep.REASON_NAVIGATOR_FAILED, failed.diagnosticReason());
		assertTrue("stopWorker must have been called on NAVIGATOR_FAILED",
			step.stopFlagForTesting());
	}

	// ── 9. Structured StepEvents ────────────────────────────────────

	@Test
	public void structuredStepEventsEmitted() throws Exception
	{
		Artemis artemis = artemisWithSession(true);
		RecordingRecorder rec = new RecordingRecorder();
		LatchedNavigator nav = new LatchedNavigator();
		WalkToWorldPointStep step = newStep(artemis, rec, nav);

		ScopedBlackboard bb = new ScopedBlackboard();
		MockInputDispatcher disp = new MockInputDispatcher();
		step.onStart(ctxAtTick(0, bb, disp, snapAt(0, OUT_OF_RANGE)));
		nav.awaitTickEntered();
		nav.respondWith(NavStatus.RUNNING);

		// Drive to success via player snapshot.
		step.check(snapAt(1, IN_RANGE), bb);
		step.check(snapAt(2, IN_RANGE), bb);

		// started event
		StepEvent started = rec.events.get(0);
		assertEquals("started", started.phase());
		assertEquals("WalkToWorldPoint(" + TARGET.getX() + "," + TARGET.getY() + ","
			+ TARGET.getPlane() + ")", started.name());
		assertEquals("tile", started.targetType());
		assertEquals("tile:" + TARGET.getX() + "," + TARGET.getY() + "," + TARGET.getPlane(),
			started.targetId());
		assertNull(started.targetName());
		assertEquals("Walk", started.verb());

		// succeeded event (last)
		StepEvent succeeded = rec.events.get(rec.events.size() - 1);
		assertEquals("succeeded", succeeded.phase());
		assertEquals("tile", succeeded.targetType());
		assertNotNull(succeeded.ticksElapsed());
	}

	// ── 10. onFailure recovery mapping (Phase 1A.4d.1) ─────────────

	@Test
	public void onFailureMapsDiagnosticsCorrectly()
	{
		Artemis artemis = artemisWithSession(true);
		LatchedNavigator nav = new LatchedNavigator();
		WalkToWorldPointStep step = newStep(artemis, null, nav);
		WorldSnapshot snap = snapAt(0, OUT_OF_RANGE);
		ScopedBlackboard bb = new ScopedBlackboard();

		// Phase 1A.4d.1: ALL walk failures Abort. The original Phase
		// 1A.4d code returned Recovery.Retry(2) for STUCK / TIMEOUT /
		// NAVIGATOR_FAILED, but that contradicted the single-use guard
		// (engine's Retry path re-fires onStart on the same Step
		// instance → IllegalStateException). Run 03 F-D1 exposed it.
		// Walk retries are now caller responsibility.
		for (String r : new String[] {
			WalkToWorldPointStep.REASON_STUCK,
			"TIMEOUT",
			WalkToWorldPointStep.REASON_NAVIGATOR_FAILED,
			WalkToWorldPointStep.REASON_NAVIGATOR_MISSING,
			WalkToWorldPointStep.REASON_NO_ROUTE,
			WalkToWorldPointStep.REASON_NAVIGATOR_EXCEPTION })
		{
			Failure f = (r.equals("TIMEOUT"))
				? Failure.fromDiagnostic(new DiagnosticReason.ActionTimedOut("Walk", 60), 60)
				: Failure.fromDiagnostic(new DiagnosticReason.Unknown(r), 0);
			Recovery rec = step.onFailure(f, snap, bb);
			assertTrue("expected Abort for " + r + " but got " + rec,
				rec instanceof Recovery.Abort);
			// Regression guard for Run 03 F-D1: must never return Retry.
			assertFalse(r + " must not return Retry (Phase 1A.4d.1)",
				rec instanceof Recovery.Retry);
		}
	}

	// ── 11. NAVIGATOR_EXCEPTION (worker catches throwable) ─────────

	/** Test Navigator that throws on the first {@code tick()} call.
	 *  Drives the worker's catch(Throwable) branch. */
	private static final class ThrowingNavigator implements Navigator
	{
		private final AtomicInteger tickStarted = new AtomicInteger(0);
		@Override
		public NavStatus tick(NavRequest request)
		{
			tickStarted.incrementAndGet();
			throw new IllegalStateException("simulated planner error");
		}
		@Override public void cancel() {}
		@Override public boolean isBusy() { return false; }
		@Override public String name() { return "throwing"; }
	}

	@Test
	public void navigatorExceptionFromTick() throws Exception
	{
		Artemis artemis = artemisWithSession(true);
		RecordingRecorder rec = new RecordingRecorder();
		ThrowingNavigator nav = new ThrowingNavigator();
		WalkToWorldPointStep step = newStep(artemis, rec, nav);

		ScopedBlackboard bb = new ScopedBlackboard();
		MockInputDispatcher disp = new MockInputDispatcher();
		step.onStart(ctxAtTick(0, bb, disp, snapAt(0, OUT_OF_RANGE)));

		// Worker calls tick(), tick throws → worker catches Throwable,
		// pins NAVIGATOR_EXCEPTION + lastStatus = FAILED, exits.
		awaitCondition(() -> step.lastStatusForTesting() == NavStatus.FAILED, 1000,
			"lastStatus=FAILED via exception");
		assertEquals(1, nav.tickStarted.get());

		Completion c = step.check(snapAt(1, OUT_OF_RANGE), bb);
		assertTrue("expected Failed", c instanceof Completion.Failed);
		StepEvent failed = rec.lastFailed().orElseThrow();
		assertEquals(WalkToWorldPointStep.REASON_NAVIGATOR_EXCEPTION, failed.diagnosticReason());
		assertTrue("stopWorker must have been called on NAVIGATOR_EXCEPTION",
			step.stopFlagForTesting());
	}

	// ── 12. Single-use guard ────────────────────────────────────────

	@Test
	public void singleUseGuardThrowsOnSecondDoStart() throws Exception
	{
		Artemis artemis = artemisWithSession(true);
		RecordingRecorder rec = new RecordingRecorder();
		LatchedNavigator nav = new LatchedNavigator();
		WalkToWorldPointStep step = newStep(artemis, rec, nav);

		ScopedBlackboard bb = new ScopedBlackboard();
		MockInputDispatcher disp = new MockInputDispatcher();
		StepContext ctx1 = ctxAtTick(0, bb, disp, snapAt(0, OUT_OF_RANGE));
		step.onStart(ctx1);
		nav.awaitTickEntered();
		// Drain the worker so we can re-call onStart without a stuck thread.
		nav.respondWith(NavStatus.FAILED);
		awaitCondition(() -> step.lastStatusForTesting() == NavStatus.FAILED, 1000,
			"worker exit after FAILED");

		// Second onStart: base class traps the IllegalStateException as
		// ONSTART_EXCEPTION and emits the failed event.
		step.onStart(ctxAtTick(1, bb, disp, snapAt(1, OUT_OF_RANGE)));
		StepEvent failed = rec.lastFailed().orElseThrow();
		assertEquals("ONSTART_EXCEPTION", failed.diagnosticReason());
		assertTrue("detail should mention single-use",
			failed.detail() != null && failed.detail().contains("single-use"));
	}
}
