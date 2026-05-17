package net.runelite.client.plugins.recorder.nav.v2;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Stroke;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.RecorderConfig;
import net.runelite.client.plugins.recorder.worldmap.TransportEdge;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/** Debug overlay for V2 (worldmap-v2) planned routes. Paints the active
 *  {@link V2Path} on the world: walk-leg tiles are coloured per leg index,
 *  transport tiles are outlined in magenta with their verb label, and the
 *  player's progress along the current walk leg is shown by dimming
 *  already-passed tiles.
 *
 *  <p>Wiring matches {@code TrailOverlay}: the executor calls the static
 *  {@link #publishActivePath} / {@link #publishProgress} helpers, which
 *  no-op when no overlay is registered. Keeps the executor agnostic of
 *  rendering and lets tests run without the overlay class loaded. */
public final class V2PathOverlay extends Overlay
{
    /** Distinct per-leg fill+line palette. Cycles when legs > palette size,
     *  but real routes rarely exceed 6 walk legs so cycling is a non-issue
     *  in practice. */
    private static final Color[] LEG_FILL = {
        new Color( 60, 200, 120, 60),    // green
        new Color( 60, 180, 220, 60),    // cyan
        new Color(220, 180,  60, 60),    // yellow
        new Color(200, 100, 220, 60),    // purple
        new Color(220, 130,  60, 60),    // orange
        new Color(120, 220, 120, 60),    // lime
    };
    private static final Color[] LEG_LINE = {
        new Color( 60, 220, 140, 220),
        new Color( 60, 200, 240, 220),
        new Color(240, 200,  60, 220),
        new Color(220, 120, 240, 220),
        new Color(240, 150,  60, 220),
        new Color(140, 240, 140, 220),
    };
    private static final Color TRANSPORT_LINE = new Color(255,  80, 200, 230);
    private static final Color PASSED_FILL    = new Color( 80,  80,  80,  40);
    private static final Color PASSED_LINE    = new Color(120, 120, 120, 110);
    private static final Color LABEL_BG       = new Color(  0,   0,   0, 180);
    private static final BasicStroke STROKE_1 = new BasicStroke(1.2f);
    private static final BasicStroke STROKE_2 = new BasicStroke(2f);

    private static final AtomicReference<V2PathOverlay> LIVE = new AtomicReference<>();

    private final Client client;
    private final RecorderConfig config;
    private final AtomicReference<V2Path> activePath = new AtomicReference<>();
    /** Current walk-leg index + progress-index within that leg. Encoded as
     *  a single AtomicInteger (high 16 bits = legIdx, low 16 = progressIdx)
     *  so both update atomically per tick from the executor. -1/-1 means
     *  "no progress yet". */
    private final AtomicInteger packedProgress = new AtomicInteger(pack(-1, -1));

    public V2PathOverlay(Client client, RecorderConfig config)
    {
        this.client = client;
        this.config = config;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
        setPriority(Overlay.PRIORITY_LOW);
        LIVE.set(this);
    }

    public void detach()
    {
        LIVE.compareAndSet(this, null);
        activePath.set(null);
        packedProgress.set(pack(-1, -1));
    }

    /** V2Executor publishes its current path here. Null clears the overlay
     *  (e.g. on route completion / reset). */
    public static void publishActivePath(@Nullable V2Path path)
    {
        V2PathOverlay o = LIVE.get();
        if (o == null) return;
        o.activePath.set(path);
        o.packedProgress.set(pack(-1, -1));
    }

    /** V2Executor publishes its leg + per-leg progress index so the overlay
     *  can dim tiles the player has already passed. */
    public static void publishProgress(int legIdx, int progressIdx)
    {
        V2PathOverlay o = LIVE.get();
        if (o != null) o.packedProgress.set(pack(legIdx, progressIdx));
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (config != null && !config.v2PathOverlay()) return null;
        V2Path path = activePath.get();
        if (path == null || path.isEmpty()) return null;
        WorldView wv = client.getTopLevelWorldView();
        if (wv == null) return null;

        int packed = packedProgress.get();
        int activeLegIdx = legIdxOf(packed);
        int activeProgressIdx = progressIdxOf(packed);

        Stroke prev = g.getStroke();
        List<V2Leg> legs = path.legs();
        for (int li = 0; li < legs.size(); li++)
        {
            V2Leg leg = legs.get(li);
            if (leg instanceof V2Leg.Walk w)
            {
                int pIdx = (li == activeLegIdx) ? activeProgressIdx : -1;
                // If li < activeLegIdx the whole leg is already passed
                // (we've advanced past it). Dim every tile.
                int forcePassedThrough = (li < activeLegIdx) ? Integer.MAX_VALUE : pIdx;
                drawWalkLeg(g, w, li, forcePassedThrough);
            }
            else if (leg instanceof V2Leg.Transport t)
            {
                drawTransport(g, t, li);
            }
        }
        g.setStroke(prev);
        return null;
    }

    private void drawWalkLeg(Graphics2D g, V2Leg.Walk leg, int legIdx, int passedThrough)
    {
        Color fill = LEG_FILL[legIdx % LEG_FILL.length];
        Color line = LEG_LINE[legIdx % LEG_LINE.length];
        g.setStroke(STROKE_1);
        List<WorldPoint> tiles = leg.tiles();
        for (int i = 0; i < tiles.size(); i++)
        {
            Polygon poly = tilePoly(tiles.get(i));
            if (poly == null) continue;
            boolean passed = i <= passedThrough;
            g.setColor(passed ? PASSED_FILL : fill);
            g.fillPolygon(poly);
            g.setColor(passed ? PASSED_LINE : line);
            g.drawPolygon(poly);
        }
    }

    private void drawTransport(Graphics2D g, V2Leg.Transport leg, int legIdx)
    {
        TransportEdge edge = leg.edge();
        if (edge == null) return;
        WorldPoint clickTile = edge.fromTile();
        if (clickTile == null) return;
        Polygon poly = tilePoly(clickTile);
        if (poly == null) return;
        g.setStroke(STROKE_2);
        g.setColor(TRANSPORT_LINE);
        g.drawPolygon(poly);
        String verb = edge.verb() == null ? "?" : edge.verb();
        String label = "#" + legIdx + " " + verb;
        labelAt(g, clickTile, label);
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

    private static int pack(int legIdx, int progressIdx)
    {
        return ((legIdx & 0xFFFF) << 16) | (progressIdx & 0xFFFF);
    }

    private static int legIdxOf(int packed)
    {
        int v = (packed >>> 16) & 0xFFFF;
        return v == 0xFFFF ? -1 : v;
    }

    private static int progressIdxOf(int packed)
    {
        int v = packed & 0xFFFF;
        return v == 0xFFFF ? -1 : v;
    }
}
