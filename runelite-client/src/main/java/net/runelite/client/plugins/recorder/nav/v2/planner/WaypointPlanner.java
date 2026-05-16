package net.runelite.client.plugins.recorder.nav.v2.planner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.nav.NavRequest;
import net.runelite.client.plugins.recorder.nav.v2.planner.spi.BfsConfig;
import net.runelite.client.plugins.recorder.nav.v2.planner.spi.NavigationContext;
import net.runelite.client.plugins.recorder.nav.v2.planner.spi.PathContext;
import net.runelite.client.plugins.recorder.nav.v2.planner.spi.PlaneTransition;
import net.runelite.client.plugins.recorder.nav.v2.planner.spi.PlayerState;
import net.runelite.client.plugins.recorder.nav.v2.planner.spi.RouteValidator;
import net.runelite.client.plugins.recorder.nav.v2.planner.spi.SkretzoBfsKernel;
import net.runelite.client.plugins.recorder.nav.v2.planner.spi.TilePredicate;
import net.runelite.client.plugins.recorder.nav.v2.planner.spi.WorldSnapshot;
import net.runelite.client.plugins.recorder.nav.v2.transport.LinkGraphDijkstra;
import net.runelite.client.plugins.recorder.nav.v2.transport.PathStep;
import net.runelite.client.plugins.recorder.nav.v2.transport.ReplanReason;
import net.runelite.client.plugins.recorder.nav.v2.transport.TransportLeg;
import net.runelite.client.plugins.recorder.nav.v2.transport.TransportTable;
import net.runelite.client.plugins.recorder.nav.v2.transport.V2Path;
import net.runelite.client.plugins.recorder.nav.v2.transport.WalkStep;
import net.runelite.client.plugins.recorder.nav.v2.transport.WaypointType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The Lane-4 orchestrator. Per spec §1 data-flow diagram and §4
 *  Lane 4 entry:
 *
 *  <ol>
 *    <li>Build {@link NavigationContext} from inputs.</li>
 *    <li>{@link LinkGraphDijkstra#findRouteSkeleton} over the
 *        transport+region graph.</li>
 *    <li>For each pair of consecutive WALK-skeleton nodes, run
 *        {@link SkretzoBfsKernel#plan} to produce the tile-level
 *        sequence for that walk leg.</li>
 *    <li>For each TRANSPORT-skeleton node, emit a typed
 *        {@link TransportLeg}.</li>
 *    <li>Pass each tile sequence through
 *        {@link PathCompressor#compressLeg} → sparse waypoints.</li>
 *    <li>{@link RouteValidator#validate} the entire flattened tile
 *        sequence — every step must satisfy collision + adjacency +
 *        plane-change rules. Validation failure ⇒ {@link
 *        ReplanReason#TARGET_UNREACHABLE}.</li>
 *    <li>Assemble the interleaved {@link PathStep} list and emit
 *        {@link V2PathImpl#of}.</li>
 *  </ol>
 *
 *  <p>Failure modes (typed):
 *  <ul>
 *    <li>{@link ReplanReason#TARGET_UNREACHABLE} — Dijkstra returned
 *        unreachable, OR a BFS leg returned unreachable, OR the
 *        validator rejected the compressed path.</li>
 *    <li>{@link ReplanReason#EXECUTOR_TIMEOUT} — a BFS leg hit the
 *        expansion budget.</li>
 *  </ul>
 *
 *  <p>Threading: pure compute. Runs on the caller's thread (typically
 *  the planner-worker thread spawned by {@code V2Navigator}). No
 *  client-thread reads — the {@link WorldSnapshot} is already
 *  immutable. */
public final class WaypointPlanner
{
	private static final Logger log = LoggerFactory.getLogger(WaypointPlanner.class);

	private WaypointPlanner() {}

	/** Spec §3 signature: {@code plan(NavigationRequest, WorldSnapshot,
	 *  BfsConfig) → V2Path}. {@link PlayerState} is reached via the
	 *  built {@link NavigationContext}, not as a separate parameter.
	 *
	 *  <p>{@code req.to()} drives the target tile. If {@code req.to()}
	 *  is null (entity-only request), the planner currently returns
	 *  a failed path with {@link ReplanReason#TARGET_UNREACHABLE} —
	 *  entity resolution is Lane 5's job (V2Navigator resolves
	 *  EntityRef → WorldPoint before calling the planner). */
	public static V2Path plan(NavRequest req, WorldSnapshot snap, BfsConfig cfg)
	{
		if (req == null) throw new IllegalArgumentException("req null");
		if (snap == null) throw new IllegalArgumentException("snap null");
		if (cfg == null) cfg = BfsConfig.defaults();

		WorldPoint target = req.to();
		if (target == null)
		{
			log.warn("[nav-v2.planner] no target tile in request; returning TARGET_UNREACHABLE");
			return V2PathImpl.failed(ReplanReason.TARGET_UNREACHABLE);
		}
		// The planner needs a start tile. Per Lane 2's snapshot, the
		// player position is captured in PlayerState (varbits / etc).
		// Lane 5 will pass start as part of the NavigationRequest at
		// integration; for now we accept the start tile being absent
		// from NavRequest and require the caller to supply it via the
		// overload below.
		return plan(req, /*start*/ null, snap, cfg);
	}

	/** Internal entry that accepts an explicit start tile. Lane 5
	 *  callers use this; the standard 3-arg entry resolves to this
	 *  with start = null (rejected). */
	public static V2Path plan(NavRequest req, WorldPoint start, WorldSnapshot snap, BfsConfig cfg)
	{
		if (req == null) throw new IllegalArgumentException("req null");
		if (snap == null) throw new IllegalArgumentException("snap null");
		if (cfg == null) cfg = BfsConfig.defaults();
		WorldPoint target = req.to();
		if (target == null)
		{
			return V2PathImpl.failed(ReplanReason.TARGET_UNREACHABLE);
		}
		if (start == null)
		{
			return V2PathImpl.failed(ReplanReason.TARGET_UNREACHABLE);
		}

		// Build navigation + path context. PlayerState comes from the
		// snapshot — Lane 2 captures it alongside the world snapshot.
		PlayerState player = extractPlayerStateOrNull(snap);
		if (player == null)
		{
			// Without a player state we can't evaluate transport
			// requirements. Lane 5 always supplies this at integration;
			// for now we still attempt to plan (no requirement-gated
			// transports will pass the filter).
			player = stubPlayerState();
		}
		NavigationContext navCtx = new NavigationContextImpl(snap, player, req);
		PathContext pathCtx = PathContextImpl.planEntry(navCtx, cfg.routeSeed());

		TransportTable table = snap.transports();
		if (table == null)
		{
			log.warn("[nav-v2.planner] snapshot has no TransportTable; routing without transports");
			table = new TransportTable(Collections.emptyList(), 0);
		}

		// Step 1: high-level skeleton.
		LinkGraphDijkstra.SkeletonResult skel =
			LinkGraphDijkstra.findRouteSkeleton(navCtx, table, start, target);
		if (skel.status() == LinkGraphDijkstra.Status.UNREACHABLE)
		{
			log.info("[nav-v2.planner] skeleton unreachable {} → {} ({})", start, target,
				skel.reasonIfFailed());
			return V2PathImpl.failed(skel.reasonIfFailed());
		}

		// Step 2: walk-leg BFS expansions + transport leg construction.
		List<List<WalkStep>> walkLegs = new ArrayList<>();
		List<TransportLeg> transports = new ArrayList<>();
		List<WorldPoint> flatTileSequence = new ArrayList<>();
		List<PlaneTransition> planeJumps = new ArrayList<>();

		// Iterate skeleton nodes, planning each leg from the previous
		// "anchor" tile to the current.
		//
		// Output model: walks and transports alternate as
		//   walk_0, [transport_0, walk_1, transport_1, walk_2, ...]
		// where walk_i is the tile-level expansion of the walking
		// segment between the i-th and (i+1)-th transport nodes (or
		// from start to first transport / from last transport to target).
		//
		// Therefore: walkLegs.size() == transports.size() + 1.
		WorldPoint prevTile = null;
		List<LinkGraphDijkstra.SkeletonNode> nodes = skel.nodes();
		// Initial walk leg accumulator; flushed when we hit a transport
		// or finish.
		List<WorldPoint> currentLegTiles = new ArrayList<>();
		for (int i = 0; i < nodes.size(); i++)
		{
			LinkGraphDijkstra.SkeletonNode n = nodes.get(i);
			if (n.kind() == LinkGraphDijkstra.NodeKind.WALK)
			{
				WorldPoint walkTo = n.tile();
				if (prevTile == null)
				{
					// Seed the first walk leg with the start tile.
					currentLegTiles.add(walkTo);
					flatTileSequence.add(walkTo);
					prevTile = walkTo;
					continue;
				}
				// BFS leg from prevTile to walkTo; append to the current
				// walk-leg accumulator.
				SkretzoBfsKernel.BfsResult res = SkretzoBfsKernel.plan(
					snap.collisionView(), prevTile, walkTo, cfg,
					(TilePredicate) null, pathCtx);
				if (res.status() != SkretzoBfsKernel.Status.PATH_FOUND)
				{
					ReplanReason reason = (res.status() == SkretzoBfsKernel.Status.BUDGET_EXHAUSTED)
						? ReplanReason.EXECUTOR_TIMEOUT
						: ReplanReason.TARGET_UNREACHABLE;
					log.info("[nav-v2.planner] BFS leg {} → {} failed: {}", prevTile, walkTo, res.status());
					return V2PathImpl.failed(reason);
				}
				List<WorldPoint> tiles = res.tiles();
				// Skip the duplicate first tile (already in currentLegTiles).
				currentLegTiles.addAll(tiles.subList(1, tiles.size()));
				flatTileSequence.addAll(tiles.subList(1, tiles.size()));
				prevTile = walkTo;
			}
			else
			{
				// Transport node. Flush the current walk-leg accumulator
				// with TRANSPORT_APPROACH as the last anchor type, then
				// emit the transport leg.
				TransportLeg leg = n.transport().toLeg();
				List<WalkStep> compressed = PathCompressor.compressLeg(
					currentLegTiles,
					walkLegs.isEmpty() ? WaypointType.WALK : WaypointType.WALK,
					WaypointType.TRANSPORT_APPROACH);
				walkLegs.add(compressed);
				transports.add(leg);
				// Record plane jump for the validator.
				if (leg.from().getPlane() != leg.to().getPlane())
				{
					planeJumps.add(new PlaneTransition()
					{
						@Override public WorldPoint from() { return leg.from(); }
						@Override public WorldPoint to() { return leg.to(); }
					});
				}
				flatTileSequence.add(leg.to());
				prevTile = leg.to();
				// Start a fresh walk-leg accumulator at the transport's
				// destination.
				currentLegTiles = new ArrayList<>();
				currentLegTiles.add(leg.to());
			}
		}
		// Flush the final walk-leg accumulator with WALK as the last
		// anchor type.
		if (!currentLegTiles.isEmpty())
		{
			WaypointType lastType = WaypointType.WALK;
			List<WalkStep> compressed = PathCompressor.compressLeg(
				currentLegTiles, WaypointType.WALK, lastType);
			walkLegs.add(compressed);
		}

		// Step 3: validator over the flat tile sequence.
		RouteValidator.ValidationResult vr = RouteValidator.validate(
			flatTileSequence, snap.collisionView(),
			(TilePredicate) null,
			planeJumps, pathCtx);
		if (!vr.ok())
		{
			log.warn("[nav-v2.planner] route validation failed at step {}: {}",
				vr.firstFailureIndex(), vr.reason());
			return V2PathImpl.failed(ReplanReason.TARGET_UNREACHABLE);
		}

		// Step 4: assemble.
		List<PathStep> steps = PathCompressor.assemble(walkLegs, transports);
		log.info("[nav-v2.planner] plan ok: {} steps ({} walks + {} transports), cost ~{} ticks",
			steps.size(), walkLegs.stream().mapToInt(List::size).sum(),
			transports.size(), skel.totalCostTicks());
		return V2PathImpl.of(steps);
	}

	/** Extract a {@link PlayerState} from the snapshot. Lane 2's
	 *  WorldSnapshot returns predicates() as Object; some
	 *  implementations may also expose the player state via a hidden
	 *  field. Lane 4 falls back to a stub when unavailable. */
	private static PlayerState extractPlayerStateOrNull(WorldSnapshot snap)
	{
		// Lane 2's canonical snapshot doesn't yet expose a
		// player() accessor; integration will. For now, return null and
		// let the caller stub.
		return null;
	}

	private static PlayerState stubPlayerState()
	{
		return new PlayerState()
		{
			@Override public int skillLevel(net.runelite.api.Skill s) { return 1; }
			@Override public int boostedLevel(net.runelite.api.Skill s) { return 1; }
			@Override public int varbit(int id) { return 0; }
			@Override public int varplayer(int id) { return 0; }
			@Override public net.runelite.api.ItemContainer inventory() { return null; }
			@Override public net.runelite.api.ItemContainer equipment() { return null; }
			@Override public boolean isMember() { return false; }
		};
	}
}
