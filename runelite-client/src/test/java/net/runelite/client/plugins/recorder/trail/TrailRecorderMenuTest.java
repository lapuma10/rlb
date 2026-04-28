package net.runelite.client.plugins.recorder.trail;

import java.util.List;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class TrailRecorderMenuTest
{
    @Test
    public void recordsTransportClickWithFullMetadata()
    {
        TrailRecorder rec = new TrailRecorder();
        rec.start("trail-m");
        rec.recordTransport(500L,
            new WorldPoint(3205, 3229, 2),
            "Climb-down", "Staircase", 16671, "GameObject",
            3, 53, 14,
            List.of("Climb-down Staircase", "Cancel", "Examine Staircase"));
        Trail t = rec.stopAndBuild();
        assertEquals(1, t.events().size());
        assertTrue(t.events().get(0) instanceof TrailEvent.Transport);
        TrailEvent.Transport tr = (TrailEvent.Transport) t.events().get(0);
        assertEquals("Climb-down", tr.option());
        assertEquals(16671, tr.targetId());
        assertEquals(53, tr.param0());
        assertEquals(14, tr.param1());
        assertEquals(List.of("Climb-down Staircase", "Cancel", "Examine Staircase"),
            tr.menuRowsAtClick());
    }

    @Test
    public void filtersNonTransportVerbs()
    {
        TrailRecorder rec = new TrailRecorder();
        rec.start("trail-m");
        boolean kept = rec.recordTransportIfWhitelisted(
            500L, new WorldPoint(0, 0, 0),
            "Talk-to", "Bob", 0, "NPC", 0, 0, 0, List.of());
        assertFalse("non-whitelisted Talk-to should be filtered", kept);
        Trail t = rec.stopAndBuild();
        assertEquals(0, t.events().size());
    }

    @Test
    public void keepsWhitelistedVerb()
    {
        TrailRecorder rec = new TrailRecorder();
        rec.start("trail-m");
        boolean kept = rec.recordTransportIfWhitelisted(
            100L, new WorldPoint(1, 1, 0),
            "Open", "Gate", 1234, "WallObject", 0, 0, 0, List.of("Open Gate"));
        assertTrue(kept);
        Trail t = rec.stopAndBuild();
        assertEquals(1, t.events().size());
    }
}
