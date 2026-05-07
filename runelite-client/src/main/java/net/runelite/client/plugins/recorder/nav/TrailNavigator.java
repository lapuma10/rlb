package net.runelite.client.plugins.recorder.nav;

import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.recorder.trail.Trail;
import net.runelite.client.plugins.recorder.trail.TrailPath;
import net.runelite.client.plugins.recorder.trail.TrailRegistry;
import net.runelite.client.plugins.recorder.trail.TrailWalker;

/** V1 adapter — wraps {@link TrailWalker} behind the {@link Navigator}
 *  contract without modifying the walker. The walker is frozen as the
 *  known-good fallback, so this adapter only translates calls.
 *
 *  <p>V1 needs a named trail, not a destination point. Requests
 *  carrying only a {@code to} {@code WorldPoint} fail fast — the V2
 *  navigator handles those.
 *
 *  <p>Status mapping: TrailWalker's {@code IN_PROGRESS} → {@link
 *  NavStatus#RUNNING}; {@code ARRIVED} → {@link NavStatus#ARRIVED};
 *  {@code STUCK} and {@code ERROR} both collapse to {@link
 *  NavStatus#FAILED}. The script's recovery path is the same for both
 *  walker failure modes, so the granularity isn't worth carrying
 *  through the interface. */
@Slf4j
public final class TrailNavigator implements Navigator
{
    /** Package-private seam: production constructor binds this to the
     *  {@link TrailWalker} passed in; tests substitute a recording stub
     *  to assert adapter behavior without standing up the walker's full
     *  collaborator graph (Client + ClientThread + dispatcher). */
    interface WalkerHook
    {
        TrailWalker.Status tick(TrailPath path) throws InterruptedException;
        void reset();
    }

    private static final String NAME = "trail-v1";

    private final TrailRegistry registry;
    private final WalkerHook walker;

    @Nullable private String activeTrailName;
    @Nullable private TrailPath activePath;
    private boolean busy;

    public TrailNavigator(TrailWalker walker, TrailRegistry registry)
    {
        this(registry, new WalkerHook()
        {
            @Override
            public TrailWalker.Status tick(TrailPath path) throws InterruptedException
            {
                return walker.tick(path);
            }

            @Override
            public void reset()
            {
                walker.reset();
            }
        });
    }

    TrailNavigator(TrailRegistry registry, WalkerHook walker)
    {
        this.registry = registry;
        this.walker = walker;
    }

    @Override
    public NavStatus tick(NavRequest request) throws InterruptedException
    {
        if (request == null || request.trailName() == null)
        {
            log.debug("trail-v1: request missing trailName; V1 requires a named trail");
            cancelInternal();
            return NavStatus.FAILED;
        }

        if (!request.trailName().equals(activeTrailName))
        {
            cancelInternal();
            Trail trail = registry.byName(request.trailName());
            if (trail == null || trail.events().isEmpty())
            {
                log.warn("trail-v1: trail '{}' missing or empty in registry",
                    request.trailName());
                return NavStatus.FAILED;
            }
            TrailPath path = TrailPath.fromTrail(trail);
            if (path.isEmpty())
            {
                log.warn("trail-v1: trail '{}' resolved to an empty TrailPath",
                    request.trailName());
                return NavStatus.FAILED;
            }
            activeTrailName = request.trailName();
            activePath = path;
        }

        busy = true;
        return mapStatus(walker.tick(activePath));
    }

    private NavStatus mapStatus(TrailWalker.Status s)
    {
        switch (s)
        {
            case ARRIVED:
                busy = false;
                return NavStatus.ARRIVED;
            case STUCK:
            case ERROR:
                busy = false;
                return NavStatus.FAILED;
            case IN_PROGRESS:
            default:
                return NavStatus.RUNNING;
        }
    }

    @Override
    public void cancel()
    {
        cancelInternal();
    }

    private void cancelInternal()
    {
        if (activeTrailName != null || activePath != null || busy)
        {
            walker.reset();
        }
        activeTrailName = null;
        activePath = null;
        busy = false;
    }

    @Override
    public boolean isBusy()
    {
        return busy;
    }

    @Override
    public String name()
    {
        return NAME;
    }
}
