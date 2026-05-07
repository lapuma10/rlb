package net.runelite.client.plugins.recorder.nav;

import javax.annotation.Nullable;
import net.runelite.api.coords.WorldPoint;

/** Immutable request handed to {@link Navigator#tick(NavRequest)}.
 *
 *  <p>Three target shapes:
 *  <ul>
 *    <li>{@code to} — concrete destination tile.</li>
 *    <li>{@code trailName} — named V1 trail.</li>
 *    <li>{@code entity} — Phase 16 entity target ("Cook" / "Bank booth")
 *        resolved by V2 via {@code EntityIndex} to the nearest known
 *        sighting.</li>
 *  </ul>
 *  A request may carry more than one shape; each Navigator picks the
 *  shape it understands. V1 needs {@code trailName}; V2 prefers
 *  {@code entity}, then {@code to}; an entity-only request fed to V1
 *  fails so {@link HybridNavigator} falls back appropriately.
 *
 *  <p>{@link BehaviorMode} hints how aggressively the navigator should
 *  vary route or click target.
 *
 *  <p>Build with the {@link #toPoint}, {@link #byTrail}, {@link #compose},
 *  or {@link #toEntity} factories rather than the canonical record
 *  constructor — call sites stay readable. */
public record NavRequest(@Nullable WorldPoint to,
                         BehaviorMode mode,
                         @Nullable String trailName,
                         @Nullable EntityRef entity)
{
    public NavRequest
    {
        if (mode == null) mode = BehaviorMode.VARIED;
    }

    /** Phase-16 entity reference: name + kind + optional action verb.
     *  V2 resolves the name to the nearest known sighting and walks to
     *  it; the script issues the verb-click after the navigator
     *  arrives. {@code action} is advisory — the navigator does not
     *  invoke it; it's recorded so the inspection log knows the
     *  intent ("walk to Bank booth, intent: Bank"). */
    public record EntityRef(String name, EntityKind kind, @Nullable String action) {}

    /** Destination-only request. V2 plans a route to {@code to}; V1
     *  rejects it because it requires a trail name. */
    public static NavRequest toPoint(WorldPoint to, BehaviorMode mode)
    {
        return new NavRequest(to, mode, null, null);
    }

    /** Named-trail request. V1 looks up the trail in its registry; V2
     *  may consult the trail name as a hint but is not required to. */
    public static NavRequest byTrail(String trailName, BehaviorMode mode)
    {
        return new NavRequest(null, mode, trailName, null);
    }

    /** Both forms — trail name plus the trail's destination tile. V1
     *  uses the trail name; V2 plans to the {@code to} point. Use this
     *  for scripts that want to support either Navigator behind one
     *  request, e.g. ChickenFarmV3 driving the bank↔pen loop. */
    public static NavRequest compose(String trailName, WorldPoint to, BehaviorMode mode)
    {
        return new NavRequest(to, mode, trailName, null);
    }

    /** Phase-16 entity target. V2 resolves to nearest sighting in
     *  EntityIndex; if no sighting, returns {@link NavStatus#FAILED}
     *  with reason ENTITY_NOT_FOUND in the log. V1 fails because it
     *  has no notion of dynamic entity lookup. */
    public static NavRequest toEntity(String name, EntityKind kind, BehaviorMode mode)
    {
        return new NavRequest(null, mode, null, new EntityRef(name, kind, null));
    }

    /** Entity target with an intended action verb (advisory — recorded
     *  for inspection logs). */
    public static NavRequest toEntity(String name, EntityKind kind, String action, BehaviorMode mode)
    {
        return new NavRequest(null, mode, null, new EntityRef(name, kind, action));
    }
}
