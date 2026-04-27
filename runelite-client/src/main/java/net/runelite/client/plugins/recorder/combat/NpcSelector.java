/*
 * Copyright (c) 2026, RuneLite
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.recorder.combat;

import net.runelite.api.Actor;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import javax.annotation.Nullable;
import java.util.Iterator;
import java.util.Objects;

/**
 * Picks the best NPC for the combat loop to attack next.
 *
 * <p>Filters: name match (case-insensitive), same plane, alive, not engaged
 * by another player, within attack range. Score: closer = better. Ties broken
 * by NPC index (stable) so test ordering is deterministic.
 *
 * <p>The class is intentionally stateless — callers pass the live NPC iterable
 * from the client world view per call (on the client thread). This keeps the
 * selector mockable in tests with hand-built NPC lists.
 */
public final class NpcSelector
{
    /**
     * Default attack range in world tiles. Chickens stand still, so 6 is plenty.
     */
    public static final int DEFAULT_RANGE = 6;

    private final String nameFilter;
    private final int range;

    public NpcSelector(String nameFilter)
    {
        this(nameFilter, DEFAULT_RANGE);
    }

    public NpcSelector(String nameFilter, int range)
    {
        this.nameFilter = Objects.requireNonNull(nameFilter, "nameFilter");
        this.range = range;
    }

    /**
     * Find the closest unengaged matching NPC near {@code playerPos}, ignoring
     * any NPC whose index is in {@code excludedIndex} (e.g. the chicken we just
     * killed but haven't been despawned yet). Returns null if no candidate
     * passes the filter.
     *
     * @param npcs       the world view's NPC iterable (typically
     *                   {@code client.getTopLevelWorldView().npcs()}).
     * @param self       the local player — used to detect "I'm interacting with
     *                   this NPC" (we treat that as engaged-by-self, which is
     *                   fine to re-target). Only "engaged-by-other" disqualifies.
     * @param playerPos  the player's current world position.
     * @param excludedIndex the NPC index to skip, or -1 for none.
     */
    @Nullable
    public NPC pick(Iterable<? extends NPC> npcs, @Nullable Player self,
                    @Nullable WorldPoint playerPos, int excludedIndex)
    {
        return pick(npcs, self, playerPos, excludedIndex, null,
            TargetVisibility.alwaysVisible());
    }

    /** Full overload — same logic as the simpler signatures, plus a
     *  visibility filter that culls NPCs the local player cannot directly
     *  see (behind walls / off-screen / under an open menu). The {@code wv}
     *  is forwarded to {@link TargetVisibility#canSee} for the world LOS
     *  test. */
    @Nullable
    public NPC pick(Iterable<? extends NPC> npcs, @Nullable Player self,
                    @Nullable WorldPoint playerPos, int excludedIndex,
                    @Nullable WorldView wv, TargetVisibility visibility)
    {
        if (npcs == null || playerPos == null) return null;
        if (visibility == null) visibility = TargetVisibility.alwaysVisible();
        NPC best = null;
        int bestDist = Integer.MAX_VALUE;
        int bestIndex = Integer.MAX_VALUE;
        for (NPC npc : npcs)
        {
            if (npc == null) continue;
            if (npc.getIndex() == excludedIndex) continue;
            if (!matchesName(npc)) continue;
            WorldPoint npcLoc = npc.getWorldLocation();
            if (npcLoc == null) continue;
            if (npcLoc.getPlane() != playerPos.getPlane()) continue;
            // Health ratio == 0 means HP bar is empty (dying / dead). Some
            // NPCs report -1 when no HP info is transmitted yet — treat that
            // as "alive (unknown)" since chickens always have a HP scale once
            // engaged.
            if (npc.getHealthRatio() == 0) continue;
            int dist = npcLoc.distanceTo(playerPos);
            if (dist > range) continue;
            // Reject if engaged by another player (someone else's chicken).
            // If the NPC's interacting target is the local player, we don't
            // exclude it — that's our prior engagement we may want to resume.
            Actor interacting = npc.getInteracting();
            if (interacting instanceof Player p && p != self) continue;
            // Visibility — last filter, most expensive (canvas + collision
            // reads). Closest *visible* chicken wins.
            if (!visibility.canSee(npc, self, wv)) continue;
            int idx = npc.getIndex();
            if (dist < bestDist || (dist == bestDist && idx < bestIndex))
            {
                best = npc;
                bestDist = dist;
                bestIndex = idx;
            }
        }
        return best;
    }

    /** Convenience wrapper without an excluded index. */
    @Nullable
    public NPC pick(Iterable<? extends NPC> npcs, @Nullable Player self,
                    @Nullable WorldPoint playerPos)
    {
        return pick(npcs, self, playerPos, -1);
    }

    /** Convenience for callers that hand us a {@code Iterator} (e.g. mocked). */
    @Nullable
    public NPC pick(Iterator<? extends NPC> it, @Nullable Player self,
                    @Nullable WorldPoint playerPos)
    {
        if (it == null) return null;
        java.util.List<NPC> list = new java.util.ArrayList<>();
        while (it.hasNext()) list.add(it.next());
        return pick(list, self, playerPos);
    }

    private boolean matchesName(NPC npc)
    {
        // Use composition name when available — Actor.getName() can return
        // "<col=...>Chicken</col>" with embedded markup on some NPCs. The
        // composition is the unstyled canonical name.
        NPCComposition c = npc.getComposition();
        String name = c == null ? npc.getName() : c.getName();
        if (name == null) return false;
        return nameFilter.equalsIgnoreCase(stripMarkup(name));
    }

    private static String stripMarkup(String s)
    {
        return s.replaceAll("<[^>]+>", "").trim();
    }

    public String nameFilter() { return nameFilter; }
    public int range() { return range; }
}
