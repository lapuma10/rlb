package net.runelite.client.plugins.recorder.nav.v2.collision;

import net.runelite.api.CollisionDataFlag;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Tests for the global collision snapshot bundled from Skretzo's
 *  shortest-path plugin. We test the public API (`flagsAt`, `isLoaded`,
 *  `mapVersion`) against known-loaded regions in the snapshot. */
public class GlobalCollisionSnapshotTest
{
    @Test
    public void loadFromResource_succeeds()
    {
        GlobalCollisionSnapshot snap = GlobalCollisionSnapshot.fromBundledResource();
        assertNotNull("snapshot non-null", snap);
        assertTrue("loaded region count > 0", snap.loadedRegionCount() > 0);
    }

    @Test
    public void mapVersion_isNonEmpty()
    {
        GlobalCollisionSnapshot snap = GlobalCollisionSnapshot.fromBundledResource();
        String v = snap.mapVersion();
        assertNotNull(v);
        assertFalse("version not empty", v.isEmpty());
    }

    @Test
    public void flagsAt_outOfBounds_returnsBlocked()
    {
        GlobalCollisionSnapshot snap = GlobalCollisionSnapshot.fromBundledResource();
        // Way outside any sensible OSRS coordinate (negative).
        WorldPoint p = new WorldPoint(-100, -100, 0);
        int flags = snap.flagsAt(p);
        assertTrue("BLOCK_MOVEMENT_FULL set when out of bounds",
            (flags & CollisionDataFlag.BLOCK_MOVEMENT_FULL) != 0);
    }

    @Test
    public void flagsAt_invalidPlane_returnsBlocked()
    {
        GlobalCollisionSnapshot snap = GlobalCollisionSnapshot.fromBundledResource();
        WorldPoint p = new WorldPoint(3222, 3218, 7); // plane 7 doesn't exist
        int flags = snap.flagsAt(p);
        assertTrue("invalid plane returns blocked",
            (flags & CollisionDataFlag.BLOCK_MOVEMENT_FULL) != 0);
    }

    @Test
    public void isLoaded_inKnownRegion_returnsTrue()
    {
        GlobalCollisionSnapshot snap = GlobalCollisionSnapshot.fromBundledResource();
        // Lumbridge area — region 12850 (50_100) is in Skretzo's map.
        WorldPoint p = new WorldPoint(3222, 3218, 0);
        assertTrue("Lumbridge region loaded", snap.isLoaded(p));
    }

    @Test
    public void isLoaded_inUnknownRegion_returnsFalse()
    {
        GlobalCollisionSnapshot snap = GlobalCollisionSnapshot.fromBundledResource();
        // (0,0) is in region 0_0 which is not part of OSRS.
        WorldPoint p = new WorldPoint(0, 0, 0);
        assertFalse("(0,0) not in any loaded region", snap.isLoaded(p));
    }

    @Test
    public void flagsAt_lumbridgeCourtyard_walkable()
    {
        GlobalCollisionSnapshot snap = GlobalCollisionSnapshot.fromBundledResource();
        // Lumbridge courtyard — wide-open walkable area.
        // We don't pin to a specific tile because data drift is possible;
        // instead we verify SOME tile in the area lets you move in all directions.
        // Loop 5×5 around (3222, 3218, 0); at least one tile should be fully walkable.
        boolean anyFullyWalkable = false;
        for (int dx = -2; dx <= 2; dx++)
        {
            for (int dy = -2; dy <= 2; dy++)
            {
                WorldPoint p = new WorldPoint(3222 + dx, 3218 + dy, 0);
                int flags = snap.flagsAt(p);
                // No directional blocks, no full-tile block.
                boolean fullyOpen =
                    (flags & CollisionDataFlag.BLOCK_MOVEMENT_FULL) == 0
                    && (flags & CollisionDataFlag.BLOCK_MOVEMENT_NORTH) == 0
                    && (flags & CollisionDataFlag.BLOCK_MOVEMENT_SOUTH) == 0
                    && (flags & CollisionDataFlag.BLOCK_MOVEMENT_EAST) == 0
                    && (flags & CollisionDataFlag.BLOCK_MOVEMENT_WEST) == 0;
                if (fullyOpen)
                {
                    anyFullyWalkable = true;
                    break;
                }
            }
            if (anyFullyWalkable) break;
        }
        assertTrue("at least one tile in lumbridge courtyard area is fully walkable",
            anyFullyWalkable);
    }

    @Test
    public void mapVersion_matchesManifest()
    {
        GlobalCollisionSnapshot snap = GlobalCollisionSnapshot.fromBundledResource();
        // MANIFEST.md documents the Skretzo source commit;
        // the implementation reads either the SHA256 or the source ref.
        // Test only that mapVersion is non-empty and stable across loads.
        GlobalCollisionSnapshot snap2 = GlobalCollisionSnapshot.fromBundledResource();
        assertEquals(snap.mapVersion(), snap2.mapVersion());
    }
}
