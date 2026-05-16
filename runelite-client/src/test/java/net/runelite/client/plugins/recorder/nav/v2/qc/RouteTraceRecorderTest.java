package net.runelite.client.plugins.recorder.nav.v2.qc;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.nav.v2.qc.contracts.ExecutorResult;
import net.runelite.client.plugins.recorder.nav.v2.qc.contracts.ReplanReason;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Self-test for the Lane-6 harness JSONL recorder. Exercises encoding,
 *  file I/O, and the in-memory pass-through path. */
public class RouteTraceRecorderTest
{
    @Test
    public void encodesMinimalTraceToValidJsonl() throws IOException
    {
        File tmp = File.createTempFile("nav-trace-", ".jsonl");
        tmp.deleteOnExit();
        try (RouteTraceRecorder rec = RouteTraceRecorder.forFile(tmp, true))
        {
            RouteTrace t = new RouteTrace(
                "tick-1", 123L,
                new WorldPoint(3200, 3201, 0),
                Optional.empty(), Optional.empty(),
                List.of(new WorldPoint(3201, 3201, 0),
                        new WorldPoint(3200, 3202, 0)),
                List.of(new RouteTrace.Rejected(
                    new WorldPoint(3199, 3201, 0), "off_corridor")),
                Optional.of(new WorldPoint(3201, 3201, 0)),
                true,
                ExecutorResult.WAYPOINT_REACHED,
                Optional.empty());
            rec.record(t);
        }

        String body = Files.readString(tmp.toPath(), StandardCharsets.UTF_8);
        // Single JSONL row.
        assertEquals(1, body.lines().count());
        assertTrue("encoded JSON missing tickId", body.contains("\"tickId\":\"tick-1\""));
        assertTrue("encoded JSON missing playerAt", body.contains("\"playerAt\":{\"x\":3200,\"y\":3201,\"plane\":0}"));
        assertTrue("encoded JSON missing sidestepUsed=true", body.contains("\"sidestepUsed\":true"));
        assertTrue("encoded JSON missing result", body.contains("\"result\":\"WAYPOINT_REACHED\""));
        assertTrue("encoded JSON should emit candidatesConsidered list",
            body.contains("\"candidatesConsidered\":["));
    }

    @Test
    public void inMemoryPathReturnsRecordedTraces() throws IOException
    {
        RouteTraceRecorder rec = RouteTraceRecorder.inMemoryOnly();
        rec.record(new RouteTrace(
            "tick-a", 0L,
            new WorldPoint(0, 0, 0),
            Optional.empty(), Optional.empty(),
            List.of(), List.of(),
            Optional.empty(), false,
            ExecutorResult.WAYPOINT_REACHED, Optional.empty()));
        rec.record(new RouteTrace(
            "tick-b", 1L,
            new WorldPoint(1, 0, 0),
            Optional.empty(), Optional.empty(),
            List.of(), List.of(),
            Optional.empty(), false,
            ExecutorResult.NEEDS_REPLAN,
            Optional.of(ReplanReason.NO_LOCAL_WALKABLE_TILE)));
        rec.close();

        assertEquals(2, rec.recorded().size());
        assertEquals("tick-a", rec.recorded().get(0).tickId);
        assertEquals(ExecutorResult.NEEDS_REPLAN, rec.recorded().get(1).result);
        assertEquals(ReplanReason.NO_LOCAL_WALKABLE_TILE,
            rec.recorded().get(1).replanReason.get());
    }

    @Test
    public void escapesProblematicCharactersInRejectedReason() throws IOException
    {
        RouteTraceRecorder rec = RouteTraceRecorder.inMemoryOnly();
        rec.record(new RouteTrace(
            "tick-x", 0L,
            new WorldPoint(0, 0, 0),
            Optional.empty(), Optional.empty(),
            List.of(),
            List.of(new RouteTrace.Rejected(
                new WorldPoint(0, 0, 0), "reason with \"quotes\" and \\ backslash")),
            Optional.empty(), false,
            ExecutorResult.WAYPOINT_REACHED, Optional.empty()));
        rec.close();

        String json = RouteTraceRecorder.toJson(rec.recorded().get(0));
        // Encoding produces valid JSON: backslash-escaped quotes + slash.
        assertTrue("quotes must be escaped",
            json.contains("\\\"quotes\\\""));
        assertTrue("backslashes must be escaped",
            json.contains("\\\\ backslash"));
    }
}
