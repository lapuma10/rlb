package net.runelite.client.sequence.views;

import java.util.Optional;
import net.runelite.client.sequence.affordance.AffordanceReport;
import net.runelite.client.sequence.affordance.BlockingInterface;

/**
 * Snapshot view of the player's interaction state. Steps consult this to
 * decide whether a click would be intercepted by a blocking interface, and
 * to drive reactive dismiss steps when one appears.
 *
 * <p>{@link #empty()} / {@link #world()} return the engine-default
 * null-object: free world interaction, no blocker, all affordances allowed.
 */
public interface InteractionView {

    /** High-level mode. */
    InteractionMode mode();

    /** True iff a world click would land normally (no modal, no menu). */
    boolean worldInteractionAvailable();

    /** True iff the player can walk (mostly the same as
     *  {@link #worldInteractionAvailable()}, but a dialog-only modal might
     *  let the player walk while blocking other clicks). */
    boolean movementAvailable();

    /** Description of the topmost blocking interface, if any. */
    Optional<BlockingInterface> blockingInterface();

    /** Per-affordance allow/deny report (may be empty for older callers). */
    AffordanceReport affordances();

    /** Engine-default null-object: free interaction, no blocker, allowed. */
    static InteractionView empty() { return EMPTY; }

    /** Alias for {@link #empty()} — descriptive name for "free world interaction". */
    static InteractionView world() { return EMPTY; }

    InteractionView EMPTY = new InteractionView() {
        @Override public InteractionMode mode()                          { return InteractionMode.WORLD; }
        @Override public boolean worldInteractionAvailable()             { return true; }
        @Override public boolean movementAvailable()                     { return true; }
        @Override public Optional<BlockingInterface> blockingInterface() { return Optional.empty(); }
        @Override public AffordanceReport affordances()                  { return AffordanceReport.allAllowed(); }
    };
}
