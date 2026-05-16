package net.runelite.client.plugins.recorder.nav.v2.planner.spi;

import net.runelite.api.coords.WorldPoint;

/** Spec §3 contract: pure-function tile gate, evaluated at BFS
 *  expansion AND executor tile-pick.
 *
 *  <p><b>Local mock</b>: matches Lane 3's
 *  {@code nav/v2/bfs/TilePredicate} (which itself is a Lane-3-local
 *  mock of the spec §3 interface). Integration consolidates to a
 *  single canonical Lane 1 location. */
@FunctionalInterface
public interface TilePredicate
{
	boolean accept(WorldPoint tile, PathContext ctx);

	/** A predicate that accepts every tile. */
	TilePredicate ALWAYS = (t, c) -> true;
}
