package net.runelite.client.plugins.recorder.transport;

import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class WaypointTest
{
    @Test
    public void walkAreaCarriesAreaAndOptionalName()
    {
        WorldArea a = new WorldArea(3091, 3243, 7, 5, 2);
        Waypoint w = Waypoint.walkArea("lumbridge_bank", a);
        assertEquals(Waypoint.Kind.WALK_AREA, w.kind());
        // area() returns the bounding box (computed from tile set); verify fields.
        WorldArea bbox = w.area();
        assertNotNull(bbox);
        assertEquals(a.getX(), bbox.getX());
        assertEquals(a.getY(), bbox.getY());
        assertEquals(a.getWidth(), bbox.getWidth());
        assertEquals(a.getHeight(), bbox.getHeight());
        assertEquals(a.getPlane(), bbox.getPlane());
        assertEquals("lumbridge_bank", w.name());
    }

    @Test
    public void walkSingleTileExposesAreaAsOneByOne()
    {
        Waypoint w = Waypoint.walk(new WorldPoint(3208, 3220, 2));
        assertEquals(Waypoint.Kind.WALK, w.kind());
        // Single-tile walk still exposes a 1x1 area for unified consumers.
        WorldArea a = w.area();
        assertNotNull(a);
        assertEquals(1, a.getWidth());
        assertEquals(1, a.getHeight());
        assertEquals(3208, a.getX());
        assertEquals(3220, a.getY());
        assertEquals(2, a.getPlane());
    }

    @Test
    public void transportRetainsTileAndVerb()
    {
        Waypoint w = Waypoint.transport(
            new WorldPoint(3239, 3295, 0),
            Waypoint.TransportKind.OPEN, "Open");
        assertEquals(Waypoint.Kind.TRANSPORT, w.kind());
        assertEquals(3239, w.tile().getX());
        assertEquals("Open", w.verb());
        // Transport waypoints have no name unless caller supplied one.
        assertNull(w.name());
    }

    @Test
    public void namedTransportFactoryAttachesName()
    {
        Waypoint w = Waypoint.transportNamed(
            "pen_gate",
            new WorldPoint(3239, 3295, 0),
            Waypoint.TransportKind.OPEN, "Open");
        assertEquals("pen_gate", w.name());
        assertEquals("Open", w.verb());
    }
}
