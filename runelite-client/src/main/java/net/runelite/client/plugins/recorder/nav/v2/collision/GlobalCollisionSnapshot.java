package net.runelite.client.plugins.recorder.nav.v2.collision;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.Constants;
import net.runelite.api.coords.WorldPoint;

/** Bundled global tile-collision snapshot vendored from Skretzo's
 *  shortest-path plugin (BSD-2-Clause). Loaded once from the
 *  classpath resource {@code /runelite/nav/collision/collision-map.zip}.
 *
 *  <p>Skretzo's binary format stores TWO flags per tile per plane:
 *  <ul>
 *    <li>flag 0 = "can move north from this tile"</li>
 *    <li>flag 1 = "can move east from this tile"</li>
 *  </ul>
 *  South and west are derived from the neighbour's bits. We expose a
 *  RuneLite-style {@link #flagsAt(WorldPoint)} that returns an int
 *  whose bits match {@link CollisionDataFlag} ({@code
 *  BLOCK_MOVEMENT_NORTH}, etc.). Movement-blocking-direction bits are
 *  derived from Skretzo's two bits; the "tile-type" bits
 *  ({@code BLOCK_MOVEMENT_FULL}, {@code BLOCK_MOVEMENT_OBJECT}) are
 *  inferred from "no cardinal direction walkable from this tile" —
 *  the same heuristic {@code CollisionMap.isBlocked} uses upstream.
 *
 *  <p>Thread safety: immutable after construction. The internal
 *  bitsets are never mutated post-load.
 *
 *  <p>This implementation is part of the Lane 2 deliverable for
 *  the observation-aware navigation engine (spec
 *  {@code docs/superpowers/specs/2026-05-16-observation-aware-navigation-engine-design.md}).
 *  The Skretzo CollisionMap inspiration is attributed in
 *  {@code NOTICES.md} at repo root. */
@Slf4j
public final class GlobalCollisionSnapshot
{
    /** Resource path inside the JAR.
     *
     *  <p>Note: spec §9 originally wrote {@code /runelite/nav/collision/}, but
     *  the runelite-gradle-plugin's {@code IndexTask} iterates {@code runelite/*}
     *  and {@code parseInt}s every subdirectory name (it expects numeric
     *  archive ids). Putting our data under {@code runelite/} therefore breaks
     *  the build. We use the top-level {@code /nav/collision/} resource path
     *  instead — equally namespaced (no other resources here), no build conflict.
     *  This deviation is documented in {@code lane2-manifest.md}. */
    public static final String RESOURCE_PATH = "/nav/collision/collision-map.zip";

    /** Manifest path used to read the vendored version string. */
    private static final String MANIFEST_RESOURCE_PATH = "/nav/collision/MANIFEST.md";

    /** Region size — number of tiles per region per dimension. */
    private static final int REGION_SIZE = Constants.REGION_SIZE;

    /** Per-region storage. Key is the region "packed" position (Skretzo packs
     *  regionX into the low 16 bits and regionY into the high 16 bits). Value
     *  is the FlagMap for that region across all loaded planes. */
    private final Map<Integer, FlagMap> regions;

    private final String mapVersion;

    private GlobalCollisionSnapshot(Map<Integer, FlagMap> regions, String mapVersion)
    {
        this.regions = regions;
        this.mapVersion = mapVersion;
    }

    /** Load the snapshot bundled with this client. Lazy — call once and reuse;
     *  parsing the zip allocates ~1MB of bitset storage. */
    public static GlobalCollisionSnapshot fromBundledResource()
    {
        InputStream zipStream = GlobalCollisionSnapshot.class.getResourceAsStream(RESOURCE_PATH);
        if (zipStream == null)
        {
            throw new IllegalStateException(
                "Bundled collision-map.zip not on classpath at " + RESOURCE_PATH
                + " — was the resources directory packaged into the jar?");
        }
        String version = readManifestVersion();
        return loadFromStream(zipStream, version);
    }

    /** Test-only factory that builds a single-region snapshot from a
     *  3D walkability grid. Encodes Skretzo's binary format inline so
     *  unit tests can produce deterministic, hand-crafted collision
     *  fixtures without going through the zip pipeline.
     *
     *  <p>{@code walkable[plane][x_local][y_local]} where {@code x_local}
     *  and {@code y_local} are 0..63 within the region; tiles outside
     *  the array bounds default to blocked. Per Skretzo's convention,
     *  the encoded N-bit is true iff both the current tile and the
     *  northern neighbour are walkable; same logic for E. South/west
     *  bits are derived at lookup time from the neighbour's bits, so
     *  no explicit storage needed here.
     *
     *  <p>Visible (package-private) for use in tests only — do NOT
     *  call from production code; loading the bundled zip is what
     *  production wants. */
    public static GlobalCollisionSnapshot forTestingWalkable(int regionX, int regionY, boolean[][][] walkable)
    {
        int planeCount = walkable.length;
        // Skretzo's encoding: BitSet with bit position
        //   (plane * 64 * 64 + y * 64 + x) * 2 + flag
        // where flag=0 is N, flag=1 is E.
        int totalBits = planeCount * REGION_SIZE * REGION_SIZE * 2;
        java.util.BitSet bits = new java.util.BitSet(totalBits);
        for (int p = 0; p < planeCount; p++)
        {
            boolean[][] plane = walkable[p];
            if (plane == null) continue;
            int planeXMax = plane.length;
            for (int x = 0; x < REGION_SIZE; x++)
            {
                if (x >= planeXMax) break;
                boolean[] col = plane[x];
                if (col == null) continue;
                int planeYMax = col.length;
                for (int y = 0; y < REGION_SIZE; y++)
                {
                    if (y >= planeYMax) break;
                    if (!col[y]) continue;  // blocked → no N/E
                    // N bit: walkable iff (x, y+1) is also walkable
                    boolean n = (y + 1 < REGION_SIZE) && (y + 1 < planeYMax) && col[y + 1];
                    // E bit: walkable iff (x+1, y) is also walkable
                    boolean e = false;
                    if (x + 1 < REGION_SIZE && x + 1 < planeXMax)
                    {
                        boolean[] colE = plane[x + 1];
                        e = colE != null && y < colE.length && colE[y];
                    }
                    int base = (p * REGION_SIZE * REGION_SIZE + y * REGION_SIZE + x) * 2;
                    if (n) bits.set(base);
                    if (e) bits.set(base + 1);
                }
            }
        }
        byte[] bytes = bits.toByteArray();
        // BitSet.toByteArray may truncate trailing-zero bytes; pad up to the
        // expected length so FlagMap recovers the right plane count.
        int expectedBytes = (totalBits + 7) / 8;
        if (bytes.length < expectedBytes)
        {
            byte[] padded = new byte[expectedBytes];
            System.arraycopy(bytes, 0, padded, 0, bytes.length);
            bytes = padded;
        }
        FlagMap fm = new FlagMap(regionX, regionY, bytes);
        Map<Integer, FlagMap> regions = new HashMap<>();
        regions.put(packRegion(regionX, regionY), fm);
        return new GlobalCollisionSnapshot(Map.copyOf(regions), "testing");
    }

    /** Visible for testing — supports loading from arbitrary streams. */
    static GlobalCollisionSnapshot loadFromStream(InputStream zipStream, String version)
    {
        Map<Integer, FlagMap> regions = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(Objects.requireNonNull(zipStream)))
        {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null)
            {
                String name = entry.getName();
                String[] parts = name.split("_");
                if (parts.length != 2)
                {
                    log.warn("[nav-v2-collision] skipping unrecognised zip entry: {}", name);
                    continue;
                }
                try
                {
                    int rx = Integer.parseInt(parts[0]);
                    int ry = Integer.parseInt(parts[1]);
                    byte[] bytes = readAll(zip);
                    FlagMap fm = new FlagMap(rx, ry, bytes);
                    regions.put(packRegion(rx, ry), fm);
                }
                catch (NumberFormatException nfe)
                {
                    log.warn("[nav-v2-collision] skipping malformed entry name {}: {}", name, nfe.getMessage());
                }
            }
        }
        catch (IOException ioe)
        {
            throw new UncheckedIOException("failed to load collision-map.zip", ioe);
        }
        return new GlobalCollisionSnapshot(Map.copyOf(regions), version);
    }

    /** Returns RuneLite-style collision flags for {@code p}. If the tile is
     *  outside the loaded snapshot or on a plane with no data,
     *  {@link CollisionDataFlag#BLOCK_MOVEMENT_FULL} is returned. */
    public int flagsAt(WorldPoint p)
    {
        return flagsAt(p.getX(), p.getY(), p.getPlane());
    }

    /** Coordinate overload — same semantics as {@link #flagsAt(WorldPoint)}. */
    public int flagsAt(int worldX, int worldY, int plane)
    {
        if (plane < 0 || plane > 3)
        {
            return CollisionDataFlag.BLOCK_MOVEMENT_FULL;
        }
        int regionX = worldX / REGION_SIZE;
        int regionY = worldY / REGION_SIZE;
        // Reject negative-coordinate divisions: Java floors toward zero for
        // negative ints which collapses (-1) and (0) into the same region.
        if (worldX < 0 || worldY < 0)
        {
            return CollisionDataFlag.BLOCK_MOVEMENT_FULL;
        }
        // Direct lookup of n/e on this tile.
        boolean n = canMoveDirectionRaw(worldX, worldY, plane, 0, regionX, regionY);
        boolean e = canMoveDirectionRaw(worldX, worldY, plane, 1, regionX, regionY);
        // South is "neighbour to the south reports its north walkable".
        int southY = worldY - 1;
        boolean s = southY >= 0
            && canMoveDirectionRaw(worldX, southY, plane, 0, regionX, southY / REGION_SIZE);
        // West is "neighbour to the west reports its east walkable".
        int westX = worldX - 1;
        boolean w = westX >= 0
            && canMoveDirectionRaw(westX, worldY, plane, 1, westX / REGION_SIZE, regionY);

        int flags = 0;
        if (!n) flags |= CollisionDataFlag.BLOCK_MOVEMENT_NORTH;
        if (!s) flags |= CollisionDataFlag.BLOCK_MOVEMENT_SOUTH;
        if (!e) flags |= CollisionDataFlag.BLOCK_MOVEMENT_EAST;
        if (!w) flags |= CollisionDataFlag.BLOCK_MOVEMENT_WEST;

        // BLOCK_MOVEMENT_FULL set when the tile is unreachable in every cardinal
        // direction. This mirrors Skretzo's CollisionMap.isBlocked().
        if (!n && !s && !e && !w)
        {
            flags |= CollisionDataFlag.BLOCK_MOVEMENT_OBJECT;
        }

        // Note that the actual cardinal-direction bits above are the ones the
        // BFS kernel will check. Diagonal blocks are derived at expansion time
        // from the four cardinal bits, per Skretzo's ne/nw/se/sw formulas.

        return flags;
    }

    /** Set of region keys (packed regionX | regionY << 16) for which this
     *  snapshot has FlagMap data. Enumerated by
     *  {@link ConnectivityComponents#fromSnapshot} to scope its flood-fill
     *  to the exact set of loaded regions. */
    public java.util.Set<Integer> loadedRegionKeys()
    {
        return java.util.Collections.unmodifiableSet(regions.keySet());
    }

    /** True iff the region containing {@code p} is loaded in this snapshot. */
    public boolean isLoaded(WorldPoint p)
    {
        return isLoaded(p.getX(), p.getY(), p.getPlane());
    }

    public boolean isLoaded(int worldX, int worldY, int plane)
    {
        if (worldX < 0 || worldY < 0 || plane < 0 || plane > 3) return false;
        int regionX = worldX / REGION_SIZE;
        int regionY = worldY / REGION_SIZE;
        FlagMap fm = regions.get(packRegion(regionX, regionY));
        if (fm == null) return false;
        return plane < fm.planeCount();
    }

    /** A short, stable identifier for the loaded snapshot.
     *  Read from MANIFEST.md if available, otherwise falls back to the
     *  number of loaded regions. Used for CI version pinning. */
    public String mapVersion()
    {
        return mapVersion;
    }

    /** Visible for testing / Lane 6 — number of regions that successfully
     *  decoded from the zip. */
    public int loadedRegionCount()
    {
        return regions.size();
    }

    /** Visible for debug output (spec §4 Lane 2 required format). */
    public String describeTile(WorldPoint p)
    {
        int flags = flagsAt(p);
        return String.format("plane=%d source=GLOBAL_SNAPSHOT flags=0x%x", p.getPlane(), flags);
    }

    // ---------------------------------------------------------------------

    /** Helper: raw lookup of one Skretzo flag bit. Returns false when
     *  out-of-region or out-of-plane (i.e., we treat unknown space as
     *  blocked). */
    private boolean canMoveDirectionRaw(int worldX, int worldY, int plane, int flag,
                                        int regionX, int regionY)
    {
        if (regionX < 0 || regionY < 0) return false;
        if (worldX < 0 || worldY < 0) return false;
        FlagMap fm = regions.get(packRegion(regionX, regionY));
        if (fm == null) return false;
        if (plane >= fm.planeCount()) return false;
        return fm.get(worldX, worldY, plane, flag);
    }

    private static int packRegion(int rx, int ry)
    {
        return (rx & 0xFFFF) | ((ry & 0xFFFF) << 16);
    }

    private static byte[] readAll(InputStream in) throws IOException
    {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] block = new byte[8192];
        int n;
        while ((n = in.read(block)) > 0)
        {
            buf.write(block, 0, n);
        }
        return buf.toByteArray();
    }

    /** Best-effort manifest read — looks for "Source ref" or "SHA256" rows. */
    private static String readManifestVersion()
    {
        InputStream s = GlobalCollisionSnapshot.class.getResourceAsStream(MANIFEST_RESOURCE_PATH);
        if (s == null) return "unknown";
        try (BufferedReader br = new BufferedReader(new InputStreamReader(s, StandardCharsets.UTF_8)))
        {
            String line;
            while ((line = br.readLine()) != null)
            {
                int idx = line.indexOf("SHA256");
                if (idx >= 0)
                {
                    int back = line.indexOf('`', idx);
                    int forward = back >= 0 ? line.indexOf('`', back + 1) : -1;
                    if (back >= 0 && forward > back)
                    {
                        return line.substring(back + 1, forward);
                    }
                }
            }
        }
        catch (IOException ignored)
        {
            // fall through
        }
        return "unknown";
    }

    /** Single-region tile-flag storage. Mirrors Skretzo's FlagMap. */
    private static final class FlagMap
    {
        private static final byte FLAG_COUNT = 2;
        private final BitSet flags;
        private final int planeCount;
        private final int minX;
        private final int minY;

        FlagMap(int regionX, int regionY, byte[] bytes)
        {
            this.minX = regionX * REGION_SIZE;
            this.minY = regionY * REGION_SIZE;
            this.flags = BitSet.valueOf(bytes);
            int scale = REGION_SIZE * REGION_SIZE * FLAG_COUNT;
            // Skretzo encodes BitSet capacity rounded up to byte boundary;
            // recover plane count by dividing total bits by per-plane size.
            this.planeCount = (flags.size() + scale - 1) / scale;
        }

        int planeCount() { return planeCount; }

        boolean get(int worldX, int worldY, int plane, int flag)
        {
            if (worldX < minX || worldX >= (minX + REGION_SIZE)) return false;
            if (worldY < minY || worldY >= (minY + REGION_SIZE)) return false;
            if (plane < 0 || plane >= planeCount) return false;
            if (flag < 0 || flag >= FLAG_COUNT) return false;
            int idx = (plane * REGION_SIZE * REGION_SIZE + (worldY - minY) * REGION_SIZE + (worldX - minX))
                * FLAG_COUNT + flag;
            return flags.get(idx);
        }
    }
}
