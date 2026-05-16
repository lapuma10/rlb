package net.runelite.client.plugins.recorder.nav.v2.predicate;

import java.util.Optional;

/** Plan-time context passed to {@link TilePredicate#accept}.
 *
 *  <p>Per spec §3, exposes the navigation context (world snapshot,
 *  player state, request) plus the current plan-in-progress state
 *  (path so far, current waypoint, route seed). Predicates use this
 *  to decide whether a tile is acceptable.
 *
 *  <p>The interface is intentionally light — Lane 4 owns the concrete
 *  {@code PathContextImpl} implementation. Method return types for
 *  {@code currentPath()} and {@code currentWaypoint()} are widened to
 *  {@link Object} until Lane 5 ships the {@code V2Path} / {@code Waypoint}
 *  interfaces; consumers cast/Optional.cast. This is documented in
 *  {@code lane2-manifest.md} as a known limitation.
 *
 *  <p>The {@code routeSeed} is a stable identifier the BFS uses for
 *  diagonal tie-break ordering. Two calls with the same seed produce
 *  byte-identical paths (Lane 3 determinism test). */
public interface PathContext
{
    /** The navigation context (world snapshot, player, request).
     *
     *  <p>Typed {@link Object} for now — see class-level note. Lane 4
     *  will replace with {@code NavigationContext} when its file lands. */
    Object navigation();

    /** The path produced so far for this plan call, if any.
     *
     *  <p>Typed {@code Optional<Object>} until Lane 5's {@code V2Path}
     *  interface lands. */
    Optional<Object> currentPath();

    /** The current waypoint being routed to, if any.
     *
     *  <p>Typed {@code Optional<Object>} until Lane 5's {@code Waypoint}
     *  interface lands. */
    Optional<Object> currentWaypoint();

    /** Route seed for deterministic tie-breaks in BFS. */
    long routeSeed();
}
