package net.runelite.client.plugins.recorder.nav.v2.qc;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.nav.v2.qc.contracts.WorldSnapshot;

/** Reads a JSONL trace and re-runs Lane-3's {@code RouteValidator} on
 *  every step transition. Output: {@link ReplayValidationResult} with
 *  per-tick OK/FAIL.
 *
 *  <p>Phase-1 stub: until Lane 3 ships {@code RouteValidator}, this
 *  class performs the *adjacency + plane* checks itself (cheaper than
 *  full collision validation). A {@code wireValidator(...)} static
 *  hook lets Lane 3 swap in the real validator at Phase 2.7 hand-off.
 *
 *  <p>Lane-3 plug-in shape: a {@link StepValidator} bi-function that
 *  takes (prevTile, nextTile, snapshot) and returns OK/FAIL+reason.
 *  Default impl checks chebyshev = 1 and same plane only — enough to
 *  catch the most common bugs (teleport-from-walk-leg, plane warp). */
public final class RouteReplayValidator
{
    private static volatile StepValidator VALIDATOR = RouteReplayValidator::defaultStep;

    private RouteReplayValidator() {}

    /** Lane 3 wires its production validator here once available. */
    public static void wireValidator(StepValidator v)
    {
        VALIDATOR = v == null ? RouteReplayValidator::defaultStep : v;
    }

    /** Replay validation result for a single JSONL trace. */
    public record ReplayValidationResult(int totalTicks, int validTicks,
                                         List<TickValidation> perTick)
    {
        public boolean allValid() { return validTicks == totalTicks; }
    }

    public record TickValidation(int index, String tickId, boolean ok, String reason) {}

    /** Functional interface Lane 3 implements. */
    @FunctionalInterface
    public interface StepValidator
    {
        Verdict validate(WorldPoint prev, WorldPoint next, WorldSnapshot snapshotOrNull);
    }

    public record Verdict(boolean ok, String reason)
    {
        public static Verdict accept() { return new Verdict(true, ""); }
        public static Verdict reject(String reason) { return new Verdict(false, reason); }
    }

    /** Validate the in-memory trace list. */
    public static ReplayValidationResult validate(List<RouteTrace> traces, WorldSnapshot snapshotOrNull)
    {
        List<TickValidation> perTick = new ArrayList<>();
        int valid = 0;
        WorldPoint prev = null;
        for (int i = 0; i < traces.size(); i++)
        {
            RouteTrace t = traces.get(i);
            WorldPoint next = t.playerAt;
            if (next == null)
            {
                perTick.add(new TickValidation(i, t.tickId, false, "no player position"));
                continue;
            }
            if (prev == null)
            {
                perTick.add(new TickValidation(i, t.tickId, true, ""));
                valid++;
                prev = next;
                continue;
            }
            // Player may not move on every tick (waiting on dispatcher,
            // transport in progress, etc.). Identical previous position
            // is a NO-OP and always valid.
            if (prev.equals(next))
            {
                perTick.add(new TickValidation(i, t.tickId, true, "no-op"));
                valid++;
                continue;
            }
            Verdict v = VALIDATOR.validate(prev, next, snapshotOrNull);
            if (v.ok)
            {
                perTick.add(new TickValidation(i, t.tickId, true, ""));
                valid++;
            }
            else
            {
                perTick.add(new TickValidation(i, t.tickId, false, v.reason));
            }
            prev = next;
        }
        return new ReplayValidationResult(traces.size(), valid, perTick);
    }

    /** Validate a JSONL file on disk. Reads back the format
     *  {@link RouteTraceRecorder} emits. Phase-1 simple parser — fields
     *  arrive in fixed order, only extract {@code tickId} +
     *  {@code playerAt} (the bits replay validation needs). */
    public static ReplayValidationResult validateFile(File f, WorldSnapshot snapshotOrNull) throws IOException
    {
        List<RouteTrace> simplified = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8))
        {
            String line;
            while ((line = r.readLine()) != null)
            {
                if (line.isBlank()) continue;
                String tickId = extractStringField(line, "tickId");
                WorldPoint p = extractWpField(line, "playerAt");
                // Build a minimal RouteTrace with just the fields replay uses.
                simplified.add(new RouteTrace(
                    tickId == null ? "" : tickId,
                    0L, p,
                    java.util.Optional.empty(), java.util.Optional.empty(),
                    java.util.List.of(), java.util.List.of(),
                    java.util.Optional.empty(),
                    false,
                    net.runelite.client.plugins.recorder.nav.v2.qc.contracts.ExecutorResult.WAYPOINT_REACHED,
                    java.util.Optional.empty()));
            }
        }
        return validate(simplified, snapshotOrNull);
    }

    private static String extractStringField(String json, String key)
    {
        String needle = "\"" + key + "\":\"";
        int start = json.indexOf(needle);
        if (start < 0) return null;
        start += needle.length();
        int end = json.indexOf('"', start);
        if (end < 0) return null;
        return json.substring(start, end);
    }

    private static WorldPoint extractWpField(String json, String key)
    {
        String needle = "\"" + key + "\":{";
        int start = json.indexOf(needle);
        if (start < 0) return null;
        start += needle.length();
        int end = json.indexOf('}', start);
        if (end < 0) return null;
        String inner = json.substring(start, end);
        int x = readInt(inner, "x"), y = readInt(inner, "y"), p = readInt(inner, "plane");
        if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE) return null;
        return new WorldPoint(x, y, p == Integer.MIN_VALUE ? 0 : p);
    }

    private static int readInt(String s, String key)
    {
        String needle = "\"" + key + "\":";
        int start = s.indexOf(needle);
        if (start < 0) return Integer.MIN_VALUE;
        start += needle.length();
        int end = start;
        while (end < s.length() && (Character.isDigit(s.charAt(end)) || s.charAt(end) == '-')) end++;
        if (start == end) return Integer.MIN_VALUE;
        try { return Integer.parseInt(s.substring(start, end)); }
        catch (NumberFormatException ex) { return Integer.MIN_VALUE; }
    }

    /** Default step validator (Phase-1 baseline): chebyshev = 1 and
     *  same plane. Replaced by {@code wireValidator(...)} once Lane 3
     *  ships. */
    private static Verdict defaultStep(WorldPoint prev, WorldPoint next, WorldSnapshot snapshot)
    {
        int dx = Math.abs(prev.getX() - next.getX());
        int dy = Math.abs(prev.getY() - next.getY());
        if (dx > 1 || dy > 1)
            return Verdict.reject("step too large: dx=" + dx + " dy=" + dy + " (no Lane-3 validator wired)");
        if (prev.getPlane() != next.getPlane())
            return Verdict.reject("plane change without TransportLeg: "
                + prev.getPlane() + "→" + next.getPlane());
        return Verdict.accept();
    }
}
