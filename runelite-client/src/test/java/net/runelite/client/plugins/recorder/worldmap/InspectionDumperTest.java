package net.runelite.client.plugins.recorder.worldmap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class InspectionDumperTest
{
    @Test
    public void dumpCurrentRegion_writesSummaryAndArrays() throws IOException
    {
        Path tmp = Files.createTempDirectory("inspect-region-");
        WorldMemoryConfig wmConfig = new WorldMemoryConfig();

        MapStore store = new MapStore(wmConfig);
        int regionId = 12850;
        RegionChunkBuilder b = new RegionChunkBuilder(regionId);
        // Two walkable tiles, one blocked.
        b.setTile(3208, 3213, 0, 0);
        b.setTile(3209, 3213, 0, 0);
        b.setTile(3210, 3213, 0, CollisionDataFlag.BLOCK_MOVEMENT_FULL);
        b.objects.put(RegionChunkBuilder.packObjKey(1530, new WorldPoint(3209, 3213, 0)),
            new EntitySighting(EntitySighting.Kind.OBJECT, 1530, "Door",
                new WorldPoint(3209, 3213, 0), 1, 1_700_000_000_000L));
        store.installSnapshotForTest(regionId, RegionChunkSnapshot.fromBuilder(b));

        EntityIndex entities = new EntityIndex();
        entities.recordNpcSighting(4626, "Cook", new WorldPoint(3208, 3213, 0), 1_700_000_000_000L);

        TransportIndex transports = new TransportIndex();
        transports.add(sampleEdgeInRegion(regionId));
        // An edge in another region — must NOT appear in the per-region dump.
        transports.add(otherRegionEdge());

        InspectionDumper dumper = new InspectionDumper(store, entities, transports,
            wmConfig, tmp.toFile());

        File out = dumper.dumpRegion(regionId);

        assertTrue("dump file written", out.exists());
        assertTrue("file lives under inspect dir", out.toPath().startsWith(tmp));

        JsonObject root = readJson(out);
        assertEquals(regionId, root.get("regionId").getAsInt());

        JsonObject summary = root.getAsJsonObject("summary");
        assertEquals(3, summary.get("knownTiles").getAsInt());
        assertEquals(2, summary.get("walkableTiles").getAsInt());
        assertEquals(1, summary.get("blockedTiles").getAsInt());
        assertEquals(1, summary.get("objects").getAsInt());
        assertEquals(1, summary.get("npcs").getAsInt());
        assertEquals("only the in-region transport counts",
            1, summary.get("transportEdges").getAsInt());

        assertTrue("tiles array present", root.has("tiles") && root.get("tiles").isJsonArray());
        assertTrue("objects array present", root.has("objects") && root.get("objects").isJsonArray());
        assertTrue("npcs array present", root.has("npcs") && root.get("npcs").isJsonArray());
        assertTrue("transportEdges array present",
            root.has("transportEdges") && root.get("transportEdges").isJsonArray());

        assertEquals(3, root.getAsJsonArray("tiles").size());
        assertEquals(1, root.getAsJsonArray("objects").size());
        assertEquals(1, root.getAsJsonArray("npcs").size());
        assertEquals("transportEdges array filtered to this region",
            1, root.getAsJsonArray("transportEdges").size());
    }

    @Test
    public void dumpRegion_unknownRegion_writesEmptySummary() throws IOException
    {
        Path tmp = Files.createTempDirectory("inspect-empty-region-");
        WorldMemoryConfig wmConfig = new WorldMemoryConfig();
        InspectionDumper dumper = new InspectionDumper(new MapStore(wmConfig),
            new EntityIndex(), new TransportIndex(), wmConfig, tmp.toFile());

        File out = dumper.dumpRegion(99999);

        JsonObject root = readJson(out);
        JsonObject summary = root.getAsJsonObject("summary");
        assertEquals(0, summary.get("knownTiles").getAsInt());
        assertEquals(0, summary.get("walkableTiles").getAsInt());
        assertEquals(0, summary.get("blockedTiles").getAsInt());
    }

    @Test
    public void dumpNearbyRegions_includesCenterPlusNeighbours() throws IOException
    {
        Path tmp = Files.createTempDirectory("inspect-nearby-");
        WorldMemoryConfig wmConfig = new WorldMemoryConfig();
        MapStore store = new MapStore(wmConfig);

        // Center region 12850 = ((50<<8)|82) — populate center and one neighbour.
        int center = 12850;
        int neighbour = center + 1;
        RegionChunkBuilder bc = new RegionChunkBuilder(center);
        bc.setTile(3208, 3213, 0, 0);
        store.installSnapshotForTest(center, RegionChunkSnapshot.fromBuilder(bc));
        RegionChunkBuilder bn = new RegionChunkBuilder(neighbour);
        bn.setTile(3208, 3469, 0, 0);
        store.installSnapshotForTest(neighbour, RegionChunkSnapshot.fromBuilder(bn));

        InspectionDumper dumper = new InspectionDumper(store, new EntityIndex(),
            new TransportIndex(), wmConfig, tmp.toFile());

        File out = dumper.dumpNearbyRegions(center);

        JsonObject root = readJson(out);
        assertEquals(center, root.get("centerRegionId").getAsInt());
        JsonArray regions = root.getAsJsonArray("regions");
        assertEquals("center + 8 neighbours", 9, regions.size());
        // The two we populated must report non-zero knownTiles.
        int populated = 0;
        for (JsonElement el : regions)
        {
            JsonObject r = el.getAsJsonObject();
            if (r.getAsJsonObject("summary").get("knownTiles").getAsInt() > 0) populated++;
        }
        assertEquals(2, populated);
    }

    @Test
    public void dumpTransportGraph_writesAllEdges() throws IOException
    {
        Path tmp = Files.createTempDirectory("inspect-transport-");
        WorldMemoryConfig wmConfig = new WorldMemoryConfig();
        TransportIndex idx = new TransportIndex();
        idx.add(sampleStaircase());
        idx.add(sampleDoor());

        InspectionDumper dumper = new InspectionDumper(new MapStore(wmConfig),
            new EntityIndex(), idx, wmConfig, tmp.toFile());

        File out = dumper.dumpTransportGraph();
        JsonObject root = readJson(out);
        JsonArray edges = root.getAsJsonArray("edges");
        assertEquals(2, edges.size());

        // Verify spec-shaped fields on the first edge.
        JsonObject e0 = edges.get(0).getAsJsonObject();
        assertTrue(e0.has("from"));
        assertTrue(e0.has("to"));
        assertTrue(e0.has("objectId"));
        assertTrue(e0.has("objectName"));
        assertTrue(e0.has("verb"));
        assertTrue(e0.has("approachTile"));
        assertTrue(e0.has("regionId"));
        assertTrue(e0.has("seenCount"));
        assertTrue(e0.has("lastSeenAt"));
        JsonObject from0 = e0.getAsJsonObject("from");
        assertTrue(from0.has("x") && from0.has("y") && from0.has("plane"));
    }

    @Test
    public void dumpEntities_writesNpcsAndObjects() throws IOException
    {
        Path tmp = Files.createTempDirectory("inspect-entities-");
        WorldMemoryConfig wmConfig = new WorldMemoryConfig();
        EntityIndex idx = new EntityIndex();
        idx.recordNpcSighting(4626, "Cook", new WorldPoint(3208, 3213, 0), 1L);
        idx.recordNpcSighting(41, "Chicken", new WorldPoint(3236, 3294, 0), 2L);
        idx.recordObjectSighting(1530, "Door", new WorldPoint(3208, 3213, 0), 3L);

        InspectionDumper dumper = new InspectionDumper(new MapStore(wmConfig),
            idx, new TransportIndex(), wmConfig, tmp.toFile());

        File out = dumper.dumpEntities();
        JsonObject root = readJson(out);
        assertEquals(2, root.getAsJsonArray("npcs").size());
        assertEquals(1, root.getAsJsonArray("objects").size());
        // Spot-check fields on one NPC entry.
        JsonObject n0 = root.getAsJsonArray("npcs").get(0).getAsJsonObject();
        assertTrue(n0.has("id") && n0.has("name") && n0.has("x") && n0.has("y")
            && n0.has("plane") && n0.has("regionId") && n0.has("seenCount") && n0.has("lastSeenAt"));
    }

    @Test
    public void planAToB_sameRegion_writesRouteAndRejections() throws IOException
    {
        Path tmp = Files.createTempDirectory("inspect-plan-");
        WorldMemoryConfig wmConfig = new WorldMemoryConfig();
        MapStore store = new MapStore(wmConfig);
        int regionId = 12850;
        // 5×1 walkable strip at plane=0 from (3208,3213) east.
        RegionChunkBuilder b = new RegionChunkBuilder(regionId);
        for (int x = 3208; x <= 3212; x++) b.setTile(x, 3213, 0, 0);
        store.installSnapshotForTest(regionId, RegionChunkSnapshot.fromBuilder(b));

        InspectionDumper dumper = new InspectionDumper(store, new EntityIndex(),
            new TransportIndex(), wmConfig, tmp.toFile());

        InspectionDumper.PlanOutcome outcome = dumper.planAToB(
            new WorldPoint(3208, 3213, 0), new WorldPoint(3212, 3213, 0));
        JsonObject root = readJson(outcome.file());
        assertEquals("success", root.get("result").getAsString());
        assertEquals(4, root.get("pathLength").getAsInt());
        assertTrue(root.has("regionsTouched"));
        assertTrue(root.has("transportEdgesUsed"));
        assertTrue(root.has("route"));
        assertTrue("rejections block present", root.has("rejections"));
        JsonObject rej = root.getAsJsonObject("rejections");
        assertTrue(rej.has("blocked"));
        assertTrue(rej.has("unknown"));
        assertTrue(rej.has("stale"));
        assertEquals("route on outcome matches path length",
            5, outcome.route().size());
        assertTrue("summary mentions tile count",
            outcome.summary().contains("4 tiles"));
    }

    @Test
    public void planAToB_crossRegion_writesNotImplementedStub() throws IOException
    {
        Path tmp = Files.createTempDirectory("inspect-plan-cross-");
        WorldMemoryConfig wmConfig = new WorldMemoryConfig();
        InspectionDumper dumper = new InspectionDumper(new MapStore(wmConfig),
            new EntityIndex(), new TransportIndex(), wmConfig, tmp.toFile());

        InspectionDumper.PlanOutcome outcome = dumper.planAToB(
            new WorldPoint(3208, 3213, 0), new WorldPoint(3093, 3245, 0));   // Draynor
        JsonObject root = readJson(outcome.file());
        assertTrue("cross-region marked deferred",
            root.get("result").getAsString().toLowerCase().contains("phase 4"));
        assertTrue(root.has("rejections"));
        assertTrue("summary mentions cross-region",
            outcome.summary().toLowerCase().contains("cross-region"));
        assertTrue(outcome.route().isEmpty());
    }

    @Test
    public void planAToB_withV2Planner_handlesCrossRegion() throws IOException
    {
        // Two-region fixture: V2 must successfully plan across the boundary.
        Path tmp = Files.createTempDirectory("inspect-plan-v2-");
        WorldMemoryConfig wmConfig = new WorldMemoryConfig();
        MapStore store = new MapStore(wmConfig);
        int regionA = net.runelite.client.plugins.recorder.worldmap.RegionIds.regionIdFor(3208, 3263);
        int regionB = net.runelite.client.plugins.recorder.worldmap.RegionIds.regionIdFor(3208, 3264);
        java.util.List<net.runelite.client.plugins.recorder.worldmap.WorldMemoryFixtures.TileSpec> tilesA
            = new java.util.ArrayList<>();
        for (int y = 3232; y <= 3263; y++)
            tilesA.add(net.runelite.client.plugins.recorder.worldmap.WorldMemoryFixtures.walkable(3208, y, 0));
        WorldMemoryFixtures.installRegion(store, regionA, tilesA);
        java.util.List<net.runelite.client.plugins.recorder.worldmap.WorldMemoryFixtures.TileSpec> tilesB
            = new java.util.ArrayList<>();
        for (int y = 3264; y <= 3270; y++)
            tilesB.add(net.runelite.client.plugins.recorder.worldmap.WorldMemoryFixtures.walkable(3208, y, 0));
        WorldMemoryFixtures.installRegion(store, regionB, tilesB);

        net.runelite.client.plugins.recorder.nav.v2.MultiRegionAStar planner
            = new net.runelite.client.plugins.recorder.nav.v2.MultiRegionAStar(
                store, new TransportIndex(), wmConfig);

        InspectionDumper dumper = new InspectionDumper(store, new EntityIndex(),
            new TransportIndex(), wmConfig, tmp.toFile(), planner);

        InspectionDumper.PlanOutcome outcome = dumper.planAToB(
            new WorldPoint(3208, 3260, 0), new WorldPoint(3208, 3268, 0));
        JsonObject root = readJson(outcome.file());

        assertEquals("success", root.get("result").getAsString());
        assertEquals(8, root.get("pathLength").getAsInt());
        // regionsTouched must include both regions (cross-region path).
        JsonArray regions = root.getAsJsonArray("regionsTouched");
        assertEquals(2, regions.size());
        assertEquals(9, root.getAsJsonArray("route").size());
        assertTrue("summary mentions cross-region tile count",
            outcome.summary().contains("9 tiles"));
        assertEquals("outcome route is non-empty so the panel can paint it",
            9, outcome.route().size());
    }

    @Test
    public void planAToB_unreachable_writesFailure() throws IOException
    {
        Path tmp = Files.createTempDirectory("inspect-plan-unreachable-");
        WorldMemoryConfig wmConfig = new WorldMemoryConfig();
        MapStore store = new MapStore(wmConfig);
        int regionId = 12850;
        // Single isolated tile — goal is somewhere in the same region but the
        // snapshot has no path to it.
        RegionChunkBuilder b = new RegionChunkBuilder(regionId);
        b.setTile(3208, 3213, 0, 0);
        store.installSnapshotForTest(regionId, RegionChunkSnapshot.fromBuilder(b));

        InspectionDumper dumper = new InspectionDumper(store, new EntityIndex(),
            new TransportIndex(), wmConfig, tmp.toFile());

        InspectionDumper.PlanOutcome outcome = dumper.planAToB(
            new WorldPoint(3208, 3213, 0), new WorldPoint(3215, 3213, 0));
        JsonObject root = readJson(outcome.file());
        assertEquals("failure", root.get("result").getAsString());
        assertTrue("summary mentions no route",
            outcome.summary().toLowerCase().contains("no route"));
    }

    // dumpCorridor_writesPerRowAnalysis + dumpReadiness_writesReadinessFields
    // removed: dumper methods deleted with RouteReadiness per spec §8 THROW.

    private static JsonObject readJson(File f) throws IOException
    {
        return JsonParser.parseString(Files.readString(f.toPath())).getAsJsonObject();
    }

    private static TransportEdge sampleEdgeInRegion(int regionId)
    {
        WorldPoint from = new WorldPoint(3208, 3216, 0);
        WorldPoint to = new WorldPoint(3208, 3217, 0);
        return new TransportEdge(from, to, 1530, "Door", "Open",
            56, 18, "GAME_OBJECT_FIRST_OPTION", from, regionId, 1, 1_700_000_000_000L, 600L);
    }

    private static TransportEdge otherRegionEdge()
    {
        WorldPoint from = new WorldPoint(3093, 3245, 0);
        WorldPoint to = new WorldPoint(3093, 3246, 0);
        return new TransportEdge(from, to, 1530, "Door", "Open",
            56, 18, "GAME_OBJECT_FIRST_OPTION", from, from.getRegionID(), 1, 1L, 600L);
    }

    private static TransportEdge sampleStaircase()
    {
        WorldPoint from = new WorldPoint(3205, 3209, 0);
        WorldPoint to = new WorldPoint(3205, 3209, 1);
        return new TransportEdge(from, to, 16671, "Staircase", "Climb-up",
            53, 14, "GAME_OBJECT_FIRST_OPTION", from, from.getRegionID(), 2, 1L, 1200L);
    }

    private static TransportEdge sampleDoor()
    {
        WorldPoint from = new WorldPoint(3236, 3293, 0);
        WorldPoint to = new WorldPoint(3236, 3294, 0);
        return new TransportEdge(from, to, 1560, "Gate", "Open",
            56, 18, "GAME_OBJECT_FIRST_OPTION", from, from.getRegionID(), 5, 2L, 800L);
    }
}
