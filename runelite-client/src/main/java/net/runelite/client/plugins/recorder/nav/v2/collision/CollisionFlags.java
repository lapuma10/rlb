package net.runelite.client.plugins.recorder.nav.v2.collision;

import net.runelite.api.CollisionDataFlag;
import net.runelite.api.coords.WorldPoint;

/** Small value-type wrapping a RuneLite-style collision-flag bitfield and
 *  the source it came from. Equivalent to "an int + an enum"; we boxed it
 *  so the {@link
 *  net.runelite.client.plugins.recorder.nav.v2.collision.WorldSnapshot#collisionAt(WorldPoint)}
 *  contract reads as one value instead of two.
 *
 *  <p>Internally, the {@code int} bitfield uses the constants in
 *  {@link CollisionDataFlag} so RuneLite consumers can mask without
 *  translation. Helper booleans wrap the most common queries to keep
 *  the BFS hot path tidy.
 *
 *  <p>This is the {@code CollisionFlags} contract type referenced in
 *  spec §3 ({@code WorldSnapshot.collisionAt(WorldPoint)}). */
public final class CollisionFlags
{
    private final int flags;
    private final CollisionView.Source source;
    private final WorldPoint at;

    public CollisionFlags(int flags, CollisionView.Source source, WorldPoint at)
    {
        this.flags = flags;
        this.source = source;
        this.at = at;
    }

    /** The raw bitfield — masks against {@link CollisionDataFlag}. */
    public int bits() { return flags; }

    /** Which collision source produced these flags. */
    public CollisionView.Source source() { return source; }

    /** The tile these flags describe. */
    public WorldPoint at() { return at; }

    /** True iff the tile is fully blocked (no walkable direction). */
    public boolean isFullyBlocked()
    {
        return (flags & CollisionDataFlag.BLOCK_MOVEMENT_FULL) != 0;
    }

    /** True iff movement in the given cardinal direction is permitted by
     *  the directional-block bits at this tile. Does NOT consult the
     *  neighbour tile's bits; combine with the neighbour query for a full
     *  walkability check. */
    public boolean canMoveNorth() { return (flags & CollisionDataFlag.BLOCK_MOVEMENT_NORTH) == 0; }
    public boolean canMoveSouth() { return (flags & CollisionDataFlag.BLOCK_MOVEMENT_SOUTH) == 0; }
    public boolean canMoveEast()  { return (flags & CollisionDataFlag.BLOCK_MOVEMENT_EAST)  == 0; }
    public boolean canMoveWest()  { return (flags & CollisionDataFlag.BLOCK_MOVEMENT_WEST)  == 0; }

    @Override
    public String toString()
    {
        return String.format("CollisionFlags(0x%x,%s,%s)", flags, source, at);
    }
}
