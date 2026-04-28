package net.runelite.client.plugins.recorder.trail;

import java.util.Collections;
import java.util.List;
import net.runelite.api.coords.WorldPoint;

/** One step in a {@link TrailPath}. Either a {@link Walk} along a list of
 *  tiles, or a {@link Transport} click on a recorded object. */
public sealed interface Leg permits Leg.Walk, Leg.Transport
{
    String kind();

    record Walk(List<WorldPoint> tiles) implements Leg
    {
        public Walk(List<WorldPoint> tiles)
        {
            if (tiles == null || tiles.isEmpty())
                throw new IllegalArgumentException("walk leg has no tiles");
            this.tiles = Collections.unmodifiableList(List.copyOf(tiles));
        }
        @Override public String kind() { return "WALK"; }
    }

    record Transport(WorldPoint tile, String verb, int objectId,
                     String targetKind, int param0, int param1) implements Leg
    {
        @Override public String kind() { return "TRANSPORT"; }
    }
}
