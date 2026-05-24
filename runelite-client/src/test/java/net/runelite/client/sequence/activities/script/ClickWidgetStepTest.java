package net.runelite.client.sequence.activities.script;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import net.runelite.client.plugins.recorder.analyse.StepEvent;
import net.runelite.client.plugins.recorder.session.SessionShape;
import net.runelite.client.sequence.StepContext;
import net.runelite.client.sequence.artemis.Artemis;
import net.runelite.client.sequence.artemis.query.WidgetQuery;
import net.runelite.client.sequence.artemis.view.WidgetRef;
import net.runelite.client.sequence.blackboard.ScopedBlackboard;
import net.runelite.client.sequence.dispatch.MockInputDispatcher;
import net.runelite.client.sequence.internal.ActionRequest;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ClickWidgetStepTest
{
	private static final int WIDGET_ID = 786456;
	private static final WidgetRef REF = new WidgetRef(WIDGET_ID, null, -1, 50L);
	private static final WidgetRef CHILD_REF = new WidgetRef(WIDGET_ID, 12, 995, 50L);

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

	private Artemis artemis(WidgetRef live)
	{
		Artemis a = mock(Artemis.class);
		when(a.session()).thenReturn(new SessionShape(() -> 0L, () -> 0L, Long.MAX_VALUE));
		when(a.player()).thenReturn(null);
		when(a.findWidget(any(WidgetQuery.class)))
			.thenReturn(live == null ? Optional.empty() : Optional.of(live));
		return a;
	}

	@Test
	public void leafWidgetClickDispatchesCorrectActionRequest()
	{
		Artemis artemis = artemis(REF);
		RecordingRecorder rec = new RecordingRecorder();
		MockInputDispatcher disp = new MockInputDispatcher();
		ClickWidgetStep step = new ClickWidgetStep(artemis, rec, REF, "Bank");

		step.onStart(ctx(52, new ScopedBlackboard(), disp));

		assertEquals(1, disp.getRequests().size());
		ActionRequest req = disp.getRequests().get(0);
		assertEquals(ActionRequest.Kind.CLICK_WIDGET, req.getKind());
		assertEquals(WIDGET_ID, req.getWidgetId());
		assertEquals("Bank", req.getVerb());
	}

	@Test
	public void childSlotWidgetClickFailsLoudInV1_0()
	{
		// Round-1 1A.3 bug: childIndex was set on a CLICK_WIDGET
		// ActionRequest, but the dispatcher's CLICK_WIDGET handler
		// only reads widgetId+verb → silent parent click. Fix: fail
		// loud with CHILD_SLOT_NOT_SUPPORTED_V1_0 until CLICK_BOUNDS
		// path lands in a v1.x follow-up.
		Artemis artemis = artemis(CHILD_REF);
		RecordingRecorder rec = new RecordingRecorder();
		MockInputDispatcher disp = new MockInputDispatcher();
		ClickWidgetStep step = new ClickWidgetStep(artemis, rec, CHILD_REF, "Withdraw-1");

		step.onStart(ctx(52, new ScopedBlackboard(), disp));

		assertTrue("must not dispatch a CLICK_WIDGET that would silently click the parent",
			disp.getRequests().isEmpty());
		StepEvent failed = rec.events.get(1);
		assertEquals("failed", failed.phase());
		assertEquals("CHILD_SLOT_NOT_SUPPORTED_V1_0", failed.diagnosticReason());
		assertEquals("widget", failed.targetType());
		assertEquals("widget:" + WIDGET_ID + "/12", failed.targetId());
	}

	@Test
	public void widgetHiddenOrMissingFailsBeforeDispatch()
	{
		Artemis artemis = artemis(null);   // findWidget returns empty
		RecordingRecorder rec = new RecordingRecorder();
		MockInputDispatcher disp = new MockInputDispatcher();
		ClickWidgetStep step = new ClickWidgetStep(artemis, rec, REF, "Bank");

		step.onStart(ctx(52, new ScopedBlackboard(), disp));

		assertTrue("must not dispatch when widget hidden/missing — CLAUDE.md §1",
			disp.getRequests().isEmpty());
		assertEquals("TARGET_NOT_FOUND", rec.events.get(1).diagnosticReason());
		assertEquals("widget", rec.events.get(1).targetType());
		assertEquals("widget:" + WIDGET_ID, rec.events.get(1).targetId());
	}

	@Test
	public void emptyVerbOmitsVerbAndUsesLeftClickDefault()
	{
		Artemis artemis = artemis(REF);
		RecordingRecorder rec = new RecordingRecorder();
		MockInputDispatcher disp = new MockInputDispatcher();
		ClickWidgetStep step = new ClickWidgetStep(artemis, rec, REF, "");

		step.onStart(ctx(52, new ScopedBlackboard(), disp));

		ActionRequest req = disp.getRequests().get(0);
		assertEquals("empty verb should be omitted so dispatcher uses widgetClick (left-click default)",
			null, req.getVerb());
	}
}
