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
    /** Optional disk-backed lazy-load source. When non-null, {@link #snapshotFor}
     *  attempts a disk read on a memory miss before returning null — required
     *  so V2 planning can target regions the player has visited before but
     *  whose in-memory snapshot has been LRU-evicted. */
    @Nullable private volatile File rootDir;
    /** Region IDs whose JSON file is known to be missing on disk. Cached to
     *  avoid re-reading the same nonexistent file every tick. Cleared for
     *  a region on {@link #publish} (in case the file appears later). */
    private final java.util.Set<Integer> diskMissCache
        = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public MapStore(WorldMemoryConfig config)
    {
        this.config = config;
    }

    /** Wire the on-disk worldmap root after construction. Called once by
     *  {@link net.runelite.client.plugins.recorder.RecorderPlugin#startUp}
     *  so the LRU can lazy-load evicted regions on demand instead of
     *  reporting "region NOT loaded" the moment the bot walks far enough
     *  to push the destination region out of the in-memory cap.
     *
     *  <p>Live failure that motivated this: ChickenFarmV3 ran combat at
     *  the pen for 20+ minutes, the player drifted south, the LRU evicted
     *  the pen region, then V2_STRICT NO_ROUTE'd the next bank→pen plan
     *  even though the snapshot was on disk and the bot had been *in*
     *  that region 5 minutes earlier. */
    public void setRootDir(@Nullable File rootDir)
    {
        this.rootDir = rootDir;
    }

    /** Return the current snapshot for {@code regionId}, or null if no
     *  snapshot has ever been published for it AND no on-disk fallback
     *  is wired / the on-disk JSON is missing.
     *
     *  <p>On a memory miss, attempts a disk read via {@link #loadFromDisk}
     *  iff {@link #setRootDir} has been called. Repeated disk misses for
     *  the same regionId are cached so the planner doesn't re-stat the
     *  filesystem every tick when a region was never persisted. */
    @Nullable
    public RegionChunkSnapshot snapshotFor(int regionId)
    {
        AtomicReference<RegionChunkSnapshot> ref = snapshots.get(regionId);
        if (ref != null && ref.get() != null)
        {
            synchronized (accessLock) { accessOrder.put(regionId, System.nanoTime()); }
            return ref.get();
        }
        // Memory miss — try disk fallback.
        File root = this.rootDir;
        if (root == null) return null;
        if (diskMissCache.contains(regionId)) return null;
        if (loadFromDisk(root, regionId))
        {
            AtomicReference<RegionChunkSnapshot> reloaded = snapshots.get(regionId);
            return reloaded == null ? null : reloaded.get();
        }
        diskMissCache.add(regionId);
        return null;
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
        // The region is live now — clear any prior disk-miss tag so a
        // future evict→reload cycle re-tries the JSON.
        diskMissCache.remove(regionId);
        synchronized (accessLock) { accessOrder.put(regionId, System.nanoTime()); }
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
        // Race-safe: if a live publish is already in flight, don't clobber
        // it with stale disk data. compareAndSet leaves any existing
        // snapshot untouched.
        AtomicReference<RegionChunkSnapshot> ref = snapshots.computeIfAbsent(
            regionId, k -> new AtomicReference<>());
        if (ref.get() != null)
        {
            // Already populated by a concurrent publish or earlier load.
            synchronized (accessLock) { accessOrder.put(regionId, System.nanoTime()); }
            return true;
        }
        RegionChunkSnapshot loaded = MapStoreIO.readRegion(rootDir, regionId);
        if (loaded.tiles().isEmpty()) return false;
        ref.compareAndSet(null, loaded);
        diskMissCache.remove(regionId);
        synchronized (accessLock) { accessOrder.put(regionId, System.nanoTime()); }
        evictIfOver();
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
