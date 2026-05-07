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
        default String diagnose(WorldPoint from, WorldPoint to) { return "(no diagnostic available)"; }
    }

    /** Test seam for the executor. Production wraps a {@link V2Executor}. */
    public interface ExecutorHook
    {
        void setPath(V2Path path);
        V2Executor.Status tick();
        void cancel();
        V2Executor.Status status();
    }

    /** Supplier for the player's current world location. Production
     *  marshals to the client thread. Tests inject a fixed point. */
    public interface PlayerLocSupplier extends Supplier<WorldPoint>
    {
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
        };
    }

    @Override
    public NavStatus tick(NavRequest request) throws InterruptedException
    {
        if (request == null)
        {
            log.debug("worldmap-v2: null request — V2 requires a target");
            return NavStatus.FAILED;
        }
        WorldPoint target = resolveTarget(request);
        if (target == null)
        {
            return NavStatus.FAILED;
        }
        if (!target.equals(activeTarget))
        {
            // New (or first) destination — replan.
            WorldPoint here = playerLoc.get();
            if (here == null)
            {
                log.warn("worldmap-v2: no player location, cannot plan");
                return NavStatus.FAILED;
            }
            V2Path path = planner.plan(here, target, request.mode());
            if (path == null || path.isEmpty())
            {
                log.warn("worldmap-v2: planner returned no route from {} to {} — {}",
                    here, target, diagnoseSafely(here, target));
                activeTarget = null;
                activePath = null;
                executor.cancel();
                return NavStatus.FAILED;
            }
            log.info("worldmap-v2: plan {} → {} legs={} cost={}",
                here, target, path.legs().size(), path.totalCost());
            activeTarget = target;
            activePath = path;
            executor.setPath(path);
        }
        return mapStatus(executor.tick());
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
            return resolveEntity(request.entity());
        }

        log.debug("worldmap-v2: request has no point and no entity — V2 cannot satisfy");
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
            log.info("worldmap-v2: ENTITY_NOT_FOUND for name=\"{}\" kind={} from {} — "
                + "no sightings in EntityIndex", ref.name(), ref.kind(), here);
            return null;
        }
        WorldPoint t = nearest.get().lastTile;
        log.info("worldmap-v2: resolved entity name=\"{}\" kind={} → {} (via nearest sighting)",
            ref.name(), ref.kind(), t);
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
