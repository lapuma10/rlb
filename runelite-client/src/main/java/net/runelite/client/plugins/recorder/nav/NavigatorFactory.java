package net.runelite.client.plugins.recorder.nav;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.recorder.RecorderConfig;
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
 *  <p>Round 1 only registers V1. Selecting {@code WORLDMAP_V2} from
 *  the panel before V2 is wired logs a warning and falls back to
 *  V1, rather than throwing. Throwing here would crash plugin
 *  startup if a stale config value pointed at V2; falling back keeps
 *  the bot usable and surfaces the mismatch via the log. The V2
 *  branch is replaced with a real {@code v2} return in Phase 6. */
@Slf4j
public final class NavigatorFactory
{
    private final RecorderConfig config;
    private final Navigator trailV1;

    public NavigatorFactory(RecorderConfig config,
                            TrailWalker walker,
                            TrailRegistry registry)
    {
        this(config, new TrailNavigator(walker, registry));
    }

    NavigatorFactory(RecorderConfig config, Navigator trailV1)
    {
        this.config = config;
        this.trailV1 = trailV1;
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
                log.warn("nav-factory: V2 (WORLDMAP_V2) selected but not yet "
                    + "registered — using V1 (TrailWalker) until Phase 6 wires V2");
                return trailV1;
            default:
                log.warn("nav-factory: unknown NavigatorImpl {} — falling back to V1", impl);
                return trailV1;
        }
    }
}
