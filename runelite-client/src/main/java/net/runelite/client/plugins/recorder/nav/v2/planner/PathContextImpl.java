package net.runelite.client.plugins.recorder.nav.v2.planner;

import java.util.Optional;
import net.runelite.client.plugins.recorder.nav.v2.predicate.NavigationContext;
import net.runelite.client.plugins.recorder.nav.v2.predicate.PathContext;
import net.runelite.client.plugins.recorder.nav.v2.transport.V2Path;
import net.runelite.client.plugins.recorder.nav.v2.transport.Waypoint;

/** Concrete implementation of spec §3 {@link PathContext}.
 *
 *  <p>Carries the {@link NavigationContext} plus optional
 *  plan-in-progress state ({@link #currentPath}, {@link #currentWaypoint})
 *  used by {@link net.runelite.client.plugins.recorder.nav.v2.predicate.TilePredicate}
 *  instances during BFS expansion and executor tile-pick.
 *
 *  <p>{@link #routeSeed} is derived from
 *  {@link net.runelite.client.plugins.recorder.nav.v2.bfs.BfsConfig#routeSeed}
 *  so the predicate's view of the seed matches the BFS kernel's,
 *  keeping deterministic-replay possible.
 *
 *  <p>Builder-style — pass null for missing optionals; the impl
 *  wraps them in {@link Optional} for the consumer. */
public final class PathContextImpl implements PathContext
{
	private final NavigationContext navigation;
	private final V2Path currentPath;
	private final Waypoint currentWaypoint;
	private final long routeSeed;

	public PathContextImpl(NavigationContext navigation, V2Path currentPath,
						   Waypoint currentWaypoint, long routeSeed)
	{
		if (navigation == null) throw new IllegalArgumentException("navigation null");
		this.navigation = navigation;
		this.currentPath = currentPath;
		this.currentWaypoint = currentWaypoint;
		this.routeSeed = routeSeed;
	}

	/** Convenience: build a path context with no plan-in-progress
	 *  state. Used by the planner entry when no path exists yet. */
	public static PathContextImpl planEntry(NavigationContext navigation, long routeSeed)
	{
		return new PathContextImpl(navigation, null, null, routeSeed);
	}

	/** Construct a new context with the current path / waypoint
	 *  filled in. Used as the planner walks the path. */
	public PathContextImpl withWaypoint(V2Path path, Waypoint waypoint)
	{
		return new PathContextImpl(this.navigation, path, waypoint, this.routeSeed);
	}

	@Override public NavigationContext navigation() { return navigation; }
	@Override public Optional<V2Path> currentPath() { return Optional.ofNullable(currentPath); }
	@Override public Optional<Waypoint> currentWaypoint() { return Optional.ofNullable(currentWaypoint); }
	@Override public long routeSeed() { return routeSeed; }
}
