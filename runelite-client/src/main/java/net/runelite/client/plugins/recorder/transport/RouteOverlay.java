package net.runelite.client.plugins.recorder.transport;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Stroke;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Paints the in-progress route on the canvas: walk areas as filled
 * rectangles, single-tile walks as outlined tiles, transports as
 * outlined object hulls (or a tile poly when the object isn't loaded).
 * Each waypoint is labelled with its name (when present) and index.
 *
 * <p>Reads from a swappable {@link AtomicReference} so the panel can
 * push updated route lists without locking.
 */
public final class RouteOverlay extends Overlay
{
    private static final Color AREA_FILL = new Color(80, 180, 255, 60);
    private static final Color AREA_LINE = new Color(80, 180, 255, 220);
    private static final Color WALK_LINE = new Color(120, 200, 255, 220);
    private static final Color TRANSPORT_LINE = new Color(255, 180, 80, 230);
    private static final BasicStroke STROKE_2 = new BasicStroke(2f);
    private static final Color LABEL_BG = new Color(0, 0, 0, 180);

    private final Client client;
    private final AtomicReference<List<Waypoint>> route = new AtomicReference<>(List.of());

    public RouteOverlay(Client client)
    {
        this.client = client;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
        setPriority(Overlay.PRIORITY_LOW);
    }

    public void setRoute(List<Waypoint> waypoints)
    {
        route.set(waypoints == null ? List.of() : List.copyOf(waypoints));
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        WorldView wv = client.getTopLevelWorldView();
        if (wv == null) return null;
        List<Waypoint> wps = route.get();
        if (wps.isEmpty()) return null;
        Stroke prev = g.getStroke();
        for (int i = 0; i < wps.size(); i++)
        {
            Waypoint wp = wps.get(i);
            String label = (wp.name() == null ? "" : wp.name() + " ") + "#" + i;
            switch (wp.kind())
            {
                case WALK_AREA:
                    drawArea(g, wp.area(), label);
                    break;
                case WALK:
                    drawTile(g, wp.tile(), WALK_LINE, label);
                    break;
                case TRANSPORT:
                {
                    String verb = wp.verb();
                    drawTile(g, wp.tile(), TRANSPORT_LINE,
                        verb != null ? label + " (" + verb + ")" : label);
                    break;
                }
            }
        }
        g.setStroke(prev);
        return null;
    }

    private void drawArea(Graphics2D g, WorldArea area, String label)
    {
        Polygon outline = areaPolygon(area);
        if (outline == null) return;
        g.setColor(AREA_FILL);
        g.fillPolygon(outline);
        g.setStroke(STROKE_2);
        g.setColor(AREA_LINE);
        g.drawPolygon(outline);
        // Label at the rough centroid of the SW tile.
        WorldPoint anchor = new WorldPoint(area.getX(), area.getY(), area.getPlane());
        labelAt(g, anchor, label);
    }

    private void drawTile(Graphics2D g, WorldPoint wp, Color colour, String label)
    {
        Polygon poly = tilePolygon(wp);
        if (poly == null) return;
        g.setStroke(STROKE_2);
        g.setColor(colour);
        g.drawPolygon(poly);
        labelAt(g, wp, label);
    }

    private Polygon tilePolygon(WorldPoint wp)
    {
        WorldView wv = client.getTopLevelWorldView();
        if (wv == null) return null;
        LocalPoint lp = LocalPoint.fromWorld(wv, wp);
        if (lp == null) return null;
        return Perspective.getCanvasTilePoly(client, lp);
    }

    private Polygon areaPolygon(WorldArea area)
    {
        WorldView wv = client.getTopLevelWorldView();
        if (wv == null) return null;
        int x1 = area.getX();
        int y1 = area.getY();
        int x2 = area.getX() + area.getWidth() - 1;
        int y2 = area.getY() + area.getHeight() - 1;
        int plane = area.getPlane();
        Polygon poly = new Polygon();
        // Walk the perimeter — bottom edge L→R, right edge B→T, top edge R→L,
        // left edge T→B — collecting one canvas point per tile-corner.
        addPerimeterPoint(poly, wv, x1, y1, plane, false, false);
        for (int x = x1; x <= x2; x++) addPerimeterPoint(poly, wv, x, y1, plane, true, false);
        for (int y = y1; y <= y2; y++) addPerimeterPoint(poly, wv, x2, y, plane, true, true);
        for (int x = x2; x >= x1; x--) addPerimeterPoint(poly, wv, x, y2, plane, false, true);
        for (int y = y2; y > y1; y--) addPerimeterPoint(poly, wv, x1, y, plane, false, false);
        return poly.npoints == 0 ? null : poly;
    }

    private void addPerimeterPoint(Polygon poly, WorldView wv, int x, int y, int plane,
                                   boolean east, boolean north)
    {
        LocalPoint lp = LocalPoint.fromWorld(wv,
            new WorldPoint(x + (east ? 1 : 0), y + (north ? 1 : 0), plane));
        if (lp == null) return;
        net.runelite.api.Point p = Perspective.localToCanvas(client, lp, plane);
        if (p == null) return;
        poly.addPoint(p.getX(), p.getY());
    }

    private void labelAt(Graphics2D g, WorldPoint wp, String label)
    {
        WorldView wv = client.getTopLevelWorldView();
        if (wv == null) return;
        LocalPoint lp = LocalPoint.fromWorld(wv, wp);
        if (lp == null) return;
        net.runelite.api.Point pt = Perspective.localToCanvas(client, lp, wp.getPlane());
        if (pt == null) return;
        g.setColor(LABEL_BG);
        g.fillRect(pt.getX() - 1, pt.getY() - 12, g.getFontMetrics().stringWidth(label) + 4, 14);
        g.setColor(Color.WHITE);
        g.drawString(label, pt.getX() + 1, pt.getY() - 1);
    }
}
