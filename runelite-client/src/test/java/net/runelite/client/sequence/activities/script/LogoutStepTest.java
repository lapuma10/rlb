package net.runelite.client.sequence.activities.script;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.plugins.recorder.analyse.StepEvent;
import net.runelite.client.plugins.recorder.session.SessionShape;
import net.runelite.client.sequence.Completion;
import net.runelite.client.sequence.Failure;
import net.runelite.client.sequence.Recovery;
import net.runelite.client.sequence.StepContext;
import net.runelite.client.sequence.WorldSnapshot;
import net.runelite.client.sequence.affordance.DiagnosticReason;
import net.runelite.client.sequence.artemis.Artemis;
import net.runelite.client.sequence.artemis.LogoutAction;
import net.runelite.client.sequence.blackboard.ScopedBlackboard;
import net.runelite.client.sequence.dispatch.MockInputDispatcher;
import net.runelite.client.sequence.internal.ActionRequest;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link LogoutStep}. Pins the Option B lifecycle: one
 * {@code CLICK_WIDGET} per attempt, observation-only LogoutAction, no
 * RUN_TASK, Recovery.Retry(2) for retryable failures, Abort for
 * non-retryable.
 */
public class LogoutStepTest
{
	private static final int LOGOUT_WIDGET_ID = 12345678;

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
		Optional<StepEvent> lastFailed()
		{
			for (int i = events.size() - 1; i >= 0; i--)
			{
				if ("failed".equals(events.get(i).phase())) return Optional.of(events.get(i));
			}
			return Optional.empty();
		}
	}

	/** Fixed-return LogoutAction stub. */
	private static LogoutAction logoutActionReturning(OptionalInt widgetId)
	{
		return () -> widgetId;
	}

	// ── Maintenance bypass ──────────────────────────────────────────

	@Test
	public void bypassesSessionGateWhenSessionExhausted()
	{
		Artemis artemis = artemisWithSession(/* shouldContinue */ false);
		Client client = mock(Client.class);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		RecordingRecorder rec = new RecordingRecorder();
		LogoutAction act = logoutActionReturning(OptionalInt.of(LOGOUT_WIDGET_ID));

		LogoutStep step = new LogoutStep(artemis, rec, client, act);
		ScopedBlackboard bb = new ScopedBlackboard();
		MockInputDispatcher disp = new MockInputDispatcher();
		step.onStart(ctxAtTick(0, bb, disp));

		// Maintenance Step: should have started AND dispatched the
		// CLICK_WIDGET despite the exhausted session. No SESSION_EXHAUSTED
		// failure.
		assertEquals("started", rec.events.get(0).phase());
		assertTrue(rec.events.stream().noneMatch(e -> "failed".equals(e.phase())));
		assertEquals(1, disp.getRequests().size());
	}

	// ── LOGOUT_ACTION_MISSING ───────────────────────────────────────

	@Test
	public void failsLoudIfLogoutActionIsNull()
	{
		Artemis artemis = artemisWithSession(true);
		Client client = mock(Client.class);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		RecordingRecorder rec = new RecordingRecorder();
		LogoutStep step = new LogoutStep(artemis, rec, client, /* logoutAction */ null);

		ScopedBlackboard bb = new ScopedBlackboard();
		MockInputDispatcher disp = new MockInputDispatcher();
		step.onStart(ctxAtTick(5, bb, disp));

		StepEvent failed = rec.lastFailed().orElseThrow();
		assertEquals(LogoutStep.REASON_LOGOUT_ACTION_MISSING, failed.diagnosticReason());
		// And no dispatch happened.
		assertEquals(0, disp.getRequests().size());
		// check() reports the pinned failure on the next tick.
		Completion c = step.check(snapAtTick(5), bb);
		assertTrue("expected Failed", c instanceof Completion.Failed);
	}

	// ── LOGOUT_FAILED — no widget visible at doStart ────────────────

	@Test
	public void failsLogoutFailedWhenNextLogoutWidgetIdIsEmpty()
	{
		Artemis artemis = artemisWithSession(true);
		Client client = mock(Client.class);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		RecordingRecorder rec = new RecordingRecorder();
		LogoutAction act = logoutActionReturning(OptionalInt.empty());

		LogoutStep step = new LogoutStep(artemis, rec, client, act);
		ScopedBlackboard bb = new ScopedBlackboard();
		MockInputDispatcher disp = new MockInputDispatcher();
		step.onStart(ctxAtTick(0, bb, disp));

		StepEvent failed = rec.lastFailed().orElseThrow();
		assertEquals(LogoutStep.REASON_LOGOUT_FAILED, failed.diagnosticReason());
		assertEquals(0, disp.getRequests().size());
	}

	// ── Dispatch path ──────────────────────────────────────────────

	@Test
	public void dispatchesClickWidgetWhenWidgetIdAvailable()
	{
		Artemis artemis = artemisWithSession(true);
		Client client = mock(Client.class);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		LogoutAction act = logoutActionReturning(OptionalInt.of(LOGOUT_WIDGET_ID));

		LogoutStep step = new LogoutStep(artemis, null, client, act);
		ScopedBlackboard bb = new ScopedBlackboard();
		MockInputDispatcher disp = new MockInputDispatcher();
		step.onStart(ctxAtTick(0, bb, disp));

		assertEquals(1, disp.getRequests().size());
		ActionRequest req = disp.getRequests().get(0);
		assertEquals(ActionRequest.Kind.CLICK_WIDGET, req.getKind());
		assertEquals(ActionRequest.Channel.MOUSE, req.getChannel());
		assertEquals(LOGOUT_WIDGET_ID, req.getWidgetId());
		assertEquals("Logout", req.getVerb());
	}

	// ── Success path ───────────────────────────────────────────────

	@Test
	public void succeedsWhenGameStateBecomesLoginScreen()
	{
		Artemis artemis = artemisWithSession(true);
		Client client = mock(Client.class);
		// LOGGED_IN at dispatch time, LOGIN_SCREEN by the time check() runs.
		// getGameState() is read only from doCheck; doStart does not consult
		// it. Returning LOGIN_SCREEN every call models "the click landed
		// and state transitioned before check() ran" without depending on
		// stub call counts.
		when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
		LogoutAction act = logoutActionReturning(OptionalInt.of(LOGOUT_WIDGET_ID));

		RecordingRecorder rec = new RecordingRecorder();
		LogoutStep step = new LogoutStep(artemis, rec, client, act);
		ScopedBlackboard bb = new ScopedBlackboard();
		MockInputDispatcher disp = new MockInputDispatcher();
		step.onStart(ctxAtTick(0, bb, disp));

		// Dispatcher idle (default). client.getGameState() now returns
		// LOGIN_SCREEN on the second call.
		Completion c = step.check(snapAtTick(1), bb);
		assertTrue("expected Succeeded but got " + c, c instanceof Completion.Succeeded);
		assertEquals("succeeded", rec.events.get(rec.events.size() - 1).phase());
	}

	// ── LOGOUT_FAILED — dispatcher idle but state unchanged ────────

	@Test
	public void failsLogoutFailedWhenDispatcherIdleButStateUnchanged()
	{
		Artemis artemis = artemisWithSession(true);
		Client client = mock(Client.class);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);   // never changes
		LogoutAction act = logoutActionReturning(OptionalInt.of(LOGOUT_WIDGET_ID));

		RecordingRecorder rec = new RecordingRecorder();
		LogoutStep step = new LogoutStep(artemis, rec, client, act);
		ScopedBlackboard bb = new ScopedBlackboard();
		MockInputDispatcher disp = new MockInputDispatcher();
		// disp.isBusy() = false by default → dispatcherIdle = true
		step.onStart(ctxAtTick(0, bb, disp));

		Completion c = step.check(snapAtTick(1), bb);
		assertTrue("expected Failed", c instanceof Completion.Failed);
		StepEvent failed = rec.lastFailed().orElseThrow();
		assertEquals(LogoutStep.REASON_LOGOUT_FAILED, failed.diagnosticReason());
	}

	// ── TIMEOUT ─────────────────────────────────────────────────────

	@Test
	public void failsTimeoutAfter8TicksWhileBusy()
	{
		Artemis artemis = artemisWithSession(true);
		Client client = mock(Client.class);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		LogoutAction act = logoutActionReturning(OptionalInt.of(LOGOUT_WIDGET_ID));

		RecordingRecorder rec = new RecordingRecorder();
		LogoutStep step = new LogoutStep(artemis, rec, client, act);
		ScopedBlackboard bb = new ScopedBlackboard();
		MockInputDispatcher disp = new MockInputDispatcher();
		// Hold dispatcher busy throughout — would otherwise short-circuit
		// to LOGOUT_FAILED at tick 1.
		disp.setBusy(true);
		step.onStart(ctxAtTick(0, bb, disp));

		// Tick 7 (elapsed=7): still RUNNING (< 8)
		assertEquals(Completion.RUNNING, step.check(snapAtTick(7), bb));
		// Tick 8 (elapsed=8 ≥ 8): TIMEOUT
		Completion c = step.check(snapAtTick(8), bb);
		assertTrue("expected Failed", c instanceof Completion.Failed);
		StepEvent failed = rec.lastFailed().orElseThrow();
		// Base class maps DiagnosticReason.ActionTimedOut → "TIMEOUT".
		assertEquals("TIMEOUT", failed.diagnosticReason());
	}

	// ── Recovery ───────────────────────────────────────────────────

	@Test
	public void onFailureReturnsRetryForLogoutFailedAndTimeout()
	{
		Artemis artemis = artemisWithSession(true);
		Client client = mock(Client.class);
		LogoutStep step = new LogoutStep(artemis, null, client,
			logoutActionReturning(OptionalInt.of(LOGOUT_WIDGET_ID)));
		WorldSnapshot s = snapAtTick(0);
		ScopedBlackboard bb = new ScopedBlackboard();

		// LOGOUT_FAILED via Unknown(detail)
		Failure logoutFailed = Failure.fromDiagnostic(
			new DiagnosticReason.Unknown(LogoutStep.REASON_LOGOUT_FAILED), 3);
		Recovery r1 = step.onFailure(logoutFailed, s, bb);
		assertTrue("expected Retry for LOGOUT_FAILED", r1 instanceof Recovery.Retry);
		assertEquals(2, ((Recovery.Retry) r1).maxAttempts());

		// TIMEOUT via ActionTimedOut
		Failure timeout = Failure.fromDiagnostic(
			new DiagnosticReason.ActionTimedOut("Logout", 8), 8);
		Recovery r2 = step.onFailure(timeout, s, bb);
		assertTrue("expected Retry for TIMEOUT", r2 instanceof Recovery.Retry);
		assertEquals(2, ((Recovery.Retry) r2).maxAttempts());
	}

	@Test
	public void onFailureReturnsAbortForLogoutActionMissing()
	{
		Artemis artemis = artemisWithSession(true);
		Client client = mock(Client.class);
		LogoutStep step = new LogoutStep(artemis, null, client, /* logoutAction */ null);
		WorldSnapshot s = snapAtTick(0);
		ScopedBlackboard bb = new ScopedBlackboard();

		Failure missing = Failure.fromDiagnostic(
			new DiagnosticReason.Unknown(LogoutStep.REASON_LOGOUT_ACTION_MISSING), 0);
		Recovery r = step.onFailure(missing, s, bb);
		assertTrue("expected Abort for LOGOUT_ACTION_MISSING",
			r instanceof Recovery.Abort);
	}

	// ── StepEvent schema ───────────────────────────────────────────

	@Test
	public void emitsStructuredStepEvents()
	{
		Artemis artemis = artemisWithSession(true);
		Client client = mock(Client.class);
		// getGameState() is read only from doCheck; doStart does not consult
		// it. Returning LOGIN_SCREEN every call models "the click landed
		// and state transitioned before check() ran" without depending on
		// stub call counts.
		when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
		LogoutAction act = logoutActionReturning(OptionalInt.of(LOGOUT_WIDGET_ID));

		RecordingRecorder rec = new RecordingRecorder();
		LogoutStep step = new LogoutStep(artemis, rec, client, act);
		ScopedBlackboard bb = new ScopedBlackboard();
		MockInputDispatcher disp = new MockInputDispatcher();
		step.onStart(ctxAtTick(0, bb, disp));
		step.check(snapAtTick(1), bb);

		// started event
		StepEvent started = rec.events.get(0);
		assertEquals("Logout", started.name());
		assertEquals("started", started.phase());
		assertEquals("game-state", started.targetType());
		assertEquals("game-state:LOGIN_SCREEN", started.targetId());
		assertEquals("Logout", started.verb());
		assertNotNull(started);

		// succeeded event (last)
		StepEvent succeeded = rec.events.get(rec.events.size() - 1);
		assertEquals("succeeded", succeeded.phase());
		assertEquals("game-state", succeeded.targetType());
		assertEquals("game-state:LOGIN_SCREEN", succeeded.targetId());
		assertEquals("Logout", succeeded.verb());
		assertNotNull(succeeded.ticksElapsed());
	}
}
