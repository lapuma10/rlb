package net.runelite.client.plugins.recorder.nav.v2;

import java.util.function.Supplier;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.nav.BehaviorMode;
import net.runelite.client.plugins.recorder.nav.NavRequest;
import net.runelite.client.plugins.recorder.nav.NavStatus;
import net.runelite.client.plugins.recorder.nav.Navigator;

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
    /** Test seam for the planner. Production binds to {@code V2Planner::plan}. */
    public interface PlannerHook
    {
        V2Path plan(WorldPoint from, WorldPoint to, BehaviorMode mode);
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

    @Nullable private WorldPoint activeTarget;
    @Nullable private V2Path activePath;

    public V2Navigator(V2Planner planner, V2Executor executor, PlayerLocSupplier playerLoc)
    {
        this(planner::plan, hookFor(executor), playerLoc);
    }

    V2Navigator(PlannerHook planner, ExecutorHook executor, PlayerLocSupplier playerLoc)
    {
        this.planner = planner;
        this.executor = executor;
        this.playerLoc = playerLoc;
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
        if (request == null || request.to() == null)
        {
            log.debug("worldmap-v2: request has no destination point — V2 requires (x,y,plane)");
            return NavStatus.FAILED;
        }
        WorldPoint target = request.to();
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
                log.warn("worldmap-v2: planner returned no route from {} to {}", here, target);
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
