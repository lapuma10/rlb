package net.runelite.client.plugins.recorder.worldmap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.coords.WorldPoint;

/** Writes inspection dumps that combine WorldMemory state into one JSON
 *  per call, so the seed pass + the V2 acceptance tests have something
 *  visible to diff against.
 *
 *  <p>The dumper is a read-only consumer of MapStore + EntityIndex +
 *  TransportIndex (the sources of truth — see spec "Source-of-truth
 *  rule"). It writes under {@code <runeliteDir>/recorder/inspect/} with
 *  filenames that include the call timestamp, so repeated dumps from
 *  the same seed-pass session land side by side and can be diffed.
 *
 *  <p>Threading: pure-data, no live Client/Scene access. Safe to call
 *  from any worker thread (the panel buttons spin off a Thread for
 *  each click). The underlying indices are concurrent-safe. */
@Slf4j
public final class InspectionDumper
{
    private static final int SCHEMA_V1 = 1;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final DateTimeFormatter TS_FORMAT =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final MapStore mapStore;
    private final EntityIndex entityIndex;
    private final TransportIndex transportIndex;
    private final WorldMemoryConfig wmConfig;
    private final File inspectDir;
    /** Optional cross-region planner. When non-null,
     *  {@link #planAToB(WorldPoint, WorldPoint)} delegates to this for
     *  all routes (including same-region same-plane). When null, falls
     *  back to the round-1 single-region Dijkstra and emits a "deferred
     *  to Phase 4" placeholder for cross-region. */
    @javax.annotation.Nullable
    private final net.runelite.client.plugins.recorder.nav.v2.MultiRegionAStar multiRegionPlanner;

    public InspectionDumper(MapStore mapStore, EntityIndex entityIndex,
                            TransportIndex transportIndex, WorldMemoryConfig wmConfig,
                            File inspectDir)
    {
        this(mapStore, entityIndex, transportIndex, wmConfig, inspectDir, null);
    }

    public InspectionDumper(MapStore mapStore, EntityIndex entityIndex,
                            TransportIndex transportIndex, WorldMemoryConfig wmConfig,
                            File inspectDir,
                            @javax.annotation.Nullable
                            net.runelite.client.plugins.recorder.nav.v2.MultiRegionAStar multiRegionPlanner)
    {
        this.mapStore = mapStore;
        this.entityIndex = entityIndex;
        this.transportIndex = transportIndex;
        this.wmConfig = wmConfig;
        this.inspectDir = inspectDir;
        this.multiRegionPlanner = multiRegionPlanner;
    }

    public File dumpRegion(int regionId)
    {
        JsonObject root = buildRegionDump(regionId);
        return write("region-" + regionId + "-" + nowStamp() + ".json", root);
    }

    public File dumpNearbyRegions(int centerRegionId)
    {
        JsonObject root = new JsonObject();
        root.addProperty("schema", SCHEMA_V1);
        root.addProperty("centerRegionId", centerRegionId);
        root.addProperty("dumpedAt", System.currentTimeMillis());

        JsonArray regions = new JsonArray();
        // Center + 8 neighbours: the region IDs are encoded as
        // ((rx<<8)|ry) so neighbours are ±1 in each axis on rx/ry.
        int rx = (centerRegionId >> 8) & 0xff;
        int ry = centerRegionId & 0xff;
        for (int dx = -1; dx <= 1; dx++)
        {
            for (int dy = -1; dy <= 1; dy++)
            {
                int neighbourId = (((rx + dx) & 0xff) << 8) | ((ry + dy) & 0xff);
                regions.add(buildRegionDump(neighbourId));
            }
        }
        root.add("regions", regions);
        return write("region-" + centerRegionId + "-nearby-" + nowStamp() + ".json", root);
    }

    public File dumpTransportGraph()
    {
        JsonObject root = new JsonObject();
        root.addProperty("schema", SCHEMA_V1);
        root.addProperty("dumpedAt", System.currentTimeMillis());
        JsonArray edges = new JsonArray();
        List<TransportEdge> all = new ArrayList<>(transportIndex.getAll());
        all.sort(Comparator.<TransportEdge>comparingInt(TransportEdge::regionId)
            .thenComparing(TransportEdge::key));
        for (TransportEdge e : all) edges.add(transportEdgeJson(e));
        root.add("edges", edges);
        return write("transports-" + nowStamp() + ".json", root);
    }

    public File dumpEntities()
    {
        JsonObject root = new JsonObject();
        root.addProperty("schema", SCHEMA_V1);
        root.addProperty("dumpedAt", System.currentTimeMillis());
        JsonArray npcs = new JsonArray();
        JsonArray objects = new JsonArray();
        for (Integer regionId : new TreeSet<>(entityIndex.knownRegionIds()))
        {
            for (EntitySighting s : entityIndex.npcsInRegion(regionId))
            {
                npcs.add(entitySightingJson(s, regionId));
            }
            for (EntitySighting s : entityIndex.objectsInRegion(regionId))
            {
                objects.add(entitySightingJson(s, regionId));
            }
        }
        root.add("npcs", npcs);
        root.add("objects", objects);
        return write("entities-" + nowStamp() + ".json", root);
    }

    /** Plans a route between {@code from} and {@code to} for the
     *  inspection panel. When a {@link MultiRegionAStar} is wired
     *  (Phase 4+), delegates to it for all routes — including
     *  cross-region and cross-plane via known transports. When not
     *  wired, falls back to a single-region Dijkstra for same-region
     *  same-plane and a "deferred" placeholder for cross-region.
     *
     *  <p>Returns a {@link PlanOutcome} carrying the dump file, a
     *  short human-readable summary (suitable for a UI status label),
     *  and the reconstructed route (empty when none — callers can
     *  publish this to the minimap overlay). */
    public PlanOutcome planAToB(WorldPoint from, WorldPoint to)
    {
        JsonObject root = new JsonObject();
        root.addProperty("schema", SCHEMA_V1);
        root.addProperty("dumpedAt", System.currentTimeMillis());
        root.add("from", worldPointJson(from));
        root.add("to", worldPointJson(to));

        int fromRegion = RegionIds.regionIdFor(from.getX(), from.getY());
        int toRegion = RegionIds.regionIdFor(to.getX(), to.getY());

        // Phase 4 path: real multi-region planner.
        if (multiRegionPlanner != null)
        {
            return planViaMultiRegion(from, to, fromRegion, toRegion, root);
        }

        JsonArray transportEdgesUsed = new JsonArray();
        JsonArray regionsTouched = new JsonArray();

        if (from.getPlane() != to.getPlane() || fromRegion != toRegion)
        {
            String msg = "cross-region/cross-plane planning is implemented in Phase 4 (V2Planner)";
            root.addProperty("result", msg);
            root.addProperty("pathLength", -1);
            root.add("regionsTouched", regionsTouched);
            root.add("transportEdgesUsed", transportEdgesUsed);
            root.add("route", new JsonArray());
            root.add("rejections", emptyRejections());
            File f = writePlanFile(from, to, root);
            String summary = "cross-region " + fromRegion + " → " + toRegion
                + " (deferred to Phase 4)";
            return new PlanOutcome(f, summary, List.of());
        }

        regionsTouched.add(toRegion);
        root.add("regionsTouched", regionsTouched);
        root.add("transportEdgesUsed", transportEdgesUsed);

        RegionChunkSnapshot snap = mapStore.snapshotFor(toRegion);
        JsonObject rejections = new JsonObject();
        if (snap == null)
        {
            root.addProperty("result", "failure");
            root.addProperty("pathLength", -1);
            root.add("route", new JsonArray());
            rejections.addProperty("blocked", 0);
            rejections.addProperty("unknown", 1);
            rejections.addProperty("stale", 0);
            root.add("rejections", rejections);
            File f = writePlanFile(from, to, root);
            return new PlanOutcome(f,
                "no snapshot loaded for region " + toRegion + " — walk through it first",
                List.of());
        }

        PathResult pr = reconstructPath(snap, from, to);
        String summary;
        List<WorldPoint> routeOut;
        if (pr.path.isEmpty())
        {
            root.addProperty("result", "failure");
            root.addProperty("pathLength", -1);
            root.add("route", new JsonArray());
            summary = "no route found — blocked=" + pr.blocked + ", unknown=" + pr.unknown;
            routeOut = List.of();
        }
        else
        {
            root.addProperty("result", "success");
            root.addProperty("pathLength", pr.path.size() - 1);
            JsonArray route = new JsonArray();
            for (WorldPoint p : pr.path) route.add(worldPointJson(p));
            root.add("route", route);
            summary = "route found — " + (pr.path.size() - 1) + " tiles";
            routeOut = List.copyOf(pr.path);
        }
        rejections.addProperty("blocked", pr.blocked);
        rejections.addProperty("unknown", pr.unknown);
        rejections.addProperty("stale", 0);   // round-1: no staleness tracking yet
        root.add("rejections", rejections);
        File f = writePlanFile(from, to, root);
        return new PlanOutcome(f, summary, routeOut);
    }

    /** Phase 4 plan path. Calls the live {@link
     *  net.runelite.client.plugins.recorder.nav.v2.MultiRegionAStar}
     *  and writes a spec-shaped dump (regionsTouched + transportEdgesUsed
     *  populated when applicable).
     *
     *  <p>The inspection panel deliberately uses raw A* (a single
     *  deterministic shortest path) rather than {@code V2Planner} so the
     *  dumps are easy to diff across runs and the inspect button doesn't
     *  perturb the live {@code RouteHistory}. Top-K alternation +
     *  weighted-random + recent-route penalty live in {@code V2Planner}
     *  and are exercised by the executor wired in Phase 5. */
    private PlanOutcome planViaMultiRegion(WorldPoint from, WorldPoint to,
                                            int fromRegion, int toRegion, JsonObject root)
    {
        net.runelite.client.plugins.recorder.nav.v2.V2Path path =
            multiRegionPlanner.plan(from, to);

        JsonArray regionsTouched = new JsonArray();
        JsonArray transportEdgesUsed = new JsonArray();
        JsonArray route = new JsonArray();
        Set<Integer> regions = new HashSet<>();

        if (path.isEmpty())
        {
            root.addProperty("result", "failure");
            root.addProperty("pathLength", -1);
            root.add("regionsTouched", regionsTouched);
            root.add("transportEdgesUsed", transportEdgesUsed);
            root.add("route", route);
            root.add("rejections", emptyRejections());
            File f = writePlanFile(from, to, root);
            return new PlanOutcome(f, "no route found", List.of());
        }

        List<WorldPoint> tiles = path.allTiles();
        for (WorldPoint p : tiles)
        {
            route.add(worldPointJson(p));
            regions.add(RegionIds.regionIdFor(p.getX(), p.getY()));
        }
        for (Integer r : new TreeSet<>(regions)) regionsTouched.add(r);

        int transportCount = 0;
        for (net.runelite.client.plugins.recorder.nav.v2.V2Leg leg : path.legs())
        {
            if (leg instanceof net.runelite.client.plugins.recorder.nav.v2.V2Leg.Transport t)
            {
                JsonObject ej = new JsonObject();
                ej.addProperty("verb", t.edge().verb());
                ej.addProperty("objectName", t.edge().objectName());
                ej.add("from", worldPointJson(t.edge().fromTile()));
                ej.add("to", worldPointJson(t.edge().toTile()));
                transportEdgesUsed.add(ej);
                transportCount++;
            }
        }

        root.addProperty("result", "success");
        root.addProperty("pathLength", path.totalCost());
        root.add("regionsTouched", regionsTouched);
        root.add("transportEdgesUsed", transportEdgesUsed);
        root.add("route", route);
        root.add("rejections", emptyRejections());
        File f = writePlanFile(from, to, root);
        String summary = "route found — " + path.totalCost() + " cost, "
            + tiles.size() + " tiles, " + regions.size() + " regions, "
            + transportCount + " transports";
        return new PlanOutcome(f, summary, tiles);
    }

    /** Outcome of {@link #planAToB} — the dump file, a UI-friendly
     *  one-line summary, and the reconstructed route (empty when none). */
    public record PlanOutcome(File file, String summary, List<WorldPoint> route) {}

    private File writePlanFile(WorldPoint from, WorldPoint to, JsonObject root)
    {
        String name = "plan-"
            + from.getX() + "x" + from.getY() + "p" + from.getPlane()
            + "-to-"
            + to.getX() + "x" + to.getY() + "p" + to.getPlane()
            + "-" + nowStamp() + ".json";
        return write(name, root);
    }

    private JsonObject buildRegionDump(int regionId)
    {
        JsonObject root = new JsonObject();
        root.addProperty("schema", SCHEMA_V1);
        root.addProperty("regionId", regionId);
        root.addProperty("dumpedAt", System.currentTimeMillis());

        RegionChunkSnapshot snap = mapStore.snapshotFor(regionId);
        Set<Integer> planes = new TreeSet<>();
        JsonArray tiles = new JsonArray();
        JsonArray objects = new JsonArray();
        int walkable = 0, blocked = 0;
        if (snap != null)
        {
            for (RegionChunkSnapshot.TileEntry t : snap.tiles())
            {
                planes.add(t.plane);
                JsonObject tj = new JsonObject();
                tj.addProperty("x", t.x);
                tj.addProperty("y", t.y);
                tj.addProperty("plane", t.plane);
                tj.addProperty("movement", t.movement);
                tiles.add(tj);
                if ((t.movement & CollisionDataFlag.BLOCK_MOVEMENT_FULL) != 0) blocked++;
                else walkable++;
            }
            for (EntitySighting o : snap.objects())
            {
                objects.add(entitySightingJson(o, regionId));
            }
            root.addProperty("gameRevision", snap.gameRevision());
            root.addProperty("lastScrapedAt", snap.lastScrapedAt());
        }

        JsonArray npcs = new JsonArray();
        for (EntitySighting n : entityIndex.npcsInRegion(regionId))
        {
            npcs.add(entitySightingJson(n, regionId));
        }

        JsonArray transportEdges = new JsonArray();
        int transportCount = 0;
        for (TransportEdge e : transportIndex.getAll())
        {
            if (e.regionId() == regionId)
            {
                transportEdges.add(transportEdgeJson(e));
                transportCount++;
            }
        }

        JsonArray planesArr = new JsonArray();
        for (Integer p : planes) planesArr.add(p);
        root.add("planes", planesArr);

        JsonObject summary = new JsonObject();
        summary.addProperty("knownTiles", walkable + blocked);
        summary.addProperty("walkableTiles", walkable);
        summary.addProperty("blockedTiles", blocked);
        summary.addProperty("objects", objects.size());
        summary.addProperty("npcs", npcs.size());
        summary.addProperty("transportEdges", transportCount);
        root.add("summary", summary);

        root.add("tiles", tiles);
        root.add("objects", objects);
        root.add("npcs", npcs);
        root.add("transportEdges", transportEdges);
        return root;
    }

    private static JsonObject worldPointJson(WorldPoint p)
    {
        JsonObject j = new JsonObject();
        j.addProperty("x", p.getX());
        j.addProperty("y", p.getY());
        j.addProperty("plane", p.getPlane());
        return j;
    }

    private static JsonObject entitySightingJson(EntitySighting s, int regionId)
    {
        JsonObject j = new JsonObject();
        j.addProperty("kind", s.kind.name());
        j.addProperty("id", s.id);
        j.addProperty("name", s.name);
        j.addProperty("x", s.lastTile.getX());
        j.addProperty("y", s.lastTile.getY());
        j.addProperty("plane", s.lastTile.getPlane());
        j.addProperty("regionId", regionId);
        j.addProperty("seenCount", s.seenCount);
        j.addProperty("lastSeenAt", s.lastSeenAt);
        return j;
    }

    private static JsonObject transportEdgeJson(TransportEdge e)
    {
        JsonObject j = new JsonObject();
        j.add("from", worldPointJson(e.fromTile()));
        j.add("to", worldPointJson(e.toTile()));
        j.addProperty("objectId", e.objectId());
        j.addProperty("objectName", e.objectName());
        j.addProperty("verb", e.verb());
        j.addProperty("targetKind", e.targetKind());
        j.add("approachTile", worldPointJson(e.approachTile()));
        j.addProperty("regionId", e.regionId());
        j.addProperty("seenCount", e.seenCount());
        j.addProperty("lastSeenAt", e.lastSeenAtMs());
        j.addProperty("observedDurationMs", e.observedDurationMs());
        return j;
    }

    private static JsonObject emptyRejections()
    {
        JsonObject j = new JsonObject();
        j.addProperty("blocked", 0);
        j.addProperty("unknown", 0);
        j.addProperty("stale", 0);
        return j;
    }

    private File write(String fileName, JsonObject root)
    {
        if (!inspectDir.exists() && !inspectDir.mkdirs())
        {
            log.warn("inspect: failed to create dir {}", inspectDir);
        }
        File out = new File(inspectDir, fileName);
        try
        {
            Files.writeString(out.toPath(), GSON.toJson(root));
        }
        catch (IOException e)
        {
            log.warn("inspect: failed to write {}: {}", out, e.getMessage());
        }
        return out;
    }

    private static String nowStamp()
    {
        return TS_FORMAT.format(Instant.now());
    }

    /** Single-region path reconstruction used by {@link #planAToB}. Walks
     *  Dijkstra from {@code from} until {@code to} is dequeued, then
     *  back-traces parents. {@code blocked} counts neighbours rejected by
     *  collision flags during expansion; {@code unknown} counts neighbours
     *  whose tile is not in the snapshot at all. */
    private PathResult reconstructPath(RegionChunkSnapshot snap, WorldPoint from, WorldPoint to)
    {
        if (snap == null || snap.tile(from.getX(), from.getY(), from.getPlane()) == null
            || snap.tile(to.getX(), to.getY(), to.getPlane()) == null)
        {
            return new PathResult(List.of(), 0, 1);
        }
        int plane = from.getPlane();
        long startKey = RegionChunkSnapshot.packTileKey(from.getX(), from.getY(), plane);
        long goalKey = RegionChunkSnapshot.packTileKey(to.getX(), to.getY(), plane);

        Map<Long, Long> parent = new HashMap<>();
        Map<Long, Integer> dist = new HashMap<>();
        java.util.PriorityQueue<long[]> pq = new java.util.PriorityQueue<>(
            Comparator.comparingInt(a -> (int) a[0]));
        pq.add(new long[]{0, startKey});
        dist.put(startKey, 0);
        parent.put(startKey, -1L);
        Set<Long> visited = new HashSet<>();
        int[] dxs = {0, 0, 1, -1, 1, 1, -1, -1};
        int[] dys = {1, -1, 0, 0, 1, -1, 1, -1};

        int blocked = 0, unknown = 0;
        boolean found = false;
        while (!pq.isEmpty())
        {
            long[] head = pq.poll();
            int d = (int) head[0];
            long k = head[1];
            if (!visited.add(k)) continue;
            if (k == goalKey) { found = true; break; }
            if (d >= wmConfig.maxPathLength) continue;
            int x = RegionChunkSnapshot.unpackX(k);
            int y = RegionChunkSnapshot.unpackY(k);
            for (int i = 0; i < 8; i++)
            {
                int nx = x + dxs[i], ny = y + dys[i];
                long nk = RegionChunkSnapshot.packTileKey(nx, ny, plane);
                RegionChunkSnapshot.TileEntry destTile = snap.tile(nx, ny, plane);
                if (destTile == null) { unknown++; continue; }
                if (!neighborTraversable(snap, x, y, plane, dxs[i], dys[i]))
                {
                    blocked++;
                    continue;
                }
                int nd = d + 1;
                if (nd < dist.getOrDefault(nk, Integer.MAX_VALUE))
                {
                    dist.put(nk, nd);
                    parent.put(nk, k);
                    pq.add(new long[]{nd, nk});
                }
            }
        }

        if (!found) return new PathResult(List.of(), blocked, unknown);

        List<WorldPoint> path = new ArrayList<>();
        long cur = goalKey;
        while (cur != -1L)
        {
            path.add(0, new WorldPoint(
                RegionChunkSnapshot.unpackX(cur),
                RegionChunkSnapshot.unpackY(cur),
                RegionChunkSnapshot.unpackPlane(cur)));
            Long parentKey = parent.get(cur);
            if (parentKey == null) break;
            cur = parentKey;
        }
        return new PathResult(path, blocked, unknown);
    }

    private static boolean neighborTraversable(RegionChunkSnapshot snap,
                                               int x, int y, int plane, int dx, int dy)
    {
        dx = Integer.signum(dx);
        dy = Integer.signum(dy);
        if (dx == 0 && dy == 0) return true;
        RegionChunkSnapshot.TileEntry destTile = snap.tile(x + dx, y + dy, plane);
        if (destTile == null) return false;
        int destFlags = destTile.movement;

        int xFlags = CollisionDataFlag.BLOCK_MOVEMENT_FULL;
        int yFlags = CollisionDataFlag.BLOCK_MOVEMENT_FULL;
        int xyFlags = CollisionDataFlag.BLOCK_MOVEMENT_FULL;

        if (dx < 0) xFlags |= CollisionDataFlag.BLOCK_MOVEMENT_EAST;
        if (dx > 0) xFlags |= CollisionDataFlag.BLOCK_MOVEMENT_WEST;
        if (dy < 0) yFlags |= CollisionDataFlag.BLOCK_MOVEMENT_NORTH;
        if (dy > 0) yFlags |= CollisionDataFlag.BLOCK_MOVEMENT_SOUTH;

        if (dx < 0 && dy < 0) xyFlags |= CollisionDataFlag.BLOCK_MOVEMENT_NORTH_EAST;
        if (dx < 0 && dy > 0) xyFlags |= CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_EAST;
        if (dx > 0 && dy < 0) xyFlags |= CollisionDataFlag.BLOCK_MOVEMENT_NORTH_WEST;
        if (dx > 0 && dy > 0) xyFlags |= CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_WEST;

        if (dx != 0 && (destFlags & xFlags) != 0) return false;
        if (dy != 0 && (destFlags & yFlags) != 0) return false;
        if (dx != 0 && dy != 0 && (destFlags & xyFlags) != 0) return false;
        return true;
    }

    private static final class PathResult
    {
        final List<WorldPoint> path;
        final int blocked;
        final int unknown;
        PathResult(List<WorldPoint> path, int blocked, int unknown)
        {
            this.path = path;
            this.blocked = blocked;
            this.unknown = unknown;
        }
    }
}
