package net.runelite.client.sequence.dispatch;

import java.awt.Polygon;
import java.awt.Rectangle;
import java.lang.reflect.Method;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Verifies that the PixelResolver bypass fix from Phase 1 is
 *  effective: sampling sites that previously returned an unguarded
 *  centroid now return null when the centroid is in a dead-zone,
 *  and the rejection loops successfully avoid dead-zone pixels
 *  when they can.
 *
 *  <p>Drives the private samplers via reflection — same shape as
 *  {@link PixelResolverRepeatTest} so we don't add a new test
 *  surface beyond what the existing suite already exercises. */
public class PixelResolverDeadZoneTest
{
    /** Whole canvas is "dead-zone" via the chatbox widget bounds.
     *  Every sample inside the polygon must therefore be rejected. */
    private static Client clientWithFullCanvasChatbox()
    {
        Client c = mock(Client.class);
        Widget chatbox = mock(Widget.class);
        when(chatbox.isHidden()).thenReturn(false);
        when(chatbox.getParent()).thenReturn(null);
        // 1000x1000 — covers any polygon used below.
        when(chatbox.getBounds()).thenReturn(new Rectangle(0, 0, 1000, 1000));
        when(c.getWidget(InterfaceID.Chatbox.UNIVERSE)).thenReturn(chatbox);
        return c;
    }

    /** Chatbox covers only the lower-left quadrant of a 800x600
     *  canvas. Samples must end up in the upper-right; rejection
     *  loop budget should not starve for a 400x400 polygon. */
    private static Client clientWithQuadrantChatbox()
    {
        Client c = mock(Client.class);
        Widget chatbox = mock(Widget.class);
        when(chatbox.isHidden()).thenReturn(false);
        when(chatbox.getParent()).thenReturn(null);
        when(chatbox.getBounds()).thenReturn(new Rectangle(0, 300, 400, 300));
        when(c.getWidget(InterfaceID.Chatbox.UNIVERSE)).thenReturn(chatbox);
        return c;
    }

    @Test
    public void sampleInsidePolygonReturnsNullWhenEntirePolygonInDeadZone() throws Exception
    {
        // Polygon fully contained inside the chatbox dead-zone.
        // Pre-Phase-1 the bare-centroid fallback returned a Point
        // unconditionally — that's the bypass we closed.
        PixelResolver pr = new PixelResolver(clientWithFullCanvasChatbox());
        Polygon hull = new Polygon(
            new int[]{100, 340, 340, 100},
            new int[]{100, 100, 340, 340},
            4);

        Method m = PixelResolver.class.getDeclaredMethod("sampleInsidePolygon", Polygon.class);
        m.setAccessible(true);
        Point p = (Point) m.invoke(pr, hull);
        assertNull("centroid bypass must NOT leak when polygon is fully dead-zoned", p);
    }

    @Test
    public void sampleNearCentroidReturnsCleanPixelWhenPolygonStraddles() throws Exception
    {
        // Polygon straddling the chatbox boundary. The sampler
        // should find an upper-right pixel and return it; if it
        // ever returns a lower-left (dead-zone) pixel, the bypass
        // is still leaking.
        PixelResolver pr = new PixelResolver(clientWithQuadrantChatbox());
        // 200x200 polygon centred at the canvas centre (400,300).
        // The polygon bbox is (300,200,500,400); the chatbox covers
        // y >= 300, so the upper half of the polygon is clear.
        Polygon hull = new Polygon(
            new int[]{300, 500, 500, 300},
            new int[]{200, 200, 400, 400},
            4);

        Method m = PixelResolver.class.getDeclaredMethod("sampleNearCentroid", Polygon.class);
        m.setAccessible(true);

        // Chatbox is (0,300,400,300) — covers x in [0,400) AND y in [300,600).
        // The valid clean region of the polygon is everything outside that
        // rectangle (i.e., y < 300 OR x >= 400). Drive it several times —
        // random sampling should produce a clean pixel on every run.
        Rectangle chatbox = new Rectangle(0, 300, 400, 300);
        for (int i = 0; i < 16; i++)
        {
            Point p = (Point) m.invoke(pr, hull);
            assertNotNull("expected a non-null sample on iter " + i, p);
            assertFalse("sample (" + p.getX() + "," + p.getY()
                    + ") must NOT land inside the chatbox dead-zone on iter " + i,
                chatbox.contains(p.getX(), p.getY()));
        }
    }

    @Test
    public void sampleNearCentroidFallsThroughToNullWhenEveryPathIsBlocked() throws Exception
    {
        // Same as test #1 but driving the higher-level entry. The
        // centroid is dead-zoned, so the centroid fallback must
        // chain to sampleInsidePolygon, which itself returns null
        // because every interior pixel is dead-zoned.
        PixelResolver pr = new PixelResolver(clientWithFullCanvasChatbox());
        Polygon hull = new Polygon(
            new int[]{100, 340, 340, 100},
            new int[]{100, 100, 340, 340},
            4);

        Method m = PixelResolver.class.getDeclaredMethod("sampleNearCentroid", Polygon.class);
        m.setAccessible(true);
        Point p = (Point) m.invoke(pr, hull);
        assertNull("centroid chain must NOT leak a dead-zone pixel", p);
    }

    @Test
    public void resolveWidgetIsNotAffectedByWorldDeadZones() throws Exception
    {
        // UI-intent resolution still works inside chatbox bounds —
        // resolveWidget intentionally does NOT consult inWorldDeadZone
        // (a chat-tab click IS supposed to be inside the chatbox).
        Client c = clientWithFullCanvasChatbox();
        // Add a small widget inside the dead-zone for resolveWidget
        // to target.
        Widget target = mock(Widget.class);
        when(target.isHidden()).thenReturn(false);
        when(target.getParent()).thenReturn(null);
        when(target.getBounds()).thenReturn(new Rectangle(100, 500, 60, 20));
        when(c.getWidget(12345)).thenReturn(target);

        PixelResolver pr = new PixelResolver(c);
        Point p = pr.resolveWidget(12345);
        assertNotNull("UI-intent resolveWidget must NOT be blocked by world dead-zones", p);
        assertTrue("widget pixel must land inside the target widget bounds",
            p.getX() >= 100 && p.getX() < 160
                && p.getY() >= 500 && p.getY() < 520);
    }
}
