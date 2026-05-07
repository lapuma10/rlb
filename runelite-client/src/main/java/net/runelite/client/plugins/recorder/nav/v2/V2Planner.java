package net.runelite.client.plugins.recorder.nav.v2;

import java.util.List;
import java.util.Random;
import java.util.function.BooleanSupplier;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.nav.BehaviorMode;
import net.runelite.client.plugins.recorder.worldmap.MapStore;
import net.runelite.client.plugins.recorder.worldmap.TransportIndex;
import net.runelite.client.plugins.recorder.worldmap.WorldMemoryConfig;

/** V2 planning entry point. Owns the {@link MultiRegionAStar} +
 *  {@link TopKRouter} + {@link RouteHistory} and applies the spec's
 *  selection algorithm:
 *
 *  <pre>
 *  if topK routes >= 2:
 *      70% pick weighted top-K route
 *      30% pick noisy A*
 *  else:
 *      use noisy A*
 *  </pre>
 *
 *  Round-1 only implements {@link BehaviorMode#VARIED}; the other modes
 *  log a warning and fall through to VARIED (per spec line 277).
 *
 *  <p>{@link #DEFAULT_NOISE} matches the spec's "±10–15%" guidance —
 *  small enough to keep the cheapest macro route stable, big enough to
 *  perturb tile-level diagonals so the bot doesn't repeat the same
 *  exact tile sequence. */
@Slf4j
public final class V2Planner
{
    public static final double DEFAULT_NOISE = 0.12;
    private static final double WEIGHTED_PICK_PROBABILITY = 0.70;

    private final MultiRegionAStar planner;
    private final TopKRouter topK;
    private final RouteHistory history;
    private final Random rng;
    @javax.annotation.Nullable private final MapStore mapStore;
    @javax.annotation.Nullable private final TransportIndex transports;
    /** Phase-11 variation gate. When false the planner returns the
     *  deterministic shortest path; when true it runs top-K + weighted
     *  pick + recent-route memory. Read live so the panel toggle takes
     *  effect on the next request without restarting the script. */
    private final BooleanSupplier variationEnabled;

    public V2Planner(MapStore mapStore, TransportIndex transports,
                     WorldMemoryConfig wmConfig, RouteHistory history)
    {
        this(mapStore, transports, wmConfig, history, () -> false);
    }

    public V2Planner(MapStore mapStore, TransportIndex transports,
                     WorldMemoryConfig wmConfig, RouteHistory history,
                     BooleanSupplier variationEnabled)
    {
        this(new MultiRegionAStar(mapStore, transports, wmConfig),
            new TopKRouter(new MultiRegionAStar(mapStore, transports, wmConfig), history),
            history, new Random(), mapStore, transports, variationEnabled);
    }

    /** Test ctor — caller injects a deterministic Random for reproducible
     *  selection traces. Variation is forced on so existing tests keep
     *  exercising the alternation paths. */
    V2Planner(MultiRegionAStar planner, TopKRouter topK, RouteHistory history, Random rng)
    {
        this(planner, topK, history, rng, null, null, () -> true);
    }

    /** Test ctor with explicit variation toggle — for the Phase-11
     *  on/off acceptance tests. */
    V2Planner(MultiRegionAStar planner, TopKRouter topK, RouteHistory history,
              Random rng, BooleanSupplier variationEnabled)
    {
        this(planner, topK, history, rng, null, null, variationEnabled);
    }

    private V2Planner(MultiRegionAStar planner, TopKRouter topK, RouteHistory history,
                      Random rng, @javax.annotation.Nullable MapStore mapStore,
                      @javax.annotation.Nullable TransportIndex transports,
                      BooleanSupplier variationEnabled)
    {
        this.planner = planner;
        this.topK = topK;
        this.history = history;
        this.rng = rng;
        this.mapStore = mapStore;
        this.transports = transports;
        this.variationEnabled = variationEnabled;
    }

    /** Diagnostic snapshot for a no-route case: which side has data,
     *  how many transport edges are known overall + outgoing from
     *  {@code from}'s tile, and whether the from/to tile entries exist
     *  in MapStore at all. Call ONLY when {@link #plan} returned EMPTY
     *  — surfaces the actual reason (NO_DATA at A, NO_DATA at B,
     *  no transport connecting planes, or NO_PATH despite both having
     *  data). */
    public String diagnose(WorldPoint from, WorldPoint to)
    {
        if (mapStore == null || transports == null)
        {
            return "(diagnose unavailable — V2Planner constructed without store refs)";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("from=").append(describe(from));
        sb.append(" to=").append(describe(to));
        sb.append(" transports{total=").append(transports.size());
        if (from != null)
        {
            sb.append(" outgoingFromHere=").append(transports.getOutgoing(from).size());
        }
        if (from != null && to != null && from.getPlane() != to.getPlane())
        {
            int crossPlane = 0;
            for (var e : transports.getAll())
            {
                if (e.fromTile().getPlane() == from.getPlane()
                    && e.toTile().getPlane() == to.getPlane()) crossPlane++;
            }
            sb.append(" crossPlane=").append(crossPlane);
        }
        sb.append("}");
        return sb.toString();
    }

    private String describe(WorldPoint p)
    {
        if (p == null) return "null";
        if (mapStore == null) return p.toString() + "(no-store)";
        var snap = mapStore.snapshotFor(net.runelite.client.plugins.recorder.worldmap
            .RegionIds.regionIdFor(p.getX(), p.getY()));
        if (snap == null) return p.toString() + "(region NOT loaded)";
        var tile = snap.tile(p.getX(), p.getY(), p.getPlane());
        if (tile == null) return p.toString() + "(tile NOT in snapshot)";
        boolean walkable = (tile.movement & net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_FULL) == 0;
        return p.toString() + (walkable ? "(walkable)" : "(blocked flags=0x" + Integer.toHexString(tile.movement) + ")");
    }

    /** Deterministic shortest-path plan, ignoring the variation flag and
     *  RouteHistory. Used by diagnostics ({@link RouteReadiness}) so the
     *  yes/no answer is stable across calls regardless of the user's
     *  variation setting. Production routing still goes through
     *  {@link #plan(WorldPoint, WorldPoint, BehaviorMode)}. */
    public V2Path planDeterministic(WorldPoint from, WorldPoint to)
    {
        if (from == null || to == null) return V2Path.EMPTY;
        return planner.plan(from, to);
    }

    /** Plan a route from {@code from} to {@code to} using
     *  {@code behaviorMode}. Returns {@link V2Path#EMPTY} if unreachable.
     *
     *  <p>If variation is disabled, returns the deterministic shortest
     *  path from a single A* run with no edge-cost noise — stable,
     *  predictable, easier to debug regressions. With variation enabled,
     *  runs the spec's selection algorithm (top-K + weighted pick +
     *  recent-route memory + 30 % noisy A* fallback). */
    public V2Path plan(WorldPoint from, WorldPoint to, BehaviorMode behaviorMode)
    {
        if (behaviorMode == null) behaviorMode = BehaviorMode.VARIED;
        if (behaviorMode != BehaviorMode.VARIED)
        {
            log.warn("v2-planner: behaviorMode={} not implemented in round 1; "
                + "falling through to VARIED", behaviorMode);
        }
        if (from == null || to == null) return V2Path.EMPTY;

        if (!variationEnabled.getAsBoolean())
        {
            // Stable path: single deterministic shortest A* run, no noise,
            // no top-K, no recent-route memory bookkeeping. The user can
            // flip the panel toggle to enable alternation.
            V2Path chosen = planner.plan(from, to);
            if (!chosen.isEmpty())
            {
                log.debug("v2-planner: variation OFF — deterministic plan {}→{} routeId={} cost={}",
                    from, to, chosen.routeId(), chosen.totalCost());
            }
            else
            {
                log.debug("v2-planner: variation OFF — no path {}→{}", from, to);
            }
            return chosen;
        }

        List<V2Path> routes = topK.findTopK(from, to);
        V2Path chosen;
        if (routes.size() >= 2 && rng.nextDouble() < WEIGHTED_PICK_PROBABILITY)
        {
            chosen = topK.pickWeighted(routes, rng);
            log.debug("v2-planner: top-K weighted pick — {} alternates, picked routeId={} cost={}",
                routes.size(), chosen.routeId(), chosen.totalCost());
        }
        else
        {
            chosen = planner.plan(from, to, DEFAULT_NOISE);
            log.debug("v2-planner: noisy A* — alts={}, picked routeId={} cost={}",
                routes.size(),
                chosen.isEmpty() ? "(empty)" : chosen.routeId(),
                chosen.isEmpty() ? -1 : chosen.totalCost());
        }
        if (!chosen.isEmpty()) history.record(chosen.routeId());
        return chosen;
    }
}
