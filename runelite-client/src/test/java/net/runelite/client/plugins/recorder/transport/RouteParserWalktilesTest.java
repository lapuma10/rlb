package net.runelite.client.plugins.recorder.transport;

import java.util.Set;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class RouteParserWalktilesTest
{
    @Test
    public void parsesWalktilesLine()
    {
        Waypoint w = RouteParser.parseLine("walktiles:3091,3243;3092,3243;3093,3244,p=2");
        assertEquals(Waypoint.Kind.WALK_AREA, w.kind());
        Set<WorldPoint> expected = Set.of(
            new WorldPoint(3091, 3243, 2),
            new WorldPoint(3092, 3243, 2),
            new WorldPoint(3093, 3244, 2));
        assertEquals(expected, w.tiles());
    }

    @Test
    public void parsesWalktilesWithoutPPrefix()
    {
        // Plane in bare ",N" form (matches walkbox: convention).
        Waypoint w = RouteParser.parseLine("walktiles:3091,3243;3092,3243,2");
        assertEquals(2, w.area().getPlane());
        assertEquals(2, w.tiles().size());
    }

    @Test
    public void parsesNamedWalktiles()
    {
        Waypoint w = RouteParser.parseLine(
            "lumbridge_bank: walktiles:3091,3243;3092,3243,p=2");
        assertEquals("lumbridge_bank", w.name());
        assertEquals(Waypoint.Kind.WALK_AREA, w.kind());
        assertEquals(2, w.tiles().size());
    }

    @Test
    public void walktilesRoundTripsThroughToString()
    {
        Waypoint original = Waypoint.walkArea("pen", Set.of(
            new WorldPoint(3232, 3293, 0),
            new WorldPoint(3234, 3295, 0),
            new WorldPoint(3233, 3294, 0)));
        String serialised = original.toString();
        Waypoint reparsed = RouteParser.parseLine(serialised);
        assertEquals(original.tiles(), reparsed.tiles());
        assertEquals("pen", reparsed.name());
    }

    @Test
    public void walktilesRejectsMultiPlaneCoords()
    {
        // Even though the line declares plane=0, declaring tiles with their
        // own plane is forbidden — the format requires a single trailing plane.
        try
        {
            RouteParser.parseLine("walktiles:3091,3243,2;3092,3243,1,p=0");
            fail("expected IllegalArgumentException");
        }
        catch (IllegalArgumentException ex)
        {
            // Accept any error — the line is malformed.
        }
    }

    @Test
    public void walktilesRejectsBadCoord()
    {
        try
        {
            RouteParser.parseLine("walktiles:3091,foo;3092,3243,p=2");
            fail("expected IllegalArgumentException");
        }
        catch (IllegalArgumentException ex)
        {
            assertTrue(ex.getMessage(), ex.getMessage().toLowerCase().contains("non-numeric")
                || ex.getMessage().toLowerCase().contains("number"));
        }
    }

    @Test
    public void parsesSingleTileWalktilesWithoutPlane()
    {
        // No semicolons, no plane suffix — used to be misparsed as plane==y.
        Waypoint w = RouteParser.parseLine("walktiles:3091,3243");
        assertEquals(Waypoint.Kind.WALK_AREA, w.kind());
        assertEquals(Set.of(new WorldPoint(3091, 3243, 0)), w.tiles());
    }

    @Test
    public void walktilesRejectsEmptyBody()
    {
        try
        {
            RouteParser.parseLine("walktiles:");
            fail("expected IllegalArgumentException");
        }
        catch (IllegalArgumentException ex)
        {
            assertTrue(ex.getMessage(), ex.getMessage().toLowerCase().contains("missing")
                || ex.getMessage().toLowerCase().contains("empty")
                || ex.getMessage().toLowerCase().contains("no tiles"));
        }
    }

    @Test
    public void perfectRectangleRoundTripsAsWalkbox()
    {
        // 2x2 perfect rect built from a tile set should serialise as walkbox:
        // (not walktiles:) since isRectangular() is true.
        Waypoint original = Waypoint.walkArea("rect", Set.of(
            new WorldPoint(0, 0, 0),
            new WorldPoint(1, 0, 0),
            new WorldPoint(0, 1, 0),
            new WorldPoint(1, 1, 0)));
        String serialised = original.toString();
        assertTrue(serialised, serialised.contains("walkbox:"));
        assertFalse(serialised, serialised.contains("walktiles:"));

        Waypoint reparsed = RouteParser.parseLine(serialised);
        assertEquals(original.tiles(), reparsed.tiles());
    }
}
