package net.runelite.client.plugins.recorder.worldmap;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import net.runelite.api.coords.WorldPoint;
import static org.junit.Assert.*;

public class MapAStarTest
{
    @Test
    public void straightShot_4tiles_reachableInDist4()
    {
        // 5×1 corridor, no walls.
        RegionChunkBuilder b = new RegionChunkBuilder(0);
        for (int x = 0; x < 5; x++) b.setTile(x, 0, 0, 0);
        RegionChunkSnapshot s = RegionChunkSnapshot.fromBuilder(b);

        WorldPoint origin = new WorldPoint(0, 0, 0);
        Set<WorldPoint> goals = Set.of(new WorldPoint(4, 0, 0));
        Map<WorldPoint, Integer> dist = MapAStar.dijkstraToAny(
            s, origin, goals, 128, 10_000);

        assertEquals(Integer.valueOf(4), dist.get(new WorldPoint(4, 0, 0)));
    }

    @Test
    public void unreachableGoal_returnsMinusOne()
    {
        // Two disconnected components separated by full-block tiles.
        // Origin in left, goal in right, no path.
        RegionChunkBuilder b = new RegionChunkBuilder(0);
        for (int x = 0; x < 5; x++) b.setTile(x, 0, 0, 0);
        // Wall the corridor at x=2.
        b.setTile(2, 0, 0, net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_FULL);
        RegionChunkSnapshot s = RegionChunkSnapshot.fromBuilder(b);

        WorldPoint origin = new WorldPoint(0, 0, 0);
        Set<WorldPoint> goals = Set.of(new WorldPoint(4, 0, 0));
        Map<WorldPoint, Integer> dist = MapAStar.dijkstraToAny(
            s, origin, goals, 128, 10_000);

        assertEquals(Integer.valueOf(-1), dist.get(new WorldPoint(4, 0, 0)));
    }

    @Test
    public void multiGoal_returnsDistsForAllGoals_inOnePass()
    {
        // 5×1 corridor.
        RegionChunkBuilder b = new RegionChunkBuilder(0);
        for (int x = 0; x < 5; x++) b.setTile(x, 0, 0, 0);
        RegionChunkSnapshot s = RegionChunkSnapshot.fromBuilder(b);

        WorldPoint origin = new WorldPoint(0, 0, 0);
        Set<WorldPoint> goals = Set.of(
            new WorldPoint(2, 0, 0),
            new WorldPoint(4, 0, 0));
        Map<WorldPoint, Integer> dist = MapAStar.dijkstraToAny(
            s, origin, goals, 128, 10_000);

        assertEquals(Integer.valueOf(2), dist.get(new WorldPoint(2, 0, 0)));
        assertEquals(Integer.valueOf(4), dist.get(new WorldPoint(4, 0, 0)));
    }
}
