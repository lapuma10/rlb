package net.runelite.client.plugins.recorder.nav;

/** Single abstraction over "get the player from A to B." Scripts depend
 *  on this interface, never on a concrete walker class. The factory
 *  picks which implementation to return based on the user's switch
 *  setting, so a single config flag flips V1 (TrailWalker) ↔ V2
 *  (WorldMemory).
 *
 *  <p>Lifecycle: the script's outer tick loop calls {@link #tick} every
 *  ~600 ms with the same {@link NavRequest} until the navigator returns
 *  {@link NavStatus#ARRIVED} or {@link NavStatus#FAILED}. The script
 *  decides what to do next; the navigator does not advance the
 *  script's state machine. {@link #cancel} aborts an in-flight
 *  navigation (useful when the script wants to switch destinations
 *  mid-route).
 *
 *  <p>Threading: implementations may dispatch input asynchronously, but
 *  {@code tick} itself is called from the script's worker thread (the
 *  same thread that today calls {@code TrailWalker.tick}). It returns
 *  promptly; long blocking work happens on the input dispatcher's own
 *  worker. {@link InterruptedException} is propagated so the script
 *  can stop cleanly when the worker is interrupted. */
public interface Navigator
{
    /** Drive one step of navigation toward the request's destination.
     *  Returns the post-tick status. Idempotent on {@link
     *  NavStatus#ARRIVED} — calling again is a no-op until {@link
     *  #cancel} or a different request resets state. */
    NavStatus tick(NavRequest request) throws InterruptedException;

    /** Abort the active navigation. Subsequent {@link #tick} calls with
     *  any request start fresh. No-op if no navigation is in flight. */
    void cancel();

    /** True between the start of a navigation and its terminal state.
     *  Mirrors {@code HumanizedInputDispatcher.isBusy}'s shape so
     *  scripts can guard against re-entrant input. */
    boolean isBusy();

    /** Short identifier for logs and inspection dumps. Stable across
     *  releases — used to attribute pathing decisions to a specific
     *  Navigator implementation. */
    String name();
}
