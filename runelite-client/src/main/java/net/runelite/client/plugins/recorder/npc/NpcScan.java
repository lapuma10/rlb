package net.runelite.client.plugins.recorder.npc;

import net.runelite.api.coords.WorldPoint;

/**
 * Result of a single scene scan for an NPC matching some criteria. See
 * {@link NpcInteraction#findOnScene}.
 *
 * <p>{@code npcIndex} is {@code -1} when nothing matched; {@link #found()}
 * is the readable form of that check. The {@code diagnostic} field is
 * always populated (hit or miss) — on miss it lists nearby NPCs so a
 * failed match's reason is greppable from the log; on hit it records
 * which arm matched ({@code matched-by=id|name}) and whether the model
 * has a hull poly available for clicking.
 *
 * <p>This is a value type; safe to pass between threads.
 */
public record NpcScan(int npcIndex, WorldPoint tile, boolean onCanvas, String diagnostic)
{
    /** True iff a matching NPC was located on the scanned scene. */
    public boolean found() { return npcIndex >= 0; }

    /** Build a miss result with no NPC and no tile, carrying only the
     *  diagnostic string. Use this in {@link NpcInteraction#findOnScene}
     *  for the "no local player / no tile / no match" branches so the
     *  caller can read a uniform shape regardless of why nothing
     *  matched. */
    public static NpcScan miss(String diagnostic)
    {
        return new NpcScan(-1, null, false, diagnostic);
    }
}
