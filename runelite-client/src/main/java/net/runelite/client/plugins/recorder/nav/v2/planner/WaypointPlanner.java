package net.runelite.client.plugins.recorder.nav.v2.planner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.nav.NavRequest;
import net.runelite.client.plugins.recorder.nav.v2.bfs.BfsConfig;
import net.runelite.client.plugins.recorder.nav.v2.bfs.PlaneTransition;
import net.runelite.client.plugins.recorder.nav.v2.bfs.RouteValidator;
import net.runelite.client.plugins.recorder.nav.v2.bfs.SkretzoBfsKernel;
import net.runelite.client.plugins.recorder.nav.v2.bfs.TilePredicate;
import net.runelite.client.plugins.recorder.nav.v2.collision.PlayerState;
import net.runelite.client.plugins.recorder.nav.v2.collision.WorldSnapshot;
import net.runelite.client.plugins.recorder.nav.v2.predicate.NavigationContext;
import net.runelite.client.plugins.recorder.nav.v2.predicate.PathContext;
import net.runelite.client.plugins.recorder.nav.v2.predicate.PredicateRegistry;
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
 *    <li>{@link ReplanReason#REGION_NOT_LOADED} — snapshot has no
 *        player position (off-scene state).</li>
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

	/** Spec §3 signature. {@code start} is read from
	 *  {@link WorldSnapshot#playerPosition()} — Lane 4 manifest concern
	 *  #5 closed at integration by adding {@code playerPosition()} to
	 *  the snapshot interface (see commit history). */
	public static V2Path plan(NavRequest req, WorldSnapshot snap, BfsConfig cfg)
	{
		if (req == null) throw new IllegalArgumentException("req null");
		if (snap == null) throw new IllegalArgumentException("snap null");
		if (cfg == null) cfg = BfsConfig.defaults();

		WorldPoint start = snap.playerPosition();
		if (start == null)
		{
			log.info("[nav-v2.planner] snapshot has no playerPosition; REGION_NOT_LOADED");
			return V2PathImpl.failed(ReplanReason.REGION_NOT_LOADED);
		}
		return plan(req, start, snap, cfg);
	}

	/** 4-arg overload accepting an explicit start tile. Used by callers
	 *  that already know the start and don't want to round-trip through
	 *  {@link WorldSnapshot#playerPosition()} — e.g. replay tests. */
	public static V2Path plan(NavRequest req, WorldPoint start, WorldSnapshot snap, BfsConfig cfg)
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
		if (start == null)
		{
			return V2PathImpl.failed(ReplanReason.REGION_NOT_LOADED);
		}

		// Build navigation + path context. Snapshot owns the live PlayerState
		// (captured on the client thread at snapshot time); fall back to a
		// minimal stub when absent so unit-test fixtures using the legacy
		// fromComponents entry don't NPE.
		PlayerState player = snap.player();
		if (player == null)
		{
			player = stubPlayerState();
		}
		NavigationContext navCtx = new NavigationContextImpl(snap, player, req);
		PathContext pathCtx = PathContextImpl.planEntry(navCtx, cfg.routeSeed());

		// Adapt the canonical PredicateRegistry into the BFS-local
		// TilePredicate shape. The registry is empty when no script has
		// registered conditions and no built-ins are wired — in that case
		// we still pass the (always-accepting) adapter so callers don't
		// need to special-case null. Predicate evaluation needs the
		// canonical PathContext; we forward whatever ctx the kernel hands
		// us and skip predicate eval if the type doesn't match (defensive).
		final PredicateRegistry registry = snap.predicates();
		final TilePredicate bfsPredicate = (registry == null)
			? null
			: (tile, ctx) ->
		{
			if (registry.size() == 0) return true;
			if (!(ctx instanceof PathContext pc)) return true;
			return registry.accepts(tile, pc);
		};

		Object transportObj = snap.transports();
		TransportTable table;
		if (transportObj instanceof TransportTable)
		{
			table = (TransportTable) transportObj;
		}
		else
		{
			if (transportObj != null)
			{
				log.warn("[nav-v2.planner] snapshot.transports() returned {} (not TransportTable); routing without transports",
					transportObj.getClass().getName());
			}
			table = new TransportTable(Collections.emptyList(), 0);
		}

		// Lane 2's WorldSnapshot exposes Lane 3's narrow CollisionView
		// interface directly (single method `flagsAt`). The concrete
		// Lane 2 CollisionView class implements that interface, so the
		// snapshot's view is passed straight through.
		final net.runelite.client.plugins.recorder.nav.v2.bfs.CollisionView bfsView =
			snap.collisionView();

		// Step 1: high-level skeleton.
		// Pass snap.components() through so Dijkstra's walk-edge filter
		// rejects cross-component walks BFS would later prove infeasible
		// (the pen-fence failure mode). Null during the precompute window
		// at plugin start — Dijkstra falls back to collision-blind walks
		// in that case, matching pre-2026-05-17 behaviour.
		LinkGraphDijkstra.SkeletonResult skel =
			LinkGraphDijkstra.findRouteSkeleton(navCtx, table, start, target,
				snap.components());
		if (skel.status() == LinkGraphDijkstra.Status.UNREACHABLE)
		{
			log.info("[nav-v2.planner] skeleton unreachable {} → {} ({})", start, target,
				skel.reasonIfFailed());
			return V2PathImpl.failed(skel.reasonIfFailed());
		}

		// Step 2: walk-leg BFS expansions + transport leg construction.
		List<List<WalkStep>> walkLegs = new ArrayList<>();
		/** Per-walk-leg flat BFS tile sequences (full lists before compression).
		 *  Lane 5's executor consumes these via the shim — single-tile
		 *  walk legs would break {@code CanvasTilePicker.pickNextInTilesAfter}
		 *  which needs ≥2 tiles in the list. */
		List<List<WorldPoint>> walkLegFlatTiles = new ArrayList<>();
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
					bfsView, prevTile, walkTo, cfg,
					bfsPredicate, pathCtx);
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
				walkLegFlatTiles.add(List.copyOf(currentLegTiles));
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
			walkLegFlatTiles.add(List.copyOf(currentLegTiles));
		}

		// Step 3: validator over the flat tile sequence.
		RouteValidator.ValidationResult vr = RouteValidator.validate(
			flatTileSequence, bfsView,
			bfsPredicate,
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
		return V2PathImpl.of(steps, walkLegFlatTiles, transports);
	}

	/** Minimal {@link PlayerState} used only when {@link WorldSnapshot#player()}
	 *  returns null — i.e. test fixtures using the legacy 6-arg
	 *  {@code fromComponents} that don't capture a real player. Production
	 *  snapshots built via {@link
	 *  net.runelite.client.plugins.recorder.nav.v2.collision.WorldSnapshotBuilder#fromClient}
	 *  always carry a real {@link PlayerState}. */
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
