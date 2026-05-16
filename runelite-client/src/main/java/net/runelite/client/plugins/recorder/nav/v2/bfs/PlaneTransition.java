package net.runelite.client.plugins.recorder.nav.v2.bfs;

import net.runelite.api.coords.WorldPoint;

/** Minimal plane-jump marker consumed by {@link RouteValidator} when a path
 *  legitimately crosses planes via a transport.
 *
 *  <p>Lane-3-local interface — the canonical spec §3 {@code TransportLeg}
 *  has the same {@code from()}/{@code to()} shape plus additional fields
 *  (type, objectId, action, requirement) the validator does not need.
 *  Lane 4/5 may adapt their {@code TransportLeg} instances to this
 *  interface by lambda or implementation.
 */
public interface PlaneTransition
{
	WorldPoint from();
	WorldPoint to();
}
