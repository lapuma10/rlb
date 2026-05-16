package net.runelite.client.plugins.recorder.nav.v2.qc.contracts;

import java.util.Optional;
import net.runelite.api.coords.WorldPoint;

/** Lane-6 scaffolding mirror of spec §3 {@code TransportLeg}. */
public interface TransportLeg
{
    WorldPoint from();
    WorldPoint to();
    TransportType type();
    Optional<Integer> objectId();
    Optional<String> action();
    TransportRequirement requirement();
}
