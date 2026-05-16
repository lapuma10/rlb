package net.runelite.client.plugins.recorder.nav.v2;

import net.runelite.api.coords.WorldPoint;

/** Spec §3 (Lane 1 contract): a single navigation waypoint emitted by
 *  the planner. The executor consumes a list of {@link Waypoint}-typed
 *  {@link PathStep}s.
 *
 *  <p>This is the abstract route shape — concrete tile choice is owned
 *  by the executor's {@code SidestepResolver}, which picks the
 *  furthest-forward walkable+clean tile inside the waypoint's tolerance
 *  bucket.
 *
 *  <p>Lane 5 created this file in flat {@code nav/v2/} to enable
 *  independent compilation while Lane 1/2/4 ship. The shape matches
 *  the spec §3 locked contract verbatim; Lane 4's planner impl
 *  ({@code WaypointPlanner}) will emit concrete instances. */
public interface Waypoint
{
    WorldPoint target();
    int toleranceRadius();
    WaypointType type();
    default boolean exactRequired() { return toleranceRadius() == 0; }
}
