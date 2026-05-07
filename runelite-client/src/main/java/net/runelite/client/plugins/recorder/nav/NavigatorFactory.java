package net.runelite.client.plugins.recorder.nav;

import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.recorder.RecorderConfig;
import net.runelite.client.plugins.recorder.nav.v2.V2Navigator;
import net.runelite.client.plugins.recorder.trail.TrailRegistry;
import net.runelite.client.plugins.recorder.trail.TrailWalker;

/** Single switch site for which {@link Navigator} a script receives.
 *
 *  <p>Reads {@link RecorderConfig#navigatorImpl()} and returns the
 *  matching pre-built instance. Instances are stable for the
 *  factory's lifetime — scripts that call {@link #getNavigator()}
 *  back-to-back get the same object, so internal state (resolved
 *  trail, busy flag) isn't dropped on cross-tick lookups.
 *
 *  <p>Phase 6 wires V2 in. The factory takes a {@link V2Navigator}
 *  built per-script (each script's {@link
 *  net.runelite.client.sequence.dispatch.HumanizedInputDispatcher}
 *  and {@link net.runelite.client.plugins.recorder.nav.v2.V2Executor}
 *  are private to the script — V2Planner and the world stores are
 *  shared singletons in the plugin). If the V2 instance is null
 *  (factory built before V2 became available, or test setup),
 *  selecting V2 falls back to V1 with a warning rather than throwing
 *  — startup must stay resilient to a stale config value. */
@Slf4j
public final class NavigatorFactory
{
    private final RecorderConfig config;
    private final Navigator trailV1;
    @Nullable private final Navigator worldmapV2;

    /** Production constructor — builds {@link TrailNavigator} from the
     *  walker + registry; binds the externally-built {@code v2} as the
     *  WORLDMAP_V2 instance. */
    public NavigatorFactory(RecorderConfig config,
                            TrailWalker walker,
                            TrailRegistry registry,
                            @Nullable V2Navigator v2)
    {
        this(config, new TrailNavigator(walker, registry), v2);
    }

    /** Test-friendly ctor — bind both Navigators directly. */
    public NavigatorFactory(RecorderConfig config, Navigator trailV1, @Nullable Navigator worldmapV2)
    {
        this.config = config;
        this.trailV1 = trailV1;
        this.worldmapV2 = worldmapV2;
    }

    /** Legacy 2-arg test ctor for round-1 sites that haven't built V2
     *  yet. Same fallback behavior as a null V2 — V2 selection logs
     *  and returns V1. */
    public NavigatorFactory(RecorderConfig config, Navigator trailV1)
    {
        this(config, trailV1, (Navigator) null);
    }

    /** Returns the Navigator the user has selected via {@link
     *  RecorderConfig#navigatorImpl()}. Stable across calls within
     *  a session — the factory keeps one instance per implementation. */
    public Navigator getNavigator()
    {
        RecorderConfig.NavigatorImpl impl = config.navigatorImpl();
        if (impl == null) impl = RecorderConfig.NavigatorImpl.TRAIL_V1;
        switch (impl)
        {
            case TRAIL_V1:
                return trailV1;
            case WORLDMAP_V2:
                if (worldmapV2 == null)
                {
                    log.warn("nav-factory: V2 selected but no V2 instance bound to this factory "
                        + "— using V1 (TrailWalker)");
                    return trailV1;
                }
                return worldmapV2;
            default:
                log.warn("nav-factory: unknown NavigatorImpl {} — falling back to V1", impl);
                return trailV1;
        }
    }
}
