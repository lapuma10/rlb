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
import net.runelite.client.sequence.artemis.query.ObjectQuery;
import net.runelite.client.sequence.artemis.view.GameObjRef;
import net.runelite.client.sequence.artemis.view.ObjectKind;
import net.runelite.client.sequence.blackboard.ScopedBlackboard;
import net.runelite.client.sequence.dispatch.MockInputDispatcher;
import net.runelite.client.sequence.internal.ActionRequest;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ClickGameObjStepTest
{
	private static final WorldPoint LOC = new WorldPoint(3208, 3220, 0);
	private static final GameObjRef REF =
		new GameObjRef(25808, "Bank booth", LOC, ObjectKind.GAME_OBJECT, 50L);

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

	private Artemis artemis(GameObjRef live, boolean sessionActive)
	{
		Artemis a = mock(Artemis.class);
		when(a.session()).thenReturn(new SessionShape(
			() -> 0L, () -> 0L, sessionActive ? Long.MAX_VALUE : 0L));
		when(a.player()).thenReturn(null);
		when(a.findObject(any(ObjectQuery.class)))
			.thenReturn(live == null ? Optional.empty() : Optional.of(live));
		return a;
	}

	private Artemis artemis(GameObjRef live) { return artemis(live, true); }

	@Test
	public void happyPathDispatchesClickGameObject()
	{
		Artemis artemis = artemis(REF);
		RecordingRecorder rec = new RecordingRecorder();
		MockInputDispatcher disp = new MockInputDispatcher();
		ClickGameObjStep step = new ClickGameObjStep(artemis, rec, REF, "Bank");

		ScopedBlackboard bb = new ScopedBlackboard();
		step.onStart(ctx(55, bb, disp));   // age 5 ≤ 8

		assertEquals(1, disp.getRequests().size());
		ActionRequest req = disp.getRequests().get(0);
		assertEquals(ActionRequest.Kind.CLICK_GAME_OBJECT, req.getKind());
		assertEquals(25808, req.getObjectId());
		assertEquals(LOC, req.getTile());
		assertEquals("Bank", req.getVerb());
		assertTrue("liveTracked must be true so dispatcher re-aims as camera moves",
			req.isLiveTracked());
	}

	@Test
	public void objectTileMismatchRejectsAsStale()
	{
		// Live object at different tile — game objects are static, so
		// any drift means we found a different instance.
		GameObjRef drifted = new GameObjRef(25808, "Bank booth",
			new WorldPoint(3209, 3220, 0), ObjectKind.GAME_OBJECT, 55L);
		Artemis artemis = artemis(drifted);
		RecordingRecorder rec = new RecordingRecorder();
		MockInputDispatcher disp = new MockInputDispatcher();
		ClickGameObjStep step = new ClickGameObjStep(artemis, rec, REF, "Bank");

		step.onStart(ctx(55, new ScopedBlackboard(), disp));

		assertTrue(disp.getRequests().isEmpty());
		assertEquals("STALE_REF", rec.events.get(1).diagnosticReason());
	}

	@Test
	public void sessionExhaustedBlocksGameplayObject()
	{
		Artemis a = artemis(REF, /* sessionActive */ false);
		RecordingRecorder rec = new RecordingRecorder();
		MockInputDispatcher disp = new MockInputDispatcher();

		ClickGameObjStep step = new ClickGameObjStep(a, rec, REF, "Bank");
		step.onStart(ctx(55, new ScopedBlackboard(), disp));

		assertTrue(disp.getRequests().isEmpty());
		assertEquals("SESSION_EXHAUSTED", rec.events.get(1).diagnosticReason());
	}

	@Test
	public void baseStaysRunningWhileDispatcherBusy()
	{
		// Mirror of ClickNpcStepTest.baseStaysRunningWhileDispatcherBusyEvenPastOldTwoTickProxy
		// — regression guard against the round-1 elapsed≥2 proxy.
		// Spec §11 base success requires dispatcher reported completion;
		// elapsed ticks alone must not flip Succeeded.
		Artemis artemis = artemis(REF);
		RecordingRecorder rec = new RecordingRecorder();
		MockInputDispatcher disp = new MockInputDispatcher();
		disp.setBusy(true);   // worker still mid-press
		ClickGameObjStep step = new ClickGameObjStep(artemis, rec, REF, "Bank");

		ScopedBlackboard bb = new ScopedBlackboard();
		step.onStart(ctx(55, bb, disp));

		// Many ticks elapse but dispatcher remains busy → keep RUNNING.
		assertTrue(step.check(snap(56), bb) instanceof Completion.Running);
		assertTrue(step.check(snap(58), bb) instanceof Completion.Running);
		assertTrue(step.check(snap(60), bb) instanceof Completion.Running);

		// Worker finishes → busy=false → Succeeded on next check.
		disp.setBusy(false);
		Completion result = step.check(snap(61), bb);
		assertTrue("ClickGameObjStep must succeed only after dispatcher idle, not elapsed proxy",
			result instanceof Completion.Succeeded);
	}
}
