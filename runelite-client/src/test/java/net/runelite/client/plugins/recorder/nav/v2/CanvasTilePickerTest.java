package net.runelite.client.plugins.recorder.nav.v2;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CanvasTilePickerTest
{
    /** Build a straight east-going walk leg of length {@code n} starting at
     *  {@code (3208, 3217, 0)}. */
    private static V2Path straightEastPath(int n)
    {
        List<WorldPoint> tiles = new ArrayList<>();
        for (int i = 0; i < n; i++) tiles.add(new WorldPoint(3208 + i, 3217, 0));
        return new V2Path(List.<V2Leg>of(new V2Leg.Walk(0, tiles)), tiles.size() - 1);
    }

    private static Predicate<WorldPoint> allowAll()
    {
        return wp -> true;
    }

    private static Predicate<WorldPoint> blockExact(WorldPoint blocked)
    {
        return wp -> !wp.equals(blocked);
    }

    private static Predicate<WorldPoint> blockAll()
    {
        return wp -> false;
    }

    @Test
    public void picks_within_visiblePathRange()
    {
        V2Path path = straightEastPath(20);
        WorldPoint player = new WorldPoint(3208, 3217, 0);
        CanvasTilePicker picker = new CanvasTilePicker();
        Random rng = new Random(42);
        WorldPoint pick = picker.pickNext(path, player, allowAll(), rng);
        assertNotNull(pick);
        int dx = pick.getX() - player.getX();
        assertTrue("pick must be ahead on the path", dx > 0);
        assertTrue("pick must be within long-bucket cap (16 tiles)", dx <= CanvasTilePicker.LONG_MAX);
    }

    @Test
    public void pickedDistance_isShortMidOnly_round2()
    {
        // Round-2 stabilization: long bucket is gated off by default.
        // Live testing showed long picks (12-16 tiles) outran the engine's
        // pathfinder on unfamiliar terrain, producing the "5 forward, 2
        // back" stall flap. Picks must land in short (2-3) or mid (6-8).
        V2Path path = straightEastPath(40);
        WorldPoint player = new WorldPoint(3208, 3217, 0);
        CanvasTilePicker picker = new CanvasTilePicker();
        int shortHits = 0, midHits = 0, longHits = 0;
        Random rng = new Random(7);
        for (int i = 0; i < 200; i++)
        {
            WorldPoint pick = picker.pickNext(path, player, allowAll(), rng);
            assertNotNull(pick);
            int dx = pick.getX() - player.getX();
            if (dx >= CanvasTilePicker.SHORT_MIN && dx <= CanvasTilePicker.SHORT_MAX) shortHits++;
            else if (dx >= CanvasTilePicker.MID_MIN && dx <= CanvasTilePicker.MID_MAX) midHits++;
            else if (dx >= CanvasTilePicker.LONG_MIN && dx <= CanvasTilePicker.LONG_MAX) longHits++;
        }
        assertTrue("short bucket must get sampled (got " + shortHits + ")", shortHits > 5);
        assertTrue("mid bucket must get sampled (got " + midHits + ")", midHits > 5);
        assertEquals("long bucket must NOT be used in round-2 default mode (got " + longHits + ")",
            0, longHits);
        assertEquals("every pick must land in short or mid bucket",
            200, shortHits + midHits);
    }

    @Test
    public void pickNextInTilesAfter_rejectsBackwardCandidates()
    {
        // Progress cursor: player walks past tile idx 5 to tile idx 10.
        // closestIndex would say playerIdx=10. With minIdxExclusive=8
        // (cursor high-water), candidates must be from idx 9..end,
        // never idx <= 8.
        V2Path path = straightEastPath(20);
        List<WorldPoint> tiles = ((V2Leg.Walk) path.legs().get(0)).tiles();
        WorldPoint player = tiles.get(10);
        CanvasTilePicker picker = new CanvasTilePicker();
        Random rng = new Random(31);
        for (int i = 0; i < 100; i++)
        {
            WorldPoint pick = picker.pickNextInTilesAfter(tiles, 8, player, allowAll(), rng, true);
            if (pick == null) continue;
            int idx = tiles.indexOf(pick);
            assertTrue("candidate idx " + idx + " must be > floor (8)", idx > 8);
        }
    }

    @Test
    public void pickNextInTilesAfter_floorAdvancesProgress()
    {
        // With floor at the end of the path, no forward candidate exists.
        V2Path path = straightEastPath(20);
        List<WorldPoint> tiles = ((V2Leg.Walk) path.legs().get(0)).tiles();
        WorldPoint player = tiles.get(10);
        CanvasTilePicker picker = new CanvasTilePicker();
        WorldPoint pick = picker.pickNextInTilesAfter(tiles, tiles.size() - 1, player,
            allowAll(), new Random(0), true);
        assertNull("floor at end → no forward candidate → null", pick);
    }

    @Test
    public void respects_EmptyTileFilter()
    {
        V2Path path = straightEastPath(20);
        WorldPoint player = new WorldPoint(3208, 3217, 0);
        WorldPoint blocked = new WorldPoint(3214, 3217, 0); // mid-bucket distance 6
        CanvasTilePicker picker = new CanvasTilePicker();
        Random rng = new Random(11);
        // 50 picks; none should land on the blocked tile.
        Set<WorldPoint> seen = new HashSet<>();
        for (int i = 0; i < 50; i++)
        {
            WorldPoint pick = picker.pickNext(path, player, blockExact(blocked), rng);
            if (pick != null) seen.add(pick);
        }
        assertTrue("blocked tile must never be picked", !seen.contains(blocked));
    }

    @Test
    public void returnsNull_whenAllCandidatesFiltered()
    {
        V2Path path = straightEastPath(20);
        WorldPoint player = new WorldPoint(3208, 3217, 0);
        CanvasTilePicker picker = new CanvasTilePicker();
        Random rng = new Random(0);
        WorldPoint pick = picker.pickNext(path, player, blockAll(), rng);
        assertNull("filter rejecting every candidate must signal modality switch via null",
            pick);
    }

    @Test
    public void playerOffPath_picksClosestPathTileForward()
    {
        // Player a bit south of the path; picker projects player onto path
        // and looks ahead from the projected index.
        V2Path path = straightEastPath(20);
        WorldPoint player = new WorldPoint(3210, 3216, 0); // 1 south of path tile (3210, 3217)
        CanvasTilePicker picker = new CanvasTilePicker();
        Random rng = new Random(99);
        WorldPoint pick = picker.pickNext(path, player, allowAll(), rng);
        assertNotNull(pick);
        // The projected index is 2 (player closest to (3210,3217)). Forward
        // pick must be > 3210 in x.
        assertTrue("pick must be forward of the player's projected path index",
            pick.getX() > 3210);
    }

    @Test
    public void emptyPath_returnsNull()
    {
        CanvasTilePicker picker = new CanvasTilePicker();
        WorldPoint pick = picker.pickNext(V2Path.EMPTY, new WorldPoint(0, 0, 0), allowAll(),
            new Random(0));
        assertNull(pick);
    }

    @Test
    public void shortPath_picksFromAvailableTiles()
    {
        V2Path path = straightEastPath(3); // tiles at x=3208, 3209, 3210
        WorldPoint player = new WorldPoint(3208, 3217, 0);
        CanvasTilePicker picker = new CanvasTilePicker();
        Random rng = new Random(0);
        WorldPoint pick = picker.pickNext(path, player, allowAll(), rng);
        // Path is too short for mid/long buckets. Picker must fall through
        // to the short bucket (1 or 2 tiles ahead) — never null when at
        // least one walkable tile exists ahead.
        assertNotNull("short path with forward tile must produce a pick", pick);
        int dx = pick.getX() - player.getX();
        assertTrue("pick must be forward", dx > 0);
        assertTrue("pick must be within path", dx <= 2);
    }
}
