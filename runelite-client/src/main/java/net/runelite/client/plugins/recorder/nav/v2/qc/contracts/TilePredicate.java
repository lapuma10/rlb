package net.runelite.client.plugins.recorder.nav.v2.qc.contracts;

import net.runelite.api.coords.WorldPoint;

/** Lane-6 scaffolding mirror of spec §3 {@code TilePredicate}.
 *  Pure function. Lane 2 owns the registry and built-ins. */
public interface TilePredicate
{
    boolean accept(WorldPoint tile, PathContext ctx);

    /** Stable identifier for the predicate (per spec §5 observability:
     *  "what predicates rejected tiles?"). */
    default String id() { return getClass().getSimpleName(); }
}
