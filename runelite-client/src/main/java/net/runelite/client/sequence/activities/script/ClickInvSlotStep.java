package net.runelite.client.sequence.activities.script;

import java.util.function.Consumer;
import net.runelite.client.plugins.recorder.analyse.StepEvent;
import net.runelite.client.sequence.Completion;
import net.runelite.client.sequence.StepContext;
import net.runelite.client.sequence.WorldSnapshot;
import net.runelite.client.sequence.affordance.DiagnosticReason;
import net.runelite.client.sequence.artemis.Artemis;
import net.runelite.client.sequence.artemis.view.InvSlot;
import net.runelite.client.sequence.artemis.view.InventoryView;
import net.runelite.client.sequence.blackboard.Blackboard;
import net.runelite.client.sequence.internal.ActionRequest;

/**
 * Step that dispatches a {@link ActionRequest.Kind#CLICK_INV_ITEM} on
 * one inventory slot with a specified verb (commonly {@code "Use"} for
 * the source half of a {@code useOn} flow, but also reusable for any
 * direct inventory verb click).
 *
 * <p>Re-resolves the {@link InvSlot} ref against live
 * {@link Artemis#inventory()} before dispatch — the slot must still
 * hold the same {@code itemId} (caller-side state can change between
 * find and click). Fails loud with {@code STALE_REF} if not.
 *
 * <p>Per-Step tick timeout is 6 (mirrors the widget click timeout).
 * Success criterion: dispatcher reports completion via
 * {@link ArtemisActionStep#dispatcherIdle()} (spec §11 base contract).
 */
public final class ClickInvSlotStep extends ArtemisActionStep
{
	private static final int TIMEOUT_TICKS = 6;

	private final InvSlot ref;
	private final String verb;

	public ClickInvSlotStep(Artemis artemis, Consumer<StepEvent> stepEventSink,
		InvSlot ref, String verb)
	{
		super(artemis, stepEventSink, false);
		if (ref == null)
		{
			throw new IllegalArgumentException("InvSlot must not be null");
		}
		if (verb == null || verb.isBlank())
		{
			throw new IllegalArgumentException("verb must not be null/blank");
		}
		this.ref = ref;
		this.verb = verb;
	}

	@Override
	public String name()
	{
		return "ClickInvSlot(slot=" + ref.slotIdx() + ", " + verb + ")";
	}

	@Override public int timeoutTicks()       { return TIMEOUT_TICKS; }
	@Override protected String targetType()   { return "inv"; }
	@Override protected String targetId()     { return "inv:" + ref.itemId() + "/slot=" + ref.slotIdx(); }
	@Override protected String targetName()   { return ref.name(); }
	@Override protected String verb()         { return verb; }

	@Override
	protected void doStart(StepContext ctx)
	{
		// Live inventory check — slot must still hold the expected
		// itemId (inventory state can drift between find and use:
		// drops, deposits, drag-rearrange).
		InventoryView inv = artemis.inventory();
		if (inv == null || inv.slots().size() <= ref.slotIdx())
		{
			failOnStart(ctx, REASON_TARGET_NOT_FOUND,
				"inv slot " + ref.slotIdx() + " out of range");
			return;
		}
		InvSlot live = inv.slots().get(ref.slotIdx());
		if (live == null || live.isEmpty() || live.itemId() != ref.itemId())
		{
			failOnStart(ctx, REASON_STALE_REF,
				"inv slot " + ref.slotIdx() + " no longer holds itemId=" + ref.itemId());
			return;
		}

		ActionRequest req = ActionRequest.builder()
			.kind(ActionRequest.Kind.CLICK_INV_ITEM)
			.channel(ActionRequest.Channel.MOUSE)
			.slot(ref.slotIdx())
			.itemId(ref.itemId())
			.verb(verb)
			.build();
		ctx.dispatcher().dispatch(req);
	}

	@Override
	protected Completion doCheck(WorldSnapshot s, Blackboard bb, long elapsed)
	{
		if (elapsed >= TIMEOUT_TICKS)
		{
			return Completion.failed(new DiagnosticReason.ActionTimedOut(name(), (int) elapsed));
		}
		if (!dispatcherIdle())
		{
			return Completion.RUNNING;
		}
		return new Completion.Succeeded("inv click dispatched (slot="
			+ ref.slotIdx() + ", verb=" + verb + ")");
	}
}
