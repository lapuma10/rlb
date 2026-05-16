package net.runelite.client.plugins.recorder.nav.v2.planner.spi;

import java.util.Optional;
import net.runelite.client.plugins.recorder.nav.v2.transport.V2Path;
import net.runelite.client.plugins.recorder.nav.v2.transport.Waypoint;

/** Spec §3 contract: per-tile-evaluation context passed to
 *  {@link TilePredicate#accept(net.runelite.api.coords.WorldPoint, PathContext)}.
 *
 *  <p><b>Local mock</b>: matches the spec §3 interface. Lane 4
 *  implements via {@link net.runelite.client.plugins.recorder.nav.v2.planner.PathContextImpl}.
 *  Integration consolidates.
 *
 *  <p>The {@code V2Path}, {@code Waypoint} references are spec §3
 *  types that Lane 5 will canonicalize; here in Lane 4 we use the
 *  local mock copies under {@code nav/v2/transport/}. Same byte-shape. */
public interface PathContext
{
	NavigationContext navigation();
	Optional<V2Path> currentPath();
	Optional<Waypoint> currentWaypoint();
	long routeSeed();
}
