package net.runelite.client.sequence.composite;

import java.util.function.Supplier;
import net.runelite.client.sequence.Completion;
import net.runelite.client.sequence.PreemptionPolicy;
import net.runelite.client.sequence.Step;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Unit-level pinning of {@link DynamicStep}'s frame-aware orchestration
 * contract — invoked directly without going through {@code StateDrivenEngine}.
 * Engine integration (factory invocation in {@code pushFirstChildIfComposite},
 * fresh-child-per-RepeatStep-iteration, the "unknown composite type" path)
 * is covered separately by {@code DynamicStepEngineIntegrationTest}
 * (slice 1C.3).
 */
public class DynamicStepTest
{
	/** Supplier sentinel that asserts it was never called in tests where
	 *  the factory should not be invoked. */
	private static final Supplier<Step> SHOULD_NOT_CALL = () ->
	{
		fail("factory must not be invoked in this test");
		return null;
	};

	@Test
	public void nameAndFactoryArePreserved()
	{
		Supplier<Step> factory = () -> FailStep.of("UNREACHABLE");
		DynamicStep ds = DynamicStep.of("find-cow", factory);
		assertEquals("find-cow", ds.name());
		assertSame("factory accessor must return the supplied instance",
			factory, ds.factory());
	}

	@Test(expected = NullPointerException.class)
	public void nullNameRejected()
	{
		DynamicStep.of(null, SHOULD_NOT_CALL);
	}

	@Test(expected = NullPointerException.class)
	public void nullFactoryRejected()
	{
		DynamicStep.of("x", null);
	}

	@Test
	public void childSucceededPropagatesAsFinishWithSuccess()
	{
		DynamicStep ds = DynamicStep.of("dyn-success", SHOULD_NOT_CALL);
		DynamicStepFrame frame = new DynamicStepFrame(ds, 0);
		CompositeStep.NextAction next = ds.onChildPopped(
			frame, new Completion.Succeeded("child ok"), null, null);
		assertTrue("expected FinishWithSuccess, was " + next.getClass().getSimpleName(),
			next instanceof CompositeStep.FinishWithSuccess);
		assertEquals("child ok",
			((CompositeStep.FinishWithSuccess) next).reason());
	}

	@Test
	public void childFailedPropagatesAsFinishWithFailure()
	{
		DynamicStep ds = DynamicStep.of("dyn-failure", SHOULD_NOT_CALL);
		DynamicStepFrame frame = new DynamicStepFrame(ds, 0);
		CompositeStep.NextAction next = ds.onChildPopped(
			frame, new Completion.Failed("child broke"), null, null);
		assertTrue("expected FinishWithFailure, was " + next.getClass().getSimpleName(),
			next instanceof CompositeStep.FinishWithFailure);
		assertEquals("child broke",
			((CompositeStep.FinishWithFailure) next).reason());
	}

	@Test(expected = UnsupportedOperationException.class)
	public void abstractOnChildPoppedOverloadThrows()
	{
		// Mirrors RepeatStep.java:46-48 — the base CompositeStep contract
		// has an abstract (Step, Completion, ...) overload; concrete
		// composites override it with a stub that throws because the
		// engine calls the frame-aware overload directly.
		DynamicStep ds = DynamicStep.of("x", SHOULD_NOT_CALL);
		ds.onChildPopped((Step) null, new Completion.Succeeded("x"), null, null);
	}

	@Test
	public void compositeMetadataMatchesBase()
	{
		// CompositeStep defaults from the abstract base (priority 0,
		// PreemptionPolicy.WHEN_SAFE, infinite timeout, always safe to
		// pause / start). DynamicStep should not override these.
		DynamicStep ds = DynamicStep.of("x", SHOULD_NOT_CALL);
		assertEquals(0, ds.priority());
		assertEquals(PreemptionPolicy.WHEN_SAFE, ds.preemptionPolicy());
		assertEquals(Integer.MAX_VALUE, ds.timeoutTicks());
		assertTrue(ds.canStart(null, null));
		assertTrue(ds.isSafeToPause(null, null));
	}

	@Test
	public void frameStartsWithNoMaterializedChild()
	{
		// The engine sets materializedChild in pushFirstChildIfComposite
		// after a successful supplier.get(). Before that the frame must
		// be honest about not having a child yet.
		DynamicStep ds = DynamicStep.of("x", SHOULD_NOT_CALL);
		DynamicStepFrame frame = new DynamicStepFrame(ds, 0);
		assertNull(frame.getMaterializedChild());
	}

	@Test
	public void frameStoresMaterializedChild()
	{
		// Telemetry / diagnostic only — the child has its own engine frame
		// on the stack. We hold the reference so post-mortem inspection
		// of a failed frame can tell what was generated.
		DynamicStep ds = DynamicStep.of("x", SHOULD_NOT_CALL);
		DynamicStepFrame frame = new DynamicStepFrame(ds, 0);
		FailStep child = FailStep.of("CHILD");
		frame.setMaterializedChild(child);
		assertSame(child, frame.getMaterializedChild());
	}

	@Test
	public void compositeCheckStaysRunning()
	{
		// CompositeStep base returns Completion.Running() from check().
		// DynamicStep must inherit this — composites never tick / poll;
		// the engine drives orchestration via onChildPopped.
		DynamicStep ds = DynamicStep.of("x", SHOULD_NOT_CALL);
		Completion c = ds.check(null, null);
		assertNotNull(c);
		assertTrue("expected Completion.Running, was " + c.getClass().getSimpleName(),
			c instanceof Completion.Running);
	}
}
