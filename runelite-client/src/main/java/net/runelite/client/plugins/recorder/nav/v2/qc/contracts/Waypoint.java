package net.runelite.client.plugins.recorder.nav.v2.qc.contracts;

import net.runelite.api.coords.WorldPoint;

/** Lane-6 scaffolding mirror of spec §3 {@code Waypoint}. Phase-1
 *  Lane-6 cannot wait for Lane 1's real interface materialisation to
 *  test against; this scaffolding lives only under {@code qc/contracts/}
 *  and is replaced 1:1 when Lane 1 ships the real {@code nav.v2.Waypoint}.
 *  The signature is byte-identical to spec §3. */
public interface Waypoint
{
    WorldPoint target();
    int toleranceRadius();
    WaypointType type();
    boolean exactRequired();
}
