package net.runelite.client.plugins.recorder.agility;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Stroke;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

public final class AgilityCaptureOverlay extends Overlay
{
    private static final Color VALID_FILL   = new Color(64, 128, 255, 80);
    private static final Color CURRENT_FILL = new Color(255, 200, 0, 80);
    private static final Color OBJECT_LINE  = new Color(255, 80, 80, 200);
    private static final Color LAPEND_LINE  = new Color(255, 200, 0, 220);
    private static final BasicStroke STROKE_2 = new BasicStroke(2f);
    private static final BasicStroke STROKE_3 = new BasicStroke(3f);

    private final Client client;
    private final AgilityCaptureSession session;

    private volatile boolean enabled = true;

    public AgilityCaptureOverlay(Client client, AgilityCaptureSession session)
    {
        this.client = client;
        this.session = session;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
        setPriority(Overlay.PRIORITY_LOW);
    }

    public void setEnabled(boolean v) { this.enabled = v; }
    public boolean isEnabled() { return enabled; }

    @Override
    public java.awt.Dimension render(Graphics2D g)
    {
        if (!enabled || !session.isActive()) return null;
        CaptureModel m = session.getModel();
        if (m == null) return null;

        // Defensive snapshot — copy collections so subsequent client-thread
        // mutations don't corrupt the in-progress draw. Iteration on
        // ConcurrentModificationException-prone HashSet is the failure mode
        // we're avoiding.
        Set<WorldPoint> validSnap   = new HashSet<>(m.validTiles);
        Set<WorldPoint> currentSnap = new HashSet<>(m.currentLapTiles);
        List<Set<WorldPoint>> objectSnaps = new ArrayList<>();
        for (ObstacleObservation o : m.obstacles)
        {
            objectSnaps.add(new HashSet<>(o.objectTiles));
        }
        WorldPoint lapEndSnap = m.lapEndTile;

        WorldView wv = client.getTopLevelWorldView();
        if (wv == null) return null;

        Stroke prev = g.getStroke();
        g.setStroke(STROKE_2);

        // 1. Fill committed validTiles (blue).
        for (WorldPoint wp : validSnap)
        {
            Polygon poly = polyFor(wv, wp);
            if (poly == null) continue;
            g.setColor(VALID_FILL);
            g.fillPolygon(poly);
        }

        // 2. Fill currentLapTiles (yellow, in-flight).
        for (WorldPoint wp : currentSnap)
        {
            Polygon poly = polyFor(wv, wp);
            if (poly == null) continue;
            g.setColor(CURRENT_FILL);
            g.fillPolygon(poly);
        }

        // 3. Outline obstacle objectTiles (red).
        g.setColor(OBJECT_LINE);
        for (Set<WorldPoint> tiles : objectSnaps)
        {
            for (WorldPoint wp : tiles)
            {
                Polygon poly = polyFor(wv, wp);
                if (poly == null) continue;
                g.drawPolygon(poly);
            }
        }

        // 4. Outline lapEndTile (gold, thicker).
        if (lapEndSnap != null)
        {
            Polygon poly = polyFor(wv, lapEndSnap);
            if (poly != null)
            {
                g.setStroke(STROKE_3);
                g.setColor(LAPEND_LINE);
                g.drawPolygon(poly);
            }
        }

        g.setStroke(prev);
        return null;
    }

    private Polygon polyFor(WorldView wv, WorldPoint wp)
    {
        LocalPoint lp = LocalPoint.fromWorld(wv, wp);
        if (lp == null) return null;
        return Perspective.getCanvasTilePoly(client, lp);
    }
}
