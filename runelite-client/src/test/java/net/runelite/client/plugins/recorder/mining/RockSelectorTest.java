/*
 * Copyright (c) 2026, RuneLite. All rights reserved.
 */
package net.runelite.client.plugins.recorder.mining;

import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class RockSelectorTest
{
    private static RockSelector.Candidate at(int x, int y)
    {
        return new RockSelector.Candidate(new WorldPoint(x, y, 0));
    }

    @Test
    public void picksClosestLiveRock()
    {
        WorldPoint here = new WorldPoint(3220, 3147, 0);
        // Three candidates: dist 5, 1, 3. Selector should pick the dist=1.
        List<RockSelector.Candidate> rocks = List.of(
            at(3225, 3147),  // dist 5
            at(3220, 3148),  // dist 1 (closest)
            at(3223, 3147)); // dist 3
        RockSelector.Candidate pick = new RockSelector().pick(rocks, here, null);
        assertNotNull(pick);
        assertEquals(3220, pick.tile().getX());
        assertEquals(3148, pick.tile().getY());
    }

    @Test
    public void emptyCandidateListReturnsNull()
    {
        WorldPoint here = new WorldPoint(3220, 3147, 0);
        assertNull(new RockSelector().pick(List.of(), here, null));
    }

    @Test
    public void allDepletedReturnsNull()
    {
        WorldPoint here = new WorldPoint(3220, 3147, 0);
        WorldPoint a = new WorldPoint(3221, 3147, 0);
        WorldPoint b = new WorldPoint(3220, 3148, 0);
        Set<WorldPoint> depleted = new HashSet<>();
        depleted.add(a);
        depleted.add(b);
        List<RockSelector.Candidate> rocks = List.of(
            new RockSelector.Candidate(a),
            new RockSelector.Candidate(b));
        assertNull(new RockSelector().pick(rocks, here, depleted));
    }

    @Test
    public void differentPlaneIsSkipped()
    {
        WorldPoint here = new WorldPoint(3220, 3147, 0);
        RockSelector.Candidate upstairs = new RockSelector.Candidate(
            new WorldPoint(3221, 3147, 1));
        RockSelector.Candidate same = new RockSelector.Candidate(
            new WorldPoint(3225, 3147, 0));
        RockSelector.Candidate pick = new RockSelector().pick(
            List.of(upstairs, same), here, null);
        assertNotNull(pick);
        assertEquals(0, pick.tile().getPlane());
    }
}
