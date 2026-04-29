package net.runelite.client.sequence.affordance;

/**
 * Engine-generic typed reason a step blocked or failed.
 *
 * <p>Subtypes carry domain-specific information ({@link BlockReason} for
 * generic engine concerns like blocked interfaces and location;
 * {@link GeBlockReason} for Grand-Exchange-domain reasons). Banking will
 * add bank-domain records to {@link BlockReason} on rebase.
 *
 * <p>{@link Loading}, {@link ActionTimedOut} and {@link Unknown} are
 * engine-internal cases used when no domain-specific reason applies.
 */
public sealed interface DiagnosticReason
    permits BlockReason, GeBlockReason,
            DiagnosticReason.Loading,
            DiagnosticReason.ActionTimedOut,
            DiagnosticReason.Unknown {

    /** The client is in a transient loading state (region change, login, etc.). */
    record Loading() implements DiagnosticReason {}

    /** A step's mutating action did not converge within {@code ticks} ticks. */
    record ActionTimedOut(String stepName, int ticks) implements DiagnosticReason {}

    /** Catch-all for diagnostics that don't fit the other categories. */
    record Unknown(String detail) implements DiagnosticReason {}
}
