package net.runelite.client.plugins.recorder.nav.v2.predicate;

import java.util.function.Supplier;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.nav.v2.collision.CollisionView;
import net.runelite.client.plugins.recorder.nav.v2.collision.WorldSnapshot;
import net.runelite.client.sequence.views.InteractionMode;

/** Factory methods producing the eight built-in tile predicates per
 *  spec §4 Lane 2. Each factory takes the context it needs at
 *  construction (most need a {@link WorldSnapshot}) and returns a
 *  {@link TilePredicate} closing over that context.
 *
 *  <p>All built-ins are pure functions over the captured snapshot.
 *  Construction is the only state-bearing step; {@link
 *  TilePredicate#accept(WorldPoint, PathContext)} runs without side
 *  effects.
 *
 *  <p>Naming conventions per spec §4 Lane 2:
 *  <ul>
 *    <li>{@code notBlocked} — full-tile-block flag check.</li>
 *    <li>{@code liveCollisionAllows} — live overlay specifically (not global).</li>
 *    <li>{@code sceneClean} — no movement-blocking actor or dynamic object on tile.</li>
 *    <li>{@code notOccupiedByBlockingActor} — actor-only.</li>
 *    <li>{@code notOccupiedByBlockingObject} — object-only.</li>
 *    <li>{@code interactionModeWorld} — current {@link InteractionMode} is WORLD.</li>
 *    <li>{@code notDangerousArea} — wilderness/PvP unless opted in.</li>
 *    <li>{@code scriptAllowed} — pass-through; always-true.</li>
 *  </ul> */
public final class BuiltInPredicates
{
    private BuiltInPredicates() {}

    /** Wilderness band starts at y &gt;= 3520 on plane 0. Conservative
     *  outline for {@link #notDangerousArea(boolean)}; refined as
     *  needed by Lane 6 acceptance failures. */
    private static final int WILDERNESS_MIN_Y = 3520;

    /** True iff the tile is not fully blocked by collision flags. */
    public static TilePredicate notBlocked(WorldSnapshot snap)
    {
        return (tile, ctx) ->
            (snap.collisionAt(tile).bits() & CollisionDataFlag.BLOCK_MOVEMENT_FULL) == 0;
    }

    /** True iff the live overlay (not global snapshot) specifically
     *  permits movement to this tile. Returns false for tiles outside
     *  the loaded scene. Used to weight live data over stale snapshot. */
    public static TilePredicate liveCollisionAllows(WorldSnapshot snap)
    {
        return (tile, ctx) ->
        {
            // Only accept if the source is LIVE_OVERLAY *and* the tile isn't
            // fully blocked. Tiles outside scene fall back to global and
            // produce LIVE_OVERLAY=false — the predicate rejects them.
            var cf = snap.collisionAt(tile);
            if (cf.source() != CollisionView.Source.LIVE_OVERLAY) return false;
            return !cf.isFullyBlocked();
        };
    }

    /** True iff the tile has neither a movement-blocking actor nor a
     *  movement-blocking object on it. Per spec §4 Lane 2 clarification,
     *  ground items / passable NPCs / decorative objects do NOT trigger
     *  this predicate — otherwise the planner would avoid valid tiles
     *  for no reason. */
    public static TilePredicate sceneClean(WorldSnapshot snap)
    {
        return (tile, ctx) ->
            !snap.blockingActorTiles().contains(tile)
            && !snap.blockingObjectTiles().contains(tile);
    }

    /** True iff no movement-blocking actor occupies the tile. */
    public static TilePredicate notOccupiedByBlockingActor(WorldSnapshot snap)
    {
        return (tile, ctx) -> !snap.blockingActorTiles().contains(tile);
    }

    /** True iff no movement-blocking dynamic object occupies the tile. */
    public static TilePredicate notOccupiedByBlockingObject(WorldSnapshot snap)
    {
        return (tile, ctx) -> !snap.blockingObjectTiles().contains(tile);
    }

    /** True iff the current {@link InteractionMode} is WORLD (the
     *  player can click world tiles). Reads the mode supplier each
     *  call — production callers should pass {@code observer::mode}.
     *
     *  <p>Note: this predicate is uniform across all tiles. It's a
     *  predicate not a top-level guard because the planner can mix it
     *  with tile-specific predicates and reuse the registry pipeline. */
    public static TilePredicate interactionModeWorld(Supplier<InteractionMode> modeSupplier)
    {
        return (tile, ctx) -> modeSupplier.get() == InteractionMode.WORLD;
    }

    /** True iff the tile is NOT in a dangerous (PvP) area — i.e.
     *  outside the wilderness on plane 0. Pass {@code optIn=true} to
     *  allow wilderness tiles (e.g. for a wilderness-aware script).
     *
     *  <p>Conservative outline; Lane 6 may surface other danger zones
     *  (e.g. Castle Wars lobby) for inclusion. */
    public static TilePredicate notDangerousArea(boolean optIn)
    {
        return (tile, ctx) ->
        {
            if (optIn) return true;
            return !isWilderness(tile);
        };
    }

    /** Pass-through predicate. Always accepts. Used by scripts that
     *  want to inject a behaviour through the predicate pipeline
     *  without filtering tiles themselves. */
    public static TilePredicate scriptAllowed()
    {
        return (tile, ctx) -> true;
    }

    /** Visible for testing — conservative wilderness check. */
    static boolean isWilderness(WorldPoint p)
    {
        // OSRS wilderness on plane 0: y >= 3520 between x 2944 and 3392 roughly.
        // We use a broad y-band; the inside detail is left to Lane 6's data.
        if (p.getY() < WILDERNESS_MIN_Y) return false;
        // Wilderness extends well below plane 0 in dungeons (Mage Arena, Pit),
        // but we filter only on the surface for now.
        return p.getPlane() == 0;
    }
}
