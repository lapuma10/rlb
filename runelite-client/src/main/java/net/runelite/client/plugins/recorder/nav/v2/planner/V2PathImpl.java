package net.runelite.client.plugins.recorder.nav.v2.planner;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.nav.v2.transport.PathId;
import net.runelite.client.plugins.recorder.nav.v2.transport.PathStep;
import net.runelite.client.plugins.recorder.nav.v2.transport.ReplanReason;
import net.runelite.client.plugins.recorder.nav.v2.transport.TransportLeg;
import net.runelite.client.plugins.recorder.nav.v2.transport.V2Path;

/** Concrete implementation of {@link V2Path} emitted by
 *  {@link WaypointPlanner#plan}.
 *
 *  <p>Two factory paths:
 *  <ul>
 *    <li>{@link #of(List)} — successful plan with ordered steps.</li>
 *    <li>{@link #failed(ReplanReason)} — failed plan; steps list is
 *        empty, {@link #failureReason()} carries the typed reason.</li>
 *  </ul>
 *
 *  <p>Both paths produce a fresh {@link PathId} and the current
 *  wall-clock time at construction. */
public final class V2PathImpl implements V2Path
{
	private final List<PathStep> steps;
	private final PathId id;
	private final long planEpochMs;
	private final ReplanReason failureReason;
	/** Full BFS tile sequence per walk leg, in walk-then-transport order:
	 *  {@code walkLegFlatTiles[i]} corresponds to the i-th walk leg of the
	 *  alternating sequence (walk_0, transport_0, walk_1, transport_1, …,
	 *  walk_N). For shim callers (Lane 5's executor expects a tile-by-tile
	 *  list, not sparse waypoints) — exposed via
	 *  {@link #walkLegFlatTiles()}. Empty for failed plans. */
	private final List<List<WorldPoint>> walkLegFlatTiles;
	/** Transports in plan order (same order as walk-leg interleaving). */
	private final List<TransportLeg> transportLegs;

	private V2PathImpl(List<PathStep> steps,
	                   List<List<WorldPoint>> walkLegFlatTiles,
	                   List<TransportLeg> transportLegs,
	                   ReplanReason failureReason)
	{
		this.steps = steps == null ? Collections.emptyList() : Collections.unmodifiableList(steps);
		this.id = PathId.allocate();
		this.planEpochMs = System.currentTimeMillis();
		this.failureReason = failureReason;
		this.walkLegFlatTiles = walkLegFlatTiles == null
			? Collections.emptyList()
			: List.copyOf(walkLegFlatTiles);
		this.transportLegs = transportLegs == null
			? Collections.emptyList()
			: List.copyOf(transportLegs);
	}

	/** Successful plan with sparse steps only. Walk legs carry no flat
	 *  tile sequence — use {@link #of(List, List, List)} when the shim
	 *  needs tile lists per leg. */
	public static V2PathImpl of(List<PathStep> steps)
	{
		if (steps == null) throw new IllegalArgumentException("steps null");
		return new V2PathImpl(steps, null, null, null);
	}

	/** Successful plan with sparse steps PLUS the full BFS tile sequence
	 *  per walk leg + transport legs in interleaving order. Used by
	 *  Lane 5's executor shim that needs the tile-by-tile view. */
	public static V2PathImpl of(List<PathStep> steps,
	                            List<List<WorldPoint>> walkLegFlatTiles,
	                            List<TransportLeg> transportLegs)
	{
		if (steps == null) throw new IllegalArgumentException("steps null");
		return new V2PathImpl(steps, walkLegFlatTiles, transportLegs, null);
	}

	/** Failed plan with a typed reason. */
	public static V2PathImpl failed(ReplanReason reason)
	{
		if (reason == null) throw new IllegalArgumentException("reason null");
		return new V2PathImpl(Collections.emptyList(), null, null, reason);
	}

	@Override public List<PathStep> steps() { return steps; }
	@Override public PathId id() { return id; }
	@Override public long planEpochMs() { return planEpochMs; }
	@Override public boolean isFailed() { return failureReason != null; }
	@Override public Optional<ReplanReason> failureReason() { return Optional.ofNullable(failureReason); }

	/** Full BFS tile sequence per walk leg, in plan order. {@code
	 *  walkLegFlatTiles().get(i)} is the i-th walk-leg's tile list.
	 *  Empty if the plan was constructed via {@link #of(List)} (sparse-only)
	 *  or failed. */
	public List<List<WorldPoint>> walkLegFlatTiles() { return walkLegFlatTiles; }

	/** Transport legs in plan order. Same as iterating {@link #steps()}
	 *  and filtering {@link net.runelite.client.plugins.recorder.nav.v2.transport.TransportStep},
	 *  but exposed directly for shim use. */
	public List<TransportLeg> transportLegs() { return transportLegs; }

	@Override
	public String toString()
	{
		return isFailed()
			? "V2PathImpl{FAILED " + failureReason + "}"
			: "V2PathImpl{" + steps.size() + " steps, id=" + id.value() + "}";
	}
}
