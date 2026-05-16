package net.runelite.client.plugins.recorder.nav.v2.qc;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.nav.v2.qc.contracts.ExecutorTickResult;

/** JSONL recorder for per-tick {@link RouteTrace} entries. One run's
 *  traces share a {@code runId} (the file name); each line is a
 *  single tick.
 *
 *  <p>Default output dir: {@code ~/.runelite/nav-traces/<runId>.jsonl}.
 *  Tests may pass an explicit output file to keep traces inside the
 *  test working directory.
 *
 *  <p>JSON shape is hand-rolled (no external dep) and mirrors the
 *  per-tick log format in spec §5. Field order is stable so
 *  {@link RouteReplayValidator} can re-parse without a real JSON parser
 *  during Phase 1 — a real parser can be added later if needed. */
public final class RouteTraceRecorder implements AutoCloseable
{
    /** Default trace directory (per Lane-6 plan Task 2). */
    public static final File DEFAULT_DIR = new File(
        System.getProperty("user.home") + "/.runelite/nav-traces");

    private final Writer out;
    private final List<RouteTrace> inMemory = new ArrayList<>();
    private final boolean alsoRetainInMemory;

    /** Open a JSONL trace file. Creates parent directories. */
    public static RouteTraceRecorder forRunId(String runId) throws IOException
    {
        File f = new File(DEFAULT_DIR, runId + ".jsonl");
        return forFile(f, true);
    }

    public static RouteTraceRecorder forFile(File f, boolean alsoRetainInMemory) throws IOException
    {
        File parent = f.getParentFile();
        if (parent != null && !parent.isDirectory()) Files.createDirectories(parent.toPath());
        Writer w = new BufferedWriter(
            Files.newBufferedWriter(f.toPath(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING));
        return new RouteTraceRecorder(w, alsoRetainInMemory);
    }

    /** In-memory only — no file output. Used by acceptance tests that
     *  inspect traces directly without touching the filesystem. */
    public static RouteTraceRecorder inMemoryOnly()
    {
        return new RouteTraceRecorder(null, true);
    }

    private RouteTraceRecorder(Writer out, boolean alsoRetainInMemory)
    {
        this.out = out;
        this.alsoRetainInMemory = alsoRetainInMemory;
    }

    /** Emit one trace row. */
    public void record(RouteTrace t) throws IOException
    {
        if (alsoRetainInMemory) inMemory.add(t);
        if (out != null)
        {
            out.write(toJson(t));
            out.write('\n');
            out.flush();
        }
    }

    /** Convenience: build a {@link RouteTrace} from an
     *  {@link ExecutorTickResult} + the extra debug fields Lane 5 must
     *  expose via its per-tick log (candidates / chosen / sidestep). */
    public RouteTrace synthesizeFromTick(ExecutorTickResult tick,
                                         List<WorldPoint> candidatesConsidered,
                                         List<RouteTrace.Rejected> candidatesRejected,
                                         Optional<WorldPoint> candidateChosen,
                                         boolean sidestepUsed)
    {
        return new RouteTrace(
            tick.debugTraceId(),
            System.currentTimeMillis(),
            tick.playerAt().orElse(null),
            tick.currentWaypoint(),
            tick.currentTransport(),
            candidatesConsidered,
            candidatesRejected,
            candidateChosen,
            sidestepUsed,
            tick.result(),
            tick.replanReason());
    }

    public List<RouteTrace> recorded() { return List.copyOf(inMemory); }

    @Override
    public void close() throws IOException
    {
        if (out != null) out.close();
    }

    // ---- minimal JSON encoder (no external dep) ----

    /** Encode one trace row. Field order is stable; per-field encoding
     *  handles strings (escaped), Optional, WorldPoint, enum, list. */
    static String toJson(RouteTrace t)
    {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        kv(sb, "tickId", quote(t.tickId)); sb.append(',');
        kv(sb, "tickEpochMs", String.valueOf(t.tickEpochMs)); sb.append(',');
        kv(sb, "playerAt", encodeWp(t.playerAt)); sb.append(',');
        kv(sb, "currentWaypoint", t.currentWaypoint.isPresent()
            ? encodeWp(t.currentWaypoint.get().target()) : "null"); sb.append(',');
        kv(sb, "currentTransport", t.currentTransport.isPresent()
            ? quote(t.currentTransport.get().type().name()) : "null"); sb.append(',');
        kv(sb, "candidatesConsidered", encodeWpList(t.candidatesConsidered)); sb.append(',');
        kv(sb, "candidatesRejected", encodeRejectedList(t.candidatesRejected)); sb.append(',');
        kv(sb, "candidateChosen", t.candidateChosen.map(RouteTraceRecorder::encodeWp).orElse("null")); sb.append(',');
        kv(sb, "sidestepUsed", String.valueOf(t.sidestepUsed)); sb.append(',');
        kv(sb, "result", quote(t.result.name())); sb.append(',');
        kv(sb, "replanReason", t.replanReason.map(r -> quote(r.name())).orElse("null"));
        sb.append('}');
        return sb.toString();
    }

    private static void kv(StringBuilder sb, String k, String v)
    {
        sb.append(quote(k)).append(':').append(v);
    }

    private static String quote(String s)
    {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);
            switch (c)
            {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static String encodeWp(WorldPoint p)
    {
        if (p == null) return "null";
        return "{\"x\":" + p.getX() + ",\"y\":" + p.getY() + ",\"plane\":" + p.getPlane() + "}";
    }

    private static String encodeWpList(List<WorldPoint> ps)
    {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < ps.size(); i++)
        {
            if (i > 0) sb.append(',');
            sb.append(encodeWp(ps.get(i)));
        }
        sb.append(']');
        return sb.toString();
    }

    private static String encodeRejectedList(List<RouteTrace.Rejected> rs)
    {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < rs.size(); i++)
        {
            if (i > 0) sb.append(',');
            sb.append("{\"tile\":").append(encodeWp(rs.get(i).tile))
              .append(",\"reason\":").append(quote(rs.get(i).reason)).append('}');
        }
        sb.append(']');
        return sb.toString();
    }
}
