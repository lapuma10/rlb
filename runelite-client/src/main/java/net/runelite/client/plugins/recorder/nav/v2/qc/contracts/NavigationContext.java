package net.runelite.client.plugins.recorder.nav.v2.qc.contracts;

import net.runelite.client.plugins.recorder.nav.NavRequest;

/** Lane-6 scaffolding mirror of spec §3 {@code NavigationContext}.
 *  Composes world geometry + player capabilities + request intent so
 *  {@code TransportRequirement.satisfiedBy(...)} can read all three. */
public interface NavigationContext
{
    WorldSnapshot world();
    PlayerState player();
    NavRequest request();
}
