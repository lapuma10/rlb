package net.runelite.client.plugins.recorder.walker;

import java.awt.Rectangle;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.recorder.transport.TransportResolver;
import net.runelite.client.plugins.recorder.transport.Waypoint;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;

/**
 * Drives a {@link PathSpec} to completion. The caller pumps {@link #tick}
 * from a worker thread (recommended cadence: every 500-700ms — close to
 * dax's 100ms re-plan but matched to the OSRS tick rate so we don't spam
 * clicks) and watches the returned {@link Status}.
 *
 * <p>Per tick, the walker:
 * <ol>
 *   <li>Reads the player's position from the client thread.</li>
 *   <li>Picks the active waypoint — the closest one by Chebyshev distance,
 *       monotonic forward (we never go back to an already-passed waypoint
 *       because the OSRS world doesn't have stable "I'm in this room" cues
 *       and you can drift back into a previous bbox while walking through).</li>
 *   <li>If the active step is a TRANSPORT and the player is on or adjacent
 *       to its tile, finds the matching object via {@link TransportResolver}
 *       and dispatches a click. Plane changes / position changes count as
 *       arrival.</li>
 *   <li>Otherwise runs a {@link Reachability} BFS from the player and
 *       picks a click target inside the active waypoint's area via
 *       {@link StepClickPicker}. If no tile in the area is reachable, falls
 *       back to picking the reachable tile closest to the area's centre
 *       (so we walk toward the area even if collision blocks the last few
 *       steps).</li>
 *   <li>If the BFS completely fails to project a click target, asks the
 *       {@link ObstacleHandler} for an obstacle on the frontier — a closed
 *       door, gate, or stile we may have to open before continuing.</li>
 *   <li>Re-clicks are throttled: only re-issued when the click target tile
 *       changes OR > {@link #RECLICK_AFTER_MS} have passed without movement.</li>
 *   <li>If the player has not moved for > {@link #STUCK_AFTER_MS} the walker
 *       returns {@link Status#STUCK} so the caller can rotate camera, log
 *       diagnostics, or abort.</li>
 * </ol>
 *
 * <p>The walker is single-threaded by contract — callers must not invoke
 * {@link #tick} from two threads concurrently. All client API reads are
 * hopped through {@link ClientThread#invokeAndWait}. Click dispatch uses
 * {@link HumanizedInputDispatcher} which has its own internal serialiser.
 */
@Slf4j
public final class UniversalWalker
{
    public enum Status { IN_PROGRESS, ARRIVED, STUCK, ERROR }

    public enum InternalState { IDLE, WALKING, AT_TRANSPORT, CROSSING, ARRIVED, STUCK }

    /** BFS depth used per tick. 16 is enough for in-canvas walks; the BFS
     *  short-circuits at depth so this is a constant cost ~256-tile cap. */
    public static final int BFS_DEPTH = 16;

    /** Re-issue a click only if either the target tile changed OR this
     *  many ms have passed since the last click without the player moving.
     *  Prevents click spam during a long walk while still recovering from
     *  dropped clicks. */
    public static final long RECLICK_AFTER_MS = 1_500;

    /** Throttle between transport interactions — gives the engine time to
     *  play the climb / open animation before we re-click. */
    public static final long INTERACT_THROTTLE_MS = 3_000;

    /** Stuck threshold. */
    public static final long STUCK_AFTER_MS = 15_000;

    /** Adjacency threshold for "at the transport tile" — within 1 tile in
     *  any direction is close enough; the engine tends to stop the player
     *  there even if the tile itself isn't standable. */
    public static final int TRANSPORT_ARRIVAL_RADIUS = 1;

    private final Client client;
    private final ClientThread clientThread;
    private final HumanizedInputDispatcher dispatcher;
    private final TransportResolver resolver;
    private final StepClickPicker picker;
    private final ObstacleHandler obstacles;

    // Per-spec mutable state. Reset whenever the spec identity changes.
    private PathSpec currentSpec;
    private int stepIdx;
    private InternalState state;
    private WorldPoint lastSeenPosition;
    private long lastMovementMs;
    private long lastClickMs;
    private long lastInteractMs;
    private WorldPoint lastClickTile;
    private Waypoint lastTransportClicked;

    public UniversalWalker(Client client, ClientThread clientThread,
                           HumanizedInputDispatcher dispatcher,
                           TransportResolver resolver)
    {
        this.client = client;
        this.clientThread = clientThread;
        this.dispatcher = dispatcher;
        this.resolver = resolver;
        this.picker = new StepClickPicker(client);
        this.obstacles = new ObstacleHandler(client, resolver);
        reset();
    }

    /** Clear all per-spec state. Call before starting a new path. The
     *  outer thread calls this when the user clicks "Start" — the walker
     *  also resets automatically when {@link #tick} sees a new spec
     *  identity. */
    public void reset()
    {
        currentSpec = null;
        stepIdx = 0;
        state = InternalState.IDLE;
        lastSeenPosition = null;
        lastMovementMs = System.currentTimeMillis();
        lastClickMs = 0;
        lastInteractMs = 0;
        lastClickTile = null;
        lastTransportClicked = null;
    }

    public InternalState state() { return state; }
    public int currentStepIndex() { return stepIdx; }
    @Nullable public PathSpec currentSpec() { return currentSpec; }

    /**
     * Drive one tick of the walker. Returns the new status. A typical
     * outer loop:
     * <pre>{@code
     * while (running) {
     *     Status st = walker.tick(spec);
     *     if (st == ARRIVED) break;
     *     if (st == STUCK) { ...recover or abort... }
     *     Thread.sleep(600);
     * }
     * }</pre>
     */
    public Status tick(PathSpec spec) throws InterruptedException
    {
        if (spec == null || spec.size() == 0) return Status.ARRIVED;
        if (currentSpec != spec)
        {
            // Either this is the first call or the caller swapped specs —
            // start fresh. Identity comparison is intentional; equal-but-
            // not-same specs (e.g. rebuilt from the same builder) get a
            // fresh state machine, which is what we want.
            reset();
            currentSpec = spec;
            state = InternalState.WALKING;
        }
        if (stepIdx >= spec.size())
        {
            state = InternalState.ARRIVED;
            return Status.ARRIVED;
        }

        Snapshot snap = readSnapshot();
        if (snap == null || snap.position == null)
        {
            // Logged-out / scene not loaded — let the outer loop retry.
            return Status.IN_PROGRESS;
        }

        long now = System.currentTimeMillis();
        if (lastSeenPosition == null || !lastSeenPosition.equals(snap.position))
        {
            lastSeenPosition = snap.position;
            lastMovementMs = now;
        }

        // Pick the active step — monotonic forward.
        int newIdx = chooseStep(spec, snap.position, stepIdx);
        if (newIdx > stepIdx)
        {
            log.info("walker: advancing step {} → {} (player at {})",
                stepIdx, newIdx, snap.position);
            stepIdx = newIdx;
            // Cancel any pending click target on the previous step so we
            // re-issue immediately for the new one.
            lastClickTile = null;
        }
        if (stepIdx >= spec.size())
        {
            state = InternalState.ARRIVED;
            return Status.ARRIVED;
        }

        Waypoint active = spec.waypoints().get(stepIdx);
        Status st;
        if (active.kind() == Waypoint.Kind.TRANSPORT)
        {
            st = handleTransport(active, snap, now);
        }
        else
        {
            st = handleWalk(active, snap, now);
        }
        if (st == Status.IN_PROGRESS && now - lastMovementMs > STUCK_AFTER_MS)
        {
            log.warn("walker: STUCK — no movement for {}ms at step {} ({})",
                now - lastMovementMs, stepIdx, describe(active));
            state = InternalState.STUCK;
            return Status.STUCK;
        }
        return st;
    }

    /** Snapshot of the per-tick read of the client thread. */
    private static final class Snapshot
    {
        WorldPoint position;
        Reachability.ReachabilityMap reach;
    }

    /** Read all the client state we need on the client thread in one hop —
     *  cheaper than two separate hops AND ensures the position and the
     *  reachability map are sampled at the same instant. */
    @Nullable
    private Snapshot readSnapshot() throws InterruptedException
    {
        AtomicReference<Snapshot> ref = new AtomicReference<>();
        java.util.concurrent.CountDownLatch latch =
            new java.util.concurrent.CountDownLatch(1);
        clientThread.invokeLater(() -> {
            try
            {
                Snapshot s = new Snapshot();
                Player self = client.getLocalPlayer();
                if (self == null) { ref.set(s); return; }
                WorldPoint here = self.getWorldLocation();
                if (here == null) { ref.set(s); return; }
                s.position = here;
                WorldView wv = client.getTopLevelWorldView();
                if (wv != null)
                {
                    s.reach = Reachability.compute(wv, here, BFS_DEPTH);
                }
                ref.set(s);
            }
            catch (Throwable th)
            {
                log.warn("walker: snapshot failed", th);
            }
            finally { latch.countDown(); }
        });
        latch.await();
        return ref.get();
    }

    /** Pick the active step. Walks the spec from {@code minIdx} forward,
     *  finds the first waypoint whose arrival predicate is NOT satisfied
     *  yet (i.e. "still need to reach this one"), and returns its index.
     *  This is monotonic — once we've passed a waypoint we never go
     *  back, even if the player drifts back into its bbox. */
    /** Pick the active step. Monotonic forward — only ever returns
     *  indices ≥ {@code minIdx}. Strategy:
     *  <ol>
     *    <li>Scan ALL steps from {@code minIdx} forward and find the
     *        HIGHEST-indexed one the player has already arrived at.</li>
     *    <li>If any, advance past it — return that index + 1.</li>
     *    <li>If none, stick with {@code minIdx} — we haven't reached
     *        anything yet.</li>
     *  </ol>
     *  This handles mid-route resume correctly: if the player starts at
     *  PEN_APPROACH (step 7) instead of the bank, scanning finds step 7
     *  as the highest "arrived" step (its area contains the player) and
     *  the walker advances to step 8 (gate) instead of trying to walk
     *  back to step 2 (stairs-landing) just because step 0 and 1 were
     *  CLIMB_DOWN to planes the player is no longer on.
     *
     *  <p>Earlier this returned the FIRST not-arrived step. That meant
     *  for a player on plane 0 starting at PEN_APPROACH:
     *  step 0 (climbDown p2) → "arrived" (player on p0 not p2), skip;
     *  step 1 (climbDown p1) → "arrived" (player on p0 not p1), skip;
     *  step 2 (walk stairs-landing) → not arrived, return 2.
     *  Walker tries to walk 80 tiles back to stairs-landing. Wrong. */
    private static int chooseStep(PathSpec spec, WorldPoint pos, int minIdx)
    {
        List<Waypoint> wps = spec.waypoints();
        int highestArrived = -1;
        for (int i = minIdx; i < wps.size(); i++)
        {
            if (arrived(wps.get(i), pos)) highestArrived = i;
        }
        if (highestArrived >= 0) return Math.min(highestArrived + 1, wps.size());
        return minIdx;
    }

    /** Has the player reached {@code w}? */
    static boolean arrived(Waypoint w, WorldPoint pos)
    {
        if (w == null || pos == null) return false;
        switch (w.kind())
        {
            case WALK:
                return pos.equals(w.tile());
            case WALK_AREA:
            {
                WorldArea a = w.area();
                if (a == null) return false;
                return pos.getPlane() == a.getPlane()
                    && pos.getX() >= a.getX()
                    && pos.getX() < a.getX() + a.getWidth()
                    && pos.getY() >= a.getY()
                    && pos.getY() < a.getY() + a.getHeight();
            }
            case TRANSPORT:
            {
                WorldPoint t = w.tile();
                if (t == null) return false;
                if (w.transportKind() == Waypoint.TransportKind.CLIMB_UP
                    || w.transportKind() == Waypoint.TransportKind.CLIMB_DOWN)
                {
                    return pos.getPlane() != t.getPlane();
                }
                // OPEN / INTERACT — adjacency on the same plane is enough.
                // The walker decides whether to count adjacency as "arrived"
                // depending on whether it has clicked the transport at
                // least once. See handleTransport.
                return pos.getPlane() == t.getPlane()
                    && Math.abs(pos.getX() - t.getX()) <= TRANSPORT_ARRIVAL_RADIUS
                    && Math.abs(pos.getY() - t.getY()) <= TRANSPORT_ARRIVAL_RADIUS;
            }
        }
        return false;
    }

    private Status handleWalk(Waypoint active, Snapshot snap, long now)
        throws InterruptedException
    {
        state = InternalState.WALKING;
        WorldArea area = active.area();
        if (area == null)
        {
            log.warn("walker: WALK step {} has no area", stepIdx);
            return Status.ERROR;
        }
        if (snap.reach == null)
        {
            return Status.IN_PROGRESS;
        }
        if (arrived(active, snap.position))
        {
            // We're already in the area — caller will advance next tick.
            return Status.IN_PROGRESS;
        }

        // Picker reads Perspective.localToCanvas / localToMinimap, both of
        // which require the client thread under -ea (they touch live
        // camera + widget state). Route through clientCall.
        StepClickPicker.ClickTarget pick = clientCall(() -> picker.pick(snap.reach, area));
        if (pick == null)
        {
            // No reachable tile inside the area projects. Two fallbacks:
            // (1) Walk toward the area centre — the picker's pickTowards
            //     finds the reachable tile that gets us closest.
            // (2) If even that fails, try the obstacle handler on the
            //     frontier — there's likely a gate/door blocking us.
            WorldPoint centre = new WorldPoint(
                area.getX() + area.getWidth() / 2,
                area.getY() + area.getHeight() / 2,
                area.getPlane());
            pick = clientCall(() -> picker.pickTowards(snap.reach, centre));
            if (pick == null)
            {
                ObstacleHandler.Result obs = clientCall(() ->
                    obstacles.findOnFrontier(snap.reach, centre));
                if (obs != null && tryClickObstacle(obs, now))
                {
                    state = InternalState.AT_TRANSPORT;
                    return Status.IN_PROGRESS;
                }
                log.debug("walker: walk step {} — no reachable projection AND no obstacle",
                    stepIdx);
                return Status.IN_PROGRESS;
            }
        }
        if (shouldClick(pick.tile, now))
        {
            log.debug("walker: clicking {} ({}px,{}px) toward step {} {}",
                pick.viaMinimap ? "minimap" : "canvas",
                pick.canvasPixel.getX(), pick.canvasPixel.getY(),
                stepIdx, describe(active));
            dispatcher.clickCanvas(pick.canvasPixel.getX(), pick.canvasPixel.getY());
            lastClickTile = pick.tile;
            lastClickMs = now;
        }
        return Status.IN_PROGRESS;
    }

    private Status handleTransport(Waypoint active, Snapshot snap, long now)
        throws InterruptedException
    {
        state = InternalState.AT_TRANSPORT;
        WorldPoint t = active.tile();
        if (t == null)
        {
            log.warn("walker: TRANSPORT step {} has no tile", stepIdx);
            return Status.ERROR;
        }

        // First: are we close enough to interact? If not, walk toward the
        // transport tile via the same BFS+picker path as handleWalk.
        boolean adjacent = snap.position.getPlane() == t.getPlane()
            && Math.abs(snap.position.getX() - t.getX()) <= TRANSPORT_ARRIVAL_RADIUS
            && Math.abs(snap.position.getY() - t.getY()) <= TRANSPORT_ARRIVAL_RADIUS;
        if (!adjacent)
        {
            // Walk toward the transport tile. Synthesize a 1x1 area for the
            // picker. Plane mismatch would have been caught by chooseStep
            // (CLIMB_UP / CLIMB_DOWN trigger arrival when planes match).
            if (snap.position.getPlane() != t.getPlane())
            {
                // Target plane unreachable from current plane without
                // another transport — caller's PathSpec is malformed.
                log.warn("walker: TRANSPORT step {} on plane {} but player on plane {}",
                    stepIdx, t.getPlane(), snap.position.getPlane());
                return Status.ERROR;
            }
            if (snap.reach == null) return Status.IN_PROGRESS;
            // pickTowards reads Perspective.localTo*, requires client thread.
            StepClickPicker.ClickTarget pick = clientCall(() ->
                picker.pickTowards(snap.reach, t));
            if (pick == null)
            {
                log.debug("walker: transport step {} — can't reach tile {} yet", stepIdx, t);
                return Status.IN_PROGRESS;
            }
            if (shouldClick(pick.tile, now))
            {
                log.debug("walker: walking toward transport {} via {} ({}px,{}px)",
                    t, pick.viaMinimap ? "minimap" : "canvas",
                    pick.canvasPixel.getX(), pick.canvasPixel.getY());
                dispatcher.clickCanvas(pick.canvasPixel.getX(), pick.canvasPixel.getY());
                lastClickTile = pick.tile;
                lastClickMs = now;
            }
            return Status.IN_PROGRESS;
        }

        // Adjacent — interact (with throttle).
        if (now - lastInteractMs < INTERACT_THROTTLE_MS)
        {
            // Wait for the previous interaction to settle before re-issuing.
            return Status.IN_PROGRESS;
        }
        ClickPair pair = clientCall(() -> {
            TransportResolver.Match m = resolver.findTransport(t, active.verb());
            if (m == null || !m.isSuccess())
            {
                // Verb missing — gate may already be open (only "Close"
                // visible) or we resolved the wrong tile. For OPEN, count
                // the "already open" walk-through as success.
                if (active.transportKind() == Waypoint.TransportKind.OPEN)
                {
                    TransportResolver.Match closeMatch =
                        resolver.findTransport(t, "Close");
                    if (closeMatch != null && closeMatch.isSuccess())
                    {
                        return ClickPair.alreadyOpen();
                    }
                }
                return ClickPair.missing();
            }
            Rectangle b = hullBoundsFromMatch(m);
            if (b == null) return ClickPair.missing();
            return ClickPair.click(b, m);
        });
        if (pair == null) return Status.IN_PROGRESS;
        if (pair.alreadyOpen)
        {
            log.info("walker: transport step {} {} already open — walking through",
                stepIdx, t);
            // Mark interact time so we don't hammer the same tile if the
            // player is held adjacent for a frame longer.
            lastInteractMs = now;
            lastTransportClicked = active;
            return Status.IN_PROGRESS;
        }
        if (pair.missing)
        {
            log.debug("walker: transport at {} verb '{}' not found yet", t, active.verb());
            return Status.IN_PROGRESS;
        }
        int cx = pair.bounds.x + pair.bounds.width / 2;
        int cy = pair.bounds.y + pair.bounds.height / 2;
        log.info("walker: clicking transport at {} verb '{}' ({}px,{}px)",
            t, active.verb(), cx, cy);
        dispatcher.clickCanvas(cx, cy);
        lastInteractMs = now;
        lastClickMs = now;
        lastTransportClicked = active;
        state = InternalState.CROSSING;
        return Status.IN_PROGRESS;
    }

    /** A click target derived from the resolver match, or a sentinel that
     *  the verb is missing (already open vs not loaded). */
    private static final class ClickPair
    {
        final Rectangle bounds;
        final TransportResolver.Match match;
        final boolean alreadyOpen;
        final boolean missing;
        private ClickPair(Rectangle b, TransportResolver.Match m,
                          boolean ao, boolean miss)
        {
            this.bounds = b; this.match = m;
            this.alreadyOpen = ao; this.missing = miss;
        }
        static ClickPair click(Rectangle b, TransportResolver.Match m)
        {
            return new ClickPair(b, m, false, false);
        }
        static ClickPair alreadyOpen() { return new ClickPair(null, null, true, false); }
        static ClickPair missing() { return new ClickPair(null, null, false, true); }
    }

    private boolean tryClickObstacle(ObstacleHandler.Result obs, long now)
        throws InterruptedException
    {
        if (now - lastInteractMs < INTERACT_THROTTLE_MS) return false;
        Rectangle b = obs.hullBounds;
        int cx = b.x + b.width / 2;
        int cy = b.y + b.height / 2;
        log.info("walker: obstacle click — verb '{}' at {} ({}px,{}px)",
            obs.match.matchedVerb(), obs.tile, cx, cy);
        dispatcher.clickCanvas(cx, cy);
        lastInteractMs = now;
        lastClickMs = now;
        return true;
    }

    private boolean shouldClick(WorldPoint targetTile, long now)
    {
        if (lastClickTile == null) return true;
        if (!lastClickTile.equals(targetTile)) return true;
        return (now - lastClickMs) >= RECLICK_AFTER_MS;
    }

    /** Read-side helper that hops to the client thread and returns a value.
     *  Equivalent to the {@code onClientThread(...)} pattern in
     *  LumbridgeBankPenScript. */
    @Nullable
    private <T> T clientCall(java.util.function.Supplier<T> sup) throws InterruptedException
    {
        AtomicReference<T> ref = new AtomicReference<>();
        java.util.concurrent.CountDownLatch latch =
            new java.util.concurrent.CountDownLatch(1);
        clientThread.invokeLater(() -> {
            try { ref.set(sup.get()); }
            catch (Throwable th) { log.warn("walker: clientCall failed", th); }
            finally { latch.countDown(); }
        });
        latch.await();
        return ref.get();
    }

    @Nullable
    private static Rectangle hullBoundsFromMatch(TransportResolver.Match m)
    {
        if (m.wallObject() != null)
        {
            var h = m.wallObject().getConvexHull();
            if (h != null) return h.getBounds();
        }
        if (m.gameObject() != null)
        {
            var h = m.gameObject().getConvexHull();
            if (h != null) return h.getBounds();
        }
        if (m.decorativeObject() != null)
        {
            var poly = m.decorativeObject().getCanvasTilePoly();
            if (poly != null) return poly.getBounds();
        }
        if (m.groundObject() != null)
        {
            var poly = m.groundObject().getCanvasTilePoly();
            if (poly != null) return poly.getBounds();
        }
        return null;
    }

    private static String describe(Waypoint w)
    {
        if (w == null) return "<null>";
        if (w.name() != null) return w.kind() + " " + w.name();
        return w.toString();
    }
}
