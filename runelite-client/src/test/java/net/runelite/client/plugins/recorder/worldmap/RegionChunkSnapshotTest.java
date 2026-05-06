package net.runelite.client.plugins.recorder.worldmap;

import java.util.Collections;
import java.util.List;
import org.junit.Test;
import net.runelite.api.coords.WorldPoint;
import static org.junit.Assert.*;

public class RegionChunkSnapshotTest
{
    @Test
    public void packAndUnpackTileKey_roundTrips()
    {
        long k = RegionChunkSnapshot.packTileKey(3208, 3213, 0);
        assertEquals(3208, RegionChunkSnapshot.unpackX(k));
        assertEquals(3213, RegionChunkSnapshot.unpackY(k));
        assertEquals(0,    RegionChunkSnapshot.unpackPlane(k));
    }

    @Test
    public void emptySnapshot_hasNoTiles()
    {
        RegionChunkSnapshot s = RegionChunkSnapshot.empty(12850);
        assertEquals(12850, s.regionId());
        assertEquals(0, s.tiles().size());
        assertEquals(0, s.objects().size());
        assertNull(s.tile(3208, 3213, 0));
    }

    @Test
    public void snapshotFromBuilder_exposesUnmodifiableViews()
    {
        RegionChunkBuilder b = new RegionChunkBuilder(12850);
        b.setTile(3208, 3213, 0, 0);
        RegionChunkSnapshot s = RegionChunkSnapshot.fromBuilder(b);

        // Tile is queryable.
        RegionChunkSnapshot.TileEntry t = s.tile(3208, 3213, 0);
        assertNotNull(t);
        assertEquals(0, t.movement);

        // Builder mutation does NOT leak to snapshot.
        b.setTile(3209, 3213, 0, 999);
        assertNull(s.tile(3209, 3213, 0));
    }
}
