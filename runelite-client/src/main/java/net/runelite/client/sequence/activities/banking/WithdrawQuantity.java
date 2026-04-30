package net.runelite.client.sequence.activities.banking;

/**
 * Describes how many of an item a banking step should ensure are in the
 * inventory before proceeding.
 *
 * <p>The step resolves a concrete {@code target} and {@code delta} at
 * {@code onStart} time (see spec §8.1).  This type carries only the
 * <em>desired policy</em>; it never stores runtime-computed values.
 *
 * <p>Three variants are permitted:
 * <ul>
 *   <li>{@link AtLeast} — ensure at least {@code qty} copies are present.
 *       Already satisfied when {@code currentCount >= qty}.
 *   <li>{@link WithdrawAmount} — withdraw exactly {@code qty} more on top
 *       of whatever's in inventory now. Never "already satisfied" — the
 *       caller has decided we want a fresh withdraw of {@code qty}
 *       regardless of starting balance. Used for the GE buy-with-prep
 *       coin withdraw, where rounding the FINAL balance up to a nice
 *       buffer (e.g. 50k) caused awkward fractional withdraws like
 *       45_001 from a starting balance of 4_999.
 *   <li>{@link FillRemainingInventory} — fill all remaining inventory slots
 *       from the bank (partial final trips are allowed; see spec §8.2).
 * </ul>
 */
public sealed interface WithdrawQuantity
        permits WithdrawQuantity.AtLeast,
                WithdrawQuantity.WithdrawAmount,
                WithdrawQuantity.FillRemainingInventory {

    /**
     * Ensure the final inventory count of the item is at least {@code qty}.
     * Already satisfied when {@code currentCount >= qty}.
     */
    record AtLeast(int qty) implements WithdrawQuantity {}

    /**
     * Withdraw exactly {@code qty} more of the item on top of whatever's in
     * inventory now. Never short-circuits as "already satisfied" — the
     * caller wants a fresh {@code qty} withdrawn regardless of starting
     * balance.
     */
    record WithdrawAmount(int qty) implements WithdrawQuantity {}

    /**
     * Withdraw enough of the item to fill all remaining inventory slots.
     * Already satisfied only when {@code freeSlots == 0}.
     */
    record FillRemainingInventory() implements WithdrawQuantity {}
}
