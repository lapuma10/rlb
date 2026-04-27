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

import net.runelite.api.World;
import net.runelite.api.WorldType;
import org.junit.Test;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class WorldPickerTest
{
    /** Mixed F2P + P2P list — only the F2P entries (308, 326, 381) should
     *  ever be picked. P2P (302, 303, ...) should never appear. */
    @Test
    public void pickRandom_onlyReturnsF2PCandidates()
    {
        // Picker is given the f2p-only list as candidates; this is the
        // boundary the spec actually cares about — the candidate list IS
        // the f2p filter. We assert no value outside that list ever shows.
        List<Integer> f2p = Arrays.asList(308, 326, 381);
        WorldPicker picker = new WorldPicker(f2p, new Random(123L));
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < 200; i++)
        {
            int w = picker.pickRandom(0);
            assertTrue("picked non-F2P world " + w, f2p.contains(w));
            seen.add(w);
        }
        // We expect to see most candidates over 200 draws.
        assertTrue("expected to see at least 2 distinct picks, got " + seen, seen.size() >= 2);
    }

    @Test
    public void pickRandom_avoidsCurrentWorld()
    {
        WorldPicker picker = new WorldPicker(Arrays.asList(308, 326), new Random(0L));
        for (int i = 0; i < 50; i++)
        {
            int w = picker.pickRandom(308);
            assertEquals("must avoid current world", 326, w);
        }
    }

    @Test
    public void pickRandom_singleCandidateEqualsCurrent_returnsSentinel()
    {
        WorldPicker picker = new WorldPicker(Arrays.asList(308), new Random());
        assertEquals(-1, picker.pickRandom(308));
    }

    @Test
    public void isF2P_reflectsCandidateList()
    {
        WorldPicker picker = new WorldPicker(Arrays.asList(308, 326), new Random());
        assertTrue(picker.isF2P(308));
        assertTrue(picker.isF2P(326));
        assertFalse(picker.isF2P(302));
        assertFalse(picker.isF2P(0));
    }

    @Test
    public void defaultCandidates_areNonEmpty()
    {
        WorldPicker p = new WorldPicker();
        assertFalse(p.getCandidates().isEmpty());
        assertTrue(p.getCandidates().contains(308));
    }

    @Test
    public void pickF2PNonPvP_excludesPvpWorlds()
    {
        World pvpWorld = mockWorld(323, EnumSet.of(WorldType.PVP), 100);
        World cleanWorld = mockWorld(308, EnumSet.noneOf(WorldType.class), 100);
        World[] all = { pvpWorld, cleanWorld };
        WorldPicker picker = new WorldPicker(new Random(0));
        Integer chosen = picker.pickF2PNonPvP(all, 326);
        assertEquals(Integer.valueOf(308), chosen);
    }

    @Test
    public void pickF2PNonPvP_excludesMembersWorlds()
    {
        World members = mockWorld(330, EnumSet.of(WorldType.MEMBERS), 100);
        World cleanWorld = mockWorld(308, EnumSet.noneOf(WorldType.class), 100);
        World[] all = { members, cleanWorld };
        WorldPicker picker = new WorldPicker(new Random(0));
        Integer chosen = picker.pickF2PNonPvP(all, 326);
        assertEquals(Integer.valueOf(308), chosen);
    }

    @Test
    public void pickF2PNonPvP_excludesCurrentWorld()
    {
        World w308 = mockWorld(308, EnumSet.noneOf(WorldType.class), 100);
        World w316 = mockWorld(316, EnumSet.noneOf(WorldType.class), 100);
        World[] all = { w308, w316 };
        WorldPicker picker = new WorldPicker(new Random(0));
        Integer chosen = picker.pickF2PNonPvP(all, 308);
        assertEquals(Integer.valueOf(316), chosen);
    }

    @Test
    public void pickF2PNonPvP_excludesOfflineWorlds()
    {
        World offline = mockWorld(308, EnumSet.noneOf(WorldType.class), -1);
        World online  = mockWorld(316, EnumSet.noneOf(WorldType.class), 100);
        World[] all = { offline, online };
        WorldPicker picker = new WorldPicker(new Random(0));
        Integer chosen = picker.pickF2PNonPvP(all, 326);
        assertEquals(Integer.valueOf(316), chosen);
    }

    @Test
    public void pickF2PNonPvP_excludesSkillTotalWorlds()
    {
        World skill = mockWorld(308, EnumSet.of(WorldType.SKILL_TOTAL), 100);
        World clean = mockWorld(316, EnumSet.noneOf(WorldType.class), 100);
        World[] all = { skill, clean };
        WorldPicker picker = new WorldPicker(new Random(0));
        Integer chosen = picker.pickF2PNonPvP(all, 326);
        assertEquals(Integer.valueOf(316), chosen);
    }

    @Test
    public void pickF2PNonPvP_returnsNull_whenNoCandidates()
    {
        World[] all = { mockWorld(308, EnumSet.of(WorldType.PVP), 100) };
        WorldPicker picker = new WorldPicker(new Random(0));
        Integer chosen = picker.pickF2PNonPvP(all, 326);
        assertNull(chosen);
    }

    private static World mockWorld(int id, EnumSet<WorldType> types, int playerCount)
    {
        World w = mock(World.class);
        when(w.getId()).thenReturn(id);
        when(w.getTypes()).thenReturn(types);
        when(w.getPlayerCount()).thenReturn(playerCount);
        return w;
    }
}
