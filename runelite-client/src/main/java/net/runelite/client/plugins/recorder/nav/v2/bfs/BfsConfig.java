package net.runelite.client.plugins.recorder.nav.v2.bfs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/** Immutable BFS configuration for {@link SkretzoBfsKernel}.
 *
 *  <p>Holds three things:
 *  <ul>
 *    <li>{@link #maxRadius()} — Chebyshev radius cap; default 128 to match the
 *        Jagex 128×128 server bound.</li>
 *    <li>{@link #maxExpandedTiles()} — hard budget on expansions; the kernel
 *        returns {@code BUDGET_EXHAUSTED} when exceeded. Default 65536, which
 *        is &gt; 128×128 = 16384, leaving slack for exploration around walls.</li>
 *    <li>{@link #routeSeed()} — seeds {@link #diagonalOrder()}. Same seed →
 *        byte-identical diagonal order → byte-identical BFS output. Different
 *        seeds may produce one of 24 permutations of {@link Diagonal}.
 *        The cardinal order is <b>always fixed</b> as W, E, S, N.</li>
 *  </ul>
 *
 *  <p>Cardinal order is non-configurable on purpose: it is the load-bearing
 *  rule that gives a Jagex-aligned first-found path. Only the diagonal
 *  tie-break may vary, which lets the planner produce trace variety without
 *  changing the chosen route shape.
 *
 *  <p>Lane 3 / Task 1.
 */
public final class BfsConfig
{
	/** Cardinal step directions, in their fixed expansion order. */
	public enum Cardinal { W, E, S, N }

	/** Diagonal step directions; expansion order is seeded permutation of these. */
	public enum Diagonal { SW, SE, NW, NE }

	private static final int DEFAULT_MAX_RADIUS = 128;
	private static final int DEFAULT_MAX_EXPANDED = 65_536;

	private static final List<Cardinal> FIXED_CARDINAL_ORDER =
		Collections.unmodifiableList(Arrays.asList(
			Cardinal.W, Cardinal.E, Cardinal.S, Cardinal.N));

	private final int maxRadius;
	private final int maxExpandedTiles;
	private final long routeSeed;

	private BfsConfig(int maxRadius, int maxExpandedTiles, long routeSeed)
	{
		this.maxRadius = maxRadius;
		this.maxExpandedTiles = maxExpandedTiles;
		this.routeSeed = routeSeed;
	}

	/** Default config: 128 radius, 65536 expanded budget, seed 0. */
	public static BfsConfig defaults()
	{
		return new BfsConfig(DEFAULT_MAX_RADIUS, DEFAULT_MAX_EXPANDED, 0L);
	}

	/** Returns a new instance with the given seed; other fields preserved. */
	public BfsConfig withSeed(long seed)
	{
		return new BfsConfig(maxRadius, maxExpandedTiles, seed);
	}

	/** Returns a new instance with the given radius cap. */
	public BfsConfig withMaxRadius(int radius)
	{
		return new BfsConfig(radius, maxExpandedTiles, routeSeed);
	}

	/** Returns a new instance with the given expanded-tile budget. */
	public BfsConfig withMaxExpandedTiles(int budget)
	{
		return new BfsConfig(maxRadius, budget, routeSeed);
	}

	public int maxRadius() { return maxRadius; }
	public int maxExpandedTiles() { return maxExpandedTiles; }
	public long routeSeed() { return routeSeed; }

	/** Fixed cardinal expansion order: W, E, S, N. Never varies with seed. */
	public List<Cardinal> cardinalOrder()
	{
		return FIXED_CARDINAL_ORDER;
	}

	/** Diagonal expansion order, derived from {@link #routeSeed()}.
	 *  Same seed → same order (deterministic). Across enough seeds, all 24
	 *  permutations of {SW, SE, NW, NE} are reachable. */
	public List<Diagonal> diagonalOrder()
	{
		// Fisher-Yates shuffle seeded from routeSeed; deterministic and uniform.
		List<Diagonal> order = new ArrayList<>(Arrays.asList(Diagonal.values()));
		Random rng = new Random(routeSeed);
		for (int i = order.size() - 1; i > 0; i--)
		{
			int j = rng.nextInt(i + 1);
			Diagonal tmp = order.get(i);
			order.set(i, order.get(j));
			order.set(j, tmp);
		}
		return Collections.unmodifiableList(order);
	}

	@Override
	public boolean equals(Object o)
	{
		if (this == o) return true;
		if (!(o instanceof BfsConfig)) return false;
		BfsConfig that = (BfsConfig) o;
		return maxRadius == that.maxRadius
			&& maxExpandedTiles == that.maxExpandedTiles
			&& routeSeed == that.routeSeed;
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(maxRadius, maxExpandedTiles, routeSeed);
	}

	@Override
	public String toString()
	{
		return "BfsConfig{maxRadius=" + maxRadius
			+ ", maxExpandedTiles=" + maxExpandedTiles
			+ ", routeSeed=" + routeSeed + "}";
	}
}
