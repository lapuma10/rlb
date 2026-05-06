package net.runelite.client.plugins.recorder.worldmap;

import java.util.Optional;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.walker.PathSpec;
import org.junit.Test;
import static org.junit.Assert.*;

public class MapPlannerTest
{
    @Test
    public void findInteractTile_emptyChunk_returnsEmpty()
    {
        MapStore store = new MapStore(new WorldMemoryConfig());
        MapPlanner planner = new MapPlanner(store, new WorldMemoryConfig());
        Optional<WorldPoint> result = planner.findInteractTile(
            new WorldPoint(3208, 3213, 0),
            new WorldPoint(3209, 3214, 0),
            2, true);
        assertFalse(result.isPresent());
    }

    @Test
    public void findInteractTile_simpleOpenArea_returnsClosestLosTile()
    {
        // 5×5 open area, target at center, range R=2, requireLOS=true.
        MapStore store = new MapStore(new WorldMemoryConfig());
        RegionChunkBuilder b = store.builderFor(0);
        for (int x = 0; x < 5; x++)
            for (int y = 0; y < 5; y++)
                b.setTile(x, y, 0, 0);
        store.publish(0, b);

        MapPlanner planner = new MapPlanner(store, new WorldMemoryConfig());
        WorldPoint target = new WorldPoint(2, 2, 0);
        Optional<WorldPoint> result = planner.findInteractTile(
            new WorldPoint(0, 0, 0), target, 2, true);
        assertTrue(result.isPresent());
        // Picked tile must be within Chebyshev R of the target (the spec's contract).
        WorldPoint t = result.get();
        int chebyshev = Math.max(Math.abs(t.getX() - target.getX()),
                                 Math.abs(t.getY() - target.getY()));
        assertTrue("expected Chebyshev <= 2, got " + chebyshev, chebyshev <= 2);
    }

    @Test
    public void planToInteractTile_outputsOneByOneWalkArea()
    {
        MapStore store = new MapStore(new WorldMemoryConfig());
        RegionChunkBuilder b = store.builderFor(0);
        for (int x = 0; x < 5; x++)
            for (int y = 0; y < 5; y++)
                b.setTile(x, y, 0, 0);
        store.publish(0, b);

        MapPlanner planner = new MapPlanner(store, new WorldMemoryConfig());
        Optional<PathSpec> spec = planner.planToInteractTile(
            new WorldPoint(0, 0, 0),
            new WorldPoint(2, 2, 0),
            2, true, "test-spec");

        assertTrue(spec.isPresent());
        assertEquals(1, spec.get().waypoints().size());
        // Verify it's a WALK_AREA, 1×1.
        var wp = spec.get().waypoints().get(0);
        assertEquals(1, wp.area().getWidth());
        assertEquals(1, wp.area().getHeight());
    }
}
