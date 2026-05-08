package net.runelite.client.plugins.recorder.worldmap;

import java.io.File;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Round-2 lazy-load: after the LRU evicts a region, the planner asking
 *  for it via {@link MapStore#snapshotFor} must rehydrate from disk
 *  (when {@code rootDir} is wired) instead of returning null. Without
 *  this, V2_STRICT NO_ROUTEs the moment the bot's destination region
 *  drops out of the in-memory cap. */
public class MapStoreLazyLoadTest
{
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static RegionChunkSnapshot tinySnap(int regionId)
    {
        RegionChunkBuilder b = new RegionChunkBuilder(regionId);
        b.gameRevision = 238;
        b.lastScrapedAt = 1L;
        b.setTile(3208, 3213, 0, 0x0);
        return RegionChunkSnapshot.fromBuilder(b);
    }

    @Test
    public void snapshotFor_evicted_loadsFromDiskWhenRootSet() throws Exception
    {
        File dir = tmp.newFolder();
        // Persist region 12850 to disk.
        MapStoreIO.writeRegion(dir, tinySnap(12850));

        MapStore store = new MapStore(new WorldMemoryConfig());
        store.setRootDir(dir);

        // Memory miss; disk hit → snapshot returned.
        RegionChunkSnapshot s = store.snapshotFor(12850);
        assertNotNull("evicted region must rehydrate from disk", s);
        assertNotNull(s.tile(3208, 3213, 0));
    }

    @Test
    public void snapshotFor_diskMiss_cachedAndReturnsNullSubsequently() throws Exception
    {
        File dir = tmp.newFolder();
        MapStore store = new MapStore(new WorldMemoryConfig());
        store.setRootDir(dir);

        // Region 99999 has no JSON on disk.
        assertNull(store.snapshotFor(99999));
        assertNull("repeat call must also return null without re-stat'ing",
            store.snapshotFor(99999));
    }

    @Test
    public void snapshotFor_publishClearsDiskMissCache() throws Exception
    {
        File dir = tmp.newFolder();
        MapStore store = new MapStore(new WorldMemoryConfig());
        store.setRootDir(dir);

        assertNull("first call: disk miss", store.snapshotFor(12850));
        // Now publish a live snapshot for the same region.
        RegionChunkBuilder b = store.builderFor(12850);
        b.gameRevision = 238;
        b.lastScrapedAt = 1L;
        b.setTile(3208, 3213, 0, 0x0);
        store.publish(12850, b);

        // Subsequent call must see the freshly published snapshot, not
        // the cached "disk miss".
        RegionChunkSnapshot s = store.snapshotFor(12850);
        assertNotNull("publish must invalidate the disk-miss cache", s);
    }

    @Test
    public void snapshotFor_noRootDir_returnsNullOnMiss()
    {
        // Production-safe default: no rootDir wired, no lazy-load.
        MapStore store = new MapStore(new WorldMemoryConfig());
        assertNull(store.snapshotFor(12850));
    }
}
