package net.runelite.client.plugins.recorder.nav;

import javax.annotation.Nullable;
import net.runelite.api.coords.WorldPoint;

/** Immutable request handed to {@link Navigator#tick(NavRequest)}.
 *
 *  <p>Carries either a destination point ({@code to}) or a named V1 trail
 *  ({@code trailName}); each Navigator implementation reads what it
 *  needs. {@link BehaviorMode} hints how aggressively the navigator
 *  should vary route or click target.
 *
 *  <p>Build with the {@link #toPoint} or {@link #byTrail} factories
 *  rather than the canonical record constructor, so call sites stay
 *  readable. */
public record NavRequest(@Nullable WorldPoint to,
                         BehaviorMode mode,
                         @Nullable String trailName)
{
    public NavRequest
    {
        if (mode == null) mode = BehaviorMode.VARIED;
    }

    /** Destination-only request. V2 plans a route to {@code to}; V1
     *  rejects it because it requires a trail name. */
    public static NavRequest toPoint(WorldPoint to, BehaviorMode mode)
    {
        return new NavRequest(to, mode, null);
    }

    /** Named-trail request. V1 looks up the trail in its registry; V2
     *  may consult the trail name as a hint but is not required to. */
    public static NavRequest byTrail(String trailName, BehaviorMode mode)
    {
        return new NavRequest(null, mode, trailName);
    }
}
