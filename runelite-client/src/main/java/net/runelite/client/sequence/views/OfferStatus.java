package net.runelite.client.sequence.views;

/**
 * Engine-internal status for a GE offer slot, mapped from
 * {@code GrandExchangeOfferState} per spec §6.1. Lets steps reason about
 * an offer without depending on the RuneLite API enum.
 */
public enum OfferStatus {
    /** Slot is unused. */
    EMPTY,
    /** Order placed, no items / coins moved yet. */
    ACTIVE,
    /** Order placed, partial fill (0 &lt; quantitySold &lt; totalQuantity). */
    PARTIALLY_COMPLETE,
    /** Order fully filled (BOUGHT / SOLD). */
    COMPLETE,
    /** Order was cancelled (CANCELLED_BUY / CANCELLED_SELL). */
    CANCELLED
}
