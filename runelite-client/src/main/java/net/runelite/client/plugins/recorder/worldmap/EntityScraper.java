package net.runelite.client.plugins.recorder.worldmap;

import net.runelite.api.NPC;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;

/** Sweeps the loaded NPC list each tick and records the most-recent
 *  sighting per (id, region) into the {@link EntityIndex}. Called on the
 *  client thread from the recorder plugin's tick subscriber. */
public final class EntityScraper
{
    public void scanNpcs(WorldView wv, long now, EntityIndex index)
    {
        if (wv == null) return;
        for (NPC npc : wv.npcs())
        {
            if (npc == null) continue;
            String name = npc.getName();
            if (name == null || name.isEmpty()) continue;
            WorldPoint loc = npc.getWorldLocation();
            if (loc == null) continue;
            index.recordNpcSighting(npc.getId(), name, loc, now);
        }
    }
}
