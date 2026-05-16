package net.runelite.client.plugins.recorder.nav.v2.collision;

import net.runelite.api.CollisionData;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.Constants;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Tests for the live scene collision overlay. We construct a fake
 *  {@link CollisionData}[] with known flag arrays, build the overlay
 *  from it, and assert the {@code flagsAt} returns the live flags
 *  and is robust to source mutation after capture. */
public class LiveSceneCollisionOverlayTest
{
    /** Build a 104×104 fake CollisionData with all zeros. */
    private static CollisionData zeroPlane()
    {
        final int[][] flags = new int[Constants.SCENE_SIZE][Constants.SCENE_SIZE];
        return () -> flags;
    }

    /** Same, but with a single tile flagged. Returns the array so the test
     *  can mutate it later. */
    private static int[][] mutableFlags()
    {
        return new int[Constants.SCENE_SIZE][Constants.SCENE_SIZE];
    }

    @Test
    public void flagsAt_insideLoadedScene_returnsLiveFlags()
    {
        int[][] flags = mutableFlags();
        // Place a known flag at scene (10, 10) on plane 0.
        flags[10][10] = CollisionDataFlag.BLOCK_MOVEMENT_NORTH;
        CollisionData[] cd = { () -> flags, zeroPlane(), zeroPlane(), zeroPlane() };

        LiveSceneCollisionOverlay overlay = LiveSceneCollisionOverlay.capture(cd, 3200, 3200, 0);

        // (10,10) in scene = (3210, 3210) world.
        int got = overlay.flagsAt(new WorldPoint(3210, 3210, 0));
        assertEquals(CollisionDataFlag.BLOCK_MOVEMENT_NORTH, got);
    }

    @Test
    public void containsTile_insideLoadedScene_returnsTrue()
    {
        CollisionData[] cd = { zeroPlane(), zeroPlane(), zeroPlane(), zeroPlane() };
        LiveSceneCollisionOverlay overlay = LiveSceneCollisionOverlay.capture(cd, 3200, 3200, 0);
        assertTrue(overlay.containsTile(new WorldPoint(3210, 3210, 0)));
    }

    @Test
    public void containsTile_outsideLoadedScene_returnsFalse()
    {
        CollisionData[] cd = { zeroPlane(), zeroPlane(), zeroPlane(), zeroPlane() };
        LiveSceneCollisionOverlay overlay = LiveSceneCollisionOverlay.capture(cd, 3200, 3200, 0);
        // Scene is 3200..3303; (3500, 3200) is outside.
        assertFalse(overlay.containsTile(new WorldPoint(3500, 3200, 0)));
        // (3199, 3200) just before start, also outside.
        assertFalse(overlay.containsTile(new WorldPoint(3199, 3200, 0)));
    }

    @Test
    public void immutable_afterCapture_externalChangesIgnored()
    {
        int[][] flags = mutableFlags();
        flags[5][5] = CollisionDataFlag.BLOCK_MOVEMENT_EAST;
        CollisionData[] cd = { () -> flags, zeroPlane(), zeroPlane(), zeroPlane() };

        LiveSceneCollisionOverlay overlay = LiveSceneCollisionOverlay.capture(cd, 3200, 3200, 0);

        // Now mutate the source array AFTER capture.
        flags[5][5] = CollisionDataFlag.BLOCK_MOVEMENT_WEST;
        flags[6][6] = CollisionDataFlag.BLOCK_MOVEMENT_SOUTH;

        // Overlay reflects the captured state, not the mutated one.
        int got = overlay.flagsAt(new WorldPoint(3205, 3205, 0));
        assertEquals(CollisionDataFlag.BLOCK_MOVEMENT_EAST, got);
        int got2 = overlay.flagsAt(new WorldPoint(3206, 3206, 0));
        assertEquals(0, got2);
    }

    @Test
    public void planeMismatch_returnsNotContained()
    {
        // Only plane 0 is provided.
        CollisionData[] cd = { zeroPlane(), null, null, null };
        LiveSceneCollisionOverlay overlay = LiveSceneCollisionOverlay.capture(cd, 3200, 3200, 0);
        assertFalse(overlay.containsTile(new WorldPoint(3210, 3210, 2)));
    }

    @Test
    public void planeMismatch_flagsAt_returnsBlockedSentinel()
    {
        CollisionData[] cd = { zeroPlane(), null, null, null };
        LiveSceneCollisionOverlay overlay = LiveSceneCollisionOverlay.capture(cd, 3200, 3200, 0);
        int got = overlay.flagsAt(new WorldPoint(3210, 3210, 2));
        // Plane unavailable → return BLOCK_MOVEMENT_FULL as our "no data" sentinel.
        assertTrue((got & CollisionDataFlag.BLOCK_MOVEMENT_FULL) != 0);
    }

    @Test
    public void capture_nullCollisionMaps_yieldsEmptyOverlay()
    {
        LiveSceneCollisionOverlay overlay = LiveSceneCollisionOverlay.capture(null, 0, 0, 0);
        assertFalse(overlay.containsTile(new WorldPoint(0, 0, 0)));
    }

    @Test
    public void basePlane_recordedInOverlay()
    {
        CollisionData[] cd = { zeroPlane(), zeroPlane(), zeroPlane(), zeroPlane() };
        LiveSceneCollisionOverlay overlay = LiveSceneCollisionOverlay.capture(cd, 3200, 3200, 1);
        assertEquals(3200, overlay.baseX());
        assertEquals(3200, overlay.baseY());
        assertEquals(1, overlay.basePlane());
    }
}
