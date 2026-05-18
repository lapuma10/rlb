package net.runelite.client.plugins.recorder.nav;

import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.recorder.RecorderConfig;
import net.runelite.client.plugins.recorder.nav.v2.V2Navigator;
import net.runelite.client.plugins.recorder.trail.TrailRegistry;
import net.runelite.client.plugins.recorder.trail.TrailWalker;

/** Single switch site for which {@link Navigator} a script receives.
 *
 *  <p>Phase 7: scripts always get a {@link HybridNavigator} — the
 *  per-tick mode dispatcher. The factory's only job is to construct
 *  the V1 + V2 instances and bind the live mode supplier. The
 *  HybridNavigator instance is stable across calls so internal state
 *  (active request, fallback flag) survives back-to-back lookups.
 *
 *  <p>If V2 isn't available (factory built without a V2Navigator —
 *  test setup, or the plugin was loaded before the worldmap subsystem
 *  was ready), the HybridNavigator silently degrades: V2_WITH_V1_FALLBACK
 *  becomes V1, V2_STRICT FAILs cleanly with a log line. Plugin startup
 *  must stay resilient. */
@Slf4j
public final class NavigatorFactory
{
    private final HybridNavigator hybrid;

    /** Production constructor — builds {@link TrailNavigator} from the
     *  walker + registry; binds the externally-built {@code v2} as the
     *  V2 instance. v2.1 defaults to null (older call sites). */
    public NavigatorFactory(RecorderConfig config,
                            TrailWalker walker,
                            TrailRegistry registry,
                            @Nullable V2Navigator v2)
    {
        this(config, walker, registry, v2, null);
    }

    /** Production ctor including v2.1. Pass the reactive Navigator
     *  instance built by {@code RecorderPlugin.buildV21Navigator}; null
     *  means "v2.1 mode falls back to V1". */
    public NavigatorFactory(RecorderConfig config,
                            TrailWalker walker,
                            TrailRegistry registry,
                            @Nullable V2Navigator v2,
                            @Nullable Navigator v21)
    {
        this(config, new TrailNavigator(walker, registry), v2, v21);
    }

    /** Test-friendly ctor — bind both Navigators directly. */
    public NavigatorFactory(RecorderConfig config, Navigator trailV1, @Nullable Navigator worldmapV2)
    {
        this(config, trailV1, worldmapV2, null);
    }

    /** Test-friendly ctor — bind all three Navigators directly. */
    public NavigatorFactory(RecorderConfig config, Navigator trailV1,
                            @Nullable Navigator worldmapV2,
                            @Nullable Navigator reactiveV21)
    {
        this.hybrid = new HybridNavigator(trailV1, worldmapV2, reactiveV21, config::navigatorMode);
    }

    /** Legacy 2-arg test ctor for sites that haven't built V2 yet.
     *  V2 / V2.1 selection there falls back to V1. */
    public NavigatorFactory(RecorderConfig config, Navigator trailV1)
    {
        this(config, trailV1, (Navigator) null);
    }

    /** Returns the {@link HybridNavigator}. Stable across calls within
     *  a session — the factory keeps a single instance so internal
     *  request/state tracking survives across cross-tick lookups. */
    public Navigator getNavigator()
    {
        return hybrid;
    }
}
