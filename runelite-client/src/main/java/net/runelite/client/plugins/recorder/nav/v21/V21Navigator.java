package net.runelite.client.plugins.recorder.nav.v21;

import java.util.List;
import java.util.Objects;
import java.util.Random;
import javax.annotation.Nullable;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.nav.NavRequest;
import net.runelite.client.plugins.recorder.nav.NavStatus;
import net.runelite.client.plugins.recorder.nav.Navigator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Reactive walker. Plans → acts → replans, every tick. No full-route
 *  validation, no Dijkstra skeleton, no precomputed transport graph.
 *
 *  <p><b>Tick loop:</b>
 *  <ol>
 *    <li>Snapshot world (player tile, plane, dispatcher-busy, live
 *        collision for player's plane).</li>
 *    <li>If goal already satisfied → ARRIVED.</li>
 *    <li>If a reactive interaction is in flight, evaluate whether it
 *        produced progress. PROGRESSED ⇒ clear pending, fall through
 *        to replan. FAILED ⇒ blacklist, fall through. STILL_WAITING
 *        ⇒ yield RUNNING.</li>
 *    <li>Plan with {@link StaticPlanner} from current tile to goal.
 *        Success → walk along the path. BlockedEdge → walk to source
 *        side, then scan for unblocking object on the edge and click.
 *        PlaneMismatch → scan around player for a Climb verb and
 *        click. BudgetExhausted → walk toward centroid, replan next
 *        tick. NoCandidate / HARD_STALL → FAILED.</li>
 *  </ol>
 *
 *  <p><b>Why this is different from v2:</b> v2 proves a full route
 *  before any click. If a door is missing from the static transport
 *  table, the route is unreachable. v2.1 reads the door from the live
 *  scene at the moment BFS reports a blocked edge — no static data
 *  needed. Doors are runtime problems first, data second. */
public final class V21Navigator implements Navigator
{
	private static final Logger log = LoggerFactory.getLogger(V21Navigator.class);
	private static final String NAME = "reactive-v21";
	/** No-progress ticks before we give up. ~9 s at 600 ms/tick — long
	 *  enough for slow stair animations + cs2 dialogs, short enough
	 *  the bot doesn't spin forever. */
	private static final int HARD_STALL_TICKS = 15;

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
			return NavStatus.ARRIVED;
		}

		// Don't trample an in-flight click. Solver-pending and walker
		// dispatch both feed the same HumanizedInputDispatcher.
		if (snap.dispatcherBusy()) return NavStatus.RUNNING;

		// Evaluate any pending reactive interaction first.
		ReactiveSolver.Outcome out = solver.evaluatePending(
			snap.playerTile(), snap.plane(), snap.nowMs());
		if (out == ReactiveSolver.Outcome.STILL_WAITING) return NavStatus.RUNNING;
		if (out == ReactiveSolver.Outcome.PROGRESSED || out == ReactiveSolver.Outcome.FAILED)
		{
			// Interaction resolved — gate was opened (or blacklisted). Clear
			// the sticky exit so the fresh replan runs from new position.
			pendingExit = null;
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
		if (plan instanceof PlanResult.BudgetExhausted)
		{
			// Walk toward centroid. The next tick's BFS, starting from
			// the new position, may find a candidate within budget.
			walker.walkTo(goal.centroid(), snap.playerTile());
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
			List<BlockerCandidate> exits = env.onClient(() ->
				env.scanner().findPerimeterExits(snap.playerTile(), goalCentroid,
					BlockerScanner.SCENE_SCAN_RADIUS, snap.collision()));
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
		solver.attempt(b, snap.playerTile(), snap.nowMs());
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
		solver.attempt(atHere, snap.playerTile(), snap.nowMs());
		pendingExit = null;
		return NavStatus.RUNNING;
	}

	private NavStatus handlePlaneMismatch(V21Env.Snapshot snap) throws InterruptedException
	{
		diag.planeMismatchScanning(snap.playerTile());
		Goal goal = activeGoal;
		if (goal == null) return NavStatus.FAILED;
		int planeDir = Integer.signum(goal.centroid().getPlane() - snap.plane());

		// Scene-wide scan (32-tile radius) for the nearest staircase /
		// trapdoor matching the direction of plane change. The previous
		// 3×3-around-player scan only worked when the bot already stood
		// on the staircase — useless from the bank floor.
		BlockerCandidate climb = env.onClient(() ->
			env.scanner().findClimbInScene(snap.playerTile(),
				BlockerScanner.SCENE_SCAN_RADIUS, planeDir, snap.collision()));
		// The reachability filter floods walkable tiles via canMove, but
		// the staircase OBJECT'S tile is itself collision-blocked (a stair
		// occupies its tile and you stand adjacent to interact). So even
		// when the player can clearly walk up to the staircase, the
		// staircase's own tile is never in the flood-fill set and the
		// filtered scan misses it. Fall back to an unfiltered scan in
		// both directions. The walk toward the found tile then hits a
		// BlockedEdge → handleBlockedEdge opens any intervening door.
		// Strict CLIMB_UP/DOWN_VERBS make this safe even for descending —
		// trapdoors use "Open" / "Enter" which aren't in those sets.
		if (climb == null && planeDir != 0)
		{
			climb = env.onClient(() ->
				env.scanner().findClimbInScene(snap.playerTile(),
					BlockerScanner.SCENE_SCAN_RADIUS, planeDir, null));
			if (climb != null)
			{
				log.info("v21: PLANE_MISMATCH unfiltered fallback — climb objectId={} verb={} at={}",
					climb.objectId(), climb.verb(), climb.objectTile());
			}
		}
		if (climb == null)
		{
			// No staircase / trapdoor in the current scene. Walk toward
			// the goal's xy on the player's plane — the loaded scene
			// only covers ~104 tiles, so we have to physically approach
			// the building that contains the stairs (the bank's
			// ground-floor entrance, in the pen→bank case). Next tick
			// the new scene window will have the bank's stairs in
			// range and Pass 1 will pick them.
			WorldPoint planarTarget = new WorldPoint(
				goal.centroid().getX(), goal.centroid().getY(), snap.plane());
			log.info("v21: PLANE_MISMATCH no climb in scene (planeDir={}) — "
				+ "walking toward planar target {}", planeDir, planarTarget);
			Goal planarGoal = new Goal.Area(planarTarget, 1);
			PlanResult planar = new StaticPlanner(snap.collision())
				.plan(snap.playerTile(), planarGoal);
			if (planar instanceof PlanResult.Success s)
			{
				walker.walkAlong(s.tiles(), snap.playerTile());
				return NavStatus.RUNNING;
			}
			if (planar instanceof PlanResult.BlockedEdge be)
			{
				return handleBlockedEdge(be, snap);
			}
			log.warn("v21: cannot plan toward planar target {} from {} — yielding",
				planarTarget, snap.playerTile());
			return NavStatus.RUNNING;
		}

		int dist = Math.max(
			Math.abs(snap.playerTile().getX() - climb.objectTile().getX()),
			Math.abs(snap.playerTile().getY() - climb.objectTile().getY()));

		// Close enough to interact — click. Re-build the candidate with
		// the current player tile so the blacklist key reflects where
		// we actually attempted from.
		if (dist <= 2)
		{
			BlockerCandidate atHere = new BlockerCandidate(
				climb.object(), climb.verb(), snap.playerTile());
			if (solver.isBlacklisted(atHere, snap.nowMs())) return NavStatus.RUNNING;
			diag.blockerFound(snap.playerTile(), climb.objectTile(), atHere);
			solver.attempt(atHere, snap.playerTile(), snap.nowMs());
			return NavStatus.RUNNING;
		}

		// Walk toward the staircase — synthesize a sub-goal at its tile
		// and run the planner. If the route to the staircase is itself
		// blocked (e.g. closed bank door), recurse the BlockedEdge
		// handler so the door gets opened on the way.
		log.info("v21: PLANE_MISMATCH — walking toward climb objectId={} verb={} at={} (dist={})",
			climb.objectId(), climb.verb(), climb.objectTile(), dist);
		Goal subGoal = new Goal.Area(climb.objectTile(), 1);
		PlanResult sub = new StaticPlanner(snap.collision()).plan(snap.playerTile(), subGoal);
		if (sub instanceof PlanResult.Success s)
		{
			walker.walkAlong(s.tiles(), snap.playerTile());
			return NavStatus.RUNNING;
		}
		if (sub instanceof PlanResult.BlockedEdge be)
		{
			return handleBlockedEdge(be, snap);
		}
		log.warn("v21: cannot plan to staircase {} from {} — yielding",
			climb.objectTile(), snap.playerTile());
		return NavStatus.RUNNING;
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

	@Override
	public void cancel()
	{
		diag.cancelled();
		activeRequest = null;
		activeGoal = null;
		lastPlayerTile = null;
		stallTicks = 0;
		pendingExit = null;
		solver.reset();
	}

	@Override
	public boolean isBusy() { return activeRequest != null; }

	@Override
	public String name() { return NAME; }
}
