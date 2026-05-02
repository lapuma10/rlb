/*
 * Copyright (c) 2026, RuneLite
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.sequence.dispatch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.ItemLayer;
import net.runelite.api.Model;
import net.runelite.api.NPC;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.WallObject;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import javax.annotation.Nullable;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.Shape;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;

/**
 * Converts a high-level target descriptor (world tile, NPC, widget+slot) into
 * the screen pixel that a humanized cursor should aim at, given the current
 * client state.
 *
 * <p>For walking, the resolver picks main-view vs. minimap based on whether
 * the target tile is currently in the loaded scene. Tiles in scene get a
 * main-view pixel via {@link Perspective#localToCanvas}; tiles outside the
 * scene get a minimap pixel via {@link Perspective#localToMinimap} after
 * being clipped to the player's reachable minimap radius.
 */
@Slf4j
@RequiredArgsConstructor
public final class PixelResolver
{
    private final Client client;
    /** Logged once per session so we can verify the clamp geometry against
     *  the widget the engine is actually using. */
    private boolean discBoundsLogged = false;
    private final Random rng = new Random();
    /** Recent click pixels — we reject any sample within {@link #MIN_REPEAT_PX}
     *  of one of these so consecutive clicks never land on the same spot.
     *  Bounded ring; oldest entries fall off. */
    private final Deque<int[]> recentClicks = new ArrayDeque<>();
    private static final int RECENT_CLICK_HISTORY = 12;
    /** No two consecutive clicks within this many pixels of each other.
     *  6 px is large enough that a human watching the cursor sees a
     *  visibly different landing spot between two clicks on the same
     *  model (a stairs hull is typically 30-60 px wide so the rejection
     *  budget never starves the sampler), small enough to still fit
     *  inside the minimap disc when the resolver falls back to ring
     *  jitter (the ring radius scales with the rejection budget — see
     *  {@link #ringJitter}). */
    private static final int MIN_REPEAT_PX = 6;

    /** Pick a screen pixel to walk toward.
     *
     *  <p>Real players alternate between minimap and main-view clicks
     *  depending on the situation: minimap for travel hops, main view for
     *  short positioning moves. We mirror that.
     *
     *  <p>Decision tree:
     *  <ul>
     *    <li>Beyond minimap radius (~17 tiles): {@code null} — caller waypoints.</li>
     *    <li>Distance &gt; 10 tiles: minimap (classic travel pattern).</li>
     *    <li>Distance ≤ 10 tiles AND tile polygon is on-screen AND we can
     *        find a candidate pixel that does not intersect any NPC/player
     *        convex hull: main view (with ~25% chance we skip this and pick
     *        minimap anyway, for variety).</li>
     *    <li>Otherwise: minimap.</li>
     *  </ul>
     *
     *  <p>The "no NPC/player overlap" rule is the key humanization fix —
     *  without it, a main-view click on a tile that has a chicken standing
     *  on it would resolve to "Attack Chicken" instead of "Walk here" and
     *  derail the route. We do not currently filter game-objects or ground
     *  items; those are rarely contested for travel tiles, and the
     *  exclusion above plus minimap fallback keeps us safe in practice.
     */
    @Nullable
    public Point resolveWalkTarget(WorldPoint target)
    {
        if (target == null) return null;
        Player local = client.getLocalPlayer();
        if (local == null || local.getWorldLocation() == null) return null;
        WorldPoint here = local.getWorldLocation();
        int dist = here.distanceTo(target);

        LocalPoint scene = LocalPoint.fromWorld(client.getTopLevelWorldView(), target);
        if (scene == null)
        {
            LocalPoint hereLocal = local.getLocalLocation();
            int dxTiles = target.getX() - here.getX();
            int dyTiles = target.getY() - here.getY();
            int sceneX = hereLocal.getX() + dxTiles * Perspective.LOCAL_TILE_SIZE;
            int sceneY = hereLocal.getY() + dyTiles * Perspective.LOCAL_TILE_SIZE;
            scene = new LocalPoint(sceneX, sceneY, client.getTopLevelWorldView().getId());
        }

        Point minimap = Perspective.localToMinimap(client, scene);
        if (minimap == null) return null;   // beyond minimap radius — waypoint upstream
        // Perspective.localToMinimap only checks the radial distance; the
        // resulting pixel can land in the corner gap between the visible
        // circle and the rectangular widget bounds, where the engine returns
        // 'Cancel' on click. Clamp into the disc so the click is on the map.
        minimap = clampToMinimapDisc(minimap);

        // Bias toward main view at short distances, minimap at long. Add a
        // 25% randomness band so the agent doesn't always pick the same
        // mode in the same circumstance.
        boolean considerMainView = dist <= 10 && rng.nextDouble() > 0.25;
        if (considerMainView)
        {
            Point clean = tryCleanMainViewPixel(scene);
            if (clean != null) { record(clean); return clean; }
            // Fall through to minimap if main view was unavailable or every
            // candidate inside the tile polygon hit an NPC/player.
        }

        // Polar jitter on a small ring so consecutive minimap clicks vary,
        // with the recent-history rejection still in play. Re-clamp because
        // jitter of up to 2px can push an edge pixel back outside the disc.
        Point p = clampToMinimapDisc(ringJitter(minimap, 1, 2));
        record(p);
        return p;
    }

    /** Force a minimap pixel for the given world target, ignoring main-view
     *  alternatives. Used by the dispatcher's "left-click would not be a
     *  walk" fallback path — minimap clicks always resolve to a walk. */
    @Nullable
    public Point resolveMinimapOnly(WorldPoint target)
    {
        if (target == null) return null;
        Player local = client.getLocalPlayer();
        if (local == null || local.getWorldLocation() == null) return null;
        WorldPoint here = local.getWorldLocation();
        LocalPoint scene = LocalPoint.fromWorld(client.getTopLevelWorldView(), target);
        if (scene == null)
        {
            LocalPoint hereLocal = local.getLocalLocation();
            int dx = target.getX() - here.getX();
            int dy = target.getY() - here.getY();
            int sx = hereLocal.getX() + dx * Perspective.LOCAL_TILE_SIZE;
            int sy = hereLocal.getY() + dy * Perspective.LOCAL_TILE_SIZE;
            scene = new LocalPoint(sx, sy, client.getTopLevelWorldView().getId());
        }
        Point mm = Perspective.localToMinimap(client, scene);
        if (mm == null) return null;
        mm = clampToMinimapDisc(mm);
        Point p = clampToMinimapDisc(ringJitter(mm, 1, 2));
        record(p);
        return p;
    }

    /** Pick a main-view pixel inside the tile's canvas polygon, with NO
     *  NPC/player avoidance and NO minimap fallback. Used for clicks that
     *  legitimately need to land on the player's own tile (loot pickup
     *  is the primary case — loot rolls to the kill tile, which is right
     *  under the player after a kill).
     *
     *  <p>Distinct from {@link #resolveWalkTarget} which biases toward
     *  pixels NOT under actors (so a walk doesn't accidentally attack)
     *  and falls back to the minimap when the tile is occluded — both
     *  exactly wrong for loot. Returns null if the tile isn't on canvas. */
    @Nullable
    public Point resolveTilePixel(WorldPoint world)
    {
        if (world == null) return null;
        LocalPoint scene = LocalPoint.fromWorld(client.getTopLevelWorldView(), world);
        if (scene == null) return null;
        Polygon poly = Perspective.getCanvasTilePoly(client, scene);
        if (poly == null || poly.npoints < 3) return null;
        Rectangle bb = poly.getBounds();
        if (bb.width < 2 || bb.height < 2) return null;
        // Sample inside the polygon. No actor-hull avoidance — we WANT
        // the cursor on the tile even if our character sprite overlaps
        // it; the engine's hover lookup picks the topmost interactable
        // (ground item over Walk-here when items are present).
        for (int attempt = 0; attempt < 24; attempt++)
        {
            int x = bb.x + rng.nextInt(bb.width);
            int y = bb.y + rng.nextInt(bb.height);
            if (!poly.contains(x, y)) continue;
            Point p = new Point(x, y);
            if (!isOnCanvas(p)) continue;
            if (conflictsWithRecent(p)) continue;
            record(p);
            return p;
        }
        // Fallback: tile centre. Always inside the poly for non-degenerate
        // tiles and on-canvas if the bbox check passed.
        Point centre = new Point(bb.x + bb.width / 2, bb.y + bb.height / 2);
        if (isOnCanvas(centre)) { record(centre); return centre; }
        return null;
    }

    /** Pick a click pixel that actually lands on the ground-item pile at
     *  {@code world}. {@link ItemLayer} extends {@link TileObject}, so the
     *  engine already maintains a {@link TileObject#getClickbox()} for the
     *  pile — the union of every item sprite's hit area, which is exactly
     *  what we want. Right-click anywhere in that shape and the menu is
     *  guaranteed to include "Take" entries for every item on the tile.
     *
     *  <p>The {@code itemId} parameter is kept for diagnostics / future
     *  per-item targeting; the layer clickbox already covers it because a
     *  chicken's feather + raw chicken always pile on the same tile.
     *
     *  <p>Fallbacks if the layer or its clickbox isn't usable yet (fresh
     *  spawn, GPU-renderer edge cases): project the tile's local centre
     *  via {@link Perspective#localToCanvas} and jitter ±{@link
     *  #ITEM_JITTER_PX} px inside the sprite footprint, then finally
     *  {@link #resolveTilePixel} (legacy random-in-tile-poly).
     */
    @Nullable
    public Point resolveGroundItemPixel(WorldPoint world, int itemId)
    {
        if (world == null) return null;
        LocalPoint scene = LocalPoint.fromWorld(client.getTopLevelWorldView(), world);
        if (scene == null) return null;
        Tile sceneTile;
        try
        {
            sceneTile = client.getScene().getTiles()
                [world.getPlane()][scene.getSceneX()][scene.getSceneY()];
        }
        catch (Throwable th) { return resolveTilePixel(world); }

        // Strategy 1 — ItemLayer clickbox. This is the engine's own
        // authoritative pile-hit shape; sampling inside it is what we
        // want for "right-click and see Take in the menu".
        if (sceneTile != null)
        {
            ItemLayer layer = sceneTile.getItemLayer();
            if (layer != null)
            {
                Shape clickbox = null;
                try { clickbox = layer.getClickbox(); }
                catch (Throwable ignored) { /* fall through */ }
                if (clickbox != null)
                {
                    Rectangle bb = clickbox.getBounds();
                    if (bb.width >= 2 && bb.height >= 2)
                    {
                        for (int attempt = 0; attempt < 24; attempt++)
                        {
                            int x = bb.x + rng.nextInt(bb.width);
                            int y = bb.y + rng.nextInt(bb.height);
                            if (!clickbox.contains(x, y)) continue;
                            Point p = new Point(x, y);
                            if (!isOnCanvas(p)) continue;
                            if (conflictsWithRecent(p)) continue;
                            record(p);
                            return p;
                        }
                        Point centre = new Point(bb.x + bb.width / 2, bb.y + bb.height / 2);
                        if (isOnCanvas(centre)) { record(centre); return centre; }
                    }
                }
            }
        }

        // Strategy 2 — project tile centre + jitter. Items render at the
        // local centre at the layer's z-offset; ±10 px keeps us inside
        // the sprite even when the layer clickbox isn't built yet.
        Point projected = Perspective.localToCanvas(client, scene, world.getPlane());
        if (projected != null && isOnCanvas(projected))
        {
            for (int attempt = 0; attempt < 16; attempt++)
            {
                int jx = rng.nextInt(ITEM_JITTER_PX * 2 + 1) - ITEM_JITTER_PX;
                int jy = rng.nextInt(ITEM_JITTER_PX * 2 + 1) - ITEM_JITTER_PX;
                Point p = new Point(projected.getX() + jx, projected.getY() + jy);
                if (!isOnCanvas(p)) continue;
                if (conflictsWithRecent(p)) continue;
                record(p);
                return p;
            }
            record(projected);
            return projected;
        }

        // Strategy 3 — tile polygon fallback (legacy behaviour).
        return resolveTilePixel(world);
    }

    /** Half-width of the random offset around the projected tile centre
     *  for ground-item clicks. ~10 px stays well inside the item sprite
     *  at default zoom (~30-50 px wide) while breaking pixel-perfect
     *  repetition. */
    private static final int ITEM_JITTER_PX = 10;

    /** Sample a pixel inside the target tile's on-screen polygon that does
     *  NOT fall inside any NPC or player convex hull. Returns null if no
     *  such pixel can be found in a budget of attempts, or the polygon is
     *  off-canvas. */
    @Nullable
    private Point tryCleanMainViewPixel(LocalPoint scene)
    {
        Polygon poly = Perspective.getCanvasTilePoly(client, scene);
        if (poly == null || poly.npoints < 3) return null;
        List<Shape> avoid = collectActorHulls();
        Rectangle bbox = poly.getBounds();
        if (bbox.width < 2 || bbox.height < 2) return null;
        int cx = bbox.x + bbox.width / 2;
        int cy = bbox.y + bbox.height / 2;
        for (int attempt = 0; attempt < 24; attempt++)
        {
            int x = bbox.x + rng.nextInt(bbox.width);
            int y = bbox.y + rng.nextInt(bbox.height);
            if (!poly.contains(x, y)) continue;
            if (Math.abs(x - cx) <= 1 && Math.abs(y - cy) <= 1) continue;
            Point candidate = new Point(x, y);
            if (!isOnCanvas(candidate)) continue;
            if (conflictsWithRecent(candidate)) continue;
            if (intersectsAny(avoid, x, y)) continue;
            return candidate;
        }
        return null;
    }

    /** All NPC + non-self player convex hulls in the loaded scene. Used as
     *  a "do not click here" mask for walking pixels. */
    private List<Shape> collectActorHulls()
    {
        List<Shape> out = new ArrayList<>();
        Player self = client.getLocalPlayer();
        for (NPC n : client.getTopLevelWorldView().npcs())
        {
            if (n == null) continue;
            Shape s = n.getConvexHull();
            if (s != null) out.add(s);
        }
        for (Player p : client.getTopLevelWorldView().players())
        {
            if (p == null || p == self) continue;
            Shape s = p.getConvexHull();
            if (s != null) out.add(s);
        }
        return out;
    }

    private static boolean intersectsAny(List<Shape> shapes, int x, int y)
    {
        for (Shape s : shapes)
        {
            if (s.contains(x, y)) return true;
        }
        return false;
    }

    private boolean isOnCanvas(Point p)
    {
        java.awt.Canvas c = client.getCanvas();
        if (c == null) return false;
        int x = p.getX(), y = p.getY();
        // Small margin so we don't pick a literal corner pixel.
        return x >= 4 && y >= 4 && x < c.getWidth() - 4 && y < c.getHeight() - 4;
    }

    /** Pick a pixel inside the NPC's clickable area, with rejection against
     *  recent clicks.
     *
     *  <p>Prefers {@link Actor#getConvexHull()} — the model's screen-space
     *  convex hull, which is the area the engine treats as "Attack [npc]".
     *  Falls back to the floor tile polygon when no hull is available
     *  (some NPCs don't expose one). The tile-poly-only path was wrong:
     *  pixels inside the tile but outside the model resolve to "Walk here"
     *  + "Examine" instead of "Attack", causing the loop to open useless
     *  right-click menus on empty grass next to the chicken. */
    @Nullable
    public Point resolveNpc(NPC npc)
    {
        if (npc == null) return null;
        Polygon poly = shapeToPolygon(npc.getConvexHull());
        if (poly == null || poly.npoints < 3) poly = npc.getCanvasTilePoly();
        if (poly == null || poly.npoints < 3) return null;
        Point p = sampleInsidePolygon(poly);
        if (p != null && isOnCanvas(p)) { record(p); return p; }
        return null;
    }

    /** Sampling region for {@link #resolveGameObject(GameObject, GameObjectStrategy)}.
     *  Different objects have different "what does the engine accept a
     *  click on" footprints; this enum lets the dispatcher iterate
     *  strategies when the first hover doesn't produce the requested
     *  verb (see in-call retry in {@code HumanizedInputDispatcher.gameObjectClick}). */
    public enum GameObjectStrategy
    {
        /** Sample inside the object's convex hull. The hull tracks the
         *  visible 3D model — correct for objects whose entire body is
         *  the click target (saplings, lecterns, banks-as-clickable-bodies).
         *  Wrong for tall multi-segment models (Lumbridge stairs, ladders,
         *  banister-topped staircases) whose hull also covers decorative
         *  geometry the engine does NOT accept clicks on; in those cases
         *  the menu comes back without the verb. Falls back to tile-poly
         *  when the hull isn't available. */
        HULL,
        /** Sample inside the object's tile-footprint polygon
         *  ({@link GameObject#getCanvasTilePoly()}). The engine's per-tile
         *  hit-test for GameObjects always fires inside this footprint —
         *  use this for stair / ladder / door / transport objects whose
         *  hull is wider than the actual click region. */
        TILE_POLY
    }

    /** Convenience: legacy callers default to {@link GameObjectStrategy#HULL},
     *  which preserves the historic behaviour. */
    @Nullable
    public Point resolveGameObject(GameObject obj)
    {
        return resolveGameObject(obj, GameObjectStrategy.HULL);
    }

    /** Pick a pixel inside the game object using the requested
     *  {@link GameObjectStrategy}.
     *
     *  <p>HULL → samples the convex hull (with tile-poly as fallback when
     *  the hull is unavailable). Best first attempt for general objects.
     *
     *  <p>TILE_POLY → samples the canvas projection of the object's tile
     *  footprint. This is what the OSRS engine hit-tests for the
     *  per-GameObject menu, so the hover reliably produces the object's
     *  verb. Use this as the retry strategy when HULL produced a pixel
     *  the engine didn't accept (the staircase / ladder pattern: hull
     *  contains decorative geometry above the clickable base).
     *
     *  <p>Rejection against the recent-click history applies the same
     *  way as for NPCs — chain-clicking the same door twice in a row
     *  should land on different pixels each time. */
    @Nullable
    public Point resolveGameObject(GameObject obj, GameObjectStrategy strategy)
    {
        if (obj == null) return null;
        Polygon poly;
        switch (strategy)
        {
            case TILE_POLY -> poly = obj.getCanvasTilePoly();
            case HULL -> {
                poly = shapeToPolygon(obj.getConvexHull());
                if (poly == null || poly.npoints < 3) poly = obj.getCanvasTilePoly();
            }
            default -> poly = null;
        }
        if (poly == null || poly.npoints < 3) return null;
        Point p = sampleInsidePolygon(poly);
        if (p != null && isOnCanvas(p)) { record(p); return p; }
        return null;
    }

    /** Pick a pixel inside the wall object's convex hull. Walls are typically
     *  doors / gates / fences — the hull spans only the wall sliver, not the
     *  whole tile, so this is what the human eye targets. Falls back to
     *  {@code getConvexHull2()} (some walls expose two segments) and finally
     *  to the underlying tile polygon. */
    @Nullable
    public Point resolveWallObject(WallObject wall)
    {
        if (wall == null) return null;
        Polygon poly = shapeToPolygon(wall.getConvexHull());
        if (poly == null || poly.npoints < 3) poly = shapeToPolygon(wall.getConvexHull2());
        if (poly == null || poly.npoints < 3)
        {
            LocalPoint lp = wall.getLocalLocation();
            poly = lp == null ? null : Perspective.getCanvasTilePoly(client, lp);
        }
        if (poly == null || poly.npoints < 3) return null;
        Point p = sampleInsidePolygon(poly);
        if (p != null && isOnCanvas(p)) { record(p); return p; }
        return null;
    }

    /** Convert an arbitrary {@link Shape} (typically a convex hull) to a
     *  {@link Polygon} suitable for pixel sampling. Returns null if the shape
     *  is null or its bounds are degenerate. */
    @Nullable
    private static Polygon shapeToPolygon(Shape s)
    {
        if (s == null) return null;
        if (s instanceof Polygon p) return p;
        // Walk the path with a flatness of 1px so we keep the convex hull's
        // approximate shape. Convex hulls are always polygonal in practice;
        // this handles the rare case the renderer returned a curved path.
        java.awt.geom.PathIterator it = s.getPathIterator(null, 1.0);
        Polygon out = new Polygon();
        double[] coords = new double[6];
        while (!it.isDone())
        {
            int seg = it.currentSegment(coords);
            if (seg == java.awt.geom.PathIterator.SEG_MOVETO
                || seg == java.awt.geom.PathIterator.SEG_LINETO)
            {
                out.addPoint((int) Math.round(coords[0]), (int) Math.round(coords[1]));
            }
            it.next();
        }
        return out.npoints >= 3 ? out : null;
    }

    /** Pick a pixel inside a widget's bounds, biased away from the centre and
     *  edges. For inventory-style widgets where you click an individual slot,
     *  pass the child widget id (not the parent) so the bounds are correct.
     *
     *  <p>Returns null when the widget is hidden (or any of its ancestors are
     *  hidden) — clicks at a hidden widget's stale bounds fall through to
     *  whatever's behind it on the canvas, which for sidebar widgets like
     *  combat-style buttons is the WORLD VIEW. The engine then treats the
     *  click as a tile walk, drifting the player further with every dropped
     *  call. The combat / training subsystems poll widgets every tick; one
     *  un-guarded hidden-widget click per second moves the player tens of
     *  tiles away from the pen in under a minute. */
    @Nullable
    public Point resolveWidget(int widgetId)
    {
        Widget w = client.getWidget(widgetId);
        if (w == null) return null;
        if (isHiddenIncludingAncestors(w)) return null;
        Rectangle r = w.getBounds();
        if (r == null || r.width < 2 || r.height < 2) return null;
        // Reject samples too near the edge (clicks can land on adjacent slots)
        // and too near the centre (looks mechanical).
        int marginX = Math.max(1, r.width / 8);
        int marginY = Math.max(1, r.height / 8);
        int cx = r.x + r.width / 2;
        int cy = r.y + r.height / 2;
        for (int attempt = 0; attempt < 8; attempt++)
        {
            int x = r.x + marginX + rng.nextInt(Math.max(1, r.width - 2 * marginX));
            int y = r.y + marginY + rng.nextInt(Math.max(1, r.height - 2 * marginY));
            // Avoid dead centre.
            if (Math.abs(x - cx) <= 1 && Math.abs(y - cy) <= 1) continue;
            Point p = new Point(x, y);
            if (!conflictsWithRecent(p))
            {
                record(p);
                return p;
            }
        }
        // Fallback if every attempt collided — accept it; recent history will
        // shift on the next call.
        Point fallback = new Point(
            r.x + marginX + rng.nextInt(Math.max(1, r.width - 2 * marginX)),
            r.y + marginY + rng.nextInt(Math.max(1, r.height - 2 * marginY)));
        record(fallback);
        return fallback;
    }

    /** True when {@code w} or any of its parent widgets is hidden. The
     *  engine flags a widget as visible only when the entire chain up to
     *  the root layer is visible — checking just {@code w.isHidden()}
     *  misses the case where a parent (e.g. the combat-tab content layer)
     *  is collapsed but the child still reports {@code !isHidden()} from
     *  its last layout. */
    private static boolean isHiddenIncludingAncestors(Widget w)
    {
        for (Widget cur = w; cur != null; cur = cur.getParent())
        {
            if (cur.isHidden()) return true;
        }
        return false;
    }

    /** Sample a pixel uniformly inside the given polygon, retrying on
     *  rejection (already-clicked pixel within MIN_REPEAT_PX) until we find
     *  a fresh one or the attempt budget is exhausted. */
    private Point sampleInsidePolygon(Polygon poly)
    {
        Rectangle bbox = poly.getBounds();
        if (bbox.width < 2 || bbox.height < 2) return null;
        int cx = bbox.x + bbox.width / 2;
        int cy = bbox.y + bbox.height / 2;
        for (int attempt = 0; attempt < 24; attempt++)
        {
            int x = bbox.x + rng.nextInt(bbox.width);
            int y = bbox.y + rng.nextInt(bbox.height);
            if (!poly.contains(x, y)) continue;
            // Reject the literal centre band — looks mechanical otherwise.
            if (Math.abs(x - cx) <= 1 && Math.abs(y - cy) <= 1) continue;
            Point p = new Point(x, y);
            if (!conflictsWithRecent(p)) return p;
        }
        // Last-resort: anything inside the polygon, even if it collides.
        for (int attempt = 0; attempt < 12; attempt++)
        {
            int x = bbox.x + rng.nextInt(bbox.width);
            int y = bbox.y + rng.nextInt(bbox.height);
            if (poly.contains(x, y)) return new Point(x, y);
        }
        return new Point(cx, cy);
    }

    /** Polar-coordinate jitter on a ring around a centre point. minR..maxR
     *  controls the inner/outer radius — minR > 0 means we never click the
     *  exact centre. Used for the minimap (small ring) and other small UI. */
    private Point ringJitter(Point center, double minR, double maxR)
    {
        for (int attempt = 0; attempt < 8; attempt++)
        {
            double r = minR + rng.nextDouble() * Math.max(0, maxR - minR);
            double a = rng.nextDouble() * Math.PI * 2;
            int x = center.getX() + (int) Math.round(r * Math.cos(a));
            int y = center.getY() + (int) Math.round(r * Math.sin(a));
            Point p = new Point(x, y);
            if (!conflictsWithRecent(p)) return p;
        }
        // Fallback: accept the last sample even if close to a recent click.
        double r = minR + rng.nextDouble() * Math.max(0, maxR - minR);
        double a = rng.nextDouble() * Math.PI * 2;
        return new Point(center.getX() + (int) Math.round(r * Math.cos(a)),
            center.getY() + (int) Math.round(r * Math.sin(a)));
    }

    private boolean conflictsWithRecent(Point candidate)
    {
        for (int[] prev : recentClicks)
        {
            int dx = candidate.getX() - prev[0];
            int dy = candidate.getY() - prev[1];
            if (dx * dx + dy * dy < MIN_REPEAT_PX * MIN_REPEAT_PX) return true;
        }
        return false;
    }

    private void record(Point p)
    {
        if (p == null) return;
        recentClicks.addLast(new int[]{p.getX(), p.getY()});
        while (recentClicks.size() > RECENT_CLICK_HISTORY) recentClicks.removeFirst();
    }

    /** Maximum world-tile distance (Chebyshev) at which
     *  {@link Perspective#localToMinimap} will return a non-null pixel for
     *  the current minimap zoom. Mirrors the engine's radial cap:
     *  {@code 20 << LOCAL_COORD_BITS} scaled by {@code 4 / minimapZoom}.
     *  At default zoom this is 20; with the minimap zoomed in it can drop
     *  as low as ~10. Subdivision uses this so we never hop to a tile
     *  that's just outside the projection range, and direct goal clicks
     *  use it so the engine's pathfinder can route across bridges/doors
     *  whenever the goal is within minimap range. */
    public int minimapRangeTiles()
    {
        try
        {
            double zoom = client.getMinimapZoom();
            if (zoom <= 0) return 20;
            // Underlying cap (Perspective.localToMinimap):
            //   distance = (20 << LOCAL_COORD_BITS) * (4d / minimapZoom)
            // In tiles that's 20 * 4 / zoom. The check is dx²+dy² >= d²,
            // so the safe Chebyshev range is the floor of that minus a
            // small margin to absorb rounding/jitter.
            int cap = (int) Math.floor(20.0 * 4.0 / zoom);
            return Math.max(6, cap - 1);
        }
        catch (Throwable th)
        {
            return 17;   // fallback — old static value
        }
    }

    /** True if the given pixel falls inside the minimap draw widget's
     *  bounds (the rectangle, NOT the inscribed disc — the rectangle is
     *  the engine's hit-test region for minimap left-clicks). */
    public boolean isMinimapPixel(Point p)
    {
        if (p == null) return false;
        Widget w = minimapWidget();
        if (w == null || w.isHidden()) return false;
        Rectangle b = w.getBounds();
        if (b == null) return false;
        return b.contains(p.getX(), p.getY());
    }

    /** Clamp a candidate pixel so it falls inside the visible minimap disc.
     *  {@link Perspective#localToMinimap} only rejects tiles beyond the
     *  radial visibility distance; the projected pixel can still land in the
     *  corner gap of the rectangular widget where the engine resolves to
     *  'Cancel' instead of WALK. If the minimap widget is unavailable, the
     *  point is returned unchanged. */
    private Point clampToMinimapDisc(Point p)
    {
        if (p == null) return null;
        Widget w = minimapWidget();
        if (w == null || w.isHidden()) return p;
        Rectangle b = w.getBounds();
        if (b == null || b.width < 8 || b.height < 8) return p;
        int cx = b.x + b.width / 2;
        int cy = b.y + b.height / 2;
        // Inscribed circle minus a small margin so we never sit on the rim,
        // where compass / orb art bleeds into the click target.
        int r = Math.min(b.width, b.height) / 2 - 4;
        if (r < 4) return p;
        if (!discBoundsLogged)
        {
            log.info("minimap disc: widget bounds=({},{} {}x{}) center=({},{}) r={}",
                b.x, b.y, b.width, b.height, cx, cy, r);
            discBoundsLogged = true;
        }
        int dx = p.getX() - cx;
        int dy = p.getY() - cy;
        long d2 = (long) dx * dx + (long) dy * dy;
        long r2 = (long) r * r;
        if (d2 <= r2) return p;
        double dist = Math.sqrt(d2);
        double scale = (r - 1) / dist;
        Point clamped = new Point(
            cx + (int) Math.round(dx * scale),
            cy + (int) Math.round(dy * scale));
        log.debug("minimap clamp: ({},{}) → ({},{})", p.getX(), p.getY(), clamped.getX(), clamped.getY());
        return clamped;
    }

    /** The minimap draw widget for the current layout (fixed / resizable
     *  classic / resizable modern). Mirrors the lookup that
     *  {@link Perspective#localToMinimap} uses internally. */
    @Nullable
    private Widget minimapWidget()
    {
        if (client.isResized())
        {
            if (client.getVarbitValue(VarbitID.RESIZABLE_STONE_ARRANGEMENT) == 1)
                return client.getWidget(WidgetInfo.RESIZABLE_MINIMAP_DRAW_AREA);
            return client.getWidget(WidgetInfo.RESIZABLE_MINIMAP_STONES_DRAW_AREA);
        }
        return client.getWidget(WidgetInfo.FIXED_VIEWPORT_MINIMAP_DRAW_AREA);
    }
}
