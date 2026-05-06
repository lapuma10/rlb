package net.runelite.client.plugins.recorder.worldmap;

import org.junit.Test;
import static org.junit.Assert.*;

public class SceneScraperSmokeTest
{
    @Test
    public void mapStore_publishedRegions_areIsolated()
    {
        MapStore store = new MapStore(new WorldMemoryConfig());
        RegionChunkBuilder a = store.builderFor(12850);
        a.setTile(3208, 3213, 0, 0);
        store.publish(12850, a);

        RegionChunkBuilder b = store.builderFor(12851);
        b.setTile(3272, 3213, 0, 0);
        store.publish(12851, b);

        assertEquals(1, store.snapshotFor(12850).tiles().size());
        assertEquals(1, store.snapshotFor(12851).tiles().size());
        assertNull(store.snapshotFor(12850).tile(3272, 3213, 0));
    }
}
