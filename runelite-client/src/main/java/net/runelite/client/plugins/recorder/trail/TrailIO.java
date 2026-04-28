package net.runelite.client.plugins.recorder.trail;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.runelite.api.coords.WorldPoint;

/** JSON serialisation for {@link Trail}. Schema:
 *
 *  <pre>{@code
 *  {
 *    "version": 1,
 *    "name": "lumby-bank-to-pen",
 *    "recordedAt": 1714247000000,
 *    "events": [
 *      {"t":"TILE","ms":0,"x":3208,"y":3220,"p":2},
 *      {"t":"TRANSPORT","ms":12000,"x":3205,"y":3229,"p":2,
 *       "option":"Climb-down","target":"Staircase","targetId":16671,
 *       "targetKind":"GameObject","actionId":3,"param0":53,"param1":14,
 *       "menuRowsAtClick":["Climb-down Staircase","Cancel"]}
 *    ]
 *  }
 *  }</pre>
 *
 *  <p>Field-by-field hand-marshal rather than Gson reflection because
 *  sealed-interface unions don't round-trip cleanly through Gson without
 *  a TypeAdapterFactory; the schema is small and stable enough that an
 *  if-tree is the simpler bet. */
public final class TrailIO
{
    private static final int CURRENT_VERSION = 1;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Gson GSON_PRETTY = new GsonBuilder()
        .setPrettyPrinting().disableHtmlEscaping().create();

    private TrailIO() {}

    public static void write(Trail trail, Writer out)
    {
        try { GSON.toJson(toJson(trail), out); }
        catch (Throwable t) { throw new UncheckedIOException(new IOException(t)); }
    }

    public static String writeToString(Trail trail)
    {
        StringWriter sw = new StringWriter();
        write(trail, sw);
        return sw.toString();
    }

    public static void writeFile(Trail trail, Path file) throws IOException
    {
        Files.createDirectories(file.getParent());
        try (Writer w = Files.newBufferedWriter(file)) { write(trail, w); }
    }

    public static Trail read(Reader in)
    {
        JsonElement root = GSON.fromJson(in, JsonElement.class);
        if (root == null || !root.isJsonObject())
            throw new IllegalArgumentException("Trail JSON not an object");
        return fromJson(root.getAsJsonObject());
    }

    public static Trail readString(String s) { return read(new StringReader(s)); }

    public static Trail readFile(Path file) throws IOException
    {
        try (Reader r = Files.newBufferedReader(file)) { return read(r); }
    }

    // ──────── marshalling helpers ────────

    private static JsonObject toJson(Trail trail)
    {
        JsonObject root = new JsonObject();
        root.addProperty("version", CURRENT_VERSION);
        root.addProperty("name", trail.name());
        root.addProperty("recordedAt", trail.recordedAt());
        JsonArray events = new JsonArray();
        for (TrailEvent e : trail.events()) events.add(eventToJson(e));
        root.add("events", events);
        return root;
    }

    private static JsonObject eventToJson(TrailEvent e)
    {
        JsonObject o = new JsonObject();
        o.addProperty("t", e.kind());
        o.addProperty("ms", e.msSinceStart());
        WorldPoint tile = e.tile();
        o.addProperty("x", tile.getX());
        o.addProperty("y", tile.getY());
        o.addProperty("p", tile.getPlane());
        if (e instanceof TrailEvent.Transport tr)
        {
            o.addProperty("option", tr.option());
            o.addProperty("target", tr.target());
            o.addProperty("targetId", tr.targetId());
            o.addProperty("targetKind", tr.targetKind());
            o.addProperty("actionId", tr.actionId());
            o.addProperty("param0", tr.param0());
            o.addProperty("param1", tr.param1());
            JsonArray rows = new JsonArray();
            for (String r : tr.menuRowsAtClick()) rows.add(r);
            o.add("menuRowsAtClick", rows);
        }
        return o;
    }

    private static Trail fromJson(JsonObject root)
    {
        if (!root.has("name"))
            throw new IllegalArgumentException("Trail missing 'name'");
        String name = root.get("name").getAsString();
        long recordedAt = root.has("recordedAt") ? root.get("recordedAt").getAsLong() : 0L;
        List<TrailEvent> events = new ArrayList<>();
        if (root.has("events") && root.get("events").isJsonArray())
        {
            for (JsonElement el : root.getAsJsonArray("events"))
            {
                events.add(eventFromJson(el.getAsJsonObject()));
            }
        }
        return new Trail(name, recordedAt, Collections.unmodifiableList(events));
    }

    private static TrailEvent eventFromJson(JsonObject o)
    {
        String t = o.get("t").getAsString();
        long ms = o.get("ms").getAsLong();
        WorldPoint tile = new WorldPoint(
            o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("p").getAsInt());
        if ("TILE".equals(t)) return new TrailEvent.Tile(ms, tile);
        if ("TRANSPORT".equals(t))
        {
            List<String> rows = new ArrayList<>();
            if (o.has("menuRowsAtClick") && o.get("menuRowsAtClick").isJsonArray())
            {
                for (JsonElement el : o.getAsJsonArray("menuRowsAtClick"))
                {
                    rows.add(el.getAsString());
                }
            }
            return new TrailEvent.Transport(ms, tile,
                o.has("option")     ? o.get("option").getAsString()     : "",
                o.has("target")     ? o.get("target").getAsString()     : "",
                o.has("targetId")   ? o.get("targetId").getAsInt()      : 0,
                o.has("targetKind") ? o.get("targetKind").getAsString() : "",
                o.has("actionId")   ? o.get("actionId").getAsInt()      : 0,
                o.has("param0")     ? o.get("param0").getAsInt()        : 0,
                o.has("param1")     ? o.get("param1").getAsInt()        : 0,
                Collections.unmodifiableList(rows));
        }
        throw new IllegalArgumentException("Unknown event type: " + t);
    }
}
