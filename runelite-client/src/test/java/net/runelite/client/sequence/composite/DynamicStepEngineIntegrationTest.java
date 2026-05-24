package net.runelite.client.sequence.composite;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.runelite.client.sequence.Completion;
import net.runelite.client.sequence.Failure;
import net.runelite.client.sequence.PlayerView;
import net.runelite.client.sequence.PreemptionPolicy;
import net.runelite.client.sequence.Recovery;
import net.runelite.client.sequence.Step;
import net.runelite.client.sequence.StepContext;
import net.runelite.client.sequence.WorldSnapshot;
import net.runelite.client.sequence.blackboard.Blackboard;
import net.runelite.client.sequence.blackboard.ScopedBlackboard;
import net.runelite.client.sequence.dispatch.MockInputDispatcher;
import net.runelite.client.sequence.internal.FixtureObserver;
import net.runelite.client.sequence.internal.PriorityPlanner;
import net.runelite.client.sequence.internal.StateDrivenEngine;
import net.runelite.client.sequence.telemetry.RingBufferTelemetry;
import net.runelite.client.sequence.telemetry.TelemetryRecord;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

/**
 * Engine-level verification of {@link DynamicStep}'s integration with
 * {@code StateDrivenEngine}. Covers the test cases that require the
 * engine actually running orchestration: factory invocation, fresh-child-
 * per-RepeatStep-iteration, factory-throws / factory-returns-null
 * diagnostics, and the no-"unknown composite type" path.
 *
 * <p>Pattern borrowed from {@code RepeatStepTest}.
 */
public class DynamicStepEngineIntegrationTest
{
	/** Trivial WorldSnapshot — engine reads tick() / player() during
	 *  orchestration but the tests don't depend on real game state. */
	private static WorldSnapshot snap()
	{
		return new WorldSnapshot()
		{
			@Override public int tick() { return 0; }
			@Override public PlayerView player() { return null; }
		};
	}

	private static StateDrivenEngine newEngine(RingBufferTelemetry tel)
	{
		// 32 snapshots is well beyond what any of these tests need (max
		// ~10 ticks each); generous to avoid observer exhaustion noise.
		List<WorldSnapshot> snaps = new ArrayList<>();
		for (int i = 0; i < 32; i++) snaps.add(snap());
		return new StateDrivenEngine(
			new FixtureObserver(snaps), new PriorityPlanner(),
			new MockInputDispatcher(), tel, new ScopedBlackboard());
	}

	private static long countByEventAndName(
		RingBufferTelemetry tel, TelemetryRecord.Event event, String stepName)
	{
		return tel.tail(256).stream()
			.filter(r -> r.event() == event && stepName.equals(r.stepName()))
			.count();
	}

	private static boolean hasFailureContainingDetail(
		RingBufferTelemetry tel, String stepName, String detailFragment)
	{
		return tel.tail(256).stream()
			.anyMatch(r -> r.event() == TelemetryRecord.Event.FAILED
				&& stepName.equals(r.stepName())
				&& r.payload() != null
				&& r.payload().contains(detailFragment));
	}

	// ─────────────────────────────────────────────────────────────
	// Test #8: engine recognises DynamicStep — no "unknown composite type"
	// ─────────────────────────────────────────────────────────────

	@Test
	public void engineRecognisesDynamicStep_noUnknownCompositeTypeFailure()
	{
		RingBufferTelemetry tel = new RingBufferTelemetry(128);
		StateDrivenEngine eng = newEngine(tel);
		DynamicStep ds = DynamicStep.of("dyn-recognised",
			() -> new ImmediateSucceed("inner"));
		eng.start(ds);
		for (int i = 0; i < 4; i++) eng.advanceTick();

		boolean anyUnknownCompositeFailure = tel.tail(256).stream()
			.anyMatch(r -> r.event() == TelemetryRecord.Event.FAILED
				&& r.payload() != null
				&& r.payload().contains("unknown composite type"));
		assertFalse("engine returned FinishWithFailure(\"unknown composite type ...\") — "
			+ "the DynamicStep dispatch branch is missing or wrong",
			anyUnknownCompositeFailure);
	}

	// ─────────────────────────────────────────────────────────────
	// Test #1: factory called exactly once per frame
	// ─────────────────────────────────────────────────────────────

	@Test
	public void factoryCalledOncePerFrame_singleExecution()
	{
		RingBufferTelemetry tel = new RingBufferTelemetry(128);
		StateDrivenEngine eng = newEngine(tel);
		AtomicInteger calls = new AtomicInteger(0);

		DynamicStep ds = DynamicStep.of("dyn-once",
			() ->
			{
				calls.incrementAndGet();
				return new ImmediateSucceed("inner");
			});
		eng.start(ds);
		// One execution — DynamicStep is the root, child runs, frame pops.
		// Advance enough ticks for child onStart + check + DynamicStep
		// orchestration.
		for (int i = 0; i < 4; i++) eng.advanceTick();

		assertEquals("factory must be invoked exactly once for one frame",
			1, calls.get());
	}

	// ─────────────────────────────────────────────────────────────
	// Test #6 + #7: fresh child per RepeatStep iteration; distinct instances
	// ─────────────────────────────────────────────────────────────

	@Test
	public void repeatStepIteration_invokesFactoryFreshEachTime()
	{
		RingBufferTelemetry tel = new RingBufferTelemetry(256);
		StateDrivenEngine eng = newEngine(tel);
		AtomicInteger calls = new AtomicInteger(0);
		List<Step> producedChildren = new ArrayList<>();

		DynamicStep ds = DynamicStep.of("dyn-repeated",
			() ->
			{
				calls.incrementAndGet();
				Step child = new ImmediateSucceed("inner-" + calls.get());
				producedChildren.add(child);
				return child;
			});
		RepeatStep rep = new RepeatStep("rep-dyn", ds, 3);
		eng.start(rep);
		// Each iteration: push DynamicStep frame → factory.get() → push
		// child → child succeeds → orchestrate up → next iteration.
		// ~4 ticks per iteration is generous.
		for (int i = 0; i < 16; i++) eng.advanceTick();

		assertEquals("factory must be invoked once per RepeatStep iteration",
			3, calls.get());

		assertEquals(3, producedChildren.size());
		assertNotSame("iteration 0 vs 1 produced the same child instance — "
			+ "supplier or DynamicStep is reusing state across iterations",
			producedChildren.get(0), producedChildren.get(1));
		assertNotSame("iteration 1 vs 2 produced the same child instance",
			producedChildren.get(1), producedChildren.get(2));
		assertNotSame("iteration 0 vs 2 produced the same child instance",
			producedChildren.get(0), producedChildren.get(2));
	}

	// ─────────────────────────────────────────────────────────────
	// Test #5: factory returns null — fails loud with DYNAMIC_FACTORY_RETURNED_NULL
	// ─────────────────────────────────────────────────────────────

	@Test
	public void factoryReturnsNull_failsWithExpectedDiagnostic()
	{
		RingBufferTelemetry tel = new RingBufferTelemetry(128);
		StateDrivenEngine eng = newEngine(tel);

		DynamicStep ds = DynamicStep.of("dyn-null", () -> null);
		eng.start(ds);
		for (int i = 0; i < 4; i++) eng.advanceTick();

		assertTrue("expected FAILED telemetry for DynamicStep with reason "
			+ "containing DYNAMIC_FACTORY_RETURNED_NULL — not found in 256-record tail",
			hasFailureContainingDetail(tel, "dyn-null", "DYNAMIC_FACTORY_RETURNED_NULL"));
	}

	// ─────────────────────────────────────────────────────────────
	// Test #4: factory throws — fails loud with DYNAMIC_FACTORY_THREW
	// ─────────────────────────────────────────────────────────────

	@Test
	public void factoryThrows_failsWithExpectedDiagnostic()
	{
		RingBufferTelemetry tel = new RingBufferTelemetry(128);
		StateDrivenEngine eng = newEngine(tel);

		DynamicStep ds = DynamicStep.of("dyn-throws",
			() ->
			{
				throw new IllegalStateException("supplier blew up");
			});
		eng.start(ds);
		for (int i = 0; i < 4; i++) eng.advanceTick();

		assertTrue("expected FAILED telemetry for DynamicStep with reason "
			+ "containing DYNAMIC_FACTORY_THREW",
			hasFailureContainingDetail(tel, "dyn-throws", "DYNAMIC_FACTORY_THREW"));
		// The original exception message should be in the diagnostic so
		// post-mortem inspection can find it.
		assertTrue("expected diagnostic to include the original exception message",
			hasFailureContainingDetail(tel, "dyn-throws", "supplier blew up"));
	}

	// ─────────────────────────────────────────────────────────────
	// Test #2/#3 (smoke at engine level): child Succeeded / Failed
	// status reaches DynamicStep telemetry as SUCCEEDED / FAILED
	// ─────────────────────────────────────────────────────────────

	@Test
	public void childSuccess_reachesDynamicStepTelemetryAsSucceeded()
	{
		RingBufferTelemetry tel = new RingBufferTelemetry(128);
		StateDrivenEngine eng = newEngine(tel);
		DynamicStep ds = DynamicStep.of("dyn-prop-ok",
			() -> new ImmediateSucceed("child-says-ok"));
		eng.start(ds);
		for (int i = 0; i < 4; i++) eng.advanceTick();

		assertTrue("DynamicStep should have emitted a SUCCEEDED telemetry event",
			countByEventAndName(tel, TelemetryRecord.Event.SUCCEEDED, "dyn-prop-ok") >= 1);
	}

	@Test
	public void selectedTelemetryUsesDynamicBodyPayload()
	{
		// Engine convention: pushFirstChildIfComposite emits a SELECTED
		// telemetry record for the new child with a per-composite payload
		// ("child #0" for LinearSequence, "selector option #N" for
		// Selector, "repeat body" for RepeatStep). DynamicStep uses
		// "dynamic body" so dashboards can tell at a glance which
		// composite produced this child.
		RingBufferTelemetry tel = new RingBufferTelemetry(128);
		StateDrivenEngine eng = newEngine(tel);
		DynamicStep ds = DynamicStep.of("dyn-selected",
			() -> new ImmediateSucceed("inner"));
		eng.start(ds);
		for (int i = 0; i < 4; i++) eng.advanceTick();

		assertTrue("expected a SELECTED telemetry record with payload \"dynamic body\"",
			tel.tail(256).stream().anyMatch(r ->
				r.event() == TelemetryRecord.Event.SELECTED
					&& "dynamic body".equals(r.payload())));
	}

	@Test
	public void dynamicStepNestedInLinearSequence_reachesSubsequentSibling()
	{
		// Smoke for the cow-pilot pattern's outer shape: a LinearSequence
		// whose child is a DynamicStep needs to advance to its next sibling
		// once the DynamicStep's child completes. If DynamicStep's
		// FinishWithSuccess wasn't being interpreted correctly by the
		// engine, the LinearSequence would never reach sibling B.
		RingBufferTelemetry tel = new RingBufferTelemetry(128);
		StateDrivenEngine eng = newEngine(tel);
		DynamicStep ds = DynamicStep.of("dyn-mid",
			() -> new ImmediateSucceed("middle-child"));
		ImmediateSucceed siblingB = new ImmediateSucceed("sibling-B");
		LinearSequence seq = new LinearSequence("seq",
			java.util.List.of(ds, siblingB));
		eng.start(seq);
		for (int i = 0; i < 8; i++) eng.advanceTick();

		assertTrue("LinearSequence should have reached sibling B after DynamicStep succeeded",
			countByEventAndName(tel, TelemetryRecord.Event.SUCCEEDED, "sibling-B") >= 1);
	}

	@Test
	public void childFailure_propagatesToDynamicStepRecovery()
	{
		// Engine convention (StateDrivenEngine.java:395-404): composite
		// returning FinishWithFailure emits RECOVERY (not FAILED) on the
		// composite's name with "FinishWithFailure: <reason>" payload.
		// Leaves emit CHECK + RECOVERY (Abort), never FAILED — that event
		// type is reserved for engine-level failures and DynamicStep's
		// factory-error path. So the canonical signal that "DynamicStep
		// saw its child fail and propagated" is the DynamicStep RECOVERY.
		RingBufferTelemetry tel = new RingBufferTelemetry(128);
		StateDrivenEngine eng = newEngine(tel);
		DynamicStep ds = DynamicStep.of("dyn-prop-fail",
			() -> FailStep.of("CHILD_DECIDED_NO"));
		eng.start(ds);
		for (int i = 0; i < 4; i++) eng.advanceTick();

		assertTrue("expected DynamicStep RECOVERY payload to include the propagated reason",
			tel.tail(256).stream().anyMatch(r ->
				r.event() == TelemetryRecord.Event.RECOVERY
					&& "dyn-prop-fail".equals(r.stepName())
					&& r.payload() != null
					&& r.payload().contains("CHILD_DECIDED_NO")));
	}

	// ─────────────────────────────────────────────────────────────
	// Fixture: trivial leaf Step that returns Completion.Succeeded on
	// first check. Equivalent shape to RepeatStepTest's Counter, kept
	// local so this test is self-contained.
	// ─────────────────────────────────────────────────────────────

	private static final class ImmediateSucceed implements Step
	{
		private final String name;
		ImmediateSucceed(String name) { this.name = name; }
		@Override public String name() { return name; }
		@Override public int priority() { return 50; }
		@Override public int timeoutTicks() { return 10; }
		@Override public PreemptionPolicy preemptionPolicy() { return PreemptionPolicy.ALWAYS; }
		@Override public boolean isSafeToPause(WorldSnapshot s, Blackboard b) { return true; }
		@Override public boolean canStart(WorldSnapshot s, Blackboard b) { return true; }
		@Override public void onStart(StepContext c) {}
		@Override public void onEvent(Object e, StepContext c) {}
		@Override public void tick(StepContext c) {}
		@Override public Completion check(WorldSnapshot s, Blackboard b)
		{
			return new Completion.Succeeded("ok");
		}
		@Override public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard b)
		{
			return new Recovery.Abort("");
		}
	}
}
