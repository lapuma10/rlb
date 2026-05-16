package net.runelite.client.plugins.recorder.nav.v2.collision;

import java.util.HashSet;
import java.util.Set;
import net.runelite.api.CollisionData;
import net.runelite.api.Constants;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.nav.v2.predicate.PredicateRegistry;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Tests for {@link WorldSnapshotBuilder}. */
public class WorldSnapshotBuilderTest
{
    private static CollisionView dummyView()
    {
        GlobalCollisionSnapshot global = GlobalCollisionSnapshot.fromBundledResource();
        int[][] zero = new int[Constants.SCENE_SIZE][Constants.SCENE_SIZE];
        CollisionData[] cd = { () -> zero, () -> zero, () -> zero, () -> zero };
        LiveSceneCollisionOverlay overlay = LiveSceneCollisionOverlay.capture(cd, 3200, 3200, 0);
        return new CollisionView(global, overlay);
    }

    @Test
    public void build_capturesAllInputs()
    {
        CollisionView view = dummyView();
        Set<WorldPoint> actors = Set.of(new WorldPoint(3210, 3210, 0));
        Set<WorldPoint> objects = Set.of(new WorldPoint(3211, 3211, 0));
        PredicateRegistry preds = new PredicateRegistry();
        Object transports = new Object();

        WorldSnapshot snap = WorldSnapshotBuilder.fromComponents(
            view, actors, objects, transports, preds, 12345L);

        assertEquals(actors, snap.blockingActorTiles());
        assertEquals(objects, snap.blockingObjectTiles());
        assertEquals(transports, snap.transports());
        assertEquals(preds, snap.predicates());
        assertEquals(12345L, snap.capturedAtMs());
        assertNotNull(snap.collisionView());
    }

    @Test
    public void blockingActorTiles_isUnmodifiable()
    {
        CollisionView view = dummyView();
        Set<WorldPoint> actors = new HashSet<>();
        actors.add(new WorldPoint(3210, 3210, 0));
        PredicateRegistry preds = new PredicateRegistry();

        WorldSnapshot snap = WorldSnapshotBuilder.fromComponents(
            view, actors, Set.of(), new Object(), preds, 0L);

        try
        {
            snap.blockingActorTiles().add(new WorldPoint(0, 0, 0));
            fail("expected UnsupportedOperationException");
        }
        catch (UnsupportedOperationException expected)
        {
            // ok
        }
    }

    @Test
    public void blockingObjectTiles_isUnmodifiable()
    {
        CollisionView view = dummyView();
        WorldSnapshot snap = WorldSnapshotBuilder.fromComponents(
            view, Set.of(), Set.of(new WorldPoint(0, 0, 0)),
            new Object(), new PredicateRegistry(), 0L);

        try
        {
            snap.blockingObjectTiles().remove(new WorldPoint(0, 0, 0));
            fail("expected UnsupportedOperationException");
        }
        catch (UnsupportedOperationException expected)
        {
            // ok
        }
    }

    @Test
    public void build_returnsImmutable_externalActorMoveAfterBuildDoesNotChangeSnapshot()
    {
        CollisionView view = dummyView();
        Set<WorldPoint> mutable = new HashSet<>();
        mutable.add(new WorldPoint(3210, 3210, 0));

        WorldSnapshot snap = WorldSnapshotBuilder.fromComponents(
            view, mutable, Set.of(), new Object(), new PredicateRegistry(), 0L);

        int snapshotSize = snap.blockingActorTiles().size();
        // Mutate the source set after build.
        mutable.add(new WorldPoint(3300, 3300, 0));
        mutable.remove(new WorldPoint(3210, 3210, 0));

        assertEquals(snapshotSize, snap.blockingActorTiles().size());
        assertTrue(snap.blockingActorTiles().contains(new WorldPoint(3210, 3210, 0)));
    }

    @Test
    public void collisionAt_returnsCollisionFlags()
    {
        CollisionView view = dummyView();
        WorldSnapshot snap = WorldSnapshotBuilder.fromComponents(
            view, Set.of(), Set.of(), new Object(), new PredicateRegistry(), 0L);

        CollisionFlags cf = snap.collisionAt(new WorldPoint(3210, 3210, 0));
        assertNotNull(cf);
        assertEquals(view.flagsAt(new WorldPoint(3210, 3210, 0)), cf.bits());
        assertEquals(view.source(new WorldPoint(3210, 3210, 0)), cf.source());
    }

    @Test
    public void capturedAtMs_isStoredAndReturned()
    {
        WorldSnapshot a = WorldSnapshotBuilder.fromComponents(
            dummyView(), Set.of(), Set.of(), new Object(), new PredicateRegistry(), 100L);
        WorldSnapshot b = WorldSnapshotBuilder.fromComponents(
            dummyView(), Set.of(), Set.of(), new Object(), new PredicateRegistry(), 200L);
        assertEquals(100L, a.capturedAtMs());
        assertEquals(200L, b.capturedAtMs());
    }
}
