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
package net.runelite.client.plugins.recorder.mining;

import net.runelite.api.coords.WorldPoint;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Set;

/**
 * Picks the next rock for the mining loop.
 *
 * <p>The loop owns a list of pre-configured {@link Candidate}s — each
 * candidate is a world tile with optional ore-type metadata, supplied by the
 * user via the panel's "Add rock here" button. {@link #pick(List, WorldPoint,
 * Set)} chooses the closest live one, skipping candidates known to be
 * depleted as of this tick.
 *
 * <p>The class is intentionally stateless — depletion knowledge comes from
 * the caller (typically {@link MiningStateTracker}'s recently-depleted ring).
 * That keeps the selector mockable in tests with hand-built candidate lists.
 */
public final class RockSelector
{
    /** A rock the user has flagged as "this is one of mine". */
    public record Candidate(WorldPoint tile, @Nullable OreType oreType)
    {
        public Candidate(WorldPoint tile) { this(tile, null); }
    }

    /**
     * Find the closest live candidate to {@code playerPos}.
     *
     * @param candidates    user-supplied rock tiles. Order is significant for
     *                      tie-breaking — earlier added wins ties.
     * @param playerPos     local player's world position. Returns null if null.
     * @param depletedTiles tiles that are known-depleted *right now* — typically
     *                      the recent-depletion ring from
     *                      {@link MiningStateTracker}. Empty / null is fine.
     * @return the chosen candidate, or null if no live candidate is on
     *         {@code playerPos}'s plane within reach.
     */
    @Nullable
    public Candidate pick(List<Candidate> candidates, @Nullable WorldPoint playerPos,
                          @Nullable Set<WorldPoint> depletedTiles)
    {
        if (candidates == null || candidates.isEmpty() || playerPos == null) return null;
        Candidate best = null;
        int bestDist = Integer.MAX_VALUE;
        int bestOrder = Integer.MAX_VALUE;
        for (int i = 0; i < candidates.size(); i++)
        {
            Candidate c = candidates.get(i);
            if (c == null || c.tile() == null) continue;
            if (c.tile().getPlane() != playerPos.getPlane()) continue;
            if (depletedTiles != null && depletedTiles.contains(c.tile())) continue;
            int dist = c.tile().distanceTo(playerPos);
            // distanceTo returns Integer.MAX_VALUE for plane mismatch; we
            // already filtered planes, so any large value here is genuine
            // out-of-scene (also fine to skip — won't be reachable).
            if (dist >= MAX_TRACKING_TILES) continue;
            if (dist < bestDist || (dist == bestDist && i < bestOrder))
            {
                best = c;
                bestDist = dist;
                bestOrder = i;
            }
        }
        return best;
    }

    /**
     * Maximum world-tile distance from the player at which we'll consider a
     * candidate rock pickable. Beyond this the rock is almost certainly out
     * of the loaded scene, so the engine can't see it / target it. The
     * scene radius is ~52 tiles around the player; 32 is a safe inner cap.
     */
    public static final int MAX_TRACKING_TILES = 32;
}
