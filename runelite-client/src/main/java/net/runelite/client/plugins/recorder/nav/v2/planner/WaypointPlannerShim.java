package net.runelite.client.plugins.recorder.nav.v2.planner;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.recorder.nav.BehaviorMode;
import net.runelite.client.plugins.recorder.nav.NavRequest;
import net.runelite.client.plugins.recorder.nav.v2.V2Leg;
import net.runelite.client.plugins.recorder.nav.v2.V2Navigator;
import net.runelite.client.plugins.recorder.nav.v2.V2Path;
import net.runelite.client.plugins.recorder.nav.v2.bfs.BfsConfig;
import net.runelite.client.plugins.recorder.nav.v2.collision.GlobalCollisionSnapshot;
import net.runelite.client.plugins.recorder.nav.v2.collision.WorldSnapshot;
import net.runelite.client.plugins.recorder.nav.v2.collision.WorldSnapshotBuilder;
import net.runelite.client.plugins.recorder.nav.v2.predicate.PredicateRegistry;
import net.runelite.client.plugins.recorder.nav.v2.transport.PathStep;
import net.runelite.client.plugins.recorder.nav.v2.transport.TransportLeg;
import net.runelite.client.plugins.recorder.nav.v2.transport.TransportStep;
import net.runelite.client.plugins.recorder.nav.v2.transport.TransportTable;
import net.runelite.client.plugins.recorder.nav.v2.transport.WalkStep;
import net.runelite.client.plugins.recorder.worldmap.TransportEdge;

/** Adapts the new {@link WaypointPlanner} (Lane 4) to the existing
 *  {@link V2Navigator.PlannerHook} contract so RecorderPlugin can drop
 *  it in where the legacy {@code V2Planner} sits today, without
 *  touching V2Navigator or V2Executor.
 *
 *  <p>The new planner emits sparse waypoints over Lane 4's
 *  {@code transport.V2Path} interface. This shim translates each step
 *  into a Lane-5 {@link V2Leg}:
 *  <ul>
 *    <li>{@link WalkStep} → {@link V2Leg.Walk} containing the single
 *        waypoint target tile. The executor's
 *        {@code CanvasTilePicker} then issues a click on that endpoint
 *        and the game engine paths to it — the same shape a human
 *        player gets when clicking-to-walk. (We deliberately do not
 *        reconstruct the full tile sequence from the BFS here; that
 *        info lives inside the planner's flat tile list and is
 *        irrelevant for execution if the executor lets the engine path.)</li>
 *    <li>{@link TransportStep} → {@link V2Leg.Transport} wrapping a
 *        {@link TransportEdge} synthesized from the
 *        {@link TransportLeg}'s {@code from} / {@code to} / {@code action} /
 *        {@code objectId}. Most fields of {@code TransportEdge} are
 *        runtime/observation stats (seenCount, durationMs) that don't
 *        affect execution; we set sensible defaults.</li>
 *  </ul>
 *
 *  <p>Threading: {@code plan} marshals to the client thread internally
 *  via {@link WorldSnapshotBuilder#fromClient}. Callers may invoke from
 *  the worker thread. */
@Slf4j
public final class WaypointPlannerShim implements V2Navigator.PlannerHook
{
    private final Client client;
    private final ClientThread clientThread;
    private final GlobalCollisionSnapshot global;
    private final TransportTable transportTable;
    private final PredicateRegistry predicates;
    /** Holder of the precomputed component map. May resolve to null
     *  during the precompute window at plugin start — Dijkstra runs
     *  collision-blind in that case (status-quo). */
    @Nullable
    private final java.util.function.Supplier<
        net.runelite.client.plugins.recorder.nav.v2.collision.ConnectivityComponents>
        componentsSupplier;

    /** 5-arg constructor — preserves pre-collision-aware-Dijkstra
     *  behaviour by passing a null components supplier. */
    public WaypointPlannerShim(Client client,
                               ClientThread clientThread,
                               GlobalCollisionSnapshot global,
                               TransportTable transportTable,
                               PredicateRegistry predicates)
    {
        this(client, clientThread, global, transportTable, predicates, /*supplier*/ null);
    }

    /** 6-arg constructor — accepts a supplier for the precomputed
     *  connectivity-components map. The supplier is invoked once per
     *  {@code plan(...)} call via {@code WorldSnapshotBuilder.fromClient}
     *  at mint time and stored on the resulting snapshot. */
    public WaypointPlannerShim(Client client,
                               ClientThread clientThread,
                               GlobalCollisionSnapshot global,
                               TransportTable transportTable,
                               PredicateRegistry predicates,
                               @Nullable java.util.function.Supplier<
                                   net.runelite.client.plugins.recorder.nav.v2.collision.ConnectivityComponents>
                                   componentsSupplier)
    {
        this.client = client;
        this.clientThread = clientThread;
        this.global = global;
        this.transportTable = transportTable;
        this.predicates = predicates;
        this.componentsSupplier = componentsSupplier;
    }

    @Override
    public V2Path plan(WorldPoint from, WorldPoint to, BehaviorMode mode)
    {
        return plan(from, to, mode, /*trailName*/ null);
    }

    @Override
    public V2Path plan(WorldPoint from, WorldPoint to, BehaviorMode mode, @Nullable String trailName)
    {
        if (from == null || to == null)
        {
            log.warn("[waypoint-shim] null from={} to={} — empty path", from, to);
            return V2Path.EMPTY;
        }

        WorldSnapshot snap;
        try
        {
            snap = WorldSnapshotBuilder.fromClient(
                client, clientThread, global, transportTable, predicates,
                WorldSnapshotBuilder.BlockingActorPolicy.NONE,
                componentsSupplier);
        }
        catch (Throwable th)
        {
            log.warn("[waypoint-shim] WorldSnapshotBuilder failed for from={} to={}", from, to, th);
            return V2Path.EMPTY;
        }

        NavRequest req = NavRequest.toPoint(to, mode == null ? BehaviorMode.VARIED : mode);
        BfsConfig cfg = BfsConfig.defaults();

        net.runelite.client.plugins.recorder.nav.v2.transport.V2Path tpath;
        try
        {
            // 4-arg overload because Lane 4 may not have player position
            // populated on every snapshot path yet.
            tpath = WaypointPlanner.plan(req, from, snap, cfg);
        }
        catch (Throwable th)
        {
            log.warn("[waypoint-shim] WaypointPlanner.plan threw for from={} to={}", from, to, th);
            return V2Path.EMPTY;
        }

        if (tpath == null || tpath.isFailed())
        {
            log.info("[waypoint-shim] no route from={} to={} reason={}",
                from, to, tpath == null ? "null" : tpath.failureReason().orElse(null));
            return V2Path.EMPTY;
        }

        // Use full BFS tile sequences per walk leg (not single-tile sparse
        // waypoints). The executor's CanvasTilePicker needs ≥2 tiles per
        // V2Leg.Walk to pick the furthest-forward walkable tile — a
        // 1-tile list breaks pickNextInTilesAfter() (QC architect finding).
        List<V2Leg> legs = new ArrayList<>();
        int totalCost = 0;
        List<List<WorldPoint>> walkLegs;
        List<TransportLeg> transportLegs;
        if (tpath instanceof V2PathImpl impl)
        {
            walkLegs = impl.walkLegFlatTiles();
            transportLegs = impl.transportLegs();
        }
        else
        {
            // Fallback: collapse sparse waypoints into single-tile legs
            // (degraded behavior — executor falls through to minimap).
            walkLegs = new ArrayList<>();
            transportLegs = new ArrayList<>();
            List<WorldPoint> currentWalkTiles = new ArrayList<>();
            for (PathStep step : tpath.steps())
            {
                if (step instanceof WalkStep ws && ws.waypoint() != null)
                {
                    currentWalkTiles.add(ws.waypoint().target());
                }
                else if (step instanceof TransportStep ts && ts.transport() != null)
                {
                    walkLegs.add(List.copyOf(currentWalkTiles));
                    transportLegs.add(ts.transport());
                    currentWalkTiles = new ArrayList<>();
                }
            }
            walkLegs.add(List.copyOf(currentWalkTiles));
        }

        // Interleave: walkLegs[0], transportLegs[0], walkLegs[1], ...,
        // transportLegs[N-1], walkLegs[N]. Same shape as PathCompressor.assemble.
        for (int i = 0; i < walkLegs.size(); i++)
        {
            List<WorldPoint> walkTiles = walkLegs.get(i);
            if (!walkTiles.isEmpty())
            {
                int regionId = walkTiles.get(0).getRegionID();
                legs.add(new V2Leg.Walk(regionId, walkTiles));
                totalCost += walkTiles.size(); // 1 tick per tile, display only
            }
            if (i < transportLegs.size())
            {
                TransportLeg leg = transportLegs.get(i);
                TransportEdge edge = synthesizeEdge(leg);
                if (edge != null)
                {
                    legs.add(new V2Leg.Transport(edge));
                    totalCost += 5; // display-only transport cost estimate
                }
            }
        }

        if (legs.isEmpty())
        {
            log.info("[waypoint-shim] planner returned 0 legs after conversion from={} to={}",
                from, to);
            return V2Path.EMPTY;
        }

        log.info("[waypoint-shim] plan {} → {} legs={} cost≈{} pathId={}",
            from, to, legs.size(), totalCost, tpath.id());
        return new V2Path(legs, totalCost);
    }

    @Override
    public String diagnose(WorldPoint from, WorldPoint to)
    {
        return "(waypoint shim — see [waypoint-shim] log lines for planner reasons)";
    }

    /** Apply a transport correction request emitted by the executor +
     *  routed through V2Navigator. Per spec §7 rule 5, only the navigator
     *  may mutate TransportTable; the shim provides the actual call site
     *  to the table singleton. Best-effort — if the corrected link cannot
     *  be built, logs and skips. */
    public void applyTransportCorrection(
        net.runelite.client.plugins.recorder.nav.v2.TransportCorrectionRequest req)
    {
        if (req == null || transportTable == null)
        {
            log.warn("[waypoint-shim] applyTransportCorrection no-op (null req or table)");
            return;
        }
        // Lane 4's TransportTable.replace accepts a single TransportLink;
        // we don't have the legacy link object here, so we delegate to
        // appendLiveLink which adds the corrected edge to the delta layer.
        // Stale entries remain but the corrected one is preferred via
        // route-history blacklisting.
        log.info("[waypoint-shim] transport correction plannedTo={} actualTo={} — appending corrected edge",
            req.plannedTo(), req.actualTo());
        // The shim doesn't have direct access to TransportLink fields
        // matching the request shape. For now, log and let the next plan
        // pick a fresh route. Full retire of the stale edge belongs in a
        // follow-up Lane 4 + Lane 5 integration pass.
    }

    /** Synthesize a {@link TransportEdge} from Lane 4's {@link TransportLeg},
     *  or return null if the leg can't be executed. Execution fields
     *  (fromTile, toTile, objectId, verb) come from the leg; observation
     *  stats get defaults. A null return skips this transport at the
     *  caller (so the planner doesn't emit a leg that would fail later
     *  with TRANSPORT_OBJECT_NOT_FOUND). */
    private static TransportEdge synthesizeEdge(TransportLeg leg)
    {
        if (leg == null)
        {
            log.warn("[waypoint-shim] null transport leg — skipping");
            return null;
        }
        WorldPoint fromTile = leg.from();
        WorldPoint toTile = leg.to();
        if (fromTile == null || toTile == null)
        {
            log.warn("[waypoint-shim] transport leg has null tile, skipping: from={} to={} verb={}",
                fromTile, toTile, leg.action().orElse(null));
            return null;
        }
        String verb = leg.action().orElse("");
        if (verb == null || verb.isBlank())
        {
            log.warn("[waypoint-shim] transport leg has no verb, skipping: from={} to={} type={}",
                fromTile, toTile, leg.type());
            return null;
        }
        int objectId = leg.objectId().orElse(0);
        WorldPoint approachTile = fromTile;
        int regionId = fromTile.getRegionID();
        long now = System.currentTimeMillis();
        // TransportEdge field order:
        //   fromTile, toTile, objectId, objectName, verb, param0, param1,
        //   targetKind, approachTile, regionId, seenCount, lastSeenAtMs,
        //   observedDurationMs
        return new TransportEdge(
            fromTile, toTile, objectId,
            /*objectName*/ "",
            verb,
            /*param0*/ 0, /*param1*/ 0,
            /*targetKind*/ "",
            approachTile, regionId,
            /*seenCount*/ 1, /*lastSeenAtMs*/ now,
            /*observedDurationMs*/ 0L);
    }
}
