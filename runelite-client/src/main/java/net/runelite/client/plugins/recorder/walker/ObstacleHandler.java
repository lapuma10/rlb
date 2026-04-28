package net.runelite.client.plugins.recorder.walker;

import java.awt.Rectangle;
import java.awt.Shape;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.transport.TransportResolver;

/**
 * Generic obstacle scanner. When a BFS halts at a frontier of unreachable
 * tiles, this class searches the surrounding area for game objects whose
 * menu actions match a known traversal verb (Open, Climb-up, Squeeze-through,
 * etc.) and returns a clickable hull rectangle so the caller can dispatch a
 * canvas click.
 *
 * <p>Scripts get the default verb whitelist for free; advanced callers can
 * narrow or extend it via {@link #withVerbs(List)} (e.g. a strict combat
 * macro might disable {@code Open} so it doesn't blunder through an enemy
 * outpost gate). Search radius defaults to 20 tiles — that comfortably
 * covers an entire BFS frontier even on the densest mazes.
 */
public final class ObstacleHandler
{
    /** Verbs the obstacle handler considers, in priority order. The same
     *  list as the {@code dax}/OSBot pattern, with the addition of
     *  {@code Use} as a low-priority catch-all for shortcuts that don't
     *  fit a cleaner verb. */
    public static final List<String> DEFAULT_VERBS = Collections.unmodifiableList(
        Arrays.asList(
            "Open",
            "Climb-up",
            "Climb-down",
            "Cross",
            "Pass",
            "Squeeze-through",
            "Climb-over",
            "Jump-over",
            "Pay-toll(10gp)",
            "Use"
        )
    );

    public static final int DEFAULT_RADIUS = 20;

    private final Client client;
    private final TransportResolver resolver;
    private final List<String> verbs;
    private final int radius;

    public ObstacleHandler(Client client, TransportResolver resolver)
    {
        this(client, resolver, DEFAULT_VERBS, DEFAULT_RADIUS);
    }

    private ObstacleHandler(Client client, TransportResolver resolver,
                            List<String> verbs, int radius)
    {
        this.client = client;
        this.resolver = resolver;
        this.verbs = verbs;
        this.radius = radius;
    }

    /** Return a copy with a custom verb whitelist. Callers can pass a
     *  trimmed list (e.g. only {@code "Open"} for gate-only routes) or an
     *  extended list (adding {@code "Pay-toll"} variants). */
    public ObstacleHandler withVerbs(List<String> verbs)
    {
        if (verbs == null || verbs.isEmpty())
            throw new IllegalArgumentException("verbs empty");
        return new ObstacleHandler(client, resolver,
            Collections.unmodifiableList(new ArrayList<>(verbs)), radius);
    }

    /** Return a copy with a custom search radius (in tiles). */
    public ObstacleHandler withRadius(int radius)
    {
        if (radius < 0) throw new IllegalArgumentException("radius < 0");
        return new ObstacleHandler(client, resolver, verbs, radius);
    }

    /** Scan tiles around {@code center} for an object whose menu actions
     *  include any of the configured verbs. Tiles closer to {@code center}
     *  are checked first; ties are broken by Chebyshev distance to
     *  {@code towards} (the BFS goal direction) so the obstacle nearest
     *  the player AND aligned with the desired direction wins. Must be
     *  called on the client thread. */
    @Nullable
    public Result findAt(WorldPoint center, @Nullable WorldPoint towards)
    {
        if (center == null) return null;
        // Generate (dx, dy) ring offsets ordered by Chebyshev distance from
        // (0,0). For each ring, stable-sort the offsets by their distance to
        // the towards-vector so directional candidates win ties.
        List<int[]> ringOffsets = ringOffsets(radius, center, towards);
        for (int[] off : ringOffsets)
        {
            WorldPoint p = new WorldPoint(
                center.getX() + off[0], center.getY() + off[1], center.getPlane());
            for (String verb : verbs)
            {
                TransportResolver.Match m = resolver.findTransport(p, verb);
                if (m == null || !m.isSuccess()) continue;
                Rectangle hull = hullBounds(m);
                if (hull == null) continue;
                return new Result(p, m, hull);
            }
        }
        return null;
    }

    /** Convenience: scan around the BFS frontier directly. The handler
     *  picks the frontier tile closest to {@code towards} as the search
     *  centre; if {@code towards} is null, the frontier tile closest to
     *  the BFS origin is used. */
    @Nullable
    public Result findOnFrontier(Reachability.ReachabilityMap reach,
                                 @Nullable WorldPoint towards)
    {
        if (reach == null) return null;
        List<WorldPoint> frontier = reach.frontier();
        if (frontier.isEmpty()) return null;
        WorldPoint anchor = towards == null ? reach.origin() : towards;
        if (anchor == null) return null;
        WorldPoint best = null;
        int bestDist = Integer.MAX_VALUE;
        for (WorldPoint p : frontier)
        {
            int d = Math.max(
                Math.abs(p.getX() - anchor.getX()),
                Math.abs(p.getY() - anchor.getY()));
            if (d < bestDist) { bestDist = d; best = p; }
        }
        if (best == null) return null;
        return findAt(best, towards);
    }

    /** Build the list of (dx, dy) offsets in a (2*radius+1)² square,
     *  excluding (0,0), sorted by Chebyshev ring then by alignment with
     *  the {@code towards} vector. Package-private so tests can verify
     *  the ordering. */
    static List<int[]> ringOffsets(int radius, WorldPoint center,
                                   @Nullable WorldPoint towards)
    {
        ArrayList<int[]> all = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++)
        {
            for (int dy = -radius; dy <= radius; dy++)
            {
                if (dx == 0 && dy == 0) continue;
                all.add(new int[]{dx, dy});
            }
        }
        int tx, ty;
        if (towards != null && center != null)
        {
            tx = towards.getX() - center.getX();
            ty = towards.getY() - center.getY();
        }
        else { tx = 0; ty = 0; }
        all.sort((a, b) -> {
            int ra = Math.max(Math.abs(a[0]), Math.abs(a[1]));
            int rb = Math.max(Math.abs(b[0]), Math.abs(b[1]));
            if (ra != rb) return Integer.compare(ra, rb);
            // Tie-break by alignment with towards-vector — larger dot
            // product = more "in the same direction" = better.
            int da = a[0] * tx + a[1] * ty;
            int db = b[0] * tx + b[1] * ty;
            return Integer.compare(db, da);
        });
        return all;
    }

    /** Click-target bounds for a {@link TransportResolver.Match}. Same
     *  fallbacks as the live LumbridgeBankPenScript: convex hull for
     *  wall/game objects, canvas tile poly for decorative/ground. */
    @Nullable
    private Rectangle hullBounds(TransportResolver.Match m)
    {
        if (m.wallObject() != null)
        {
            Shape h = m.wallObject().getConvexHull();
            if (h != null) return h.getBounds();
        }
        if (m.gameObject() != null)
        {
            Shape h = m.gameObject().getConvexHull();
            if (h != null) return h.getBounds();
        }
        if (m.decorativeObject() != null)
        {
            var poly = m.decorativeObject().getCanvasTilePoly();
            if (poly != null) return poly.getBounds();
        }
        if (m.groundObject() != null)
        {
            var poly = m.groundObject().getCanvasTilePoly();
            if (poly != null) return poly.getBounds();
        }
        return null;
    }

    /** A successful obstacle match: which tile carries it, the resolver
     *  match (so callers can read the matched verb / object id), and the
     *  pre-computed hull rectangle ready for clicking. */
    public static final class Result
    {
        public final WorldPoint tile;
        public final TransportResolver.Match match;
        public final Rectangle hullBounds;
        Result(WorldPoint tile, TransportResolver.Match match, Rectangle hull)
        {
            this.tile = tile; this.match = match; this.hullBounds = hull;
        }
    }
}
