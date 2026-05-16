package net.runelite.client.plugins.recorder.nav.v2;

import java.util.Random;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.worldmap.TransportEdge;

/** Per-tick state machine that turns a {@link V2Path} into walk-clicks,
 *  minimap-clicks, and transport verb-clicks. Lives behind {@link
 *  V2Navigator}; the Navigator owns the planner and feeds new paths in
 *  via {@link #setPath}.
 *
 *  <p>Round 2 (transport execution): the executor walks legs in
 *  sequence, scoping click candidates to the CURRENT leg only — never
 *  flattening tiles across legs/planes. WALK legs drive walk-here
 *  clicks; TRANSPORT legs walk to the approach tile and dispatch a
 *  verb-click on the matched live object, then poll for the player to
 *  reach the destination plane.
 *
 *  <p>Modality selection: canvas by default; switches to minimap when
 *  the recent canvas filter rejection rate is high. {@link
 *  Modality#WORLDMAP} is reserved and throws on use — deferred to V2.5.
 *
 *  <p>Stall detection: if the player hasn't progressed for {@link
 *  #STALL_TICKS} ticks on a walk leg, the executor delegates to {@link
 *  InvalidationClassifier} for a typed recovery — bounded catch-up
 *  click, or FAILED for static-collision/transport-state mismatches so
 *  the Navigator can replan. */
@Slf4j
public final class V2Executor
{
    /** Run-toggle stub — round 1 honors only {@link #UNCHANGED}. Other
     *  values log a warning and fall through. Per spec lines 277-284. */
    public enum RunMode { UNCHANGED, ON, OFF }

    /** Click modalities the executor can pick for the current tick.
     *  Round-1 active: {@link #CANVAS}, {@link #MINIMAP}. {@link #WORLDMAP}
     *  is reserved for V2.5; selecting it throws. */
    public enum Modality { CANVAS, MINIMAP, WORLDMAP }

    public enum Status { IDLE, RUNNING, ARRIVED, FAILED }

    /** Tagged reason attached to a {@link Status#FAILED} transition.
     *  {@code null} while RUNNING / ARRIVED / IDLE. */
    public enum FailureReason
    {
        /** Reserved for transport KINDS that round-2 transport executor
         *  still cannot drive (e.g. agility shortcuts requiring multi-step
         *  flows beyond a single verb-click). The standard Lumbridge-style
         *  staircase / ladder / door is implemented and does NOT surface
         *  this reason. */
        TRANSPORT_EXECUTOR_MISSING,
        /** Defense-in-depth: repeated candidate picks resolved to
         *  tiles on a plane different from the player's current
         *  plane, beyond the per-leg bound. Indicates a malformed
         *  route or planner bug. */
        CROSS_PLANE_CANDIDATES_EXHAUSTED,
        /** Spec HARD CONSTRAINT exhaust path: every canvas candidate
         *  on the leg was rejected by the strict-walk gate
         *  ({@code isLeftClickWalk} mismatch — overlapping tree /
         *  ground item / NPC) up to {@link #MAX_CLICK_REJECTS_PER_LEG}.
         *  The navigator should replan from a fresh world view. */
        UNSAFE_CANVAS_CLICK_EXHAUSTED,
        /** Stall classifier returned STATIC_COLLISION_MISMATCH or
         *  TRANSPORT_STATE_MISMATCH — replanning is the right move,
         *  retrying the same tile is a waste. */
        STALL_CLASSIFIER_REPLAN,
        /** Catch-up budget exhausted on a stalled leg
         *  ({@link #MAX_CATCHUP_CLICKS_PER_LEG}). */
        CATCHUP_EXHAUSTED,
        /** Player location went null and stayed null for more than
         *  {@link #MAX_NO_PLAYER_LOC_TICKS} ticks. */
        PLAYER_LOC_LOST,
        /** Both modalities returned no candidate for more than
         *  {@link #MAX_NO_CANDIDATE_TICKS} consecutive ticks before any
         *  click was dispatched. */
        NO_CANDIDATE_AVAILABLE,
        /** Transport leg: no live object on the recorded tile (or any
         *  1-ring neighbor) advertises the planned verb. The recorded
         *  edge is stale or the scene hasn't streamed in yet. */
        TRANSPORT_OBJECT_NOT_FOUND,
        /** Transport leg: live object exists but does not advertise the
         *  planned verb (different impostor state, e.g. door already
         *  open). Today {@link #TRANSPORT_OBJECT_NOT_FOUND} subsumes this
         *  case — the resolver returns null when the verb isn't on the
         *  object. Reserved for future split-out. */
        TRANSPORT_ACTION_MISSING,
        /** Transport leg: dispatcher refused the verb-click (busy /
         *  cross-plane refusal / null match). The script's outer loop
         *  decides whether to retry or fall back. */
        TRANSPORT_CLICK_FAILED,
        /** Transport leg: clicked, but the player did not reach the
         *  destination plane within {@link #TRANSPORT_TIMEOUT_TICKS}. */
        TRANSPORT_TIMEOUT,
        /** Transport leg: player is on neither the from-plane nor the
         *  to-plane. Out-of-band state; navigator should replan. */
        TRANSPORT_PLANE_MISMATCH,
        /** Transport kind is recognised but the executor has no live
         *  driver for it yet (reserved — round-2 covers verb-on-object
         *  transports; multi-step shortcuts surface this). */
        TRANSPORT_UNSUPPORTED,
        /** Defensive: leg index advanced past the end of the path
         *  unexpectedly, or the leg type is neither WALK nor TRANSPORT. */
        NO_CURRENT_LEG,
        /** A picker returned a candidate that does not belong to the
         *  current leg (would be a planner / picker bug). Retained as
         *  an explicit channel; round-2 picker is leg-scoped so this
         *  should never fire. */
        FUTURE_LEG_CANDIDATE_REJECTED,
        /** Round-2 progress invariant: every forward candidate has been
         *  filtered out and the executor cannot pick a tile strictly
         *  ahead of the leg's progress cursor. Surfaces a leg whose
         *  forward tail was entirely entity-contaminated / blacklisted
         *  / reachable past the end. */
        NO_FORWARD_CANDIDATE,
        /** Round-2 transport correction: the verb-click succeeded
         *  (player plane changed) but the destination plane / tile does
         *  NOT match the planned edge. The executor self-corrects the
         *  TransportIndex entry and signals replan-from-here so the
         *  navigator routes from the player's actual position. The
         *  Navigator handles this as a transient — does NOT propagate
         *  FAILED upward unless the replan budget is exhausted. */
        TRANSPORT_RESULT_MISMATCH,
        /** Round-2 direction validation: the planner emitted a
         *  TRANSPORT leg whose {@code fromTile.plane} doesn't match the
         *  player's plane, or whose {@code toTile.plane} doesn't match
         *  the next WALK leg's plane. The executor refuses to drive a
         *  staircase that climbs the wrong way. */
        TRANSPORT_EDGE_DIRECTION_MISSING,
        /** Round-2 gate handling: the executor detected a static-collision
         *  mismatch (snapshot says walkable, live says blocked) AND
         *  could not find an adjacent object advertising "Open". The
         *  navigator should replan or fall back. */
        OPENABLE_BLOCKER_NOT_FOUND,
        /** Round-2 gate handling: an Open verb-click was dispatched
         *  but the live collision flag did not flip walkable within
         *  {@link #OPENABLE_TIMEOUT_TICKS}. Either the click missed
         *  or the gate is permanently locked. */
        OPENABLE_BLOCKER_TIMEOUT,
        /** Catch-all — kept for tests that don't care about the
         *  precise enum case. New code paths should pick a more
         *  specific value. */
        OTHER
    }

    /** Live-state seam. Production binds these to {@link
     *  net.runelite.api.Client}, {@link net.runelite.client.callback.ClientThread},
     *  {@link EmptyTileFilter}, {@link MinimapClicker}, and {@link
     *  net.runelite.client.sequence.dispatch.HumanizedInputDispatcher}.
     *  Tests inject deterministic fakes. */
    public interface Env
    {
        @Nullable WorldPoint playerLoc();
        boolean isPlausiblyClean(WorldPoint tile);
        boolean canMinimapClick(WorldPoint tile);
        boolean dispatchWalk(WorldPoint tile);
        boolean dispatchMinimap(WorldPoint tile);
        boolean dispatcherBusy();
        long nowMs();

        default boolean snapshotSaysWalkable(WorldPoint tile) { return true; }
        default boolean liveCollisionAllows(WorldPoint tile) { return true; }
        default boolean dynamicEntityOnTile(WorldPoint tile) { return false; }
        @Nullable default String lastDispatchError() { return null; }

        /** Resolve which tile to click for a transport with the given
         *  {@code objectId} + {@code verb}, starting at {@code fromTile}.
         *  Production wraps {@link
         *  net.runelite.client.plugins.recorder.transport.TransportResolver}
         *  and searches {@code fromTile} first, then the 8-neighbor ring
         *  (covers door / wall-object cases where the click target lives
         *  on a tile adjacent to the recorded standing tile).
         *  Returns {@code null} when no live object on those tiles
         *  advertises the verb — the executor surfaces {@link
         *  FailureReason#TRANSPORT_OBJECT_NOT_FOUND}. */
        @Nullable default WorldPoint resolveTransportClickTile(int objectId, String verb,
                                                               WorldPoint fromTile)
        { return null; }

        /** Dispatch a CLICK_GAME_OBJECT for the verb on the resolved
         *  click tile. Returns {@code true} if the dispatcher accepted
         *  the request (busy flag now held). The executor flips into
         *  WAITING_FOR_TRANSPORT and polls plane change. */
        default boolean dispatchTransport(WorldPoint clickTile, String verb)
        { return false; }

        /** Self-correction hook: live transport actually moved player to
         *  {@code actualToTile} but the planned edge said
         *  {@code plannedToTile}. Production rewrites the {@link
         *  net.runelite.client.plugins.recorder.worldmap.TransportIndex}
         *  entry under the same key (fromTile + verb + objectId) so the
         *  next plan uses correct data. Tests record the call. */
        default void correctTransportEdge(int objectId, String verb,
                                          WorldPoint fromTile,
                                          WorldPoint plannedToTile,
                                          WorldPoint actualToTile,
                                          WorldPoint approachTile,
                                          int regionId)
        { /* default: no-op (tests inject behavior) */ }

        /** Look at {@code blockedTile} and its 1-ring of neighbors for a
         *  wall/game/decorative/ground object whose menu actions contain
         *  "Open". Returns the click tile (where to dispatch the Open
         *  verb-click), or {@code null} when no openable found.
         *  Mirrors {@link #resolveTransportClickTile} for the gate case. */
        @Nullable default WorldPoint findOpenableNear(WorldPoint blockedTile)
        { return null; }

        /** Dispatch a CLICK_GAME_OBJECT with verb "Open" on the resolved
         *  tile. Returns true if the dispatcher accepted the request. */
        default boolean dispatchOpen(WorldPoint clickTile)
        { return false; }

        /** Lane 5 plan Task 1: collapse all per-tick reads into one
         *  client-thread marshal. Production impl ({@link V2ExecutorEnv})
         *  marshals once and reads {@code playerLoc}, candidate-tile
         *  filter pass, live collision, dynamic-entity occupancy,
         *  snapshot walkability, and minimap reachability in one go.
         *
         *  <p>Default delegates back to the individual methods (1 wait
         *  each) for envs that haven't migrated. Tests can override to
         *  count the bundled-read invocation count. */
        default TickReadOut snapshotForTick(@Nullable WorldPoint candidateTile)
        {
            WorldPoint p = playerLoc();
            boolean clean = candidateTile == null || isPlausiblyClean(candidateTile);
            boolean live = candidateTile == null || liveCollisionAllows(candidateTile);
            boolean dyn = candidateTile != null && dynamicEntityOnTile(candidateTile);
            boolean snap = candidateTile == null || snapshotSaysWalkable(candidateTile);
            boolean mm = candidateTile == null || canMinimapClick(candidateTile);
            return new TickReadOut(p, clean, live, dyn, snap, mm);
        }

        /** Immutable bundle of per-tick state reads. Built once per
         *  {@link V2Executor#tick()} call from {@link #snapshotForTick}. */
        record TickReadOut(
            @Nullable WorldPoint playerLoc,
            boolean candidateClean,
            boolean liveCollisionAllows,
            boolean dynamicEntityOnTile,
            boolean snapshotSaysWalkable,
            boolean canMinimapClick) {}
    }

    /** Ticks of zero progress before treating a WALK leg as stalled. */
    public static final int STALL_TICKS = 4;
    /** Maximum catch-up clicks per leg before bailing to FAILED. */
    public static final int MAX_CATCHUP_CLICKS_PER_LEG = 2;
    /** Maximum consecutive strict-walk rejections per leg. */
    public static final int MAX_CLICK_REJECTS_PER_LEG = 5;
    /** Defense-in-depth bound for cross-plane candidate rejections. */
    public static final int MAX_CROSS_PLANE_REJECTS_PER_ROUTE = 5;
    /** Bound on consecutive null-playerLoc ticks. */
    public static final int MAX_NO_PLAYER_LOC_TICKS = 20;
    /** Bound on consecutive ticks with no candidate from either modality. */
    public static final int MAX_NO_CANDIDATE_TICKS = 8;
    /** Ticks to wait after a transport click for the player to reach
     *  the destination plane. 15 ticks ≈ 9 s — generous enough to
     *  absorb staircase animations + scene rebuild, short enough to
     *  surface a missed click before the script's outer loop times out. */
    public static final int TRANSPORT_TIMEOUT_TICKS = 15;
    /** Chebyshev distance considered "at the approach tile" — the
     *  executor will dispatch the verb-click as soon as the player is
     *  this close. Matches V1's TRANSPORT_ADJACENCY behaviour. */
    public static final int TRANSPORT_APPROACH_CHEBYSHEV = 1;
    /** Ticks to wait after dispatching an Open verb-click for the
     *  blocked tile's live collision to flip walkable. */
    public static final int OPENABLE_TIMEOUT_TICKS = 8;
    /** Maximum Open verb-clicks per leg before falling through to normal
     *  stall handling. Bounds the wall-edge fallback in case the openable
     *  isn't actually the blocker (false-positive 1-ring scan match) or
     *  the gate refuses to open (locked, decorative). */
    public static final int MAX_OPENABLE_ATTEMPTS_PER_LEG = 3;

    private static final int FILTER_REJECT_WINDOW = 4;
    private static final double MINIMAP_BIAS_THRESHOLD = 0.5;

    /** Phase-13 click-improvement sub-step toggles. */
    public interface Toggles
    {
        default boolean variableDistance() { return true; }
        default boolean minimapModality() { return true; }
        default boolean catchupClicks() { return true; }

        Toggles ALL_ON = new Toggles() {};
    }

    private final Env env;
    private final CanvasTilePicker canvasPicker;
    private final InvalidationClassifier classifier;
    private final Random rng;
    private final Toggles toggles;

    @Nullable private V2Path path;
    private int legIdx;
    private int catchupClicksThisLeg;
    private int clickRejectsThisLeg;
    private int crossPlaneRejectsThisRoute;
    private int ticksSinceProgress;
    private int consecutiveNullPlayerLocTicks;
    private int consecutiveNoCandidateTicks;
    @Nullable private WorldPoint lastPlayerLoc;
    @Nullable private WorldPoint lastDispatchedTile;
    private boolean lastDispatchWasMinimap;
    private final boolean[] recentRejects = new boolean[FILTER_REJECT_WINDOW];
    private int rejectCursor;
    private RunMode runMode = RunMode.UNCHANGED;
    private Status status = Status.IDLE;
    @Nullable private FailureReason lastFailureReason;

    /** Transport-leg state — set true when the executor dispatches the
     *  verb-click for the current TRANSPORT leg, cleared on advance. */
    private boolean transportClicked;
    /** Ticks since the transport click; bounded by {@link #TRANSPORT_TIMEOUT_TICKS}. */
    private int ticksSinceTransportClick;

    /** Round-2 progress cursor: highest leg-tile index the player has
     *  reached on the current WALK leg. Picker uses this as a strict
     *  floor — candidates with idx <= progressIdx are rejected so the
     *  bot never clicks backwards along a leg. {@code -1} = uninitialized
     *  (recomputed at the next walk-leg tick). */
    private int progressIdx;

    /** Round-2 replan signal: when set, the navigator should cancel the
     *  active path and replan from the player's current position before
     *  surfacing FAILED. Used after {@link FailureReason#TRANSPORT_RESULT_MISMATCH}
     *  so a wrong-toTile transport edge gets corrected and re-routed
     *  rather than aborting the script. */
    private boolean wantsReplanFromHere;

    /** Round-2 gate handling: tile we detected as static-collision-blocked
     *  and dispatched an Open verb-click for. Cleared once the live
     *  collision flips walkable or the timeout fires. */
    @Nullable private WorldPoint pendingOpenTile;
    /** Ticks elapsed since the last Open verb-click was dispatched. */
    private int ticksSincePendingOpen;
    /** True when the pending Open click was triggered by the wall-edge
     *  fallback rather than a static-collision mismatch. Wall-edge gates
     *  don't set BLOCK_MOVEMENT_FULL on the blocked tile (only directional
     *  flags on adjacent edges), so {@code liveCollisionAllows} is always
     *  true and the polling loop must wait on dispatcher-idle instead. */
    private boolean pendingOpenIsWallEdge;
    /** Per-leg counter — number of Open verb-clicks dispatched. Bounded
     *  by {@link #MAX_OPENABLE_ATTEMPTS_PER_LEG}. */
    private int openableAttemptsThisLeg;

    public V2Executor(Env env, CanvasTilePicker canvasPicker,
                      InvalidationClassifier classifier, Random rng)
    {
        this(env, canvasPicker, classifier, rng, Toggles.ALL_ON);
    }

    public V2Executor(Env env, CanvasTilePicker canvasPicker,
                      InvalidationClassifier classifier, Random rng,
                      Toggles toggles)
    {
        this.env = env;
        this.canvasPicker = canvasPicker;
        this.classifier = classifier;
        this.rng = rng;
        this.toggles = toggles == null ? Toggles.ALL_ON : toggles;
    }

    /** Start executing a new path. Clears all per-leg state and
     *  resets the classifier's attempt-local blacklist. */
    public void setPath(V2Path newPath)
    {
        this.path = newPath;
        this.legIdx = 0;
        this.catchupClicksThisLeg = 0;
        this.clickRejectsThisLeg = 0;
        this.crossPlaneRejectsThisRoute = 0;
        this.ticksSinceProgress = 0;
        this.consecutiveNullPlayerLocTicks = 0;
        this.consecutiveNoCandidateTicks = 0;
        this.lastPlayerLoc = null;
        this.lastDispatchedTile = null;
        this.lastDispatchWasMinimap = false;
        this.rejectCursor = 0;
        for (int i = 0; i < recentRejects.length; i++) recentRejects[i] = false;
        this.classifier.resetForNewRoute();
        this.lastFailureReason = null;
        this.transportClicked = false;
        this.ticksSinceTransportClick = 0;
        this.progressIdx = -1;
        this.wantsReplanFromHere = false;
        this.pendingOpenTile = null;
        this.ticksSincePendingOpen = 0;
        this.pendingOpenIsWallEdge = false;
        this.openableAttemptsThisLeg = 0;
        if (newPath == null || newPath.isEmpty())
        {
            this.status = Status.IDLE;
            return;
        }
        logRouteSummary(newPath);
        this.status = Status.RUNNING;
    }

    /** Round-2 route summary: dump per-leg shape + plane/region/transport
     *  inventory + bounding box so a live failure can be diagnosed without
     *  re-running. Replaces the per-leg block in the previous setPath. */
    private void logRouteSummary(V2Path p)
    {
        int totalTiles = 0;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        java.util.Set<Integer> planes = new java.util.TreeSet<>();
        java.util.Set<Integer> regions = new java.util.TreeSet<>();
        int transportCount = 0;
        java.util.List<WorldPoint> firstTiles = new java.util.ArrayList<>();
        java.util.List<WorldPoint> lastTiles = new java.util.ArrayList<>();
        java.util.List<WorldPoint> allTiles = p.allTiles();
        for (int i = 0; i < Math.min(10, allTiles.size()); i++) firstTiles.add(allTiles.get(i));
        for (int i = Math.max(0, allTiles.size() - 10); i < allTiles.size(); i++) lastTiles.add(allTiles.get(i));
        for (V2Leg leg : p.legs())
        {
            if (leg instanceof V2Leg.Walk w)
            {
                totalTiles += w.tiles().size();
                regions.add(w.regionId());
                for (WorldPoint t : w.tiles())
                {
                    planes.add(t.getPlane());
                    if (t.getX() < minX) minX = t.getX();
                    if (t.getX() > maxX) maxX = t.getX();
                    if (t.getY() < minY) minY = t.getY();
                    if (t.getY() > maxY) maxY = t.getY();
                }
            }
            else if (leg instanceof V2Leg.Transport t)
            {
                transportCount++;
                TransportEdge e = t.edge();
                planes.add(e.fromTile().getPlane());
                planes.add(e.toTile().getPlane());
                regions.add(e.regionId());
            }
        }
        log.info("v2-executor: route accepted legs={} routeId={} totalTiles={} planes={} regions={} transports={} bbox=[{},{} → {},{}] cost={}",
            p.legs().size(), p.routeId(), totalTiles, planes, regions, transportCount,
            minX, minY, maxX, maxY, p.totalCost());
        log.info("v2-executor: route firstTiles={} lastTiles={}", firstTiles, lastTiles);
        for (int i = 0; i < p.legs().size(); i++)
        {
            V2Leg leg = p.legs().get(i);
            if (leg instanceof V2Leg.Walk w)
            {
                log.info("v2-executor: leg={} type=WALK plane={} tiles={} start={} end={}",
                    i, w.start().getPlane(), w.tiles().size(), w.start(), w.end());
            }
            else if (leg instanceof V2Leg.Transport t)
            {
                TransportEdge e = t.edge();
                log.info("v2-executor: leg={} type=TRANSPORT verb={} objectId={} from={} to={} approach={}",
                    i, e.verb(), e.objectId(), e.fromTile(), e.toTile(), e.approachTile());
            }
        }
    }

    public void cancel()
    {
        this.path = null;
        this.status = Status.IDLE;
        this.lastFailureReason = null;
        this.transportClicked = false;
        this.ticksSinceTransportClick = 0;
        this.progressIdx = -1;
        this.wantsReplanFromHere = false;
        this.pendingOpenTile = null;
        this.ticksSincePendingOpen = 0;
    }

    /** Round-2 replan signal. The Navigator reads this after each tick;
     *  when {@code true}, it should cancel the active path and replan
     *  from the player's current world location instead of propagating
     *  FAILED. The flag clears on the next {@link #setPath} or
     *  {@link #cancel}. */
    public boolean wantsReplanFromHere() { return wantsReplanFromHere; }

    /** Round-1 stub. Logs a warning and falls through to UNCHANGED. */
    public void setRunMode(RunMode mode)
    {
        if (mode == null) return;
        if (mode != RunMode.UNCHANGED)
        {
            log.warn("v2-executor: run-toggle {} not implemented round 1; "
                + "falling through to UNCHANGED", mode);
        }
        this.runMode = RunMode.UNCHANGED;
    }

    public Status status() { return status; }

    @Nullable public FailureReason lastFailureReason() { return lastFailureReason; }

    /** Currently-executing leg index. Zero-based; advances when a leg
     *  completes. Exposed for tests + diagnostics. */
    public int currentLegIndex() { return legIdx; }

    /** True iff the executor has dispatched the verb-click for the
     *  current TRANSPORT leg and is polling for the destination plane. */
    public boolean isWaitingForTransport() { return transportClicked; }

    /** Drive one tick of execution. */
    public Status tick()
    {
        if (path == null || path.isEmpty()) { status = Status.IDLE; return status; }
        if (status != Status.RUNNING) return status;
        // A replan was requested (transport mismatch) — wait for the
        // navigator to call setPath / cancel. Don't dispatch on a route
        // we already know is invalid.
        if (wantsReplanFromHere) return status;

        WorldPoint here = env.playerLoc();
        if (here == null)
        {
            consecutiveNullPlayerLocTicks++;
            if (consecutiveNullPlayerLocTicks >= MAX_NO_PLAYER_LOC_TICKS)
            {
                log.warn("v2-executor: PLAYER_LOC_LOST — null playerLoc for {} consecutive ticks; FAILED",
                    consecutiveNullPlayerLocTicks);
                status = Status.FAILED;
                lastFailureReason = FailureReason.PLAYER_LOC_LOST;
            }
            return status;
        }
        consecutiveNullPlayerLocTicks = 0;

        // Defensive: legIdx past the end → ARRIVED.
        if (legIdx >= path.legs().size())
        {
            status = Status.ARRIVED;
            log.info("v2-executor: arrived (all {} legs complete) player={}",
                path.legs().size(), here);
            return status;
        }

        // Overall path-end fast path — covers walk-only paths where the
        // player is already at the destination tile.
        WorldPoint dest = pathEnd();
        if (dest != null && here.equals(dest))
        {
            status = Status.ARRIVED;
            log.info("v2-executor: arrived at {}", dest);
            return status;
        }

        if (env.dispatcherBusy()) return status;

        V2Leg currentLeg = path.legs().get(legIdx);

        // Strict-walk rejection handling — only meaningful for walk-leg
        // dispatches. Transport verb-clicks have their own success channel
        // (resolveTransportClickTile + plane change).
        if (!(currentLeg instanceof V2Leg.Transport))
        {
            String lastErr = env.lastDispatchError();
            if (lastErr != null && lastDispatchedTile != null && !lastDispatchWasMinimap)
            {
                log.info("v2-executor: UNSAFE_CANVAS_CLICK at {} — \"{}\"; blacklisting + picking different tile (rejects this leg: {}/{})",
                    lastDispatchedTile, lastErr, clickRejectsThisLeg + 1, MAX_CLICK_REJECTS_PER_LEG);
                classifier.blacklistTile(lastDispatchedTile);
                clickRejectsThisLeg++;
                lastDispatchedTile = null;
                ticksSinceProgress = 0;
                if (clickRejectsThisLeg >= MAX_CLICK_REJECTS_PER_LEG)
                {
                    log.warn("v2-executor: UNSAFE_CANVAS_CLICK_EXHAUSTED — FAILED after {} consecutive rejections",
                        clickRejectsThisLeg);
                    status = Status.FAILED;
                    lastFailureReason = FailureReason.UNSAFE_CANVAS_CLICK_EXHAUSTED;
                    return status;
                }
            }
        }

        // Progress tracking.
        if (lastPlayerLoc == null || !here.equals(lastPlayerLoc))
        {
            ticksSinceProgress = 0;
            lastPlayerLoc = here;
        }
        else
        {
            ticksSinceProgress++;
        }

        if (currentLeg instanceof V2Leg.Walk w)
        {
            return executeWalkLeg(here, w);
        }
        if (currentLeg instanceof V2Leg.Transport t)
        {
            return executeTransportLeg(here, t);
        }
        log.warn("v2-executor: NO_CURRENT_LEG — leg type unrecognised at idx={}", legIdx);
        status = Status.FAILED;
        lastFailureReason = FailureReason.NO_CURRENT_LEG;
        return status;
    }

    /** Execute one tick on a WALK leg. Round-2 invariants:
     *  <ul>
     *    <li>Per-leg progress cursor — picks must be strictly ahead of
     *        the high-water idx. No backward clicks.</li>
     *    <li>Openable-blocker recovery — if a static-collision mismatch
     *        is detected on the current click target, search the 1-ring
     *        for an Open verb and click it before falling through to
     *        stall recovery.</li>
     *  </ul> */
    private Status executeWalkLeg(WorldPoint here, V2Leg.Walk w)
    {
        // Recompute / advance the progress cursor. closestIndex is
        // monotonic-by-max — never regress past a high-water idx, even
        // if the player gets pushed back by an obstacle / NPC.
        int closest = closestIdxOnPath(w.tiles(), here);
        int oldProgress = progressIdx;
        if (closest > progressIdx) progressIdx = closest;
        if (progressIdx != oldProgress)
        {
            log.info("v2-executor: progress advanced old={} new={} leg={}",
                oldProgress, progressIdx, legIdx);
        }

        // Walk-leg advance: player has reached the end of this leg.
        if (here.getPlane() == w.end().getPlane()
            && chebyshev(here, w.end()) <= 1)
        {
            advanceLeg("WALK", w.end());
            return status;
        }

        // If we have a pending Open click, poll for live passability.
        if (pendingOpenTile != null)
        {
            ticksSincePendingOpen++;
            // Wall-edge case: liveCollisionAllows is BLOCK_MOVEMENT_FULL-only
            // and the wall-edge tile never had that flag set, so it returns
            // true immediately. Use dispatcher-idle as the resume signal —
            // the Open verb-click chain is in flight while busy=true; once
            // it clears, the gate has finished animating and a fresh walk
            // dispatch will succeed. Static-mismatch case keeps the original
            // BLOCK_MOVEMENT_FULL flip as the primary signal.
            boolean liveOK = env.liveCollisionAllows(pendingOpenTile);
            boolean dispatcherIdle = !env.dispatcherBusy();
            boolean canResume = pendingOpenIsWallEdge
                ? (liveOK && dispatcherIdle)
                : liveOK;
            if (canResume)
            {
                log.info("[v2-blocker] passability restored, continuing leg={} tile={} mode={}",
                    legIdx, pendingOpenTile, pendingOpenIsWallEdge ? "wall-edge" : "static-mismatch");
                pendingOpenTile = null;
                ticksSincePendingOpen = 0;
                pendingOpenIsWallEdge = false;
                lastDispatchedTile = null;
            }
            else if (ticksSincePendingOpen >= OPENABLE_TIMEOUT_TICKS)
            {
                log.warn("[v2-blocker] failed reason=OPENABLE_BLOCKER_TIMEOUT tile={} ticks={}",
                    pendingOpenTile, ticksSincePendingOpen);
                status = Status.FAILED;
                lastFailureReason = FailureReason.OPENABLE_BLOCKER_TIMEOUT;
                return status;
            }
            else
            {
                return status;   // still waiting for the gate to open
            }
        }

        // Click-in-flight handling.
        if (lastDispatchedTile != null && !here.equals(lastDispatchedTile))
        {
            if (ticksSinceProgress < STALL_TICKS) return status;
            // Stalled. Before falling into stall classifier, see if the
            // stall is gate-shaped (snapshot walkable, live blocked, +
            // openable nearby).
            if (tryHandleOpenableBlocker(here)) return status;
            if (handleStall(here)) return status;
        }
        else if (here.equals(lastDispatchedTile))
        {
            lastDispatchedTile = null;
            catchupClicksThisLeg = 0;
        }

        Modality modality = pickModality();
        if (modality == Modality.WORLDMAP)
        {
            throw new UnsupportedOperationException("WORLDMAP modality deferred to V2.5");
        }

        if (modality == Modality.CANVAS)
        {
            WorldPoint pick = canvasPicker.pickNextInTilesAfter(w.tiles(), progressIdx, here,
                this::canvasFilter, rng, toggles.variableDistance());
            if (pick != null)
            {
                int candidateIdx = w.tiles().indexOf(pick);
                if (candidateIdx <= progressIdx)
                {
                    log.warn("v2-executor: rejected backward candidate idx={} currentIdx={}",
                        candidateIdx, progressIdx);
                    pick = null;
                }
                else
                {
                    int remainingBefore = w.tiles().size() - 1 - progressIdx;
                    int remainingAfter = w.tiles().size() - 1 - candidateIdx;
                    log.debug("v2-executor: walk progress leg={} currentIdx={} candidateIdx={} remainingBefore={} remainingAfter={}",
                        legIdx, progressIdx, candidateIdx, remainingBefore, remainingAfter);
                }
            }
            if (!acceptCandidate(pick, here, "canvas"))
            {
                pick = null;
                if (status == Status.FAILED) return status;
            }
            recordReject(pick == null);
            if (pick != null)
            {
                if (env.dispatchWalk(pick))
                {
                    lastDispatchedTile = pick;
                    lastDispatchWasMinimap = false;
                    consecutiveNoCandidateTicks = 0;
                    log.debug("v2-executor: leg={} WALK canvas dispatch → {}", legIdx, pick);
                }
                return status;
            }
            if (!toggles.minimapModality())
            {
                log.warn("v2-executor: canvas exhausted and minimap modality disabled — FAILED so navigator can replan");
                status = Status.FAILED;
                lastFailureReason = FailureReason.UNSAFE_CANVAS_CLICK_EXHAUSTED;
                return status;
            }
            modality = Modality.MINIMAP;
        }

        // MINIMAP modality — current leg only, also progress-monotonic.
        WorldPoint mmTarget = pickMinimapTargetForLeg(here, w);
        if (!acceptCandidate(mmTarget, here, "minimap"))
        {
            mmTarget = null;
            if (status == Status.FAILED) return status;
        }
        if (mmTarget == null)
        {
            ticksSinceProgress++;
            consecutiveNoCandidateTicks++;
            if (consecutiveNoCandidateTicks >= MAX_NO_CANDIDATE_TICKS)
            {
                log.warn("v2-executor: NO_FORWARD_CANDIDATE / NO_CANDIDATE_AVAILABLE — both modalities returned null for {} consecutive ticks; FAILED",
                    consecutiveNoCandidateTicks);
                status = Status.FAILED;
                // Distinguish "no forward candidate" (progress cursor hit
                // the end) from generic "no candidate" (filter rejected
                // everything). The cursor hitting the end means the leg
                // is essentially done — leg advance handles that — so
                // the cursor-overshoot path lands here with all forward
                // tiles past the cursor BUT none passing the filter.
                lastFailureReason = (progressIdx >= w.tiles().size() - 1)
                    ? FailureReason.NO_FORWARD_CANDIDATE
                    : FailureReason.NO_CANDIDATE_AVAILABLE;
            }
            return status;
        }
        if (!env.canMinimapClick(mmTarget))
        {
            ticksSinceProgress++;
            consecutiveNoCandidateTicks++;
            if (consecutiveNoCandidateTicks >= MAX_NO_CANDIDATE_TICKS)
            {
                log.warn("v2-executor: NO_CANDIDATE_AVAILABLE — minimap precondition failed for {} consecutive ticks; FAILED",
                    consecutiveNoCandidateTicks);
                status = Status.FAILED;
                lastFailureReason = FailureReason.NO_CANDIDATE_AVAILABLE;
            }
            return status;
        }
        if (env.dispatchMinimap(mmTarget))
        {
            lastDispatchedTile = mmTarget;
            lastDispatchWasMinimap = true;
            consecutiveNoCandidateTicks = 0;
            log.debug("v2-executor: leg={} WALK minimap dispatch → {}", legIdx, mmTarget);
        }
        return status;
    }

    /** Round-2 gate handling. Two trigger paths:
     *
     *  <ol>
     *    <li><b>Static-collision mismatch</b> — the dispatched tile is
     *        walkable in the snapshot but {@code BLOCK_MOVEMENT_FULL} is
     *        live-set. Catches doors / objects that occupy a whole tile.
     *        Hard-fails if no Open verb is found in the dispatched tile's
     *        1-ring (the planner picked a tile we can't pass; only a
     *        replan recovers).</li>
     *    <li><b>Wall-edge fallback</b> — stalled with no static-mismatch
     *        but an "Open" verb-bearing object lives in the player's
     *        1-ring. Catches gates whose collision is directional
     *        ({@code BLOCK_MOVEMENT_NORTH/EAST/SOUTH/WEST}), which
     *        {@code liveCollisionAllows} can't see — the tile itself
     *        stays walkable, only the edge is blocked. Soft-falls through
     *        to normal stall handling if no openable nearby; bounded by
     *        {@link #MAX_OPENABLE_ATTEMPTS_PER_LEG} so a false-positive
     *        scan match (a closed gate adjacent to but not blocking the
     *        player) doesn't loop forever.</li>
     *  </ol>
     *
     *  Returns true if the call consumed the tick (Open dispatched, or
     *  hard-failed with status set). Returns false to let stall handling
     *  proceed.
     *
     *  Live-observed failure that motivated the wall-edge path: chicken
     *  pen entrance gate (id=1560) at (3236, 3295). Not in TransportIndex,
     *  so V2 plans a WALK leg straight through; live gate is closed; bot
     *  stalled and the static-mismatch trigger never fired because the
     *  pen-side tile remained {@code BLOCK_MOVEMENT_FULL}-walkable. */
    private boolean tryHandleOpenableBlocker(WorldPoint here)
    {
        WorldPoint blocked = lastDispatchedTile;
        if (blocked == null) return false;

        boolean staticMismatch =
            env.snapshotSaysWalkable(blocked) && !env.liveCollisionAllows(blocked);

        WorldPoint openTile;
        boolean wallEdge;
        if (staticMismatch)
        {
            openTile = env.findOpenableNear(blocked);
            wallEdge = false;
            if (openTile == null)
            {
                log.warn("[v2-blocker] failed reason=OPENABLE_BLOCKER_NOT_FOUND blockedEdge={} player={}",
                    blocked, here);
                status = Status.FAILED;
                lastFailureReason = FailureReason.OPENABLE_BLOCKER_NOT_FOUND;
                return true;
            }
        }
        else
        {
            // Wall-edge fallback: scan around the player. The planner had
            // the tile as walkable so liveCollisionAllows agrees — the
            // mismatch is on the EDGE, not the tile. We can't see
            // directional flags from here, so we use openable-presence as
            // the gate signal.
            openTile = env.findOpenableNear(here);
            wallEdge = true;
            if (openTile == null) return false;   // not gate-shaped; let stall handling run
        }

        if (openableAttemptsThisLeg >= MAX_OPENABLE_ATTEMPTS_PER_LEG)
        {
            log.warn("[v2-blocker] openable attempts exhausted leg={} attempts={} — falling through",
                legIdx, openableAttemptsThisLeg);
            return false;
        }

        log.info("[v2-blocker] {} from={} to={} found openable tile={}",
            wallEdge ? "wall-edge" : "static-mismatch", here, blocked, openTile);
        if (env.dispatchOpen(openTile))
        {
            log.info("[v2-blocker] clicked Open tile={}", openTile);
            pendingOpenTile = blocked;
            ticksSincePendingOpen = 0;
            pendingOpenIsWallEdge = wallEdge;
            lastDispatchedTile = null;   // don't classify this dispatch as a walk-stall
            ticksSinceProgress = 0;
            openableAttemptsThisLeg++;
            return true;
        }
        log.warn("[v2-blocker] failed reason=TRANSPORT_CLICK_FAILED tile={} (dispatcher refused)",
            openTile);
        status = Status.FAILED;
        lastFailureReason = FailureReason.TRANSPORT_CLICK_FAILED;
        return true;
    }

    private static int closestIdxOnPath(java.util.List<WorldPoint> tiles, WorldPoint here)
    {
        int best = 0;
        int bestDist = Integer.MAX_VALUE;
        for (int i = 0; i < tiles.size(); i++)
        {
            WorldPoint t = tiles.get(i);
            int d = chebyshev(t, here)
                  + (t.getPlane() == here.getPlane() ? 0 : 1000);
            if (d < bestDist) { bestDist = d; best = i; }
        }
        return best;
    }

    /** Execute one tick on a TRANSPORT leg. Round-2 invariants:
     *  <ul>
     *    <li>Direction validation — refuse to drive an edge whose
     *        from/to planes don't match the player's current plane and
     *        the next WALK leg's plane.</li>
     *    <li>Result mismatch correction — if plane changes to a value
     *        other than {@code edge.toTile().plane}, log
     *        TRANSPORT_RESULT_MISMATCH, correct the TransportIndex
     *        entry, and signal {@link #wantsReplanFromHere} so the
     *        navigator routes from the player's actual position.</li>
     *  </ul> */
    private Status executeTransportLeg(WorldPoint here, V2Leg.Transport t)
    {
        TransportEdge edge = t.edge();
        WorldPoint expectedTo = edge.toTile();
        WorldPoint approach = edge.approachTile() != null ? edge.approachTile() : edge.fromTile();

        // Direction validation — refuse the leg if planes don't line up.
        // Must be done BEFORE the success check so a wrong-direction edge
        // can't accidentally short-circuit on a same-plane player.
        Integer nextWalkPlane = nextWalkLegPlane();
        if (here.getPlane() != edge.fromTile().getPlane() && !transportClicked)
        {
            // We haven't clicked yet, and the player isn't on the from-plane.
            // This is direction-mismatch (or stale state from a prior leg).
            log.warn("v2-executor: transport failed reason=TRANSPORT_EDGE_DIRECTION_MISSING detail=verb={} objectId={} edge.from.plane={} edge.to.plane={} player.plane={} nextWalk.plane={}",
                edge.verb(), edge.objectId(),
                edge.fromTile().getPlane(), expectedTo.getPlane(), here.getPlane(),
                nextWalkPlane);
            status = Status.FAILED;
            lastFailureReason = FailureReason.TRANSPORT_EDGE_DIRECTION_MISSING;
            return status;
        }
        if (nextWalkPlane != null && nextWalkPlane != expectedTo.getPlane()
            && !transportClicked)
        {
            log.warn("v2-executor: transport failed reason=TRANSPORT_EDGE_DIRECTION_MISSING detail=verb={} objectId={} edge.to.plane={} nextWalk.plane={}",
                edge.verb(), edge.objectId(), expectedTo.getPlane(), nextWalkPlane);
            status = Status.FAILED;
            lastFailureReason = FailureReason.TRANSPORT_EDGE_DIRECTION_MISSING;
            return status;
        }

        // Result evaluation runs only once we've issued the click — the
        // plane change is the engine's success signal. If we haven't
        // clicked yet and the player happens to be on the to-plane,
        // the transport is already done (e.g. interrupted prior run);
        // advance.
        if (transportClicked)
        {
            ticksSinceTransportClick++;

            // Plane changed away from the from-plane?
            int fromPlane = edge.fromTile().getPlane();
            int toPlane = expectedTo.getPlane();
            if (here.getPlane() == toPlane)
            {
                log.info("v2-executor: transport success player={} (toPlane={} verb={} objectId={})",
                    here, toPlane, edge.verb(), edge.objectId());
                advanceTransportLeg();
                return status;
            }
            if (here.getPlane() != fromPlane)
            {
                // Plane changed but to a DIFFERENT plane than the planned
                // toTile. Recorded edge is wrong — correct it, replan.
                log.warn("[v2-transport] result mismatch plannedTo={} actual={}",
                    expectedTo, here);
                log.info("[v2-transport] correcting edge from={} verb={} oldTo={} newTo={}",
                    edge.fromTile(), edge.verb(), expectedTo, here);
                env.correctTransportEdge(edge.objectId(), edge.verb(),
                    edge.fromTile(), expectedTo, here, approach, edge.regionId());
                log.info("v2-executor: replanning from current position after transport correction (player={})",
                    here);
                wantsReplanFromHere = true;
                lastFailureReason = FailureReason.TRANSPORT_RESULT_MISMATCH;
                // Don't FAIL — leave status RUNNING; navigator will see
                // wantsReplanFromHere on its next tick and replan from
                // the player's current position. Until then, tick is a
                // no-op (early return at the top of the next tick).
                return status;
            }
            // Still on from-plane → waiting.
            log.debug("v2-executor: waiting for transport ({}/{} ticks since click, verb={} objectId={})",
                ticksSinceTransportClick, TRANSPORT_TIMEOUT_TICKS, edge.verb(), edge.objectId());
            if (ticksSinceTransportClick >= TRANSPORT_TIMEOUT_TICKS)
            {
                log.warn("v2-executor: transport failed reason=TRANSPORT_TIMEOUT detail=verb={} objectId={} from={} to={} player={} ticks={}",
                    edge.verb(), edge.objectId(), edge.fromTile(), expectedTo, here, ticksSinceTransportClick);
                status = Status.FAILED;
                lastFailureReason = FailureReason.TRANSPORT_TIMEOUT;
            }
            return status;
        }

        // Pre-click branch: player on from-plane, transport not clicked.
        // Need to walk to approach first?
        int distToApproach = chebyshev(here, approach);
        if (distToApproach > TRANSPORT_APPROACH_CHEBYSHEV)
        {
            log.info("v2-executor: leg={} TRANSPORT walking to approach={} dist={} (verb={} objectId={})",
                legIdx, approach, distToApproach, edge.verb(), edge.objectId());
            if (lastDispatchedTile == null
                || !approach.equals(lastDispatchedTile)
                || ticksSinceProgress >= STALL_TICKS)
            {
                if (env.dispatchWalk(approach))
                {
                    lastDispatchedTile = approach;
                    lastDispatchWasMinimap = false;
                    ticksSinceProgress = 0;
                    log.debug("v2-executor: leg={} TRANSPORT approach walk → {}", legIdx, approach);
                }
            }
            return status;
        }

        // Adjacent or at approach — resolve and click.
        WorldPoint clickTile = env.resolveTransportClickTile(
            edge.objectId(), edge.verb(), edge.fromTile());
        if (clickTile == null)
        {
            log.warn("v2-executor: transport failed reason=TRANSPORT_OBJECT_NOT_FOUND detail=verb={} objectId={} fromTile={} approach={} player={}",
                edge.verb(), edge.objectId(), edge.fromTile(), approach, here);
            status = Status.FAILED;
            lastFailureReason = FailureReason.TRANSPORT_OBJECT_NOT_FOUND;
            return status;
        }
        log.info("v2-executor: leg={} TRANSPORT object found tile={} verb={} objectId={}",
            legIdx, clickTile, edge.verb(), edge.objectId());
        if (!env.dispatchTransport(clickTile, edge.verb()))
        {
            log.warn("v2-executor: transport failed reason=TRANSPORT_CLICK_FAILED detail=verb={} objectId={} clickTile={} player={} (dispatcher refused)",
                edge.verb(), edge.objectId(), clickTile, here);
            status = Status.FAILED;
            lastFailureReason = FailureReason.TRANSPORT_CLICK_FAILED;
            return status;
        }
        log.info("v2-executor: leg={} TRANSPORT clicked tile={} verb={} objectId={}",
            legIdx, clickTile, edge.verb(), edge.objectId());
        transportClicked = true;
        ticksSinceTransportClick = 0;
        lastDispatchedTile = null;
        return status;
    }

    /** Returns the plane of the next WALK leg after the current TRANSPORT
     *  leg, or null if the transport is the last leg. Used for direction
     *  validation. */
    @Nullable
    private Integer nextWalkLegPlane()
    {
        if (path == null) return null;
        for (int i = legIdx + 1; i < path.legs().size(); i++)
        {
            V2Leg leg = path.legs().get(i);
            if (leg instanceof V2Leg.Walk w) return w.start().getPlane();
        }
        return null;
    }

    private void advanceLeg(String tag, WorldPoint endTile)
    {
        log.info("v2-executor: advance leg {} -> {} ({} leg complete at {})",
            legIdx, legIdx + 1, tag, endTile);
        legIdx++;
        catchupClicksThisLeg = 0;
        clickRejectsThisLeg = 0;
        ticksSinceProgress = 0;
        lastDispatchedTile = null;
        progressIdx = -1;   // reset cursor for the next walk leg
        openableAttemptsThisLeg = 0;
    }

    private void advanceTransportLeg()
    {
        log.info("v2-executor: advance leg {} -> {} (TRANSPORT complete)",
            legIdx, legIdx + 1);
        legIdx++;
        catchupClicksThisLeg = 0;
        clickRejectsThisLeg = 0;
        ticksSinceProgress = 0;
        lastDispatchedTile = null;
        transportClicked = false;
        ticksSinceTransportClick = 0;
        progressIdx = -1;
        openableAttemptsThisLeg = 0;
    }

    private static int chebyshev(WorldPoint a, WorldPoint b)
    {
        return Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getY() - b.getY()));
    }

    /** Hard plane guard. Returns false (and applies recovery) if the
     *  candidate is on a plane the player can't reach by walking. */
    private boolean acceptCandidate(@Nullable WorldPoint candidate,
                                    WorldPoint playerHere, String modalityTag)
    {
        if (candidate == null) return true;
        if (candidate.getPlane() == playerHere.getPlane()) return true;
        crossPlaneRejectsThisRoute++;
        log.warn("v2-executor: rejected cross-plane {} candidate {} (player on plane {}); rejects this route: {}/{}",
            modalityTag, candidate, playerHere.getPlane(),
            crossPlaneRejectsThisRoute, MAX_CROSS_PLANE_REJECTS_PER_ROUTE);
        classifier.blacklistTile(candidate);
        if (crossPlaneRejectsThisRoute >= MAX_CROSS_PLANE_REJECTS_PER_ROUTE)
        {
            log.warn("v2-executor: cross-plane candidate budget exhausted ({} ≥ {}) — FAILED",
                crossPlaneRejectsThisRoute, MAX_CROSS_PLANE_REJECTS_PER_ROUTE);
            status = Status.FAILED;
            lastFailureReason = FailureReason.CROSS_PLANE_CANDIDATES_EXHAUSTED;
        }
        return false;
    }

    private boolean canvasFilter(WorldPoint tile)
    {
        if (classifier.isBlacklisted(tile)) return false;
        if (classifier.hasTransientPenalty(tile)) return false;
        return env.isPlausiblyClean(tile);
    }

    private boolean handleStall(WorldPoint here)
    {
        if (lastDispatchedTile == null) return false;

        InvalidationClassifier.FailureContext ctx = buildFailureContext(here);
        InvalidationClassifier.FailureClass fc = classifier.classify(ctx);
        log.info("v2-executor: stall classified {} for tile {} (player at {})",
            fc, lastDispatchedTile, here);

        if (fc == InvalidationClassifier.FailureClass.STATIC_COLLISION_MISMATCH
            || fc == InvalidationClassifier.FailureClass.TRANSPORT_STATE_MISMATCH)
        {
            log.warn("v2-executor: {} requires a fresh plan — FAILED", fc);
            status = Status.FAILED;
            lastFailureReason = FailureReason.STALL_CLASSIFIER_REPLAN;
            return true;
        }

        if (!toggles.catchupClicks())
        {
            log.warn("v2-executor: stall + catch-up disabled — FAILED so navigator replans");
            status = Status.FAILED;
            lastFailureReason = FailureReason.CATCHUP_EXHAUSTED;
            return true;
        }
        if (catchupClicksThisLeg < MAX_CATCHUP_CLICKS_PER_LEG)
        {
            log.info("v2-executor: stall after {} ticks at {} — catch-up re-click on {} ({})",
                ticksSinceProgress, here, lastDispatchedTile, fc);
            boolean ok = lastDispatchWasMinimap
                ? env.dispatchMinimap(lastDispatchedTile)
                : env.dispatchWalk(lastDispatchedTile);
            if (ok)
            {
                catchupClicksThisLeg++;
                ticksSinceProgress = 0;
                return true;
            }
        }
        // Catch-up budget exhausted on this leg. Instead of failing the
        // whole script, blacklist the tile and request a replan from
        // current position. The classifier already added the tile to
        // its blacklist (per-route), so the next plan won't pick it.
        // The navigator's replan budget (MAX_REPLANS_PER_REQUEST=3)
        // bounds how many times this can recur before propagating
        // FAILED. Live observation that motivated this: bot reached
        // 80%+ of bank → pen route, stalled on a single water/sentinel
        // tile near the destination, gave up despite the rest of the
        // route being walkable.
        log.warn("v2-executor: stall recovery exhausted after {} catch-up click(s) on {} — requesting replan",
            catchupClicksThisLeg, lastDispatchedTile);
        if (lastDispatchedTile != null) classifier.blacklistTile(lastDispatchedTile);
        wantsReplanFromHere = true;
        lastFailureReason = FailureReason.CATCHUP_EXHAUSTED;
        // Don't FAIL — leave status RUNNING so the navigator picks up
        // wantsReplanFromHere and replans within its budget.
        return true;
    }

    private InvalidationClassifier.FailureContext buildFailureContext(WorldPoint here)
    {
        WorldPoint tile = lastDispatchedTile;
        TransportLegInfo tli = transportLegFor(tile);
        return new InvalidationClassifier.FailureContext(
            tile, here, here,
            env.snapshotSaysWalkable(tile),
            env.liveCollisionAllows(tile),
            env.dynamicEntityOnTile(tile),
            tli != null,
            tli == null ? null : tli.edge,
            tli == null || tli.verbStillPresent);
    }

    private static final class TransportLegInfo
    {
        final TransportEdge edge;
        final boolean verbStillPresent;
        TransportLegInfo(TransportEdge e, boolean verbPresent)
        { this.edge = e; this.verbStillPresent = verbPresent; }
    }

    @Nullable
    private TransportLegInfo transportLegFor(WorldPoint tile)
    {
        if (path == null || tile == null) return null;
        for (V2Leg leg : path.legs())
        {
            if (leg instanceof V2Leg.Transport t)
            {
                if (tile.equals(t.edge().approachTile()) || tile.equals(t.edge().fromTile()))
                {
                    return new TransportLegInfo(t.edge(), true);
                }
            }
        }
        return null;
    }

    private Modality pickModality()
    {
        if (!toggles.minimapModality()) return Modality.CANVAS;
        if (recentRejectRate() >= MINIMAP_BIAS_THRESHOLD)
        {
            return Modality.MINIMAP;
        }
        return Modality.CANVAS;
    }

    private double recentRejectRate()
    {
        int hits = 0;
        for (boolean r : recentRejects) if (r) hits++;
        return hits / (double) recentRejects.length;
    }

    private void recordReject(boolean rejected)
    {
        recentRejects[rejectCursor] = rejected;
        rejectCursor = (rejectCursor + 1) % recentRejects.length;
    }

    @Nullable
    private WorldPoint pickMinimapTargetForLeg(WorldPoint here, V2Leg.Walk w)
    {
        for (int i = w.tiles().size() - 1; i >= 0; i--)
        {
            WorldPoint t = w.tiles().get(i);
            int d = chebyshev(t, here);
            if (d >= CanvasTilePicker.MID_MIN
                && !classifier.isBlacklisted(t)
                && !classifier.hasTransientPenalty(t))
            {
                return t;
            }
        }
        return null;
    }

    @Nullable
    private WorldPoint pathEnd()
    {
        if (path == null || path.isEmpty()) return null;
        for (int i = path.legs().size() - 1; i >= 0; i--)
        {
            V2Leg leg = path.legs().get(i);
            if (leg instanceof V2Leg.Walk w) return w.end();
            if (leg instanceof V2Leg.Transport t) return t.edge().toTile();
        }
        return null;
    }
}
