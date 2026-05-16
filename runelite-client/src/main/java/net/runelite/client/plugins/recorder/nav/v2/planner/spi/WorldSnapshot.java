package net.runelite.client.plugins.recorder.nav.v2.planner.spi;

import java.util.Set;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.nav.v2.transport.TransportTable;

/** Spec §3 contract: immutable world snapshot.
 *
 *  <p><b>Local mock</b>: matches Lane 2's
 *  {@code nav/v2/collision/WorldSnapshot}. Integration consolidates.
 *
 *  <p>Tightening note (cross-lane): Lane 2's mock returned
 *  {@code Object} for {@code transports()} pending Lane 4. Here in
 *  Lane 4 we already know the type, so this local mock returns
 *  {@link TransportTable} directly. Integration tightens Lane 2's
 *  version the same way.
 *
 *  <p>The {@code predicates()} method returns {@code Object} for the
 *  same reason — Lane 2's {@code PredicateRegistry} is on a separate
 *  branch. Lane 4 does not actively consume the registry from the
 *  snapshot; the planner passes predicates explicitly. */
public interface WorldSnapshot
{
	CollisionFlags collisionAt(WorldPoint p);

	CollisionView collisionView();

	Set<WorldPoint> blockingActorTiles();

	Set<WorldPoint> blockingObjectTiles();

	TransportTable transports();

	Object predicates();

	long capturedAtMs();
}
