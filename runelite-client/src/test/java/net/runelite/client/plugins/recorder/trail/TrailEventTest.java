package net.runelite.client.plugins.recorder.trail;

import java.util.List;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class TrailEventTest
{
    @Test
    public void tileEventCarriesTileAndOffset()
    {
        TrailEvent.Tile t = new TrailEvent.Tile(600L, new WorldPoint(3208, 3220, 2));
        assertEquals("TILE", t.kind());
        assertEquals(600L, t.msSinceStart());
        assertEquals(new WorldPoint(3208, 3220, 2), t.tile());
    }

    @Test
    public void transportEventCarriesFullMetadata()
    {
        TrailEvent.Transport tr = new TrailEvent.Transport(
            12_000L,
            new WorldPoint(3205, 3229, 2),
            "Climb-down", "Staircase", 16671, "GameObject",
            3, 53, 14,
            List.of("Climb-down Staircase", "Cancel", "Examine Staircase"));
        assertEquals("TRANSPORT", tr.kind());
        assertEquals("Climb-down", tr.option());
        assertEquals(16671, tr.targetId());
        assertEquals(3, tr.actionId());
        assertEquals(List.of("Climb-down Staircase", "Cancel", "Examine Staircase"),
            tr.menuRowsAtClick());
    }
}
