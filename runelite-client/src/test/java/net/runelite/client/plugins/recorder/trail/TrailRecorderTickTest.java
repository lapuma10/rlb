package net.runelite.client.plugins.recorder.trail;

import java.util.List;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class TrailRecorderTickTest
{
    @Test
    public void tickCapturesTileWhenChanged()
    {
        TrailRecorder rec = new TrailRecorder();
        rec.start("test");
        rec.recordTile(0L,    new WorldPoint(100, 100, 0));
        rec.recordTile(600L,  new WorldPoint(100, 101, 0));
        rec.recordTile(1200L, new WorldPoint(100, 101, 0)); // unchanged → drop
        rec.recordTile(1800L, new WorldPoint(101, 101, 0));
        Trail t = rec.stopAndBuild();
        assertEquals(3, t.events().size());
        assertEquals(new WorldPoint(100, 100, 0), t.events().get(0).tile());
        assertEquals(new WorldPoint(100, 101, 0), t.events().get(1).tile());
        assertEquals(new WorldPoint(101, 101, 0), t.events().get(2).tile());
    }

    @Test
    public void recordingNotStartedDropsAllEvents()
    {
        TrailRecorder rec = new TrailRecorder();
        rec.recordTile(0L, new WorldPoint(1, 1, 0));     // dropped — not started
        rec.start("a");
        rec.recordTile(100L, new WorldPoint(2, 2, 0));   // kept
        Trail t = rec.stopAndBuild();
        assertEquals(1, t.events().size());
        assertEquals(new WorldPoint(2, 2, 0), t.events().get(0).tile());
    }

    @Test
    public void msIsRelativeToStart()
    {
        TrailRecorder rec = new TrailRecorder();
        rec.startAt("rel", 10_000L);
        rec.recordTileAtAbsoluteMs(10_000L, new WorldPoint(0, 0, 0));
        rec.recordTileAtAbsoluteMs(10_600L, new WorldPoint(0, 1, 0));
        Trail t = rec.stopAndBuild();
        assertEquals(0L,   t.events().get(0).msSinceStart());
        assertEquals(600L, t.events().get(1).msSinceStart());
    }

    @Test
    public void stopWhileNotRecordingThrows()
    {
        TrailRecorder rec = new TrailRecorder();
        try { rec.stopAndBuild(); fail(); }
        catch (IllegalStateException ok) { /* expected */ }
    }
}
