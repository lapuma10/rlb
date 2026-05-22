package net.runelite.client.plugins.recorder.agility;

import net.runelite.api.coords.WorldPoint;

public class PendingClick
{
    public int objectId;
    public String verb;
    public WorldPoint objectTile;
    public WorldPoint sourceTile;
    public long clickAtMs;
    public long deadlineMs;
    public long xpBefore;
    public ClickOutcome outcome;

    public PendingClick(int objectId, String verb, WorldPoint objectTile, WorldPoint sourceTile,
                        long clickAtMs, long deadlineMs, long xpBefore)
    {
        this.objectId   = objectId;
        this.verb       = verb;
        this.objectTile = objectTile;
        this.sourceTile = sourceTile;
        this.clickAtMs  = clickAtMs;
        this.deadlineMs = deadlineMs;
        this.xpBefore   = xpBefore;
        this.outcome    = ClickOutcome.PENDING;
    }
}
