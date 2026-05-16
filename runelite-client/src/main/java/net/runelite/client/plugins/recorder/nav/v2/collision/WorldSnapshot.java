package net.runelite.client.plugins.recorder.nav.v2.collision;

import java.util.Set;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.nav.v2.predicate.PredicateRegistry;

/** Immutable world-state snapshot used by the planner for one
 *  {@code plan(...)} call.
 *
 *  <p>This is the contract interface from spec §3 — Lane 1 owns the
 *  shape, Lane 2 owns the implementation file location (here in
 *  {@code nav/v2/collision/} because Lane 2 constructs instances via
 *  {@link WorldSnapshotBuilder}). Downstream lanes import this
 *  interface and consume the snapshot read-only.
 *
 *  <p>Once built, every accessor returns the same data for the
 *  lifetime of the instance. The implementation copies arrays
 *  defensively at construction so external mutation of the underlying
 *  client state is invisible to consumers. This is what gives the
 *  planner a stable view to plan against.
 *
 *  <p>{@code transports()} is typed as {@link Object} for now: Lane 4
 *  owns the {@code TransportTable} interface and will tighten this
 *  return type when its file lands (cross-lane edit approved via
 *  coordination per spec §11). Consumers will need to cast until then.
 *  This is documented as a known limitation in {@code lane2-manifest.md}. */
public interface WorldSnapshot
{
    /** Returns the merged collision flags for {@code p}. Live overlay
     *  wins inside the loaded scene; global snapshot is the fallback;
     *  both miss → flags reflect {@code BLOCK_MOVEMENT_FULL}. */
    CollisionFlags collisionAt(WorldPoint p);

    /** Read-only view of the {@link CollisionView} used at
     *  construction. The BFS kernel (Lane 3) consumes this directly
     *  for the hot path.
     *
     *  <p>Return type is the narrow Lane 3 interface
     *  ({@code nav.v2.bfs.CollisionView}) — Lane 2's concrete
     *  {@code CollisionView} class implements it. Lane 4 / Lane 5
     *  consume only {@code flagsAt(WorldPoint)} on the hot path; test
     *  fixtures can supply a simple lambda matching this interface. */
    net.runelite.client.plugins.recorder.nav.v2.bfs.CollisionView collisionView();

    /** Tiles that are blocked because a movement-blocking actor stands
     *  on them. Empty set if no actors block movement.
     *  Immutable — backed by {@code Collections.unmodifiableSet}. */
    Set<WorldPoint> blockingActorTiles();

    /** Tiles blocked by movement-blocking dynamic objects. Immutable. */
    Set<WorldPoint> blockingObjectTiles();

    /** The transport table consulted by the planner.
     *
     *  <p>Typed {@link Object} until Lane 4 ships its {@code
     *  TransportTable} interface; consumers cast/Optional.cast. Lane 4
     *  will tighten this via a coordinated cross-lane edit. */
    Object transports();

    /** The predicate registry consulted by both the BFS kernel and
     *  the executor when scoring tile choice. */
    PredicateRegistry predicates();

    /** Wall-clock instant the snapshot was captured. Used for
     *  diagnostic correlation; consumers should NOT use this as a
     *  cache key. */
    long capturedAtMs();

    /** Player's {@link WorldPoint} at snapshot capture. Used by
     *  {@code WaypointPlanner} to seed routing from the live position
     *  without taking a separate {@code start} parameter.
     *
     *  <p>Returns {@code null} if the player was not in a loaded scene
     *  at capture time (e.g., logging in, world hop in progress). The
     *  planner translates this to {@link
     *  net.runelite.client.plugins.recorder.nav.v2.transport.ReplanReason#REGION_NOT_LOADED}.
     *
     *  <p>Added at integration per spec §3 — the original §3 used a
     *  separate {@code start} parameter on {@code WaypointPlanner.plan},
     *  but spec §3 §11 left placement open. The snapshot owns it
     *  because the player's position is part of the immutable world
     *  state captured for one plan call. */
    @javax.annotation.Nullable
    WorldPoint playerPosition();
}
