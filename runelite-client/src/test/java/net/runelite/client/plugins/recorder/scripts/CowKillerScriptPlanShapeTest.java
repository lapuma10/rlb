package net.runelite.client.plugins.recorder.scripts;

import java.util.Optional;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.session.SessionShape;
import net.runelite.client.sequence.Step;
import net.runelite.client.sequence.artemis.Artemis;
import net.runelite.client.sequence.artemis.outcome.OutcomeCheck;
import net.runelite.client.sequence.artemis.query.NpcQuery;
import net.runelite.client.sequence.artemis.session.IdlePolicy;
import net.runelite.client.sequence.artemis.view.NpcRef;
import net.runelite.client.sequence.artemis.view.PlayerState;
import net.runelite.client.sequence.artemis.zones.NamedZone;
import net.runelite.client.sequence.composite.DynamicStep;
import net.runelite.client.sequence.composite.FailStep;
import net.runelite.client.sequence.composite.LinearSequence;
import net.runelite.client.sequence.composite.RepeatStep;
import net.runelite.client.sequence.composite.Selector;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the structural contract of {@link CowKillerScript#plan()} per
 * Phase 2C of the cow-pilot plan. Tests the tree shape via existing
 * composite getters + the four-branch DynamicStep factory's
 * fail-closed behavior. No game state, no I/O — pure plan-shape
 * coverage.
 *
 * <p>Branch enumeration (factory order matters — Branch 0 must run first
 * because IdleStep bypasses the session gate):
 * <ol start="0">
 *   <li>{@code !session.shouldContinue()} → {@code FailStep("SESSION_EXHAUSTED")}</li>
 *   <li>{@code player == null || !player.idle()} → idle Step</li>
 *   <li>{@code findNpc.isEmpty()} → idle Step</li>
 *   <li>otherwise → base 2-arg {@code click(NpcRef, "Attack")}</li>
 * </ol>
 */
public class CowKillerScriptPlanShapeTest
{
	private Artemis artemis;
	private Step mockWalkStep;
	private Step mockLogoutStep;
	private Step mockIdleStep;
	private Step mockClickStep;
	private CowKillerScript script;

	@Before
	public void setUp()
	{
		artemis = mock(Artemis.class);
		mockWalkStep   = mock(Step.class, "walk");
		mockLogoutStep = mock(Step.class, "logout");
		mockIdleStep   = mock(Step.class, "idle");
		mockClickStep  = mock(Step.class, "click");

		// plan() builds the outer tree at construction-equivalent time;
		// the supplier inside DynamicStep is evaluated only when tests
		// invoke factory().get() — but Mockito needs the walkTo / logout
		// stubs in place before plan() runs.
		when(artemis.walkTo(NamedZone.LUMBRIDGE_COW_FIELD)).thenReturn(mockWalkStep);
		when(artemis.logout()).thenReturn(mockLogoutStep);

		// Default supplier-branch stubs — individual tests override.
		when(artemis.idle(any(IdlePolicy.class))).thenReturn(mockIdleStep);

		script = new CowKillerScript(artemis);
	}

	// ── Structural shape (Tests 1-4) ────────────────────────────────

	@Test
	public void planRootIsLinearSequenceCowKillerV1()
	{
		Step root = script.plan();
		assertTrue("plan root must be a LinearSequence", root instanceof LinearSequence);
		LinearSequence ls = (LinearSequence) root;
		assertEquals("cow-killer-v1", ls.name());
		assertEquals("LinearSequence must have exactly 3 children "
			+ "(walk + post-walk settle idle + selector). Phase 2C.x "
			+ "inserted artemis.idle(SHORT_IDLE) at children[1] to drain "
			+ "the walk's residual dispatcher chain.",
			3, ls.getChildren().size());
	}

	@Test
	public void child0IsTheWalkToZoneStep()
	{
		LinearSequence root = (LinearSequence) script.plan();
		assertSame("child 0 must be the Step returned by artemis.walkTo(LUMBRIDGE_COW_FIELD)",
			mockWalkStep, root.getChildren().get(0));
	}

	@Test
	public void child1IsPostWalkSettleIdleStep()
	{
		// Phase 2C.x F3 fix: artemis.idle(SHORT_IDLE) between walkTo
		// and the loop Selector drains the walk's residual dispatcher
		// chain so the first attack iteration enqueues against an idle
		// dispatcher (avoids the "dispatcher busy, dropping CLICK_NPC"
		// path that produced the 8-tick ActionTimedOut → Failed →
		// Selector → logout chain in Run 01 attempt 1.A).
		LinearSequence root = (LinearSequence) script.plan();
		Step child1 = root.getChildren().get(1);
		assertSame("child 1 must be the post-walk settle Step "
			+ "(artemis.idle(SHORT_IDLE))",
			mockIdleStep, child1);
	}

	@Test
	public void child2IsSelectorWithRepeatThenLogout()
	{
		// Phase 2C.x: Selector moved from children[1] to children[2]
		// after the post-walk settle idle was inserted at children[1].
		LinearSequence root = (LinearSequence) script.plan();
		Step child2 = root.getChildren().get(2);
		assertTrue("child 2 must be a Selector", child2 instanceof Selector);
		Selector sel = (Selector) child2;
		assertEquals("Selector must have exactly 2 options (repeat + logout)",
			2, sel.children().size());
		assertTrue("Selector option 0 must be a RepeatStep",
			sel.children().get(0) instanceof RepeatStep);
		assertSame("Selector option 1 must be artemis.logout()",
			mockLogoutStep, sel.children().get(1));
	}

	@Test
	public void repeatStepHasTimesZeroAndDynamicStepBody()
	{
		RepeatStep repeat = extractRepeatStep();
		assertEquals("RepeatStep times must be 0 (infinite-until-Failed)",
			0, repeat.times());
		assertTrue("RepeatStep body must be a DynamicStep",
			repeat.body() instanceof DynamicStep);
	}

	// ── Branch 0: session exhausted (Test 5) ────────────────────────

	@Test
	public void supplierBranch0_sessionExhausted_returnsFailStepAndShortCircuits()
	{
		// Real SessionShape with budget=0 → shouldContinue() returns false
		// immediately (tick 0 - sessionStart 0 >= budget 0 → exhausted).
		when(artemis.session()).thenReturn(new SessionShape(() -> 0L, () -> 0L, 0L));

		Step result = invokeBranch();

		// Phase 2C.x regression guard: SESSION_EXHAUSTED MUST return
		// FailStep DIRECTLY — never wrapped in any Selector. The 2C.x
		// inner Selector("attack-or-retry") wraps ONLY Branch 3's click;
		// wrapping Branch 0 would swallow the terminal signal and
		// prevent the outer Selector(RepeatStep, logout) from reaching
		// artemis.logout(). Both assertions below are deliberately
		// stricter than necessary today, to catch a future refactor
		// that wraps Branch 0 in any composite.
		assertTrue("Branch 0 must return a FailStep, was " + result.getClass().getSimpleName(),
			result instanceof FailStep);
		assertTrue("FailStep name (== reason) must contain SESSION_EXHAUSTED, was: "
				+ ((FailStep) result).name(),
			((FailStep) result).name().contains("SESSION_EXHAUSTED"));

		// Branch 0 must short-circuit before any downstream Artemis call.
		verify(artemis, never()).player();
		verify(artemis, never()).findNpc(any(NpcQuery.class));
		verify(artemis, never()).click(any(NpcRef.class), anyString());
		verify(artemis, never()).click(any(NpcRef.class), anyString(), any(OutcomeCheck.class));
		verify(artemis, never()).idle(any(IdlePolicy.class));
	}

	// ── Branch 1: player busy (Test 6) ──────────────────────────────

	@Test
	public void supplierBranch1_playerBusy_returnsIdleStepAndSkipsFindAndClick()
	{
		when(artemis.session()).thenReturn(sessionContinuing());
		when(artemis.player()).thenReturn(playerState(/* idle */ false));

		Step result = invokeBranch();

		assertSame("Branch 1 must return the idle Step (artemis.idle(SHORT_IDLE))",
			mockIdleStep, result);
		verify(artemis, never()).findNpc(any(NpcQuery.class));
		verify(artemis, never()).click(any(NpcRef.class), anyString());
		verify(artemis, never()).click(any(NpcRef.class), anyString(), any(OutcomeCheck.class));
	}

	@Test
	public void supplierBranch1_playerNull_returnsIdleStepAndSkipsFindAndClick()
	{
		// Defensive — Artemis.player() can return null pre-login per
		// Artemis.java:71. The supplier must treat null as "busy".
		when(artemis.session()).thenReturn(sessionContinuing());
		when(artemis.player()).thenReturn(null);

		Step result = invokeBranch();

		assertSame(mockIdleStep, result);
		verify(artemis, never()).findNpc(any(NpcQuery.class));
		verify(artemis, never()).click(any(NpcRef.class), anyString());
	}

	// ── Branch 2: no cow found (Test 7) ─────────────────────────────

	@Test
	public void supplierBranch2_noCow_returnsIdleStepNotFailStepAndSkipsClick()
	{
		when(artemis.session()).thenReturn(sessionContinuing());
		when(artemis.player()).thenReturn(playerState(/* idle */ true));
		when(artemis.findNpc(any(NpcQuery.class))).thenReturn(Optional.empty());

		Step result = invokeBranch();

		assertSame("Branch 2 must return the idle Step (NOT FailStep — no-cow does not terminate)",
			mockIdleStep, result);
		// Critical regression assertion: NOT a FailStep. The whole point
		// of the round-2 review fix was that one empty read should not
		// trigger logout. If a future refactor reintroduces FailStep
		// here, this assertion catches it.
		assertTrue("result must not be a FailStep", !(result instanceof FailStep));
		verify(artemis, never()).click(any(NpcRef.class), anyString());
		verify(artemis, never()).click(any(NpcRef.class), anyString(), any(OutcomeCheck.class));
	}

	// ── Branch 3: engage (Test 8) ───────────────────────────────────

	@Test
	public void supplierBranch3_cowFound_returnsAttackOrRetrySelectorWithClickThenIdle()
	{
		when(artemis.session()).thenReturn(sessionContinuing());
		when(artemis.player()).thenReturn(playerState(/* idle */ true));
		NpcRef cow = new NpcRef(
			/* index */ 42,
			/* id */ 81,                                // arbitrary; not asserted
			/* name */ "Cow",
			/* originalLoc */ new WorldPoint(3258, 3265, 0),
			/* healthRatio */ NpcRef.HEALTH_RATIO_UNKNOWN,
			/* observedTick */ 1L);
		when(artemis.findNpc(any(NpcQuery.class))).thenReturn(Optional.of(cow));
		when(artemis.click(cow, "Attack")).thenReturn(mockClickStep);

		Step result = invokeBranch();

		// Phase 2C.x: Branch 3 now wraps the click in a
		// Selector("attack-or-retry") so hard ClickNpcStep failures
		// (STALE_REF / TARGET_NOT_FOUND / ActionTimedOut) fall through
		// to a short idle and let the next iteration retry, instead of
		// propagating up through DynamicStep → RepeatStep → outer
		// Selector → artemis.logout(). The result must be the Selector,
		// not the click Step directly.
		assertTrue("Branch 3 must return a Selector (the attack-or-retry "
				+ "wrapper), was " + result.getClass().getSimpleName(),
			result instanceof Selector);
		Selector attackOrRetry = (Selector) result;
		assertEquals("Selector name must be \"attack-or-retry\"",
			"attack-or-retry", attackOrRetry.name());
		assertEquals("attack-or-retry must have exactly 2 options "
				+ "(click then idle)",
			2, attackOrRetry.children().size());

		assertSame("attack-or-retry option 0 must be "
				+ "artemis.click(cow, \"Attack\")",
			mockClickStep, attackOrRetry.children().get(0));
		assertSame("attack-or-retry option 1 must be the idle fallback "
				+ "(artemis.idle(SHORT_IDLE))",
			mockIdleStep, attackOrRetry.children().get(1));

		// Pin the overload signature: base 2-arg click(NpcRef, String),
		// NOT the 3-arg OutcomeCheck variant. The 3-arg variant routes
		// through OutcomeChecks.evaluate() and InteractingWithMe is
		// unsupported in v1 (OutcomeChecks.java:87-91).
		verify(artemis).click(cow, "Attack");
		verify(artemis, never()).click(any(NpcRef.class), anyString(),
			any(OutcomeCheck.class));
		// Phase 2C.x: artemis.idle IS now called in Branch 3 — it
		// materializes option 1 of the attack-or-retry Selector.
		verify(artemis).idle(any(IdlePolicy.class));
	}

	// ── Helpers ─────────────────────────────────────────────────────

	private RepeatStep extractRepeatStep()
	{
		LinearSequence root = (LinearSequence) script.plan();
		// Phase 2C.x: Selector moved from children[1] to children[2]
		// after the post-walk settle idle was inserted at children[1].
		Selector sel = (Selector) root.getChildren().get(2);
		Step option0 = sel.children().get(0);
		assertTrue("Selector option 0 must be RepeatStep", option0 instanceof RepeatStep);
		return (RepeatStep) option0;
	}

	private DynamicStep extractDynamicStep()
	{
		Step body = extractRepeatStep().body();
		assertTrue("RepeatStep body must be DynamicStep", body instanceof DynamicStep);
		return (DynamicStep) body;
	}

	/**
	 * Materialize the DynamicStep's child for a single supplier invocation,
	 * isolating supplier-time mock invocations from plan()-construction-time
	 * invocations.
	 *
	 * <p>Phase 2C.x: {@code script.plan()} construction now calls
	 * {@code artemis.idle(SHORT_IDLE)} once (for the post-walk settle)
	 * and {@code artemis.walkTo(...)} / {@code artemis.logout()} once each
	 * (for the outer tree). Without resetting invocation counts between
	 * plan-construction and supplier-execution, branch tests'
	 * {@code verify(artemis, never()).idle(...)} and
	 * {@code verify(artemis).idle(...)} assertions are contaminated by
	 * the post-walk-settle call from plan() and report misleading counts.
	 *
	 * <p>{@link Mockito#clearInvocations(Object[])} resets recorded
	 * invocations without touching stubs — {@code when(...).thenReturn(...)}
	 * remains in effect, only the call counter is reset.
	 */
	private Step invokeBranch()
	{
		DynamicStep dyn = extractDynamicStep();
		clearInvocations(artemis);
		return dyn.factory().get();
	}

	private static SessionShape sessionContinuing()
	{
		// Long.MAX_VALUE budget — shouldContinue() always returns true.
		return new SessionShape(() -> 0L, () -> 0L, Long.MAX_VALUE);
	}

	private static PlayerState playerState(boolean idle)
	{
		return new PlayerState(
			/* loc */ new WorldPoint(3258, 3265, 0),
			/* plane */ 0,
			/* animation */ idle ? -1 : 422,   // 422 = some attack anim; specific value not asserted
			/* hp */ 10,
			/* prayer */ 1,
			/* energy */ 10_000,
			/* idle */ idle);
	}
}
