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
 * offer at timeout counts as success ({@code Succeeded} on
 * {@link net.runelite.client.sequence.views.OfferStatus#PARTIALLY_COMPLETE})
 * or failure ({@code Failed(GeOfferIncomplete)}).
 */
public record OfferWaitPolicy(int timeoutTicks, boolean acceptPartialOnTimeout) {

    public OfferWaitPolicy {
        if (timeoutTicks < 0) {
            throw new IllegalArgumentException("timeoutTicks must be >= 0, got " + timeoutTicks);
        }
    }

    /** Wait up to {@code ticks}; partial fill at timeout is a failure. */
    public static OfferWaitPolicy until(int ticks)            { return new OfferWaitPolicy(ticks, false); }

    /** Wait up to {@code ticks}; partial fill at timeout is treated as success. */
    public static OfferWaitPolicy untilOrPartial(int ticks)   { return new OfferWaitPolicy(ticks, true); }

    /** Dispatch and return; do not wait. */
    public static OfferWaitPolicy noWait()                    { return new OfferWaitPolicy(0, false); }
}
