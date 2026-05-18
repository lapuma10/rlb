package net.runelite.client.plugins.recorder.quest.steps;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.nav.BehaviorMode;
import net.runelite.client.plugins.recorder.nav.NavRequest;
import net.runelite.client.plugins.recorder.nav.NavStatus;
import net.runelite.client.plugins.recorder.nav.v2.V2Navigator;
import net.runelite.client.sequence.*;
import net.runelite.client.sequence.blackboard.Blackboard;

/**
 * Cross-scene walking step backed by {@link V2Navigator}. Use this when
 * the destination is more than ~50 tiles away or on a different plane —
 * the engine's built-in {@code WalkStep} only handles in-scene targets
 * and will spam "walk target not resolvable" otherwise.
 *
 * <p><b>Threading.</b> V2Navigator.tick runs a Dijkstra plan on every
 * destination change — for skeleton-broad searches this is ~1 s of
 * synchronous compute. If we ran it inside {@link #tick} (which the
 * engine invokes on the client thread), the OSRS render loop freezes
 * for the duration. Instead {@link #onStart} spawns a dedicated daemon
 * worker that runs the nav.tick loop; {@link #check} reads the latest
 * status from a volatile field. The engine's per-tick {@link #tick}
 * hook is a no-op (just an abort check).
 *
 * <p><b>Stop semantics.</b> {@code abortRequested} is polled inside the
 * worker loop so the panel's Stop button cuts the walk short instead of
 * waiting for ARRIVED/FAILED. The thread also exits when the step ends
 * for any other reason (success, failure, timeout) — see
 * {@link #stopWorker}.
 */
@Slf4j
public final class NavWalkStep implements Step {

    /** Cadence between nav.tick calls on the worker thread. The executor
     *  inside nav.tick already throttles its click dispatch; the loop just
     *  needs to keep the planner responsive to player position drift. */
    private static final long WORKER_TICK_INTERVAL_MS = 200L;
    /** Hard cap so a broken plan / unreachable target doesn't keep a
     *  worker alive forever after the engine has moved on. */
    private static final long WORKER_DEADLINE_MS = 240_000L;

    private final V2Navigator nav;
    private final WorldPoint target;
    private final int arrivalRadius;
    private final BehaviorMode mode;
    private final BooleanSupplier abortRequested;

    /** Latest status written by the worker; read by {@link #check}. */
    private volatile NavStatus lastStatus = NavStatus.IDLE;
    /** Snapshot of {@link V2Navigator#lastFailureReason()} captured by
     *  the worker the moment it observed a FAILED status. Saved here so
     *  {@link #check} can report it even if the navigator's state has
     *  since been overwritten by a {@link V2Navigator#cancel()} during
     *  shutdown. */
    private final AtomicReference<V2Navigator.FailureReason> failureReason = new AtomicReference<>();
    /** Set when the worker is asked to exit (success, fail, abort, or
     *  next-step takeover). Polled inside the loop; ensures we don't keep
     *  the planner busy after the engine has popped this step. */
    private volatile boolean stopFlag = false;
    @Nullable private Thread worker;

    public NavWalkStep(V2Navigator nav, WorldPoint target, int arrivalRadius) {
        this(nav, target, arrivalRadius, BehaviorMode.VARIED, () -> false);
    }

    public NavWalkStep(V2Navigator nav, WorldPoint target, int arrivalRadius,
                       BehaviorMode mode, BooleanSupplier abortRequested) {
        this.nav = nav;
        this.target = target;
        this.arrivalRadius = arrivalRadius;
        this.mode = mode;
        this.abortRequested = abortRequested != null ? abortRequested : () -> false;
    }

    @Override public String name()                                          { return "NavWalkTo " + target; }
    @Override public int priority()                                         { return 50; }
    @Override public int timeoutTicks()                                     { return 400; }
    @Override public PreemptionPolicy preemptionPolicy()                    { return PreemptionPolicy.WHEN_SAFE; }
    @Override public boolean isSafeToPause(WorldSnapshot s, Blackboard b)   { return true; }
    @Override public boolean canStart(WorldSnapshot s, Blackboard b)        { return s.player() != null; }

    @Override
    public void onStart(StepContext ctx) {
        // Defensive: cancel any in-flight nav plan owned by the same
        // V2Navigator instance so a stale executor RUNNING state from a
        // prior step doesn't prevent the planner from re-planning to the
        // new target.
        nav.cancel();
        lastStatus = NavStatus.IDLE;
        failureReason.set(null);
        stopFlag = false;

        if (withinArrival(ctx.snapshot())) {
            lastStatus = NavStatus.ARRIVED;
            log.info("nav-walk: start target={} — already within radius, skipping worker", target);
            return;
        }

        // Spawn the worker. Daemon = JVM exit doesn't wait for it; the
        // engine pop already calls stopWorker via the END_LIFECYCLE hooks
        // below (onFailure / check returning Succeeded — the engine has
        // no onEnd hook, so we lean on check returning a terminal state
        // and the worker observing stopFlag).
        worker = new Thread(this::runWorker, "ernest-nav-" + target);
        worker.setDaemon(true);
        worker.start();
        log.info("nav-walk: start target={} radius={} worker={}", target, arrivalRadius, worker.getName());
    }

    /** Worker loop. Calls {@link V2Navigator#tick} until ARRIVED, FAILED,
     *  abort, or deadline. Runs on its own daemon thread — never on the
     *  client thread. The planner Dijkstra is heavy (~1 s for broad
     *  skeleton searches), so this MUST stay off the engine tick. */
    private void runWorker() {
        long deadline = System.currentTimeMillis() + WORKER_DEADLINE_MS;
        try {
            while (!stopFlag && System.currentTimeMillis() < deadline) {
                if (abortRequested.getAsBoolean()) {
                    log.info("nav-walk: {} aborted by stop signal", target);
                    nav.cancel();
                    return;
                }
                NavStatus s;
                try {
                    s = nav.tick(NavRequest.toPoint(target, mode));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.info("nav-walk: {} worker interrupted", target);
                    return;
                } catch (RuntimeException re) {
                    log.warn("nav-walk: {} nav.tick threw — failing step: {}", target, re.toString());
                    failureReason.set(V2Navigator.FailureReason.EXECUTOR_FAILED);
                    lastStatus = NavStatus.FAILED;
                    return;
                }
                lastStatus = s;
                if (s == NavStatus.ARRIVED) {
                    log.info("nav-walk: {} ARRIVED", target);
                    return;
                }
                if (s == NavStatus.FAILED) {
                    failureReason.set(nav.lastFailureReason());
                    log.warn("nav-walk: {} FAILED reason={}", target, nav.lastFailureReason());
                    return;
                }
                try {
                    Thread.sleep(WORKER_TICK_INTERVAL_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (!stopFlag) {
                log.warn("nav-walk: {} worker deadline ({} ms) exceeded — marking FAILED", target, WORKER_DEADLINE_MS);
                failureReason.set(V2Navigator.FailureReason.EXECUTOR_FAILED);
                lastStatus = NavStatus.FAILED;
            }
        } finally {
            // Don't cancel nav here — it may already be the right plan
            // for the next NavWalkStep (V2Navigator caches per-target).
        }
    }

    @Override
    public void tick(StepContext ctx) {
        // No-op on the client thread. The worker does the real work.
        // We could opportunistically observe abortRequested here too but
        // the worker polls it on every loop iteration anyway.
    }

    @Override
    public Completion check(WorldSnapshot s, Blackboard bb) {
        if (withinArrival(s)) {
            stopWorker("arrived (via player snapshot)");
            return new Completion.Succeeded("nav arrived at " + target);
        }
        NavStatus st = lastStatus;
        if (st == NavStatus.ARRIVED) {
            stopWorker("arrived (worker reported)");
            return new Completion.Succeeded("nav reported ARRIVED for " + target);
        }
        if (st == NavStatus.FAILED) {
            V2Navigator.FailureReason fr = failureReason.get();
            stopWorker("failed");
            return new Completion.Failed("nav failed: " + (fr != null ? fr : "unknown"));
        }
        return Completion.RUNNING;
    }

    @Override
    public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard bb) {
        // One retry — replan is cheap; if the planner can't find a route the
        // second attempt will fail the same way and the LinearSequence aborts.
        stopWorker("step failure");
        return new Recovery.Retry(1);
    }

    @Override public void onEvent(Object e, StepContext ctx) { /* unused */ }

    /** Signal the worker to exit and best-effort wait briefly. Idempotent. */
    private void stopWorker(String reason) {
        if (stopFlag) return;
        stopFlag = true;
        Thread w = worker;
        if (w != null && w.isAlive()) {
            // Don't block check() — the worker polls stopFlag every loop;
            // it'll exit within WORKER_TICK_INTERVAL_MS (200ms).
            w.interrupt();
            log.info("nav-walk: {} signalled worker stop — reason: {}", target, reason);
        }
        worker = null;
    }

    private boolean withinArrival(WorldSnapshot s) {
        if (s.player() == null || s.player().worldLocation() == null) return false;
        WorldPoint here = s.player().worldLocation();
        return here.getPlane() == target.getPlane()
            && here.distanceTo(target) <= arrivalRadius;
    }
}
