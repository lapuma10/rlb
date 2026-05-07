package net.runelite.client.plugins.recorder.nav.v2;

import java.util.Random;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;

/** Per-tick state machine that turns a {@link V2Path} into walk-clicks
 *  and minimap-clicks. Lives behind {@link V2Navigator}; the Navigator
 *  owns the planner and feeds new paths in via {@link #setPath}.
 *
 *  <p>The executor is structured as a thin orchestrator over four
 *  collaborators:
 *  <ul>
 *    <li>{@link CanvasTilePicker} — chooses the next canvas-click tile</li>
 *    <li>{@link MinimapClicker} — minimap-modality clicks with preconditions</li>
 *    <li>{@link InvalidationClassifier} — typed failure handling, blacklist</li>
 *    <li>{@link Env} — live reads (player location, empty-tile filter,
 *        dispatcher) marshalled to whatever thread the underlying API
 *        needs. Tests inject a deterministic fake.</li>
 *  </ul>
 *
 *  <p>Modality selection (round 1): canvas by default; switches to
 *  minimap when the recent canvas filter rejection rate is high (the
 *  CanvasTilePicker keeps returning null because too many path tiles
 *  are entity-contaminated). The {@code WORLDMAP} slot is reserved in
 *  {@link Modality} but throws on use — deferred to V2.5 per spec
 *  lines 281-289.
 *
 *  <p>Stall detection: if the player hasn't progressed for
 *  {@link #STALL_TICKS} ticks, the executor delegates to
 *  {@link InvalidationClassifier} and either issues a bounded catch-up
 *  click or returns FAILED so the Navigator can replan. */
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

    /** Live-state seam. Production binds these to {@link
     *  net.runelite.api.Client}, {@link net.runelite.client.callback.ClientThread},
     *  {@link EmptyTileFilter}, {@link MinimapClicker}, and {@link
     *  net.runelite.client.sequence.dispatch.HumanizedInputDispatcher}.
     *  Tests inject deterministic fakes so the state machine can be
     *  exercised without a live client.
     *
     *  <p>Method contracts:
     *  <ul>
     *    <li>{@link #playerLoc()} — current player tile or null if
     *        the local player is missing (logged out)</li>
     *    <li>{@link #isPlausiblyClean(WorldPoint)} — wraps
     *        {@code EmptyTileFilter::isPlausiblyClean}; runs on the
     *        client thread</li>
     *    <li>{@link #canMinimapClick(WorldPoint)} — wraps
     *        {@code MinimapClicker::canClick}; runs on the client thread</li>
     *    <li>{@link #dispatchWalk(WorldPoint)} — enqueue an
     *        {@code ActionRequest.Kind.WALK} via the dispatcher; returns
     *        true if accepted (false if dispatcher busy)</li>
     *    <li>{@link #dispatchMinimap(WorldPoint)} — wraps
     *        {@code MinimapClicker::dispatch}; returns true if enqueued</li>
     *    <li>{@link #dispatcherBusy()} — wraps the dispatcher's busy flag</li>
     *    <li>{@link #nowMs()} — clock used for stall detection. Tests
     *        substitute a fixed clock</li>
     *  </ul> */
    public interface Env
    {
        @Nullable WorldPoint playerLoc();
        boolean isPlausiblyClean(WorldPoint tile);
        boolean canMinimapClick(WorldPoint tile);
        boolean dispatchWalk(WorldPoint tile);
        boolean dispatchMinimap(WorldPoint tile);
        boolean dispatcherBusy();
        long nowMs();

        /** Snapshot view: does {@link
         *  net.runelite.client.plugins.recorder.worldmap.MapStore} think
         *  the tile is walkable? Used by {@link InvalidationClassifier}
         *  to detect static-collision mismatches. */
        default boolean snapshotSaysWalkable(WorldPoint tile) { return true; }

        /** Live view: do the engine's collision flags currently allow
         *  walking onto the tile? Compared to {@link
         *  #snapshotSaysWalkable} to classify static-vs-dynamic
         *  failures. */
        default boolean liveCollisionAllows(WorldPoint tile) { return true; }

        /** Live view: NPC / player physically standing on the tile.
         *  Distinct from {@link #isPlausiblyClean} which also rejects
         *  decoration/door/ground-item — this is just the dynamic-
         *  blocker channel. */
        default boolean dynamicEntityOnTile(WorldPoint tile) { return false; }
    }

    /** Ticks of zero progress before treating the leg as stalled and
     *  consulting {@link InvalidationClassifier}. Generous so a
     *  single dropped click doesn't trigger a recovery flap — real
     *  walks can take 4–6 ticks to land a click effect on the engine. */
    public static final int STALL_TICKS = 4;

    /** Maximum catch-up clicks per leg before bailing to FAILED.
     *  Per spec lines 293-298: catch-up is a bounded recovery, not
     *  a per-cycle decoration. */
    public static final int MAX_CATCHUP_CLICKS_PER_LEG = 2;

    /** Recent-filter window size for the modality bias decision. */
    private static final int FILTER_REJECT_WINDOW = 4;
    /** Switch to minimap modality when rejection rate is at or above
     *  this fraction over {@link #FILTER_REJECT_WINDOW}. */
    private static final double MINIMAP_BIAS_THRESHOLD = 0.5;

    private final Env env;
    private final CanvasTilePicker canvasPicker;
    private final InvalidationClassifier classifier;
    private final Random rng;

    @Nullable private V2Path path;
    private int legIdx;
    private int catchupClicksThisLeg;
    private int ticksSinceProgress;
    @Nullable private WorldPoint lastPlayerLoc;
    @Nullable private WorldPoint lastDispatchedTile;
    private boolean lastDispatchWasMinimap;
    private final boolean[] recentRejects = new boolean[FILTER_REJECT_WINDOW];
    private int rejectCursor;
    private RunMode runMode = RunMode.UNCHANGED;
    private Status status = Status.IDLE;

    public V2Executor(Env env, CanvasTilePicker canvasPicker,
                      InvalidationClassifier classifier, Random rng)
    {
        this.env = env;
        this.canvasPicker = canvasPicker;
        this.classifier = classifier;
        this.rng = rng;
    }

    /** Start executing a new path. Clears all per-leg state and
     *  resets the classifier's attempt-local blacklist. */
    public void setPath(V2Path newPath)
    {
        this.path = newPath;
        this.legIdx = 0;
        this.catchupClicksThisLeg = 0;
        this.ticksSinceProgress = 0;
        this.lastPlayerLoc = null;
        this.lastDispatchedTile = null;
        this.lastDispatchWasMinimap = false;
        this.rejectCursor = 0;
        for (int i = 0; i < recentRejects.length; i++) recentRejects[i] = false;
        this.classifier.resetForNewRoute();
        this.status = (newPath == null || newPath.isEmpty()) ? Status.IDLE : Status.RUNNING;
    }

    public void cancel()
    {
        this.path = null;
        this.status = Status.IDLE;
    }

    /** Round-1 stub. Logs a warning and falls through to UNCHANGED for
     *  any value other than UNCHANGED. The interface exists so future
     *  rounds can wire run-toggle behavior without API churn. */
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

    /** Drive one tick of execution. Returns the post-tick status; the
     *  Navigator collapses RUNNING / ARRIVED / FAILED into NavStatus. */
    public Status tick()
    {
        if (path == null || path.isEmpty()) { status = Status.IDLE; return status; }
        if (status != Status.RUNNING) return status;

        WorldPoint here = env.playerLoc();
        if (here == null) return status;   // logged out / mid-load — try again next tick

        // Arrived?
        WorldPoint dest = pathEnd();
        if (dest != null && here.equals(dest))
        {
            status = Status.ARRIVED;
            log.info("v2-executor: arrived at {}", dest);
            return status;
        }

        // If a click is still in flight (dispatcher mid-chain), wait
        // it out. Don't tick the stall counter while the dispatcher is
        // busy — the player legitimately hasn't started moving yet.
        if (env.dispatcherBusy()) return status;

        // Update progress tracking AFTER the busy gate so each tick of
        // mid-walk silence doesn't get counted as a stall.
        if (lastPlayerLoc == null || !here.equals(lastPlayerLoc))
        {
            ticksSinceProgress = 0;
            lastPlayerLoc = here;
        }
        else
        {
            ticksSinceProgress++;
        }

        // If a click is in flight (we issued one, player hasn't reached
        // the target yet), don't issue a new one unless we've stalled.
        if (lastDispatchedTile != null && !here.equals(lastDispatchedTile))
        {
            if (ticksSinceProgress < STALL_TICKS) return status;
            // Stalled — handleStall takes over.
            if (handleStall(here)) return status;
        }
        else if (here.equals(lastDispatchedTile))
        {
            // Reached the previous click target. Clear it so the picker
            // chooses a new one this tick. Reset per-leg counters that
            // are scoped to one click chain.
            lastDispatchedTile = null;
            catchupClicksThisLeg = 0;
        }

        // Pick modality + click target.
        Modality modality = pickModality();
        if (modality == Modality.WORLDMAP)
        {
            throw new UnsupportedOperationException("WORLDMAP modality deferred to V2.5");
        }

        if (modality == Modality.CANVAS)
        {
            WorldPoint pick = canvasPicker.pickNext(path, here, this::canvasFilter, rng);
            recordReject(pick == null);
            if (pick != null)
            {
                if (env.dispatchWalk(pick))
                {
                    lastDispatchedTile = pick;
                    lastDispatchWasMinimap = false;
                    log.debug("v2-executor: canvas dispatch → {}", pick);
                }
                return status;
            }
            // Canvas exhausted: fall through to minimap this tick.
            modality = Modality.MINIMAP;
        }

        // MINIMAP modality.
        WorldPoint mmTarget = pickMinimapTarget(here);
        if (mmTarget == null)
        {
            // Both modalities failed for this tick. Counter the stall
            // budget so we don't loop forever with no candidate.
            ticksSinceProgress++;
            return status;
        }
        if (!env.canMinimapClick(mmTarget))
        {
            ticksSinceProgress++;
            return status;
        }
        if (env.dispatchMinimap(mmTarget))
        {
            lastDispatchedTile = mmTarget;
            lastDispatchWasMinimap = true;
            log.debug("v2-executor: minimap dispatch → {}", mmTarget);
        }
        return status;
    }

    private boolean canvasFilter(WorldPoint tile)
    {
        if (classifier.isBlacklisted(tile)) return false;
        if (classifier.hasTransientPenalty(tile)) return false;
        return env.isPlausiblyClean(tile);
    }

    /** Returns true if the stall was handled (catch-up click dispatched
     *  or status transitioned to FAILED). False = continue the normal
     *  pick path. */
    private boolean handleStall(WorldPoint here)
    {
        if (lastDispatchedTile == null)
        {
            // Stall before any click — fall through to normal pick.
            return false;
        }

        // Classify the failure so the recovery channel matches the
        // root cause. STATIC_COLLISION_MISMATCH auto-blacklists the
        // tile in the classifier; TRANSPORT_STATE_MISMATCH auto-marks
        // the edge stale. Either way, retrying the same tile is a
        // waste — replan instead.
        InvalidationClassifier.FailureContext ctx = buildFailureContext(here);
        InvalidationClassifier.FailureClass fc = classifier.classify(ctx);
        log.info("v2-executor: stall classified {} for tile {} (player at {})",
            fc, lastDispatchedTile, here);

        if (fc == InvalidationClassifier.FailureClass.STATIC_COLLISION_MISMATCH
            || fc == InvalidationClassifier.FailureClass.TRANSPORT_STATE_MISMATCH)
        {
            log.warn("v2-executor: {} requires a fresh plan — FAILED so navigator replans", fc);
            status = Status.FAILED;
            return true;
        }

        // DYNAMIC_BLOCKER / UNKNOWN: bounded catch-up. The classifier
        // already added a transient penalty for DYNAMIC_BLOCKER, so the
        // canvas filter will skip the tile on the next pick if needed.
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
                ticksSinceProgress = 0;   // give the catch-up a window
                return true;
            }
        }
        log.warn("v2-executor: stall recovery exhausted after {} catch-up click(s) — FAILED",
            catchupClicksThisLeg);
        status = Status.FAILED;
        return true;
    }

    private InvalidationClassifier.FailureContext buildFailureContext(WorldPoint here)
    {
        WorldPoint tile = lastDispatchedTile;
        // Detect transport-leg context. If the last dispatch was on a
        // transport leg's approach tile, surface the edge so the
        // classifier can flag it stale on verb-mismatch.
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
        final net.runelite.client.plugins.recorder.worldmap.TransportEdge edge;
        final boolean verbStillPresent;

        TransportLegInfo(net.runelite.client.plugins.recorder.worldmap.TransportEdge e,
                         boolean verbPresent)
        {
            this.edge = e;
            this.verbStillPresent = verbPresent;
        }
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
                    // Round-1: assume verb still present unless the
                    // executor has another channel to tell. Phase 6.5
                    // can wire a live-menu probe here when the engine
                    // can resolve the object's actions on the click
                    // tile. Conservative default: verb present →
                    // classifier won't tag it TRANSPORT_STATE_MISMATCH
                    // unless extended.
                    return new TransportLegInfo(t.edge(), true);
                }
            }
        }
        return null;
    }

    private Modality pickModality()
    {
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
    private WorldPoint pickMinimapTarget(WorldPoint here)
    {
        // Prefer a tile far enough down the path that the dispatcher's
        // minimap projection can land on it. Walk legs only; transports
        // are click-by-verb in a future round.
        for (V2Leg leg : path.legs())
        {
            if (!(leg instanceof V2Leg.Walk w)) continue;
            for (int i = w.tiles().size() - 1; i >= 0; i--)
            {
                WorldPoint t = w.tiles().get(i);
                int d = Math.max(Math.abs(t.getX() - here.getX()),
                                 Math.abs(t.getY() - here.getY()));
                if (d >= CanvasTilePicker.MID_MIN
                    && !classifier.isBlacklisted(t)
                    && !classifier.hasTransientPenalty(t))
                {
                    return t;
                }
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
