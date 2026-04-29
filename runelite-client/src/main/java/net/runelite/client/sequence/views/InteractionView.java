package net.runelite.client.sequence.views;

import java.util.Optional;
import net.runelite.client.sequence.affordance.BlockingInterface;

/**
 * Snapshot view of the player's interaction state. Steps consult this to
 * decide whether a click would be intercepted by a blocking interface, and
 * to drive reactive dismiss steps when one appears.
 *
 * <p>{@link #empty()} returns the engine-default null-object: free world
 * interaction, no blocker.
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

    /** Engine-default null-object: free interaction, no blocker. */
    static InteractionView empty() { return EMPTY; }

    InteractionView EMPTY = new InteractionView() {
        public InteractionMode mode()                       { return InteractionMode.WORLD; }
        public boolean worldInteractionAvailable()          { return true; }
        public boolean movementAvailable()                  { return true; }
        public Optional<BlockingInterface> blockingInterface() { return Optional.empty(); }
    };
}
