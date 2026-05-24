package net.runelite.client.sequence.activities.script;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.runelite.client.plugins.recorder.analyse.StepEvent;
import net.runelite.client.plugins.recorder.session.SessionShape;
import net.runelite.client.sequence.Completion;
import net.runelite.client.sequence.StepContext;
import net.runelite.client.sequence.WorldSnapshot;
import net.runelite.client.sequence.artemis.Artemis;
import net.runelite.client.sequence.artemis.view.InvSlot;
import net.runelite.client.sequence.artemis.view.InventoryView;
import net.runelite.client.sequence.blackboard.ScopedBlackboard;
import net.runelite.client.sequence.dispatch.MockInputDispatcher;
import net.runelite.client.sequence.internal.ActionRequest;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ClickInvSlotStep} — the inventory-slot click Step
 * that backs the source half of {@code Artemis.useOn(...)} and is
 * also reusable for any direct inventory verb dispatch.
 */
public class ClickInvSlotStepTest
{
	private static final InvSlot TINDER = new InvSlot(0, 590, 1, "Tinderbox");

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

	private static InventoryView invWith(InvSlot... presentSlots)
	{
		List<InvSlot> slots = new ArrayList<>(28);
		for (int i = 0; i < 28; i++)
		{
			InvSlot found = null;
			for (InvSlot p : presentSlots)
			{
				if (p.slotIdx() == i) { found = p; break; }
			}
			slots.add(found != null ? found : new InvSlot(i, InvSlot.EMPTY_ITEM_ID, 0, null));
		}
		return new InventoryView(slots);
	}

	private Artemis artemis(InventoryView inv)
	{
		Artemis a = mock(Artemis.class);
		when(a.session()).thenReturn(new SessionShape(() -> 0L, () -> 0L, Long.MAX_VALUE));
		when(a.inventory()).thenReturn(inv);
		when(a.player()).thenReturn(null);
		return a;
	}

	// ── Happy path ─────────────────────────────────────────────────

	@Test
	public void dispatchesClickInvItemWithSlotItemIdAndVerb()
	{
		Artemis artemis = artemis(invWith(TINDER));
		RecordingRecorder rec = new RecordingRecorder();
		MockInputDispatcher disp = new MockInputDispatcher();
		ClickInvSlotStep step = new ClickInvSlotStep(artemis, rec, TINDER, "Use");

		step.onStart(ctx(50, new ScopedBlackboard(), disp));

		assertEquals(1, disp.getRequests().size());
		ActionRequest req = disp.getRequests().get(0);
		assertEquals(ActionRequest.Kind.CLICK_INV_ITEM, req.getKind());
		assertEquals(0, req.getSlot());
		assertEquals(590, req.getItemId());
		assertEquals("Use", req.getVerb());
	}

	// ── Re-resolution failures ─────────────────────────────────────

	@Test
	public void emptySlotFailsBeforeDispatch()
	{
		Artemis artemis = artemis(invWith());   // slot 0 is empty
		RecordingRecorder rec = new RecordingRecorder();
		MockInputDispatcher disp = new MockInputDispatcher();
		ClickInvSlotStep step = new ClickInvSlotStep(artemis, rec, TINDER, "Use");

		step.onStart(ctx(50, new ScopedBlackboard(), disp));

		assertTrue(disp.getRequests().isEmpty());
		assertEquals("STALE_REF", rec.events.get(1).diagnosticReason());
	}

	@Test
	public void differentItemIdInSlotFailsAsStale()
	{
		InvSlot other = new InvSlot(0, 9999, 1, "Other");
		Artemis artemis = artemis(invWith(other));
		RecordingRecorder rec = new RecordingRecorder();
		MockInputDispatcher disp = new MockInputDispatcher();
		ClickInvSlotStep step = new ClickInvSlotStep(artemis, rec, TINDER, "Use");

		step.onStart(ctx(50, new ScopedBlackboard(), disp));

		assertTrue(disp.getRequests().isEmpty());
		assertEquals("STALE_REF", rec.events.get(1).diagnosticReason());
	}

	// ── Completion via dispatcher idle ─────────────────────────────

	@Test
	public void succeedsOnceDispatcherIdle()
	{
		Artemis artemis = artemis(invWith(TINDER));
		RecordingRecorder rec = new RecordingRecorder();
		MockInputDispatcher disp = new MockInputDispatcher();
		disp.setBusy(true);
		ClickInvSlotStep step = new ClickInvSlotStep(artemis, rec, TINDER, "Use");

		ScopedBlackboard bb = new ScopedBlackboard();
		step.onStart(ctx(50, bb, disp));

		assertTrue(step.check(snap(51), bb) instanceof Completion.Running);
		disp.setBusy(false);
		assertTrue(step.check(snap(52), bb) instanceof Completion.Succeeded);
	}

	// ── StepEvent shape ────────────────────────────────────────────

	@Test
	public void stepEventCarriesInvTargetType()
	{
		Artemis artemis = artemis(invWith(TINDER));
		RecordingRecorder rec = new RecordingRecorder();
		MockInputDispatcher disp = new MockInputDispatcher();
		ClickInvSlotStep step = new ClickInvSlotStep(artemis, rec, TINDER, "Use");

		step.onStart(ctx(50, new ScopedBlackboard(), disp));

		StepEvent started = rec.events.get(0);
		assertEquals("inv", started.targetType());
		assertEquals("inv:590/slot=0", started.targetId());
		assertEquals("Tinderbox", started.targetName());
		assertEquals("Use", started.verb());
	}

	// ── Constructor validation ─────────────────────────────────────

	@Test(expected = IllegalArgumentException.class)
	public void nullRefRejected()
	{
		new ClickInvSlotStep(mock(Artemis.class), null, null, "Use");
	}

	@Test(expected = IllegalArgumentException.class)
	public void blankVerbRejected()
	{
		new ClickInvSlotStep(mock(Artemis.class), null, TINDER, "  ");
	}
}
