package net.runelite.client.plugins.recorder.worldmap;

import java.util.Optional;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.walker.PathSpec;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.junit.Assert.assertNotEquals;

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

    @Test
    public void lumbridgeKitchenWallFixture_picksLosValidTile() throws Exception
    {
        RegionChunkSnapshot snap = MapStoreIO.readFixture("lumbridge-kitchen-wall.json");
        assertNotNull("fixture not found on classpath", snap);
        assertFalse("fixture loaded empty — resource missing?", snap.tiles().isEmpty());

        MapStore store = new MapStore(new WorldMemoryConfig());
        store.installSnapshotForTest(snap.regionId(), snap);

        MapPlanner planner = new MapPlanner(store, new WorldMemoryConfig());
        WorldPoint player = new WorldPoint(3208, 3215, 0);   // approach from south
        WorldPoint cookTarget = new WorldPoint(3211, 3214, 0);

        Optional<WorldPoint> stand = planner.findInteractTile(player, cookTarget, 2, true);

        assertTrue("planner must find a valid stand tile", stand.isPresent());
        assertTrue("chosen tile must have LOS to cookTarget",
            Bresenham.hasLineOfSight(snap, stand.get(), cookTarget));
        // (3210, 3214, 0) is the closest-by-Chebyshev tile to the west but is
        // a wall (BLOCK_MOVEMENT_FULL | BLOCK_LINE_OF_SIGHT_FULL) — must be rejected.
        assertNotEquals(new WorldPoint(3210, 3214, 0), stand.get());
    }
}
