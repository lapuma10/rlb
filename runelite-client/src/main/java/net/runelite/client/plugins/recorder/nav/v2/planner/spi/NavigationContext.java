package net.runelite.client.plugins.recorder.nav.v2.planner.spi;

import net.runelite.client.plugins.recorder.nav.NavRequest;

/** Spec §3 contract: composed (world + player + request) view used
 *  by transport requirements and the planner.
 *
 *  <p><b>Local mock</b>: matches the spec §3 interface. Lane 4
 *  implements via {@link net.runelite.client.plugins.recorder.nav.v2.planner.NavigationContextImpl}.
 *  Integration consolidates the interface to its canonical Lane 1
 *  location.
 *
 *  <p>Holds the three things every {@code TransportRequirement}
 *  needs in a single object so checks remain decoupled from the
 *  planner core. */
public interface NavigationContext
{
	WorldSnapshot world();
	PlayerState player();
	NavRequest request();
}
