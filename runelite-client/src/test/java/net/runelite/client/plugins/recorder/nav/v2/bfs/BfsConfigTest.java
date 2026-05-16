package net.runelite.client.plugins.recorder.nav.v2.bfs;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** RED tests for {@link BfsConfig} — seeded diagonal tie-break, fixed cardinal order,
 *  determinism + variety contract. Lane 3 / Task 1. */
public class BfsConfigTest
{
	@Test
	public void withSeed_sameSeed_sameDiagonalOrder()
	{
		BfsConfig a = BfsConfig.defaults().withSeed(42L);
		BfsConfig b = BfsConfig.defaults().withSeed(42L);
		assertEquals(a.diagonalOrder(), b.diagonalOrder());
	}

	@Test
	public void withSeed_differentSeeds_canProduceDifferentOrders()
	{
		// scan a range; at least two seeds must differ in diagonal order
		List<BfsConfig.Diagonal> base = BfsConfig.defaults().withSeed(0L).diagonalOrder();
		boolean foundDifferent = false;
		for (long s = 1L; s < 200L; s++)
		{
			if (!BfsConfig.defaults().withSeed(s).diagonalOrder().equals(base))
			{
				foundDifferent = true;
				break;
			}
		}
		assertTrue("expected at least one seed in [1,200) to differ from seed 0", foundDifferent);
	}

	@Test
	public void diagonalOrder_is24Permutations()
	{
		// the diagonal order is a permutation of {SW, SE, NW, NE}; across enough seeds,
		// all 24 permutations must be reachable.
		Set<List<BfsConfig.Diagonal>> seen = new HashSet<>();
		for (long s = 0L; s < 20_000L; s++)
		{
			List<BfsConfig.Diagonal> order = BfsConfig.defaults().withSeed(s).diagonalOrder();
			assertEquals("diagonal order must have 4 entries", 4, order.size());
			assertEquals("diagonal order must be a permutation",
				new HashSet<>(Arrays.asList(BfsConfig.Diagonal.values())),
				new HashSet<>(order));
			seen.add(order);
			if (seen.size() == 24)
			{
				break;
			}
		}
		assertEquals("expected 24 distinct permutations across 20000 seeds", 24, seen.size());
	}

	@Test
	public void cardinalOrderIsFixed()
	{
		List<BfsConfig.Cardinal> expected = Arrays.asList(
			BfsConfig.Cardinal.W,
			BfsConfig.Cardinal.E,
			BfsConfig.Cardinal.S,
			BfsConfig.Cardinal.N);
		// regardless of seed, cardinal order must be exactly W,E,S,N
		for (long s : new long[]{0L, 1L, 42L, 99999L, Long.MAX_VALUE, -1L})
		{
			assertEquals("cardinal order must be fixed W,E,S,N for seed=" + s,
				expected, BfsConfig.defaults().withSeed(s).cardinalOrder());
		}
	}

	@Test
	public void defaults_haveSaneBounds()
	{
		BfsConfig cfg = BfsConfig.defaults();
		// 128 tile radius is the Jagex bound
		assertEquals(128, cfg.maxRadius());
		// expanded tile budget must be generous within 128x128
		assertTrue("maxExpandedTiles should be > 16384 (128*128) to allow exploration of full box",
			cfg.maxExpandedTiles() > 128 * 128);
	}

	@Test
	public void withSeed_doesNotMutate()
	{
		// immutability — withSeed returns a NEW instance
		BfsConfig a = BfsConfig.defaults();
		BfsConfig b = a.withSeed(42L);
		assertNotNull(a);
		assertNotNull(b);
		assertNotEquals("withSeed must return a new instance, not the same object", a, b);
	}

	@Test
	public void diagonalEnum_hasFourValues()
	{
		assertEquals(4, BfsConfig.Diagonal.values().length);
	}

	@Test
	public void cardinalEnum_hasFourValues()
	{
		assertEquals(4, BfsConfig.Cardinal.values().length);
	}
}
