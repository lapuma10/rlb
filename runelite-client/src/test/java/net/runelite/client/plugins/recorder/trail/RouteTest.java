package net.runelite.client.plugins.recorder.trail;

import java.util.List;
import java.util.Random;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;

/** Pin {@link Route}'s builder validation, weighted-random sampling
 *  distribution, and {@link Route#noRepeat()} alternation. The picker
 *  is a pure function over {@link java.util.Random}, so seeded RNGs
 *  give stable assertions. */
public class RouteTest
{
    private static Trail trail(String name)
    {
        return new Trail(name, 0L, List.of(
            new TrailEvent.Tile(0, new WorldPoint(0, 0, 0))));
    }

    @Test
    public void singleTrailDegenerateAlwaysReturnsThatTrail()
    {
        Trail t = trail("only");
        Route r = Route.builder().trail(t, 5).build();
        Random rng = new Random(0);
        for (int i = 0; i < 50; i++)
        {
            assertSame(t, r.pickWeightedRandom(null, rng));
            assertSame("single-trail Route must ignore noRepeat",
                t, r.pickWeightedRandom(t, rng));
        }
    }

    @Test
    public void weightedDistributionApproximatesRequestedRatio()
    {
        // 77/23 split. Over 10k samples the empirical ratio should land
        // within ±2% of the target. Tolerance is generous because we're
        // pinning behavior, not the RNG quality.
        Trail a = trail("a");
        Trail b = trail("b");
        Route r = Route.builder().trail(a, 77).trail(b, 23).build();
        Random rng = new Random(12345);
        int countA = 0;
        final int N = 10_000;
        for (int i = 0; i < N; i++)
        {
            if (r.pickWeightedRandom(null, rng) == a) countA++;
        }
        double ratioA = countA / (double) N;
        assertTrue("expected ~0.77 ±0.02, got " + ratioA,
            ratioA > 0.75 && ratioA < 0.79);
    }

    @Test
    public void noRepeatExcludesPreviousPick()
    {
        // With noRepeat=true and two trails, the picker must alternate:
        // every call passes the most-recent pick as `previous`, so the
        // other trail is the only candidate.
        Trail a = trail("a");
        Trail b = trail("b");
        Route r = Route.builder().trail(a, 1).trail(b, 1).noRepeat(true).build();
        Random rng = new Random(7);
        Trail prev = null;
        for (int i = 0; i < 30; i++)
        {
            Trail picked = r.pickWeightedRandom(prev, rng);
            if (prev != null)
            {
                assertNotSame("noRepeat must exclude previous pick", prev, picked);
            }
            prev = picked;
        }
    }

    @Test
    public void noRepeatWithSingleTrailReturnsThatTrail()
    {
        Trail t = trail("only");
        Route r = Route.builder().trail(t, 1).noRepeat(true).build();
        Random rng = new Random(3);
        // Even passing `t` as previous, single-trail path returns `t`.
        for (int i = 0; i < 10; i++)
        {
            assertSame(t, r.pickWeightedRandom(t, rng));
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void zeroWeightRejected()
    {
        Route.builder().trail(trail("a"), 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void negativeWeightRejected()
    {
        Route.builder().trail(trail("a"), -3);
    }

    @Test(expected = IllegalArgumentException.class)
    public void emptyBuilderRejected()
    {
        Route.builder().build();
    }

    @Test
    public void corridorRadiusClampedToZeroThree()
    {
        Trail t = trail("a");
        assertEquals(3, Route.builder().trail(t).corridorRadius(99).build().corridorRadius());
        assertEquals(0, Route.builder().trail(t).corridorRadius(-1).build().corridorRadius());
        assertEquals(2, Route.builder().trail(t).corridorRadius(2).build().corridorRadius());
    }

    @Test
    public void corridorRadiusDefaultIsOne()
    {
        Route r = Route.builder().trail(trail("a")).build();
        assertEquals("default corridor radius is 1 (the v2 spec minimum)",
            1, r.corridorRadius());
    }

    @Test
    public void fromTrailsPicksUpAllMatchingPrefix()
    {
        // Three trails; two share the prefix and one doesn't.
        List<Trail> all = List.of(
            trail("lumby-bank-to-cook-north"),
            trail("lumby-bank-to-cook-south"),
            trail("draynor-loop"));
        Route r = Route.fromTrails(all, "lumby-bank-to-cook");
        assertEquals(2, r.entries().size());
        for (Route.Entry e : r.entries())
        {
            assertEquals("equal-weight", 1, e.weight());
            assertTrue(e.trail().name().startsWith("lumby-bank-to-cook"));
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void fromTrailsThrowsOnZeroMatches()
    {
        Route.fromTrails(List.of(trail("a"), trail("b")), "no-such-prefix");
    }
}
