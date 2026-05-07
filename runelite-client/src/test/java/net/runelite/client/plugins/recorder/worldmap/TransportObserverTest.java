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
}
