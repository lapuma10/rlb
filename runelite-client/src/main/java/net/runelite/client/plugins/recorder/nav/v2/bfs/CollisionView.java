package net.runelite.client.plugins.recorder.nav.v2.bfs;

import net.runelite.api.CollisionDataFlag;
import net.runelite.api.coords.WorldPoint;

/** Minimal read-only collision view consumed by {@link SkretzoBfsKernel}.
 *
 *  <p>Lane-3-local interface: Lane 2 will ship the canonical
 *  {@code nav/v2/collision/CollisionView.java}. Until that lands, the BFS
 *  kernel operates on this shape so it stays fully unit-testable without a
 *  RuneLite {@code Client}.
 *
 *  <p>When Lane 2 lands, this interface either becomes a type alias for the
 *  Lane 2 interface (single-method, structurally identical) or is replaced by
 *  it across the kernel. Either way, fixtures continue to satisfy it.
 *
 *  <p>Return value: a bitmask of {@link CollisionDataFlag} bits at the given
 *  tile. Tiles outside the loaded region must return
 *  {@link CollisionDataFlag#BLOCK_MOVEMENT_FULL} (treat as off-scene = fully
 *  blocked). The kernel never assumes the tile exists; the flagmap decides.
 */
public interface CollisionView
{
	/** Returns the {@link CollisionDataFlag} bitmask at the given tile, or
	 *  {@link CollisionDataFlag#BLOCK_MOVEMENT_FULL} if the tile is outside
	 *  any known region/plane. */
	int flagsAt(WorldPoint p);
}
