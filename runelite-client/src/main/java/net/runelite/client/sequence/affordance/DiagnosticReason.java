package net.runelite.client.sequence.affordance;

/**
 * Engine-generic typed reason a step blocked or failed.
 *
 * <p>Lives in {@code sequence/affordance/} — no bank/GE/domain knowledge of
 * its own. The engine (Completion / Failure / Executor / telemetry) imports
 * {@code DiagnosticReason}, never {@link BlockReason} or
 * {@link GeBlockReason} directly.
 *
 * <p>Subtypes carry domain-specific information:
 * <ul>
 *   <li>{@link BlockReason} — generic engine + banking-domain reasons</li>
 *   <li>{@link GeBlockReason} — Grand-Exchange-domain reasons</li>
 * </ul>
 *
 * <p>{@link Loading}, {@link ActionTimedOut} and {@link Unknown} are
 * engine-internal cases used when no domain-specific reason applies.
 */
public sealed interface DiagnosticReason
    permits BlockReason,
            GeBlockReason,
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
