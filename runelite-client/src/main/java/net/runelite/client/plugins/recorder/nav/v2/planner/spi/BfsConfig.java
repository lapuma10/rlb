package net.runelite.client.plugins.recorder.nav.v2.planner.spi;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/** Spec §3 / Lane 3 contract: immutable BFS configuration.
 *
 *  <p><b>Local mock</b>: byte-identical to Lane 3's
 *  {@code nav/v2/bfs/BfsConfig}. Integration consolidates to Lane 3's
 *  canonical location.
 *
 *  <p>Cardinal order is fixed (W, E, S, N) per spec §1. Only
 *  diagonals vary by seed. */
public final class BfsConfig
{
	public enum Cardinal { W, E, S, N }
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

	public static BfsConfig defaults()
	{
		return new BfsConfig(DEFAULT_MAX_RADIUS, DEFAULT_MAX_EXPANDED, 0L);
	}

	public BfsConfig withSeed(long seed)
	{
		return new BfsConfig(maxRadius, maxExpandedTiles, seed);
	}

	public BfsConfig withMaxRadius(int radius)
	{
		return new BfsConfig(radius, maxExpandedTiles, routeSeed);
	}

	public BfsConfig withMaxExpandedTiles(int budget)
	{
		return new BfsConfig(maxRadius, budget, routeSeed);
	}

	public int maxRadius() { return maxRadius; }
	public int maxExpandedTiles() { return maxExpandedTiles; }
	public long routeSeed() { return routeSeed; }

	public List<Cardinal> cardinalOrder()
	{
		return FIXED_CARDINAL_ORDER;
	}

	public List<Diagonal> diagonalOrder()
	{
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
