package net.runelite.client.plugins.recorder.nav.v2.predicate;

import java.util.Optional;
import net.runelite.client.plugins.recorder.nav.v2.transport.V2Path;
import net.runelite.client.plugins.recorder.nav.v2.transport.Waypoint;

/** Plan-time context passed to {@link TilePredicate#accept}.
 *
 *  <p>Per spec §3, exposes the navigation context (world snapshot,
 *  player state, request) plus the current plan-in-progress state
 *  (path so far, current waypoint, route seed). Predicates use this
 *  to decide whether a tile is acceptable.
 *
 *  <p>The interface is the spec §3 contract; Lane 4 owns
 *  {@code PathContextImpl}. The {@code routeSeed} is a stable
 *  identifier the BFS uses for diagonal tie-break ordering. Two calls
 *  with the same seed produce byte-identical paths (Lane 3 determinism
 *  test). */
public interface PathContext
{
    /** The navigation context (world snapshot, player, request). */
    NavigationContext navigation();

    /** The path produced so far for this plan call, if any. */
    Optional<V2Path> currentPath();

    /** The current waypoint being routed to, if any. */
    Optional<Waypoint> currentWaypoint();

    /** Route seed for deterministic tie-breaks in BFS. */
    long routeSeed();
}
