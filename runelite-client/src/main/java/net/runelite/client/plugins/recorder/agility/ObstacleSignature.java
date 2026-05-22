package net.runelite.client.plugins.recorder.agility;

import java.util.Objects;
import net.runelite.api.coords.WorldPoint;

public final class ObstacleSignature
{
    public final int objectId;
    public final WorldPoint objectTile;
    public final String verb;

    public ObstacleSignature(int objectId, WorldPoint objectTile, String verb)
    {
        this.objectId   = objectId;
        this.objectTile = objectTile;
        this.verb       = verb;
    }

    @Override public boolean equals(Object o)
    {
        if (this == o) return true;
        if (!(o instanceof ObstacleSignature)) return false;
        ObstacleSignature s = (ObstacleSignature) o;
        return objectId == s.objectId
            && Objects.equals(objectTile, s.objectTile)
            && Objects.equals(verb, s.verb);
    }

    @Override public int hashCode() { return Objects.hash(objectId, objectTile, verb); }

    @Override public String toString() { return "Sig(" + objectId + "," + objectTile + ",'" + verb + "')"; }
}
