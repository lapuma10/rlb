package net.runelite.client.sequence.composite;

import net.runelite.client.sequence.Completion;
import net.runelite.client.sequence.Failure;
import net.runelite.client.sequence.PreemptionPolicy;
import net.runelite.client.sequence.Recovery;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Pins {@link FailStep}'s contract: leaf step that {@link #check(...)}-fails
 * immediately with the configured reason. Used by {@link DynamicStep}
 * factories to signal "no target found" without reaching into engine
 * internals.
 */
public class FailStepTest
{
	@Test
	public void checkReturnsFailedWithGivenReasonOnFirstCall()
	{
		FailStep step = FailStep.of("NO_COW_FOUND");
		Completion c = step.check(null, null);
		assertTrue("expected Completion.Failed, was " + c.getClass().getSimpleName(),
			c instanceof Completion.Failed);
		assertEquals("NO_COW_FOUND", ((Completion.Failed) c).reason());
	}

	@Test
	public void reasonIsPreserved()
	{
		FailStep step = FailStep.of("ANY_REASON_STRING_123");
		Completion c = step.check(null, null);
		assertEquals("ANY_REASON_STRING_123",
			((Completion.Failed) c).reason());
	}

	@Test
	public void checkRemainsFailedOnSubsequentCalls()
	{
		// Defensive — even if the engine accidentally polls check again,
		// the answer must stay Failed. No stateful one-shot.
		FailStep step = FailStep.of("STILL_FAILED");
		assertTrue(step.check(null, null) instanceof Completion.Failed);
		assertTrue(step.check(null, null) instanceof Completion.Failed);
	}

	@Test
	public void onFailureReturnsAbortWithSameReason()
	{
		// FailStep is definitive — never retry. Abort with the same reason
		// so the surrounding composite (DynamicStep, Selector, etc.) sees
		// the same diagnostic at both check() and onFailure().
		FailStep step = FailStep.of("FACTORY_SAID_NO");
		Recovery r = step.onFailure(
			new Failure("FACTORY_SAID_NO", 0, null), null, null);
		assertTrue("expected Recovery.Abort, was " + r.getClass().getSimpleName(),
			r instanceof Recovery.Abort);
		assertEquals("FACTORY_SAID_NO", ((Recovery.Abort) r).reason());
	}

	@Test
	public void nameMatchesConstructorArg()
	{
		FailStep step = FailStep.of("MY_REASON");
		// FailStep uses the reason as its name so telemetry shows the
		// failure cause at a glance.
		assertEquals("MY_REASON", step.name());
	}

	@Test
	public void hasValidEngineMetadata()
	{
		// Smoke — make sure the Step interface contract is fully
		// implemented and doesn't NPE for engine-facing accessors.
		FailStep step = FailStep.of("X");
		assertNotNull(step.preemptionPolicy());
		assertTrue(step.timeoutTicks() >= 1);
		// canStart / isSafeToPause must be permissive so the engine can
		// push the FailStep and let check() do the failing.
		assertTrue(step.canStart(null, null));
		assertTrue(step.isSafeToPause(null, null));
	}

	@Test(expected = NullPointerException.class)
	public void nullReasonRejected()
	{
		// Defensive — empty reason is allowed (caller's choice) but null
		// is a programming error.
		FailStep.of(null);
	}

	@Test
	public void preemptionPolicyIsWhenSafe()
	{
		// Definitive failure should not block preemption. WHEN_SAFE
		// matches CompositeStep's default and the engine's expectations.
		assertEquals(PreemptionPolicy.WHEN_SAFE, FailStep.of("X").preemptionPolicy());
	}
}
