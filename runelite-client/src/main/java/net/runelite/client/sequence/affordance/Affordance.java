package net.runelite.client.sequence.affordance;

import java.util.List;
import java.util.Optional;

/**
 * Whether a particular {@link ActionKind} is currently permitted, and if not, why and how to recover.
 * Scaffolded in Task 1 — real affordance computation is deferred to Task 10.
 */
public record Affordance(
    ActionKind kind,
    Optional<DiagnosticReason> reason,
    List<ActionKind> suggestedRecoveries
) {
    public static Affordance allowed(ActionKind k) {
        return new Affordance(k, Optional.empty(), List.of());
    }

    public static Affordance blocked(ActionKind k, DiagnosticReason r, ActionKind... recovers) {
        return new Affordance(k, Optional.of(r), List.of(recovers));
    }

    public boolean isAllowed() {
        return reason.isEmpty();
    }
}
