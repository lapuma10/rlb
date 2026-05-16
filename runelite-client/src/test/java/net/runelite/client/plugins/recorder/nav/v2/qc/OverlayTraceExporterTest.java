package net.runelite.client.plugins.recorder.nav.v2.qc;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.nav.v2.qc.contracts.ExecutorResult;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Self-test for OverlayTraceExporter. Exercises path-hash stability
 *  and Jaccard overlap math used by Test 9. */
public class OverlayTraceExporterTest
{
    @Test
    public void identicalRunsHaveIdenticalHashes()
    {
        var a = OverlayTraceExporter.toTraceTiles("a", traceList(
            new WorldPoint(3200, 3200, 0),
            new WorldPoint(3201, 3200, 0),
            new WorldPoint(3202, 3200, 0)));
        var b = OverlayTraceExporter.toTraceTiles("b", traceList(
            new WorldPoint(3200, 3200, 0),
            new WorldPoint(3201, 3200, 0),
            new WorldPoint(3202, 3200, 0)));
        assertEquals("identical tile chains must hash identically",
            OverlayTraceExporter.hashPath(a.tiles()),
            OverlayTraceExporter.hashPath(b.tiles()));

        var summary = OverlayTraceExporter.summarize(List.of(a, b));
        assertTrue("two identical runs must register allHashesIdentical",
            summary.allHashesIdentical());
        assertEquals("complete overlap = mean Jaccard 1.0",
            1.0, summary.meanJaccardOverlap(), 1e-9);
    }

    @Test
    public void divergentRunsHaveDistinctHashesAndLowerOverlap()
    {
        var a = OverlayTraceExporter.toTraceTiles("a", traceList(
            new WorldPoint(3200, 3200, 0),
            new WorldPoint(3201, 3200, 0),
            new WorldPoint(3202, 3200, 0)));
        var b = OverlayTraceExporter.toTraceTiles("b", traceList(
            new WorldPoint(3200, 3200, 0),
            new WorldPoint(3201, 3201, 0),
            new WorldPoint(3202, 3200, 0)));
        assertFalse("divergent paths must not hash identically",
            OverlayTraceExporter.hashPath(a.tiles())
                .equals(OverlayTraceExporter.hashPath(b.tiles())));
        var summary = OverlayTraceExporter.summarize(List.of(a, b));
        assertFalse("divergent paths must not register allHashesIdentical",
            summary.allHashesIdentical());
        assertTrue("partial overlap must be 0 < J < 1, got "
            + summary.meanJaccardOverlap(),
            summary.meanJaccardOverlap() > 0.0
            && summary.meanJaccardOverlap() < 1.0);
    }

    @Test
    public void deduplicatesConsecutiveIdenticalTiles()
    {
        var a = OverlayTraceExporter.toTraceTiles("a", traceList(
            new WorldPoint(3200, 3200, 0),
            new WorldPoint(3200, 3200, 0),   // duplicate
            new WorldPoint(3200, 3200, 0),   // duplicate
            new WorldPoint(3201, 3200, 0)));
        assertEquals("consecutive duplicates must collapse",
            2, a.tiles().size());
    }

    @Test
    public void svgEmitsValidEnvelope() throws IOException
    {
        var a = OverlayTraceExporter.toTraceTiles("a", traceList(
            new WorldPoint(3200, 3200, 0),
            new WorldPoint(3201, 3200, 0)));
        File tmp = File.createTempFile("overlay-", ".svg");
        tmp.deleteOnExit();
        OverlayTraceExporter.toSvg(List.of(a), tmp);
        String body = Files.readString(tmp.toPath(), StandardCharsets.UTF_8);
        assertTrue("SVG must declare xmlns", body.contains("xmlns=\"http://www.w3.org/2000/svg\""));
        assertTrue("SVG must contain a polyline for the trace",
            body.contains("<polyline"));
    }

    private static List<RouteTrace> traceList(WorldPoint... tiles)
    {
        List<RouteTrace> out = new java.util.ArrayList<>();
        for (WorldPoint p : tiles)
            out.add(new RouteTrace(
                "t", 0L, p,
                Optional.empty(), Optional.empty(),
                List.of(), List.of(),
                Optional.empty(), false,
                ExecutorResult.WAYPOINT_REACHED, Optional.empty()));
        return out;
    }
}
