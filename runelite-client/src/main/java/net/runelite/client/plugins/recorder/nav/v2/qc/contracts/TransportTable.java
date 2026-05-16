package net.runelite.client.plugins.recorder.nav.v2.qc.contracts;

import java.util.List;
import net.runelite.api.coords.WorldPoint;

/** Lane-6 scaffolding mirror of spec §3 {@code TransportTable}. Lane 4
 *  owns the production implementation. The harness only needs read
 *  access here so it can verify "transport excluded when requirement
 *  unsatisfied" and "one-way edges stay one-way". */
public interface TransportTable
{
    List<TransportLeg> outgoingFrom(WorldPoint p);
    int totalLinks();
    int oneWayLinks();
    int twoWayLinks();
    int planeChangingLinks();
    int requirementGatedLinks();
}
