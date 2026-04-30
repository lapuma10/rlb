package net.runelite.client.sequence.activities.ge;

/**
 * How long {@code WaitForOfferStep} waits for an offer to fill, and what
 * happens if the timeout fires before the offer is COMPLETE.
 *
 * <p>{@code timeoutTicks == 0} is "do not wait" — dispatch the offer and
 * return immediately. Used when the caller wants to schedule the offer and
 * collect later from a separate run.
 *
 * <p>{@code acceptPartialOnTimeout} controls whether a partially-filled
 * offer at the timeout counts as success ({@code Succeeded} on
 * {@link net.runelite.client.sequence.views.OfferStatus#PARTIALLY_COMPLETE})
 * or failure ({@code Failed(GeOfferIncomplete)}).
 *
 * <p>{@code partialStallTicks > 0} short-circuits the wait when the offer
 * is partially filled but the {@code completedQuantity} hasn't moved for
 * that many consecutive ticks. This is the OSRS buy-limit cap signal:
 * the player asked for 200 of an item but the 4-hour buy limit was hit
 * mid-fill, so the offer goes ACTIVE with e.g. 160/200 and never
 * progresses until the rolling window resets. Real players know to
 * collect the partial and move on. Default: 0 (disabled — only the
 * timeout-based partial accept fires).
 */
public record OfferWaitPolicy(int timeoutTicks, boolean acceptPartialOnTimeout,
                              int partialStallTicks) {

    public OfferWaitPolicy {
        if (timeoutTicks < 0) {
            throw new IllegalArgumentException("timeoutTicks must be >= 0, got " + timeoutTicks);
        }
        if (partialStallTicks < 0) {
            throw new IllegalArgumentException("partialStallTicks must be >= 0, got " + partialStallTicks);
        }
    }

    /** Source-compat constructor — kept so existing callers keep working
     *  with the timeoutTicks + acceptPartialOnTimeout shape. Stall
     *  detection is disabled. */
    public OfferWaitPolicy(int timeoutTicks, boolean acceptPartialOnTimeout) {
        this(timeoutTicks, acceptPartialOnTimeout, 0);
    }

    /** Wait up to {@code ticks}; partial fill at timeout is a failure. */
    public static OfferWaitPolicy until(int ticks)            { return new OfferWaitPolicy(ticks, false, 0); }

    /** Wait up to {@code ticks}; partial fill at timeout is treated as success. */
    public static OfferWaitPolicy untilOrPartial(int ticks)   { return new OfferWaitPolicy(ticks, true, 0); }

    /** Wait up to {@code ticks}; partial fill at timeout is success AND a
     *  partial fill that stalls for {@code stallTicks} consecutive
     *  snapshots short-circuits the wait. Use this for craft / skill
     *  flows where the bot needs the items NOW — when the GE buy limit
     *  caps a fill at e.g. 160/200, we collect the 160 and proceed
     *  rather than hanging until the 4-hour limit window resets. */
    public static OfferWaitPolicy untilOrPartialStall(int ticks, int stallTicks) {
        return new OfferWaitPolicy(ticks, true, stallTicks);
    }

    /** Dispatch and return; do not wait. */
    public static OfferWaitPolicy noWait()                    { return new OfferWaitPolicy(0, false, 0); }
}
