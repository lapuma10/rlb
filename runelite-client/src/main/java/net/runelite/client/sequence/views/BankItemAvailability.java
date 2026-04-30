package net.runelite.client.sequence.views;

import java.util.OptionalInt;

public record BankItemAvailability(
    Presence presence,
    OptionalInt knownCount,
    boolean visible,
    boolean stackable
) {
    public static BankItemAvailability unknown() {
        return new BankItemAvailability(Presence.UNKNOWN, OptionalInt.empty(), false, false);
    }

    public static BankItemAvailability absent() {
        return new BankItemAvailability(Presence.ABSENT, OptionalInt.empty(), false, false);
    }

    /** Backward-compatible factory; stackable defaults to {@code false}. */
    public static BankItemAvailability present(int n, boolean visible) {
        return new BankItemAvailability(Presence.PRESENT, OptionalInt.of(n), visible, false);
    }

    /** Production factory: stackable reflects the item's normal-form
     *  {@code ItemComposition.isStackable()}. Steps use this to decide
     *  whether a withdraw of qty>1 occupies one slot or qty slots. */
    public static BankItemAvailability present(int n, boolean visible, boolean stackable) {
        return new BankItemAvailability(Presence.PRESENT, OptionalInt.of(n), visible, stackable);
    }
}
