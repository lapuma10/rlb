package net.runelite.client.plugins.recorder.transport;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Stroke;
import java.util.List;
import java.util.Set;
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
    // Annotator preview while AreaSelector is active — green = additive,
    // red = subtractive. Distinct from the saved-route blue so the user
    // can see the in-flight selection on top of any existing waypoints.
    private static final Color PREVIEW_ADD_FILL = new Color(120, 230, 120, 90);
    private static final Color PREVIEW_ADD_LINE = new Color(120, 230, 120, 230);
    private static final Color PREVIEW_SUB_FILL = new Color(230, 120, 120, 90);
    private static final Color PREVIEW_SUB_LINE = new Color(230, 120, 120, 230);
    private static final Color LABEL_BG = new Color(0, 0, 0, 180);
    private static final BasicStroke STROKE_2 = new BasicStroke(2f);
    private static final BasicStroke STROKE_3 = new BasicStroke(3f);

    private final Client client;
    private final AtomicReference<List<Waypoint>> route = new AtomicReference<>(List.of());
    private final AtomicReference<Waypoint> selected = new AtomicReference<>();
    private final AtomicReference<Set<WorldPoint>> previewTiles = new AtomicReference<>(Set.of());
    private final AtomicReference<Set<WorldPoint>> inflightRect = new AtomicReference<>(Set.of());
    private volatile boolean inflightSubtract;

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

    /** Replace the live-preview tile set rendered on top of the saved route.
     *  Used by the AreaSelector to show the in-progress working set. Pass
     *  {@code Set.of()} (empty) to clear. */
    public void setPreviewTiles(Set<WorldPoint> tiles)
    {
        previewTiles.set(tiles == null ? Set.of() : Set.copyOf(tiles));
    }

    /** Render a transient rectangle covering {@code tiles} while the user
     *  is mid-drag. {@code subtract} chooses the colour (red vs green).
     *  Pass {@code Set.of()} to clear (e.g. on release / cancel). */
    public void setInflightRect(Set<WorldPoint> tiles, boolean subtract)
    {
        inflightRect.set(tiles == null ? Set.of() : Set.copyOf(tiles));
        this.inflightSubtract = subtract;
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        WorldView wv = client.getTopLevelWorldView();
        if (wv == null) return null;
        List<Waypoint> wps = route.get();
        Set<WorldPoint> preview = previewTiles.get();
        Set<WorldPoint> inflight = inflightRect.get();
        if (wps.isEmpty() && preview.isEmpty() && inflight.isEmpty()) return null;
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
        // Annotator preview goes ON TOP so the user sees what they're
        // about to commit even when waypoints overlap.
        if (!preview.isEmpty())
        {
            drawPreview(g, preview, PREVIEW_ADD_FILL, PREVIEW_ADD_LINE);
        }
        if (!inflight.isEmpty())
        {
            Color fill = inflightSubtract ? PREVIEW_SUB_FILL : PREVIEW_ADD_FILL;
            Color line = inflightSubtract ? PREVIEW_SUB_LINE : PREVIEW_ADD_LINE;
            drawPreview(g, inflight, fill, line);
        }
        g.setStroke(prev);
        return null;
    }

    private void drawPreview(Graphics2D g, Set<WorldPoint> tiles, Color fill, Color line)
    {
        WorldView wv = client.getTopLevelWorldView();
        if (wv == null) return;
        g.setStroke(STROKE_2);
        for (WorldPoint wp : tiles)
        {
            LocalPoint lp = LocalPoint.fromWorld(wv, wp);
            if (lp == null) continue;
            Polygon poly = Perspective.getCanvasTilePoly(client, lp);
            if (poly == null) continue;
            g.setColor(fill);
            g.fillPolygon(poly);
            g.setColor(line);
            g.drawPolygon(poly);
        }
    }

    private void drawTileSet(Graphics2D g, Set<WorldPoint> tiles,
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
