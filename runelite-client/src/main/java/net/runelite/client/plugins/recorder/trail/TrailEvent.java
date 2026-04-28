package net.runelite.client.plugins.recorder.trail;

import java.util.List;
import net.runelite.api.coords.WorldPoint;

/**
 * One recorded event in a {@link Trail}. Either a {@link Tile}
 * (player position changed) or a {@link Transport} (the user clicked a
 * region-transition menu entry). Sealed-style: {@link #kind()} returns
 * {@code "TILE"} or {@code "TRANSPORT"}; pattern-match by class.
 */
public sealed interface TrailEvent permits TrailEvent.Tile, TrailEvent.Transport
{
    long msSinceStart();
    String kind();
    WorldPoint tile();

    record Tile(long msSinceStart, WorldPoint tile) implements TrailEvent
    {
        @Override public String kind() { return "TILE"; }
    }

    record Transport(
        long msSinceStart,
        WorldPoint tile,
        String option,
        String target,
        int targetId,
        String targetKind,
        int actionId,
        int param0,
        int param1,
        List<String> menuRowsAtClick) implements TrailEvent
    {
        @Override public String kind() { return "TRANSPORT"; }
    }
}
