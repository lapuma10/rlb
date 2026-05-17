package net.runelite.client.plugins.recorder.nav.v2.collision;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.Constants;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.nav.v2.bfs.SkretzoBfsKernel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Precomputed connectivity components for static OSRS collision.
 *
 *  <p>For each plane in a {@link GlobalCollisionSnapshot}, flood-fills
 *  every walkable tile into labelled components. Two tiles are in the
 *  same component iff a tile-by-tile walking path exists between them
 *  under {@link SkretzoBfsKernel#canMove} — the same step rule the BFS
 *  kernel applies at runtime. By construction, no drift between this
 *  view and the BFS kernel's view of static reachability is possible.
 *
 *  <p>Used by {@link
 *  net.runelite.client.plugins.recorder.nav.v2.transport.LinkGraphDijkstra}
 *  to filter implicit walk edges: nodes in different components are
 *  guaranteed unwalkable in static collision space, so Dijkstra
 *  refuses to add a walk edge between them. The only way to bridge
 *  components is then via an explicit transport edge — which is
 *  exactly what gates / doors / stairs are modelled as.
 *
 *  <p>The flood-fill is 8-direction with the pillar rule (i.e. via
 *  {@code canMove}); cardinal-only would over-partition corner-touch
 *  tiles whose only connection is a diagonal step.
 *
 *  <p>Live overlay changes are NOT reflected. The components view is
 *  bound to the static snapshot. Live-open doors not modelled as
 *  transport entries become accepted regressions — see the spec
 *  {@code docs/superpowers/specs/2026-05-17-collision-aware-dijkstra-design.md}
 *  §4 "Live-overlay-passable / static-blocked" for the rationale.
 *
 *  <p>Thread-safety: immutable after {@link #fromSnapshot} returns. */
public final class ConnectivityComponents
{
    private static final Logger log = LoggerFactory.getLogger(ConnectivityComponents.class);

    private static final int REGION_SIZE = Constants.REGION_SIZE;
    private static final int TILES_PER_PLANE = REGION_SIZE * REGION_SIZE;

    /** Per-region component IDs.
     *
     *  <p>{@code regions.get(regionKey)} returns {@code int[plane * TILES_PER_PLANE + y_local * REGION_SIZE + x_local]}
     *  (a single flat int[]) where the value is the 1-based component
     *  id, or {@code 0} for unwalkable/unlabeled. We expose 0-based
     *  ids externally (subtracting 1), so unwalkable returns {@code -1}.
     *
     *  <p>Flat int[] per-region is allocated only when the region has
     *  at least one walkable tile. Sparse: regions not in the source
     *  snapshot are absent from the map.
     *
     *  <p>{@code planeCount} is stored alongside so we can compute
     *  flat indices correctly without ragged-array sentinels. */
    private final Map<Integer, RegionComponents> regions;
    private final int totalComponents;

    private ConnectivityComponents(Map<Integer, RegionComponents> regions, int totalComponents)
    {
        this.regions = regions;
        this.totalComponents = totalComponents;
    }

    /** Build components from a global collision snapshot. Runs once;
     *  ~1-2s for the full bundled map on a desktop machine. */
    public static ConnectivityComponents fromSnapshot(GlobalCollisionSnapshot snap)
    {
        long t0 = System.nanoTime();
        Set<Integer> regionKeys = snap.loadedRegionKeys();
        // Single static-only CollisionView — the live overlay is empty so
        // CollisionView falls through to the global snapshot for every
        // tile lookup. This is what gives us "static collision view"
        // semantics for the flood-fill without re-implementing canMove.
        CollisionView staticView = new CollisionView(
            snap, LiveSceneCollisionOverlay.capture(null, 0, 0, 0));

        Map<Integer, RegionComponents> out = new HashMap<>(regionKeys.size() * 2);
        int nextId = 1;  // 1-based; 0 == unlabeled

        for (Integer regionKey : regionKeys)
        {
            int rx = regionKey & 0xFFFF;
            int ry = (regionKey >>> 16) & 0xFFFF;
            int baseX = rx * REGION_SIZE;
            int baseY = ry * REGION_SIZE;
            // Probe planeCount by checking isLoaded at the four planes.
            // GlobalCollisionSnapshot encodes per-region plane count
            // implicitly via the FlagMap; we re-derive it here without
            // reaching into private state.
            int planeCount = 0;
            for (int p = 0; p < 4; p++)
            {
                if (snap.isLoaded(new WorldPoint(baseX, baseY, p)))
                {
                    planeCount = p + 1;
                }
            }
            if (planeCount == 0) continue;

            int[] ids = new int[planeCount * TILES_PER_PLANE];
            RegionComponents rc = new RegionComponents(planeCount, ids);
            out.put(regionKey, rc);

            for (int p = 0; p < planeCount; p++)
            {
                for (int yLocal = 0; yLocal < REGION_SIZE; yLocal++)
                {
                    for (int xLocal = 0; xLocal < REGION_SIZE; xLocal++)
                    {
                        int idx = p * TILES_PER_PLANE + yLocal * REGION_SIZE + xLocal;
                        if (ids[idx] != 0) continue;  // already labelled
                        int wx = baseX + xLocal;
                        int wy = baseY + yLocal;
                        if (!isWalkable(staticView, wx, wy, p)) continue;
                        // Flood-fill this component.
                        floodFill(staticView, out, wx, wy, p, nextId);
                        nextId++;
                    }
                }
            }
        }

        int totalComponents = nextId - 1;
        log.info("[nav-v2.components] built {} components across {} regions in {} ms",
            totalComponents, out.size(), (System.nanoTime() - t0) / 1_000_000);
        return new ConnectivityComponents(out, totalComponents);
    }

    /** 8-direction BFS flood-fill from the seed tile, using the shared
     *  {@link SkretzoBfsKernel#canMove} step rule. Writes 1-based
     *  component IDs into the per-region storage. Crosses region
     *  boundaries transparently — the staticView routes lookups to the
     *  correct neighbour FlagMap. */
    private static void floodFill(CollisionView staticView,
                                  Map<Integer, RegionComponents> out,
                                  int seedX, int seedY, int plane,
                                  int componentId)
    {
        final int[] dxs = {-1, 1, 0, 0, -1, 1, -1, 1};
        final int[] dys = {0, 0, -1, 1, -1, -1, 1, 1};

        Deque<long[]> queue = new ArrayDeque<>();
        setComponent(out, seedX, seedY, plane, componentId);
        queue.add(new long[]{seedX, seedY});

        while (!queue.isEmpty())
        {
            long[] head = queue.poll();
            int x = (int) head[0];
            int y = (int) head[1];
            for (int i = 0; i < 8; i++)
            {
                int dx = dxs[i];
                int dy = dys[i];
                int nx = x + dx;
                int ny = y + dy;
                if (!SkretzoBfsKernel.canMove(staticView, x, y, plane, dx, dy)) continue;
                int existing = readComponent(out, nx, ny, plane);
                if (existing != 0) continue;  // already labelled (or out-of-snapshot)
                if (!isInSnapshot(out, nx, ny, plane)) continue;  // edge of snapshot
                setComponent(out, nx, ny, plane, componentId);
                queue.add(new long[]{nx, ny});
            }
        }
    }

    private static boolean isWalkable(CollisionView view, int wx, int wy, int plane)
    {
        int flags = view.flagsAt(wx, wy, plane);
        return (flags & CollisionDataFlag.BLOCK_MOVEMENT_FULL) == 0;
    }

    private static int readComponent(Map<Integer, RegionComponents> out, int wx, int wy, int plane)
    {
        int rx = Math.floorDiv(wx, REGION_SIZE);
        int ry = Math.floorDiv(wy, REGION_SIZE);
        int key = packRegion(rx, ry);
        RegionComponents rc = out.get(key);
        if (rc == null) return 0;
        if (plane < 0 || plane >= rc.planeCount) return 0;
        int xLocal = wx - rx * REGION_SIZE;
        int yLocal = wy - ry * REGION_SIZE;
        return rc.ids[plane * TILES_PER_PLANE + yLocal * REGION_SIZE + xLocal];
    }

    private static boolean isInSnapshot(Map<Integer, RegionComponents> out, int wx, int wy, int plane)
    {
        int rx = Math.floorDiv(wx, REGION_SIZE);
        int ry = Math.floorDiv(wy, REGION_SIZE);
        RegionComponents rc = out.get(packRegion(rx, ry));
        return rc != null && plane >= 0 && plane < rc.planeCount;
    }

    private static void setComponent(Map<Integer, RegionComponents> out,
                                     int wx, int wy, int plane, int id)
    {
        int rx = Math.floorDiv(wx, REGION_SIZE);
        int ry = Math.floorDiv(wy, REGION_SIZE);
        RegionComponents rc = out.get(packRegion(rx, ry));
        if (rc == null || plane < 0 || plane >= rc.planeCount) return;
        int xLocal = wx - rx * REGION_SIZE;
        int yLocal = wy - ry * REGION_SIZE;
        rc.ids[plane * TILES_PER_PLANE + yLocal * REGION_SIZE + xLocal] = id;
    }

    private static int packRegion(int rx, int ry)
    {
        return (rx & 0xFFFF) | ((ry & 0xFFFF) << 16);
    }

    /** Returns the component id for {@code p}; {@code -1} if the tile
     *  is in a region not present in the source snapshot, or if the
     *  tile is fully blocked.
     *
     *  <p>Different ids ⇒ no walkable path exists between the two
     *  tiles in static collision space. */
    public int componentOf(WorldPoint p)
    {
        return componentOf(p.getX(), p.getY(), p.getPlane());
    }

    public int componentOf(int x, int y, int plane)
    {
        int raw = readComponent(regions, x, y, plane);
        return raw == 0 ? -1 : raw - 1;
    }

    /** True iff both points return a non-negative component id and the
     *  ids are equal. Use as a Dijkstra walk-edge predicate. */
    public boolean sameComponent(WorldPoint a, @Nullable WorldPoint b)
    {
        if (b == null) return false;
        int ca = componentOf(a);
        if (ca < 0) return false;
        return ca == componentOf(b);
    }

    /** Total number of distinct components built. Diagnostic only. */
    public int componentCount() { return totalComponents; }

    /** Number of regions for which any component data exists. Diagnostic only. */
    public int regionCount() { return regions.size(); }

    /** Per-region storage: planeCount + flat component-id array.
     *  Immutable after {@link ConnectivityComponents#fromSnapshot} returns. */
    private static final class RegionComponents
    {
        final int planeCount;
        final int[] ids;

        RegionComponents(int planeCount, int[] ids)
        {
            this.planeCount = planeCount;
            this.ids = ids;
        }
    }
}
