package net.runelite.client.sequence.affordance;

import net.runelite.api.coords.WorldArea;

import java.util.List;

/**
 * Generic + bank-domain blocking reasons surfaced by engine-level steps.
 *
 * <p>Sub-interface of {@link DiagnosticReason}. Banking steps and the
 * affordance report use {@code BlockReason}; engine plumbing
 * ({@link net.runelite.client.sequence.Completion.Failed#diagnostic()},
 * {@link net.runelite.client.sequence.Failure#diagnostic()},
 * {@link net.runelite.client.sequence.blackboard.SequenceBlackboardKeys#LAST_BLOCK_REASON})
 * accept the broader {@link DiagnosticReason}.
 *
 * <p>Records are kept in this file (implicit {@code permits} clause for
 * the sealed type, since all subtypes live in the same compilation unit).
 */
public sealed interface BlockReason extends DiagnosticReason {

    // ---- generic engine blockers ----

    /** The bank-pin keypad is up; cannot proceed without entering the pin. */
    record PinKeypadUp() implements BlockReason {}

    /** A blocking interface intercepts world interaction. */
    record WorldInteractionBlocked(BlockingInterface by) implements BlockReason {}

    /** Player is not in the required world area for this step. */
    record NotAtLocation(WorldArea required) implements BlockReason {}

    /** A modal dialog is open and intercepts world clicks. */
    record DialogOpen(int rootWidgetId, String label) implements BlockReason {}

    /** A leftover left-click context menu is open and intercepts world clicks. */
    record MenuOpen() implements BlockReason {}

    // ---- banking-domain blockers ----

    /** Bank widget is not currently open. */
    record BankNotOpen() implements BlockReason {}

    /** Bank widget is open but the contents container has not loaded yet. */
    record BankNotReady() implements BlockReason {}

    /** Bank contents are unknown (snapshot has no view yet, etc.). */
    record BankContentsUnknown() implements BlockReason {}

    /** A required item is missing from the bank in sufficient quantity. */
    record BankMissingItem(int itemId, String name, int requiredQty) implements BlockReason {}

    /** Inventory has no free slots for the requested withdraw. */
    record InventoryFull(int neededFreeSlots) implements BlockReason {}

    /** Inventory does not match the requested loadout. */
    record LoadoutMismatch(List<ItemDiff> diff) implements BlockReason {}

    /** A withdraw request would be a no-op (already-have / missing) at this point. */
    record WithdrawNoOp(int itemId, int ticks) implements BlockReason {}
}
