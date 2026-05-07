package net.runelite.client.plugins.recorder.worldmap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;

/** JSON read/write for {@link TransportEdge} persistence.
 *
 *  <p>Path: {@code <rootDir>/transports.json} — single top-level file
 *  rather than per-region. The transport graph is small (a few hundred
 *  edges per loaded world even after weeks of capture) and the planner
 *  expects to traverse the whole graph cross-region, so a single index
 *  is simpler and more cache-friendly than splitting by region.
 *
 *  <p>Schema-versioned (v1). Future v2 readers ignore unknown fields
 *  via Gson's default lenient behaviour. */
@Slf4j
public final class TransportIO
{
    private static final int SCHEMA_V1 = 1;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private TransportIO() {}

    public static File transportsFile(File rootDir)
    {
        return new File(rootDir, "transports.json");
    }

    /** Persist the full edge set to {@code <rootDir>/transports.json}.
     *  Overwrites any existing file. Logs a warning on IO failure but
     *  does not throw — flushes happen on a daemon thread and a
     *  transient FS failure must not crash the recorder. */
    public static void writeAll(File rootDir, Collection<TransportEdge> edges)
    {
        if (rootDir == null) return;
        rootDir.mkdirs();
        File out = transportsFile(rootDir);

        JsonObject root = new JsonObject();
        root.addProperty("schema", SCHEMA_V1);

        JsonArray arr = new JsonArray();
        if (edges != null)
        {
            for (TransportEdge e : edges)
            {
                if (e != null) arr.add(toJson(e));
            }
        }
        root.add("edges", arr);

        try
        {
            Files.writeString(out.toPath(), GSON.toJson(root));
        }
        catch (IOException e)
        {
            log.warn("worldmap: failed to write transports.json: {}", e.getMessage());
        }
    }

    /** Read the persisted edge set. Returns an empty list if the file
     *  does not exist or cannot be parsed — the caller treats both
     *  cases the same: an empty TransportIndex is the cold-start
     *  shape. */
    public static List<TransportEdge> readAll(File rootDir)
    {
        File f = transportsFile(rootDir);
        if (!f.exists()) return List.of();
        try (Reader r = Files.newBufferedReader(f.toPath()))
        {
            JsonElement el = GSON.fromJson(r, JsonElement.class);
            if (el == null || !el.isJsonObject())
            {
                log.warn("worldmap: transports.json is not an object, returning empty");
                return List.of();
            }
            JsonObject root = el.getAsJsonObject();
            if (!root.has("edges") || !root.get("edges").isJsonArray())
            {
                return List.of();
            }
            List<TransportEdge> out = new ArrayList<>();
            for (JsonElement ee : root.getAsJsonArray("edges"))
            {
                if (!ee.isJsonObject()) continue;
                TransportEdge edge = fromJson(ee.getAsJsonObject());
                if (edge != null) out.add(edge);
            }
            return out;
        }
        catch (Exception e)
        {
            log.warn("worldmap: failed to read transports.json: {}", e.getMessage());
            return List.of();
        }
    }

    private static JsonObject toJson(TransportEdge e)
    {
        JsonObject o = new JsonObject();
        o.addProperty("fromX", e.fromTile().getX());
        o.addProperty("fromY", e.fromTile().getY());
        o.addProperty("fromPlane", e.fromTile().getPlane());
        o.addProperty("toX", e.toTile().getX());
        o.addProperty("toY", e.toTile().getY());
        o.addProperty("toPlane", e.toTile().getPlane());
        o.addProperty("objectId", e.objectId());
        o.addProperty("objectName", e.objectName());
        o.addProperty("verb", e.verb());
        o.addProperty("param0", e.param0());
        o.addProperty("param1", e.param1());
        o.addProperty("targetKind", e.targetKind());
        o.addProperty("approachX", e.approachTile().getX());
        o.addProperty("approachY", e.approachTile().getY());
        o.addProperty("approachPlane", e.approachTile().getPlane());
        o.addProperty("regionId", e.regionId());
        o.addProperty("seenCount", e.seenCount());
        o.addProperty("lastSeenAtMs", e.lastSeenAtMs());
        o.addProperty("observedDurationMs", e.observedDurationMs());
        return o;
    }

    private static TransportEdge fromJson(JsonObject o)
    {
        try
        {
            WorldPoint from = new WorldPoint(
                o.get("fromX").getAsInt(),
                o.get("fromY").getAsInt(),
                o.get("fromPlane").getAsInt());
            WorldPoint to = new WorldPoint(
                o.get("toX").getAsInt(),
                o.get("toY").getAsInt(),
                o.get("toPlane").getAsInt());
            WorldPoint approach = o.has("approachX")
                ? new WorldPoint(
                    o.get("approachX").getAsInt(),
                    o.get("approachY").getAsInt(),
                    o.get("approachPlane").getAsInt())
                : from;
            return new TransportEdge(
                from, to,
                o.get("objectId").getAsInt(),
                o.has("objectName") ? o.get("objectName").getAsString() : "",
                o.get("verb").getAsString(),
                o.has("param0") ? o.get("param0").getAsInt() : 0,
                o.has("param1") ? o.get("param1").getAsInt() : 0,
                o.has("targetKind") ? o.get("targetKind").getAsString() : "",
                approach,
                o.has("regionId") ? o.get("regionId").getAsInt() : from.getRegionID(),
                o.has("seenCount") ? o.get("seenCount").getAsInt() : 1,
                o.has("lastSeenAtMs") ? o.get("lastSeenAtMs").getAsLong() : 0L,
                o.has("observedDurationMs") ? o.get("observedDurationMs").getAsLong() : 0L);
        }
        catch (Exception ex)
        {
            log.warn("worldmap: dropping malformed transport edge: {}", ex.getMessage());
            return null;
        }
    }
}
