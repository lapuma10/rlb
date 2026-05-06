package net.runelite.client.plugins.recorder.worldmap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;

/**
 * JSON read/write helpers for the WorldMemory storage layer.
 *
 * <p>Persistence layout:
 * <pre>
 *   &lt;rootDir&gt;/regions/&lt;regionId&gt;.json    — tile collision + objects
 *   &lt;rootDir&gt;/entities/&lt;regionId&gt;.json   — NPC sightings
 * </pre>
 *
 * <p>Schema: see WORLDMEMORY-SPEC.md "Storage format" section.
 * v1 readers ignore unknown fields (Gson's default lenient-on-read behaviour).
 * Forward-compat: any future field added to the JSON is silently ignored
 * when reading with this code.
 */
@Slf4j
public final class MapStoreIO
{
    private static final int SCHEMA_V1 = 1;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private MapStoreIO() {}

    // ──────────────────────────────────────────────────────────────────────────
    // Region (tiles + objects)
    // ──────────────────────────────────────────────────────────────────────────

    public static void writeRegion(File rootDir, RegionChunkSnapshot snap)
    {
        File regionsDir = new File(rootDir, "regions");
        regionsDir.mkdirs();
        File out = new File(regionsDir, snap.regionId() + ".json");

        JsonObject root = new JsonObject();
        root.addProperty("schema", SCHEMA_V1);
        root.addProperty("regionId", snap.regionId());
        root.addProperty("gameRevision", snap.gameRevision());
        root.addProperty("lastScrapedAt", snap.lastScrapedAt());

        JsonArray tiles = new JsonArray();
        for (RegionChunkSnapshot.TileEntry t : snap.tiles())
        {
            JsonObject tj = new JsonObject();
            tj.addProperty("x", t.x);
            tj.addProperty("y", t.y);
            tj.addProperty("plane", t.plane);
            tj.addProperty("movement", t.movement);
            tj.addProperty("los", 0);   // reserved for v2; always 0 in v1
            tiles.add(tj);
        }
        root.add("tiles", tiles);

        JsonArray objects = new JsonArray();
        for (EntitySighting o : snap.objects())
        {
            JsonObject oj = new JsonObject();
            oj.addProperty("id", o.id);
            oj.addProperty("name", o.name);
            oj.addProperty("x", o.lastTile.getX());
            oj.addProperty("y", o.lastTile.getY());
            oj.addProperty("plane", o.lastTile.getPlane());
            oj.addProperty("seenCount", o.seenCount);
            oj.addProperty("lastSeenAt", o.lastSeenAt);
            objects.add(oj);
        }
        root.add("objects", objects);

        try
        {
            Files.writeString(out.toPath(), GSON.toJson(root));
        }
        catch (IOException e)
        {
            log.warn("worldmap: failed to write region {} to {}: {}",
                snap.regionId(), out, e.getMessage());
        }
    }

    /** Read the persisted region file. Returns an empty snapshot (0 tiles) if
     *  the file does not exist or cannot be parsed — the caller can detect this
     *  via {@code tiles().isEmpty()}. */
    public static RegionChunkSnapshot readRegion(File rootDir, int regionId)
    {
        File f = new File(new File(rootDir, "regions"), regionId + ".json");
        if (!f.exists())
        {
            return RegionChunkSnapshot.empty(regionId);
        }
        try (Reader r = Files.newBufferedReader(f.toPath()))
        {
            JsonElement el = GSON.fromJson(r, JsonElement.class);
            if (el == null || !el.isJsonObject())
            {
                log.warn("worldmap: region {} JSON is not an object, returning empty", regionId);
                return RegionChunkSnapshot.empty(regionId);
            }
            return regionFromJson(el.getAsJsonObject(), regionId);
        }
        catch (Exception e)
        {
            log.warn("worldmap: failed to read region {}: {}", regionId, e.getMessage());
            return RegionChunkSnapshot.empty(regionId);
        }
    }

    private static RegionChunkSnapshot regionFromJson(JsonObject root, int regionId)
    {
        int id = root.has("regionId") ? root.get("regionId").getAsInt() : regionId;
        int rev = root.has("gameRevision") ? root.get("gameRevision").getAsInt() : 0;
        long ts = root.has("lastScrapedAt") ? root.get("lastScrapedAt").getAsLong() : 0L;

        RegionChunkBuilder b = new RegionChunkBuilder(id);
        b.gameRevision = rev;
        b.lastScrapedAt = ts;

        if (root.has("tiles") && root.get("tiles").isJsonArray())
        {
            for (JsonElement te : root.getAsJsonArray("tiles"))
            {
                JsonObject tj = te.getAsJsonObject();
                int x = tj.get("x").getAsInt();
                int y = tj.get("y").getAsInt();
                int plane = tj.get("plane").getAsInt();
                int movement = tj.get("movement").getAsInt();
                // "los" field ignored in v1 — LOS bits already in movement
                b.setTile(x, y, plane, movement);
            }
        }

        if (root.has("objects") && root.get("objects").isJsonArray())
        {
            for (JsonElement oe : root.getAsJsonArray("objects"))
            {
                JsonObject oj = oe.getAsJsonObject();
                int objId = oj.get("id").getAsInt();
                String name = oj.has("name") ? oj.get("name").getAsString() : "";
                int ox = oj.get("x").getAsInt();
                int oy = oj.get("y").getAsInt();
                int oplane = oj.get("plane").getAsInt();
                int seenCount = oj.has("seenCount") ? oj.get("seenCount").getAsInt() : 1;
                long lastSeenAt = oj.has("lastSeenAt") ? oj.get("lastSeenAt").getAsLong() : 0L;
                WorldPoint tile = new WorldPoint(ox, oy, oplane);
                b.objects.put(RegionChunkBuilder.packObjKey(objId, tile),
                    new EntitySighting(EntitySighting.Kind.OBJECT, objId, name,
                        tile, seenCount, lastSeenAt));
            }
        }

        return RegionChunkSnapshot.fromBuilder(b);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Entities (NPC sightings)
    // ──────────────────────────────────────────────────────────────────────────

    public static void writeEntities(File rootDir, int regionId, List<EntitySighting> npcs)
    {
        File entitiesDir = new File(rootDir, "entities");
        entitiesDir.mkdirs();
        File out = new File(entitiesDir, regionId + ".json");

        JsonObject root = new JsonObject();
        root.addProperty("schema", SCHEMA_V1);
        root.addProperty("regionId", regionId);

        JsonArray arr = new JsonArray();
        for (EntitySighting s : npcs)
        {
            JsonObject nj = new JsonObject();
            nj.addProperty("id", s.id);
            nj.addProperty("name", s.name);
            JsonObject lt = new JsonObject();
            lt.addProperty("x", s.lastTile.getX());
            lt.addProperty("y", s.lastTile.getY());
            lt.addProperty("plane", s.lastTile.getPlane());
            nj.add("lastTile", lt);
            nj.addProperty("seenCount", s.seenCount);
            nj.addProperty("lastSeenAt", s.lastSeenAt);
            arr.add(nj);
        }
        root.add("npcs", arr);

        try
        {
            Files.writeString(out.toPath(), GSON.toJson(root));
        }
        catch (IOException e)
        {
            log.warn("worldmap: failed to write entities for region {} to {}: {}",
                regionId, out, e.getMessage());
        }
    }

    /** Read persisted NPC sightings for {@code regionId}. Returns empty list
     *  if the file does not exist or cannot be parsed. */
    public static List<EntitySighting> readEntities(File rootDir, int regionId)
    {
        File f = new File(new File(rootDir, "entities"), regionId + ".json");
        if (!f.exists()) return Collections.emptyList();
        try (Reader r = Files.newBufferedReader(f.toPath()))
        {
            JsonElement el = GSON.fromJson(r, JsonElement.class);
            if (el == null || !el.isJsonObject()) return Collections.emptyList();
            JsonObject root = el.getAsJsonObject();
            if (!root.has("npcs") || !root.get("npcs").isJsonArray())
                return Collections.emptyList();
            List<EntitySighting> out = new ArrayList<>();
            for (JsonElement ne : root.getAsJsonArray("npcs"))
            {
                JsonObject nj = ne.getAsJsonObject();
                int id = nj.get("id").getAsInt();
                String name = nj.has("name") ? nj.get("name").getAsString() : "";
                JsonObject lt = nj.getAsJsonObject("lastTile");
                WorldPoint tile = new WorldPoint(
                    lt.get("x").getAsInt(), lt.get("y").getAsInt(), lt.get("plane").getAsInt());
                int seenCount = nj.has("seenCount") ? nj.get("seenCount").getAsInt() : 1;
                long lastSeenAt = nj.has("lastSeenAt") ? nj.get("lastSeenAt").getAsLong() : 0L;
                out.add(new EntitySighting(EntitySighting.Kind.NPC, id, name,
                    tile, seenCount, lastSeenAt));
            }
            return Collections.unmodifiableList(out);
        }
        catch (Exception e)
        {
            log.warn("worldmap: failed to read entities for region {}: {}", regionId, e.getMessage());
            return Collections.emptyList();
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Fixture loading (test-only; reads from classpath)
    // ──────────────────────────────────────────────────────────────────────────

    /** Read a region fixture from the classpath at
     *  {@code /worldmap/fixtures/<resourceName>}. Used by Phase 5 fixture tests.
     *  Returns an empty snapshot (regionId=0) on failure — the test should
     *  assert non-empty to catch missing resources. */
    public RegionChunkSnapshot readFixture(String resourceName)
    {
        try (InputStream is = getClass().getResourceAsStream(
                "/worldmap/fixtures/" + resourceName))
        {
            if (is == null)
            {
                log.warn("worldmap: fixture not found on classpath: /worldmap/fixtures/{}",
                    resourceName);
                return RegionChunkSnapshot.empty(0);
            }
            try (Reader r = new InputStreamReader(is, StandardCharsets.UTF_8))
            {
                JsonElement el = GSON.fromJson(r, JsonElement.class);
                if (el == null || !el.isJsonObject())
                {
                    log.warn("worldmap: fixture {} is not a JSON object", resourceName);
                    return RegionChunkSnapshot.empty(0);
                }
                JsonObject root = el.getAsJsonObject();
                int regionId = root.has("regionId") ? root.get("regionId").getAsInt() : 0;
                return regionFromJson(root, regionId);
            }
        }
        catch (Exception e)
        {
            log.warn("worldmap: failed to read fixture {}: {}", resourceName, e.getMessage());
            return RegionChunkSnapshot.empty(0);
        }
    }
}
