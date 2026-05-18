package net.runelite.client.plugins.recorder.nav.v21;

import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import net.runelite.client.sequence.internal.ActionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Click an unblocking object, watch for proof of progress, blacklist
 *  it if nothing changed.
 *
 *  <p><b>Proof of progress.</b> A successful reactive interaction is one
 *  of:
 *  <ul>
 *    <li>player tile changed (door opened, we walked through),</li>
 *    <li>plane changed (stair / ladder),</li>
 *    <li>(implicit) the next planner call succeeds where it previously
 *        reported BlockedEdge — surfaced as "moved" because the player
 *        crossed at least one tile while replanning.</li>
 *  </ul>
 *  Without one of these within {@link #PROGRESS_DEADLINE_MS}, the
 *  attempt is recorded as a failure and the candidate is blacklisted
 *  for {@link #BLACKLIST_TTL_MS}. This is the door-spam-loop guard.
 *
 *  <p><b>Blacklist key.</b> {@code (objectId, verb, approachTile)} —
 *  the same door clicked from the OTHER side is a different attempt
 *  and gets its own blacklist entry. Closed-from-the-south, opened-
 *  from-the-north works correctly. */
public final class ReactiveSolver
{
	private static final Logger log = LoggerFactory.getLogger(ReactiveSolver.class);

	/** How long to wait for player tile / plane to change after we
	 *  dispatch a reactive click. Doors animate in ~1-2 ticks, stairs
	 *  in ~3-4 ticks, ladders ~5 ticks. 3 s covers all reasonable
	 *  cases without being so long that a wrong-door click holds the
	 *  bot for ages. */
	static final long PROGRESS_DEADLINE_MS = 3_000;
	/** How long to suppress retries of a failed (objectId, verb, approach).
	 *  20 s is long enough that a transient scene/load issue clears,
	 *  short enough that the user can re-trigger by waiting. */
	static final long BLACKLIST_TTL_MS = 20_000;

	private final HumanizedInputDispatcher dispatcher;
	private final Map<String, Long> blacklistedUntilMs = new HashMap<>();
	@Nullable private PendingInteraction pending;

	public ReactiveSolver(HumanizedInputDispatcher dispatcher)
	{
		this.dispatcher = dispatcher;
	}

	public boolean isBlacklisted(BlockerCandidate b, long nowMs)
	{
		Long until = blacklistedUntilMs.get(b.blacklistKey());
		return until != null && until > nowMs;
	}

	public boolean hasPending() { return pending != null; }

	/** Dispatch the verb click and arm a PendingInteraction. Returns
	 *  true if the click was dispatched. */
	public boolean attempt(BlockerCandidate b, WorldPoint playerTileBefore, long nowMs)
	{
		if (dispatcher.isBusy()) return false;
		ActionRequest req = ActionRequest.builder()
			.kind(ActionRequest.Kind.CLICK_GAME_OBJECT)
			.channel(ActionRequest.Channel.MOUSE)
			.tile(b.objectTile())
			.objectId(b.objectId())
			.verb(b.verb())
			.liveTracked(true)
			.build();
		log.info("v21.react: dispatch verb={} object={} at={} player={}",
			b.verb(), b.objectId(), b.objectTile(), playerTileBefore);
		dispatcher.dispatch(req);
		pending = new PendingInteraction(b, playerTileBefore, nowMs);
		return true;
	}

	/** Called every tick by the navigator. */
	public Outcome evaluatePending(WorldPoint playerTileNow, int planeNow, long nowMs)
	{
		if (pending == null) return Outcome.NOTHING_PENDING;
		boolean moved = !pending.playerTileBefore.equals(playerTileNow);
		boolean planeChanged = pending.playerTileBefore.getPlane() != planeNow;
		if (moved || planeChanged)
		{
			log.info("v21.react: PROGRESS verb={} object={} moved={} planeChanged={}",
				pending.candidate.verb(), pending.candidate.objectId(), moved, planeChanged);
			pending = null;
			return Outcome.PROGRESSED;
		}
		if (nowMs - pending.dispatchedAtMs > PROGRESS_DEADLINE_MS)
		{
			String key = pending.candidate.blacklistKey();
			blacklistedUntilMs.put(key, nowMs + BLACKLIST_TTL_MS);
			log.warn("v21.react: NO_PROGRESS — blacklisting {} for {} ms (verb={} object={})",
				key, BLACKLIST_TTL_MS, pending.candidate.verb(), pending.candidate.objectId());
			pending = null;
			return Outcome.FAILED;
		}
		return Outcome.STILL_WAITING;
	}

	public void reset()
	{
		// Clear pending so we don't false-positive a stale "moved" event
		// after the next request starts. Intentionally do NOT clear the
		// blacklist — a door that failed a moment ago should stay
		// blacklisted for its TTL even if the script issues a new
		// navigation request (likely the same door is still broken).
		pending = null;
	}

	public enum Outcome { NOTHING_PENDING, STILL_WAITING, PROGRESSED, FAILED }

	private record PendingInteraction(BlockerCandidate candidate,
		WorldPoint playerTileBefore, long dispatchedAtMs) {}
}
