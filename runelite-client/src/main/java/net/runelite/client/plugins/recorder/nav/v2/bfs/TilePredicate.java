package net.runelite.client.plugins.recorder.nav.v2.bfs;

import net.runelite.api.coords.WorldPoint;

/** Minimal pure-function tile predicate consumed by {@link SkretzoBfsKernel}
 *  during expansion and by {@link RouteValidator} during validation.
 *
 *  <p>Lane-3-local interface: the canonical {@code TilePredicate} is owned by
 *  Lane 1 (spec §3) and the registry of built-in predicates by Lane 2. Until
 *  those land, the BFS kernel uses this shape so it can be unit-tested in
 *  isolation. When Lane 1/2 ship, this interface either becomes a type alias
 *  or is replaced — its single-method shape matches the spec.
 *
 *  <p>The {@code ctx} parameter is the path context (spec §3
 *  {@code PathContext}); Lane 3 does not depend on its concrete shape, so it
 *  is typed as {@code Object} here. Predicates that need a real
 *  {@code PathContext} are pure functions of it and may freely cast.
 *
 *  <p>Contract: pure function. No side effects. No route-planning logic.
 */
@FunctionalInterface
public interface TilePredicate
{
	/** Returns true if the tile is acceptable in the current path context. */
	boolean accept(WorldPoint tile, Object ctx);
}
