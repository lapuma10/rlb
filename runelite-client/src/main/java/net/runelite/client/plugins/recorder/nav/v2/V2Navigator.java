package net.runelite.client.plugins.recorder.nav.v2;

import java.util.Optional;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.nav.BehaviorMode;
import net.runelite.client.plugins.recorder.nav.EntityKind;
import net.runelite.client.plugins.recorder.nav.NavRequest;
import net.runelite.client.plugins.recorder.nav.NavStatus;
import net.runelite.client.plugins.recorder.nav.Navigator;
import net.runelite.client.plugins.recorder.worldmap.EntityIndex;
import net.runelite.client.plugins.recorder.worldmap.EntitySighting;

/** V2 implementation of {@link Navigator}. Plans the route once per
 *  destination via {@link V2Planner}, then drives clicks tick by tick
 *  via {@link V2Executor}. The single switch site in {@code
 *  NavigatorFactory} flips between this and {@link
 *  net.runelite.client.plugins.recorder.nav.TrailNavigator}.
 *
 *  <p>Replanning rules: a fresh plan only happens when the destination
 *  WorldPoint changes between ticks. Re-issuing the same request every
 *  tick (the script's normal pattern) keeps using the cached plan.
 *  Cancelling drops the cached plan; the next tick replans.
 *
 *  <p>Status mapping:
 *  <ul>
 *    <li>executor IDLE → {@link NavStatus#IDLE} (after cancel/arrived)</li>
 *    <li>executor RUNNING → {@link NavStatus#RUNNING}</li>
 *    <li>executor ARRIVED → {@link NavStatus#ARRIVED}</li>
 *    <li>executor FAILED → {@link NavStatus#FAILED}</li>
 *  </ul> */
@Slf4j
public final class V2Navigator implements Navigator
{
    /** Test seam for the planner. Production binds to a real
     *  {@link V2Planner}. {@link #diagnose} surfaces a one-line
     *  reason when {@link #plan} returns empty so the no-route log
     *  line carries actionable info (region not loaded / tile
     *  missing / no cross-plane transport). */
    public interface PlannerHook
    {
        V2Path plan(WorldPoint from, WorldPoint to, BehaviorMode mode);
        /** Trail-bias overload: when {@code trailName} resolves to a
         *  recorded V1 trail, the planner steers A* toward the trail's
         *  recorded tiles. Default delegates to the unbiased
         *  {@link #plan(WorldPoint, WorldPoint, BehaviorMode)} so test
         *  hooks that don't care about trails keep working. */
        default V2Path plan(WorldPoint from, WorldPoint to, BehaviorMode mode,
                            @Nullable String trailName)
        {
            return plan(from, to, mode);
        }
        default String diagnose(WorldPoint from, WorldPoint to) { return "(no diagnostic available)"; }
    }

    /** Test seam for the executor. Production wraps a {@link V2Executor}. */
    public interface ExecutorHook
    {
        void setPath(V2Path path);
        V2Executor.Status tick();
        void cancel();
        V2Executor.Status status();
        /** Underlying executor failure reason (if any). The navigator
         *  surfaces this via {@link V2Navigator#lastExecutorFailureReason}
         *  so log lines and panel reports can attribute an
         *  {@code EXECUTOR_FAILED} navigator status to the precise
         *  executor cause (TRANSPORT_EXECUTOR_MISSING / CATCHUP_EXHAUSTED
         *  / PLAYER_LOC_LOST / …). */
        @Nullable default V2Executor.FailureReason lastFailureReason() { return null; }
        /** Round-2: returns true when the executor wants the navigator
         *  to replan from the player's current position (e.g. after a
         *  TRANSPORT_RESULT_MISMATCH self-correction). */
        default boolean wantsReplanFromHere() { return false; }
    }

    /** Supplier for the player's current world location. Production
     *  marshals to the client thread. Tests inject a fixed point. */
    public interface PlayerLocSupplier extends Supplier<WorldPoint>
    {
    }

    /** Tagged reason attached to a {@link NavStatus#FAILED} return.
     *  Set by the most recent {@link #tick} that failed, cleared on the
     *  next successful resolution. Surfaces to the panel + log so the
     *  morning live-test workflow knows whether a missing-entity
     *  failure differs from a missing-route failure. */
    public enum FailureReason
    {
        /** Phase-16: request named an entity but {@link EntityIndex} had
         *  no matching sighting (or no EntityIndex was wired). */
        ENTITY_NOT_FOUND,
        /** Planner returned an empty path — see the no-route log line
         *  for the planner's diagnostic. */
        NO_ROUTE,
        /** Executor reported FAILED — see {@link V2Executor#lastFailureReason}
         *  for the specific reason. */
        EXECUTOR_FAILED,
        /** Player location unknown (logged out / scene unloaded). */
        NO_PLAYER_LOC,
        /** Request shape unsupported (no point, no entity). */
        BAD_REQUEST
    }

    private static final String NAME = "worldmap-v2";

    private final PlannerHook planner;
    private final ExecutorHook executor;
    private final PlayerLocSupplier playerLoc;
    /** Phase-16 entity resolution. Optional — when null, entity-targeted
     *  requests fail with ENTITY_NOT_FOUND. */
    @Nullable private final EntityIndex entityIndex;

    @Nullable private WorldPoint activeTarget;
    @Nullable private V2Path activePath;
    @Nullable private FailureReason lastFailureReason;
    /** Round-2 replan budget: number of executor-driven replans (e.g.
     *  TRANSPORT_RESULT_MISMATCH) consumed for the active request.
     *  Bounded by {@link #MAX_REPLANS_PER_REQUEST} so a transport whose
     *  toTile is wrong doesn't loop forever. Resets when target changes. */
    private int replansThisRequest;
    /** Executor-driven replans per request. Bumped 1 → 3 after live
     *  bank↔pen testing: a single misrecorded {@code toTile} (e.g.
     *  "Climb-down p2→p0" actually goes p2→p1) commonly cascades into
     *  a second misrecording on the next floor (p1→p0 via "Climb-up"
     *  that actually goes p1→p2), and the bot needs at least two
     *  replans to self-heal both edges before a third replan picks the
     *  correct route. 3 leaves headroom for one transport graph that's
     *  fully corrupted; if more replans are needed, the recorded data
     *  is too stale and the script should fail loudly. */
    public static final int MAX_REPLANS_PER_REQUEST = 3;

    public V2Navigator(V2Planner planner, V2Executor executor, PlayerLocSupplier playerLoc)
    {
        this(plannerHookFor(planner), hookFor(executor), playerLoc, null);
    }

    public V2Navigator(V2Planner planner, V2Executor executor, PlayerLocSupplier playerLoc,
                       @Nullable EntityIndex entityIndex)
    {
        this(plannerHookFor(planner), hookFor(executor), playerLoc, entityIndex);
    }

    private static PlannerHook plannerHookFor(V2Planner planner)
    {
        return new PlannerHook()
        {
            @Override public V2Path plan(WorldPoint from, WorldPoint to, BehaviorMode mode)
            { return planner.plan(from, to, mode); }
            @Override public V2Path plan(WorldPoint from, WorldPoint to, BehaviorMode mode,
                                         @Nullable String trailName)
            { return planner.plan(from, to, mode, trailName); }
            @Override public String diagnose(WorldPoint from, WorldPoint to)
            { return planner.diagnose(from, to); }
        };
    }

    V2Navigator(PlannerHook planner, ExecutorHook executor, PlayerLocSupplier playerLoc)
    {
        this(planner, executor, playerLoc, null);
    }

    V2Navigator(PlannerHook planner, ExecutorHook executor, PlayerLocSupplier playerLoc,
                @Nullable EntityIndex entityIndex)
    {
        this.planner = planner;
        this.executor = executor;
        this.playerLoc = playerLoc;
        this.entityIndex = entityIndex;
    }

    private static ExecutorHook hookFor(V2Executor executor)
    {
        return new ExecutorHook()
        {
            @Override public void setPath(V2Path p) { executor.setPath(p); }
            @Override public V2Executor.Status tick() { return executor.tick(); }
            @Override public void cancel() { executor.cancel(); }
            @Override public V2Executor.Status status() { return executor.status(); }
            @Override public V2Executor.FailureReason lastFailureReason() { return executor.lastFailureReason(); }
            @Override public boolean wantsReplanFromHere() { return executor.wantsReplanFromHere(); }
        };
    }

    /** Tagged reason for the most recent FAILED transition. {@code null}
     *  while RUNNING / ARRIVED / IDLE or after {@link #cancel}. */
    @Nullable public FailureReason lastFailureReason() { return lastFailureReason; }

    /** When {@link #lastFailureReason} is {@link FailureReason#EXECUTOR_FAILED},
     *  the underlying executor's reason explains *why* the executor
     *  failed (TRANSPORT_EXECUTOR_MISSING / CATCHUP_EXHAUSTED / etc.).
     *  Returns {@code null} for non-executor failures or when the
     *  executor never failed. */
    @Nullable public V2Executor.FailureReason lastExecutorFailureReason()
    {
        return executor.lastFailureReason();
    }

    @Override
    public NavStatus tick(NavRequest request) throws InterruptedException
    {
        if (request == null)
        {
            log.debug("worldmap-v2: null request — V2 requires a target");
            lastFailureReason = FailureReason.BAD_REQUEST;
            return NavStatus.FAILED;
        }
        WorldPoint target = resolveTarget(request);
        if (target == null)
        {
            return NavStatus.FAILED;
        }
        if (!target.equals(activeTarget))
        {
            // New (or first) destination — replan and reset budget.
            WorldPoint here = playerLoc.get();
            if (here == null)
            {
                log.warn("worldmap-v2: no player location, cannot plan");
                lastFailureReason = FailureReason.NO_PLAYER_LOC;
                return NavStatus.FAILED;
            }
            V2Path path = planner.plan(here, target, request.mode(), request.trailName());
            if (path == null || path.isEmpty())
            {
                log.warn("worldmap-v2: NO_ROUTE from {} to {} — {}",
                    here, target, diagnoseSafely(here, target));
                activeTarget = null;
                activePath = null;
                executor.cancel();
                lastFailureReason = FailureReason.NO_ROUTE;
                return NavStatus.FAILED;
            }
            log.info("worldmap-v2: plan {} → {} legs={} cost={} routeId={} trail={}",
                here, target, path.legs().size(), path.totalCost(), path.routeId(),
                request.trailName() == null ? "none" : request.trailName());
            activeTarget = target;
            activePath = path;
            lastFailureReason = null;
            replansThisRequest = 0;
            executor.setPath(path);
        }

        // Round-2 replan signal: executor self-corrected a transport edge
        // and asks the navigator to replan from the player's actual
        // position. Bounded by MAX_REPLANS_PER_REQUEST.
        if (executor.wantsReplanFromHere())
        {
            if (replansThisRequest >= MAX_REPLANS_PER_REQUEST)
            {
                log.warn("worldmap-v2: replan budget exhausted ({}/{}), surfacing FAILED — exec.reason={}",
                    replansThisRequest, MAX_REPLANS_PER_REQUEST, executor.lastFailureReason());
                lastFailureReason = FailureReason.EXECUTOR_FAILED;
                executor.cancel();
                return NavStatus.FAILED;
            }
            WorldPoint here = playerLoc.get();
            if (here == null)
            {
                log.warn("worldmap-v2: replan-from-here requested but player loc unknown");
                lastFailureReason = FailureReason.NO_PLAYER_LOC;
                return NavStatus.FAILED;
            }
            V2Path path = planner.plan(here, target, request.mode(), request.trailName());
            if (path == null || path.isEmpty())
            {
                log.warn("worldmap-v2: replan-from-here NO_ROUTE from {} to {} — {}",
                    here, target, diagnoseSafely(here, target));
                lastFailureReason = FailureReason.NO_ROUTE;
                executor.cancel();
                return NavStatus.FAILED;
            }
            log.info("worldmap-v2: replan-from-here {} → {} legs={} cost={} routeId={} (replan {}/{}) trail={}",
                here, target, path.legs().size(), path.totalCost(), path.routeId(),
                replansThisRequest + 1, MAX_REPLANS_PER_REQUEST,
                request.trailName() == null ? "none" : request.trailName());
            replansThisRequest++;
            activePath = path;
            executor.setPath(path);
            // Continue to executor.tick() below so the new path drives
            // immediately, not on the next tick.
        }

        NavStatus s = mapStatus(executor.tick());
        if (s == NavStatus.FAILED)
        {
            lastFailureReason = FailureReason.EXECUTOR_FAILED;
            log.warn("worldmap-v2: executor FAILED — exec.reason={}",
                executor.lastFailureReason());
        }
        else if (s == NavStatus.ARRIVED) lastFailureReason = null;
        return s;
    }

    private String diagnoseSafely(WorldPoint from, WorldPoint to)
    {
        try { return planner.diagnose(from, to); }
        catch (Throwable th) { return "(diagnostic threw: " + th.getMessage() + ")"; }
    }

    /** Resolve the request's target tile. Point and trail-with-point
     *  requests reuse {@code request.to()}; entity-targeted requests
     *  go through {@link EntityIndex} to find the nearest known
     *  sighting. Returns {@code null} (caller treats as FAILED) when
     *  resolution is impossible. */
    @Nullable
    private WorldPoint resolveTarget(NavRequest request)
    {
        // Direct point — preferred path.
        if (request.to() != null) return request.to();

        // Entity-targeted — Phase 16.
        if (request.entity() != null)
        {
            WorldPoint resolved = resolveEntity(request.entity());
            if (resolved == null) lastFailureReason = FailureReason.ENTITY_NOT_FOUND;
            return resolved;
        }

        log.debug("worldmap-v2: request has no point and no entity — V2 cannot satisfy");
        lastFailureReason = FailureReason.BAD_REQUEST;
        return null;
    }

    @Nullable
    private WorldPoint resolveEntity(NavRequest.EntityRef ref)
    {
        if (entityIndex == null)
        {
            log.warn("worldmap-v2: ENTITY_NOT_FOUND — no EntityIndex wired, cannot resolve {}",
                ref);
            return null;
        }
        WorldPoint here = playerLoc.get();
        if (here == null)
        {
            log.warn("worldmap-v2: cannot resolve entity — player location unknown");
            return null;
        }
        Optional<EntitySighting> nearest;
        if (ref.kind() == EntityKind.NPC)
        {
            nearest = entityIndex.nearestNpc(ref.name(), here);
        }
        else if (ref.kind() == EntityKind.OBJECT)
        {
            nearest = entityIndex.nearestObject(ref.name(), here);
        }
        else
        {
            // AREA reserved — round-1 not supported.
            log.warn("worldmap-v2: ENTITY_NOT_FOUND — kind {} not implemented in round 1",
                ref.kind());
            return null;
        }
        if (nearest.isEmpty())
        {
            log.info("worldmap-v2: ENTITY_NOT_FOUND for name=\"{}\" kind={} action={} from {} — "
                + "no sightings in EntityIndex",
                ref.name(), ref.kind(), ref.action() == null ? "(none)" : ref.action(), here);
            return null;
        }
        WorldPoint t = nearest.get().lastTile;
        log.info("worldmap-v2: resolved entity name=\"{}\" kind={} action={} → {} (via nearest sighting)",
            ref.name(), ref.kind(), ref.action() == null ? "(none)" : ref.action(), t);
        return t;
    }

    private NavStatus mapStatus(V2Executor.Status s)
    {
        switch (s)
        {
            case ARRIVED: return NavStatus.ARRIVED;
            case FAILED: return NavStatus.FAILED;
            case RUNNING: return NavStatus.RUNNING;
            case IDLE:
            default: return NavStatus.IDLE;
        }
    }

    @Override
    public void cancel()
    {
        activeTarget = null;
        activePath = null;
        lastFailureReason = null;
        executor.cancel();
    }

    @Override
    public boolean isBusy()
    {
        V2Executor.Status s = executor.status();
        return s == V2Executor.Status.RUNNING;
    }

    @Override
    public String name()
    {
        return NAME;
    }
}
