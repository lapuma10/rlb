package net.runelite.client.sequence.activities.banking;

/**
 * Describes how many of an item a banking step should ensure are in the
 * inventory before proceeding.
 *
 * <p>The step resolves a concrete {@code target} and {@code delta} at
 * {@code onStart} time (see spec §8.1).  This type carries only the
 * <em>desired policy</em>; it never stores runtime-computed values.
 *
 * <p>Only two variants are permitted:
 * <ul>
 *   <li>{@link AtLeast} — ensure at least {@code qty} copies are present.
 *   <li>{@link FillRemainingInventory} — fill all remaining inventory slots
 *       from the bank (partial final trips are allowed; see spec §8.2).
 * </ul>
 *
 * <p>There is intentionally no {@code Exact} variant.  "Exact" withdrawal
 * semantics are identical to {@code AtLeast} (already-satisfied iff
 * {@code currentCount >= q}) and the name was misleading.
 */
public sealed interface WithdrawQuantity
        permits WithdrawQuantity.AtLeast, WithdrawQuantity.FillRemainingInventory {

    /**
     * Ensure the final inventory count of the item is at least {@code qty}.
     * Already satisfied when {@code currentCount >= qty}.
     */
    record AtLeast(int qty) implements WithdrawQuantity {}

    /**
     * Withdraw enough of the item to fill all remaining inventory slots.
     * Already satisfied only when {@code freeSlots == 0}.
     */
    record FillRemainingInventory() implements WithdrawQuantity {}
}
