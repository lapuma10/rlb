package net.runelite.client.plugins.recorder.nav.v2.qc.contracts;

import java.util.List;
import java.util.Optional;
import net.runelite.api.coords.WorldPoint;

/** Lane-6 scaffolding mirror of spec §3 {@code PredicateRegistry}.
 *  Lane 2 ships the production registry. The harness uses this surface
 *  to query "which predicate first rejected tile X" — that's the data
 *  every failed route's debug trace must carry per spec §5
 *  ("What predicates rejected tiles?"). */
public interface PredicateRegistry
{
    List<TilePredicate> active();
    Optional<TilePredicate> firstRejectorOf(WorldPoint tile, PathContext ctx);
}
