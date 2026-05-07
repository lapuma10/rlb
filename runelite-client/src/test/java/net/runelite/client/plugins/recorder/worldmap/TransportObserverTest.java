package net.runelite.client.plugins.recorder.worldmap;

import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;

/** Phase-2 pending-phase tests for {@link TransportObserver}. The
 *  {@code @Subscribe} handler is exercised via the package-private
 *  {@code capturePending} helper, which mirrors what
 *  {@link TransportObserver#onMenuOptionClicked} does after unpacking
 *  the MenuEntry. Resolution-phase tests land alongside Task 2.3. */
public class TransportObserverTest
{
    private static final WorldPoint LUMBY_BANK = new WorldPoint(3208, 3216, 2);

    @Test
    public void onMenuOptionClicked_whitelistedVerb_storesPending()
    {
        TransportIndex idx = new TransportIndex();
        TransportObserver obs = new TransportObserver(null, idx);

        obs.capturePending(LUMBY_BANK, "Climb-down", "Staircase",
            16671, 53, 14, "GAME_OBJECT_FIRST_OPTION", 1_000L);

        assertEquals(1, obs.pendingCount());
        assertEquals("nothing publishes until resolution lands", 0, idx.size());
    }

    @Test
    public void onMenuOptionClicked_nonWhitelistedVerb_ignored()
    {
        TransportIndex idx = new TransportIndex();
        TransportObserver obs = new TransportObserver(null, idx);

        obs.capturePending(LUMBY_BANK, "Attack", "Goblin",
            123, 0, 0, "NPC_FIRST_OPTION", 1_000L);

        assertEquals(0, obs.pendingCount());
        assertEquals(0, idx.size());
    }

    @Test
    public void onMenuOptionClicked_blankVerb_ignored()
    {
        TransportIndex idx = new TransportIndex();
        TransportObserver obs = new TransportObserver(null, idx);

        obs.capturePending(LUMBY_BANK, "", "",
            0, 0, 0, "", 1_000L);

        assertEquals(0, obs.pendingCount());
    }

    @Test
    public void onMenuOptionClicked_nullFromTile_ignored()
    {
        TransportIndex idx = new TransportIndex();
        TransportObserver obs = new TransportObserver(null, idx);

        obs.capturePending(null, "Open", "Gate",
            1530, 0, 0, "GAME_OBJECT_FIRST_OPTION", 1_000L);

        assertEquals(0, obs.pendingCount());
    }

    @Test
    public void multipleCaptures_atSameTile_coexist()
    {
        TransportIndex idx = new TransportIndex();
        TransportObserver obs = new TransportObserver(null, idx);

        obs.capturePending(LUMBY_BANK, "Open", "Gate",
            1530, 0, 0, "GAME_OBJECT_FIRST_OPTION", 1_000L);
        obs.capturePending(LUMBY_BANK, "Climb-down", "Staircase",
            16671, 0, 0, "GAME_OBJECT_FIRST_OPTION", 1_500L);

        assertEquals("distinct verbs from same tile keep independent pendings",
            2, obs.pendingCount());
    }

    @Test
    public void pendingOlderThanResolutionTimeout_isExpiredOnTick()
    {
        TransportIndex idx = new TransportIndex();
        TransportObserver obs = new TransportObserver(null, idx, 3L);

        obs.capturePending(LUMBY_BANK, "Open", "Gate",
            1530, 0, 0, "GAME_OBJECT_FIRST_OPTION", 1_000L);
        assertEquals(1, obs.pendingCount());

        // 4 ticks (> 3 timeout) — pending must be discarded with no
        // edge published.
        for (int i = 0; i < 4; i++) obs.tickForTest();

        assertEquals(0, obs.pendingCount());
        assertEquals("expired pending must NOT publish to the index",
            0, idx.size());
    }

    @Test
    public void onTick_playerMovedAfterPending_resolvesEdge()
    {
        TransportIndex idx = new TransportIndex();
        TransportObserver obs = new TransportObserver(null, idx);

        // Click stairs at fromTile, plane 2.
        obs.capturePending(LUMBY_BANK, "Climb-down", "Staircase",
            16671, 53, 14, "GAME_OBJECT_FIRST_OPTION", 1_000L);

        // Tick 1: still on fromTile (stair animation).
        obs.tickForTest(LUMBY_BANK, 1_400L);
        assertEquals("not yet resolved while still on fromTile", 0, idx.size());

        // Tick 2: engine put us one plane down, different tile.
        WorldPoint groundLanding = new WorldPoint(3206, 3216, 1);
        obs.tickForTest(groundLanding, 2_200L);

        assertEquals(1, idx.size());
        assertEquals(0, obs.pendingCount());
        TransportEdge edge = idx.getAll().iterator().next();
        assertEquals(LUMBY_BANK, edge.fromTile());
        assertEquals(groundLanding, edge.toTile());
        assertEquals("Climb-down", edge.verb());
        assertEquals(16671, edge.objectId());
        assertEquals("toTile.plane - fromTile.plane",
            -1, edge.toTile().getPlane() - edge.fromTile().getPlane());
        assertEquals("durationMs = nowMs - clickTimeMs",
            1_200L, edge.observedDurationMs());
    }

    @Test
    public void onTick_planeChangedAcrossTransition_isCapturedInEdge()
    {
        TransportIndex idx = new TransportIndex();
        TransportObserver obs = new TransportObserver(null, idx);

        // Climb-up from plane 0.
        WorldPoint stairsBottom = new WorldPoint(3206, 3216, 0);
        WorldPoint stairsTop = new WorldPoint(3206, 3216, 1);
        obs.capturePending(stairsBottom, "Climb-up", "Staircase",
            16672, 53, 14, "GAME_OBJECT_FIRST_OPTION", 1_000L);

        obs.tickForTest(stairsBottom, 1_400L);
        obs.tickForTest(stairsTop, 2_200L);

        assertEquals(1, idx.size());
        TransportEdge e = idx.getAll().iterator().next();
        assertEquals("Climb-up", e.verb());
        assertEquals(0, e.fromTile().getPlane());
        assertEquals(1, e.toTile().getPlane());
    }

    @Test
    public void onTick_pendingNeverResolves_onlyExpired_doesNotPublish()
    {
        TransportIndex idx = new TransportIndex();
        TransportObserver obs = new TransportObserver(null, idx, 3L);

        obs.capturePending(LUMBY_BANK, "Open", "Gate",
            1530, 0, 0, "GAME_OBJECT_FIRST_OPTION", 1_000L);

        // Player never moved — keep observing the same tile.
        for (int i = 0; i < 5; i++) obs.tickForTest(LUMBY_BANK, 2_000L + i * 600L);

        assertEquals("never published — only expired", 0, idx.size());
        assertEquals(0, obs.pendingCount());
    }

    @Test
    public void onTick_repeatedResolution_bumpsSeenCount()
    {
        TransportIndex idx = new TransportIndex();
        TransportObserver obs = new TransportObserver(null, idx);

        WorldPoint b = new WorldPoint(3208, 3216, 2);
        WorldPoint t = new WorldPoint(3206, 3216, 1);

        obs.capturePending(b, "Climb-down", "Staircase",
            16671, 0, 0, "GAME_OBJECT_FIRST_OPTION", 1_000L);
        obs.tickForTest(b, 1_400L);
        obs.tickForTest(t, 2_200L);

        // Walk back up via a different click; observe a second time.
        obs.capturePending(b, "Climb-down", "Staircase",
            16671, 0, 0, "GAME_OBJECT_FIRST_OPTION", 5_000L);
        obs.tickForTest(b, 5_400L);
        obs.tickForTest(t, 6_200L);

        assertEquals("dedupe by key — single edge", 1, idx.size());
        assertEquals(2, idx.getAll().iterator().next().seenCount());
    }

    // ──────────── click-target-aware tests ────────────

    @Test
    public void onTick_clickAdjacentToTarget_engineWalkOntoTargetDoesNotResolve()
    {
        // Reproduces the bug from the live transports.json smoke test:
        // user clicks "Top-floor" on a staircase from one tile away;
        // engine routes them onto the staircase tile FIRST, then
        // teleports them to the next plane. Without click-target
        // gating the engine-walk tick was being recorded as the
        // transport, producing a same-plane 1-tile edge with no
        // teleport.
        TransportIndex idx = new TransportIndex();
        TransportObserver obs = new TransportObserver(null, idx);

        WorldPoint adjacentToStair = new WorldPoint(3207, 3228, 0);
        WorldPoint stairTile = new WorldPoint(3207, 3229, 0);
        WorldPoint topFloor = new WorldPoint(3207, 3229, 2);

        obs.capturePending(adjacentToStair, stairTile,
            "Top-floor", "Staircase",
            56230, 20, 61, "GAME_OBJECT_SECOND_OPTION", 1_000L);

        // T=1: engine routes player onto the staircase tile. This is
        // a 1-tile same-plane move — must NOT resolve.
        obs.tickForTest(stairTile, 1_600L);
        assertEquals("engine walk to stair must not resolve", 0, idx.size());

        // T=2: settle tick (animation playing).
        obs.tickForTest(stairTile, 2_200L);
        assertEquals(0, idx.size());

        // T=3: teleport to plane 2.
        obs.tickForTest(topFloor, 2_800L);

        assertEquals(1, idx.size());
        TransportEdge edge = idx.getAll().iterator().next();
        assertEquals("fromTile is the staircase, NOT the engine-walk start",
            stairTile, edge.fromTile());
        assertEquals(topFloor, edge.toTile());
        assertEquals("Top-floor", edge.verb());
        assertEquals("plane delta captured",
            2, edge.toTile().getPlane() - edge.fromTile().getPlane());
    }

    @Test
    public void onTick_doorScenario_resolvesAfterArrivalAndSettle()
    {
        // Door / gate case: no plane change. Player walks to approach
        // tile, door opens (one settle tick), player walks through to
        // the far side. The captured edge's fromTile must be the
        // approach tile, not an intermediate walking tile.
        TransportIndex idx = new TransportIndex();
        TransportObserver obs = new TransportObserver(null, idx);

        WorldPoint farAway = new WorldPoint(3232, 3300, 0);
        WorldPoint walkTile = new WorldPoint(3231, 3299, 0);
        WorldPoint approach = new WorldPoint(3230, 3298, 0);
        WorldPoint doorTile = new WorldPoint(3230, 3297, 0);
        WorldPoint farSide = new WorldPoint(3230, 3296, 0);

        obs.capturePending(farAway, doorTile,
            "Open", "Gate",
            1530, 56, 18, "GAME_OBJECT_FIRST_OPTION", 1_000L);

        // Walking toward the door — far from clickTarget, must NOT resolve.
        obs.tickForTest(walkTile, 1_600L);
        assertEquals(0, idx.size());

        // Arrives at approach tile (Chebyshev 1 from door) → ARRIVED.
        obs.tickForTest(approach, 2_200L);
        assertEquals("arrival tile change must NOT resolve immediately",
            0, idx.size());

        // Settle tick — door opening animation. ARRIVED → READY.
        obs.tickForTest(approach, 2_800L);
        assertEquals(0, idx.size());

        // Walks through to far side. READY + tile change → resolve.
        obs.tickForTest(farSide, 3_400L);

        assertEquals(1, idx.size());
        TransportEdge edge = idx.getAll().iterator().next();
        assertEquals("fromTile is the approach tile (just before walking through)",
            approach, edge.fromTile());
        assertEquals(farSide, edge.toTile());
        assertEquals("Open", edge.verb());
        assertEquals("same plane — door, not stair",
            0, edge.toTile().getPlane() - edge.fromTile().getPlane());
    }

    @Test
    public void onTick_walkingTowardClickTarget_intermediateTilesDoNotResolve()
    {
        // Belt-and-suspenders: every walk tile BEFORE arrival must
        // not produce an edge.
        TransportIndex idx = new TransportIndex();
        TransportObserver obs = new TransportObserver(null, idx);

        WorldPoint start = new WorldPoint(3220, 3300, 0);
        WorldPoint mid1 = new WorldPoint(3221, 3300, 0);
        WorldPoint mid2 = new WorldPoint(3222, 3300, 0);
        WorldPoint approach = new WorldPoint(3223, 3300, 0);
        WorldPoint clickTarget = new WorldPoint(3224, 3300, 0);

        obs.capturePending(start, clickTarget,
            "Open", "Door",
            1530, 24, 0, "GAME_OBJECT_FIRST_OPTION", 1_000L);

        obs.tickForTest(mid1, 1_600L);
        obs.tickForTest(mid2, 2_200L);
        obs.tickForTest(approach, 2_800L);

        assertEquals("nothing resolves before settle tick after arrival",
            0, idx.size());
        assertEquals("pending still alive", 1, obs.pendingCount());
    }
}
