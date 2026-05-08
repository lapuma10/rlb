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
        if (newPath == null || newPath.isEmpty())
        {
            this.status = Status.IDLE;
            return;
        }
        log.info("v2-executor: route accepted legs={} routeId={}",
            newPath.legs().size(), newPath.routeId());
        for (int i = 0; i < newPath.legs().size(); i++)
        {
            V2Leg leg = newPath.legs().get(i);
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
        this.status = Status.RUNNING;
    }

    public void cancel()
    {
        this.path = null;
        this.status = Status.IDLE;
        this.lastFailureReason = null;
        this.transportClicked = false;
        this.ticksSinceTransportClick = 0;
    }

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

    /** Execute one tick on a WALK leg. Candidate tiles are SCOPED to
     *  this leg only — never flattened across legs/planes. Advances to
     *  the next leg when the player is at (or within Chebyshev 1 of)
     *  the leg's end tile. */
    private Status executeWalkLeg(WorldPoint here, V2Leg.Walk w)
    {
        // Walk-leg advance: player has reached the end of this leg.
        // Use Chebyshev<=1 so an overshoot click that landed one tile
        // past the end (or a planner that ends a leg one short of a
        // transport approach) still advances cleanly.
        if (here.getPlane() == w.end().getPlane()
            && chebyshev(here, w.end()) <= 1)
        {
            advanceLeg("WALK", w.end());
            return status;
        }

        // Click-in-flight handling.
        if (lastDispatchedTile != null && !here.equals(lastDispatchedTile))
        {
            if (ticksSinceProgress < STALL_TICKS) return status;
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
            WorldPoint pick = canvasPicker.pickNextInTiles(w.tiles(), here,
                this::canvasFilter, rng, toggles.variableDistance());
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

        // MINIMAP modality — current leg only.
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
                log.warn("v2-executor: NO_CANDIDATE_AVAILABLE — both modalities returned null for {} consecutive ticks; FAILED",
                    consecutiveNoCandidateTicks);
                status = Status.FAILED;
                lastFailureReason = FailureReason.NO_CANDIDATE_AVAILABLE;
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

    /** Execute one tick on a TRANSPORT leg. Walks to the approach tile
     *  if needed, dispatches the verb-click on the resolved live object,
     *  then polls until the player is on the destination plane. */
    private Status executeTransportLeg(WorldPoint here, V2Leg.Transport t)
    {
        TransportEdge edge = t.edge();
        WorldPoint expectedTo = edge.toTile();
        WorldPoint approach = edge.approachTile() != null ? edge.approachTile() : edge.fromTile();

        // Success: player reached the destination plane.
        if (here.getPlane() == expectedTo.getPlane())
        {
            log.info("v2-executor: transport success player={} (toPlane={} verb={} objectId={})",
                here, expectedTo.getPlane(), edge.verb(), edge.objectId());
            advanceTransportLeg();
            return status;
        }

        // Player must be on the from-plane — neither from nor to is the
        // out-of-band TRANSPORT_PLANE_MISMATCH case.
        if (here.getPlane() != edge.fromTile().getPlane())
        {
            log.warn("v2-executor: transport failed reason=TRANSPORT_PLANE_MISMATCH detail=verb={} objectId={} from.plane={} to.plane={} player.plane={}",
                edge.verb(), edge.objectId(),
                edge.fromTile().getPlane(), expectedTo.getPlane(), here.getPlane());
            status = Status.FAILED;
            lastFailureReason = FailureReason.TRANSPORT_PLANE_MISMATCH;
            return status;
        }

        // Click already dispatched — poll plane change with a bounded timeout.
        if (transportClicked)
        {
            ticksSinceTransportClick++;
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

        // Need to walk to approach first?
        int distToApproach = chebyshev(here, approach);
        if (distToApproach > TRANSPORT_APPROACH_CHEBYSHEV)
        {
            log.info("v2-executor: leg={} TRANSPORT walking to approach={} dist={} (verb={} objectId={})",
                legIdx, approach, distToApproach, edge.verb(), edge.objectId());
            // Don't re-dispatch the same approach tile every tick — let the
            // walk land. If we stall, the lastDispatchedTile-clear path on
            // arrival or a stall-driven re-click will try again.
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
        // Don't track this dispatch with lastDispatchedTile — the player
        // won't physically arrive on the object's tile; the success signal
        // is the plane change which we poll on next ticks.
        lastDispatchedTile = null;
        return status;
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
        log.warn("v2-executor: stall recovery exhausted after {} catch-up click(s) — FAILED",
            catchupClicksThisLeg);
        status = Status.FAILED;
        lastFailureReason = FailureReason.CATCHUP_EXHAUSTED;
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
