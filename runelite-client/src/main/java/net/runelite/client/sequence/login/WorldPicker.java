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
package net.runelite.client.sequence.login;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import javax.annotation.Nullable;
import net.runelite.api.World;
import net.runelite.api.WorldType;

/**
 * Filters and picks a free-to-play world for login. Lives separately from
 * LoginAssistant so the picker logic is unit-testable without spinning up a
 * real client. Default candidate list is hard-coded to known-stable F2P
 * worlds (308, 316, 326, 335, 381) so we don't need a network round-trip
 * against the Jagex world directory at login time.
 */
public final class WorldPicker
{
    /** Default candidates. F2P, English, no special types (DMM/Tournament/etc).
     *  Update list when these worlds get reassigned by Jagex. */
    public static final List<Integer> DEFAULT_F2P_WORLDS =
        Collections.unmodifiableList(Arrays.asList(308, 316, 326, 335, 381));

    /** World types that make a world ineligible for automated F2P login. */
    private static final EnumSet<WorldType> EXCLUDED = EnumSet.of(
        WorldType.MEMBERS,
        WorldType.PVP,
        WorldType.DEADMAN,
        WorldType.BOUNTY,
        WorldType.PVP_ARENA,
        WorldType.LAST_MAN_STANDING,
        WorldType.HIGH_RISK,
        WorldType.TOURNAMENT_WORLD,
        WorldType.FRESH_START_WORLD,
        WorldType.SEASONAL,
        WorldType.BETA_WORLD,
        WorldType.SKILL_TOTAL,
        WorldType.QUEST_SPEEDRUNNING
    );

    private final List<Integer> candidates;
    private final Random rng;

    public WorldPicker()
    {
        this(DEFAULT_F2P_WORLDS, new Random());
    }

    public WorldPicker(Random rng)
    {
        this(DEFAULT_F2P_WORLDS, rng);
    }

    public WorldPicker(List<Integer> candidates, Random rng)
    {
        if (candidates == null || candidates.isEmpty())
            throw new IllegalArgumentException("candidates required");
        if (rng == null) throw new IllegalArgumentException("rng required");
        // Defensive copy.
        this.candidates = Collections.unmodifiableList(new java.util.ArrayList<>(candidates));
        this.rng = rng;
    }

    public List<Integer> getCandidates() { return candidates; }

    /** Pick a random F2P world from the candidate list, optionally avoiding
     *  {@code currentWorld} so we don't switch to the world we're already
     *  on. Returns -1 only if the candidate list contains a single element
     *  equal to currentWorld (caller treats as "stay"). */
    public int pickRandom(int currentWorld)
    {
        // Filter out the current world to force a switch (matches the OSRS
        // "world hop" intent — picking the same world is a no-op).
        List<Integer> usable = new java.util.ArrayList<>(candidates.size());
        for (int w : candidates) if (w != currentWorld) usable.add(w);
        if (usable.isEmpty()) return -1;
        return usable.get(rng.nextInt(usable.size()));
    }

    /** True if {@code worldId} appears in this picker's F2P candidate list. */
    public boolean isF2P(int worldId)
    {
        return candidates.contains(worldId);
    }

    /**
     * Pick a random F2P, non-PvP, non-skill-restricted world that is online and
     * not the current world. Returns null if no valid candidate exists.
     *
     * See spec §6.3.
     *
     * @param worlds          list from client.getWorldList()
     * @param currentWorldId  the world we want to switch FROM (excluded)
     */
    @Nullable
    public Integer pickF2PNonPvP(World[] worlds, int currentWorldId)
    {
        List<World> candidates = new ArrayList<>();
        for (World w : worlds)
        {
            if (w.getId() == currentWorldId) continue;
            if (w.getPlayerCount() < 0) continue;
            if (!Collections.disjoint(w.getTypes(), EXCLUDED)) continue;
            candidates.add(w);
        }
        if (candidates.isEmpty()) return null;
        return candidates.get(rng.nextInt(candidates.size())).getId();
    }
}
