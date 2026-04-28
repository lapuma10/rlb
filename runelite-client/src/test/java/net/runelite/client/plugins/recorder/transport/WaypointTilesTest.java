package net.runelite.client.plugins.recorder.transport;

import java.util.HashSet;
import java.util.Set;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class WaypointTilesTest
{
    @Test
    public void walkAreaFromTileSetExposesTheSet()
    {
        Set<WorldPoint> tiles = Set.of(
            new WorldPoint(3091, 3243, 2),
            new WorldPoint(3092, 3243, 2),
            new WorldPoint(3091, 3244, 2));
        Waypoint w = Waypoint.walkArea("lumbridge_bank", tiles);
        assertEquals(Waypoint.Kind.WALK_AREA, w.kind());
        assertEquals(tiles, w.tiles());
    }

    @Test
    public void walkAreaBboxIsTheBoundingRectangle()
    {
        Set<WorldPoint> tiles = Set.of(
            new WorldPoint(3091, 3243, 2),
            new WorldPoint(3093, 3245, 2),
            new WorldPoint(3092, 3244, 2));
        Waypoint w = Waypoint.walkArea(null, tiles);
        WorldArea bbox = w.area();
        assertNotNull(bbox);
        assertEquals(3091, bbox.getX());
        assertEquals(3243, bbox.getY());
        assertEquals(3, bbox.getWidth());   // 3093-3091+1
        assertEquals(3, bbox.getHeight());  // 3245-3243+1
        assertEquals(2, bbox.getPlane());
    }

    @Test
    public void walkAreaFromRectangleFillsEveryTile()
    {
        WorldArea rect = new WorldArea(3091, 3243, 3, 2, 2); // 3x2 = 6 tiles
        Waypoint w = Waypoint.walkArea("rect", rect);
        assertEquals(Waypoint.Kind.WALK_AREA, w.kind());
        assertEquals(6, w.tiles().size());
        assertTrue(w.tiles().contains(new WorldPoint(3091, 3243, 2)));
        assertTrue(w.tiles().contains(new WorldPoint(3093, 3244, 2)));
    }

    @Test
    public void walkSingleTileTilesReturnsSingletonSet()
    {
        WorldPoint p = new WorldPoint(3208, 3220, 0);
        Waypoint w = Waypoint.walk(p);
        assertEquals(Set.of(p), w.tiles());
    }

    @Test
    public void transportTilesIsEmptySet()
    {
        Waypoint w = Waypoint.transport(new WorldPoint(3239, 3295, 0),
            Waypoint.TransportKind.OPEN, "Open");
        assertTrue(w.tiles().isEmpty());
    }

    @Test
    public void walkAreaRejectsEmptyTileSet()
    {
        try
        {
            Waypoint.walkArea(null, new HashSet<>());
            fail("expected IllegalArgumentException");
        }
        catch (IllegalArgumentException ex)
        {
            assertTrue(ex.getMessage(), ex.getMessage().toLowerCase().contains("empty"));
        }
    }

    @Test
    public void walkAreaRejectsMultiPlaneTileSet()
    {
        Set<WorldPoint> tiles = Set.of(
            new WorldPoint(3091, 3243, 0),
            new WorldPoint(3092, 3243, 1));
        try
        {
            Waypoint.walkArea(null, tiles);
            fail("expected IllegalArgumentException");
        }
        catch (IllegalArgumentException ex)
        {
            assertTrue(ex.getMessage(), ex.getMessage().toLowerCase().contains("plane"));
        }
    }
}
