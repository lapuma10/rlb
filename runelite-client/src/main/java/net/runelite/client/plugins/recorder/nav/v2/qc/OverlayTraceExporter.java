package net.runelite.client.plugins.recorder.nav.v2.qc;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.coords.WorldPoint;

/** Reads N trace lists for the same route. Produces an overlay
 *  representation showing where they overlap (high-density tiles) vs
 *  diverge (sidestep variation).
 *
 *  <p>Two exports:
 *  <ul>
 *    <li>{@link #toSvg(List, File)} — SVG with each trace colored
 *        differently; cell intensity scales with overlap count.</li>
 *    <li>{@link #summarize(List)} — text-diff summary with overlap
 *        ratio, divergence tiles, and a per-run path-hash for byte-
 *        identity detection (used by Test 9).</li>
 *  </ul>
 *
 *  <p>Per the Lane-6 plan §Task-4 and spec §10 — variety is route
 *  ROBUSTNESS, not detection-evasion. The exporter exists to confirm
 *  "the engine does not hardcode tile replay across cycles". */
public final class OverlayTraceExporter
{
    private OverlayTraceExporter() {}

    /** Per-trace tile sequence (just the {@code playerAt} chain). */
    public record TraceTiles(String runId, List<WorldPoint> tiles)
    {
        public TraceTiles { tiles = List.copyOf(tiles); }
    }

    /** Per-run summary written by {@link #summarize(List)}. */
    public record Summary(int runCount,
                          int uniqueTilesAcrossAllRuns,
                          int sharedAcrossAllRuns,
                          List<String> pathHashes,
                          boolean allHashesIdentical,
                          double meanJaccardOverlap)
    {
    }

    /** Convert a list of {@link RouteTrace} into a {@link TraceTiles}
     *  (deduplicating consecutive identical tiles). */
    public static TraceTiles toTraceTiles(String runId, List<RouteTrace> traces)
    {
        List<WorldPoint> tiles = new ArrayList<>(traces.size());
        WorldPoint prev = null;
        for (RouteTrace t : traces)
        {
            WorldPoint p = t.playerAt;
            if (p == null) continue;
            if (prev != null && prev.equals(p)) continue;
            tiles.add(p);
            prev = p;
        }
        return new TraceTiles(runId, tiles);
    }

    /** Phase-1 summary used by Test 9 to detect "all 5 traces are
     *  byte-identical = hardcoded tile replay". */
    public static Summary summarize(List<TraceTiles> runs)
    {
        if (runs.isEmpty())
            return new Summary(0, 0, 0, List.of(), false, 0.0);

        Set<WorldPoint> union = new HashSet<>();
        Map<WorldPoint, Integer> tileFreq = new HashMap<>();
        List<String> hashes = new ArrayList<>();
        Set<String> hashSet = new HashSet<>();
        for (TraceTiles tt : runs)
        {
            hashes.add(hashPath(tt.tiles));
            hashSet.add(hashes.get(hashes.size() - 1));
            for (WorldPoint p : tt.tiles)
            {
                union.add(p);
                tileFreq.merge(p, 1, Integer::sum);
            }
        }

        int sharedAll = 0;
        for (Integer c : tileFreq.values()) if (c == runs.size()) sharedAll++;

        // Pairwise mean Jaccard overlap.
        double meanJ = 0.0;
        int pairs = 0;
        for (int i = 0; i < runs.size(); i++)
        {
            Set<WorldPoint> a = new HashSet<>(runs.get(i).tiles);
            for (int j = i + 1; j < runs.size(); j++)
            {
                Set<WorldPoint> b = new HashSet<>(runs.get(j).tiles);
                Set<WorldPoint> inter = new HashSet<>(a);
                inter.retainAll(b);
                Set<WorldPoint> un = new HashSet<>(a);
                un.addAll(b);
                meanJ += un.isEmpty() ? 0.0 : (double) inter.size() / un.size();
                pairs++;
            }
        }
        if (pairs > 0) meanJ /= pairs;
        return new Summary(runs.size(), union.size(), sharedAll, hashes,
            hashSet.size() == 1, meanJ);
    }

    /** Emit an SVG overlay. Each run is a different stroke color;
     *  shared tiles render thicker. */
    public static void toSvg(List<TraceTiles> runs, File out) throws IOException
    {
        if (runs.isEmpty()) throw new IllegalArgumentException("no runs to overlay");
        // Bounds.
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (TraceTiles tt : runs)
            for (WorldPoint p : tt.tiles)
            {
                minX = Math.min(minX, p.getX()); minY = Math.min(minY, p.getY());
                maxX = Math.max(maxX, p.getX()); maxY = Math.max(maxY, p.getY());
            }
        if (minX == Integer.MAX_VALUE)
            throw new IllegalArgumentException("no tiles in any run");

        int pad = 2;
        int w = (maxX - minX + 2 * pad + 1) * 8;
        int h = (maxY - minY + 2 * pad + 1) * 8;
        // Frequency map.
        Map<WorldPoint, Integer> freq = new HashMap<>();
        for (TraceTiles tt : runs)
            for (WorldPoint p : tt.tiles) freq.merge(p, 1, Integer::sum);

        StringBuilder sb = new StringBuilder();
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(w)
          .append("\" height=\"").append(h).append("\">");
        sb.append("<rect width=\"100%\" height=\"100%\" fill=\"#111\"/>");
        // Heat layer first.
        for (var e : freq.entrySet())
        {
            int x = (e.getKey().getX() - minX + pad) * 8;
            // SVG y grows downward; OSRS y grows north — flip for readability.
            int y = (maxY - e.getKey().getY() + pad) * 8;
            int alpha = Math.min(255, e.getValue() * 60);
            sb.append("<rect x=\"").append(x).append("\" y=\"").append(y)
              .append("\" width=\"8\" height=\"8\" fill=\"rgba(255,255,255,")
              .append(alpha / 255.0).append(")\"/>");
        }
        // Per-run trace lines.
        String[] palette = {"#ff5252", "#42c5f5", "#ffd54f", "#aeea00", "#b388ff", "#ff8a65"};
        for (int i = 0; i < runs.size(); i++)
        {
            TraceTiles tt = runs.get(i);
            String stroke = palette[i % palette.length];
            sb.append("<polyline fill=\"none\" stroke=\"").append(stroke)
              .append("\" stroke-width=\"1.5\" points=\"");
            for (WorldPoint p : tt.tiles)
            {
                int x = (p.getX() - minX + pad) * 8 + 4;
                int y = (maxY - p.getY() + pad) * 8 + 4;
                sb.append(x).append(',').append(y).append(' ');
            }
            sb.append("\"/>");
        }
        sb.append("</svg>");
        File parent = out.getParentFile();
        if (parent != null && !parent.isDirectory()) Files.createDirectories(parent.toPath());
        Files.writeString(out.toPath(), sb.toString(), StandardCharsets.UTF_8);
    }

    /** Path hash: simple but stable. SHA-1 over the tile coordinate
     *  sequence. Used by Test 9 to detect "all 5 runs are byte-
     *  identical" as a sign of hardcoded replay. */
    public static String hashPath(List<WorldPoint> tiles)
    {
        try
        {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            for (WorldPoint p : tiles)
            {
                md.update((byte) p.getX());
                md.update((byte) (p.getX() >> 8));
                md.update((byte) p.getY());
                md.update((byte) (p.getY() >> 8));
                md.update((byte) p.getPlane());
            }
            byte[] dig = md.digest();
            StringBuilder sb = new StringBuilder(dig.length * 2);
            for (byte b : dig) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        }
        catch (java.security.NoSuchAlgorithmException ex)
        {
            // Fallback: deterministic hash via java.util.Arrays.hashCode.
            int[] flat = new int[tiles.size() * 3];
            for (int i = 0; i < tiles.size(); i++)
            {
                flat[i * 3] = tiles.get(i).getX();
                flat[i * 3 + 1] = tiles.get(i).getY();
                flat[i * 3 + 2] = tiles.get(i).getPlane();
            }
            return Integer.toHexString(Arrays.hashCode(flat));
        }
    }
}
