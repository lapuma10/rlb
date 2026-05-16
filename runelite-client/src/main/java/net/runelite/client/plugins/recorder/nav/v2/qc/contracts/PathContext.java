package net.runelite.client.plugins.recorder.nav.v2.qc.contracts;

import java.util.Optional;

/** Lane-6 scaffolding mirror of spec §3 {@code PathContext}. Passed to
 *  {@code TilePredicate.accept(...)} during BFS expansion AND executor
 *  tile pick. Lane 4 owns the production concrete impl. */
public interface PathContext
{
    NavigationContext navigation();
    Optional<V2Path> currentPath();
    Optional<Waypoint> currentWaypoint();
    long routeSeed();
}
