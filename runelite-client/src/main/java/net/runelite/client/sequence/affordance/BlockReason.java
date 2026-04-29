package net.runelite.client.sequence.affordance;

import net.runelite.api.coords.WorldArea;

/**
 * Generic blocking reasons surfaced by engine-level steps.
 *
 * <p>Phase A subset (GE proof). Banking will add bank-domain records here
 * on rebase ({@code BankNotOpen}, {@code BankMissingItem}, …).
 */
public sealed interface BlockReason extends DiagnosticReason
    permits BlockReason.PinKeypadUp,
            BlockReason.WorldInteractionBlocked,
            BlockReason.NotAtLocation {

    /** The bank-pin keypad is up; cannot proceed without entering the pin. */
    record PinKeypadUp() implements BlockReason {}

    /** A blocking interface intercepts world interaction. */
    record WorldInteractionBlocked(BlockingInterface by) implements BlockReason {}

    /** Player is not in the required world area for this step. */
    record NotAtLocation(WorldArea required) implements BlockReason {}
}
