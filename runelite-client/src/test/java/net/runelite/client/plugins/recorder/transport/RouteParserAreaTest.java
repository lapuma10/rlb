package net.runelite.client.plugins.recorder.transport;

import org.junit.Test;
import static org.junit.Assert.*;

public class RouteParserAreaTest
{
    @Test
    public void parsesWalkbox()
    {
        Waypoint w = RouteParser.parseLine("walkbox:3091,3243 - 3097,3247,2");
        assertEquals(Waypoint.Kind.WALK_AREA, w.kind());
        assertEquals(3091, w.area().getX());
        assertEquals(3243, w.area().getY());
        assertEquals(7, w.area().getWidth());   // 3097 - 3091 + 1
        assertEquals(5, w.area().getHeight());  // 3247 - 3243 + 1
        assertEquals(2, w.area().getPlane());
        assertNull(w.name());
    }

    @Test
    public void parsesNamedWalkbox()
    {
        Waypoint w = RouteParser.parseLine(
            "lumbridge_bank: walkbox:3091,3243 - 3097,3247,2");
        assertEquals("lumbridge_bank", w.name());
        assertEquals(Waypoint.Kind.WALK_AREA, w.kind());
        assertEquals(7, w.area().getWidth());
    }

    @Test
    public void parsesNamedTransport()
    {
        Waypoint w = RouteParser.parseLine("pen_gate: open:3239,3295,0");
        assertEquals("pen_gate", w.name());
        assertEquals(Waypoint.Kind.TRANSPORT, w.kind());
        assertEquals(Waypoint.TransportKind.OPEN, w.transportKind());
        assertEquals("Open", w.verb());
    }

    @Test
    public void parsesNamedPlainTile()
    {
        Waypoint w = RouteParser.parseLine("waypoint_1: 3208,3220,2");
        assertEquals("waypoint_1", w.name());
        assertEquals(Waypoint.Kind.WALK, w.kind());
        assertEquals(3208, w.tile().getX());
    }

    @Test
    public void walkboxRejectsBadCorners()
    {
        try
        {
            RouteParser.parseLine("walkbox:3097,3247 - 3091,3243,2");
            fail("expected IllegalArgumentException for ne < sw");
        }
        catch (IllegalArgumentException ex)
        {
            assertTrue(ex.getMessage(), ex.getMessage().toLowerCase().contains("corner"));
        }
    }

    @Test
    public void planeMustMatchOnSingleLine()
    {
        Waypoint w = RouteParser.parseLine("walkbox:3091,3243 - 3097,3247,0");
        assertEquals(0, w.area().getPlane());
    }

    @Test
    public void inlineHashCommentIsStripped()
    {
        Waypoint w = RouteParser.parseLine("open:3239,3295,0  # objId=1551 verb=Open");
        assertEquals(Waypoint.Kind.TRANSPORT, w.kind());
        assertEquals(Waypoint.TransportKind.OPEN, w.transportKind());
        assertEquals(3239, w.tile().getX());
        assertEquals("Open", w.verb());
        assertNull(w.name());
    }

    @Test
    public void inlineHashCommentStrippedFromInteractLine()
    {
        Waypoint w = RouteParser.parseLine("interact:3239,3295,0:Squeeze-through  # objId=42");
        assertEquals(Waypoint.Kind.TRANSPORT, w.kind());
        assertEquals(Waypoint.TransportKind.INTERACT, w.transportKind());
        assertEquals("Squeeze-through", w.verb());
    }

    @Test
    public void inlineHashCommentStrippedFromNamedLine()
    {
        Waypoint w = RouteParser.parseLine("pen_gate: open:3239,3295,0  # objId=1551 verb=Open");
        assertEquals("pen_gate", w.name());
        assertEquals(Waypoint.Kind.TRANSPORT, w.kind());
        assertEquals(Waypoint.TransportKind.OPEN, w.transportKind());
        assertEquals("Open", w.verb());
    }
}
