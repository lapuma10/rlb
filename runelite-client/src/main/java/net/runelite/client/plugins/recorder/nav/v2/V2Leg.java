package net.runelite.client.plugins.recorder.nav.v2;

import java.util.List;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.worldmap.TransportEdge;

/** A single leg of a V2 path: either a tile-by-tile walk inside one
 *  region (or one continuous walk across region boundaries — see
 *  {@link Walk#regionId}) or a transport invocation (stair, gate, ladder,
 *  door, climb action).
 *
 *  <p>Sealed to keep the executor's state machine exhaustive — the V2
 *  executor switches on the leg subtype to decide whether to drive a
 *  walk-click or a verb-click. */
public sealed interface V2Leg permits V2Leg.Walk, V2Leg.Transport
{
    /** Walk leg: a sequence of WorldPoints from {@code tiles[0]} to
     *  {@code tiles[last]}. {@code regionId} is the region the leg
     *  primarily lives in (the first tile's region). The full tile
     *  sequence may cross region boundaries; the executor doesn't
     *  branch on region inside a single Walk. */
    record Walk(int regionId, List<WorldPoint> tiles) implements V2Leg
    {
        public Walk
        {
            if (tiles == null || tiles.isEmpty())
                throw new IllegalArgumentException("Walk leg requires at least one tile");
            tiles = List.copyOf(tiles);
        }

        public WorldPoint start() { return tiles.get(0); }
        public WorldPoint end() { return tiles.get(tiles.size() - 1); }
    }

    /** Transport leg: the executor right-clicks the {@link TransportEdge}'s
     *  approach tile / object and selects the verb. */
    record Transport(TransportEdge edge) implements V2Leg
    {
        public Transport
        {
            if (edge == null) throw new IllegalArgumentException("transport edge null");
        }
    }
}
