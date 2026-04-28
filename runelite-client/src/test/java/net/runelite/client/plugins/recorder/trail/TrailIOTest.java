package net.runelite.client.plugins.recorder.trail;

import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class TrailIOTest
{
    @Test
    public void roundTripPreservesAllFields()
    {
        Trail original = new Trail("lumby-bank-to-pen", 1714247000000L, List.of(
            new TrailEvent.Tile(0L,   new WorldPoint(3208, 3220, 2)),
            new TrailEvent.Tile(600L, new WorldPoint(3208, 3221, 2)),
            new TrailEvent.Transport(12_000L, new WorldPoint(3205, 3229, 2),
                "Climb-down", "Staircase", 16671, "GameObject",
                3, 53, 14,
                List.of("Climb-down Staircase", "Cancel", "Examine Staircase")),
            new TrailEvent.Tile(12_600L, new WorldPoint(3205, 3229, 1))
        ));

        StringWriter sw = new StringWriter();
        TrailIO.write(original, sw);
        String json = sw.toString();
        assertTrue("JSON missing version", json.contains("\"version\""));
        assertTrue("JSON missing transport metadata", json.contains("\"targetId\":16671"));
        assertTrue("JSON missing menuRowsAtClick",
            json.contains("Climb-down Staircase"));

        Trail roundTripped = TrailIO.read(new StringReader(json));
        assertEquals("lumby-bank-to-pen", roundTripped.name());
        assertEquals(1714247000000L, roundTripped.recordedAt());
        assertEquals(4, roundTripped.events().size());
        TrailEvent t0 = roundTripped.events().get(0);
        assertTrue(t0 instanceof TrailEvent.Tile);
        assertEquals(new WorldPoint(3208, 3220, 2), t0.tile());
        TrailEvent t2 = roundTripped.events().get(2);
        assertTrue(t2 instanceof TrailEvent.Transport);
        TrailEvent.Transport tr = (TrailEvent.Transport) t2;
        assertEquals("Climb-down", tr.option());
        assertEquals(16671, tr.targetId());
        assertEquals(53, tr.param0());
        assertEquals(14, tr.param1());
        assertEquals(List.of("Climb-down Staircase", "Cancel", "Examine Staircase"),
            tr.menuRowsAtClick());
    }

    @Test
    public void readMissingFieldThrows()
    {
        // Older / hand-edited file missing 'name' should fail loudly so we
        // don't load a half-broken trail and confuse the planner.
        try
        {
            TrailIO.read(new StringReader("{\"version\":1,\"events\":[]}"));
            fail("expected IllegalArgumentException for missing name");
        }
        catch (IllegalArgumentException ok) { /* expected */ }
    }

    @Test
    public void writeAndReadFile() throws Exception
    {
        Path tmp = Files.createTempFile("trail-iotest-", ".json");
        try
        {
            Trail t = new Trail("tiny", 0L, List.of(
                new TrailEvent.Tile(0L, new WorldPoint(3, 2, 1))));
            TrailIO.writeFile(t, tmp);
            Trail back = TrailIO.readFile(tmp);
            assertEquals("tiny", back.name());
            assertEquals(1, back.events().size());
        }
        finally { Files.deleteIfExists(tmp); }
    }
}
