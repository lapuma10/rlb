package net.runelite.client.plugins.recorder.worldmap;

import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class BresenhamLosTest
{
    @Test
    public void straightLine_noWalls_hasLineOfSight()
    {
        // Empty 3×3, no walls.
        RegionChunkBuilder b = new RegionChunkBuilder(0);
        for (int x = 0; x < 3; x++)
            for (int y = 0; y < 3; y++)
                b.setTile(x, y, 0, 0);
        RegionChunkSnapshot s = RegionChunkSnapshot.fromBuilder(b);

        assertTrue(Bresenham.hasLineOfSight(s,
            new WorldPoint(0, 0, 0), new WorldPoint(2, 2, 0)));
    }

    @Test
    public void wallBetween_blocksLineOfSight()
    {
        RegionChunkBuilder b = new RegionChunkBuilder(0);
        for (int x = 0; x < 3; x++)
            for (int y = 0; y < 3; y++)
                b.setTile(x, y, 0, 0);
        // Place an LOS-blocking flag on the middle tile.
        b.setTile(1, 1, 0, net.runelite.api.CollisionDataFlag.BLOCK_LINE_OF_SIGHT_FULL);
        RegionChunkSnapshot s = RegionChunkSnapshot.fromBuilder(b);

        assertFalse(Bresenham.hasLineOfSight(s,
            new WorldPoint(0, 0, 0), new WorldPoint(2, 2, 0)));
    }
}
