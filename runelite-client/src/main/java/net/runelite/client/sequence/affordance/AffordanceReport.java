package net.runelite.client.sequence.affordance;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * A full snapshot of which {@link ActionKind}s are currently allowed.
 * Scaffolded in Task 1 — {@link #allAllowed()} is the only production factory used until Task 10.
 */
public record AffordanceReport(List<Affordance> entries) {

    /**
     * Returns a report where every {@link ActionKind} is allowed.
     * Used as the default stub until the production affordance computation is wired in Task 10.
     */
    public static AffordanceReport allAllowed() {
        List<Affordance> all = Arrays.stream(ActionKind.values())
            .map(Affordance::allowed)
            .collect(Collectors.toList());
        return new AffordanceReport(all);
    }

    public boolean isAllowed(ActionKind k) {
        return entries.stream()
            .filter(a -> a.kind() == k)
            .findFirst()
            .map(Affordance::isAllowed)
            .orElse(true); // absent entry = not blocked
    }

    public Optional<Affordance> entry(ActionKind k) {
        return entries.stream()
            .filter(a -> a.kind() == k)
            .findFirst();
    }

    public List<Affordance> blocked() {
        return entries.stream()
            .filter(a -> !a.isAllowed())
            .collect(Collectors.toList());
    }
}
