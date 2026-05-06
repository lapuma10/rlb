package net.runelite.client.plugins.recorder.worldmap;

import net.runelite.api.coords.WorldPoint;

/** A single sighting of an NPC or game-object — what we saw and where.
 *  Immutable; identity is (kind, id, name, lastTile.region). Used by both
 *  EntityIndex (in-memory lookup) and the JSON persistence layer. */
public final class EntitySighting
{
    public enum Kind { NPC, OBJECT }

    public final Kind kind;
    public final int id;
    public final String name;
    public final WorldPoint lastTile;
    public final int seenCount;
    public final long lastSeenAt;

    public EntitySighting(Kind kind, int id, String name, WorldPoint lastTile,
                          int seenCount, long lastSeenAt)
    {
        this.kind = kind;
        this.id = id;
        this.name = name;
        this.lastTile = lastTile;
        this.seenCount = seenCount;
        this.lastSeenAt = lastSeenAt;
    }

    public EntitySighting withUpdatedSighting(WorldPoint newTile, long now)
    {
        return new EntitySighting(kind, id, name, newTile, seenCount + 1, now);
    }
}
