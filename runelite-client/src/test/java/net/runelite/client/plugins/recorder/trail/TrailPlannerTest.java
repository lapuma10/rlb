package net.runelite.client.plugins.recorder.trail;

import java.util.List;
import java.util.Optional;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class TrailPlannerTest
{
    @Test
    public void planSingleTrailWalksEveryTile()
    {
        Trail t = new Trail("straight", 0L, List.of(
            new TrailEvent.Tile(0L, new WorldPoint(0, 0, 0)),
            new TrailEvent.Tile(1L, new WorldPoint(0, 1, 0)),
            new TrailEvent.Tile(2L, new WorldPoint(0, 2, 0))));
        TrailGraph g = TrailGraph.build(List.of(t));
        TrailPlanner pl = new TrailPlanner(g);
        Optional<TrailPath> p = pl.plan(new WorldPoint(0, 0, 0), new WorldPoint(0, 2, 0));
        assertTrue(p.isPresent());
        assertEquals(1, p.get().legs().size());
        Leg.Walk w = (Leg.Walk) p.get().legs().get(0);
        assertEquals(3, w.tiles().size());
        assertEquals(new WorldPoint(0, 0, 0), w.tiles().get(0));
        assertEquals(new WorldPoint(0, 2, 0), w.tiles().get(2));
    }

    @Test
    public void planEmitsTransportLegBetweenWalks()
    {
        Trail t = new Trail("ladder", 0L, List.of(
            new TrailEvent.Tile(0L, new WorldPoint(0, 0, 0)),
            new TrailEvent.Tile(1L, new WorldPoint(1, 0, 0)),
            new TrailEvent.Transport(2L, new WorldPoint(1, 0, 0),
                "Climb-up", "Ladder", 9999, "GameObject", 3, 5, 6, List.of()),
            new TrailEvent.Tile(3L, new WorldPoint(1, 0, 1)),
            new TrailEvent.Tile(4L, new WorldPoint(2, 0, 1))));
        TrailGraph g = TrailGraph.build(List.of(t));
        TrailPlanner pl = new TrailPlanner(g);
        Optional<TrailPath> p = pl.plan(new WorldPoint(0, 0, 0), new WorldPoint(2, 0, 1));
        assertTrue(p.isPresent());
        List<Leg> legs = p.get().legs();
        assertEquals(3, legs.size());
        assertTrue("first leg WALK", legs.get(0) instanceof Leg.Walk);
        assertTrue("middle leg TRANSPORT", legs.get(1) instanceof Leg.Transport);
        assertTrue("last leg WALK", legs.get(2) instanceof Leg.Walk);
        Leg.Transport tr = (Leg.Transport) legs.get(1);
        assertEquals("Climb-up", tr.verb());
        assertEquals(9999, tr.objectId());
    }

    @Test
    public void planAcrossJunctionOfTwoTrails()
    {
        // Trail A: (0,0)->(1,0)->(2,0) on plane 0.
        // Trail B: (2,0)->(3,0)->(4,0) on plane 0.
        // Shared tile (2,0) — cost-0 junction. Plan (0,0)->(4,0) returns one
        // coalesced walk leg covering both trails.
        Trail a = new Trail("A", 0L, List.of(
            new TrailEvent.Tile(0L, new WorldPoint(0, 0, 0)),
            new TrailEvent.Tile(1L, new WorldPoint(1, 0, 0)),
            new TrailEvent.Tile(2L, new WorldPoint(2, 0, 0))));
        Trail b = new Trail("B", 0L, List.of(
            new TrailEvent.Tile(0L, new WorldPoint(2, 0, 0)),
            new TrailEvent.Tile(1L, new WorldPoint(3, 0, 0)),
            new TrailEvent.Tile(2L, new WorldPoint(4, 0, 0))));
        TrailGraph g = TrailGraph.build(List.of(a, b));
        TrailPlanner pl = new TrailPlanner(g);
        Optional<TrailPath> p = pl.plan(new WorldPoint(0, 0, 0), new WorldPoint(4, 0, 0));
        assertTrue(p.isPresent());
        // Coalesced into one walk.
        assertEquals(1, p.get().legs().size());
        Leg.Walk w = (Leg.Walk) p.get().legs().get(0);
        // Path visits all 5 tiles in order (the shared (2,0) tile dedupes).
        assertEquals(5, w.tiles().size());
        assertEquals(new WorldPoint(0, 0, 0), w.tiles().get(0));
        assertEquals(new WorldPoint(4, 0, 0), w.tiles().get(4));
    }

    @Test
    public void planReturnsEmptyWhenTargetOffGraph()
    {
        Trail t = new Trail("a", 0L, List.of(
            new TrailEvent.Tile(0L, new WorldPoint(0, 0, 0))));
        TrailGraph g = TrailGraph.build(List.of(t));
        TrailPlanner pl = new TrailPlanner(g);
        Optional<TrailPath> p = pl.plan(new WorldPoint(0, 0, 0), new WorldPoint(999, 999, 0));
        assertFalse(p.isPresent());
    }
}
