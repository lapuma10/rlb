package net.runelite.client.plugins.recorder.scripts;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.RuneLite;
import net.runelite.client.plugins.recorder.scripts.RooftopAgilityScript.RooftopCourse;
import net.runelite.client.plugins.recorder.scripts.RooftopAgilityScript.RooftopCourseId;
import net.runelite.client.plugins.recorder.scripts.RooftopAgilityScript.RooftopNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Loads rooftop-agility course definitions from JSON files in
 *  {@code ~/.runelite/recorder/rooftops/}. Each file describes a single
 *  course: id (matching a {@link RooftopCourseId}), label, obstacles, and
 *  tile zones. Lets the user add new courses without recompiling — drop
 *  a file, restart the client, the dropdown picks it up.
 *
 *  <p>Schema (lenient — unknown fields ignored, missing optionals get
 *  sensible defaults):
 *  <pre>{@code
 *  {
 *    "id": "AL_KHARID",
 *    "label": "Al-Kharid Rooftop",
 *    "agilityLevel": 20,
 *    "scanRadius": 16,                    // optional, default 14
 *    "approachTiles":  [[3273, 3195, 0], [3274, 3195, 0]],
 *    "startTiles":     [[3273, 3195, 0]], // subset of approachTiles
 *    "fallTiles":      [],
 *    "lapEndTiles":    [[3304, 3162, 3]],
 *    "validTiles":     [[...]],           // optional, auto-computed if missing
 *    "obstacles": [
 *      { "label": "Rough wall", "objectId": 11633, "action": "Climb-up",
 *        "objectTiles": [[3272, 3195, 0]],
 *        "stageTiles":  [[3272, 3195, 0], [3273, 3195, 0]],
 *        "timeoutMs": 4000 },
 *      ...
 *    ]
 *  }
 *  }</pre>
 *
 *  <p>Tile entries are 3-element JSON arrays of {@code [x, y, plane]}. */
public final class RooftopCourseLoader
{
    private static final Logger log = LoggerFactory.getLogger(RooftopCourseLoader.class);

    /** Directory scanned for {@code *.json} files. Created lazily — if it
     *  doesn't exist, the loader silently returns an empty map. */
    public static final Path ROOFTOPS_DIR =
        Paths.get(RuneLite.RUNELITE_DIR.getAbsolutePath(), "recorder", "rooftops");

    private RooftopCourseLoader() {}

    /** Load every {@code *.json} file under {@link #ROOFTOPS_DIR}. Files
     *  that fail to parse or fail {@link RooftopAgilityScript#validateCourse}
     *  are logged and skipped — they don't block other files. Returns a
     *  map keyed by the {@code id} field of each file. */
    public static Map<RooftopCourseId, RooftopCourse> loadAll()
    {
        Map<RooftopCourseId, RooftopCourse> out = new EnumMap<>(RooftopCourseId.class);
        if (!Files.isDirectory(ROOFTOPS_DIR))
        {
            log.debug("rooftop-courses: dir {} does not exist; no JSON courses loaded",
                ROOFTOPS_DIR);
            return out;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(ROOFTOPS_DIR, "*.json"))
        {
            for (Path file : stream)
            {
                // Convention: filenames starting with '_' are templates /
                // documentation and are skipped — lets us ship an example
                // file in this directory without it registering as a
                // (broken) course definition.
                String name = file.getFileName().toString();
                if (name.startsWith("_")) continue;
                try
                {
                    RooftopCourse c = parse(file);
                    RooftopAgilityScript.validateCourse(c);
                    out.put(c.id, c);
                    log.info("rooftop-courses: loaded {} from {} ({} obstacles)",
                        c.id, file.getFileName(), c.nodes.size());
                }
                catch (Throwable t)
                {
                    log.warn("rooftop-courses: failed to load {} — {}", file, t.toString());
                }
            }
        }
        catch (IOException e)
        {
            log.warn("rooftop-courses: failed to list {} — {}", ROOFTOPS_DIR, e.toString());
        }
        return out;
    }

    private static RooftopCourse parse(Path file) throws IOException
    {
        JsonObject root;
        try (Reader r = Files.newBufferedReader(file))
        {
            root = JsonParser.parseReader(r).getAsJsonObject();
        }
        catch (JsonSyntaxException e)
        {
            throw new IOException("bad JSON: " + e.getMessage(), e);
        }

        RooftopCourseId id = RooftopCourseId.valueOf(requireString(root, "id").trim());
        String label       = requireString(root, "label");
        int level          = root.has("agilityLevel") ? root.get("agilityLevel").getAsInt() : 1;
        int scanRadius     = root.has("scanRadius") ? root.get("scanRadius").getAsInt()
                                                    : RooftopAgilityScript.DEFAULT_OBSTACLE_SCAN_RADIUS;

        Set<WorldPoint> approach = parseTiles(root, "approachTiles");
        Set<WorldPoint> start    = parseTiles(root, "startTiles");
        Set<WorldPoint> fall     = root.has("fallTiles") ? parseTiles(root, "fallTiles") : Set.of();
        Set<WorldPoint> lapEnd   = parseTiles(root, "lapEndTiles");

        if (!root.has("obstacles") || !root.get("obstacles").isJsonArray())
        {
            throw new IOException("missing or non-array 'obstacles'");
        }
        JsonArray nodesArr = root.getAsJsonArray("obstacles");
        List<RooftopNode> nodes = new ArrayList<>(nodesArr.size());
        for (int i = 0; i < nodesArr.size(); i++)
        {
            JsonObject n = nodesArr.get(i).getAsJsonObject();
            String nlabel    = requireString(n, "label");
            int objectId     = n.get("objectId").getAsInt();
            String action    = requireString(n, "action");
            long timeoutMs   = n.has("timeoutMs") ? n.get("timeoutMs").getAsLong() : 5_000L;
            Set<WorldPoint> objectTiles = parseTiles(n, "objectTiles");
            Set<WorldPoint> stageTiles  = parseTiles(n, "stageTiles");
            nodes.add(new RooftopNode(nlabel, objectId, action, objectTiles, stageTiles, timeoutMs));
        }

        // validTiles defaults to the union of every stage's tiles + lapEnd —
        // matches the hardcoded Draynor convention, so JSON files rarely
        // need to set it explicitly.
        Set<WorldPoint> valid;
        if (root.has("validTiles"))
        {
            valid = parseTiles(root, "validTiles");
        }
        else
        {
            valid = new HashSet<>();
            for (RooftopNode n : nodes) valid.addAll(n.stageTiles);
            valid.addAll(lapEnd);
        }

        return new RooftopCourse(id, label, level,
            approach, start, valid, fall, lapEnd, nodes, scanRadius);
    }

    private static String requireString(JsonObject o, String key) throws IOException
    {
        JsonElement el = o.get(key);
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive())
            throw new IOException("missing string field '" + key + "'");
        return el.getAsString();
    }

    private static Set<WorldPoint> parseTiles(JsonObject o, String key) throws IOException
    {
        JsonElement el = o.get(key);
        if (el == null || el.isJsonNull()) return Set.of();
        if (!el.isJsonArray())
            throw new IOException("'" + key + "' must be an array of [x,y,plane] triples");
        JsonArray arr = el.getAsJsonArray();
        Set<WorldPoint> out = new HashSet<>(arr.size());
        for (int i = 0; i < arr.size(); i++)
        {
            JsonElement entry = arr.get(i);
            if (!entry.isJsonArray())
                throw new IOException("'" + key + "[" + i + "]' must be [x,y,plane]");
            JsonArray xyp = entry.getAsJsonArray();
            if (xyp.size() != 3)
                throw new IOException("'" + key + "[" + i + "]' must have 3 elements (x, y, plane)");
            out.add(new WorldPoint(xyp.get(0).getAsInt(), xyp.get(1).getAsInt(), xyp.get(2).getAsInt()));
        }
        return out;
    }
}
