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
 * Engine-internal base for navigation Steps that drive {@link Navigator}.
 * Pulls up the daemon-worker pattern shared by {@link WalkToWorldPointStep}
 * (Phase 1A.4c) and {@link WalkToZoneStep} (Phase 1A.4d) so the worker
 * machinery and diagnostic vocabulary live in one place.
 *
 * <p>Subclasses provide:
 * <ul>
 *   <li>{@link #navigationTarget()} — the {@link WorldPoint} the worker
 *       feeds to {@code NavRequest.toPoint(...)}.</li>
 *   <li>{@link #isAtArrival(WorldPoint)} — the success predicate evaluated
 *       in {@link #doCheck} against {@code snapshot.player().worldLocation()}.</li>
 *   <li>{@link #doPreFlight(StepContext)} — optional pre-worker check
 *       (e.g. {@code WalkToZoneStep} fails {@code EMPTY_ZONE} here before
 *       any worker is spawned). Default: returns {@code true}.</li>
 *   <li>{@link #workerThreadName()} — daemon thread name for logs / jstack.</li>
 *   <li>The {@link Artemis} surface contract methods ({@link #name},
 *       {@link #targetType}, {@link #targetId}, {@link #targetName},
 *       {@link #verb}, {@link #timeoutTicks}).</li>
 * </ul>
 *
 * <p><b>Threading invariants</b> (carried forward from 1A.4c):
 * <ul>
 *   <li>{@link #doStart} runs on the client thread and spawns a daemon
 *       worker; never blocks.</li>
 *   <li>The worker may only call {@link Navigator#tick} and write the
 *       volatile status fields. It must not dispatch input, must not
 *       read widgets, and must not decide Step success.</li>
 *   <li>{@link #doCheck} (client thread) owns every {@link Completion}
 *       decision and calls {@link #stopWorker} on every terminal path.</li>
 *   <li>{@link #stopWorker} is idempotent.</li>
 * </ul>
 *
 * <p><b>Single-use:</b> a {@code WalkStepBase} instance can be started
 * once. A second {@link #doStart} throws {@link IllegalStateException}
 * (surfaces via the base's {@code onStart} catch as
 * {@code ONSTART_EXCEPTION}).
 *
 * <p>Diagnostic vocabulary (5 codes plus subclass-specific):
 * {@link #REASON_NAVIGATOR_MISSING}, {@link #REASON_NO_ROUTE},
 * {@link #REASON_STUCK}, {@link #REASON_NAVIGATOR_FAILED},
 * {@link #REASON_NAVIGATOR_EXCEPTION}, plus the inherited
 * {@code REASON_TIMEOUT} from {@link ArtemisActionStep}. Subclasses may
 * surface additional codes via {@link #doPreFlight} (e.g.
 * {@code EMPTY_ZONE}); the {@link #onFailure} switch maps every
 * failure to {@link Recovery.Abort}.
 *
 * <p><b>Phase 1A.4d.1 — no Recovery.Retry from this Step.</b> The
 * original Phase 1A.4d code returned {@link Recovery.Retry} for
 * {@code STUCK} / {@code TIMEOUT} / {@code NAVIGATOR_FAILED}, but that
 * contradicted the single-use guard: the engine's Retry path
 * ({@code StateDrivenEngine.applyRecovery}) re-fires {@code onStart}
 * on the SAME Step instance, which trips
 * {@link IllegalStateException} at re-entry. Run 03 F-D1 exposed this
 * explicitly. Walk retries are now caller responsibility — build them
 * via a parent composite that constructs a fresh
 * {@link Artemis#walkTo} Step per attempt.
 *
 * <p>Package-private — scripts never see this type; they get a {@link
 * net.runelite.client.sequence.Step} from {@link Artemis#walkTo}.
 */
@Slf4j
abstract class WalkStepBase extends ArtemisActionStep
{
	/** Per-Step tick budget. ~36 s — short-to-medium walks; longer
	 *  journeys chain multiple Steps. */
	protected static final int TIMEOUT_TICKS = 60;

	/** Cadence between {@code navigator.tick(req)} calls on the worker
	 *  thread. The Navigator internally throttles click dispatch; this
	 *  is the planner's loop cadence. */
	private static final long WORKER_TICK_INTERVAL_MS = 200L;

	/** Wall-clock safety net so a hung worker exits even if
	 *  {@link #stopWorker} fails to deliver. 60 s ≈ 100 game ticks. */
	private static final long WORKER_DEADLINE_MS = 60_000L;

	/** Consecutive in-range ticks required before declaring Succeeded.
	 *  Defends against single-tick clipping. */
	protected static final int ARRIVAL_DEBOUNCE_TICKS = 2;

	/** Player location unchanged for this many consecutive ticks → STUCK.
	 *  ~3.6 s — tolerates ladders/gates, catches real stalls. Gated by
	 *  {@link #workerEverRunning} (Phase 1A.4d.1) so the counter only
	 *  runs once the navigator has actually started driving the
	 *  player — otherwise the dispatcher's pre-walk humanized cursor +
	 *  minimap click chain (often ~3 s on long walks) would fire
	 *  STUCK before the player could have moved at all. */
	private static final int STUCK_THRESHOLD_TICKS = 6;

	// ── Shared diagnostic codes ─────────────────────────────────────

	static final String REASON_NAVIGATOR_MISSING   = "NAVIGATOR_MISSING";
	static final String REASON_NO_ROUTE            = "NO_ROUTE";
	static final String REASON_STUCK               = "STUCK";
	static final String REASON_NAVIGATOR_FAILED    = "NAVIGATOR_FAILED";
	static final String REASON_NAVIGATOR_EXCEPTION = "NAVIGATOR_EXCEPTION";

	// ── Fields ──────────────────────────────────────────────────────

	@Nullable protected final Navigator navigator;

	/** Single-use guard. Set on first doStart; second call throws. */
	private boolean started = false;

	/** Latest status written by the worker; read by {@link #doCheck}.
	 *  volatile + single-writer (worker thread) gives a publishing
	 *  barrier without further synchronization. */
	private volatile NavStatus lastStatus = NavStatus.IDLE;

	/** Diagnostic code captured by the worker when it observes FAILED
	 *  or catches an exception. Null until then. Read by {@link #doCheck}
	 *  AFTER observing {@code lastStatus == FAILED}. JLS §17.4.4
	 *  ordering: worker writes this BEFORE the volatile {@code lastStatus}
	 *  gate, so a reader observing FAILED sees the published code. */
	@Nullable private volatile String workerFailureCode = null;

	/** Free-form detail accompanying {@link #workerFailureCode} —
	 *  Throwable.toString() for exceptions, descriptor for deadline. */
	@Nullable private volatile String workerFailureDetail = null;

	/** True once the worker has observed at least one
	 *  {@link NavStatus#RUNNING} status. Two consumers (Phase 1A.4d.1):
	 *  <ul>
	 *    <li>Worker thread: distinguishes {@code NO_ROUTE} (first tick
	 *        FAILED) from {@code NAVIGATOR_FAILED} (mid-route failure)
	 *        when assigning {@link #workerFailureCode}.</li>
	 *    <li>Client thread ({@link #doCheck}): gates the stuck counter
	 *        so STUCK can't fire before the navigator has actually
	 *        started running.</li>
	 *  </ul>
	 *  {@code volatile} because the writer is the daemon worker and one
	 *  of the readers is the client thread. JLS §17.4.4 publishes the
	 *  worker's "I started running" observation across threads. */
	private volatile boolean workerEverRunning = false;

	/** Signal to the worker to exit. Worker polls every iteration and
	 *  inside the catch handler for the sleep. */
	private volatile boolean stopFlag = false;

	/** Last observed player location, written by {@link #doCheck} each
	 *  tick. Used to compute the stuck counter. */
	@Nullable private WorldPoint lastObservedLoc = null;

	/** Consecutive ticks the player has not moved while still not at
	 *  target. Reset to zero when location changes. */
	private int stuckTicks = 0;

	/** Consecutive ticks the player has been at arrival (per
	 *  {@link #isAtArrival}). Reset to zero when not at arrival. */
	private int inRangeTicks = 0;

	@Nullable private Thread worker = null;

	protected WalkStepBase(Artemis artemis, Consumer<StepEvent> stepEventSink,
		@Nullable Navigator navigator)
	{
		super(artemis, stepEventSink, /* maintenance */ false);
		this.navigator = navigator;
	}

	// ── Subclass hooks ──────────────────────────────────────────────

	/** The {@link WorldPoint} the worker feeds to
	 *  {@link NavRequest#toPoint(WorldPoint, BehaviorMode)}. Called once
	 *  after {@link #doPreFlight} returns true, before the worker is
	 *  spawned. Must not return null. */
	protected abstract WorldPoint navigationTarget();

	/** Arrival predicate. Evaluated by {@link #doCheck} against
	 *  {@code snapshot.player().worldLocation()}. {@code here} may be
	 *  null (no player snapshot) — subclass returns false in that case. */
	protected abstract boolean isAtArrival(@Nullable WorldPoint here);

	/** Optional pre-worker validation. Called by {@link #doStart} AFTER
	 *  the null-navigator check, BEFORE the worker is spawned. Subclass
	 *  may call {@link #failOnStart} and return false to short-circuit;
	 *  default returns true (no pre-flight). */
	protected boolean doPreFlight(StepContext ctx)
	{
		return true;
	}

	/** Daemon thread name. Used in logs and jstack output to attribute
	 *  workers to their Step. */
	protected abstract String workerThreadName();

	// ── Test-only accessors (package-private) ───────────────────────

	final NavStatus lastStatusForTesting()             { return lastStatus; }
	@Nullable final String failureCodeForTesting()     { return workerFailureCode; }
	final boolean workerAliveForTesting()              { Thread w = worker; return w != null && w.isAlive(); }
	final boolean stopFlagForTesting()                 { return stopFlag; }
	final boolean workerEverRunningForTesting()        { return workerEverRunning; }

	// ── Lifecycle (final — subclasses extend via hooks above) ───────

	@Override
	protected final void doStart(StepContext ctx)
	{
		if (started)
		{
			throw new IllegalStateException(
				"WalkStep is single-use — construct a fresh Step per walk (" + name() + ")");
		}
		started = true;

		if (navigator == null)
		{
			failOnStart(ctx, REASON_NAVIGATOR_MISSING,
				"ArtemisDeps.navigator is null — wire a Navigator at construction");
			return;
		}

		// Subclass pre-flight (e.g. EMPTY_ZONE for WalkToZoneStep). If it
		// fails, the subclass already called failOnStart — no worker
		// spawned, no Navigator touched.
		if (!doPreFlight(ctx))
		{
			return;
		}

		// Capture initial player location for stuck-counter baseline.
		WorldSnapshot s = ctx.snapshot();
		PlayerView p = s == null ? null : s.player();
		this.lastObservedLoc = p == null ? null : p.worldLocation();

		WorldPoint navTarget = navigationTarget();
		if (navTarget == null)
		{
			// Subclass contract violation — doPreFlight returned true
			// but navigationTarget is null. Fail loud rather than NPE
			// inside the worker.
			failOnStart(ctx, REASON_NAVIGATOR_FAILED,
				"navigationTarget() returned null after doPreFlight passed — subclass bug");
			return;
		}

		Thread w = new Thread(() -> runWorker(navTarget), workerThreadName());
		w.setDaemon(true);
		this.worker = w;
		w.start();
		log.info("walk: start name={} target={} worker={}", name(), navTarget, w.getName());
	}

	/** Worker loop. Calls {@link Navigator#tick} until ARRIVED / FAILED /
	 *  stopFlag / interrupt / deadline. Writes the volatile status fields
	 *  only; never decides Step success (doCheck owns that). */
	private void runWorker(WorldPoint target)
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
				log.info("walk: {} worker interrupted", name());
				return;
			}
			catch (Throwable th)
			{
				log.warn("walk: {} navigator.tick threw — failing step: {}", name(), th.toString());
				// Diagnostic fields BEFORE the volatile lastStatus gate.
				// JLS §17.4.4: volatile write to lastStatus publishes the
				// prior writes so the reader observes a consistent state.
				workerFailureDetail = th.toString();
				workerFailureCode = REASON_NAVIGATOR_EXCEPTION;
				lastStatus = NavStatus.FAILED;
				return;
			}

			if (s == NavStatus.RUNNING)
			{
				workerEverRunning = true;
			}

			if (s == NavStatus.ARRIVED)
			{
				lastStatus = s;
				log.info("walk: {} worker observed ARRIVED", name());
				return;
			}
			if (s == NavStatus.FAILED)
			{
				workerFailureCode = workerEverRunning ? REASON_NAVIGATOR_FAILED : REASON_NO_ROUTE;
				lastStatus = s;
				log.warn("walk: {} worker observed FAILED → {}", name(), workerFailureCode);
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

		// Exited via deadline (not stopFlag). Surface as NAVIGATOR_FAILED.
		if (!stopFlag)
		{
			log.warn("walk: {} worker deadline ({} ms) exceeded — marking FAILED",
				name(), WORKER_DEADLINE_MS);
			workerFailureDetail = "worker deadline " + WORKER_DEADLINE_MS + "ms";
			workerFailureCode = REASON_NAVIGATOR_FAILED;
			lastStatus = NavStatus.FAILED;
		}
	}

	@Override
	protected final Completion doCheck(WorldSnapshot s, Blackboard bb, long elapsed)
	{
		WorldPoint here = (s == null || s.player() == null) ? null : s.player().worldLocation();

		// 1. Arrival debounce — succeed FIRST so a player arriving on the
		//    cusp of the timeout still wins (matches LogoutStep convention).
		//    Skipped ENTIRELY when {@code here} is null (login transition,
		//    loading): we don't know where the player is, so we neither
		//    increment nor reset inRangeTicks. A player at-arrival on tick
		//    N that hits a null snapshot on tick N+1 keeps their progress.
		//    Matches the 1A.4c behavior exactly.
		if (here != null)
		{
			if (isAtArrival(here))
			{
				inRangeTicks++;
				if (inRangeTicks >= ARRIVAL_DEBOUNCE_TICKS)
				{
					stopWorker("arrived");
					return new Completion.Succeeded("arrived (at-destination for "
						+ inRangeTicks + " consecutive ticks)");
				}
			}
			else
			{
				inRangeTicks = 0;
			}
		}

		// 2. Step-level timeout.
		if (elapsed >= TIMEOUT_TICKS)
		{
			stopWorker("step timeout");
			return Completion.failed(new DiagnosticReason.ActionTimedOut(name(), (int) elapsed));
		}

		// 3. Stuck detection — skipped when {@code here} is null (login
		//    transitions). Counting null-snapshots as "unchanged" would
		//    fire spurious STUCK during login.
		//
		//    Phase 1A.4d.1: ALSO skipped while {@link #workerEverRunning}
		//    is false. The dispatcher's pre-walk humanized cursor +
		//    minimap click chain typically takes ~3 s on long walks —
		//    during that window the player cannot have moved yet (the
		//    click hasn't even landed). Counting "unchanged" against the
		//    6-tick threshold (~3.6 s) under those conditions fired
		//    spurious STUCK and propagated up through the broken
		//    Recovery.Retry contract to {@link IllegalStateException}
		//    at re-entry. Gating on the worker's first
		//    {@code NavStatus.RUNNING} observation makes the 6-tick
		//    constant's semantics actually true: "location unchanged
		//    while we were supposed to be walking."
		if (here != null && workerEverRunning)
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

		// 4. Worker terminal observation.
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
		// Phase 1A.4d.1: ALL walk failures Abort. WalkStep is single-use
		// (see {@link #doStart} guard); the engine's Recovery.Retry path
		// re-fires onStart on the SAME Step instance, which would trip
		// the guard with IllegalStateException (Run 03 F-D1). Future
		// walk retries must be built by a parent composite that
		// constructs a fresh {@link Artemis#walkTo} Step per attempt —
		// not by reusing this one.
		return new Recovery.Abort(reason);
	}

	/** Idempotent. Sets {@link #stopFlag} and interrupts the worker so it
	 *  exits within {@link #WORKER_TICK_INTERVAL_MS}. Does NOT join the
	 *  thread (would block the client thread); the daemon flag ensures
	 *  no JVM-exit hold.
	 *
	 *  <p>Single-thread invariant: called only from the client-thread
	 *  lifecycle methods (doCheck, onFailure), never from the worker
	 *  itself. The engine serializes Step lifecycle calls so concurrent
	 *  invocations don't occur. */
	protected final void stopWorker(String reason)
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
			log.info("walk: {} stop signal — reason: {}", name(), reason);
		}
		worker = null;
	}

	private static boolean locEquals(@Nullable WorldPoint a, @Nullable WorldPoint b)
	{
		if (a == null && b == null) return true;
		if (a == null || b == null) return false;
		return a.getX() == b.getX() && a.getY() == b.getY() && a.getPlane() == b.getPlane();
	}

	/** Re-extracts our diagnostic string from the engine's {@link Failure}
	 *  (which carries the {@link DiagnosticReason} we returned from
	 *  {@link #doCheck} or that the base class pinned via
	 *  {@code failOnStart}). Mirror of {@code LogoutStep.extractReason}. */
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
