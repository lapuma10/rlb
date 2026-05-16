package net.runelite.client.plugins.recorder.nav.v2.planner.spi;

import net.runelite.api.coords.WorldPoint;

/** Spec §3 contract: minimal read-only collision view.
 *
 *  <p><b>Local mock</b>: byte-identical to Lane 3's
 *  {@code nav/v2/bfs/CollisionView} and to Lane 2's eventual
 *  {@code nav/v2/collision/CollisionView}. Integration consolidates.
 *
 *  <p>Returns the {@code CollisionDataFlag} bitmask at the tile, or
 *  {@code BLOCK_MOVEMENT_FULL} if the tile is outside the known
 *  region. */
@FunctionalInterface
public interface CollisionView
{
	int flagsAt(WorldPoint p);
}
