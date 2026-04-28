package net.runelite.client.plugins.recorder.walker;

import java.util.HashSet;
import java.util.Set;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.transport.Waypoint;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Verifies that the {@link PathSpec.Builder} builds the right
 * {@link Waypoint} kinds + verbs. Pure data-model assertions — no
 * dependency on the rest of the walker.
 */
public class PathSpecTest
{
    @Test
    public void walkAreaProducesWalkAreaKind()
    {
        PathSpec p = PathSpec.builder("test")
            .walk("yard", new WorldArea(3219, 3217, 9, 4, 0))
            .build();
        assertEquals(1, p.size());
        Waypoint w = p.waypoints().get(0);
        assertEquals(Waypoint.Kind.WALK_AREA, w.kind());
        assertEquals("yard", w.name());
        assertEquals(3219, w.area().getX());
    }

    @Test
    public void climbUpProducesClimbUpTransport()
    {
        PathSpec p = PathSpec.builder()
            .climbUp(new WorldPoint(3205, 3229, 1))
            .build();
        Waypoint w = p.waypoints().get(0);
        assertEquals(Waypoint.Kind.TRANSPORT, w.kind());
        assertEquals(Waypoint.TransportKind.CLIMB_UP, w.transportKind());
        assertEquals("Climb-up", w.verb());
        assertEquals(3205, w.tile().getX());
        assertEquals(1, w.tile().getPlane());
    }

    @Test
    public void climbDownProducesClimbDownTransport()
    {
        PathSpec p = PathSpec.builder()
            .climbDown(new WorldPoint(3205, 3229, 2))
            .build();
        Waypoint w = p.waypoints().get(0);
        assertEquals(Waypoint.Kind.TRANSPORT, w.kind());
        assertEquals(Waypoint.TransportKind.CLIMB_DOWN, w.transportKind());
        assertEquals("Climb-down", w.verb());
    }

    @Test
    public void gateProducesOpenTransport()
    {
        PathSpec p = PathSpec.builder()
            .gate(new WorldPoint(3243, 3236, 0))
            .build();
        Waypoint w = p.waypoints().get(0);
        assertEquals(Waypoint.Kind.TRANSPORT, w.kind());
        assertEquals(Waypoint.TransportKind.OPEN, w.transportKind());
        assertEquals("Open", w.verb());
    }

    @Test
    public void transportRequiresVerb()
    {
        PathSpec.Builder b = PathSpec.builder();
        try
        {
            b.transport(new WorldPoint(0, 0, 0), "");
            fail("expected IAE");
        }
        catch (IllegalArgumentException e)
        {
            // expected
        }
    }

    @Test
    public void walkTilesAcceptsIrregularSet()
    {
        Set<WorldPoint> tiles = new HashSet<>();
        tiles.add(new WorldPoint(0, 0, 0));
        tiles.add(new WorldPoint(0, 1, 0));
        tiles.add(new WorldPoint(2, 0, 0));
        PathSpec p = PathSpec.builder()
            .walkTiles("alcove", tiles)
            .build();
        Waypoint w = p.waypoints().get(0);
        assertEquals(Waypoint.Kind.WALK_AREA, w.kind());
        assertEquals(3, w.tiles().size());
        assertFalse(w.isRectangular()); // 3 tiles in a 3x2 bbox
    }

    @Test
    public void multiStepRouteIsBuiltInOrder()
    {
        PathSpec p = PathSpec.builder("lumbridge-out")
            .walk("yard", new WorldArea(3219, 3217, 9, 4, 0))
            .walk("bridge", new WorldArea(3237, 3225, 7, 2, 0))
            .gate(new WorldPoint(3243, 3236, 0))
            .walk("approach", new WorldArea(3238, 3289, 3, 8, 0))
            .build();
        assertEquals(4, p.size());
        assertEquals("yard", p.waypoints().get(0).name());
        assertEquals(Waypoint.Kind.TRANSPORT, p.waypoints().get(2).kind());
        assertEquals("approach", p.waypoints().get(3).name());
    }

    @Test
    public void ofWrapsExistingWaypointList()
    {
        PathSpec p = PathSpec.of("custom",
            java.util.List.of(
                Waypoint.walk(new WorldPoint(3200, 3200, 0)),
                Waypoint.walkArea("destination", new WorldArea(3210, 3210, 2, 2, 0))));
        assertEquals(2, p.size());
        assertEquals(Waypoint.Kind.WALK, p.waypoints().get(0).kind());
        assertEquals(Waypoint.Kind.WALK_AREA, p.waypoints().get(1).kind());
    }
}
