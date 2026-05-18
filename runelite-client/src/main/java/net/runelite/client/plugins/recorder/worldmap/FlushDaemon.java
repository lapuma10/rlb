package net.runelite.client.plugins.recorder.worldmap;

import java.io.File;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;

/**
 * Daemon thread that periodically flushes dirty region chunks, entity
 * sightings, and the transport-edge graph to disk. Start/stop follows
 * the plugin lifecycle; {@link #flushOnce()} is also called at plugin
 * shutdown to ensure in-memory data is persisted before the JVM exits.
 *
 * <p>The transport graph is small enough to flush as a whole-file
 * overwrite rather than dirty-tracking per region — see {@link
 * TransportIndex#takeDirty()}.
 */
@Slf4j
public final class FlushDaemon
{
    private final MapStore store;
    private final EntityIndex index;
    @Nullable private final TransportIndex transports;
    private final File rootDir;
    private final long intervalMs;
    private final CopyOnWriteArrayList<Runnable> extraCallbacks = new CopyOnWriteArrayList<>();
    private volatile boolean running;
    private Thread t;

    public FlushDaemon(MapStore store, EntityIndex index, File rootDir, long intervalMs)
    {
        this(store, index, null, rootDir, intervalMs);
    }

    public FlushDaemon(MapStore store, EntityIndex index,
                       @Nullable TransportIndex transports,
                       File rootDir, long intervalMs)
    {
        this.store = store;
        this.index = index;
        this.transports = transports;
        this.rootDir = rootDir;
        this.intervalMs = intervalMs;
    }

    public void start()
    {
        running = true;
        t = new Thread(this::loop, "worldmap-flush");
        t.setDaemon(true);
        t.start();
    }

    public void stop()
    {
        running = false;
        if (t != null) t.interrupt();
    }

    /** Register an extra writer to run on each flush tick (and on the
     *  shutdown {@link #flushOnce()}). Used by v2.1 sidecars
     *  (dead-ends, route-skeletons) so they share the same cadence as
     *  the worldmap/transport stores. Callbacks must be idempotent
     *  and no-op when their own dirty flag is clear. */
    public void addFlushCallback(Runnable r)
    {
        if (r != null) extraCallbacks.add(r);
    }

    private void loop()
    {
        while (running)
        {
            try { Thread.sleep(intervalMs); }
            catch (InterruptedException ie) { return; }
            try { flushOnce(); }
            catch (Throwable th) { log.warn("worldmap flush failed", th); }
        }
    }

    public void flushOnce()
    {
        // Tiles + objects per region.
        Set<Integer> dirtyTiles = store.takeDirtyRegionIds();
        for (int regionId : dirtyTiles)
        {
            RegionChunkSnapshot snap = store.snapshotFor(regionId);
            if (snap == null) continue;
            MapStoreIO.writeRegion(rootDir, snap);
        }
        // Entity sightings per region.
        Set<Integer> dirtyEntities = index.takeDirtyRegionIds();
        for (int regionId : dirtyEntities)
        {
            List<EntitySighting> npcs = index.npcsInRegion(regionId);
            MapStoreIO.writeEntities(rootDir, regionId, npcs);
        }
        // Transport graph — single top-level file overwrite when the
        // index has changed since the last flush.
        if (transports != null && transports.takeDirty())
        {
            TransportIO.writeAll(rootDir, transports.getAll());
        }
        // Extra registered writers (v2.1 dead-end memory, route
        // skeletons, etc.). Each is responsible for its own dirty
        // check so a no-op tick is cheap.
        for (Runnable r : extraCallbacks)
        {
            try { r.run(); }
            catch (Throwable th) { log.warn("worldmap flush callback failed", th); }
        }
    }
}
