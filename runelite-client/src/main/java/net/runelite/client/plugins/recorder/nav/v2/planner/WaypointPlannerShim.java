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

    public WaypointPlannerShim(Client client,
                               ClientThread clientThread,
                               GlobalCollisionSnapshot global,
                               TransportTable transportTable,
                               PredicateRegistry predicates)
    {
        this.client = client;
        this.clientThread = clientThread;
        this.global = global;
        this.transportTable = transportTable;
        this.predicates = predicates;
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
                WorldSnapshotBuilder.BlockingActorPolicy.NONE);
        }
        catch (Throwable th)
        {
            log.warn("[waypoint-shim] WorldSnapshotBuilder failed: {}", th.toString());
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
            log.warn("[waypoint-shim] WaypointPlanner.plan threw: {}", th.toString());
            return V2Path.EMPTY;
        }

        if (tpath == null || tpath.isFailed())
        {
            log.info("[waypoint-shim] no route from={} to={} reason={}",
                from, to, tpath == null ? "null" : tpath.failureReason().orElse(null));
            return V2Path.EMPTY;
        }

        List<V2Leg> legs = new ArrayList<>();
        int totalCost = 0;
        List<PathStep> steps = tpath.steps();
        for (PathStep step : steps)
        {
            if (step instanceof WalkStep ws)
            {
                WorldPoint target = ws.waypoint() == null ? null : ws.waypoint().target();
                if (target == null) continue;
                int regionId = target.getRegionID();
                legs.add(new V2Leg.Walk(regionId, List.of(target)));
                totalCost += 1; // 1 tick per waypoint endpoint click; rough estimate
            }
            else if (step instanceof TransportStep ts)
            {
                TransportLeg leg = ts.transport();
                if (leg == null) continue;
                TransportEdge edge = synthesizeEdge(leg);
                legs.add(new V2Leg.Transport(edge));
                totalCost += 5; // approximate teleport/transport tick cost
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

    /** Synthesize a {@link TransportEdge} from Lane 4's {@link TransportLeg}.
     *  Execution fields (fromTile, toTile, objectId, verb) come from the
     *  leg; observation-stat fields (seenCount, durationMs) get sensible
     *  defaults — the executor reads only the execution fields.
     *  TransportEdge requires a non-blank verb; fall back to "Walk-here"
     *  when the leg's action is empty. */
    private static TransportEdge synthesizeEdge(TransportLeg leg)
    {
        WorldPoint fromTile = leg.from();
        WorldPoint toTile = leg.to();
        int objectId = leg.objectId().orElse(0);
        String verb = leg.action().orElse("");
        if (verb == null || verb.isBlank()) verb = "Walk-here";
        WorldPoint approachTile = fromTile;
        int regionId = fromTile == null ? 0 : fromTile.getRegionID();
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
