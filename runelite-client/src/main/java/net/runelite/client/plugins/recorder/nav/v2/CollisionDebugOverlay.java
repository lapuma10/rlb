package net.runelite.client.plugins.recorder.nav.v2;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Stroke;
import javax.annotation.Nullable;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.RecorderConfig;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/** Paints per-tile collision flags on the loaded scene so the user can
 *  visually pinpoint where V2's BFS hits dead ends.
 *
 *  <p>Colour key:
 *  <ul>
 *    <li>Green tile fill: walkable (no FULL block).</li>
 *    <li>Solid red fill: {@link CollisionDataFlag#BLOCK_MOVEMENT_FULL}.</li>
 *    <li>Red edge segment: directional wall on that side
 *        ({@code BLOCK_MOVEMENT_NORTH/EAST/SOUTH/WEST}).</li>
 *  </ul>
 *
 *  <p>Source: live {@code WorldView.getCollisionMaps()} for the player's
 *  plane. Same data the engine + V2's BFS kernel see. Tiles outside the
 *  104×104 loaded scene are not rendered. */
public final class CollisionDebugOverlay extends Overlay
{
    private static final Color WALKABLE_FILL = new Color( 60, 220, 100, 28);
    private static final Color FULL_BLOCK    = new Color(220,  40,  40, 120);
    private static final Color EDGE_BLOCK    = new Color(255,  50,  50, 230);
    private static final BasicStroke STROKE_EDGE = new BasicStroke(2f);
    private static final BasicStroke STROKE_THIN = new BasicStroke(1f);

    /** Render radius around the player (Chebyshev). 30 tiles ≈ visible
     *  canvas + a small buffer; covers the 16-tile minimap reach and a
     *  bit beyond so the user can scan ahead of where the bot is
     *  walking. Tightening this is fine; loosening past ~50 starts to
     *  hit the loaded-scene edge anyway. */
    private static final int RENDER_RADIUS_TILES = 30;

    private final Client client;
    private final RecorderConfig config;

    public CollisionDebugOverlay(Client client, RecorderConfig config)
    {
        this.client = client;
        this.config = config;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
        setPriority(Overlay.PRIORITY_LOW);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (config == null || !config.collisionDebugOverlay()) return null;
        WorldView wv = client.getTopLevelWorldView();
        if (wv == null) return null;
        Player self = client.getLocalPlayer();
        if (self == null) return null;
        WorldPoint here = self.getWorldLocation();
        if (here == null) return null;

        CollisionData[] maps = wv.getCollisionMaps();
        if (maps == null) return null;
        int plane = here.getPlane();
        if (plane < 0 || plane >= maps.length) return null;
        CollisionData cd = maps[plane];
        if (cd == null) return null;
        int[][] flags = cd.getFlags();
        if (flags == null) return null;

        int baseX = wv.getBaseX();
        int baseY = wv.getBaseY();
        int wSize = flags.length;
        int hSize = wSize > 0 ? flags[0].length : 0;

        Stroke prev = g.getStroke();
        for (int dx = -RENDER_RADIUS_TILES; dx <= RENDER_RADIUS_TILES; dx++)
        {
            for (int dy = -RENDER_RADIUS_TILES; dy <= RENDER_RADIUS_TILES; dy++)
            {
                int wx = here.getX() + dx;
                int wy = here.getY() + dy;
                int sx = wx - baseX;
                int sy = wy - baseY;
                if (sx < 0 || sy < 0 || sx >= wSize || sy >= hSize) continue;
                int f = flags[sx][sy];
                WorldPoint wp = new WorldPoint(wx, wy, plane);
                Polygon poly = tilePoly(wp);
                if (poly == null) continue;

                boolean full = (f & CollisionDataFlag.BLOCK_MOVEMENT_FULL) != 0;
                if (full)
                {
                    g.setStroke(STROKE_THIN);
                    g.setColor(FULL_BLOCK);
                    g.fillPolygon(poly);
                }
                else
                {
                    g.setColor(WALKABLE_FILL);
                    g.fillPolygon(poly);
                }

                // Directional edges. Polygon vertices are roughly
                // [NW, NE, SE, SW] in screen space (engine projection
                // can flip with camera). To paint a "blocked north"
                // segment in a way that survives camera rotation we
                // just draw between adjacent vertices that align with
                // the world direction — for a top-down isometric view
                // the polygon's 4 sides are the N/E/S/W edges.
                g.setStroke(STROKE_EDGE);
                g.setColor(EDGE_BLOCK);
                if (poly.npoints >= 4)
                {
                    // Perspective.getCanvasTilePoly returns vertices in
                    // world-space order SW → SE → NE → NW
                    // (Perspective.java:777-791). So:
                    //   SW→SE = SOUTH edge
                    //   SE→NE = EAST edge
                    //   NE→NW = NORTH edge
                    //   NW→SW = WEST edge
                    int[] x = poly.xpoints;
                    int[] y = poly.ypoints;
                    if ((f & CollisionDataFlag.BLOCK_MOVEMENT_SOUTH) != 0)
                    {
                        g.drawLine(x[0], y[0], x[1], y[1]);
                    }
                    if ((f & CollisionDataFlag.BLOCK_MOVEMENT_EAST) != 0)
                    {
                        g.drawLine(x[1], y[1], x[2], y[2]);
                    }
                    if ((f & CollisionDataFlag.BLOCK_MOVEMENT_NORTH) != 0)
                    {
                        g.drawLine(x[2], y[2], x[3], y[3]);
                    }
                    if ((f & CollisionDataFlag.BLOCK_MOVEMENT_WEST) != 0)
                    {
                        g.drawLine(x[3], y[3], x[0], y[0]);
                    }
                }
            }
        }
        g.setStroke(prev);
        return null;
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
}
