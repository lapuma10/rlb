package net.runelite.client.plugins.recorder.nav.v21;

import java.util.List;
import java.util.Random;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import net.runelite.client.sequence.internal.ActionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Dispatch one walk click per tick. Picks a tile ~17-21 along the
 *  path from the player's current position; the engine routes the
 *  rest.
 *
 *  <p>No internal state — each call is self-contained. The navigator
 *  replans every tick anyway, so caching paths between ticks would
 *  just create staleness bugs.
 *
 *  <p>Before dispatching, rotates the camera toward the target via
 *  {@link HumanizedInputDispatcher#rotateCameraToward} so the tile is
 *  visibly on-canvas. The dispatcher's rotate method is smart enough
 *  to no-op when the target is already comfortably visible, so
 *  always-calling it is cheap on short walks and load-bearing on
 *  long ones.
 *
 *  <p>Two strict-mode variants exist. {@link #walkAlong} dispatches a
 *  non-strict walk: every tile in {@code path} is BFS-validated, so if
 *  the hover happens to resolve to "Attack Chicken" on top of the
 *  target tile, the dispatcher's minimap fallback is an acceptable
 *  rescue — we still end up walking the BFS-correct route.
 *  {@link #walkAlongStrict} dispatches a strict walk: when the hover
 *  isn't WALK, the dispatcher refuses to fall back to the minimap.
 *  This is required for transport-approach final walks where the
 *  caller needs to stand on a specific source tile to interact with
 *  the transport — a minimap fallback could route us to an adjacent
 *  walkable tile instead and the verb click then fails. */
public final class WalkExecutor
{
	private static final Logger log = LoggerFactory.getLogger(WalkExecutor.class);

	/** Tiles past the player to aim for. 17-21 is a comfortable lookahead:
	 *  close enough for the engine's click-to-run to finish the leg,
	 *  far enough that the bot doesn't re-click every 2 tiles. */
	static final int MIN_LOOKAHEAD = 17;
	static final int MAX_LOOKAHEAD = 21;
	/** Camera-rotation threshold (Chebyshev tiles from player). Below
	 *  this, the target is usually already on screen so we skip the
	 *  rotation call entirely. The dispatcher's own visibility check
	 *  would also no-op, but skipping the marshal-to-client-thread is
	 *  cheaper on the dominant short-walk case. */
	static final int ROTATE_THRESHOLD = 10;

	private final HumanizedInputDispatcher dispatcher;
	private final Random rng;

	public WalkExecutor(HumanizedInputDispatcher dispatcher, Random rng)
	{
		this.dispatcher = dispatcher;
		this.rng = rng;
	}

	/** Walk one step along {@code path} starting from {@code playerNow}.
	 *  Picks a tile {@code MIN_LOOKAHEAD..MAX_LOOKAHEAD} ahead of
	 *  {@code playerNow} in the path, capped at the last tile. Returns
	 *  true if a click was dispatched.
	 *
	 *  <p>Non-strict — the dispatcher may fall back to the minimap when
	 *  the canvas hover isn't WALK. Safe because every tile in
	 *  {@code path} is BFS-validated, so a minimap rescue still routes
	 *  the player along a correct corridor. */
	public boolean walkAlong(List<WorldPoint> path, WorldPoint playerNow) throws InterruptedException
	{
		if (path == null || path.isEmpty() || !path.get(0).equals(playerNow))
		{
			log.warn("v21.walk: stale path — first tile {} != playerNow {}",
				path == null || path.isEmpty() ? null : path.get(0), playerNow);
			return false;
		}
		if (dispatcher.isBusy()) return false;
		WorldPoint target = pickLookahead(path);
		return dispatchWalk(target, playerNow, false);
	}

	/** Strict variant of {@link #walkAlong}. The dispatched walk refuses
	 *  to fall back to the minimap when the canvas hover isn't WALK —
	 *  used for transport-approach final walks where we must end up on
	 *  a specific source tile to interact with the transport object. */
	public boolean walkAlongStrict(List<WorldPoint> path, WorldPoint playerNow) throws InterruptedException
	{
		if (path == null || path.isEmpty() || !path.get(0).equals(playerNow))
		{
			log.warn("v21.walk: stale path — first tile {} != playerNow {}",
				path == null || path.isEmpty() ? null : path.get(0), playerNow);
			return false;
		}
		if (dispatcher.isBusy()) return false;
		WorldPoint target = pickLookahead(path);
		return dispatchWalk(target, playerNow, true);
	}

	/** Walk directly to a specific tile. Used when we need to get to a
	 *  blocker's source side before interacting.
	 *
	 *  @deprecated v21 dispatches walks only through walkAlong /
	 *  walkAlongStrict with a BFS-validated path. Any new call site
	 *  must opt into bypassing path validation — prefer constructing a
	 *  path with StaticPlanner first. */
	@Deprecated
	public boolean walkTo(WorldPoint tile, WorldPoint from) throws InterruptedException
	{
		if (tile == null) return false;
		if (dispatcher.isBusy()) return false;
		return dispatchWalk(tile, from, true);
	}

	private WorldPoint pickLookahead(List<WorldPoint> path)
	{
		int lookahead = MIN_LOOKAHEAD + rng.nextInt(MAX_LOOKAHEAD - MIN_LOOKAHEAD + 1);
		int targetIdx = Math.min(lookahead, path.size() - 1);
		return path.get(targetIdx);
	}

	private boolean dispatchWalk(WorldPoint tile, WorldPoint from, boolean strict) throws InterruptedException
	{
		int dist = Math.max(Math.abs(tile.getX() - from.getX()),
			Math.abs(tile.getY() - from.getY()));
		if (dist > ROTATE_THRESHOLD)
		{
			// Bring the target on-screen. rotateCameraToward is a no-op
			// when the target is already comfortably visible (the
			// dispatcher's own check), so the cost is just one
			// client-thread marshal on far walks. Worth it — without
			// rotation, far targets that project below the world view
			// fall through to the minimap which sometimes also fails
			// (target outside minimap radius, partially off-edge).
			log.debug("v21.walk: rotate camera toward {} (dist={})", tile, dist);
			dispatcher.rotateCameraToward(tile);
		}
		log.debug("v21.walk: dispatch {} (dist={}, strict={})", tile, dist, strict);
		ActionRequest req = ActionRequest.builder()
			.kind(ActionRequest.Kind.WALK)
			.channel(ActionRequest.Channel.MOUSE)
			.tile(tile)
			.strictWalk(strict)
			.build();
		dispatcher.dispatch(req);
		return true;
	}
}
