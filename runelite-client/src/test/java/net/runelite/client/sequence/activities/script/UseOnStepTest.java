package net.runelite.client.sequence.activities.script;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.analyse.StepEvent;
import net.runelite.client.sequence.Step;
import net.runelite.client.sequence.artemis.Artemis;
import net.runelite.client.sequence.artemis.view.GameObjRef;
import net.runelite.client.sequence.artemis.view.InvSlot;
import net.runelite.client.sequence.artemis.view.NpcRef;
import net.runelite.client.sequence.artemis.view.ObjectKind;
import net.runelite.client.sequence.artemis.view.WidgetRef;
import net.runelite.client.sequence.composite.LinearSequence;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link UseOnStep} as a factory that composes the two-click
 * useOn flow via {@link LinearSequence}.
 *
 * <p>The full source-click + target-click integration semantics live
 * in {@link ClickInvSlotStepTest} and the target-type Step tests;
 * here we verify only the composition shape — the returned Step is a
 * LinearSequence of (source ClickInvSlotStep, target Click* Step),
 * each constructed with verb="Use".
 */
public class UseOnStepTest
{
	private static final WorldPoint LOC = new WorldPoint(3208, 3220, 0);
	private static final InvSlot TINDER = new InvSlot(0, 590, 1, "Tinderbox");
	private static final InvSlot LOGS   = new InvSlot(1, 1511, 1, "Logs");
	private static final NpcRef NPC =
		new NpcRef(7, 1234, "Cow", LOC, 100, 50L);
	private static final GameObjRef OBJ =
		new GameObjRef(25808, "Bank booth", LOC, ObjectKind.GAME_OBJECT, 50L);
	private static final WidgetRef WIDGET =
		new WidgetRef(786456, null, -1, 50L);

	private static final Consumer<StepEvent> SINK = (ev) -> {};

	// ── Composition shape ──────────────────────────────────────────

	@Test
	public void onInvSlotReturnsLinearSequenceWithTwoInvClicks()
	{
		Step composed = UseOnStep.onInvSlot(mock(Artemis.class), SINK, TINDER, LOGS);

		assertTrue("UseOnStep.onInvSlot must return a LinearSequence",
			composed instanceof LinearSequence);
		LinearSequence seq = (LinearSequence) composed;
		List<Step> children = seq.getChildren();
		assertEquals("UseOn sequence has source-click + target-click", 2, children.size());
		assertTrue("source is a ClickInvSlotStep",
			children.get(0) instanceof ClickInvSlotStep);
		assertTrue("target is a ClickInvSlotStep",
			children.get(1) instanceof ClickInvSlotStep);
	}

	@Test
	public void onGameObjReturnsSourceThenTargetSequence()
	{
		Step composed = UseOnStep.onGameObj(mock(Artemis.class), SINK, TINDER, OBJ);

		assertTrue(composed instanceof LinearSequence);
		List<Step> children = ((LinearSequence) composed).getChildren();
		assertEquals(2, children.size());
		assertTrue(children.get(0) instanceof ClickInvSlotStep);
		assertTrue(children.get(1) instanceof ClickGameObjStep);
	}

	@Test
	public void onNpcReturnsSourceThenTargetSequence()
	{
		Step composed = UseOnStep.onNpc(mock(Artemis.class), SINK, TINDER, NPC);

		assertTrue(composed instanceof LinearSequence);
		List<Step> children = ((LinearSequence) composed).getChildren();
		assertEquals(2, children.size());
		assertTrue(children.get(0) instanceof ClickInvSlotStep);
		assertTrue(children.get(1) instanceof ClickNpcStep);
	}

	@Test
	public void onWidgetReturnsSourceThenTargetSequence()
	{
		Step composed = UseOnStep.onWidget(mock(Artemis.class), SINK, TINDER, WIDGET);

		assertTrue(composed instanceof LinearSequence);
		List<Step> children = ((LinearSequence) composed).getChildren();
		assertEquals(2, children.size());
		assertTrue(children.get(0) instanceof ClickInvSlotStep);
		assertTrue(children.get(1) instanceof ClickWidgetStep);
	}

	@Test
	public void sourceClickStepUsesVerbUse()
	{
		Step composed = UseOnStep.onNpc(mock(Artemis.class), SINK, TINDER, NPC);
		ClickInvSlotStep source = (ClickInvSlotStep)
			((LinearSequence) composed).getChildren().get(0);
		// The Step.name() embeds the verb — cheap public-visible
		// witness without exposing a new accessor just for tests.
		assertTrue("source ClickInvSlotStep name should embed verb=Use, got: " + source.name(),
			source.name().contains("Use"));
	}

	@Test
	public void targetClickStepUsesVerbUse()
	{
		Step composed = UseOnStep.onGameObj(mock(Artemis.class), SINK, TINDER, OBJ);
		ClickGameObjStep target = (ClickGameObjStep)
			((LinearSequence) composed).getChildren().get(1);
		assertTrue("target ClickGameObjStep name should embed verb=Use, got: " + target.name(),
			target.name().contains("Use"));
	}

	@Test
	public void sequenceNameDescribesSourceAndTarget()
	{
		LinearSequence seq = (LinearSequence) UseOnStep.onNpc(
			mock(Artemis.class), SINK, TINDER, NPC);
		assertNotNull(seq.name());
		assertTrue("name should mention source slot, got: " + seq.name(),
			seq.name().contains("slot=" + TINDER.slotIdx()));
		assertTrue("name should mention target npc id, got: " + seq.name(),
			seq.name().contains("npc=" + NPC.id()));
	}

	// ── Constructor validation ─────────────────────────────────────

	@Test(expected = IllegalArgumentException.class)
	public void nullSourceRejectedForInvSlotVariant()
	{
		UseOnStep.onInvSlot(mock(Artemis.class), SINK, null, LOGS);
	}

	@Test(expected = IllegalArgumentException.class)
	public void nullTargetRejectedForInvSlotVariant()
	{
		UseOnStep.onInvSlot(mock(Artemis.class), SINK, TINDER, null);
	}

	@Test(expected = IllegalArgumentException.class)
	public void nullTargetRejectedForGameObjVariant()
	{
		UseOnStep.onGameObj(mock(Artemis.class), SINK, TINDER, null);
	}

	@Test(expected = IllegalArgumentException.class)
	public void nullTargetRejectedForNpcVariant()
	{
		UseOnStep.onNpc(mock(Artemis.class), SINK, TINDER, null);
	}

	@Test(expected = IllegalArgumentException.class)
	public void nullTargetRejectedForWidgetVariant()
	{
		UseOnStep.onWidget(mock(Artemis.class), SINK, TINDER, null);
	}

	// Used implicit constructor avoids unused-field warnings while
	// keeping these constants visible to future tests.
	@SuppressWarnings("unused")
	private static void pinConstants()
	{
		List<Object> _used = new ArrayList<>(List.of(LOC, TINDER, LOGS, NPC, OBJ, WIDGET));
	}
}
