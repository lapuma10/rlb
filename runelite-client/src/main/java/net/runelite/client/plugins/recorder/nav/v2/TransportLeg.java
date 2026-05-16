package net.runelite.client.plugins.recorder.nav.v2;

import java.util.Optional;
import net.runelite.api.coords.WorldPoint;

/** Spec §3 (Lane 1 contract): a typed transport segment in a {@link
 *  V2Path}. Replaces the older inline transport-edge usage.
 *
 *  <p>Lane 5 created this file in flat {@code nav/v2/} to enable
 *  independent compilation. The concrete impl + {@link
 *  net.runelite.client.plugins.recorder.worldmap.TransportEdge}
 *  adapter is consumed by Lane 4's planner (and an adapter is
 *  provided in {@code executor/TransportLegFromEdge}). */
public interface TransportLeg
{
    WorldPoint from();
    WorldPoint to();
    TransportType type();
    Optional<Integer> objectId();
    Optional<String> action();

    /** Approach tile — where the executor stands before clicking. May
     *  equal {@link #from()} for verb-on-tile transports. */
    default WorldPoint approach() { return from(); }

    /** Region id of the transport edge (used by planners + executor
     *  for region-local routing). Default to from-tile's region. */
    default int regionId()
    {
        WorldPoint f = from();
        return f == null ? -1 : (f.getX() >> 6) * 256 + (f.getY() >> 6);
    }
}
