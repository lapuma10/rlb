package net.runelite.client.plugins.recorder.worldmap;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class TransportIOTest
{
    @Test
    public void writeThenRead_preservesAllFields() throws IOException
    {
        Path tmp = Files.createTempDirectory("transport-io-rt-");
        TransportEdge in = sampleEdge();
        TransportIO.writeAll(tmp.toFile(), List.of(in));

        List<TransportEdge> out = TransportIO.readAll(tmp.toFile());

        assertEquals(1, out.size());
        TransportEdge got = out.get(0);
        assertEquals(in.fromTile(), got.fromTile());
        assertEquals(in.toTile(), got.toTile());
        assertEquals(in.objectId(), got.objectId());
        assertEquals(in.objectName(), got.objectName());
        assertEquals(in.verb(), got.verb());
        assertEquals(in.param0(), got.param0());
        assertEquals(in.param1(), got.param1());
        assertEquals(in.targetKind(), got.targetKind());
        assertEquals(in.approachTile(), got.approachTile());
        assertEquals(in.regionId(), got.regionId());
        assertEquals(in.seenCount(), got.seenCount());
        assertEquals(in.lastSeenAtMs(), got.lastSeenAtMs());
        assertEquals(in.observedDurationMs(), got.observedDurationMs());
    }

    @Test
    public void readAll_missingFile_returnsEmptyList() throws IOException
    {
        Path tmp = Files.createTempDirectory("transport-io-empty-");

        List<TransportEdge> out = TransportIO.readAll(tmp.toFile());

        assertTrue("missing file → empty list, no exception", out.isEmpty());
    }

    @Test
    public void readAll_corruptFile_returnsEmptyList() throws IOException
    {
        Path tmp = Files.createTempDirectory("transport-io-corrupt-");
        File f = new File(tmp.toFile(), "transports.json");
        Files.writeString(f.toPath(), "not json at all {{{");

        List<TransportEdge> out = TransportIO.readAll(tmp.toFile());

        assertTrue("corrupt JSON → empty list, no exception", out.isEmpty());
    }

    @Test
    public void writeAll_overwrites_existingFile() throws IOException
    {
        Path tmp = Files.createTempDirectory("transport-io-overwrite-");
        TransportIO.writeAll(tmp.toFile(), List.of(sampleEdge(), sampleEdge2()));
        assertEquals(2, TransportIO.readAll(tmp.toFile()).size());

        TransportIO.writeAll(tmp.toFile(), List.of(sampleEdge()));

        assertEquals("write replaces, does not append", 1,
            TransportIO.readAll(tmp.toFile()).size());
    }

    @Test
    public void writeAll_emptyCollection_writesEmptyArray() throws IOException
    {
        Path tmp = Files.createTempDirectory("transport-io-empty-write-");
        TransportIO.writeAll(tmp.toFile(), List.of());

        File f = TransportIO.transportsFile(tmp.toFile());
        assertTrue("file is created even for empty edges", f.exists());
        assertTrue(TransportIO.readAll(tmp.toFile()).isEmpty());
    }

    private static TransportEdge sampleEdge()
    {
        WorldPoint from = new WorldPoint(3208, 3216, 2);
        WorldPoint to = new WorldPoint(3206, 3216, 1);
        return new TransportEdge(
            from, to,
            16671, "Staircase", "Climb-down",
            53, 14, "GAME_OBJECT_FIRST_OPTION",
            from, from.getRegionID(),
            3, 1_700_000_000_000L, 1_200L);
    }

    private static TransportEdge sampleEdge2()
    {
        WorldPoint from = new WorldPoint(3208, 3220, 0);
        WorldPoint to = new WorldPoint(3208, 3221, 0);
        return new TransportEdge(
            from, to,
            1530, "Door", "Open",
            56, 18, "GAME_OBJECT_FIRST_OPTION",
            from, from.getRegionID(),
            1, 1_700_000_001_000L, 600L);
    }
}
