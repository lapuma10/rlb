package net.runelite.client.plugins.recorder.trail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class TrailRegistryTest
{
    @Test
    public void loadsAllJsonFilesInDirectory() throws IOException
    {
        Path dir = Files.createTempDirectory("trails-");
        try
        {
            TrailIO.writeFile(new Trail("a", 1L, List.of(
                new TrailEvent.Tile(0L, new WorldPoint(1,1,0)))), dir.resolve("a.json"));
            TrailIO.writeFile(new Trail("b", 2L, List.of(
                new TrailEvent.Tile(0L, new WorldPoint(2,2,0)))), dir.resolve("b.json"));
            Files.writeString(dir.resolve("note.txt"), "ignore me");

            TrailRegistry reg = new TrailRegistry(dir);
            reg.load();
            assertEquals(2, reg.all().size());
            assertNotNull(reg.byName("a"));
            assertNotNull(reg.byName("b"));
            assertNull(reg.byName("missing"));
        }
        finally { deleteRecursive(dir); }
    }

    @Test
    public void missingDirectoryYieldsEmptyRegistry() throws IOException
    {
        Path dir = Files.createTempDirectory("trails-").resolve("nope");
        TrailRegistry reg = new TrailRegistry(dir);
        reg.load();
        assertEquals(0, reg.all().size());
    }

    @Test
    public void saveWritesUnderRegistryDir() throws IOException
    {
        Path dir = Files.createTempDirectory("trails-");
        try
        {
            TrailRegistry reg = new TrailRegistry(dir);
            Trail t = new Trail("save-test", 0L, List.of(
                new TrailEvent.Tile(0L, new WorldPoint(3,3,0))));
            reg.save(t);
            assertTrue(Files.exists(dir.resolve("save-test.json")));
            reg.load();
            assertNotNull(reg.byName("save-test"));
        }
        finally { deleteRecursive(dir); }
    }

    private static void deleteRecursive(Path p) throws IOException
    {
        if (!Files.exists(p)) return;
        try (var s = Files.walk(p))
        {
            s.sorted(java.util.Comparator.reverseOrder())
             .forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored){} });
        }
    }
}
