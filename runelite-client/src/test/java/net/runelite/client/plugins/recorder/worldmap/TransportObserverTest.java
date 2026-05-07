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
}
