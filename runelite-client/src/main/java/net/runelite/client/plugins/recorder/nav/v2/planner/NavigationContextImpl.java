package net.runelite.client.plugins.recorder.nav.v2.planner;

import net.runelite.client.plugins.recorder.nav.NavRequest;
import net.runelite.client.plugins.recorder.nav.v2.planner.spi.NavigationContext;
import net.runelite.client.plugins.recorder.nav.v2.planner.spi.PlayerState;
import net.runelite.client.plugins.recorder.nav.v2.planner.spi.WorldSnapshot;

/** Concrete implementation of spec §3 {@link NavigationContext}.
 *
 *  <p>Composes a {@link WorldSnapshot} (Lane 2-built; here consumed
 *  as the local SPI mock), a {@link PlayerState} (same), and a
 *  {@link NavRequest}. The composition is record-shaped: immutable
 *  for one plan call.
 *
 *  <p>Used by:
 *  <ul>
 *    <li>{@link net.runelite.client.plugins.recorder.nav.v2.transport.LinkGraphDijkstra}
 *        — to evaluate {@code TransportRequirement.satisfiedBy(ctx)}.</li>
 *    <li>{@link WaypointPlanner} entry — built once per plan call.</li>
 *  </ul>
 */
public final class NavigationContextImpl implements NavigationContext
{
	private final WorldSnapshot world;
	private final PlayerState player;
	private final NavRequest request;

	public NavigationContextImpl(WorldSnapshot world, PlayerState player, NavRequest request)
	{
		if (world == null) throw new IllegalArgumentException("world null");
		if (player == null) throw new IllegalArgumentException("player null");
		if (request == null) throw new IllegalArgumentException("request null");
		this.world = world;
		this.player = player;
		this.request = request;
	}

	@Override public WorldSnapshot world() { return world; }
	@Override public PlayerState player() { return player; }
	@Override public NavRequest request() { return request; }
}
