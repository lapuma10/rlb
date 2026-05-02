package net.runelite.client.plugins.recorder.trail;

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
import net.runelite.client.plugins.recorder.RecorderConfig;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Debug overlay for the {@link TrailWalker}. Paints the recorded trail in
 * yellow (every WALK-leg tile + transport tiles, distinguished by colour and
 * verb label) and the walker's most recent click pick in blue.
 *
 * <p>Wiring is intentionally indirect: {@link TrailWalker} pushes state via
 * the static {@link #publishActiveTrail} / {@link #publishCurrentPick}
 * helpers, so script code that constructs a walker doesn't need to know
 * the overlay exists. The plugin registers a single instance on start-up;
 * the static accessors no-op when no overlay is registered.
 */
public final class TrailOverlay extends Overlay
{
    /** Walked-leg tiles. Soft yellow fill, brighter outline. */
    private static final Color WALK_FILL = new Color(255, 220, 60, 50);
    private static final Color WALK_LINE = new Color(255, 220, 60, 200);
    /** Transport tiles. Distinct orange so the user can tell at a glance
     *  which tiles are walks vs. action verbs. */
    private static final Color TRANSPORT_LINE = new Color(255, 140, 30, 230);
    /** Currently-targeted walk pick. Bright blue, thick stroke, drawn on
     *  TOP of the yellow trail so it stands out even when it's also part
     *  of the leg's tile list. */
    private static final Color PICK_FILL = new Color(60, 140, 255, 110);
    private static final Color PICK_LINE = new Color(60, 180, 255, 255);
    private static final Color LABEL_BG = new Color(0, 0, 0, 180);
    private static final BasicStroke STROKE_1 = new BasicStroke(1.2f);
    private static final BasicStroke STROKE_2 = new BasicStroke(2f);
    private static final BasicStroke STROKE_3 = new BasicStroke(3f);

    /** Most recently registered live overlay. {@code null} when the
     *  recorder plugin hasn't started or has shut down — the publish
     *  helpers no-op in that state so the walker can call them
     *  unconditionally. */
    private static final AtomicReference<TrailOverlay> LIVE = new AtomicReference<>();

    private final Client client;
    private final RecorderConfig config;
    private final AtomicReference<TrailPath> activeTrail = new AtomicReference<>();
    private final AtomicReference<WorldPoint> currentPick = new AtomicReference<>();

    public TrailOverlay(Client client, RecorderConfig config)
    {
        this.client = client;
        this.config = config;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
        setPriority(Overlay.PRIORITY_LOW);
        LIVE.set(this);
    }

    /** Disconnect from the publish helpers. The recorder plugin calls this
     *  on shutdown so a stale reference doesn't leak rendering work. */
    public void detach()
    {
        LIVE.compareAndSet(this, null);
        activeTrail.set(null);
        currentPick.set(null);
    }

    /** Push the current trail being walked. Pass {@code null} to clear
     *  (e.g. on walker reset / script stop). No-op when no overlay is
     *  registered — the walker calls this unconditionally. */
    public static void publishActiveTrail(@Nullable TrailPath path)
    {
        TrailOverlay o = LIVE.get();
        if (o != null) o.activeTrail.set(path);
    }

    /** Push the walker's current click target. Pass {@code null} to
     *  clear (e.g. on leg advance). No-op when no overlay is registered. */
    public static void publishCurrentPick(@Nullable WorldPoint tile)
    {
        TrailOverlay o = LIVE.get();
        if (o != null) o.currentPick.set(tile);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (config != null && !config.trailOverlay()) return null;
        TrailPath path = activeTrail.get();
        if (path == null) return null;
        WorldView wv = client.getTopLevelWorldView();
        if (wv == null) return null;

        Stroke prev = g.getStroke();
        // 1. Walk-leg + transport tiles in yellow / orange.
        List<Leg> legs = path.legs();
        for (int i = 0; i < legs.size(); i++)
        {
            Leg leg = legs.get(i);
            if (leg instanceof Leg.Walk w)
            {
                drawWalkLeg(g, w);
            }
            else if (leg instanceof Leg.Transport t)
            {
                drawTransport(g, t, i);
            }
        }
        // 2. Current pick on top — drawn last so blue wins over yellow
        //    when the pick is also part of a walk leg.
        WorldPoint pick = currentPick.get();
        if (pick != null) drawPick(g, pick);
        g.setStroke(prev);
        return null;
    }

    private void drawWalkLeg(Graphics2D g, Leg.Walk leg)
    {
        WorldView wv = client.getTopLevelWorldView();
        if (wv == null) return;
        g.setStroke(STROKE_1);
        for (WorldPoint wp : leg.tiles())
        {
            Polygon poly = tilePoly(wp);
            if (poly == null) continue;
            g.setColor(WALK_FILL);
            g.fillPolygon(poly);
            g.setColor(WALK_LINE);
            g.drawPolygon(poly);
        }
    }

    private void drawTransport(Graphics2D g, Leg.Transport leg, int legIndex)
    {
        Polygon poly = tilePoly(leg.tile());
        if (poly == null) return;
        g.setStroke(STROKE_2);
        g.setColor(TRANSPORT_LINE);
        g.drawPolygon(poly);
        String verb = leg.verb();
        String label = "#" + legIndex + (verb == null ? "" : " " + verb);
        labelAt(g, leg.tile(), label);
    }

    private void drawPick(Graphics2D g, WorldPoint pick)
    {
        Polygon poly = tilePoly(pick);
        if (poly == null) return;
        g.setStroke(STROKE_3);
        g.setColor(PICK_FILL);
        g.fillPolygon(poly);
        g.setColor(PICK_LINE);
        g.drawPolygon(poly);
    }

    @Nullable
    private Polygon tilePoly(WorldPoint wp)
    {
        WorldView wv = client.getTopLevelWorldView();
        if (wv == null) return null;
        LocalPoint lp = LocalPoint.fromWorld(wv, wp);
        if (lp == null) return null;
        return Perspective.getCanvasTilePoly(client, lp);
    }

    private void labelAt(Graphics2D g, WorldPoint wp, String label)
    {
        WorldView wv = client.getTopLevelWorldView();
        if (wv == null) return;
        LocalPoint lp = LocalPoint.fromWorld(wv, wp);
        if (lp == null) return;
        net.runelite.api.Point pt = Perspective.localToCanvas(client, lp, wp.getPlane());
        if (pt == null) return;
        int w = g.getFontMetrics().stringWidth(label) + 4;
        g.setColor(LABEL_BG);
        g.fillRect(pt.getX() - 1, pt.getY() - 12, w, 14);
        g.setColor(Color.WHITE);
        g.drawString(label, pt.getX() + 1, pt.getY() - 1);
    }
}
