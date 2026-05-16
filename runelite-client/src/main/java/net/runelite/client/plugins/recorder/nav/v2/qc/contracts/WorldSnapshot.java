package net.runelite.client.plugins.recorder.nav.v2.qc.contracts;

import java.util.Set;
import net.runelite.api.coords.WorldPoint;

/** Lane-6 scaffolding mirror of spec §3 {@code WorldSnapshot}.
 *  Immutable for the duration of one plan call. Lane 2 owns the
 *  production builder. */
public interface WorldSnapshot
{
    CollisionFlags collisionAt(WorldPoint p);
    Set<WorldPoint> blockingActorTiles();
    Set<WorldPoint> blockingObjectTiles();
    TransportTable transports();
    PredicateRegistry predicates();
    long capturedAtMs();
}
