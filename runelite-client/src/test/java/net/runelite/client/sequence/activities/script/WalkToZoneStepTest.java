package net.runelite.client.sequence.activities.script;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
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
import net.runelite.client.sequence.artemis.query.RotationPolicySelector;
import net.runelite.client.sequence.artemis.zones.NamedZone;
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
 * Tests for {@link WalkToZoneStep}. Pins the EMPTY_ZONE pre-flight
 * (no worker spawned, no Navigator call), the tile pick via the shared
 * {@link RotationPolicySelector}, and the zone-membership arrival
 * predicate. Uses the same {@code LatchedNavigator} pattern as
 * {@link WalkToWorldPointStepTest} — no Thread.sleep-based timing
 * races.
 */
public class WalkToZoneStepTest
{
	/** The populated smoke zone (Phase 1A.4d). */
	private static final NamedZone SMOKE_ZONE = NamedZone.LUMBRIDGE_CASTLE_GROUND_FLOOR;

	/** Empty-zone fixture. NOTE: if {@code LUMBRIDGE_BANK} is populated
	 *  later (Phase 5+ bank-tier migration), this test must move to
	 *  another empty zone or build a helper that picks any empty zone
	 *  dynamically. */
	private static final NamedZone EMPTY_ZONE = NamedZone.LUMBRIDGE_BANK;

	/** A tile inside SMOKE_ZONE (center of the 9×9 rectangle). */
	private static final WorldPoint IN_ZONE = new WorldPoint(3221, 3219, 0);

	/** A tile outside SMOKE_ZONE. */
	private static final WorldPoint OUT_OF_ZONE = new WorldPoint(3100, 3100, 0);

	private final List<WalkToZoneStep> live = new ArrayList<>();

	@After
	public void tearDown() throws Exception
	{
		for (WalkToZoneStep s : live)
		{
			try
			{
				java.lang.reflect.Method m = WalkStepBase.class
					.getDeclaredMethod("stopWorker", String.class);
				m.setAccessible(true);
				m.invoke(s, "tearDown");
			}
			catch (ReflectiveOperationException ignored) { /* best-effort */ }
		}
		live.clear();
	}

	// ── LatchedNavigator (copy of WalkToWorldPointStepTest's helper) ─

	private static final class LatchedNavigator implements Navigator
	{
		private final LinkedBlockingQueue<NavStatus> responses = new LinkedBlockingQueue<>();
		private final Semaphore tickStarted = new Semaphore(0);
		private final AtomicInteger tickCount = new AtomicInteger(0);
		@Nullable private volatile NavRequest lastRequest = null;

		@Override
		public NavStatus tick(NavRequest request) throws InterruptedException
		{
			this.lastRequest = request;
			tickCount.incrementAndGet();
			tickStarted.release();
			return responses.take();
		}

		@Override public void cancel() {}
		@Override public boolean isBusy() { return false; }
		@Override public String name() { return "latched"; }

		void awaitTickEntered() throws InterruptedException
		{
			if (!tickStarted.tryAcquire(2, TimeUnit.SECONDS))
			{
				fail("worker did not enter navigator.tick() within 2s");
			}
		}

		void respondWith(NavStatus s) { responses.add(s); }
		int tickCalls() { return tickCount.get(); }
		@Nullable NavRequest lastRequest() { return lastRequest; }
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

	private static RotationPolicySelector deterministicSelector()
	{
		// Same RNG seed = same picks across tests; combined with
		// UniformWithinRange this gives a deterministic chosen tile.
		return new RotationPolicySelector(new Random(0xABCDEF0L));
	}

	private WalkToZoneStep newStep(Artemis a, java.util.function.Consumer<StepEvent> sink,
		NamedZone zone, @Nullable Navigator nav)
	{
		WalkToZoneStep s = new WalkToZoneStep(a, sink, zone, nav, deterministicSelector());
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

	// ── 1. Populated smoke zone has tiles ───────────────────────────

	@Test
	public void populatedSmokeZoneHasNonEmptyTiles()
	{
		List<WorldPoint> tiles = SMOKE_ZONE.tiles();
		assertFalse("LUMBRIDGE_CASTLE_GROUND_FLOOR.tiles() must be populated", tiles.isEmpty());
		assertEquals("expected 9×9 = 81 tiles", 81, tiles.size());
		assertTrue("center tile (3221,3219,0) should be in the zone",
			tiles.contains(IN_ZONE));
		// All tiles share plane 0 and zone's declared plane.
		assertEquals(0, SMOKE_ZONE.plane());
	}

	// ── 1b. Phase 2A populated cow field ────────────────────────────

	@Test
	public void cowFieldIsPopulatedInPhase2A()
	{
		// Phase 2A populated LUMBRIDGE_COW_FIELD with 220 tiles
		// (10×22 rectangle). If this assertion fails, either the zone
		// data regressed or Phase 2A was not applied.
		List<WorldPoint> tiles = NamedZone.LUMBRIDGE_COW_FIELD.tiles();
		assertFalse("LUMBRIDGE_COW_FIELD.tiles() must be populated in Phase 2A",
			tiles.isEmpty());
		assertEquals("expected 10×22 = 220 tiles", 220, tiles.size());
	}

	@Test
	public void cowFieldDoesNotFailEmptyZone() throws Exception
	{
		// Phase 2A acceptance: WalkToZoneStep for LUMBRIDGE_COW_FIELD
		// must NOT emit a REASON_EMPTY_ZONE failure. Verifies the
		// data-layer population (NamedZoneTest) actually reaches the
		// step's doPreFlight — i.e. no enum-constant body wiring error
		// hides the populated tile list from the runtime path.
		Artemis artemis = artemisWithSession(true);
		RecordingRecorder rec = new RecordingRecorder();
		LatchedNavigator nav = new LatchedNavigator();
		WalkToZoneStep step = newStep(artemis, rec, NamedZone.LUMBRIDGE_COW_FIELD, nav);

		ScopedBlackboard bb = new ScopedBlackboard();
		MockInputDispatcher disp = new MockInputDispatcher();
		step.onStart(ctxAtTick(0, bb, disp, snapAt(0, OUT_OF_ZONE)));

		// No EMPTY_ZONE failure recorded — either no failure at all, or
		// the failure (if any) is not the empty-zone diagnostic.
		Optional<StepEvent> failed = rec.lastFailed();
		assertTrue("LUMBRIDGE_COW_FIELD doPreFlight must not emit REASON_EMPTY_ZONE",
			failed.isEmpty()
				|| !WalkToZoneStep.REASON_EMPTY_ZONE.equals(failed.get().diagnosticReason()));

		// doPreFlight passed — a chosen tile was picked from the zone.
		WorldPoint chosen = step.chosenTileForTesting();
		assertNotNull("doPreFlight should have picked a tile for the populated zone",
			chosen);
		assertTrue("chosen tile " + chosen + " must be inside LUMBRIDGE_COW_FIELD.tiles()",
			NamedZone.LUMBRIDGE_COW_FIELD.tiles().contains(chosen));

		// Drain the navigator so the worker exits cleanly under tearDown.
		nav.awaitTickEntered();
		nav.respondWith(NavStatus.RUNNING);
	}

	// ── 2. EMPTY_ZONE diagnostic ────────────────────────────────────

	@Test
	public void emptyZoneFailsLoudWithEmptyZoneDiagnostic()
	{
		Artemis artemis = artemisWithSession(true);
		RecordingRecorder rec = new RecordingRecorder();
		LatchedNavigator nav = new LatchedNavigator();
		WalkToZoneStep step = newStep(artemis, rec, EMPTY_ZONE, nav);

		ScopedBlackboard bb = new ScopedBlackboard();
		MockInputDispatcher disp = new MockInputDispatcher();
		step.onStart(ctxAtTick(0, bb, disp, snapAt(0, OUT_OF_ZONE)));

		StepEvent failed = rec.lastFailed().orElseThrow();
		assertEquals(WalkToZoneStep.REASON_EMPTY_ZONE, failed.diagnosticReason());
		assertTrue("detail should mention NamedZone." + EMPTY_ZONE.name(),
			failed.detail() != null && failed.detail().contains(EMPTY_ZONE.name()));
	}

	// ── 3. EMPTY_ZONE does not start worker ─────────────────────────

	@Test
	public void emptyZoneDoesNotStartWorker() throws Exception
	{
		Artemis artemis = artemisWithSession(true);
		LatchedNavigator nav = new LatchedNavigator();
		WalkToZoneStep step = newStep(artemis, null, EMPTY_ZONE, nav);

		ScopedBlackboard bb = new ScopedBlackboard();
		MockInputDispatcher disp = new MockInputDispatcher();
		step.onStart(ctxAtTick(0, bb, disp, snapAt(0, OUT_OF_ZONE)));

		assertFalse("EMPTY_ZONE must NOT spawn a worker", step.workerAliveForTesting());
	}

	// ── 4. EMPTY_ZONE does not touch Navigator ──────────────────────

	@Test
	public void emptyZoneDoesNotCallNavigator() throws Exception
	{
		Artemis artemis = artemisWithSession(true);
		LatchedNavigator nav = new LatchedNavigator();
		WalkToZoneStep step = newStep(artemis, null, EMPTY_ZONE, nav);

		ScopedBlackboard bb = new ScopedBlackboard();
		MockInputDispatcher disp = new MockInputDispatcher();
		step.onStart(ctxAtTick(0, bb, disp, snapAt(0, OUT_OF_ZONE)));

		assertEquals("navigator.tick must NOT be called when zone is empty",
			0, nav.tickCalls());
	}

	// ── 5. Chosen tile is in the zone tile set ──────────────────────

	@Test
	public void chosenTileIsInZoneTileSet() throws Exception
	{
		Artemis artemis = artemisWithSession(true);
		LatchedNavigator nav = new LatchedNavigator();
		WalkToZoneStep step = newStep(artemis, null, SMOKE_ZONE, nav);

		ScopedBlackboard bb = new ScopedBlackboard();
		MockInputDispatcher disp = new MockInputDispatcher();
		step.onStart(ctxAtTick(0, bb, disp, snapAt(0, OUT_OF_ZONE)));

		WorldPoint chosen = step.chosenTileForTesting();
		assertNotNull("doPreFlight should have picked a tile", chosen);
		assertTrue("chosen tile " + chosen + " must be in zone.tiles()",
			SMOKE_ZONE.tiles().contains(chosen));
		assertEquals("chosen tile must be on the zone's plane",
			SMOKE_ZONE.plane(), chosen.getPlane());

		// And the worker request must use that exact tile.
		nav.awaitTickEntered();
		assertNotNull(nav.lastRequest());
		assertEquals(chosen, nav.lastRequest().to());
		nav.respondWith(NavStatus.RUNNING);
	}

	// ── 6. Arrival debounce: succeeds after 2 in-zone ticks ─────────

	@Test
	public void succeedsAfterTwoConsecutiveTicksInZone() throws Exception
	{
		Artemis artemis = artemisWithSession(true);
		RecordingRecorder rec = new RecordingRecorder();
		LatchedNavigator nav = new LatchedNavigator();
		WalkToZoneStep step = newStep(artemis, rec, SMOKE_ZONE, nav);

		ScopedBlackboard bb = new ScopedBlackboard();
		MockInputDispatcher disp = new MockInputDispatcher();
		step.onStart(ctxAtTick(0, bb, disp, snapAt(0, OUT_OF_ZONE)));
		nav.awaitTickEntered();
		nav.respondWith(NavStatus.RUNNING);

		// Use a zone tile that is NOT the chosen tile (just to prove
		// success doesn't require arriving at the chosen tile).
		WorldPoint chosen = step.chosenTileForTesting();
		WorldPoint zoneEdge = new WorldPoint(3217, 3215, 0); // corner of the 9×9
		assertNotNull(chosen);
		assertTrue("chosen tile should be different from zoneEdge",
			!chosen.equals(zoneEdge));
		assertTrue("zoneEdge should be in zone",
			SMOKE_ZONE.tiles().contains(zoneEdge));

		// Tick 1: in zone (not chosen tile) — 1 consecutive in-range
		Completion c1 = step.check(snapAt(1, zoneEdge), bb);
		assertEquals(Completion.RUNNING, c1);
		// Tick 2: still in zone — 2 consecutive, succeed
		Completion c2 = step.check(snapAt(2, zoneEdge), bb);
		assertTrue("expected Succeeded but got " + c2, c2 instanceof Completion.Succeeded);
		assertTrue("stopWorker must have been called on Succeeded",
			step.stopFlagForTesting());
	}

	// ── 7. Debounce: one in-zone tick is not enough ─────────────────

	@Test
	public void doesNotSucceedAfterOneInZoneTick() throws Exception
	{
		Artemis artemis = artemisWithSession(true);
		LatchedNavigator nav = new LatchedNavigator();
		WalkToZoneStep step = newStep(artemis, null, SMOKE_ZONE, nav);

		ScopedBlackboard bb = new ScopedBlackboard();
		MockInputDispatcher disp = new MockInputDispatcher();
		step.onStart(ctxAtTick(0, bb, disp, snapAt(0, OUT_OF_ZONE)));
		nav.awaitTickEntered();
		nav.respondWith(NavStatus.RUNNING);

		assertEquals(Completion.RUNNING, step.check(snapAt(1, IN_ZONE), bb));
		// Drifted out of zone — counter resets
		assertEquals(Completion.RUNNING, step.check(snapAt(2, OUT_OF_ZONE), bb));
		// Back in zone — only 1 consecutive
		assertEquals(Completion.RUNNING, step.check(snapAt(3, IN_ZONE), bb));
		// Still in zone — now 2 consecutive
		Completion c = step.check(snapAt(4, IN_ZONE), bb);
		assertTrue("expected Succeeded after debounce", c instanceof Completion.Succeeded);
	}

	// ── 8. Session gate ─────────────────────────────────────────────

	@Test
	public void sessionExhaustedBlocksStart()
	{
		Artemis artemis = artemisWithSession(false);
		RecordingRecorder rec = new RecordingRecorder();
		LatchedNavigator nav = new LatchedNavigator();
		WalkToZoneStep step = newStep(artemis, rec, SMOKE_ZONE, nav);

		ScopedBlackboard bb = new ScopedBlackboard();
		MockInputDispatcher disp = new MockInputDispatcher();
		step.onStart(ctxAtTick(0, bb, disp, snapAt(0, IN_ZONE)));

		StepEvent failed = rec.lastFailed().orElseThrow();
		assertEquals("SESSION_EXHAUSTED", failed.diagnosticReason());
		assertFalse("no worker on session-exhausted", step.workerAliveForTesting());
		assertEquals(0, nav.tickCalls());
	}

	// ── 9. NAVIGATOR_MISSING ────────────────────────────────────────

	@Test
	public void navigatorMissingFailsLoud()
	{
		Artemis artemis = artemisWithSession(true);
		RecordingRecorder rec = new RecordingRecorder();
		WalkToZoneStep step = newStep(artemis, rec, SMOKE_ZONE, /* nav */ null);

		ScopedBlackboard bb = new ScopedBlackboard();
		MockInputDispatcher disp = new MockInputDispatcher();
		step.onStart(ctxAtTick(0, bb, disp, snapAt(0, OUT_OF_ZONE)));

		StepEvent failed = rec.lastFailed().orElseThrow();
		assertEquals(WalkStepBase.REASON_NAVIGATOR_MISSING, failed.diagnosticReason());
		assertFalse(step.workerAliveForTesting());
	}

	// ── 10. Structured StepEvents ───────────────────────────────────

	@Test
	public void structuredStepEventsEmitted() throws Exception
	{
		Artemis artemis = artemisWithSession(true);
		RecordingRecorder rec = new RecordingRecorder();
		LatchedNavigator nav = new LatchedNavigator();
		WalkToZoneStep step = newStep(artemis, rec, SMOKE_ZONE, nav);

		ScopedBlackboard bb = new ScopedBlackboard();
		MockInputDispatcher disp = new MockInputDispatcher();
		step.onStart(ctxAtTick(0, bb, disp, snapAt(0, OUT_OF_ZONE)));
		nav.awaitTickEntered();
		nav.respondWith(NavStatus.RUNNING);

		step.check(snapAt(1, IN_ZONE), bb);
		step.check(snapAt(2, IN_ZONE), bb);

		StepEvent started = rec.events.get(0);
		assertEquals("started", started.phase());
		assertEquals("WalkToZone(" + SMOKE_ZONE.name() + ")", started.name());
		assertEquals("zone", started.targetType());
		assertEquals("zone:" + SMOKE_ZONE.name(), started.targetId());
		assertEquals(SMOKE_ZONE.name(), started.targetName());
		assertEquals("Walk", started.verb());

		StepEvent succeeded = rec.events.get(rec.events.size() - 1);
		assertEquals("succeeded", succeeded.phase());
		assertEquals("zone", succeeded.targetType());
		assertEquals(SMOKE_ZONE.name(), succeeded.targetName());
		assertNotNull(succeeded.ticksElapsed());
	}

	// ── 11. Recovery mapping (Phase 1A.4d.1: every reason → Abort) ──

	@Test
	public void onFailureMapsAllDiagnosticsCorrectly()
	{
		Artemis artemis = artemisWithSession(true);
		LatchedNavigator nav = new LatchedNavigator();
		WalkToZoneStep step = newStep(artemis, null, SMOKE_ZONE, nav);
		WorldSnapshot snap = snapAt(0, OUT_OF_ZONE);
		ScopedBlackboard bb = new ScopedBlackboard();

		// Phase 1A.4d.1: ALL walk failures Abort. The original Phase
		// 1A.4d code returned Recovery.Retry(2) for STUCK / TIMEOUT /
		// NAVIGATOR_FAILED, but that contradicted the single-use guard
		// (engine's Retry path re-fires onStart on the same Step
		// instance → IllegalStateException). Run 03 F-D1 exposed it.
		// Walk retries are now caller responsibility via a parent
		// composite that constructs a fresh artemis.walkTo(...) Step
		// per attempt.
		for (String r : new String[] {
			WalkStepBase.REASON_STUCK,
			"TIMEOUT",
			WalkStepBase.REASON_NAVIGATOR_FAILED,
			WalkStepBase.REASON_NAVIGATOR_MISSING,
			WalkStepBase.REASON_NO_ROUTE,
			WalkStepBase.REASON_NAVIGATOR_EXCEPTION,
			WalkToZoneStep.REASON_EMPTY_ZONE })
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
}
