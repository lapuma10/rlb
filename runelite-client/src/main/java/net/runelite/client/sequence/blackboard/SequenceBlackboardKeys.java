package net.runelite.client.sequence.blackboard;

import net.runelite.client.sequence.affordance.DiagnosticReason;

/**
 * Engine-internal blackboard keys reused across steps. Domain-specific keys
 * live alongside their domain (e.g.
 * {@code activities/ge/GeBlackboardKeys}).
 */
public final class SequenceBlackboardKeys {

    private SequenceBlackboardKeys() {}

    /**
     * STEP-scoped: the typed reason a step rejected itself in the most recent
     * {@code canStart} or {@code onStart} pre-flight. Populated by the step
     * itself; read by the planner / engine for telemetry.
     */
    public static final BlackboardKey<DiagnosticReason> LAST_BLOCK_REASON =
        BlackboardKey.of("step.lastBlockReason", DiagnosticReason.class);
}
