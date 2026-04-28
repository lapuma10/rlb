package net.runelite.client.plugins.recorder.transport;

import java.util.Set;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * End-to-end round-trip checks for {@link Waypoint#toString()} ↔ {@link RouteParser#parseLine(String)}.
 * Every Waypoint kind that the editor can save must reload identically, otherwise
 * the WaypointEditor save/load cycle silently corrupts data.
 */
public class RoundTripTest
{
    @Test
    public void walkRoundTrips()
    {
        Waypoint w = Waypoint.walk(new WorldPoint(3091, 3243, 2));
        Waypoint r = RouteParser.parseLine(w.toString());
        assertEquals(Waypoint.Kind.WALK, r.kind());
        assertEquals(w.tile(), r.tile());
        assertNull(r.name());
    }

    @Test
    public void walkNamedRoundTrips()
    {
        Waypoint w = Waypoint.walkNamed("start", new WorldPoint(3091, 3243, 0));
        Waypoint r = RouteParser.parseLine(w.toString());
        assertEquals("start", r.name());
        assertEquals(w.tile(), r.tile());
    }

    @Test
    public void walkAreaRectangularRoundTrips()
    {
        Waypoint w = Waypoint.walkArea("box", new WorldArea(3091, 3243, 3, 2, 0));
        Waypoint r = RouteParser.parseLine(w.toString());
        assertEquals(Waypoint.Kind.WALK_AREA, r.kind());
        assertEquals(w.tiles(), r.tiles());
        assertEquals("box", r.name());
    }

    @Test
    public void walkAreaIrregularRoundTrips()
    {
        Waypoint w = Waypoint.walkArea("pen", Set.of(
            new WorldPoint(3232, 3293, 0),
            new WorldPoint(3234, 3295, 0),
            new WorldPoint(3233, 3294, 0)));
        Waypoint r = RouteParser.parseLine(w.toString());
        assertEquals(Waypoint.Kind.WALK_AREA, r.kind());
        assertEquals(w.tiles(), r.tiles());
        assertEquals("pen", r.name());
    }

    @Test
    public void transportOpenRoundTrips()
    {
        Waypoint w = Waypoint.transport(new WorldPoint(3239, 3295, 0),
            Waypoint.TransportKind.OPEN, "Open");
        Waypoint r = RouteParser.parseLine(w.toString());
        assertEquals(Waypoint.Kind.TRANSPORT, r.kind());
        assertEquals(Waypoint.TransportKind.OPEN, r.transportKind());
        assertEquals(w.tile(), r.tile());
    }

    @Test
    public void transportClimbUpRoundTrips()
    {
        Waypoint w = Waypoint.transport(new WorldPoint(3208, 3220, 0),
            Waypoint.TransportKind.CLIMB_UP, "Climb-up");
        Waypoint r = RouteParser.parseLine(w.toString());
        assertEquals(Waypoint.TransportKind.CLIMB_UP, r.transportKind());
        assertEquals(w.tile(), r.tile());
    }

    @Test
    public void transportClimbDownRoundTrips()
    {
        Waypoint w = Waypoint.transport(new WorldPoint(3208, 3220, 1),
            Waypoint.TransportKind.CLIMB_DOWN, "Climb-down");
        Waypoint r = RouteParser.parseLine(w.toString());
        assertEquals(Waypoint.TransportKind.CLIMB_DOWN, r.transportKind());
        assertEquals(w.tile(), r.tile());
    }

    @Test
    public void transportInteractRoundTrips()
    {
        Waypoint w = Waypoint.transport(new WorldPoint(3232, 3293, 0),
            Waypoint.TransportKind.INTERACT, "Pick-lock");
        Waypoint r = RouteParser.parseLine(w.toString());
        assertEquals(Waypoint.TransportKind.INTERACT, r.transportKind());
        assertEquals("Pick-lock", r.verb());
        assertEquals(w.tile(), r.tile());
    }

    @Test
    public void transportNamedRoundTrips()
    {
        Waypoint w = Waypoint.transportNamed("pen_gate",
            new WorldPoint(3239, 3295, 0),
            Waypoint.TransportKind.OPEN, "Open");
        Waypoint r = RouteParser.parseLine(w.toString());
        assertEquals("pen_gate", r.name());
        assertEquals(Waypoint.TransportKind.OPEN, r.transportKind());
    }
}
