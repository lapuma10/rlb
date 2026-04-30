package net.runelite.client.sequence.views;

import java.util.Optional;
import net.runelite.client.sequence.affordance.AffordanceReport;
import net.runelite.client.sequence.affordance.BlockingInterface;

public interface InteractionView {
    InteractionMode mode();
    boolean worldInteractionAvailable();
    boolean movementAvailable();
    Optional<BlockingInterface> blockingInterface();
    AffordanceReport affordances();

    static InteractionView world() {
        return new InteractionView() {
            @Override public InteractionMode mode()                           { return InteractionMode.WORLD; }
            @Override public boolean worldInteractionAvailable()              { return true; }
            @Override public boolean movementAvailable()                      { return true; }
            @Override public Optional<BlockingInterface> blockingInterface()  { return Optional.empty(); }
            @Override public AffordanceReport affordances()                   { return AffordanceReport.allAllowed(); }
        };
    }
}
