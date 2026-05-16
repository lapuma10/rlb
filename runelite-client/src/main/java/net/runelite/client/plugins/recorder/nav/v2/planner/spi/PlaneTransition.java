package net.runelite.client.plugins.recorder.nav.v2.planner.spi;

import net.runelite.api.coords.WorldPoint;

/** Spec §3 / Lane 3 contract: marker for a legitimate plane-change
 *  edge explained by a transport.
 *
 *  <p><b>Local mock</b>: matches Lane 3's
 *  {@code nav/v2/bfs/PlaneTransition}. Integration consolidates. */
public interface PlaneTransition
{
	WorldPoint from();
	WorldPoint to();
}
