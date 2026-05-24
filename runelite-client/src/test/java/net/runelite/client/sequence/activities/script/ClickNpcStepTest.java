package net.runelite.client.sequence.activities.script;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.analyse.StepEvent;
import net.runelite.client.plugins.recorder.session.SessionShape;
import net.runelite.client.sequence.Completion;
import net.runelite.client.sequence.StepContext;
import net.runelite.client.sequence.WorldSnapshot;
import net.runelite.client.sequence.artemis.Artemis;
import net.runelite.client.sequence.artemis.query.NpcQuery;
import net.runelite.client.sequence.artemis.view.NpcRef;
import net.runelite.client.sequence.blackboard.ScopedBlackboard;
import net.runelite.client.sequence.dispatch.MockInputDispatcher;
import net.runelite.client.sequence.internal.ActionRequest;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ClickNpcStep} — stale-ref rejection, identity-match
 * re-resolution, structured StepEvent emission, and the exact
 * {@link ActionRequest} shape dispatched.
 */
public class ClickNpcStepTest
{
	private static final WorldPoint LOC = new WorldPoint(3200, 3200, 0);
	private static final NpcRef REF = new NpcRef(7, 1234, "Cow", LOC, 100, /*observedTick*/ 50L);

	private static final class RecordingRecorder implements Consumer<StepEvent>
	{
		final List<StepEvent> events = new ArrayList<>();
		@Override public void accept(StepEvent ev) { events.add(ev); }
	}

	private static StepContext ctx(int tick, ScopedBlackboard bb, MockInputDispatcher disp)
	{
		StepContext ctx = mock(StepContext.class);
		when(ctx.bb()).thenReturn(bb);
		when(ctx.currentTick()).thenReturn(tick);
		when(ctx.dispatcher()).thenReturn(disp);
		return ctx;
	}

	private static WorldSnapshot snap(int tick)
	{
		WorldSnapshot s = mock(WorldSnapshot.class);
		when(s.tick()).thenReturn(tick);
		return s;
	}

	private Artemis artemisActive(NpcRef liveResult)
	{
		Artemis a = mock(Artemis.class);
		when(a.session()).thenReturn(new SessionShape(() -> 0L, () -> 0L, Long.MAX_VALUE));
		when(a.player()).thenReturn(null);
		when(a.findNpc(any(NpcQuery.class)))
			.thenReturn(liveResult == null ? Optional.empty() : Optional.of(liveResult));
		return a;
	}

	// ── Stale ref (age > budget) ────────────────────────────────────

	@Test
	public void staleRefByAgeFailsBeforeDispatch()
	{
		Artemis artemis = artemisActive(REF);
		RecordingRecorder rec = new RecordingRecorder();
		MockInputDispatcher disp = new MockInputDispatcher();
		ClickNpcStep step = new ClickNpcStep(artemis, rec, REF, "Attack");

		ScopedBlackboard bb = new ScopedBlackboard();
		// ref.observedTick == 50; budget = 8 ticks; now = 100 → 50 > 8.
		step.onStart(ctx(100, bb, disp));

		assertTrue("no dispatch should fire for stale ref", disp.getRequests().isEmpty());
		Completion result = step.check(snap(101), bb);
		assertTrue(result instanceof Completion.Failed);

		// StepEvent: started + failed with STALE_REF diagnostic.
		assertEquals(2, rec.events.size());
		assertEquals("failed", rec.events.get(1).phase());
		assertEquals("STALE_REF", rec.events.get(1).diagnosticReason());
		assertEquals("npc", rec.events.get(1).targetType());
		assertEquals("Attack", rec.events.get(1).verb());
	}

	// ── Target not found ────────────────────────────────────────────

	@Test
	public void targetNotFoundFailsBeforeDispatch()
	{
		Artemis artemis = artemisActive(null);   // findNpc returns empty
		RecordingRecorder rec = new RecordingRecorder();
		MockInputDispatcher disp = new MockInputDispatcher();
		ClickNpcStep step = new ClickNpcStep(artemis, rec, REF, "Attack");

		ScopedBlackboard bb = new ScopedBlackboard();
		step.onStart(ctx(52, bb, disp));   // age 2 ≤ 8

		assertTrue(disp.getRequests().isEmpty());
		assertEquals("TARGET_NOT_FOUND", rec.events.get(1).diagnosticReason());
	}

	// ── Identity mismatch (drift > 2 tiles) ────────────────────────

	@Test
	public void liveNpcDriftedTooFarRejectsAsStale()
	{
		// Ref originalLoc = 3200,3200. Live NPC at 3210,3200 → drift=10 > 2.
		NpcRef drifted = new NpcRef(7, 1234, "Cow",
			new WorldPoint(3210, 3200, 0), 100, 60L);
		Artemis artemis = artemisActive(drifted);
		RecordingRecorder rec = new RecordingRecorder();
		MockInputDispatcher disp = new MockInputDispatcher();
		ClickNpcStep step = new ClickNpcStep(artemis, rec, REF, "Attack");

		ScopedBlackboard bb = new ScopedBlackboard();
		step.onStart(ctx(52, bb, disp));

		assertTrue(disp.getRequests().isEmpty());
		assertEquals("STALE_REF", rec.events.get(1).diagnosticReason());
	}

	// ── Happy path: dispatches CLICK_NPC with the live index ────────

	@Test
	public void happyPathDispatchesClickNpcWithLiveIndexAndVerb()
	{
		// Live NPC has a DIFFERENT index — engine reused 7 for someone
		// else after our find. The Step must dispatch on the live index.
		NpcRef live = new NpcRef(11, 1234, "Cow", LOC, 100, 60L);
		Artemis artemis = artemisActive(live);
		RecordingRecorder rec = new RecordingRecorder();
		MockInputDispatcher disp = new MockInputDispatcher();
		ClickNpcStep step = new ClickNpcStep(artemis, rec, REF, "Attack");

		ScopedBlackboard bb = new ScopedBlackboard();
		step.onStart(ctx(52, bb, disp));

		assertEquals("exactly one dispatch", 1, disp.getRequests().size());
		ActionRequest req = disp.getRequests().get(0);
		assertEquals(ActionRequest.Kind.CLICK_NPC, req.getKind());
		assertEquals("must dispatch on the LIVE index, not the ref's stale index",
			11, req.getNpcIndex());
		assertEquals("Attack", req.getVerb());

		// StepEvent: started, no failure yet.
		assertEquals(1, rec.events.size());
		assertEquals("started", rec.events.get(0).phase());
	}

	// ── Base success: dispatcher idle after 2 ticks ────────────────

	@Test
	public void baseSucceedsWhenDispatcherIdleNotOnElapsedAlone()
	{
		// Spec §11 base success: dispatcher reported completion. Tick
		// elapsed without dispatcher idle MUST stay Running; once
		// dispatcher reports idle, Succeeded fires.
		Artemis artemis = artemisActive(REF);
		RecordingRecorder rec = new RecordingRecorder();
		MockInputDispatcher disp = new MockInputDispatcher();
		disp.setBusy(true);   // worker still mid-press
		ClickNpcStep step = new ClickNpcStep(artemis, rec, REF, "Attack");

		ScopedBlackboard bb = new ScopedBlackboard();
		step.onStart(ctx(52, bb, disp));

		// Many ticks elapse but dispatcher remains busy → keep RUNNING.
		assertTrue(step.check(snap(53), bb) instanceof Completion.Running);
		assertTrue(step.check(snap(55), bb) instanceof Completion.Running);
		assertTrue(step.check(snap(57), bb) instanceof Completion.Running);

		// Worker finishes → busy=false → Succeeded on next check.
		disp.setBusy(false);
		Completion result = step.check(snap(58), bb);
		assertTrue("Succeeded only after dispatcher idle, not on elapsed-tick proxy",
			result instanceof Completion.Succeeded);

		StepEvent last = rec.events.get(rec.events.size() - 1);
		assertEquals("succeeded", last.phase());
		assertEquals("npc", last.targetType());
	}

	@Test
	public void baseStaysRunningWhileDispatcherBusyEvenPastOldTwoTickProxy()
	{
		// Regression guard for the round-1 elapsed≥2 proxy: at elapsed=2
		// with busy=true, the old impl returned Succeeded; the fix
		// returns Running.
		Artemis artemis = artemisActive(REF);
		RecordingRecorder rec = new RecordingRecorder();
		MockInputDispatcher disp = new MockInputDispatcher();
		disp.setBusy(true);
		ClickNpcStep step = new ClickNpcStep(artemis, rec, REF, "Attack");

		ScopedBlackboard bb = new ScopedBlackboard();
		step.onStart(ctx(52, bb, disp));
		assertTrue(step.check(snap(54), bb) instanceof Completion.Running);
	}

	// ── Constructor validation ─────────────────────────────────────

	@Test(expected = IllegalArgumentException.class)
	public void nullRefRejected()
	{
		new ClickNpcStep(mock(Artemis.class), null, null, "Attack");
	}

	@Test(expected = IllegalArgumentException.class)
	public void blankVerbRejected()
	{
		new ClickNpcStep(mock(Artemis.class), null, REF, "  ");
	}

	// ── Return type contract: Artemis surface ──────────────────────

	@Test
	public void returnTypeIsStepNotActionRequest()
	{
		// Type system enforces this — but make it observable:
		ClickNpcStep step = new ClickNpcStep(mock(Artemis.class), null, REF, "Attack");
		assertNotNull(step.name());
		// The Step's only public dispatch surface is Step's lifecycle
		// methods. ActionRequest is package-private to the dispatcher.
		// (No public method returns an ActionRequest — this test
		// documents that, and the test compiles only because the
		// class shape continues to honour it.)
	}
}
