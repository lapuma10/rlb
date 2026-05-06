package net.runelite.client.plugins.recorder.worldmap;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;

/**
 * Process-wide store for {@link RegionChunkSnapshot}s. The scraper writes
 * via {@link #publish}; readers (the planner) call {@link #snapshotFor}.
 *
 * <p>Concurrency model: each region's snapshot lives in its own
 * {@link AtomicReference}. Publication is a single {@code set()} on a
 * fully-constructed snapshot. Readers see either the previous or the next
 * snapshot — never a torn intermediate.
 *
 * <p>Eviction: bounded LRU by recent reader access. Hot regions stay
 * resident; cold regions are evicted when the cap is hit. Eviction does
 * NOT delete persisted JSON — only frees in-memory state.
 */
public final class MapStore
{
    private final WorldMemoryConfig config;
    private final Map<Integer, AtomicReference<RegionChunkSnapshot>> snapshots
        = new ConcurrentHashMap<>();
    private final LinkedHashMap<Integer, Long> accessOrder
        = new LinkedHashMap<>(16, 0.75f, true);
    private final Object accessLock = new Object();
    /** Region IDs that have a published snapshot newer than the last flush. */
    private final java.util.Set<Integer> dirtyRegionIds
        = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public MapStore(WorldMemoryConfig config)
    {
        this.config = config;
    }

    /** Return the current snapshot for {@code regionId}, or null if no
     *  snapshot has ever been published for it. */
    @Nullable
    public RegionChunkSnapshot snapshotFor(int regionId)
    {
        AtomicReference<RegionChunkSnapshot> ref = snapshots.get(regionId);
        if (ref == null) return null;
        synchronized (accessLock) { accessOrder.put(regionId, System.nanoTime()); }
        return ref.get();
    }

    /** Get a builder for an in-progress scrape. If a snapshot already
     *  exists for this region, the builder starts as a copy of it (so
     *  partial scrapes overlay onto existing data instead of clearing it).
     *
     *  <p>MUST be called only on the client thread (scraper context).
     *  The returned builder is single-threaded; the caller must publish
     *  it via {@link #publish} before returning to the worker pool.
     *
     *  <p>Cost note: this allocates a fresh map and copies all tiles from
     *  the prior snapshot. With ~4096 tiles per region and 8 bytes per
     *  entry, that's ~32KB per scrape × 2-tick cadence ~= 27KB/s of
     *  allocator pressure. Validated tolerable per spec; profile if it
     *  ever shows up as a hotspot. */
    public RegionChunkBuilder builderFor(int regionId)
    {
        AtomicReference<RegionChunkSnapshot> ref = snapshots.get(regionId);
        if (ref == null || ref.get() == null)
        {
            return new RegionChunkBuilder(regionId);
        }
        return RegionChunkBuilder.copyOf(ref.get());
    }

    /** Publish a fresh snapshot atomically. The next call to
     *  {@link #snapshotFor} sees the new data; readers in flight finish
     *  on the previous snapshot. Marks the region dirty for the flush daemon. */
    public void publish(int regionId, RegionChunkBuilder builder)
    {
        RegionChunkSnapshot snap = RegionChunkSnapshot.fromBuilder(builder);
        AtomicReference<RegionChunkSnapshot> ref = snapshots.computeIfAbsent(
            regionId, k -> new AtomicReference<>());
        ref.set(snap);
        dirtyRegionIds.add(regionId);
        evictIfOver();
    }

    /** Atomically take and clear the dirty-region set. Called by the flush
     *  daemon — the regions returned will be persisted; any region
     *  re-published during/after the flush is re-added by the next
     *  publish() call. Race-safe by the ConcurrentHashMap.newKeySet
     *  semantics: removeAll + add operations are independent atomic ops. */
    public java.util.Set<Integer> takeDirtyRegionIds()
    {
        java.util.Set<Integer> snapshot = new java.util.HashSet<>(dirtyRegionIds);
        dirtyRegionIds.removeAll(snapshot);
        return snapshot;
    }

    /** Bootstrap load: read the persisted JSON for {@code regionId} (if any)
     *  and install it as the active snapshot. Called from RecorderPlugin's
     *  startup thread. Returns true iff a snapshot was loaded (and thus
     *  installed). */
    public boolean loadFromDisk(File rootDir, int regionId)
    {
        RegionChunkSnapshot loaded = MapStoreIO.readRegion(rootDir, regionId);
        if (loaded.tiles().isEmpty()) return false;
        snapshots.computeIfAbsent(regionId, k -> new AtomicReference<>()).set(loaded);
        synchronized (accessLock) { accessOrder.put(regionId, System.nanoTime()); }
        return true;
    }

    /** Test-only: directly install a snapshot. Used by fixture-driven tests
     *  to skip the scraper. Package-private — production callers should use
     *  loadFromDisk() instead. */
    void installSnapshotForTest(int regionId, RegionChunkSnapshot snap)
    {
        snapshots.computeIfAbsent(regionId, k -> new AtomicReference<>()).set(snap);
        synchronized (accessLock) { accessOrder.put(regionId, System.nanoTime()); }
    }

    private void evictIfOver()
    {
        synchronized (accessLock)
        {
            while (accessOrder.size() > config.memoryResidentChunkCount)
            {
                Integer oldest = accessOrder.keySet().iterator().next();
                accessOrder.remove(oldest);
                snapshots.remove(oldest);
                // Note: dirty bit not cleared on eviction — if a chunk was dirty
                // and gets evicted, the flush daemon's next pass picks up an empty
                // snapshot lookup and skips it. That's acceptable: data was
                // re-derivable from the next live scrape.
            }
        }
    }
}
