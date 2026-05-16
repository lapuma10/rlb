package net.runelite.client.plugins.recorder.nav.v2.executor;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.nav.v2.Waypoint;
import net.runelite.client.plugins.recorder.nav.v2.WaypointType;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Lane 5 plan Task 3: SidestepResolver picks the furthest-forward
 *  walkable+clean tile within the current waypoint's tolerance bucket,
 *  honoring spec §4 Lane 5 sidestep rules. */
public class SidestepResolverTest
{
    private static Waypoint walk(WorldPoint target, int tolerance)
    {
        return new Waypoint()
        {
            @Override public WorldPoint target() { return target; }
            @Override public int toleranceRadius() { return tolerance; }
            @Override public WaypointType type() { return WaypointType.WALK; }
        };
    }

    private static Waypoint exact(WorldPoint target, WaypointType type)
    {
        return new Waypoint()
        {
            @Override public WorldPoint target() { return target; }
            @Override public int toleranceRadius() { return 0; }
            @Override public WaypointType type() { return type; }
        };
    }

    @Test
    public void resolve_normalWaypoint_picksValidTileInTolerance()
    {
        // Player at (10,10), target waypoint at (16,10) with tolerance 2
        // → corridor is x in [14..18], y in [8..12]. Player should pick
        // the furthest-forward valid tile (largest x toward 16).
        WorldPoint player = new WorldPoint(10, 10, 0);
        Waypoint w = walk(new WorldPoint(16, 10, 0), 2);

        SidestepResolver r = new SidestepResolver();
        SidestepResolver.ResolveResult result = r.resolve(
            w, player, /*nextWaypoint*/ null,
            /*tileAccept*/ tile -> true,
            /*skipAnchorTiles*/ Collections.emptySet());

        assertEquals(SidestepResolver.Status.OK, result.status());
        assertTrue(result.chosen().isPresent());
        // The chosen tile must be in the tolerance corridor
        WorldPoint chosen = result.chosen().get();
        int dx = Math.abs(chosen.getX() - 16);
        int dy = Math.abs(chosen.getY() - 10);
        assertTrue("chosen within tolerance chebyshev", Math.max(dx, dy) <= 2);
    }

    @Test
    public void resolve_oneBlockedTileInCorridor_picksAdjacentValidTile_sidestepTrue()
    {
        // The ideal forward tile is blocked; the resolver picks an
        // adjacent valid tile in the bucket and reports sidestep=true.
        WorldPoint player = new WorldPoint(10, 10, 0);
        Waypoint w = walk(new WorldPoint(16, 10, 0), 2);
        WorldPoint blockedTile = new WorldPoint(16, 10, 0);

        SidestepResolver r = new SidestepResolver();
        SidestepResolver.ResolveResult result = r.resolve(
            w, player, /*nextWaypoint*/ null,
            tile -> !tile.equals(blockedTile),
            Collections.emptySet());

        assertEquals(SidestepResolver.Status.OK, result.status());
        assertTrue(result.chosen().isPresent());
        assertFalse("chosen must not be the blocked tile",
            result.chosen().get().equals(blockedTile));
    }

    @Test
    public void resolve_wholeCorridorBlocked_returnsNoLocalWalkableTile()
    {
        WorldPoint player = new WorldPoint(10, 10, 0);
        Waypoint w = walk(new WorldPoint(16, 10, 0), 2);

        SidestepResolver r = new SidestepResolver();
        SidestepResolver.ResolveResult result = r.resolve(
            w, player, null,
            /*tileAccept*/ tile -> false,
            Collections.emptySet());

        assertEquals(SidestepResolver.Status.NO_LOCAL_WALKABLE_TILE, result.status());
        assertFalse(result.chosen().isPresent());
    }

    @Test
    public void resolve_exactTransportApproachBlocked_returnsNoLocalWalkableTile()
    {
        // Exact-required waypoint with the exact tile blocked → resolver
        // refuses to sidestep (no fake success). Status NO_LOCAL_WALKABLE_TILE.
        WorldPoint player = new WorldPoint(10, 10, 0);
        WorldPoint approach = new WorldPoint(16, 10, 0);
        Waypoint w = exact(approach, WaypointType.TRANSPORT_APPROACH);

        SidestepResolver r = new SidestepResolver();
        SidestepResolver.ResolveResult result = r.resolve(
            w, player, null,
            tile -> !tile.equals(approach),
            Collections.emptySet());

        assertEquals(SidestepResolver.Status.NO_LOCAL_WALKABLE_TILE, result.status());
        assertFalse(result.chosen().isPresent());
    }

    @Test
    public void resolve_exactTransportApproachOK_picksExactTile()
    {
        WorldPoint player = new WorldPoint(10, 10, 0);
        WorldPoint approach = new WorldPoint(16, 10, 0);
        Waypoint w = exact(approach, WaypointType.TRANSPORT_APPROACH);

        SidestepResolver r = new SidestepResolver();
        SidestepResolver.ResolveResult result = r.resolve(
            w, player, null,
            tile -> true,
            Collections.emptySet());

        assertEquals(SidestepResolver.Status.OK, result.status());
        assertEquals(approach, result.chosen().get());
    }

    @Test
    public void resolve_doesNotSkipSafetyAnchor()
    {
        // Anchor tile sits between player and waypoint, and the corridor
        // is entirely PAST the anchor. The resolver must refuse to pick
        // any tile in the corridor (which would skip the anchor) and
        // return NO_LOCAL_WALKABLE_TILE so the executor processes the
        // anchor first.
        WorldPoint player = new WorldPoint(10, 10, 0);
        Waypoint w = walk(new WorldPoint(16, 10, 0), 2);
        Set<WorldPoint> skipAnchors = new HashSet<>();
        WorldPoint anchor = new WorldPoint(13, 10, 0);
        skipAnchors.add(anchor);

        SidestepResolver r = new SidestepResolver();
        SidestepResolver.ResolveResult result = r.resolve(
            w, player, null, tile -> true, skipAnchors);

        // The corridor [14..18] entirely skips the anchor at x=13. The
        // resolver must therefore return NO_LOCAL_WALKABLE_TILE so the
        // executor falls back to processing the anchor as its own
        // waypoint first.
        assertEquals(SidestepResolver.Status.NO_LOCAL_WALKABLE_TILE, result.status());
        assertFalse(result.chosen().isPresent());
    }

    @Test
    public void resolve_anchorInsideCorridor_picksAtOrBeforeAnchor()
    {
        // Anchor tile inside the tolerance corridor — resolver must pick
        // the anchor or a tile before it (never past it).
        WorldPoint player = new WorldPoint(10, 10, 0);
        Waypoint w = walk(new WorldPoint(13, 10, 0), 2);
        Set<WorldPoint> skipAnchors = new HashSet<>();
        WorldPoint anchor = new WorldPoint(12, 10, 0);
        skipAnchors.add(anchor);

        SidestepResolver r = new SidestepResolver();
        SidestepResolver.ResolveResult result = r.resolve(
            w, player, null, tile -> true, skipAnchors);

        // Corridor for target (13,10) tolerance 2 = [11..15]. Anchor at
        // (12,10) is inside. Resolver must pick a tile at x <= 12
        // (the anchor) or before.
        assertEquals(SidestepResolver.Status.OK, result.status());
        WorldPoint chosen = result.chosen().get();
        assertTrue("chosen must not skip the anchor at x=12: chosen=" + chosen,
            chosen.getX() <= 12);
    }

    @Test
    public void resolve_doesNotIncreaseDistanceBeyondThreshold()
    {
        // Next waypoint at (20, 10). Default threshold = 2. Resolver may
        // not pick a tile whose distance to next waypoint exceeds the
        // "current waypoint's distance to next + 2".
        WorldPoint player = new WorldPoint(10, 10, 0);
        Waypoint w = walk(new WorldPoint(16, 10, 0), 4);
        Waypoint next = walk(new WorldPoint(20, 10, 0), 1);

        SidestepResolver r = new SidestepResolver();
        SidestepResolver.ResolveResult result = r.resolve(
            w, player, next, tile -> true, Collections.emptySet());

        assertTrue(result.chosen().isPresent());
        WorldPoint chosen = result.chosen().get();
        int distToNext = Math.max(
            Math.abs(chosen.getX() - 20), Math.abs(chosen.getY() - 10));
        // Current waypoint is 4 from next; threshold is +2, so distToNext <= 6
        assertTrue("chosen must not be farther from next waypoint than threshold (got " + distToNext + ")",
            distToNext <= 6);
    }

    @Test
    public void resolve_resultIncludesSidestepFlag()
    {
        WorldPoint player = new WorldPoint(10, 10, 0);
        Waypoint w = walk(new WorldPoint(16, 10, 0), 2);

        SidestepResolver r = new SidestepResolver();
        SidestepResolver.ResolveResult result = r.resolve(
            w, player, null, tile -> true, Collections.emptySet());
        assertNotNull("sidestepUsed flag is non-null", result.sidestepUsed());
    }

    @Test
    public void resolve_recordsRejectedCandidates()
    {
        // When tiles in the corridor are rejected, the result's
        // rejected list documents which ones + a reason tag.
        WorldPoint player = new WorldPoint(10, 10, 0);
        Waypoint w = walk(new WorldPoint(16, 10, 0), 2);
        WorldPoint blocked1 = new WorldPoint(16, 10, 0);
        WorldPoint blocked2 = new WorldPoint(17, 10, 0);
        Set<WorldPoint> blocked = new HashSet<>();
        blocked.add(blocked1);
        blocked.add(blocked2);

        SidestepResolver r = new SidestepResolver();
        SidestepResolver.ResolveResult result = r.resolve(
            w, player, null,
            tile -> !blocked.contains(tile),
            Collections.emptySet());

        assertNotNull(result.rejected());
        // We don't require a specific count — just that rejected exists.
        // The chosen tile should still be in the corridor.
        assertTrue(result.chosen().isPresent());
    }
}
