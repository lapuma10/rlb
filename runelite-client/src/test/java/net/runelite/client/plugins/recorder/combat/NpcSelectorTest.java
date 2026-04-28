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
 * LOSS OF USE, DATA, OR PROFITS; HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.recorder.combat;

import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.util.Arrays;
import java.util.List;

public class NpcSelectorTest
{
    private static NPC mockChicken(int idx, int x, int y, int plane)
    {
        return mockNpc(idx, "Chicken", x, y, plane, /*hp*/ 30, /*interactingPlayer*/ null);
    }

    private static NPC mockNpc(int idx, String name, int x, int y, int plane,
                               int hp, Player interactingPlayer)
    {
        NPC n = mock(NPC.class);
        when(n.getIndex()).thenReturn(idx);
        when(n.getName()).thenReturn(name);
        NPCComposition c = mock(NPCComposition.class);
        when(c.getName()).thenReturn(name);
        when(n.getComposition()).thenReturn(c);
        when(n.getWorldLocation()).thenReturn(new WorldPoint(x, y, plane));
        when(n.getHealthRatio()).thenReturn(hp);
        when(n.getInteracting()).thenReturn(interactingPlayer);
        return n;
    }

    @Test
    public void prefersClosestUnengagedChicken()
    {
        WorldPoint here = new WorldPoint(3230, 3296, 0);
        List<NPC> npcs = Arrays.asList(
            mockChicken(1, 3232, 3296, 0),   // dist 2
            mockChicken(2, 3231, 3297, 0),   // dist 1 (closest)
            mockChicken(3, 3230, 3300, 0));  // dist 4
        NPC pick = new NpcSelector("Chicken").pick(npcs, null, here);
        assertNotNull(pick);
        assertEquals(2, pick.getIndex());
    }

    @Test
    public void rejectsChickenEngagedByOtherPlayer()
    {
        WorldPoint here = new WorldPoint(3230, 3296, 0);
        Player otherPlayer = mock(Player.class);
        NPC engagedByOther = mockNpc(1, "Chicken", 3231, 3296, 0, 30, otherPlayer);
        NPC freeOne = mockChicken(2, 3232, 3297, 0);
        NPC pick = new NpcSelector("Chicken").pick(Arrays.asList(engagedByOther, freeOne), null, here);
        assertNotNull(pick);
        assertEquals("must skip the engaged-by-other chicken", 2, pick.getIndex());
    }

    @Test
    public void rejectsWrongNameAndDeadAndDifferentPlane()
    {
        WorldPoint here = new WorldPoint(3230, 3296, 0);
        NPC cow = mockNpc(10, "Cow", 3231, 3296, 0, 30, null);
        NPC dyingChicken = mockNpc(11, "Chicken", 3232, 3296, 0, 0, null);
        NPC upstairsChicken = mockNpc(12, "Chicken", 3231, 3297, 1, 30, null);
        NPC validChicken = mockChicken(13, 3233, 3297, 0);
        NPC pick = new NpcSelector("Chicken").pick(
            Arrays.asList(cow, dyingChicken, upstairsChicken, validChicken), null, here);
        assertNotNull(pick);
        assertEquals(13, pick.getIndex());
    }

    @Test
    public void returnsNullWhenNoCandidatesInRange()
    {
        WorldPoint here = new WorldPoint(3230, 3296, 0);
        NPC farChicken = mockChicken(20, 3260, 3296, 0);   // dist 30 — out of range (default 6)
        assertNull(new NpcSelector("Chicken").pick(List.of(farChicken), null, here));
    }

    @Test
    public void caseInsensitiveAndStripsMarkup()
    {
        WorldPoint here = new WorldPoint(3230, 3296, 0);
        NPC styled = mockNpc(30, "<col=ff0000>chicken</col>", 3231, 3296, 0, 30, null);
        NPC pick = new NpcSelector("Chicken").pick(List.of(styled), null, here);
        assertNotNull(pick);
        assertEquals(30, pick.getIndex());
    }

    @Test
    public void excludedIndexIsSkipped()
    {
        WorldPoint here = new WorldPoint(3230, 3296, 0);
        List<NPC> npcs = Arrays.asList(
            mockChicken(1, 3231, 3296, 0),   // dist 1, normally first
            mockChicken(2, 3231, 3297, 0));  // dist 1+, but unique idx
        NPC pick = new NpcSelector("Chicken").pick(npcs, null, here, 1);
        assertNotNull(pick);
        assertEquals("excluded #1 → must pick #2", 2, pick.getIndex());
    }

    @Test
    public void rejectsChickenOutsideArea()
    {
        WorldPoint here = new WorldPoint(3236, 3296, 0);
        WorldArea pen = new WorldArea(3232, 3293, 8, 8, 0);
        NPC inside = mockChicken(1, 3235, 3296, 0);
        NPC outside = mockChicken(2, 3245, 3296, 0); // east of the pen
        NpcSelector sel = new NpcSelector("Chicken", NpcSelector.DEFAULT_RANGE, pen);
        assertEquals(NpcSelector.Rejection.OUT_OF_AREA,
            sel.classify(outside, null, here, -1, null, null));
        assertNull(sel.classify(inside, null, here, -1, null, null));
    }
}
