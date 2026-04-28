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
import net.runelite.client.sequence.internal.ActionRequest;

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
    public static final long RECLICK_AFTER_MS = 3_000;

    /** Player must have been still for at least this long since the last
     *  position change before we re-click. Mirror's V1's 2.5s rule:
     *  while the engine is walking the player toward our previous click,
     *  re-clicking the same step just queues redundant walk targets and
     *  cancels in-flight movement. Only re-click when the engine has
     *  stopped doing what we asked it to do. */
    public static final long STILL_THRESHOLD_MS = 2_500;

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
    /** Which step the last click was for. Click cadence is per-step: a
     *  step change triggers an immediate click, the next click on the
     *  same step waits for {@link #STILL_THRESHOLD_MS} of stillness AND
     *  {@link #RECLICK_AFTER_MS} since last click. */
    private int lastClickStepIdx = -1;
    /** Highest TRANSPORT step index we've explicitly clicked (or
     *  detected as already-open). Pass 1 of chooseStep uses this to
     *  ensure we don't advance past an OPEN/INTERACT transport just
     *  because the player happens to be inside the bbox of the
     *  WALK_AREA on the OTHER side of the fence (the static
     *  {@link #arrived} check for OPEN is mere adjacency — it can't
     *  tell whether we've actually crossed). */
    private int lastClickedTransportIdx = -1;
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
        lastClickStepIdx = -1;
        lastClickedTransportIdx = -1;
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
        int newIdx = chooseStep(spec, snap.position, stepIdx, lastClickedTransportIdx);
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
     *  indices ≥ {@code minIdx}. Three-pass strategy mirroring V1:
     *
     *  <ol>
     *    <li><b>Containing area wins outright.</b> If a WALK_AREA contains
     *        the player's tile, advance past it (= go to next step).</li>
     *    <li><b>Past transports.</b> Find the highest CLIMB the player
     *        has descended/ascended past. The next walking step is
     *        somewhere after that.</li>
     *    <li><b>Closest WALK_AREA on the player's plane.</b> Among
     *        WALK_AREA steps after the last transport-passed, pick the
     *        one closest to the player by Chebyshev distance to the area
     *        centre. This is V1's {@code closestLandmarkIdx}: a player
     *        BETWEEN two landmarks gets snapped to the nearer one and
     *        walks forward from there.</li>
     *  </ol>
     *
     *  <p>If no WALK_AREA matches the player's plane, fall back to the
     *  next transport — typically a CLIMB up/down the player needs to
     *  do. */
    private static int chooseStep(PathSpec spec, WorldPoint pos, int minIdx,
                                  int lastClickedTransportIdx)
    {
        List<Waypoint> wps = spec.waypoints();

        // Pass 1: WALK_AREA containing the player.
        int containedIdx = -1;
        for (int i = minIdx; i < wps.size(); i++)
        {
            Waypoint w = wps.get(i);
            if (w.kind() != Waypoint.Kind.WALK_AREA) continue;
            WorldArea a = w.area();
            if (a == null) continue;
            if (a.getPlane() == pos.getPlane()
                && pos.getX() >= a.getX() && pos.getX() < a.getX() + a.getWidth()
                && pos.getY() >= a.getY() && pos.getY() < a.getY() + a.getHeight())
            {
                containedIdx = i;
            }
        }
        if (containedIdx >= 0)
        {
            // Block: if any TRANSPORT between minIdx and containedIdx
            // hasn't been finished, return that transport's index — we
            // can't advance through a gate/staircase just because the
            // player happens to be inside the bbox of the WALK_AREA on
            // the other side. CLIMB done = plane crossed (arrived());
            // OPEN/INTERACT done = explicit click recorded in
            // lastClickedTransportIdx.
            int blocking = unfinishedTransportBefore(
                wps, minIdx, containedIdx, pos, lastClickedTransportIdx);
            if (blocking >= 0) return blocking;
            return Math.min(containedIdx + 1, wps.size());
        }

        // STAY-ON-TRANSPORT — never skip past an unfinished transport.
        if (minIdx < wps.size())
        {
            Waypoint cur = wps.get(minIdx);
            if (cur.kind() == Waypoint.Kind.TRANSPORT
                && !transportFinished(cur, minIdx, pos, lastClickedTransportIdx))
            {
                return minIdx;
            }
        }

        // Pass 2: highest TRANSPORT the player has passed.
        int highestTransportArrived = -1;
        for (int i = minIdx; i < wps.size(); i++)
        {
            Waypoint w = wps.get(i);
            if (w.kind() == Waypoint.Kind.TRANSPORT
                && transportFinished(w, i, pos, lastClickedTransportIdx))
            {
                highestTransportArrived = i;
            }
        }
        int searchFrom = Math.max(minIdx, highestTransportArrived + 1);

        // Cap forward scan at the next unfinished TRANSPORT.
        int upperBound = wps.size();
        for (int i = searchFrom; i < wps.size(); i++)
        {
            Waypoint w = wps.get(i);
            if (w.kind() == Waypoint.Kind.TRANSPORT
                && !transportFinished(w, i, pos, lastClickedTransportIdx))
            {
                upperBound = i;
                break;
            }
        }

        // Pass 3: closest WALK_AREA on the player's plane between
        // searchFrom and upperBound.
        int closestIdx = -1;
        int closestDist = Integer.MAX_VALUE;
        for (int i = searchFrom; i < upperBound; i++)
        {
            Waypoint w = wps.get(i);
            if (w.kind() != Waypoint.Kind.WALK_AREA) continue;
            WorldArea a = w.area();
            if (a == null || a.getPlane() != pos.getPlane()) continue;
            int cx = a.getX() + a.getWidth() / 2;
            int cy = a.getY() + a.getHeight() / 2;
            int d = Math.max(Math.abs(pos.getX() - cx),
                             Math.abs(pos.getY() - cy));
            if (d < closestDist)
            {
                closestDist = d;
                closestIdx = i;
            }
        }
        if (closestIdx >= 0) return closestIdx;

        // No WALK_AREA on player's plane in the bounded range — must
        // be mid-staircase or already at upperBound transport. Stay.
        return searchFrom;
    }

    /** True iff this TRANSPORT step is "done" — the walker should
     *  consider it traversed and let chooseStep advance past it.
     *  Definition differs by kind:
     *  <ul>
     *    <li>CLIMB_UP / CLIMB_DOWN: destination plane reached
     *        (delegates to {@link #arrived}).</li>
     *    <li>OPEN / INTERACT: walker has explicitly clicked the
     *        verb at this step. Adjacency alone isn't enough — a
     *        player can be adjacent to a closed gate without ever
     *        having opened it; advancing past in that case bypasses
     *        the gate-handling pipeline.</li>
     *  </ul>
     */
    private static boolean transportFinished(Waypoint w, int idx, WorldPoint pos,
                                             int lastClickedTransportIdx)
    {
        if (w == null || w.kind() != Waypoint.Kind.TRANSPORT) return false;
        Waypoint.TransportKind tk = w.transportKind();
        if (tk == Waypoint.TransportKind.CLIMB_UP
            || tk == Waypoint.TransportKind.CLIMB_DOWN)
        {
            return arrived(w, pos);
        }
        // OPEN / INTERACT — require explicit click recorded.
        return idx <= lastClickedTransportIdx;
    }

    /** Find the lowest-indexed unfinished TRANSPORT in
     *  {@code [from, until)}. Returns -1 if none. Used by chooseStep
     *  to block Pass 1 from advancing past gates the walker hasn't
     *  actually clicked. */
    private static int unfinishedTransportBefore(List<Waypoint> wps,
                                                 int from, int until,
                                                 WorldPoint pos,
                                                 int lastClickedTransportIdx)
    {
        for (int i = from; i < until; i++)
        {
            Waypoint w = wps.get(i);
            if (w.kind() != Waypoint.Kind.TRANSPORT) continue;
            if (!transportFinished(w, i, pos, lastClickedTransportIdx)) return i;
        }
        return -1;
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
                if (w.transportKind() == Waypoint.TransportKind.CLIMB_DOWN)
                {
                    // Source plane = t.plane. Past this climb iff player has
                    // descended below it. NOT just "different plane" — a
                    // player still on a higher plane than the source hasn't
                    // started yet.
                    return pos.getPlane() < t.getPlane();
                }
                if (w.transportKind() == Waypoint.TransportKind.CLIMB_UP)
                {
                    return pos.getPlane() > t.getPlane();
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
        if (shouldClick(now))
        {
            log.info("walker: clicking {} ({}px,{}px) toward step {} {} (sinceClick={}ms, sinceMove={}ms)",
                pick.viaMinimap ? "minimap" : "canvas",
                pick.canvasPixel.getX(), pick.canvasPixel.getY(),
                stepIdx, describe(active),
                lastClickMs == 0 ? "never" : (now - lastClickMs) + "",
                now - lastMovementMs);
            dispatcher.clickCanvas(pick.canvasPixel.getX(), pick.canvasPixel.getY());
            lastClickTile = pick.tile;
            lastClickMs = now;
            lastClickStepIdx = stepIdx;
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
            if (shouldClick(now))
            {
                log.info("walker: walking toward transport {} via {} ({}px,{}px) (sinceClick={}ms, sinceMove={}ms)",
                    t, pick.viaMinimap ? "minimap" : "canvas",
                    pick.canvasPixel.getX(), pick.canvasPixel.getY(),
                    lastClickMs == 0 ? "never" : (now - lastClickMs) + "",
                    now - lastMovementMs);
                dispatcher.clickCanvas(pick.canvasPixel.getX(), pick.canvasPixel.getY());
                lastClickTile = pick.tile;
                lastClickMs = now;
                lastClickStepIdx = stepIdx;
            }
            return Status.IN_PROGRESS;
        }

        // Adjacent — interact (with throttle).
        if (now - lastInteractMs < INTERACT_THROTTLE_MS)
        {
            // Wait for the previous interaction to settle before re-issuing.
            return Status.IN_PROGRESS;
        }
        // Wait for the player to STOP animating before clicking the verb.
        // V1's rule: never invoke a stairs/gate menu while the player is
        // mid-walk — the engine drops the click and we fall back to a
        // wrong-verb canvas resolution at the cursor pixel (e.g. wrong
        // gate). Reads getPoseAnimation() == getIdlePoseAnimation() on
        // the client thread.
        Boolean settled = clientCall(() -> {
            Player self = client.getLocalPlayer();
            if (self == null) return false;
            return self.getPoseAnimation() == self.getIdlePoseAnimation();
        });
        if (settled == null || !settled)
        {
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
            lastClickedTransportIdx = stepIdx;
            return Status.IN_PROGRESS;
        }
        if (pair.missing)
        {
            log.debug("walker: transport at {} verb '{}' not found yet", t, active.verb());
            return Status.IN_PROGRESS;
        }
        // Dispatch via CLICK_GAME_OBJECT — the dispatcher's pixel
        // resolver samples a point INSIDE the convex hull (not just the
        // bounds-rect centre, which can fall outside the hull for thin
        // / diagonal models like fence gates and staircase rails),
        // hovers, and either left-clicks (verb is L-click default) or
        // right-clicks + selects the matching menu row.
        log.info("walker: dispatching CLICK_GAME_OBJECT at {} verb '{}'",
            t, active.verb());
        ActionRequest req = ActionRequest.builder()
            .kind(ActionRequest.Kind.CLICK_GAME_OBJECT)
            .channel(ActionRequest.Channel.MOUSE)
            .tile(t)
            .verb(active.verb())
            .build();
        dispatcher.dispatch(req);
        lastInteractMs = now;
        lastClickedTransportIdx = stepIdx;
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
        // Same hull-pixel concern as handleTransport — route through
        // the dispatcher's CLICK_GAME_OBJECT instead of bounds-centre.
        log.info("walker: obstacle dispatch CLICK_GAME_OBJECT at {} verb '{}'",
            obs.tile, obs.match.matchedVerb());
        ActionRequest req = ActionRequest.builder()
            .kind(ActionRequest.Kind.CLICK_GAME_OBJECT)
            .channel(ActionRequest.Channel.MOUSE)
            .tile(obs.tile)
            .verb(obs.match.matchedVerb())
            .build();
        dispatcher.dispatch(req);
        lastInteractMs = now;
        lastClickMs = now;
        return true;
    }

    /** V1-style click cadence — re-click only when the active step has
     *  changed OR (the player has been still for {@link #STILL_THRESHOLD_MS}
     *  AND it's been {@link #RECLICK_AFTER_MS} since the last click).
     *
     *  <p>Earlier this compared the picked TILE; the BFS picks a different
     *  tile every tick (player position shifts, BFS frontier shifts), so
     *  the comparison was always "tile changed" and clicks fired on every
     *  tick. The fix is to compare the STEP — the leg the walker is on.
     *  A step is a stable unit; the picked tile inside it is not. */
    private boolean shouldClick(long now)
    {
        boolean stepChanged = lastClickStepIdx != stepIdx;
        if (stepChanged) return true;
        long sinceClick = lastClickMs == 0 ? Long.MAX_VALUE : now - lastClickMs;
        long sinceMove = now - lastMovementMs;
        boolean stillWalking = sinceMove < STILL_THRESHOLD_MS;
        boolean recentClick = sinceClick < RECLICK_AFTER_MS;
        return !recentClick && !stillWalking;
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
