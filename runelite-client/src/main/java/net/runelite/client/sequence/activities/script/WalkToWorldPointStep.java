package net.runelite.client.sequence.activities.script;

import java.util.function.Consumer;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.analyse.StepEvent;
import net.runelite.client.plugins.recorder.nav.BehaviorMode;
import net.runelite.client.plugins.recorder.nav.NavRequest;
import net.runelite.client.plugins.recorder.nav.NavStatus;
import net.runelite.client.plugins.recorder.nav.Navigator;
import net.runelite.client.sequence.Completion;
import net.runelite.client.sequence.Failure;
import net.runelite.client.sequence.PlayerView;
import net.runelite.client.sequence.Recovery;
import net.runelite.client.sequence.StepContext;
import net.runelite.client.sequence.WorldSnapshot;
import net.runelite.client.sequence.affordance.DiagnosticReason;
import net.runelite.client.sequence.artemis.Artemis;
import net.runelite.client.sequence.blackboard.Blackboard;

/**
 * Step backing {@code Artemis.walkTo(WorldPoint)}. Gameplay Step;
 * honors {@code artemis.session().shouldContinue()}.
 *
 * <p><b>Threading.</b> {@link Navigator#tick} runs a planner that can
 * take ~1 second of synchronous compute (V2's Dijkstra). Running that
 * inside {@link #doCheck} (client thread) would freeze the OSRS render
 * loop. {@code RUN_TASK} would put it on the dispatcher worker — but
 * the Navigator INTERNALLY dispatches CLICK_TILE / WALK actions via
 * the same dispatcher, and the dispatcher's busy flag would silently
 * drop those recursive dispatches (same failure mode as 1A.4b's
 * LogoutAction). The clean fix, matching the legacy
 * {@code quest/steps/NavWalkStep}, is a dedicated daemon worker:
 *
 * <pre>
 *   doStart  (client thread)  → null + reuse + session checks,
 *                               spawn daemon worker, return.
 *   worker   (own daemon)     → loop: navigator.tick(req); write
 *                               volatile lastStatus + failureCode;
 *                               exit on ARRIVED / FAILED / stopFlag /
 *                               deadline / interrupt.
 *   doCheck  (client thread)  → read snapshot.player(), evaluate
 *                               arrival debounce, stuck counter,
 *                               step timeout, worker terminal status.
 *                               OWNS the Succeeded/Failed decision.
 *   onFailure (client thread) → stopWorker(reason); Retry/Abort.
 *   tick      (client thread) → no-op.
 * </pre>
 *
 * <p>The worker may ONLY call {@link Navigator#tick} and write the
 * volatile status fields. It must not dispatch input, read widgets,
 * or decide Step success. {@link #doCheck} owns all completion
 * decisions per the 1A.4c guardrail.
 *
 * <p>Single-use: instances are not reusable. A second {@link #doStart}
 * call throws {@link IllegalStateException} (surfaces as
 * {@code ONSTART_EXCEPTION} via the base class trap). Scripts construct
 * a fresh Step per walk.
 *
 * <p>Diagnostic vocabulary (6 codes):
 * <ul>
 *   <li>{@code NAVIGATOR_MISSING} — null Navigator at doStart → Abort.</li>
 *   <li>{@code NO_ROUTE} — worker's first tick returns FAILED (no prior
 *       RUNNING) → Abort; replanning won't find a path.</li>
 *   <li>{@code STUCK} — player location unchanged for
 *       {@link #STUCK_THRESHOLD_TICKS} consecutive ticks → Retry(2).</li>
 *   <li>{@code TIMEOUT} — step elapsed ≥ {@link #TIMEOUT_TICKS} → Retry(2).</li>
 *   <li>{@code NAVIGATOR_FAILED} — worker FAILED after ≥1 RUNNING, or
 *       worker deadline exceeded → Retry(2); transient mid-route failure.</li>
 *   <li>{@code NAVIGATOR_EXCEPTION} — worker caught Throwable from
 *       navigator.tick → Abort; unknown state, don't retry into the
 *       same bug.</li>
 * </ul>
 */
@Slf4j
public final class WalkToWorldPointStep extends ArtemisActionStep
{
	/** Per-Step tick budget. ~36s — covers short-to-medium walks; longer
	 *  journeys should chain multiple Steps or use a different
	 *  scheduling strategy. */
	private static final int TIMEOUT_TICKS = 60;

	/** Cadence between {@code navigator.tick(req)} calls on the worker
	 *  thread. Matches {@link net.runelite.client.plugins.recorder.quest.steps.NavWalkStep}.
	 *  The Navigator already throttles its internal click dispatch; this
	 *  is the planner's loop cadence. */
	private static final long WORKER_TICK_INTERVAL_MS = 200L;

	/** Wall-clock safety net so a hung worker exits even if {@link #stopWorker}
	 *  fails to deliver. 60 s ≈ 100 game ticks — well past the Step's own
	 *  60-tick timeout. */
	private static final long WORKER_DEADLINE_MS = 60_000L;

	/** Player location within this many tiles of the target counts as
	 *  in-range for arrival debounce. */
	private static final int ARRIVAL_RADIUS_TILES = 1;

	/** Consecutive in-range ticks required before declaring Succeeded.
	 *  Defends against single-tick clipping artifacts. */
	private static final int ARRIVAL_DEBOUNCE_TICKS = 2;

	/** Player location unchanged for this many consecutive ticks while
	 *  not at the target → STUCK. ~3.6 s — tolerates ladders/gates,
	 *  catches real stalls. */
	private static final int STUCK_THRESHOLD_TICKS = 6;

	// ── Diagnostic codes (vocabulary §1A.4c) ────────────────────────

	static final String REASON_NAVIGATOR_MISSING   = "NAVIGATOR_MISSING";
	static final String REASON_NO_ROUTE            = "NO_ROUTE";
	static final String REASON_STUCK               = "STUCK";
	static final String REASON_NAVIGATOR_FAILED    = "NAVIGATOR_FAILED";
	static final String REASON_NAVIGATOR_EXCEPTION = "NAVIGATOR_EXCEPTION";

	// ── Fields ──────────────────────────────────────────────────────

	private final WorldPoint target;
	@Nullable private final Navigator navigator;

	/** Single-use guard. Set on first doStart; second call throws. */
	private boolean started = false;

	/** Latest status written by the worker; read by {@link #doCheck}.
	 *  volatile + single-writer (the worker thread) gives a publishing
	 *  barrier without further synchronization. */
	private volatile NavStatus lastStatus = NavStatus.IDLE;

	/** Diagnostic code captured by the worker when it observes FAILED
	 *  or catches an exception. Null until then. Read by {@link #doCheck}
	 *  after observing {@code lastStatus == FAILED}. */
	@Nullable private volatile String workerFailureCode = null;

	/** Free-form detail accompanying {@link #workerFailureCode} —
	 *  Throwable.toString() for exceptions, descriptor for deadline. */
	@Nullable private volatile String workerFailureDetail = null;

	/** True once the worker has observed at least one RUNNING status.
	 *  Distinguishes NO_ROUTE (first tick FAILED) from NAVIGATOR_FAILED
	 *  (mid-route failure). Single-writer (worker), single-reader (worker
	 *  on the same path), so a plain boolean is sufficient. */
	private boolean workerHadRunning = false;

	/** Signal to the worker to exit. Worker polls every iteration and
	 *  inside the catch handler for the sleep. */
	private volatile boolean stopFlag = false;

	/** Last observed player location, written by {@link #doCheck} each
	 *  tick. Used to compute the stuck counter. Captured from snapshot;
	 *  may be null briefly during login transitions. */
	@Nullable private WorldPoint lastObservedLoc = null;

	/** Consecutive ticks the player has not moved while still not at
	 *  target. Reset to zero when location changes. */
	private int stuckTicks = 0;

	/** Consecutive ticks the player has been within the arrival radius.
	 *  Reset to zero when out of range. Triggers Succeeded at
	 *  {@link #ARRIVAL_DEBOUNCE_TICKS}. */
	private int inRangeTicks = 0;

	@Nullable private Thread worker = null;

	public WalkToWorldPointStep(Artemis artemis, Consumer<StepEvent> stepEventSink,
		WorldPoint target, @Nullable Navigator navigator)
	{
		super(artemis, stepEventSink, /* maintenance */ false);
		if (target == null)
		{
			throw new IllegalArgumentException("WorldPoint target must not be null");
		}
		this.target = target;
		this.navigator = navigator;
	}

	@Override
	public String name()
	{
		return "WalkToWorldPoint(" + target.getX() + "," + target.getY() + "," + target.getPlane() + ")";
	}

	@Override public int timeoutTicks()         { return TIMEOUT_TICKS; }
	@Override protected String targetType()     { return "tile"; }
	@Override protected String targetId()       { return "tile:" + target.getX() + "," + target.getY() + "," + target.getPlane(); }
	@Override protected String targetName()     { return null; }
	@Override protected String verb()           { return "Walk"; }

	// ── Test-only accessors (package-private) ───────────────────────

	NavStatus lastStatusForTesting()             { return lastStatus; }
	@Nullable String failureCodeForTesting()     { return workerFailureCode; }
	boolean workerAliveForTesting()              { Thread w = worker; return w != null && w.isAlive(); }
	boolean stopFlagForTesting()                 { return stopFlag; }

	@Override
	protected void doStart(StepContext ctx)
	{
		if (started)
		{
			throw new IllegalStateException(
				"WalkToWorldPointStep is single-use — construct a fresh Step per walk (target=" + target + ")");
		}
		started = true;

		if (navigator == null)
		{
			failOnStart(ctx, REASON_NAVIGATOR_MISSING,
				"ArtemisDeps.navigator is null — wire a Navigator at construction");
			return;
		}

		// Capture initial player location for stuck-counter baseline. A
		// null player here is rare (would imply login transition between
		// the session gate and this point) but defensible — leave
		// lastObservedLoc null, the first doCheck handles it.
		WorldSnapshot s = ctx.snapshot();
		PlayerView p = s == null ? null : s.player();
		this.lastObservedLoc = p == null ? null : p.worldLocation();

		Thread w = new Thread(this::runWorker,
			"artemis-walk-" + target.getX() + "," + target.getY() + "," + target.getPlane());
		w.setDaemon(true);
		this.worker = w;
		w.start();
		log.info("walk: start target={} worker={}", target, w.getName());
	}

	/** Worker loop. Calls {@link Navigator#tick} until ARRIVED / FAILED /
	 *  stopFlag / interrupt / deadline. Writes volatile {@link #lastStatus}
	 *  + {@link #workerFailureCode}. Does NOT decide Step success —
	 *  {@link #doCheck} owns that. */
	private void runWorker()
	{
		long deadline = System.currentTimeMillis() + WORKER_DEADLINE_MS;
		NavRequest req = NavRequest.toPoint(target, BehaviorMode.VARIED);

		while (!stopFlag && System.currentTimeMillis() < deadline)
		{
			NavStatus s;
			try
			{
				s = navigator.tick(req);
			}
			catch (InterruptedException ie)
			{
				Thread.currentThread().interrupt();
				log.info("walk: {} worker interrupted", target);
				return;
			}
			catch (Throwable th)
			{
				log.warn("walk: {} navigator.tick threw — failing step: {}", target, th.toString());
				// Order matters: write the diagnostic fields BEFORE the
				// volatile lastStatus that gates them. Reader observes
				// lastStatus = FAILED, then reads workerFailureCode; with
				// this ordering the volatile sync on lastStatus guarantees
				// the prior writes are visible (JLS §17.4.4).
				workerFailureDetail = th.toString();
				workerFailureCode = REASON_NAVIGATOR_EXCEPTION;
				lastStatus = NavStatus.FAILED;
				return;
			}

			if (s == NavStatus.RUNNING)
			{
				workerHadRunning = true;
			}

			if (s == NavStatus.ARRIVED)
			{
				// No diagnostic fields to publish — single volatile write
				// is the terminal signal.
				lastStatus = s;
				log.info("walk: {} worker observed ARRIVED", target);
				return;
			}
			if (s == NavStatus.FAILED)
			{
				// Write diagnostic fields BEFORE the gate (see catch block).
				workerFailureCode = workerHadRunning ? REASON_NAVIGATOR_FAILED : REASON_NO_ROUTE;
				lastStatus = s;
				log.warn("walk: {} worker observed FAILED → {}", target, workerFailureCode);
				return;
			}
			// Non-terminal status (RUNNING, IDLE) — publish for liveness.
			lastStatus = s;

			try
			{
				Thread.sleep(WORKER_TICK_INTERVAL_MS);
			}
			catch (InterruptedException ie)
			{
				Thread.currentThread().interrupt();
				return;
			}
		}

		// Exited via deadline (not stopFlag). Surface as NAVIGATOR_FAILED
		// so the Step retries; the original cause is logged. Same field-
		// ordering rule as the FAILED branch above: diagnostic first,
		// volatile gate last.
		if (!stopFlag)
		{
			log.warn("walk: {} worker deadline ({} ms) exceeded — marking FAILED",
				target, WORKER_DEADLINE_MS);
			workerFailureDetail = "worker deadline " + WORKER_DEADLINE_MS + "ms";
			workerFailureCode = REASON_NAVIGATOR_FAILED;
			lastStatus = NavStatus.FAILED;
		}
	}

	@Override
	protected Completion doCheck(WorldSnapshot s, Blackboard bb, long elapsed)
	{
		WorldPoint here = (s == null || s.player() == null) ? null : s.player().worldLocation();

		// 1. Arrival debounce — succeed FIRST. Matches LogoutStep's
		//    "succeed before timeout" convention (1A.4b): if the player
		//    actually arrived on the same tick the step timer hits, we
		//    award the win rather than failing with TIMEOUT. Also runs
		//    before worker-terminal so a late-arriving worker FAILED
		//    doesn't override a player who genuinely arrived.
		//    Skipped entirely when {@code here == null} — can't decide.
		if (here != null)
		{
			boolean inRange = here.getPlane() == target.getPlane()
				&& here.distanceTo(target) <= ARRIVAL_RADIUS_TILES;
			if (inRange)
			{
				inRangeTicks++;
				if (inRangeTicks >= ARRIVAL_DEBOUNCE_TICKS)
				{
					stopWorker("arrived");
					return new Completion.Succeeded("arrived (within "
						+ ARRIVAL_RADIUS_TILES + "t of " + target + " for "
						+ inRangeTicks + " consecutive ticks)");
				}
			}
			else
			{
				inRangeTicks = 0;
			}
		}

		// 2. Step-level timeout. Stop the worker so it doesn't leak past
		//    the engine's last view of this Step.
		if (elapsed >= TIMEOUT_TICKS)
		{
			stopWorker("step timeout");
			return Completion.failed(new DiagnosticReason.ActionTimedOut(name(), (int) elapsed));
		}

		// 3. Stuck detection — player loc unchanged for STUCK_THRESHOLD_TICKS
		//    consecutive ticks. Skipped when {@code here == null}: we
		//    cannot decide if the player is stuck or simply not-observable
		//    (login transition, loading), and counting null snapshots as
		//    "unchanged" would fire spurious STUCK during login.
		if (here != null)
		{
			boolean locUnchanged = locEquals(here, lastObservedLoc);
			if (locUnchanged)
			{
				stuckTicks++;
			}
			else
			{
				stuckTicks = 0;
				lastObservedLoc = here;
			}
			if (stuckTicks >= STUCK_THRESHOLD_TICKS)
			{
				stopWorker("stuck");
				return Completion.failed(new DiagnosticReason.Unknown(REASON_STUCK));
			}
		}

		// 4. Worker terminal observation. The worker only reports FAILED;
		//    ARRIVED is handled by the player-location debounce above
		//    (worker may report ARRIVED slightly before the player loc
		//    actually settles within the radius). FAILED here means the
		//    Navigator gave up.
		if (lastStatus == NavStatus.FAILED)
		{
			String code = workerFailureCode;
			stopWorker("worker failed: " + code);
			return Completion.failed(new DiagnosticReason.Unknown(
				code != null ? code : REASON_NAVIGATOR_FAILED));
		}

		return Completion.RUNNING;
	}

	@Override
	public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard bb)
	{
		String reason = extractReason(f);
		// onFailure is the engine's last call on this Step; always stop
		// the worker so a still-looping navigator.tick doesn't outlive
		// the Step's engine view.
		stopWorker("step onFailure: " + reason);
		return switch (reason)
		{
			case REASON_STUCK, REASON_TIMEOUT, REASON_NAVIGATOR_FAILED -> new Recovery.Retry(2);
			case REASON_NAVIGATOR_MISSING, REASON_NO_ROUTE, REASON_NAVIGATOR_EXCEPTION -> new Recovery.Abort(reason);
			default -> new Recovery.Abort("unrecognized walk failure: " + reason);
		};
	}

	/** Idempotent. Sets {@link #stopFlag} and interrupts the worker so it
	 *  exits within {@link #WORKER_TICK_INTERVAL_MS}. Does NOT join the
	 *  thread (would block the client thread); the daemon flag ensures
	 *  no JVM-exit hold. */
	private void stopWorker(String reason)
	{
		if (stopFlag)
		{
			return;
		}
		stopFlag = true;
		Thread w = worker;
		if (w != null && w.isAlive())
		{
			w.interrupt();
			log.info("walk: {} stop signal — reason: {}", target, reason);
		}
		worker = null;
	}

	private static boolean locEquals(@Nullable WorldPoint a, @Nullable WorldPoint b)
	{
		if (a == null && b == null) return true;
		if (a == null || b == null) return false;
		return a.getX() == b.getX() && a.getY() == b.getY() && a.getPlane() == b.getPlane();
	}

	/** Mirror of {@link LogoutStep}'s helper — re-extracts our diagnostic
	 *  string from the engine's {@link Failure} (which carries the
	 *  {@link DiagnosticReason} we returned from {@link #doCheck} or that
	 *  the base class pinned via {@code failOnStart}). */
	private static String extractReason(Failure f)
	{
		DiagnosticReason d = f.diagnostic();
		if (d instanceof DiagnosticReason.ActionTimedOut)
		{
			return REASON_TIMEOUT;
		}
		if (d instanceof DiagnosticReason.Unknown unk)
		{
			return unk.detail() != null ? unk.detail() : REASON_TIMEOUT;
		}
		return f.reason() != null ? f.reason() : REASON_TIMEOUT;
	}
}
