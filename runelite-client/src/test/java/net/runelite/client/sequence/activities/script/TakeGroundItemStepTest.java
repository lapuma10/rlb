package net.runelite.client.sequence.activities.script;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.analyse.StepEvent;
import net.runelite.client.plugins.recorder.session.SessionShape;
import net.runelite.client.sequence.Completion;
import net.runelite.client.sequence.Failure;
import net.runelite.client.sequence.Recovery;
import net.runelite.client.sequence.StepContext;
import net.runelite.client.sequence.WorldSnapshot;
import net.runelite.client.sequence.artemis.Artemis;
import net.runelite.client.sequence.artemis.query.ItemQuery;
import net.runelite.client.sequence.artemis.view.GroundItemRef;
import net.runelite.client.sequence.artemis.view.InvSlot;
import net.runelite.client.sequence.artemis.view.InventoryView;
import net.runelite.client.sequence.blackboard.ScopedBlackboard;
import net.runelite.client.sequence.dispatch.MockInputDispatcher;
import net.runelite.client.sequence.internal.ActionRequest;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TakeGroundItemStepTest
{
	private static final WorldPoint LOC = new WorldPoint(3201, 3220, 0);
	private static final int ITEM_ID = 526;   // Bones
	private static final GroundItemRef REF =
		new GroundItemRef(ITEM_ID, "Bones", 1, LOC, 50L);

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

	private static InventoryView emptyInv()
	{
		List<InvSlot> slots = new ArrayList<>(28);
		for (int i = 0; i < 28; i++)
		{
			slots.add(new InvSlot(i, InvSlot.EMPTY_ITEM_ID, 0, null));
		}
		return new InventoryView(slots);
	}

	private static InventoryView fullInv()
	{
		List<InvSlot> slots = new ArrayList<>(28);
		for (int i = 0; i < 28; i++)
		{
			slots.add(new InvSlot(i, 1, 1, "filler"));
		}
		return new InventoryView(slots);
	}

	private static InventoryView withCount(int itemId, int count)
	{
		List<InvSlot> slots = new ArrayList<>(28);
		for (int i = 0; i < 28; i++)
		{
			if (i == 0)
			{
				slots.add(new InvSlot(0, itemId, count, "x"));
			}
			else
			{
				slots.add(new InvSlot(i, InvSlot.EMPTY_ITEM_ID, 0, null));
			}
		}
		return new InventoryView(slots);
	}

	private Artemis artemis(GroundItemRef live, InventoryView inv)
	{
		Artemis a = mock(Artemis.class);
		when(a.session()).thenReturn(new SessionShape(() -> 0L, () -> 0L, Long.MAX_VALUE));
		when(a.inventory()).thenReturn(inv);
		when(a.player()).thenReturn(null);
		when(a.findItem(any(ItemQuery.class)))
			.thenReturn(live == null ? Optional.empty() : Optional.of(live));
		return a;
	}

	@Test
	public void happyPathDispatchesClickGroundItem()
	{
		Artemis artemis = artemis(REF, emptyInv());
		RecordingRecorder rec = new RecordingRecorder();
		MockInputDispatcher disp = new MockInputDispatcher();
		TakeGroundItemStep step = new TakeGroundItemStep(artemis, rec, REF);

		step.onStart(ctx(52, new ScopedBlackboard(), disp));

		assertEquals(1, disp.getRequests().size());
		ActionRequest req = disp.getRequests().get(0);
		assertEquals(ActionRequest.Kind.CLICK_GROUND_ITEM, req.getKind());
		assertEquals(ITEM_ID, req.getItemId());
		assertEquals(LOC, req.getTile());
		assertEquals("Take", req.getVerb());
	}

	@Test
	public void inventoryFullAbortsAndDoesNotRetry()
	{
		Artemis artemis = artemis(REF, fullInv());
		RecordingRecorder rec = new RecordingRecorder();
		MockInputDispatcher disp = new MockInputDispatcher();
		TakeGroundItemStep step = new TakeGroundItemStep(artemis, rec, REF);

		ScopedBlackboard bb = new ScopedBlackboard();
		step.onStart(ctx(52, bb, disp));

		assertTrue(disp.getRequests().isEmpty());
		assertEquals("INVENTORY_FULL", rec.events.get(1).diagnosticReason());

		// onFailure: must Abort (not Retry).
		Failure f = new Failure("INVENTORY_FULL", 0, null);
		Recovery rec1 = step.onFailure(f, snap(53), bb);
		assertTrue(rec1 instanceof Recovery.Abort);
	}

	@Test
	public void inventoryGainTriggersSucceeded()
	{
		// Baseline: 0 of ITEM_ID. After dispatch: 1 of ITEM_ID. Step must
		// observe inventory().count(ITEM_ID) > baseline.
		final InventoryView before = emptyInv();
		final InventoryView after = withCount(ITEM_ID, 1);
		Artemis a = mock(Artemis.class);
		when(a.session()).thenReturn(new SessionShape(() -> 0L, () -> 0L, Long.MAX_VALUE));
		when(a.findItem(any(ItemQuery.class))).thenReturn(Optional.of(REF));
		when(a.player()).thenReturn(null);
		// Sequence: before (in onStart) → after (in check). The Step
		// reads inventory() once per lifecycle method, so 2 returns
		// suffice.
		when(a.inventory()).thenReturn(before, after);

		RecordingRecorder rec = new RecordingRecorder();
		MockInputDispatcher disp = new MockInputDispatcher();
		TakeGroundItemStep step = new TakeGroundItemStep(a, rec, REF);
		ScopedBlackboard bb = new ScopedBlackboard();
		step.onStart(ctx(52, bb, disp));

		Completion result = step.check(snap(53), bb);
		assertTrue(result instanceof Completion.Succeeded);
		StepEvent last = rec.events.get(rec.events.size() - 1);
		assertEquals("succeeded", last.phase());
		assertEquals("Take", last.verb());
	}

	@Test
	public void noItemFoundFailsAsTargetNotFound()
	{
		Artemis artemis = artemis(null, emptyInv());
		RecordingRecorder rec = new RecordingRecorder();
		MockInputDispatcher disp = new MockInputDispatcher();
		TakeGroundItemStep step = new TakeGroundItemStep(artemis, rec, REF);

		step.onStart(ctx(52, new ScopedBlackboard(), disp));

		assertTrue(disp.getRequests().isEmpty());
		assertEquals("TARGET_NOT_FOUND", rec.events.get(1).diagnosticReason());
	}

	@Test
	public void targetTypeIsItem()
	{
		// Sanity check that StepEvent.targetType is the typed string,
		// not stuffed into detail.
		Artemis artemis = artemis(REF, emptyInv());
		RecordingRecorder rec = new RecordingRecorder();
		MockInputDispatcher disp = new MockInputDispatcher();
		TakeGroundItemStep step = new TakeGroundItemStep(artemis, rec, REF);

		step.onStart(ctx(52, new ScopedBlackboard(), disp));

		StepEvent started = rec.events.get(0);
		assertEquals("item", started.targetType());
		assertEquals("Bones", started.targetName());
		assertEquals("Take", started.verb());
	}
}
