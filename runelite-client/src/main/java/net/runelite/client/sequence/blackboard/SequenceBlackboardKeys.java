package net.runelite.client.sequence.blackboard;

import net.runelite.client.sequence.affordance.DiagnosticReason;

/**
 * Shared, engine-visible blackboard keys. Step-private keys stay in the step
 * class itself; only cross-step / engine-visible keys live here. Domain-specific
 * keys live alongside their domain (e.g. {@code activities/ge/GeBlackboardKeys}).
 */
public final class SequenceBlackboardKeys {

    private SequenceBlackboardKeys() {}

    /**
     * STEP-scoped: the typed reason a step's last canStart-rejection cited.
     * Written by a step before returning false from canStart (or before returning
     * Completion.Failed with a diagnostic reason). Read by the planner immediately
     * at the rejection site and by telemetry. Never relied on as the sole surfacing
     * path — the planner also pushes a (stepName, reason) tuple to telemetry at
     * reject time because this key is mutable and would race when multiple candidate
     * steps are evaluated in the same tick.
     */
    public static final BlackboardKey<DiagnosticReason> LAST_BLOCK_REASON =
        BlackboardKey.of("step.lastBlockReason", DiagnosticReason.class);
}
