package net.runelite.client.plugins.recorder.nav.v2.predicate;

import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import net.runelite.api.CollisionData;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.Constants;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.nav.v2.collision.CollisionView;
import net.runelite.client.plugins.recorder.nav.v2.collision.GlobalCollisionSnapshot;
import net.runelite.client.plugins.recorder.nav.v2.collision.LiveSceneCollisionOverlay;
import net.runelite.client.plugins.recorder.nav.v2.collision.WorldSnapshot;
import net.runelite.client.plugins.recorder.nav.v2.collision.WorldSnapshotBuilder;
import net.runelite.client.sequence.views.InteractionMode;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Tests for {@link BuiltInPredicates}. Verifies each factory produces a
 *  predicate with the documented semantics. */
public class BuiltInPredicatesTest
{
    private static PathContext ctx()
    {
        return new PathContext()
        {
            @Override public Object navigation() { return null; }
            @Override public Optional<Object> currentPath() { return Optional.empty(); }
            @Override public Optional<Object> currentWaypoint() { return Optional.empty(); }
            @Override public long routeSeed() { return 0; }
        };
    }

    private static WorldSnapshot snapshotWith(Set<WorldPoint> actors, Set<WorldPoint> objects)
    {
        GlobalCollisionSnapshot global = GlobalCollisionSnapshot.fromBundledResource();
        int[][] zero = new int[Constants.SCENE_SIZE][Constants.SCENE_SIZE];
        CollisionData[] cd = { () -> zero, () -> zero, () -> zero, () -> zero };
        LiveSceneCollisionOverlay overlay = LiveSceneCollisionOverlay.capture(cd, 3200, 3200, 0);
        CollisionView view = new CollisionView(global, overlay);
        return WorldSnapshotBuilder.fromComponents(
            view, actors, objects, new Object(), new PredicateRegistry(), 0L);
    }

    private static WorldSnapshot snapshotWithFlag(WorldPoint tile, int flag)
    {
        GlobalCollisionSnapshot global = GlobalCollisionSnapshot.fromBundledResource();
        int[][] arr = new int[Constants.SCENE_SIZE][Constants.SCENE_SIZE];
        int sx = tile.getX() - 3200;
        int sy = tile.getY() - 3200;
        arr[sx][sy] = flag;
        CollisionData[] cd = { () -> arr, () -> new int[Constants.SCENE_SIZE][Constants.SCENE_SIZE],
            () -> new int[Constants.SCENE_SIZE][Constants.SCENE_SIZE],
            () -> new int[Constants.SCENE_SIZE][Constants.SCENE_SIZE] };
        LiveSceneCollisionOverlay overlay = LiveSceneCollisionOverlay.capture(cd, 3200, 3200, 0);
        CollisionView view = new CollisionView(global, overlay);
        return WorldSnapshotBuilder.fromComponents(
            view, Set.of(), Set.of(), new Object(), new PredicateRegistry(), 0L);
    }

    // -- notBlocked ----------------------------------------------------------

    @Test
    public void notBlocked_walkableTile_accepts()
    {
        WorldPoint tile = new WorldPoint(3210, 3210, 0);
        WorldSnapshot snap = snapshotWithFlag(tile, 0); // no flags
        TilePredicate p = BuiltInPredicates.notBlocked(snap);
        assertTrue(p.accept(tile, ctx()));
    }

    @Test
    public void notBlocked_blockedTile_rejects()
    {
        WorldPoint tile = new WorldPoint(3210, 3210, 0);
        WorldSnapshot snap = snapshotWithFlag(tile, CollisionDataFlag.BLOCK_MOVEMENT_FULL);
        TilePredicate p = BuiltInPredicates.notBlocked(snap);
        assertFalse(p.accept(tile, ctx()));
    }

    // -- liveCollisionAllows ------------------------------------------------

    @Test
    public void liveCollisionAllows_liveSourceAndWalkable_accepts()
    {
        WorldPoint tile = new WorldPoint(3210, 3210, 0);
        WorldSnapshot snap = snapshotWithFlag(tile, 0);
        TilePredicate p = BuiltInPredicates.liveCollisionAllows(snap);
        assertTrue(p.accept(tile, ctx()));
    }

    @Test
    public void liveCollisionAllows_liveSourceAndBlocked_rejects()
    {
        WorldPoint tile = new WorldPoint(3210, 3210, 0);
        WorldSnapshot snap = snapshotWithFlag(tile, CollisionDataFlag.BLOCK_MOVEMENT_FULL);
        TilePredicate p = BuiltInPredicates.liveCollisionAllows(snap);
        assertFalse(p.accept(tile, ctx()));
    }

    // -- sceneClean ---------------------------------------------------------

    @Test
    public void sceneClean_blockingActorOnTile_rejects()
    {
        WorldPoint tile = new WorldPoint(3210, 3210, 0);
        WorldSnapshot snap = snapshotWith(Set.of(tile), Set.of());
        TilePredicate p = BuiltInPredicates.sceneClean(snap);
        assertFalse(p.accept(tile, ctx()));
    }

    @Test
    public void sceneClean_groundItemOnTile_accepts()
    {
        // Ground items / non-blocking actors are NOT in blockingActorTiles or
        // blockingObjectTiles, so SceneClean accepts.
        WorldPoint tile = new WorldPoint(3210, 3210, 0);
        WorldSnapshot snap = snapshotWith(Set.of(), Set.of());
        TilePredicate p = BuiltInPredicates.sceneClean(snap);
        assertTrue(p.accept(tile, ctx()));
    }

    @Test
    public void sceneClean_blockingObjectOnTile_rejects()
    {
        WorldPoint tile = new WorldPoint(3210, 3210, 0);
        WorldSnapshot snap = snapshotWith(Set.of(), Set.of(tile));
        TilePredicate p = BuiltInPredicates.sceneClean(snap);
        assertFalse(p.accept(tile, ctx()));
    }

    // -- notOccupiedByBlockingActor -----------------------------------------

    @Test
    public void notOccupiedByBlockingActor_actorOnTile_rejects()
    {
        WorldPoint tile = new WorldPoint(3210, 3210, 0);
        WorldSnapshot snap = snapshotWith(Set.of(tile), Set.of());
        TilePredicate p = BuiltInPredicates.notOccupiedByBlockingActor(snap);
        assertFalse(p.accept(tile, ctx()));
    }

    @Test
    public void notOccupiedByBlockingActor_noActor_accepts()
    {
        WorldPoint tile = new WorldPoint(3210, 3210, 0);
        WorldSnapshot snap = snapshotWith(Set.of(), Set.of());
        TilePredicate p = BuiltInPredicates.notOccupiedByBlockingActor(snap);
        assertTrue(p.accept(tile, ctx()));
    }

    // -- notOccupiedByBlockingObject ----------------------------------------

    @Test
    public void notOccupiedByBlockingObject_objectOnTile_rejects()
    {
        WorldPoint tile = new WorldPoint(3210, 3210, 0);
        WorldSnapshot snap = snapshotWith(Set.of(), Set.of(tile));
        TilePredicate p = BuiltInPredicates.notOccupiedByBlockingObject(snap);
        assertFalse(p.accept(tile, ctx()));
    }

    @Test
    public void notOccupiedByBlockingObject_noObject_accepts()
    {
        WorldPoint tile = new WorldPoint(3210, 3210, 0);
        WorldSnapshot snap = snapshotWith(Set.of(), Set.of());
        TilePredicate p = BuiltInPredicates.notOccupiedByBlockingObject(snap);
        assertTrue(p.accept(tile, ctx()));
    }

    // -- interactionModeWorld -----------------------------------------------

    @Test
    public void interactionModeWorld_modeWorld_accepts()
    {
        Supplier<InteractionMode> mode = () -> InteractionMode.WORLD;
        TilePredicate p = BuiltInPredicates.interactionModeWorld(mode);
        assertTrue(p.accept(new WorldPoint(3210, 3210, 0), ctx()));
    }

    @Test
    public void interactionModeWorld_modeBanking_rejects()
    {
        Supplier<InteractionMode> mode = () -> InteractionMode.BANKING;
        TilePredicate p = BuiltInPredicates.interactionModeWorld(mode);
        assertFalse(p.accept(new WorldPoint(3210, 3210, 0), ctx()));
    }

    @Test
    public void interactionModeWorld_modeLoading_rejects()
    {
        Supplier<InteractionMode> mode = () -> InteractionMode.LOADING;
        TilePredicate p = BuiltInPredicates.interactionModeWorld(mode);
        assertFalse(p.accept(new WorldPoint(3210, 3210, 0), ctx()));
    }

    // -- notDangerousArea ---------------------------------------------------

    @Test
    public void notDangerousArea_wildernessTile_rejects()
    {
        // Wilderness band: y >= 3520 on plane 0.
        WorldPoint wildy = new WorldPoint(3200, 3525, 0);
        TilePredicate p = BuiltInPredicates.notDangerousArea(false);
        assertFalse(p.accept(wildy, ctx()));
    }

    @Test
    public void notDangerousArea_safeTile_accepts()
    {
        WorldPoint safe = new WorldPoint(3222, 3218, 0); // Lumbridge
        TilePredicate p = BuiltInPredicates.notDangerousArea(false);
        assertTrue(p.accept(safe, ctx()));
    }

    @Test
    public void notDangerousArea_optedIn_acceptsWilderness()
    {
        WorldPoint wildy = new WorldPoint(3200, 3525, 0);
        TilePredicate p = BuiltInPredicates.notDangerousArea(true);
        assertTrue(p.accept(wildy, ctx()));
    }

    // -- scriptAllowed ------------------------------------------------------

    @Test
    public void scriptAllowed_alwaysAccepts()
    {
        TilePredicate p = BuiltInPredicates.scriptAllowed();
        assertTrue(p.accept(new WorldPoint(0, 0, 0), ctx()));
        assertTrue(p.accept(new WorldPoint(99999, 99999, 3), ctx()));
    }
}
