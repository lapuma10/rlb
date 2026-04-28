package net.runelite.client.plugins.recorder.walker;

import java.util.List;
import javax.annotation.Nullable;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;

/**
 * Combines a {@link Reachability.ReachabilityMap} with canvas / minimap
 * projection to choose the best click target. Mirrors the pick-walk-target
 * logic baked into {@code LumbridgeBankPenScript} but generalised so any
 * script can call it.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Iterate every reachable tile in the BFS map, on the same plane as
 *       the target.</li>
 *   <li>Score by Chebyshev distance from the tile to the target's centre —
 *       smaller is better. Ties broken by the tile's distance from the BFS
 *       origin (further from origin is better — pushes the player along
 *       the path).</li>
 *   <li>Prefer a tile that projects to the canvas viewport. Fall back to
 *       a tile that projects to the minimap if no canvas tile is close
 *       enough.</li>
 *   <li>Return null if NO reachable tile projects to either.</li>
 * </ol>
 *
 * <p>Must be called on the client thread — {@link Perspective#localToCanvas}
 * and {@link Perspective#localToMinimap} read live camera and viewport state.
 */
public final class StepClickPicker
{
    /** A reachable tile, the canvas pixel to click for it, and the kind of
     *  click. {@code distanceToTarget} is the Chebyshev distance from
     *  {@link #tile} to the target centre and is used by callers that want
     *  to detect "we've already arrived" (distance 0). */
    public static final class ClickTarget
    {
        public final WorldPoint tile;
        public final Point canvasPixel;
        public final boolean viaMinimap;
        public final int distanceFromOrigin;
        public final int distanceToTarget;

        ClickTarget(WorldPoint tile, Point canvas, boolean viaMinimap,
                    int distanceFromOrigin, int distanceToTarget)
        {
            this.tile = tile;
            this.canvasPixel = canvas;
            this.viaMinimap = viaMinimap;
            this.distanceFromOrigin = distanceFromOrigin;
            this.distanceToTarget = distanceToTarget;
        }
    }

    private final Client client;

    public StepClickPicker(Client client)
    {
        this.client = client;
    }

    /** Pick the best click target inside {@code area}. Two-pass:
     *  <ol>
     *    <li>BFS-reachable tiles in the area (preferred — known walkable
     *        path under our local collision view).</li>
     *    <li>If none, ANY tile in the area that projects to minimap.
     *        Trust the engine's pathfind (~25 tile depth, knows how to
     *        navigate around closed gates and through transports). This
     *        matches V1's behaviour: V1 clicks the destination and lets
     *        the engine work it out, which handles the
     *        goblin-fence → bridge case where a fence sits between
     *        BFS-reachable tiles and the destination.</li>
     *  </ol>
     *  Returns null only if even Pass 2 finds nothing — destination is
     *  past minimap range entirely. */
    @Nullable
    public ClickTarget pick(Reachability.ReachabilityMap reach, WorldArea area)
    {
        if (reach == null || area == null) return null;
        WorldPoint origin = reach.origin();
        if (origin == null) return null;
        if (origin.getPlane() != area.getPlane()) return null;
        WorldView wv = client.getTopLevelWorldView();
        if (wv == null) return null;

        int targetCx = area.getX() + area.getWidth() / 2;
        int targetCy = area.getY() + area.getHeight() / 2;

        // Pass 1: BFS-reachable tiles in the area.
        ClickTarget bestCanvas = null;
        ClickTarget bestMinimap = null;
        for (WorldPoint t : reach.reachableTiles())
        {
            if (!areaContains(area, t)) continue;
            ClickTarget c = projectTile(wv, t, reach.distance(t),
                chebyshev(t.getX(), t.getY(), targetCx, targetCy));
            if (c == null) continue;
            if (c.viaMinimap)
            {
                if (isBetter(c, bestMinimap)) bestMinimap = c;
            }
            else
            {
                if (isBetter(c, bestCanvas)) bestCanvas = c;
            }
        }
        if (bestCanvas != null) return bestCanvas;
        if (bestMinimap != null) return bestMinimap;

        // Pass 2: ANY tile in the area that projects. Engine pathfinds
        // around obstacles (gates, walls within 25 tiles).
        ClickTarget bestAny = null;
        for (int dx = 0; dx < area.getWidth(); dx++)
        {
            for (int dy = 0; dy < area.getHeight(); dy++)
            {
                WorldPoint t = new WorldPoint(
                    area.getX() + dx, area.getY() + dy, area.getPlane());
                int distToTarget = chebyshev(t.getX(), t.getY(),
                                             targetCx, targetCy);
                int distFromOrigin = chebyshev(origin.getX(), origin.getY(),
                                               t.getX(), t.getY());
                ClickTarget c = projectTile(wv, t, distFromOrigin, distToTarget);
                if (c == null) continue;
                if (isBetter(c, bestAny)) bestAny = c;
            }
        }
        return bestAny;
    }

    /** Pick the best click target heading TOWARD {@code target} — useful when
     *  the player is far from the next waypoint and can only reach part of
     *  the way there in one BFS. Iterates every reachable tile, picks the
     *  one closest to {@code target} that strictly improves over the
     *  player's current distance to {@code target}.
     *
     *  <p><b>Minimap-only on purpose.</b> A canvas click resolves through
     *  whatever's rendered at the picked pixel — a tree there triggers
     *  "Chop", a log "Take", an NPC "Attack". Minimap clicks always route
     *  as "Walk here". Step-toward picks intermediate tiles by definition,
     *  where pixel-overlap with clickable objects is the rule not the
     *  exception. Direct-path {@link #pick} keeps canvas preference because
     *  there the destination IS the click target.
     *
     *  <p><b>Strict progress.</b> Never returns a tile farther from
     *  {@code target} than the player already is — would cause the bot to
     *  walk away from the goal. Returns null when no reachable tile
     *  improves toward target. */
    @Nullable
    public ClickTarget pickTowards(Reachability.ReachabilityMap reach, WorldPoint target)
    {
        if (reach == null || target == null) return null;
        WorldPoint origin = reach.origin();
        if (origin == null) return null;
        if (origin.getPlane() != target.getPlane()) return null;
        WorldView wv = client.getTopLevelWorldView();
        if (wv == null) return null;

        // Player's current distance to target — every candidate must beat it.
        int hereToTarget = chebyshev(origin.getX(), origin.getY(),
                                     target.getX(), target.getY());

        // Pass 1: BFS-reachable tiles, minimap-only.
        ClickTarget best = null;
        for (WorldPoint t : reach.reachableTiles())
        {
            int distToTarget = chebyshev(t.getX(), t.getY(),
                                         target.getX(), target.getY());
            if (distToTarget >= hereToTarget) continue;       // strict progress
            LocalPoint lp = LocalPoint.fromWorld(wv, t);
            if (lp == null) continue;
            Point mini = Perspective.localToMinimap(client, lp);
            if (mini == null) continue;
            ClickTarget c = new ClickTarget(t, mini, true,
                reach.distance(t), distToTarget);
            if (isBetter(c, best)) best = c;
        }
        if (best != null) return best;

        // Pass 2: BFS halted (closed gate / wall in the way) — trust the
        // engine. Click the target tile itself if it projects to minimap;
        // engine pathfind goes ~25 tiles and navigates around / through
        // transports. This is the V1 behaviour: see a destination, click
        // it, let the game work out the route.
        LocalPoint lpTarget = LocalPoint.fromWorld(wv, target);
        if (lpTarget == null) return null;
        Point miniTarget = Perspective.localToMinimap(client, lpTarget);
        if (miniTarget == null) return null;
        return new ClickTarget(target, miniTarget, true,
            chebyshev(origin.getX(), origin.getY(), target.getX(), target.getY()),
            0);
    }

    /** True if {@code candidate} is strictly better than {@code current}
     *  (lower distance to target, with ties broken by larger distance from
     *  origin — the latter pushes us further along the path). */
    private static boolean isBetter(ClickTarget candidate, ClickTarget current)
    {
        if (current == null) return true;
        if (candidate.distanceToTarget != current.distanceToTarget)
            return candidate.distanceToTarget < current.distanceToTarget;
        return candidate.distanceFromOrigin > current.distanceFromOrigin;
    }

    @Nullable
    private ClickTarget projectTile(WorldView wv, WorldPoint t,
                                    int distanceFromOrigin, int distanceToTarget)
    {
        LocalPoint lp = LocalPoint.fromWorld(wv, t);
        if (lp == null) return null;
        Point canvas = Perspective.localToCanvas(client, lp, t.getPlane());
        if (canvas != null && inViewport(canvas))
        {
            return new ClickTarget(t, canvas, false, distanceFromOrigin, distanceToTarget);
        }
        Point mini = Perspective.localToMinimap(client, lp);
        if (mini != null)
        {
            return new ClickTarget(t, mini, true, distanceFromOrigin, distanceToTarget);
        }
        return null;
    }

    private boolean inViewport(Point cp)
    {
        int vx = client.getViewportXOffset();
        int vy = client.getViewportYOffset();
        int vw = client.getViewportWidth();
        int vh = client.getViewportHeight();
        return cp.getX() >= vx && cp.getX() < vx + vw
            && cp.getY() >= vy && cp.getY() < vy + vh;
    }

    static boolean areaContains(WorldArea a, WorldPoint p)
    {
        return p.getPlane() == a.getPlane()
            && p.getX() >= a.getX() && p.getX() < a.getX() + a.getWidth()
            && p.getY() >= a.getY() && p.getY() < a.getY() + a.getHeight();
    }

    static int chebyshev(int ax, int ay, int bx, int by)
    {
        return Math.max(Math.abs(ax - bx), Math.abs(ay - by));
    }

    /** For tests: list reachable tiles in {@code area}, sorted by Chebyshev
     *  distance to the area's centre (closest first). Pure logic, no
     *  client calls — useful for verifying the picker's preference order
     *  without a full Client mock. */
    static List<WorldPoint> candidatesInArea(Reachability.ReachabilityMap reach, WorldArea area)
    {
        java.util.ArrayList<WorldPoint> out = new java.util.ArrayList<>();
        if (reach == null || area == null) return out;
        if (reach.plane() != area.getPlane()) return out;
        int cx = area.getX() + area.getWidth() / 2;
        int cy = area.getY() + area.getHeight() / 2;
        for (WorldPoint t : reach.reachableTiles())
        {
            if (areaContains(area, t)) out.add(t);
        }
        out.sort((a, b) -> Integer.compare(
            chebyshev(a.getX(), a.getY(), cx, cy),
            chebyshev(b.getX(), b.getY(), cx, cy)));
        return out;
    }
}
