package net.runelite.client.plugins.recorder.session;

import java.util.function.LongSupplier;

/**
 * Read-only view of the current session's shape: time remaining within
 * the configured per-session tick budget, and the gap since the last
 * recorded break.
 *
 * <p>Phase 0A.2 scaffold — the consumer-facing surface is the three
 * methods {@link #shouldContinue()}, {@link #budgetExhausted()},
 * {@link #ticksSinceLastBreak()}. There is no mutator: the
 * last-break-tick source is supplied at construction via a
 * {@link LongSupplier} (Phase 0B will wire that supplier to read from
 * BreakScheduler; here in 0A.2 production is not yet constructing
 * SessionShape at all).
 *
 * <p>Construction-time tick is captured as the session origin —
 * {@link #budgetExhausted()} compares against ticks elapsed since that
 * moment, not against absolute tick value, so a session that begins
 * mid-day does not see "budget already exhausted" because the wall
 * clock or tick clock happens to be large.
 *
 * <p>The {@link LongSupplier} indirection makes the class trivially
 * testable with synthetic clocks; production wiring (later) supplies
 * {@code () -> client.getTickCount()} and a break-tick supplier of the
 * same shape.
 */
public final class SessionShape
{
	private final LongSupplier tickSupplier;
	private final LongSupplier lastBreakTickSupplier;
	private final long tickBudget;
	private final long sessionStartTick;

	/**
	 * @param tickSupplier            current tick clock (production:
	 *                                {@code () -> client.getTickCount()})
	 * @param lastBreakTickSupplier   tick at which the most recent
	 *                                break ended (Phase 0B wires this
	 *                                to BreakScheduler; for early
	 *                                callers without a break source,
	 *                                pass the same supplier as
	 *                                {@code tickSupplier} captured at
	 *                                construction)
	 * @param tickBudget              maximum ticks the session is
	 *                                allowed to run (e.g. 24000 for a
	 *                                4-hour session at 600 ms / tick)
	 */
	public SessionShape(LongSupplier tickSupplier, LongSupplier lastBreakTickSupplier, long tickBudget)
	{
		this.tickSupplier = tickSupplier;
		this.lastBreakTickSupplier = lastBreakTickSupplier;
		this.tickBudget = tickBudget;
		this.sessionStartTick = tickSupplier.getAsLong();
	}

	/** True iff the session may still run another step. v1: equivalent
	 *  to {@code !budgetExhausted()}; future revisions may layer on
	 *  reactive policy (low health, paused, etc.). */
	public boolean shouldContinue()
	{
		return !budgetExhausted();
	}

	/** True once {@code ticksElapsed >= tickBudget}. Exact-equality
	 *  threshold so a budget of N ticks allows exactly the first N
	 *  ticks of dispatched work, then stops. */
	public boolean budgetExhausted()
	{
		return tickSupplier.getAsLong() - sessionStartTick >= tickBudget;
	}

	/** Tick distance from the most recent break to now. Pre-break (no
	 *  break has happened yet) the supplier typically returns the
	 *  session-start tick, in which case this equals the total ticks
	 *  elapsed in the session. */
	public long ticksSinceLastBreak()
	{
		return tickSupplier.getAsLong() - lastBreakTickSupplier.getAsLong();
	}
}
