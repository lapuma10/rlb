package net.runelite.client.plugins.recorder.nav.v21;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.runelite.api.TileObject;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.nav.NavRequest;
import net.runelite.client.plugins.recorder.nav.NavStatus;
import net.runelite.client.plugins.recorder.nav.Navigator;
import net.runelite.client.plugins.recorder.nav.v2.bfs.CollisionView;
import net.runelite.client.plugins.recorder.trail.Trail;
import net.runelite.client.plugins.recorder.worldmap.TransportEdge;
import net.runelite.client.plugins.recorder.worldmap.TransportIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Reactive walker. Plans → acts → replans, every tick. No full-route
 *  validation, no Dijkstra skeleton, no precomputed transport graph.
 *
 *  <p><b>Tick priorities (post-Task-10):</b>
 *  <ol>
 *    <li>Snapshot world (multi-plane collision).</li>
 *    <li>Goal satisfied → ARRIVED (writes RouteSkeleton on success).</li>
 *    <li>Dispatcher busy → RUNNING.</li>
 *    <li>{@link ReactiveSolver#evaluatePending} → progressed/failed
 *        transitions, dead-end signal when applicable.</li>
 *    <li>HARD_STALL check.</li>
 *    <li>{@code pendingExit} (sticky perimeter exit) early-return.</li>
 *    <li>{@code pendingTransport} (sticky transport / anchor) early-return.</li>
 *    <li>Trail anchor rung — next reachable anchor adopted as
 *        pendingExit or pendingTransport.</li>
 *    <li>{@link StaticPlanner#plan}:
 *      <ul>
 *        <li>Success → walker.walkAlong path.</li>
 *        <li>BlockedEdge → {@link #handleBlockedEdge}.</li>
 *        <li>PlaneMismatch → {@link #handlePlaneMismatch}:
 *          router → next-anchor-on-current-plane → trail-corridor →
 *          planar projection → typed FAILED.</li>
 *        <li>BudgetExhausted → walker.walkAlong(pathToBestVisited).</li>
 *        <li>NoCandidate → FAILED.</li>
 *      </ul>
 *    </li>
 *  </ol>
 *
 *  <p>Sticky subtasks own their tick slot: while a pending subtask is
 *  active, the planner is not consulted. Pre-dispatch state is captured
 *  at the moment of dispatch (inside {@link #attemptStickyClick}) and
 *  preserved until the next solver outcome resolves — never overwritten
 *  per tick. */
public final class V21Navigator implements Navigator
{
	private static final Logger log = LoggerFactory.getLogger(V21Navigator.class);
	private static final String NAME = "reactive-v21";
	/** No-progress ticks before we give up. ~9 s at 600 ms/tick — long
	 *  enough for slow stair animations + cs2 dialogs, short enough
	 *  the bot doesn't spin forever. */
	private static final int HARD_STALL_TICKS = 15;
	/** Window for the inverse-transport heuristic: a transport that
	 *  fires within this many ms of its inverse (same object id, swapped
	 *  fromTile/toTile) marks the FIRST transport as dead-end for the
	 *  goal. 6 ticks × 600 ms = 3.6 s — long enough for an animation
	 *  to complete on the destination plane and us to click "back". */
	private static final long INVERSE_WINDOW_MS = 6L * 600L;

	private final V21Env env;
	private final WalkExecutor walker;
	private final ReactiveSolver solver;
	private final V21Diagnostics diag = new V21Diagnostics();

	@Nullable private NavRequest activeRequest;
	@Nullable private Goal activeGoal;
	@Nullable private WorldPoint lastPlayerTile;
	private int stallTicks;
	/** Perimeter exit chosen by {@link #handleBlockedEdge}. Kept across
	 *  ticks so the plane-mismatch fallback cannot overwrite the intent
	 *  and send the bot in the wrong direction between discovery and
	 *  interaction. Cleared on interaction dispatch, blacklist hit, or
	 *  new navigation request. */
	@Nullable private BlockerCandidate pendingExit;
	/** Sticky transport / plane-changing anchor click. The
	 *  {@link BlockerCandidate} is what gets dispatched — anchors and
	 *  router picks both reduce to {@code BlockerCandidate} for execution.
	 *  No synthetic {@link TransportEdge} ever lives here. */
	@Nullable private BlockerCandidate pendingTransport;
	@Nullable private TrailGuide trailGuide;
	private int nextAnchorIndex = 0;
	/** The anchor whose click is sticky right now. Null when
	 *  {@link #pendingTransport} or {@link #pendingExit} came from the
	 *  router or perimeter-exit ranker rather than the trail. */
	@Nullable private InteractionAnchor activeAnchor;
	/** The edge key of a router-picked pending transport. Null for
	 *  anchor-driven dispatches (TransportObserver may write the
	 *  matching edge after the click). Used by the PROGRESSED handler
	 *  to look up the fired edge. */
	@Nullable private String pendingEdgeKey;
	/** Last fired transport, retained across the PROGRESSED handler for
	 *  inverse-transport detection (forced reverse within {@code
	 *  INVERSE_WINDOW_MS} marks the first edge as dead-end). */
	@Nullable private TransportEdge lastFiredEdge;
	private long lastFiredAtMs;
	/** Pre-dispatch state captured atomically with the click in
	 *  {@link #attemptStickyClick}. Preserved across ticks until the
	 *  solver outcome resolves; then cleared. Reading these in the
	 *  PROGRESSED branch tells us what the world looked like BEFORE we
	 *  clicked, so we can detect plane changes correctly. */
	private int pendingStartPlane = -1;
	/** Captured at dispatch alongside pendingStartPlane. Currently unread —
	 *  reserved for future dead-end attribution / timing diagnostics. */
	@Nullable private WorldPoint pendingStartTile;
	/** See {@link #pendingStartTile}. */
	private long pendingStartedAtMs;
	/** Ordered edge keys of transports successfully fired during the
	 *  current navigation request. Written to a {@link RouteSkeleton}
	 *  on ARRIVED. Cleared on new request. */
	private final List<String> skeletonInProgress = new ArrayList<>();

	public V21Navigator(V21Env env)
	{
		this.env = env;
		this.walker = new WalkExecutor(env.dispatcher(), new Random());
		this.solver = new ReactiveSolver(env.dispatcher());
	}

	@Override
	public NavStatus tick(NavRequest request) throws InterruptedException
	{
		if (request == null) return NavStatus.FAILED;
		V21Env.Snapshot snap = env.snapshot();
		if (snap == null || snap.playerTile() == null)
		{
			diag.noPlayer();
			return NavStatus.FAILED;
		}

		// New request → reset reactive state. (Mode-change resets are
		// owned by HybridNavigator, which cancels us before swapping.)
		if (!Objects.equals(request, activeRequest))
		{
			diag.newRequest(request, activeRequest);
			activeRequest = request;
			activeGoal = adaptGoal(request);
			lastPlayerTile = null;
			stallTicks = 0;
			pendingExit = null;
			solver.reset();

			String trailName = (request != null) ? request.trailName() : null;
			Trail trail = (trailName != null) ? env.trails().byName(trailName) : null;
			trailGuide = (trail != null) ? TrailGuide.fromTrail(trail) : null;
			nextAnchorIndex = 0;
			activeAnchor = null;
			pendingTransport = null;
			pendingEdgeKey = null;
			lastFiredEdge = null;
			lastFiredAtMs = 0L;
			pendingStartPlane = -1;
			pendingStartTile = null;
			pendingStartedAtMs = 0L;
			skeletonInProgress.clear();
			if (trailGuide != null)
			{
				log.info("v21.guide: trail={} anchors={} corridor={}",
					trailName, trailGuide.anchors().size(), trailGuide.corridor().size());
			}
		}
		Goal goal = activeGoal;
		if (goal == null)
		{
			log.warn("v21: BAD_REQUEST {} — cannot adapt to Goal", request);
			return NavStatus.FAILED;
		}
		if (goal.isSatisfied(snap.playerTile()))
		{
			diag.arrived(goal, snap.playerTile());
			stallTicks = 0;
			if (!skeletonInProgress.isEmpty())
			{
				env.skeletons().recordSuccess(new RouteSkeleton(
					routeKey(),
					goal.centroid(),
					goal.centroid().getPlane(),
					List.copyOf(skeletonInProgress),
					snap.nowMs()));
				log.info("v21.skeleton: recorded {} edges for route={} goal={}",
					skeletonInProgress.size(), routeKey(), goal.centroid());
			}
			skeletonInProgress.clear();
			return NavStatus.ARRIVED;
		}

		// Don't trample an in-flight click. Solver-pending and walker
		// dispatch both feed the same HumanizedInputDispatcher.
		if (snap.dispatcherBusy()) return NavStatus.RUNNING;

		// Evaluate any pending reactive interaction first. The PROGRESSED
		// / FAILED branches read pendingStart{Plane,Tile,Ms} captured at
		// the dispatch tick by attemptStickyClick.
		ReactiveSolver.Outcome out = solver.evaluatePending(
			snap.playerTile(), snap.plane(), snap.nowMs());
		if (out == ReactiveSolver.Outcome.STILL_WAITING) return NavStatus.RUNNING;
		if (out == ReactiveSolver.Outcome.PROGRESSED)
		{
			boolean planeChanged = (pendingStartPlane != -1
				&& pendingStartPlane != snap.plane());
			if (planeChanged && isAnchorOrEdgePending())
			{
				// Resolve the actual fired edge so we can record it in
				// the skeleton + detect inverse-transport.
				String firedKey = (pendingEdgeKey != null) ? pendingEdgeKey
					: (activeAnchor != null
						? findEdgeKeyByObjectAndApproach(env.transports(),
							activeAnchor.objectId(),
							activeAnchor.approachTile(),
							activeAnchor.verb())
						: null);
				if (firedKey != null) skeletonInProgress.add(firedKey);

				// Re-check forward reachability: can ANY next step
				// (anchor or known transport) bring us closer to the
				// goal? If not, the transport we just fired was a
				// dead-end for this goal. Player landing on the goal
				// plane after the transport is a strong "yes, this
				// helped" — skip the dead-end mark in that case.
				boolean canContinue = (snap.plane() == goal.centroid().getPlane());
				if (!canContinue && trailGuide != null)
				{
					Optional<AnchorSelector.Active> nextActive =
						AnchorSelector.selectActive(
							trailGuide, nextAnchorIndex,
							snap.playerTile(),
							(start, g) -> new StaticPlanner(snap.collision()).plan(start, g),
							a -> env.deadEnds().isDeadEnd(
								GoalDeadEndKey.fromAnchor(routeKey(), a, goal.centroid()),
								snap.nowMs()),
							this::findInSceneOnClient);
					if (nextActive.isPresent()) canContinue = true;
				}
				if (!canContinue)
				{
					Predicate<TransportEdge> isEdgeBad =
						buildEdgeBlacklistPredicate(snap, goal, routeKey());
					Optional<TransportCandidate> tc = TransportRouter.findNext(
						snap.playerTile(), goal.centroid(),
						env.transports(), snap.collisionByPlane(),
						this::findInSceneOnClient, isEdgeBad);
					if (tc.isPresent()) canContinue = true;
				}
				if (!canContinue)
				{
					markDeadEndForFiredTransport(snap.nowMs(),
						"DESTINATION_NO_KNOWN_PROGRESS");
				}

				// Inverse-transport detection: forced reverse within
				// INVERSE_WINDOW_MS marks the FIRST transport as bad.
				TransportEdge fired = (firedKey != null)
					? env.transports().byKey(firedKey) : null;
				if (fired != null && lastFiredEdge != null
					&& snap.nowMs() - lastFiredAtMs < INVERSE_WINDOW_MS
					&& fired.fromTile().equals(lastFiredEdge.toTile())
					&& fired.toTile().equals(lastFiredEdge.fromTile())
					&& fired.objectId() == lastFiredEdge.objectId())
				{
					env.deadEnds().markDeadEnd(
						GoalDeadEndKey.fromEdge(routeKey(), lastFiredEdge, goal.centroid()),
						"FORCED_INVERSE_WITHIN_6_TICKS",
						snap.nowMs());
					log.info("v21.deadend: FORCED_INVERSE on {} (just fired inverse {})",
						lastFiredEdge.key(), fired.key());
				}

				// Advance anchor pointer if an anchor was active.
				if (activeAnchor != null) nextAnchorIndex++;
				activeAnchor = null;
				pendingTransport = null;
				pendingExit = null;
				pendingEdgeKey = null;
				lastFiredEdge = fired;
				lastFiredAtMs = snap.nowMs();
				pendingStartPlane = -1;
				pendingStartTile = null;
				pendingStartedAtMs = 0L;
			}
			else
			{
				// PROGRESSED on a same-plane interaction (door / gate /
				// route walk) — clear sticky and replan.
				pendingTransport = null;
				pendingExit = null;
				activeAnchor = null;
				pendingEdgeKey = null;
				pendingStartPlane = -1;
				pendingStartTile = null;
				pendingStartedAtMs = 0L;
			}
		}
		else if (out == ReactiveSolver.Outcome.FAILED)
		{
			// Solver already short-blacklisted. Clear sticky state; do
			// NOT mark dead-end here — that's the long-term layer and
			// shouldn't fire on every solver miss.
			pendingTransport = null;
			pendingExit = null;
			activeAnchor = null;
			pendingEdgeKey = null;
			pendingStartPlane = -1;
			pendingStartTile = null;
			pendingStartedAtMs = 0L;
		}

		// Progress accounting.
		boolean progressed = lastPlayerTile != null
			&& !lastPlayerTile.equals(snap.playerTile());
		if (progressed || out == ReactiveSolver.Outcome.PROGRESSED)
		{
			stallTicks = 0;
		}
		else
		{
			stallTicks++;
		}
		lastPlayerTile = snap.playerTile();

		if (stallTicks >= HARD_STALL_TICKS)
		{
			diag.hardStall(stallTicks, goal, snap.playerTile());
			return NavStatus.FAILED;
		}

		// If we already committed to a specific perimeter exit, continue
		// toward it rather than re-running the full planner pipeline.
		// This prevents plane-mismatch / blocked-edge logic from
		// overwriting the intent on subsequent ticks.
		if (pendingExit != null)
		{
			NavStatus s = handlePendingExit(pendingExit, snap);
			if (s != null) return s;
			// null → exit was cleared (blacklisted / unreachable); fall
			// through to full replan.
		}

		// Sticky transport / anchor click.
		if (pendingTransport != null)
		{
			NavStatus s = handlePendingTransport(pendingTransport, snap);
			if (s != null) return s;
			// null → pending was cleared (blacklisted / dead-end / can't
			// plan); fall through.
		}

		// Trail anchor rung. In-order, reachability-checked. Sits AFTER
		// both pending early-returns and BEFORE the planner call.
		if (trailGuide != null && pendingTransport == null && pendingExit == null)
		{
			final V21Env.Snapshot snapRef = snap;
			final Goal goalRef = goal;
			Optional<AnchorSelector.Active> picked = AnchorSelector.selectActive(
				trailGuide, nextAnchorIndex, snap.playerTile(),
				(start, g) -> new StaticPlanner(snapRef.collision()).plan(start, g),
				a -> env.deadEnds().isDeadEnd(
					GoalDeadEndKey.fromAnchor(routeKey(), a, goalRef.centroid()),
					snapRef.nowMs()),
				this::findInSceneOnClient);
			if (picked.isPresent())
			{
				InteractionAnchor a = picked.get().anchor();
				TileObject obj = picked.get().sceneObject();
				BlockerCandidate bc = new BlockerCandidate(obj, a.verb(), snap.playerTile());
				activeAnchor = a;
				if (!a.isTransportAnchor())
				{
					pendingExit = bc;
					log.info("v21.anchor: adopt local-anchor idx={} objectId={} verb={} at={}",
						picked.get().indexInGuide(), a.objectId(), a.verb(), a.objectTile());
					NavStatus s = handlePendingExit(pendingExit, snap);
					return (s != null) ? s : NavStatus.RUNNING;
				}
				else
				{
					pendingTransport = bc;
					// pendingEdgeKey stays null — anchor may or may not
					// correspond to an index edge. The PROGRESSED handler
					// resolves the actual fired edge via TransportObserver/index.
					log.info("v21.anchor: adopt transport-anchor idx={} objectId={} verb={} at={}",
						picked.get().indexInGuide(), a.objectId(), a.verb(), a.objectTile());
					NavStatus s = handlePendingTransport(pendingTransport, snap);
					return (s != null) ? s : NavStatus.RUNNING;
				}
			}
		}

		// Re-plan from current position.
		PlanResult plan = new StaticPlanner(snap.collision()).plan(snap.playerTile(), goal);
		diag.plan(snap.playerTile(), goal, plan);

		if (plan instanceof PlanResult.Success s)
		{
			walker.walkAlong(s.tiles(), snap.playerTile());
			return NavStatus.RUNNING;
		}
		if (plan instanceof PlanResult.BlockedEdge be)
		{
			// Diagnostic: when BFS reports the BlockedEdge AT the start
			// tile with a path of length 1, BFS didn't expand at all.
			// That means every direction from the player tile is
			// canMove=false. Either the bot is genuinely wedged (rare)
			// or LiveCollisionView is lying. Log canMove results so we
			// can tell which.
			if (be.from().equals(snap.playerTile()) && be.pathToFrom().size() == 1)
			{
				logCanMoveAroundStart(snap);
			}
			return handleBlockedEdge(be, snap);
		}
		if (plan instanceof PlanResult.PlaneMismatch)
		{
			return handlePlaneMismatch(snap);
		}
		if (plan instanceof PlanResult.BudgetExhausted bx)
		{
			List<WorldPoint> path = bx.pathToBestVisited();
			if (path == null || path.size() <= 1
				|| path.get(path.size() - 1).equals(snap.playerTile()))
			{
				log.warn("v21: BUDGET_EXHAUSTED_NO_PROGRESS expanded={} player={} goal={}",
					bx.expanded(), snap.playerTile(), goal.centroid());
				return NavStatus.FAILED;
			}
			walker.walkAlong(path, snap.playerTile());
			return NavStatus.RUNNING;
		}
		// PlanResult.NoCandidate or unknown — fail fast.
		return NavStatus.FAILED;
	}

	/** Dump canMove for all 8 directions from {@code snap.playerTile}.
	 *  Fires when BFS reports a BlockedEdge at the start tile — the
	 *  symptom of "BFS thinks the bot is surrounded by walls." Either
	 *  the bot really is wedged (e.g. on a fence corner with diagonal
	 *  blocks) or the captured collision is wrong. The log line tells
	 *  us which. */
	private void logCanMoveAroundStart(V21Env.Snapshot snap)
	{
		int[][] steps = {
			{-1,  0}, { 1,  0}, { 0, -1}, { 0,  1},
			{-1, -1}, { 1, -1}, {-1,  1}, { 1,  1}
		};
		String[] names = { "W", "E", "S", "N", "SW", "SE", "NW", "NE" };
		StringBuilder sb = new StringBuilder();
		sb.append("CANMOVE-FROM-START at ").append(snap.playerTile()).append(": ");
		for (int i = 0; i < steps.length; i++)
		{
			int dx = steps[i][0], dy = steps[i][1];
			boolean ok = net.runelite.client.plugins.recorder.nav.v2.bfs.SkretzoBfsKernel
				.canMove(snap.collision(), snap.playerTile().getX(), snap.playerTile().getY(),
					snap.plane(), dx, dy);
			sb.append(names[i]).append("=").append(ok ? "Y" : "N").append(" ");
		}
		log.warn("v21: {}", sb.toString());
	}

	private NavStatus handleBlockedEdge(PlanResult.BlockedEdge be, V21Env.Snapshot snap) throws InterruptedException
	{
		// 1. Walk to source side of blocked edge if we're not there.
		//    The edge may be 30-50+ tiles away (the cow-pen south gate
		//    from Lumbridge bank, for example). A direct walkTo on
		//    those tiles fails because the canvas pixel is off-screen.
		//    Use the BFS path the planner returned and let WalkExecutor
		//    pick a 6-9-tile lookahead, same as the Success case.
		if (!snap.playerTile().equals(be.from()))
		{
			walker.walkAlong(be.pathToFrom(), snap.playerTile());
			return NavStatus.RUNNING;
		}
		// 2. Find a blocker on the edge. Strict first, widen if null.
		BlockerCandidate b = env.onClient(() ->
		{
			BlockerCandidate strict = env.scanner().findOnEdge(be.from(), be.to());
			return strict != null ? strict : env.scanner().findNearEdge(be.from(), be.to());
		});
		// 3. If no blocker on the specific edge, the reported edge is
		//    just a fence — the bot is inside a reachable component
		//    whose actual exit is on the perimeter elsewhere.
		//    `findPerimeterExits` scores by Chebyshev to the active
		//    goal's centroid, so an exit toward the goal ranks before
		//    an exit pointing away. Generic: chicken pen, cow pen,
		//    bank interior, courtyard, room — same primitive.
		if (b == null)
		{
			Goal currentGoal = activeGoal;
			WorldPoint goalCentroid = currentGoal != null
				? currentGoal.centroid() : be.from();
			List<WorldPoint> corridor = (trailGuide != null) ? trailGuide.corridor() : null;
			List<BlockerCandidate> exits = env.onClient(() ->
				env.scanner().findPerimeterExits(snap.playerTile(), goalCentroid,
					BlockerScanner.SCENE_SCAN_RADIUS, snap.collision(), corridor));
			BlockerCandidate exit = null;
			for (BlockerCandidate cand : exits)
			{
				BlockerCandidate atHere = new BlockerCandidate(
					cand.object(), cand.verb(), snap.playerTile());
				if (!solver.isBlacklisted(atHere, snap.nowMs()))
				{
					exit = cand;
					break;
				}
			}
			if (exit == null)
			{
				diag.blockerNotFound(be.from(), be.to());
				return NavStatus.RUNNING;
			}
			// Store as sticky subtask so subsequent ticks continue toward
			// this exit instead of restarting the full planner pipeline.
			pendingExit = exit;
			NavStatus exitStatus = handlePendingExit(exit, snap);
			return exitStatus != null ? exitStatus : NavStatus.RUNNING;
		}
		if (solver.isBlacklisted(b, snap.nowMs()))
		{
			log.info("v21: blocker {} blacklisted — waiting for TTL or alternate path",
				b.blacklistKey());
			return NavStatus.RUNNING;
		}
		diag.blockerFound(be.from(), be.to(), b);
		attemptStickyClick(b, snap);
		return NavStatus.RUNNING;
	}

	/** Walk toward or click the sticky perimeter exit.
	 *
	 *  <p>Returns RUNNING when a walk or click was dispatched. Returns
	 *  null when the exit is no longer actionable (blacklisted or
	 *  unreachable) — caller should fall through to a full replan. */
	@Nullable
	private NavStatus handlePendingExit(BlockerCandidate exit, V21Env.Snapshot snap)
		throws InterruptedException
	{
		// Re-build with current player tile so the blacklist key is accurate
		// for the approach direction right now.
		BlockerCandidate atHere = new BlockerCandidate(
			exit.object(), exit.verb(), snap.playerTile());
		if (solver.isBlacklisted(atHere, snap.nowMs()))
		{
			log.info("v21: pendingExit {} blacklisted — clearing", exit.objectTile());
			pendingExit = null;
			return null;
		}

		int dist = Math.max(
			Math.abs(snap.playerTile().getX() - exit.objectTile().getX()),
			Math.abs(snap.playerTile().getY() - exit.objectTile().getY()));

		if (dist > 2)
		{
			Goal subGoal = new Goal.Area(exit.objectTile(), 1);
			PlanResult sub = new StaticPlanner(snap.collision())
				.plan(snap.playerTile(), subGoal);
			if (sub instanceof PlanResult.Success s)
			{
				log.debug("v21: pendingExit walk → {} (dist={})", exit.objectTile(), dist);
				walker.walkAlong(s.tiles(), snap.playerTile());
				return NavStatus.RUNNING;
			}
			log.warn("v21: cannot plan to pendingExit {} — clearing", exit.objectTile());
			pendingExit = null;
			return null;
		}

		// Within interaction range — click.
		log.info("v21: pendingExit dist={} — clicking verb={} at {}",
			dist, exit.verb(), exit.objectTile());
		attemptStickyClick(atHere, snap);
		pendingExit = null;
		return NavStatus.RUNNING;
	}

	/** Walk toward or click the sticky transport / plane-changing anchor.
	 *  Returns RUNNING when a walk or click was dispatched. Returns null
	 *  when the pending is no longer actionable — caller falls through. */
	@Nullable
	private NavStatus handlePendingTransport(BlockerCandidate t, V21Env.Snapshot snap)
		throws InterruptedException
	{
		Goal goal = activeGoal;
		if (goal == null)
		{
			pendingTransport = null;
			pendingEdgeKey = null;
			activeAnchor = null;
			return null;
		}
		// Re-build with current player tile for blacklist key accuracy.
		BlockerCandidate atHere = new BlockerCandidate(
			t.object(), t.verb(), snap.playerTile());
		if (solver.isBlacklisted(atHere, snap.nowMs()))
		{
			log.info("v21: pendingTransport {} blacklisted — clearing", t.objectTile());
			pendingTransport = null;
			pendingEdgeKey = null;
			return null;
		}
		// Anchor-dead-end check.
		if (activeAnchor != null
			&& env.deadEnds().isDeadEnd(
				GoalDeadEndKey.fromAnchor(routeKey(), activeAnchor, goal.centroid()),
				snap.nowMs()))
		{
			log.info("v21: pendingTransport anchor objectId={} dead-end — clearing",
				activeAnchor.objectId());
			pendingTransport = null;
			activeAnchor = null;
			return null;
		}
		// Edge-dead-end check (router-driven pending).
		if (pendingEdgeKey != null)
		{
			TransportEdge e = env.transports().byKey(pendingEdgeKey);
			if (e != null && env.deadEnds().isDeadEnd(
					GoalDeadEndKey.fromEdge(routeKey(), e, goal.centroid()),
					snap.nowMs()))
			{
				log.info("v21: pendingTransport edge {} dead-end — clearing", pendingEdgeKey);
				pendingTransport = null;
				pendingEdgeKey = null;
				return null;
			}
		}

		int dist = Math.max(
			Math.abs(snap.playerTile().getX() - t.objectTile().getX()),
			Math.abs(snap.playerTile().getY() - t.objectTile().getY()));

		if (dist > 2)
		{
			Goal subGoal = new Goal.Area(t.objectTile(), 1);
			PlanResult sub = new StaticPlanner(snap.collision())
				.plan(snap.playerTile(), subGoal);
			if (sub instanceof PlanResult.Success s)
			{
				log.debug("v21: pendingTransport walk → {} (dist={}, strict)",
					t.objectTile(), dist);
				walker.walkAlongStrict(s.tiles(), snap.playerTile());
				return NavStatus.RUNNING;
			}
			if (sub instanceof PlanResult.BlockedEdge be)
			{
				return handleBlockedEdge(be, snap);
			}
			// NoCandidate / PlaneMismatch / BudgetExhausted at this scale
			// — give up the pending, let the main tick replan.
			log.warn("v21: cannot plan to pendingTransport {} (sub={}) — clearing",
				t.objectTile(), sub.getClass().getSimpleName());
			pendingTransport = null;
			pendingEdgeKey = null;
			return null;
		}

		// Within interaction range — click.
		log.info("v21: pendingTransport dist={} — clicking verb={} at {}",
			dist, t.verb(), t.objectTile());
		attemptStickyClick(atHere, snap);
		return NavStatus.RUNNING;
	}

	/** Five-rung plane-mismatch handler:
	 *  <ol>
	 *    <li>TransportRouter on known-edge graph.</li>
	 *    <li>Next trail anchor on player's plane (in trail order).</li>
	 *    <li>Trail-corridor tile on player's plane closest to next anchor /
	 *        trail terminus.</li>
	 *    <li>Planar projection of goal centroid on player plane.</li>
	 *    <li>Typed FAILED — {@code NO_KNOWN_TRANSPORT_ROUTE_TO_GOAL}.</li>
	 *  </ol> */
	private NavStatus handlePlaneMismatch(V21Env.Snapshot snap) throws InterruptedException
	{
		diag.planeMismatchScanning(snap.playerTile());
		Goal goal = activeGoal;
		if (goal == null) return NavStatus.FAILED;
		String rk = routeKey();

		// 1. TransportRouter on the known graph.
		Predicate<TransportEdge> isEdgeBad = buildEdgeBlacklistPredicate(snap, goal, rk);
		Optional<TransportCandidate> next = TransportRouter.findNext(
			snap.playerTile(), goal.centroid(),
			env.transports(), snap.collisionByPlane(),
			this::findInSceneOnClient, isEdgeBad);
		if (next.isPresent())
		{
			TransportCandidate tc = next.get();
			pendingTransport = tc.executable();
			pendingEdgeKey = tc.edge().key();
			log.info("v21.transport: chosen edge {} chain={} cost={}",
				pendingEdgeKey, tc.chainLength(), tc.estimatedTotalCost());
			NavStatus s = handlePendingTransport(pendingTransport, snap);
			return (s != null) ? s : NavStatus.RUNNING;
		}

		// 2. Trail-guided same-plane progression toward the next anchor
		//    in trail order whose approachTile is on the current plane.
		if (trailGuide != null)
		{
			InteractionAnchor sameplane = nextAnchorOnCurrentPlane(
				trailGuide, nextAnchorIndex, snap.plane());
			if (sameplane != null)
			{
				log.info("v21.fallback: walking toward next-anchor-on-plane idx-from={} "
						+ "objectId={} approach={}",
					nextAnchorIndex, sameplane.objectId(), sameplane.approachTile());
				NavStatus s = walkTowardSubGoal(snap, sameplane.approachTile());
				if (s != null) return s;
			}
		}

		// 3. Trail-corridor fallback on player's plane.
		if (trailGuide != null)
		{
			WorldPoint corridorTarget = trailCorridorTargetOnCurrentPlane(
				trailGuide, nextAnchorIndex, snap.playerTile(),
				goal.centroid(), snap.collision());
			if (corridorTarget != null)
			{
				log.info("v21.fallback: walking toward corridor tile {}", corridorTarget);
				NavStatus s = walkTowardSubGoal(snap, corridorTarget);
				if (s != null) return s;
			}
		}

		// 4. Planar projection of goal centroid (last resort before
		//    failure). Project the goal's xy onto the current plane.
		WorldPoint planarTarget = new WorldPoint(
			goal.centroid().getX(), goal.centroid().getY(), snap.plane());
		log.info("v21.fallback: planar projection toward {}", planarTarget);
		NavStatus s = walkTowardSubGoal(snap, planarTarget);
		if (s != null) return s;

		// 5. Typed FAILED.
		log.warn("v21: NO_KNOWN_TRANSPORT_ROUTE_TO_GOAL player={} goal={} routeKey={}",
			snap.playerTile(), goal.centroid(), rk);
		return NavStatus.FAILED;
	}

	/** Plan and walk one step toward an intermediate sub-goal. Returns
	 *  RUNNING on a successful dispatch, null if the planner had no
	 *  workable answer (caller falls through to the next rung). */
	@Nullable
	private NavStatus walkTowardSubGoal(V21Env.Snapshot snap, WorldPoint sub)
		throws InterruptedException
	{
		PlanResult pr = new StaticPlanner(snap.collision())
			.plan(snap.playerTile(), new Goal.Area(sub, 1));
		if (pr instanceof PlanResult.Success ps)
		{
			walker.walkAlong(ps.tiles(), snap.playerTile());
			return NavStatus.RUNNING;
		}
		if (pr instanceof PlanResult.BlockedEdge be)
		{
			return handleBlockedEdge(be, snap);
		}
		if (pr instanceof PlanResult.BudgetExhausted bx)
		{
			List<WorldPoint> path = bx.pathToBestVisited();
			if (path != null && path.size() > 1
				&& !path.get(path.size() - 1).equals(snap.playerTile()))
			{
				walker.walkAlong(path, snap.playerTile());
				return NavStatus.RUNNING;
			}
		}
		// PlanResult.NoCandidate, PlaneMismatch, or BudgetExhausted with
		// no improvement — let the caller try the next fallback rung.
		return null;
	}

	/** Adapt {@link NavRequest} into a {@link Goal}. Round-1 supports
	 *  point destinations only; entity-only requests fail so
	 *  HybridNavigator's V21_WITH_V1_FALLBACK mode can hand off to V1.
	 *
	 *  <p>The default 1-tile slop ({@link Goal.Area} radius=1) is the
	 *  point: most scripts that hard-coded an exact tile don't actually
	 *  require it. Scripts that DO need an exact tile should route via
	 *  a dedicated factory (added in round 2). */
	@Nullable
	private static Goal adaptGoal(NavRequest req)
	{
		WorldPoint to = req.to();
		if (to != null) return new Goal.Area(to, 1);
		return null;  // entity-only or trail-only — defer to round 2
	}

	// ─── helpers ────────────────────────────────────────────────────

	/** The active request's trail name, or null when no trail is bound
	 *  to this navigation. Used as the {@code routeKey} for goal-aware
	 *  dead-end + skeleton entries. */
	@Nullable
	private String routeKey()
	{
		return (activeRequest != null) ? activeRequest.trailName() : null;
	}

	/** Marshal a scene lookup onto the client thread. The router /
	 *  selector run on the worker thread; scanning the scene reads
	 *  {@code Scene} which is client-thread only. */
	@Nullable
	private TileObject findInSceneOnClient(int objectId, WorldPoint near, int radius)
	{
		return env.onClient(() -> env.scanner().findObjectInScene(objectId, near, radius));
	}

	/** Capture pre-dispatch state and dispatch the click. Single capture
	 *  site so the {@code pendingStart*} fields are guaranteed atomic
	 *  with the dispatch — never overwritten on subsequent ticks until
	 *  the solver outcome resolves. */
	private void attemptStickyClick(BlockerCandidate b, V21Env.Snapshot snap)
	{
		pendingStartPlane = snap.plane();
		pendingStartTile = snap.playerTile();
		pendingStartedAtMs = snap.nowMs();
		solver.attempt(b, snap.playerTile(), snap.nowMs());
	}

	/** True iff a sticky anchor click or a router-picked edge click is
	 *  currently pending — used to gate the PROGRESSED handler's
	 *  dead-end / skeleton signal so plain perimeter-exit progress
	 *  doesn't write to the transport-only lanes. */
	private boolean isAnchorOrEdgePending()
	{
		return activeAnchor != null || pendingEdgeKey != null;
	}

	/** Predicate the router and selectors use to skip edges. Short-term
	 *  blacklisted edges (the solver's 20s TTL) and long-term goal-aware
	 *  dead-end edges both return true. */
	private Predicate<TransportEdge> buildEdgeBlacklistPredicate(
		V21Env.Snapshot snap, Goal goal, @Nullable String rk)
	{
		final long nowMs = snap.nowMs();
		final WorldPoint goalCentroid = goal.centroid();
		return e ->
		{
			BlockerCandidate atHere = blockerForEdge(e, snap.playerTile());
			if (atHere != null && solver.isBlacklisted(atHere, nowMs)) return true;
			return env.deadEnds().isDeadEnd(
				GoalDeadEndKey.fromEdge(rk, e, goalCentroid), nowMs);
		};
	}

	/** Build a {@link BlockerCandidate} for the given edge by finding
	 *  its in-scene object. Returns null when the object is not visible
	 *  right now (legitimate when the scene doesn't cover the edge's
	 *  fromTile). Caller treats null as "skip this edge for now." */
	@Nullable
	private BlockerCandidate blockerForEdge(TransportEdge e, WorldPoint playerTile)
	{
		TileObject obj = findInSceneOnClient(e.objectId(), e.fromTile(),
			TransportRouter.SCENE_PROBE_RADIUS);
		if (obj == null) return null;
		return new BlockerCandidate(obj, e.verb(), playerTile);
	}

	/** Look up an edge matching {@code (approach tile, verb, objectId)}
	 *  in the index. Returns the matched edge's {@link
	 *  TransportEdge#key()} or null when none exists. Used by the
	 *  PROGRESSED handler to identify which real edge an anchor click
	 *  just exercised. */
	@Nullable
	private String findEdgeKeyByObjectAndApproach(TransportIndex idx,
		int objectId, WorldPoint approach, String verb)
	{
		if (idx == null || approach == null || verb == null) return null;
		for (TransportEdge e : idx.getOutgoing(approach))
		{
			if (e.objectId() == objectId && verb.equalsIgnoreCase(e.verb()))
			{
				return e.key();
			}
		}
		return null;
	}

	/** Mark the just-fired transport as goal-aware dead-end. Builds the
	 *  key from {@link #activeAnchor} when present (anchor-driven
	 *  dispatch), otherwise from the edge identified by
	 *  {@link #pendingEdgeKey} (router-driven). No-op when neither is
	 *  available (shouldn't happen — caller gates on
	 *  {@link #isAnchorOrEdgePending}). */
	private void markDeadEndForFiredTransport(long nowMs, String reason)
	{
		WorldPoint goalCentroid = (activeGoal != null) ? activeGoal.centroid() : null;
		if (goalCentroid == null) return;
		String rk = routeKey();
		if (activeAnchor != null)
		{
			env.deadEnds().markDeadEnd(
				GoalDeadEndKey.fromAnchor(rk, activeAnchor, goalCentroid),
				reason, nowMs);
			log.info("v21.deadend: anchor objectId={} verb={} reason={}",
				activeAnchor.objectId(), activeAnchor.verb(), reason);
			return;
		}
		if (pendingEdgeKey != null)
		{
			TransportEdge e = env.transports().byKey(pendingEdgeKey);
			if (e != null)
			{
				env.deadEnds().markDeadEnd(
					GoalDeadEndKey.fromEdge(rk, e, goalCentroid),
					reason, nowMs);
				log.info("v21.deadend: edge {} reason={}", pendingEdgeKey, reason);
			}
		}
	}

	/** Iterate {@code anchors.subList(fromIndex, size)} and return the
	 *  first anchor whose {@code approachTile.plane == plane}, or null
	 *  if none. Used by the plane-mismatch fallback to walk toward the
	 *  next reachable anchor on the current plane in trail order. */
	@Nullable
	private static InteractionAnchor nextAnchorOnCurrentPlane(TrailGuide g,
		int fromIdx, int plane)
	{
		if (g == null) return null;
		List<InteractionAnchor> all = g.anchors();
		for (int i = fromIdx; i < all.size(); i++)
		{
			InteractionAnchor a = all.get(i);
			if (a.approachTile().getPlane() == plane) return a;
		}
		return null;
	}

	/** Choose a corridor tile on the player's plane to walk toward when
	 *  no anchor remains on this plane. Picks the tile closest to the
	 *  next anchor's approachTile (or to the goal centroid if past the
	 *  last anchor), subject to a {@code chebyshev(player, t) ≤ 32}
	 *  practical-walk-range cap. Returns null if nothing reachable.
	 *
	 *  <p>Cost: O(N) corridor scan + a single BFS for the chosen tile. */
	@Nullable
	private static WorldPoint trailCorridorTargetOnCurrentPlane(TrailGuide g,
		int fromIdx, WorldPoint player, WorldPoint goalCentroid, CollisionView col)
	{
		if (g == null || player == null) return null;
		List<InteractionAnchor> anchors = g.anchors();
		WorldPoint referenceTile;
		if (fromIdx < anchors.size())
		{
			referenceTile = anchors.get(fromIdx).approachTile();
		}
		else
		{
			referenceTile = goalCentroid;
		}
		if (referenceTile == null) return null;

		WorldPoint best = null;
		int bestDistToRef = Integer.MAX_VALUE;
		for (WorldPoint t : g.corridor())
		{
			if (t == null) continue;
			if (t.getPlane() != player.getPlane()) continue;
			if (t.equals(player)) continue;
			int dPlayer = chebyshev(player, t);
			if (dPlayer > 32) continue;
			int dRef = chebyshev(t, referenceTile);
			if (dRef < bestDistToRef)
			{
				bestDistToRef = dRef;
				best = t;
			}
		}
		if (best == null) return null;
		PlanResult p = new StaticPlanner(col).plan(player, new Goal.Area(best, 1));
		return (p instanceof PlanResult.Success) ? best : null;
	}

	private static int chebyshev(WorldPoint a, WorldPoint b)
	{
		return Math.max(Math.abs(a.getX() - b.getX()),
			Math.abs(a.getY() - b.getY()));
	}

	@Override
	public void cancel()
	{
		diag.cancelled();
		activeRequest = null;
		activeGoal = null;
		lastPlayerTile = null;
		stallTicks = 0;
		pendingExit = null;
		pendingTransport = null;
		pendingEdgeKey = null;
		activeAnchor = null;
		lastFiredEdge = null;
		lastFiredAtMs = 0L;
		pendingStartPlane = -1;
		pendingStartTile = null;
		pendingStartedAtMs = 0L;
		trailGuide = null;
		nextAnchorIndex = 0;
		skeletonInProgress.clear();
		solver.reset();
	}

	@Override
	public boolean isBusy() { return activeRequest != null; }

	@Override
	public String name() { return NAME; }
}
