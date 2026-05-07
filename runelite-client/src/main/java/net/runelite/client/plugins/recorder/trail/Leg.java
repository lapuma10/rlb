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

    /** @param tile           the transport object's own tile (where the verb
     *                        click is targeted; what {@link Trail} records as
     *                        the TRANSPORT event tile).
     *  @param approachTile   tile the player was standing on when the verb
     *                        was used in the recording — i.e. the tile the
     *                        engine routed them to right before applying the
     *                        transport.  May be null for legacy data; when
     *                        null, walkers fall back to {@code tile}.  This
     *                        is the correct destination for a "walk closer
     *                        to the transport" click — clicking {@code tile}
     *                        directly often pathfinds AROUND walls because
     *                        the object's own tile is inside the wall geometry. */
    record Transport(WorldPoint tile, String verb, int objectId,
                     String targetKind, int param0, int param1,
                     WorldPoint approachTile) implements Leg
    {
        /** Backward-compatible 6-arg constructor for callers that don't
         *  know the standing-tile.  Approach tile defaults to {@code null};
         *  walkers fall back to {@code tile} for the walk-closer target. */
        public Transport(WorldPoint tile, String verb, int objectId,
                         String targetKind, int param0, int param1)
        {
            this(tile, verb, objectId, targetKind, param0, param1, null);
        }
        @Override public String kind() { return "TRANSPORT"; }
    }
}
