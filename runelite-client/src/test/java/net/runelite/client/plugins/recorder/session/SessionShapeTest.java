package net.runelite.client.plugins.recorder.session;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SessionShapeTest
{
	// ─── defaults ───────────────────────────────────────────────────

	@Test
	public void freshSessionShouldContinueAndIsNotExhausted()
	{
		AtomicLong tick = new AtomicLong(100L);
		AtomicLong breakTick = new AtomicLong(100L);
		SessionShape s = new SessionShape(tick::get, breakTick::get, 50L);

		assertTrue(s.shouldContinue());
		assertFalse(s.budgetExhausted());
	}

	@Test
	public void ticksSinceLastBreakStartsAtZeroWhenBreakSupplierMatchesTickSupplier()
	{
		AtomicLong tick = new AtomicLong(100L);
		AtomicLong breakTick = new AtomicLong(100L);
		SessionShape s = new SessionShape(tick::get, breakTick::get, 50L);

		assertEquals(0L, s.ticksSinceLastBreak());
	}

	// ─── budget threshold ──────────────────────────────────────────

	@Test
	public void budgetExhaustsExactlyWhenElapsedReachesBudget()
	{
		AtomicLong tick = new AtomicLong(100L);
		AtomicLong breakTick = new AtomicLong(100L);
		SessionShape s = new SessionShape(tick::get, breakTick::get, 10L);

		tick.set(109L);
		assertFalse("9 ticks elapsed < 10 budget", s.budgetExhausted());
		assertTrue(s.shouldContinue());

		tick.set(110L);
		assertTrue("10 ticks elapsed >= 10 budget", s.budgetExhausted());
		assertFalse(s.shouldContinue());

		tick.set(500L);
		assertTrue("well past budget", s.budgetExhausted());
		assertFalse(s.shouldContinue());
	}

	@Test
	public void zeroBudgetIsExhaustedImmediately()
	{
		AtomicLong tick = new AtomicLong(100L);
		SessionShape s = new SessionShape(tick::get, tick::get, 0L);

		assertTrue(s.budgetExhausted());
		assertFalse(s.shouldContinue());
	}

	// ─── session origin captured at construction, not on each call ─

	@Test
	public void sessionOriginIsCapturedAtConstructionNotResolvedOnEachCall()
	{
		// If the budget compared against absolute tick value instead of
		// elapsed-since-construction, a session that begins at tick 500
		// with a 100-tick budget would report exhausted immediately.
		AtomicLong tick = new AtomicLong(500L);
		AtomicLong breakTick = new AtomicLong(500L);
		SessionShape s = new SessionShape(tick::get, breakTick::get, 100L);

		assertFalse(s.budgetExhausted());

		tick.set(599L);
		assertFalse(s.budgetExhausted());

		tick.set(600L);
		assertTrue(s.budgetExhausted());
	}

	// ─── break-tick supplier drives ticksSinceLastBreak ────────────

	@Test
	public void ticksSinceLastBreakReflectsExternalBreakSupplier()
	{
		AtomicLong tick = new AtomicLong(100L);
		AtomicLong breakTick = new AtomicLong(100L);
		SessionShape s = new SessionShape(tick::get, breakTick::get, 1000L);

		tick.set(115L);
		assertEquals(15L, s.ticksSinceLastBreak());

		// A break happens at tick 115 → supplier moves to 115.
		breakTick.set(115L);
		assertEquals(0L, s.ticksSinceLastBreak());

		tick.set(120L);
		assertEquals(5L, s.ticksSinceLastBreak());

		// Another break at 120.
		breakTick.set(120L);
		assertEquals(0L, s.ticksSinceLastBreak());
	}

	// ─── defensive: large values don't overflow long math ──────────

	@Test
	public void largeTickValuesDoNotOverflow()
	{
		// Ticks are 32-bit in the API today, but the supplier is
		// LongSupplier so cooperating callers can widen. Make sure
		// subtraction is plain long arithmetic with no surprise.
		long origin = Long.MAX_VALUE - 1000L;
		AtomicLong tick = new AtomicLong(origin);
		AtomicLong breakTick = new AtomicLong(origin);
		SessionShape s = new SessionShape(tick::get, breakTick::get, 500L);

		tick.set(origin + 499L);
		assertFalse(s.budgetExhausted());

		tick.set(origin + 500L);
		assertTrue(s.budgetExhausted());

		breakTick.set(origin + 500L);
		tick.set(origin + 503L);
		assertEquals(3L, s.ticksSinceLastBreak());
	}
}
