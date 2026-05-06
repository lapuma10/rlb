package net.runelite.client.plugins.recorder.worldmap;

import java.io.File;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class MapStoreIOTest
{
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void writeThenRead_roundTripsTilesAndObjects() throws Exception
    {
        File dir = tmp.newFolder();
        RegionChunkBuilder b = new RegionChunkBuilder(12850);
        b.gameRevision = 238;
        b.lastScrapedAt = 1714960000000L;
        b.setTile(3208, 3213, 0, 0x0);
        b.setTile(3209, 3214, 0, net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_FULL);  // floor-blocked (range tile)
        RegionChunkSnapshot snap = RegionChunkSnapshot.fromBuilder(b);

        MapStoreIO.writeRegion(dir, snap);
        RegionChunkSnapshot reloaded = MapStoreIO.readRegion(dir, 12850);

        assertEquals(238, reloaded.gameRevision());
        assertEquals(2, reloaded.tiles().size());
        assertNotNull(reloaded.tile(3208, 3213, 0));
        // BLOCK_MOVEMENT_FULL = BLOCK_MOVEMENT_OBJECT | BLOCK_MOVEMENT_FLOOR_DECORATION | BLOCK_MOVEMENT_FLOOR
        //                     = 0x100 | 0x40000 | 0x200000 = 0x240100
        assertEquals(net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_FULL,
            reloaded.tile(3209, 3214, 0).movement);
    }

    @Test
    public void readMissingRegion_returnsEmpty() throws Exception
    {
        File dir = tmp.newFolder();
        RegionChunkSnapshot s = MapStoreIO.readRegion(dir, 99999);
        assertEquals(99999, s.regionId());
        assertEquals(0, s.tiles().size());
    }

    @Test
    public void readJsonWithUnknownField_ignoresIt() throws Exception
    {
        File dir = tmp.newFolder();
        File regionsDir = new File(dir, "regions");
        regionsDir.mkdirs();
        File f = new File(regionsDir, "12850.json");
        // Hand-write JSON with an unknown future field.
        Files.writeString(f.toPath(),
            "{\"schema\":1,\"regionId\":12850,\"gameRevision\":238,"
            + "\"lastScrapedAt\":1,\"tiles\":[],\"objects\":[],"
            + "\"futureField\":\"ignored\"}");
        RegionChunkSnapshot s = MapStoreIO.readRegion(dir, 12850);
        assertEquals(238, s.gameRevision());
    }
}
