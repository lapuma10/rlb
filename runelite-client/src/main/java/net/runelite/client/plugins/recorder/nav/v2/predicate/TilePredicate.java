package net.runelite.client.plugins.recorder.nav.v2.predicate;

import net.runelite.api.coords.WorldPoint;

/** A pure-function tile filter. Evaluated by the BFS kernel during
 *  expansion and again by the executor when picking a concrete tile
 *  within a waypoint's tolerance bucket.
 *
 *  <p>Per spec §3: predicates are pure functions. Implementations MUST
 *  NOT mutate planner state, dispatch input, or hold locks beyond
 *  reads from the supplied {@link PathContext}. A predicate that
 *  throws is treated as REJECT (see {@link PredicateRegistry}).
 *
 *  <p>This is the contract interface from spec §3 — Lane 1 owns the
 *  shape, Lane 2 owns the file (per spec §4 Lane 2). */
@FunctionalInterface
public interface TilePredicate
{
    /** Returns true if {@code tile} is permitted to be in the route.
     *  False rejects it from both BFS expansion and executor pick.
     *
     *  @param tile  the tile being considered (immutable).
     *  @param ctx   immutable plan context for the current plan call.
     */
    boolean accept(WorldPoint tile, PathContext ctx);
}
