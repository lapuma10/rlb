package net.runelite.client.plugins.recorder.nav.v2;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.Stroke;
import javax.annotation.Nullable;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.GroundObject;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.WallObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.RecorderConfig;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/** Paints the convex hulls of interactable game objects on the loaded
 *  scene. Lets you verify where a CLICK_GAME_OBJECT dispatch would
 *  actually land — the hull is the exact area the dispatcher samples
 *  a click pixel from, and if it doesn't visually cover the object
 *  model in-game we've got a hull/projection problem.
 *
 *  <p>Object types painted:
 *  <ul>
 *    <li>{@link WallObject} — gates, doors, fences (cyan hull + label).</li>
 *    <li>{@link GameObject} — stairs, ladders, NPCs-as-objects, scenery
 *        (yellow hull + label).</li>
 *    <li>{@link DecorativeObject} — wall-mounted decorations with actions
 *        (magenta hull + label).</li>
 *    <li>{@link GroundObject} — flat-ground decorations with actions
 *        (orange hull + label). Hulls here are shallow and rarely
 *        contested, but rendered for completeness.</li>
 *  </ul>
 *
 *  <p>Only objects whose first menu action is non-null and not
 *  {@code "Walk here"} are painted — pure scenery (decorative trees,
 *  flat carpets) is skipped. */
public final class ObjectDebugOverlay extends Overlay
{
    private static final Color WALL_LINE       = new Color( 60, 220, 240, 220);
    private static final Color WALL_FILL       = new Color( 60, 220, 240,  40);
    private static final Color GAME_LINE       = new Color(255, 230,  60, 220);
    private static final Color GAME_FILL       = new Color(255, 230,  60,  40);
    private static final Color DECORATIVE_LINE = new Color(255,  80, 220, 220);
    private static final Color DECORATIVE_FILL = new Color(255,  80, 220,  35);
    private static final Color GROUND_LINE     = new Color(255, 150,  60, 220);
    private static final Color GROUND_FILL     = new Color(255, 150,  60,  35);
    /** Faint stroke around the underlying tile poly so you can see the
     *  "stand-on-this-tile" footprint behind the 3D model hull. The hull
     *  alone often misses the floor patch at the base of stairs/ladders —
     *  that patch is what TILE_POLY strategy uses as a click target. */
    private static final Color TILE_OUTLINE    = new Color(255, 255, 255,  70);
    private static final Color LABEL_BG        = new Color(  0,   0,   0, 180);
    private static final BasicStroke STROKE    = new BasicStroke(1.6f);
    private static final BasicStroke STROKE_TILE = new BasicStroke(0.8f);

    /** Tile radius scanned around the player. 15 ≈ a screenful, keeps the
     *  per-frame object iteration cheap. */
    private static final int RADIUS_TILES = 15;

    private final Client client;
    private final RecorderConfig config;

    public ObjectDebugOverlay(Client client, RecorderConfig config)
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
        if (config == null || !config.objectDebugOverlay()) return null;
        WorldView wv = client.getTopLevelWorldView();
        if (wv == null) return null;
        Scene scene = wv.getScene();
        if (scene == null) return null;
        Player self = client.getLocalPlayer();
        if (self == null) return null;
        WorldPoint here = self.getWorldLocation();
        if (here == null) return null;
        int plane = here.getPlane();

        Tile[][][] tiles = scene.getTiles();
        if (tiles == null || plane < 0 || plane >= tiles.length) return null;
        Tile[][] plane2d = tiles[plane];
        if (plane2d == null) return null;

        int baseX = wv.getBaseX();
        int baseY = wv.getBaseY();
        Stroke prev = g.getStroke();
        g.setStroke(STROKE);

        for (int dx = -RADIUS_TILES; dx <= RADIUS_TILES; dx++)
        {
            for (int dy = -RADIUS_TILES; dy <= RADIUS_TILES; dy++)
            {
                int wx = here.getX() + dx;
                int wy = here.getY() + dy;
                int sx = wx - baseX;
                int sy = wy - baseY;
                if (sx < 0 || sy < 0 || sx >= plane2d.length) continue;
                Tile[] col = plane2d[sx];
                if (col == null || sy >= col.length) continue;
                Tile t = col[sy];
                if (t == null) continue;

                // Anchor tile poly — the floor patch beneath any object on
                // this tile. HULL strategy targets the 3D model; if that
                // hull misses the floor (typical for stairs), TILE_POLY
                // strategy uses this poly. Painting it makes the
                // "uncovered patch" issue visible.
                Polygon tilePoly = computeTilePoly(wv, wx, wy, plane);

                WallObject wall = t.getWallObject();
                if (wall != null)
                {
                    String action = firstAction(wall.getId());
                    if (action != null)
                    {
                        String lbl = action + " #" + wall.getId() + " W";
                        drawTileOutline(g, tilePoly);
                        drawHull(g, wall.getConvexHull(), WALL_FILL, WALL_LINE, lbl);
                        drawHull(g, wall.getConvexHull2(), WALL_FILL, WALL_LINE, null);
                    }
                }
                GameObject[] gobs = t.getGameObjects();
                if (gobs != null)
                {
                    for (GameObject go : gobs)
                    {
                        if (go == null) continue;
                        // Tile.getGameObjects() also contains wrappers around
                        // Actors (Players, NPCs). Their getId() resolves to
                        // an unrelated ObjectComposition and their hull paints
                        // over the actor model — skip them.
                        if (go.getRenderable() instanceof Actor) continue;
                        // Multi-tile objects appear on every covered tile —
                        // only render once at the canonical (sceneMin) tile
                        // to avoid hull stacking + label duplication.
                        if (go.getSceneMinLocation() == null
                            || go.getSceneMinLocation().getX() != sx
                            || go.getSceneMinLocation().getY() != sy) continue;
                        String action = firstAction(go.getId());
                        if (action != null)
                        {
                            String lbl = action + " #" + go.getId() + " G";
                            drawTileOutline(g, tilePoly);
                            drawHull(g, go.getConvexHull(), GAME_FILL, GAME_LINE, lbl);
                        }
                    }
                }
                DecorativeObject deco = t.getDecorativeObject();
                if (deco != null)
                {
                    String action = firstAction(deco.getId());
                    if (action != null)
                    {
                        String lbl = action + " #" + deco.getId() + " D";
                        drawHull(g, deco.getConvexHull(), DECORATIVE_FILL, DECORATIVE_LINE, lbl);
                        drawHull(g, deco.getConvexHull2(), DECORATIVE_FILL, DECORATIVE_LINE, null);
                    }
                }
                GroundObject gnd = t.getGroundObject();
                if (gnd != null)
                {
                    String action = firstAction(gnd.getId());
                    if (action != null)
                    {
                        String lbl = action + " #" + gnd.getId() + " R";
                        drawHull(g, gnd.getConvexHull(), GROUND_FILL, GROUND_LINE, lbl);
                    }
                }
            }
        }
        g.setStroke(prev);
        return null;
    }

    /** Returns the first non-null menu action of the object with {@code id},
     *  resolving impostor compositions for multi-state objects (e.g.
     *  open vs closed doors). Filters out pure-scenery cases where the
     *  first action is "Walk here" or null. */
    @Nullable
    private String firstAction(int id)
    {
        try
        {
            ObjectComposition def = client.getObjectDefinition(id);
            if (def == null) return null;
            if (def.getImpostorIds() != null)
            {
                try
                {
                    ObjectComposition imp = def.getImpostor();
                    if (imp != null) def = imp;
                }
                catch (Throwable ignored) { /* impostor needs a varbit state we
                    may lack; fall back to base def */ }
            }
            String[] actions = def.getActions();
            if (actions == null) return null;
            for (String a : actions)
            {
                if (a == null) continue;
                if ("Walk here".equalsIgnoreCase(a)) continue;
                return a;
            }
            return null;
        }
        catch (Throwable th)
        {
            return null;
        }
    }

    private void drawHull(Graphics2D g, @Nullable Shape hull, Color fill, Color line,
                          @Nullable String label)
    {
        if (hull == null) return;
        g.setColor(fill);
        g.fill(hull);
        g.setColor(line);
        g.draw(hull);
        if (label != null)
        {
            Rectangle b = hull.getBounds();
            if (b.width < 1 || b.height < 1) return;
            int x = b.x + b.width / 2;
            int y = b.y - 2;
            int w = g.getFontMetrics().stringWidth(label) + 4;
            g.setColor(LABEL_BG);
            g.fillRect(x - w / 2, y - 11, w, 13);
            g.setColor(Color.WHITE);
            g.drawString(label, x - w / 2 + 2, y - 1);
        }
    }

    @Nullable
    private static Polygon toPolygon(@Nullable Shape s)
    {
        if (s instanceof Polygon p) return p;
        return null;
    }

    @Nullable
    private Polygon computeTilePoly(WorldView wv, int wx, int wy, int plane)
    {
        LocalPoint lp = LocalPoint.fromWorld(wv, new WorldPoint(wx, wy, plane));
        if (lp == null) return null;
        return Perspective.getCanvasTilePoly(client, lp);
    }

    private void drawTileOutline(Graphics2D g, @Nullable Polygon tilePoly)
    {
        if (tilePoly == null) return;
        Stroke prev = g.getStroke();
        g.setStroke(STROKE_TILE);
        g.setColor(TILE_OUTLINE);
        g.drawPolygon(tilePoly);
        g.setStroke(prev);
    }
}
