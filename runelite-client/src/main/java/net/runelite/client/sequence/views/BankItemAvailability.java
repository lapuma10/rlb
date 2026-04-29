package net.runelite.client.sequence.views;

import java.util.OptionalInt;

public record BankItemAvailability(
    Presence presence,
    OptionalInt knownCount,
    boolean visible
) {
    public static BankItemAvailability unknown() {
        return new BankItemAvailability(Presence.UNKNOWN, OptionalInt.empty(), false);
    }

    public static BankItemAvailability absent() {
        return new BankItemAvailability(Presence.ABSENT, OptionalInt.empty(), false);
    }

    public static BankItemAvailability present(int n, boolean visible) {
        return new BankItemAvailability(Presence.PRESENT, OptionalInt.of(n), visible);
    }
}
