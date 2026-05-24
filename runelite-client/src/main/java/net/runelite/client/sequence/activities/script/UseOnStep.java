package net.runelite.client.sequence.activities.script;

import java.util.List;
import java.util.function.Consumer;
import net.runelite.client.plugins.recorder.analyse.StepEvent;
import net.runelite.client.sequence.Step;
import net.runelite.client.sequence.artemis.Artemis;
import net.runelite.client.sequence.artemis.view.GameObjRef;
import net.runelite.client.sequence.artemis.view.InvSlot;
import net.runelite.client.sequence.artemis.view.NpcRef;
import net.runelite.client.sequence.artemis.view.WidgetRef;
import net.runelite.client.sequence.composite.LinearSequence;

/**
 * Static factory for {@code Artemis.useOn(InvSlot, ...)} flows. Returns
 * a {@link LinearSequence} of two single-dispatch Steps — the engine
 * sequences them via spec §12.5 (next Step starts after the previous
 * returns {@code Succeeded}).
 *
 * <p>Each Step dispatches in its own {@code onStart}; neither dispatches
 * from {@code check()}. The earlier 1A.3 round-1 shape (one Step
 * holding a pinned {@code InputDispatcher} and firing the second click
 * from {@code check()}) violated the engine contract that {@code onStart}
 * is the dispatch site AND raced the dispatcher busy guard (silently
 * dropping the second click). This factory shape removes both problems.
 *
 * <p>Sequence per variant:
 * <ul>
 *   <li>{@link #onInvSlot} — {@link ClickInvSlotStep}({@code source}, "Use")
 *       → {@link ClickInvSlotStep}({@code target}, "Use")</li>
 *   <li>{@link #onGameObj} — {@link ClickInvSlotStep}({@code source}, "Use")
 *       → {@link ClickGameObjStep}({@code target}, "Use")</li>
 *   <li>{@link #onNpc} — {@link ClickInvSlotStep}({@code source}, "Use")
 *       → {@link ClickNpcStep}({@code target}, "Use")</li>
 *   <li>{@link #onWidget} — {@link ClickInvSlotStep}({@code source}, "Use")
 *       → {@link ClickWidgetStep}({@code target}, "Use")</li>
 * </ul>
 *
 * <p>Each constituent Step independently honors session-gate,
 * re-resolution, structured StepEvent emission, and
 * {@code dispatcherIdle()} completion gating — no shared state between
 * them beyond the engine sequencing.
 */
public final class UseOnStep
{
	private UseOnStep() {}

	public static Step onInvSlot(Artemis artemis, Consumer<StepEvent> stepEventSink,
		InvSlot source, InvSlot target)
	{
		requireSource(source);
		if (target == null)
		{
			throw new IllegalArgumentException("target InvSlot must not be null");
		}
		return new LinearSequence(
			"UseOn(slot=" + source.slotIdx() + " → slot=" + target.slotIdx() + ")",
			List.of(
				new ClickInvSlotStep(artemis, stepEventSink, source, "Use"),
				new ClickInvSlotStep(artemis, stepEventSink, target, "Use")));
	}

	public static Step onGameObj(Artemis artemis, Consumer<StepEvent> stepEventSink,
		InvSlot source, GameObjRef target)
	{
		requireSource(source);
		if (target == null)
		{
			throw new IllegalArgumentException("target GameObjRef must not be null");
		}
		return new LinearSequence(
			"UseOn(slot=" + source.slotIdx() + " → obj=" + target.id() + ")",
			List.of(
				new ClickInvSlotStep(artemis, stepEventSink, source, "Use"),
				new ClickGameObjStep(artemis, stepEventSink, target, "Use")));
	}

	public static Step onNpc(Artemis artemis, Consumer<StepEvent> stepEventSink,
		InvSlot source, NpcRef target)
	{
		requireSource(source);
		if (target == null)
		{
			throw new IllegalArgumentException("target NpcRef must not be null");
		}
		return new LinearSequence(
			"UseOn(slot=" + source.slotIdx() + " → npc=" + target.id() + ")",
			List.of(
				new ClickInvSlotStep(artemis, stepEventSink, source, "Use"),
				new ClickNpcStep(artemis, stepEventSink, target, "Use")));
	}

	public static Step onWidget(Artemis artemis, Consumer<StepEvent> stepEventSink,
		InvSlot source, WidgetRef target)
	{
		requireSource(source);
		if (target == null)
		{
			throw new IllegalArgumentException("target WidgetRef must not be null");
		}
		return new LinearSequence(
			"UseOn(slot=" + source.slotIdx() + " → widget=" + target.widgetId() + ")",
			List.of(
				new ClickInvSlotStep(artemis, stepEventSink, source, "Use"),
				new ClickWidgetStep(artemis, stepEventSink, target, "Use")));
	}

	private static void requireSource(InvSlot source)
	{
		if (source == null)
		{
			throw new IllegalArgumentException("source InvSlot must not be null");
		}
	}
}
