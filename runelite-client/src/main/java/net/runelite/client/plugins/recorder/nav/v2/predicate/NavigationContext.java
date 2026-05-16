package net.runelite.client.plugins.recorder.nav.v2.predicate;

import net.runelite.client.plugins.recorder.nav.NavRequest;
import net.runelite.client.plugins.recorder.nav.v2.collision.PlayerState;
import net.runelite.client.plugins.recorder.nav.v2.collision.WorldSnapshot;

/** Spec §3 contract: composed (world + player + request) view used
 *  by transport requirements and the planner.
 *
 *  <p>Per spec §4 Lane 1 owns this interface; spec §4 Lane 2 "TilePredicate
 *  and PathContext are defined in §3 (Lane 1 owns the interfaces). Lane 2
 *  implements PredicateRegistry and the built-in predicates; the concrete
 *  PathContextImpl lives in Lane 4." NavigationContext lives next to PathContext
 *  for symmetry — both are Lane 1 interface shapes that Lane 2 hosts.
 *
 *  <p>Holds the three things every {@code TransportRequirement} needs in
 *  a single object so checks remain decoupled from the planner core. */
public interface NavigationContext
{
    WorldSnapshot world();
    PlayerState player();
    NavRequest request();
}
