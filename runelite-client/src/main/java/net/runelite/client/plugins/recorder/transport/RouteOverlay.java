package net.runelite.client.plugins.recorder.transport;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Stroke;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Paints route waypoints on the canvas. Each WALK / WALK_AREA waypoint
 * draws every tile in its set with a translucent fill + thin outline
 * (per tile — so irregular shapes render correctly, and the legacy
 * perimeter-walk bug — drawing across half-tile-offset corners — goes
 * away). Transports draw their tile poly + verb label.
 *
 * <p>The currently-selected waypoint (set via {@link #setSelected})
 * draws with a thicker cyan outline and higher fill alpha so the user
 * can spot it among the route.
 */
public final class RouteOverlay extends Overlay
{
    private static final Color AREA_FILL = new Color(80, 180, 255, 60);
    private static final Color AREA_LINE = new Color(80, 180, 255, 220);
    private static final Color WALK_LINE = new Color(120, 200, 255, 220);
    private static final Color TRANSPORT_LINE = new Color(255, 180, 80, 230);
    private static final Color SELECTED_FILL = new Color(80, 220, 255, 110);
    private static final Color SELECTED_LINE = new Color(80, 220, 255, 255);
    private static final Color LABEL_BG = new Color(0, 0, 0, 180);
    private static final BasicStroke STROKE_2 = new BasicStroke(2f);
    private static final BasicStroke STROKE_3 = new BasicStroke(3f);

    private final Client client;
    private final AtomicReference<List<Waypoint>> route = new AtomicReference<>(List.of());
    private final AtomicReference<Waypoint> selected = new AtomicReference<>();

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

    /** Mark a waypoint as selected so it renders with stronger emphasis.
     *  Pass {@code null} to clear. The waypoint is matched by reference
     *  identity — pass the same instance held in the panel's list. */
    public void setSelected(@Nullable Waypoint wp)
    {
        selected.set(wp);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        WorldView wv = client.getTopLevelWorldView();
        if (wv == null) return null;
        List<Waypoint> wps = route.get();
        if (wps.isEmpty()) return null;
        Waypoint sel = selected.get();
        Stroke prev = g.getStroke();
        for (int i = 0; i < wps.size(); i++)
        {
            Waypoint wp = wps.get(i);
            boolean isSel = wp == sel;
            String label = (isSel ? "▶ " : "")
                + (wp.name() == null ? "" : wp.name() + " ") + "#" + i;
            switch (wp.kind())
            {
                case WALK_AREA:
                case WALK:
                    drawTileSet(g, wp.tiles(), isSel, label);
                    break;
                case TRANSPORT:
                {
                    String verb = wp.verb();
                    drawTransport(g, wp.tile(), isSel,
                        verb != null ? label + " (" + verb + ")" : label);
                    break;
                }
            }
        }
        g.setStroke(prev);
        return null;
    }

    private void drawTileSet(Graphics2D g, java.util.Set<WorldPoint> tiles,
                             boolean selected, String label)
    {
        WorldView wv = client.getTopLevelWorldView();
        if (wv == null) return;
        Color fill = selected ? SELECTED_FILL : AREA_FILL;
        Color line = selected ? SELECTED_LINE : AREA_LINE;
        BasicStroke stroke = selected ? STROKE_3 : STROKE_2;
        WorldPoint labelAnchor = null;
        for (WorldPoint wp : tiles)
        {
            LocalPoint lp = LocalPoint.fromWorld(wv, wp);
            if (lp == null) continue;
            Polygon poly = Perspective.getCanvasTilePoly(client, lp);
            if (poly == null) continue;
            g.setColor(fill);
            g.fillPolygon(poly);
            g.setStroke(stroke);
            g.setColor(line);
            g.drawPolygon(poly);
            // Label on the SW-most tile we successfully projected.
            if (labelAnchor == null
                || wp.getX() < labelAnchor.getX()
                || (wp.getX() == labelAnchor.getX() && wp.getY() < labelAnchor.getY()))
            {
                labelAnchor = wp;
            }
        }
        if (labelAnchor != null) labelAt(g, labelAnchor, label);
    }

    private void drawTransport(Graphics2D g, WorldPoint wp, boolean selected, String label)
    {
        WorldView wv = client.getTopLevelWorldView();
        if (wv == null) return;
        LocalPoint lp = LocalPoint.fromWorld(wv, wp);
        if (lp == null) return;
        Polygon poly = Perspective.getCanvasTilePoly(client, lp);
        if (poly == null) return;
        BasicStroke stroke = selected ? STROKE_3 : STROKE_2;
        Color line = selected ? SELECTED_LINE : TRANSPORT_LINE;
        g.setStroke(stroke);
        g.setColor(line);
        g.drawPolygon(poly);
        labelAt(g, wp, label);
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
