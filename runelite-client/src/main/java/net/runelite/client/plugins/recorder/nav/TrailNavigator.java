package net.runelite.client.plugins.recorder.nav;

import java.util.function.Supplier;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.trail.Leg;
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

    static final int ENTRY_LEG_MAX_DISTANCE = 15;

    private static final String NAME = "trail-v1";

    private final TrailRegistry registry;
    private final WalkerHook walker;
    /** Returns the player's current tile, or null if unknown. Called once
     *  per new-trail load to pick the nearest entry leg. */
    private final Supplier<WorldPoint> playerPositionSupplier;

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
        }, walker::readPlayerTile);
    }

    TrailNavigator(TrailRegistry registry, WalkerHook walker)
    {
        this(registry, walker, () -> null);
    }

    TrailNavigator(TrailRegistry registry, WalkerHook walker,
                   Supplier<WorldPoint> playerPositionSupplier)
    {
        this.registry = registry;
        this.walker = walker;
        this.playerPositionSupplier = playerPositionSupplier == null ? () -> null
            : playerPositionSupplier;
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
            WorldPoint pos = playerPositionSupplier.get();
            if (pos != null)
            {
                int entryIdx = path.findEntryLeg(pos);
                int minDist = minLegDistance(path, entryIdx, pos);
                if (minDist > ENTRY_LEG_MAX_DISTANCE)
                {
                    log.warn("trail-v1: player at {} is {} tiles from nearest leg {} — "
                        + "trail '{}' not reachable from here (threshold={})",
                        pos, minDist, entryIdx, request.trailName(), ENTRY_LEG_MAX_DISTANCE);
                    return NavStatus.FAILED;
                }
                if (entryIdx > 0)
                {
                    log.info("trail-v1: entering trail '{}' at leg {} (player={} dist={})",
                        request.trailName(), entryIdx, pos, minDist);
                    path = path.subPath(entryIdx);
                }
            }
            activeTrailName = request.trailName();
            activePath = path;
        }

        busy = true;
        return mapStatus(walker.tick(activePath));
    }

    /** Chebyshev distance from {@code pos} to the nearest tile of leg
     *  {@code idx} in {@code path}. Returns {@link Integer#MAX_VALUE} if
     *  no tile on the player's plane exists in that leg. */
    private static int minLegDistance(TrailPath path, int idx, WorldPoint pos)
    {
        if (idx < 0 || idx >= path.size()) return Integer.MAX_VALUE;
        Leg leg = path.legs().get(idx);
        if (leg instanceof Leg.Walk w)
        {
            int best = Integer.MAX_VALUE;
            for (WorldPoint t : w.tiles())
            {
                if (t.getPlane() != pos.getPlane()) continue;
                int d = Math.max(Math.abs(t.getX() - pos.getX()),
                                 Math.abs(t.getY() - pos.getY()));
                if (d < best) best = d;
            }
            return best;
        }
        if (leg instanceof Leg.Transport t)
        {
            WorldPoint tile = t.tile();
            if (tile.getPlane() != pos.getPlane()) return Integer.MAX_VALUE;
            return Math.max(Math.abs(tile.getX() - pos.getX()),
                            Math.abs(tile.getY() - pos.getY()));
        }
        return Integer.MAX_VALUE;
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
