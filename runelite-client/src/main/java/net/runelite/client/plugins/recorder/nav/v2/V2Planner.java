package net.runelite.client.plugins.recorder.nav.v2;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.nav.BehaviorMode;
import net.runelite.client.plugins.recorder.worldmap.MapStore;
import net.runelite.client.plugins.recorder.worldmap.RegionChunkSnapshot;
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
    /** Trail-bias hook: given a request's {@code trailName}, returns the
     *  list of recorded tiles for that trail (or null if no trail
     *  exists). Production wires this from {@link
     *  net.runelite.client.plugins.recorder.trail.TrailRegistry}. Tests
     *  can leave it null — bias degrades gracefully to the unbiased
     *  shortest-path. */
    @javax.annotation.Nullable
    private final Function<String, List<WorldPoint>> trailTilesByName;

    public V2Planner(MapStore mapStore, TransportIndex transports,
                     WorldMemoryConfig wmConfig, RouteHistory history)
    {
        this(mapStore, transports, wmConfig, history, () -> false, null);
    }

    public V2Planner(MapStore mapStore, TransportIndex transports,
                     WorldMemoryConfig wmConfig, RouteHistory history,
                     BooleanSupplier variationEnabled)
    {
        this(mapStore, transports, wmConfig, history, variationEnabled, null);
    }

    public V2Planner(MapStore mapStore, TransportIndex transports,
                     WorldMemoryConfig wmConfig, RouteHistory history,
                     BooleanSupplier variationEnabled,
                     @javax.annotation.Nullable Function<String, List<WorldPoint>> trailTilesByName)
    {
        this(new MultiRegionAStar(mapStore, transports, wmConfig),
            new TopKRouter(new MultiRegionAStar(mapStore, transports, wmConfig), history),
            history, new Random(), mapStore, transports, variationEnabled, trailTilesByName);
    }

    /** Test ctor — caller injects a deterministic Random for reproducible
     *  selection traces. Variation is forced on so existing tests keep
     *  exercising the alternation paths. */
    V2Planner(MultiRegionAStar planner, TopKRouter topK, RouteHistory history, Random rng)
    {
        this(planner, topK, history, rng, null, null, () -> true, null);
    }

    /** Test ctor with explicit variation toggle — for the Phase-11
     *  on/off acceptance tests. */
    V2Planner(MultiRegionAStar planner, TopKRouter topK, RouteHistory history,
              Random rng, BooleanSupplier variationEnabled)
    {
        this(planner, topK, history, rng, null, null, variationEnabled, null);
    }

    private V2Planner(MultiRegionAStar planner, TopKRouter topK, RouteHistory history,
                      Random rng, @javax.annotation.Nullable MapStore mapStore,
                      @javax.annotation.Nullable TransportIndex transports,
                      BooleanSupplier variationEnabled,
                      @javax.annotation.Nullable Function<String, List<WorldPoint>> trailTilesByName)
    {
        this.planner = planner;
        this.topK = topK;
        this.history = history;
        this.rng = rng;
        this.mapStore = mapStore;
        this.transports = transports;
        this.variationEnabled = variationEnabled;
        this.trailTilesByName = trailTilesByName;
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
     *  RouteHistory. Used by diagnostics so the yes/no answer is stable
     *  across calls regardless of the user's variation setting. Production
     *  routing still goes through
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
        return plan(from, to, behaviorMode, null);
    }

    /** Trail-biased plan. When {@code trailName} resolves to a recorded
     *  trail, A* prefers walk edges that land on the trail's recorded
     *  tiles (multiplier {@link MultiRegionAStar#TRAIL_PREFERENCE_MULTIPLIER}).
     *  This delivers V1-natural routes for known destinations while
     *  keeping V2's adaptability — when the trail's tiles are blocked
     *  (NPCs, transient obstacles), A* deviates around them.
     *
     *  <p>Falls through to unbiased planning when {@code trailName} is
     *  null, the trail loader is not wired, the trail is missing, or the
     *  trail has no tile events for the planning plane(s). */
    public V2Path plan(WorldPoint from, WorldPoint to, BehaviorMode behaviorMode,
                       @javax.annotation.Nullable String trailName)
    {
        if (behaviorMode == null) behaviorMode = BehaviorMode.VARIED;
        if (behaviorMode != BehaviorMode.VARIED)
        {
            log.warn("v2-planner: behaviorMode={} not implemented in round 1; "
                + "falling through to VARIED", behaviorMode);
        }
        if (from == null || to == null) return V2Path.EMPTY;

        Set<Long> preferred = resolveTrailPreferredKeys(trailName);

        if (!variationEnabled.getAsBoolean())
        {
            // Stable path: single deterministic shortest A* run, no noise,
            // no top-K, no recent-route memory bookkeeping. The user can
            // flip the panel toggle to enable alternation.
            V2Path chosen = preferred.isEmpty()
                ? planner.plan(from, to)
                : planner.plan(from, to, 0.0, java.util.Collections.emptyMap(), null, preferred);
            if (!chosen.isEmpty())
            {
                log.debug("v2-planner: variation OFF — deterministic plan {}→{} routeId={} cost={} trail={}",
                    from, to, chosen.routeId(), chosen.totalCost(),
                    preferred.isEmpty() ? "none" : trailName + "(" + preferred.size() + " tiles)");
            }
            else
            {
                log.debug("v2-planner: variation OFF — no path {}→{}", from, to);
            }
            return chosen;
        }

        // Variation ON path: top-K alternation skips trail bias because
        // top-K's job is to *diverge* from the cheapest route, while
        // trail bias's job is to *converge* on the recorded route — the
        // two goals fight. When a trail is wired we take the biased
        // single-A* branch; when not, we fall back to the spec's
        // top-K + noisy pick.
        if (!preferred.isEmpty())
        {
            V2Path chosen = planner.plan(from, to, DEFAULT_NOISE,
                java.util.Collections.emptyMap(), rng, preferred);
            if (!chosen.isEmpty())
            {
                history.record(chosen.routeId());
                log.debug("v2-planner: trail-biased — picked routeId={} cost={} trail={}({} tiles)",
                    chosen.routeId(), chosen.totalCost(), trailName, preferred.size());
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

    /** Resolve the trail's recorded tile sequence to packed-key set. The
     *  recording samples per game tick, which means consecutive points
     *  may be 2 tiles apart — we walk the line between them so A* gets
     *  bias on every intermediate tile, not just the sampled ones.
     *  Returns an empty set when no trail loader is wired or the trail
     *  resolves to nothing. */
    private Set<Long> resolveTrailPreferredKeys(@javax.annotation.Nullable String trailName)
    {
        if (trailName == null || trailName.isBlank() || trailTilesByName == null)
        {
            return Collections.emptySet();
        }
        List<WorldPoint> tiles;
        try
        {
            tiles = trailTilesByName.apply(trailName);
        }
        catch (Throwable th)
        {
            log.warn("v2-planner: trail loader threw for '{}' — falling back to unbiased plan",
                trailName, th);
            return Collections.emptySet();
        }
        if (tiles == null || tiles.isEmpty()) return Collections.emptySet();

        Set<Long> out = new HashSet<>();
        WorldPoint prev = null;
        for (WorldPoint t : tiles)
        {
            if (t == null) continue;
            if (prev != null && prev.getPlane() == t.getPlane())
            {
                int dx = t.getX() - prev.getX();
                int dy = t.getY() - prev.getY();
                int steps = Math.max(Math.abs(dx), Math.abs(dy));
                if (steps > 1 && steps <= 4)
                {
                    // Recording sampled mid-walk: interpolate so each
                    // unit step lands a preferred-tile key. Skips when
                    // the gap is bigger than 4 (a teleport / different
                    // session glitch) — the bias would mis-fit those.
                    int sx = Integer.signum(dx);
                    int sy = Integer.signum(dy);
                    for (int k = 1; k < steps; k++)
                    {
                        int nx = prev.getX() + sx * k;
                        int ny = prev.getY() + sy * k;
                        out.add(RegionChunkSnapshot.packTileKey(nx, ny, t.getPlane()));
                    }
                }
            }
            out.add(RegionChunkSnapshot.packTileKey(t.getX(), t.getY(), t.getPlane()));
            prev = t;
        }
        return out;
    }
}
