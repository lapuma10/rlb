package net.runelite.client.plugins.recorder.nav.v2;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.worldmap.TransportEdge;

/** Repeated-A* top-K router. Calls {@link MultiRegionAStar} K times,
 *  penalising edges from the previous attempt's path so the next run
 *  finds a different corridor when one exists. Cheaper than Yen's
 *  K-shortest-paths and good enough for V2 round-1 (spec lines
 *  317-336).
 *
 *  <p>Round-1 constants:
 *  <ul>
 *    <li>{@link #K} = 3 — never return more than 3 routes.</li>
 *    <li>{@link #MAX_ATTEMPTS} = 6 — give up after 6 A* runs even if K
 *        not reached.</li>
 *    <li>{@link #EDGE_REUSE_PENALTY} = 2.5 — each used edge costs 2.5×
 *        on the next attempt.</li>
 *    <li>{@link #COST_REJECT_MULTIPLIER} = 1.75 — drop any route with
 *        total cost above 1.75× the cheapest.</li>
 *  </ul>
 *
 *  Weighted-random pick uses {@code weight = 1 / (cost ×
 *  RouteHistory.penaltyFor(routeId))}, so cheaper + less-recently-used
 *  routes are preferred but not deterministic. */
@Slf4j
public final class TopKRouter
{
    public static final int K = 3;
    public static final int MAX_ATTEMPTS = 6;
    public static final double EDGE_REUSE_PENALTY = 2.5;
    public static final double COST_REJECT_MULTIPLIER = 1.75;

    private final MultiRegionAStar planner;
    private final RouteHistory history;

    public TopKRouter(MultiRegionAStar planner, RouteHistory history)
    {
        this.planner = planner;
        this.history = history;
    }

    /** Find up to {@link #K} distinct routes from {@code from} to
     *  {@code to}. Returns them sorted ascending by raw cost. */
    public List<V2Path> findTopK(WorldPoint from, WorldPoint to)
    {
        List<V2Path> routes = new ArrayList<>();
        Set<String> seenRouteIds = new HashSet<>();
        Map<String, Double> edgePenalties = new HashMap<>();
        int cheapest = Integer.MAX_VALUE;

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++)
        {
            if (routes.size() >= K) break;
            V2Path path = planner.plan(from, to, 0.0, edgePenalties, null);
            if (path.isEmpty()) break;
            if (!seenRouteIds.add(path.routeId())) continue;

            if (cheapest == Integer.MAX_VALUE) cheapest = path.totalCost();
            if (path.totalCost() > cheapest * COST_REJECT_MULTIPLIER)
            {
                log.debug("top-k: rejecting route {} cost={} > {} * {} = {}",
                    path.routeId(), path.totalCost(), cheapest,
                    COST_REJECT_MULTIPLIER, cheapest * COST_REJECT_MULTIPLIER);
                continue;
            }
            routes.add(path);
            penaliseEdges(path, edgePenalties);
        }
        routes.sort((a, b) -> Integer.compare(a.totalCost(), b.totalCost()));
        return routes;
    }

    /** Weighted-random pick. {@code weight = 1 / (cost ×
     *  history.penaltyFor(routeId))}. Returns the cheapest as a
     *  fallback when the list is degenerate (single entry). */
    public V2Path pickWeighted(List<V2Path> routes, Random rng)
    {
        if (routes == null || routes.isEmpty()) return V2Path.EMPTY;
        if (routes.size() == 1) return routes.get(0);
        double[] weights = new double[routes.size()];
        double total = 0;
        for (int i = 0; i < routes.size(); i++)
        {
            V2Path p = routes.get(i);
            double penalty = history.penaltyFor(p.routeId());
            // Avoid divide-by-zero on cost=0 (degenerate same-tile plan).
            double effectiveCost = Math.max(1, p.totalCost()) * penalty;
            weights[i] = 1.0 / effectiveCost;
            total += weights[i];
        }
        double r = rng.nextDouble() * total;
        double acc = 0;
        for (int i = 0; i < routes.size(); i++)
        {
            acc += weights[i];
            if (r < acc) return routes.get(i);
        }
        return routes.get(routes.size() - 1);
    }

    /** Multiply every edge in {@code path} by {@link #EDGE_REUSE_PENALTY}
     *  in {@code edgePenalties}, accumulating across calls so an edge
     *  used in two prior attempts is doubly penalised on the next. */
    private static void penaliseEdges(V2Path path, Map<String, Double> edgePenalties)
    {
        for (V2Leg leg : path.legs())
        {
            if (leg instanceof V2Leg.Walk w)
            {
                List<WorldPoint> tiles = w.tiles();
                for (int i = 1; i < tiles.size(); i++)
                {
                    WorldPoint a = tiles.get(i - 1);
                    WorldPoint b = tiles.get(i);
                    if (a.getPlane() != b.getPlane()) continue;
                    String key = MultiRegionAStar.walkEdgeKey(
                        a.getX(), a.getY(), a.getPlane(), b.getX(), b.getY());
                    edgePenalties.merge(key, EDGE_REUSE_PENALTY,
                        (existing, incoming) -> existing * incoming);
                }
            }
            else if (leg instanceof V2Leg.Transport t)
            {
                TransportEdge e = t.edge();
                edgePenalties.merge(e.key(), EDGE_REUSE_PENALTY,
                    (existing, incoming) -> existing * incoming);
            }
        }
    }
}
