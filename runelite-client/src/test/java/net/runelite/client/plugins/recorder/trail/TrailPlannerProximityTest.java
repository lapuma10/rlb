package net.runelite.client.plugins.recorder.trail;

import java.util.List;
import java.util.Optional;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class TrailPlannerProximityTest
{
    @Test
    public void offGraphStartWithinOneTileSnapsToNearestNode()
    {
        Trail t = new Trail("a", 0L, List.of(
            new TrailEvent.Tile(0L, new WorldPoint(10, 10, 0)),
            new TrailEvent.Tile(1L, new WorldPoint(10, 11, 0))));
        TrailGraph g = TrailGraph.build(List.of(t));
        TrailPlanner pl = new TrailPlanner(g);
        // Player at (11,10) — 1 tile off-axis from (10,10). Target at (10,11).
        Optional<TrailPath> p = pl.plan(new WorldPoint(11, 10, 0), new WorldPoint(10, 11, 0));
        assertTrue(p.isPresent());
        Leg.Walk w = (Leg.Walk) p.get().legs().get(0);
        // First tile is the snap-to node.
        assertEquals(new WorldPoint(10, 10, 0), w.tiles().get(0));
    }

    @Test
    public void offGraphEndWithinOneTileSnapsToNearestNode()
    {
        Trail t = new Trail("a", 0L, List.of(
            new TrailEvent.Tile(0L, new WorldPoint(10, 10, 0)),
            new TrailEvent.Tile(1L, new WorldPoint(10, 11, 0))));
        TrailGraph g = TrailGraph.build(List.of(t));
        TrailPlanner pl = new TrailPlanner(g);
        // Target (11,11) — 1 tile from (10,11).
        Optional<TrailPath> p = pl.plan(new WorldPoint(10, 10, 0), new WorldPoint(11, 11, 0));
        assertTrue(p.isPresent());
        Leg.Walk w = (Leg.Walk) p.get().legs().get(0);
        // Last tile is (10,11) (graph node) — caller walks the final hop.
        assertEquals(new WorldPoint(10, 11, 0), w.tiles().get(w.tiles().size() - 1));
    }

    @Test
    public void offGraphEndpointBeyondSnapRadiusFails()
    {
        Trail t = new Trail("a", 0L, List.of(
            new TrailEvent.Tile(0L, new WorldPoint(10, 10, 0))));
        TrailGraph g = TrailGraph.build(List.of(t));
        TrailPlanner pl = new TrailPlanner(g);
        // SNAP_RADIUS = 32 — well outside it. No graph node within range
        // on the same plane.
        Optional<TrailPath> p = pl.plan(new WorldPoint(10, 10, 0), new WorldPoint(100, 100, 0));
        assertFalse(p.isPresent());
    }

    @Test
    public void offGraphEndpointAcrossPlaneFails()
    {
        Trail t = new Trail("a", 0L, List.of(
            new TrailEvent.Tile(0L, new WorldPoint(10, 10, 0))));
        TrailGraph g = TrailGraph.build(List.of(t));
        TrailPlanner pl = new TrailPlanner(g);
        // Same x/y but different plane — snap is plane-gated.
        Optional<TrailPath> p = pl.plan(new WorldPoint(10, 10, 0), new WorldPoint(10, 10, 1));
        assertFalse(p.isPresent());
    }
}
