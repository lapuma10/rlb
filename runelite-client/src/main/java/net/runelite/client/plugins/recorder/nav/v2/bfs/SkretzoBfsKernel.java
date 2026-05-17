package net.runelite.client.plugins.recorder.nav.v2.bfs;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.coords.WorldPoint;

/** Tile-level BFS over a {@link CollisionView}, forked from Skretzo's
 *  open-source RuneLite shortest-path plugin.
 *
 *  <p>Cardinal-first expansion order ({@code W, E, S, N}) followed by a
 *  seeded permutation of {@code (SW, SE, NW, NE)} from {@link BfsConfig}.
 *  Cardinal order is fixed; only diagonals vary by seed. The intent is
 *  server-alignment with Jagex's first-found-path BFS; <b>validation</b>
 *  is in {@code JagexServerAlignmentTest} (Task 4), not asserted here.
 *
 *  <p>Movement rules:
 *  <ul>
 *    <li>Cardinal step from {@code A} to {@code B}: blocked when
 *        {@code flagsAt(A)} has the matching directional block bit,
 *        {@code flagsAt(B)} has the opposite directional block bit, OR
 *        {@code flagsAt(B)} has any {@link CollisionDataFlag#BLOCK_MOVEMENT_FULL}
 *        bit set.</li>
 *    <li>Diagonal step from {@code A} to {@code D}: requires
 *        <i>both</i> intermediate cardinal half-steps to be unblocked
 *        (the pillar / corner rule). Additionally, {@code flagsAt(D)}'s
 *        opposite-diagonal block bit must not be set, and {@code D}
 *        must not be fully blocked.</li>
 *  </ul>
 *
 *  <p>Plane-bound: BFS does not cross plane boundaries. Cross-plane
 *  routing is the planner's job (transports).
 *
 *  <p>Returns {@link BfsResult} carrying status, tile list (if found),
 *  expansion count, and a typed reason on failure. Pure function: no
 *  side effects, no logging.
 *
 *  <p>Lane 3 / Task 2.
 */
public final class SkretzoBfsKernel
{
	private SkretzoBfsKernel() {}

	/** Result of a BFS plan call. */
	public static final class BfsResult
	{
		private final Status status;
		private final List<WorldPoint> tiles;
		private final int expanded;
		private final LocalReplanReason reasonIfFailed;

		private BfsResult(Status status, List<WorldPoint> tiles, int expanded, LocalReplanReason reasonIfFailed)
		{
			this.status = status;
			this.tiles = tiles == null ? Collections.emptyList() : Collections.unmodifiableList(tiles);
			this.expanded = expanded;
			this.reasonIfFailed = reasonIfFailed;
		}

		public Status status() { return status; }
		public List<WorldPoint> tiles() { return tiles; }
		public int expanded() { return expanded; }
		public LocalReplanReason reasonIfFailed() { return reasonIfFailed; }
	}

	public enum Status { PATH_FOUND, UNREACHABLE, BUDGET_EXHAUSTED }

	/** Run BFS from {@code from} to {@code to} on the same plane. */
	public static BfsResult plan(CollisionView collision, WorldPoint from, WorldPoint to,
								 BfsConfig cfg, TilePredicate predicate)
	{
		return plan(collision, from, to, cfg, predicate, null);
	}

	/** Run BFS from {@code from} to {@code to}. The {@code ctx} is forwarded
	 *  unmodified to every {@link TilePredicate#accept} call. */
	public static BfsResult plan(CollisionView collision, WorldPoint from, WorldPoint to,
								 BfsConfig cfg, TilePredicate predicate, Object ctx)
	{
		// trivial: same tile
		if (from.equals(to))
		{
			return new BfsResult(Status.PATH_FOUND, List.of(from), 1, null);
		}
		// cross-plane queries are out of scope for the kernel; planner handles transports.
		if (from.getPlane() != to.getPlane())
		{
			return new BfsResult(Status.UNREACHABLE, null, 0, LocalReplanReason.TARGET_UNREACHABLE);
		}

		int plane = from.getPlane();
		int sx = from.getX();
		int sy = from.getY();

		Map<Long, Long> parent = new HashMap<>();
		Set<Long> visited = new HashSet<>();
		Deque<long[]> queue = new ArrayDeque<>();
		long startKey = pack(sx, sy);
		queue.add(new long[]{sx, sy});
		visited.add(startKey);
		int expanded = 0;

		List<int[]> stepOrder = buildStepOrder(cfg);

		while (!queue.isEmpty())
		{
			if (expanded >= cfg.maxExpandedTiles())
			{
				return new BfsResult(Status.BUDGET_EXHAUSTED, null, expanded, LocalReplanReason.EXECUTOR_TIMEOUT);
			}
			long[] head = queue.poll();
			int x = (int) head[0];
			int y = (int) head[1];
			expanded++;

			// Chebyshev-distance bound
			int chebToStart = Math.max(Math.abs(x - sx), Math.abs(y - sy));
			if (chebToStart > cfg.maxRadius())
			{
				continue;
			}

			for (int[] step : stepOrder)
			{
				int dx = step[0];
				int dy = step[1];
				int nx = x + dx;
				int ny = y + dy;
				long nk = pack(nx, ny);
				if (visited.contains(nk))
				{
					continue;
				}
				// chebyshev bound on neighbor
				int chebN = Math.max(Math.abs(nx - sx), Math.abs(ny - sy));
				if (chebN > cfg.maxRadius())
				{
					continue;
				}
				if (!canMove(collision, x, y, plane, dx, dy))
				{
					continue;
				}
				WorldPoint nWp = new WorldPoint(nx, ny, plane);
				if (predicate != null && !predicate.accept(nWp, ctx))
				{
					continue;
				}
				visited.add(nk);
				parent.put(nk, pack(x, y));
				if (nx == to.getX() && ny == to.getY())
				{
					return new BfsResult(Status.PATH_FOUND, reconstruct(parent, sx, sy, nx, ny, plane), expanded, null);
				}
				queue.add(new long[]{nx, ny});
			}
		}
		return new BfsResult(Status.UNREACHABLE, null, expanded, LocalReplanReason.TARGET_UNREACHABLE);
	}

	/** Returns true iff a single step from (x,y) by (dx,dy) is allowed by
	 *  collision flags. Cardinal-first; diagonals require both cardinal
	 *  half-steps to be unblocked (pillar / corner rule).
	 *
	 *  <p>Public for reuse by {@link
	 *  net.runelite.client.plugins.recorder.nav.v2.collision.ConnectivityComponents}
	 *  — the components flood-fill must use the same step rule the BFS
	 *  applies at runtime, otherwise Dijkstra's component filter and
	 *  BFS's walk check could disagree. One source of truth lives here. */
	public static boolean canMove(CollisionView collision, int x, int y, int plane, int dx, int dy)
	{
		if (dx == 0 && dy == 0) return false;
		int nx = x + dx;
		int ny = y + dy;
		WorldPoint here = new WorldPoint(x, y, plane);
		WorldPoint dst = new WorldPoint(nx, ny, plane);
		int dstFlags = collision.flagsAt(dst);
		if ((dstFlags & CollisionDataFlag.BLOCK_MOVEMENT_FULL) != 0)
		{
			return false;
		}
		int srcFlags = collision.flagsAt(here);
		if (dx == 0 && dy != 0)
		{
			return canMoveCardinal(srcFlags, dstFlags, 0, dy);
		}
		if (dy == 0 && dx != 0)
		{
			return canMoveCardinal(srcFlags, dstFlags, dx, 0);
		}
		// diagonal: pillar rule + diagonal flag
		if (!canMoveCardinal(srcFlags, collision.flagsAt(new WorldPoint(x + dx, y, plane)), dx, 0))
		{
			return false;
		}
		if (!canMoveCardinal(srcFlags, collision.flagsAt(new WorldPoint(x, y + dy, plane)), 0, dy))
		{
			return false;
		}
		// The intermediate cardinal tiles must not be fully blocked either.
		if ((collision.flagsAt(new WorldPoint(x + dx, y, plane)) & CollisionDataFlag.BLOCK_MOVEMENT_FULL) != 0)
		{
			return false;
		}
		if ((collision.flagsAt(new WorldPoint(x, y + dy, plane)) & CollisionDataFlag.BLOCK_MOVEMENT_FULL) != 0)
		{
			return false;
		}
		// Diagonal block flag on dest
		int diagDstBlock = diagonalBlockOnDest(dx, dy);
		if ((dstFlags & diagDstBlock) != 0)
		{
			return false;
		}
		return true;
	}

	/** Cardinal step (dx,dy) must have at least one of {dx, dy} == 0 and the other ±1. */
	private static boolean canMoveCardinal(int srcFlags, int dstFlags, int dx, int dy)
	{
		// Determine source-side exit flag and dest-side entry flag.
		int srcExit;
		int dstEntry;
		if (dx == -1 && dy == 0) // moving WEST
		{
			srcExit = CollisionDataFlag.BLOCK_MOVEMENT_WEST;
			dstEntry = CollisionDataFlag.BLOCK_MOVEMENT_EAST;
		}
		else if (dx == 1 && dy == 0) // moving EAST
		{
			srcExit = CollisionDataFlag.BLOCK_MOVEMENT_EAST;
			dstEntry = CollisionDataFlag.BLOCK_MOVEMENT_WEST;
		}
		else if (dx == 0 && dy == -1) // moving SOUTH
		{
			srcExit = CollisionDataFlag.BLOCK_MOVEMENT_SOUTH;
			dstEntry = CollisionDataFlag.BLOCK_MOVEMENT_NORTH;
		}
		else if (dx == 0 && dy == 1) // moving NORTH
		{
			srcExit = CollisionDataFlag.BLOCK_MOVEMENT_NORTH;
			dstEntry = CollisionDataFlag.BLOCK_MOVEMENT_SOUTH;
		}
		else
		{
			// not cardinal; caller bug
			return false;
		}
		if ((srcFlags & srcExit) != 0) return false;
		if ((dstFlags & dstEntry) != 0) return false;
		if ((dstFlags & CollisionDataFlag.BLOCK_MOVEMENT_FULL) != 0) return false;
		return true;
	}

	/** The diagonal-block flag on the destination tile, indexed by (dx,dy). */
	private static int diagonalBlockOnDest(int dx, int dy)
	{
		// dx=-1, dy=-1: moving SW -> dest sees its NE entry blocked
		if (dx == -1 && dy == -1) return CollisionDataFlag.BLOCK_MOVEMENT_NORTH_EAST;
		if (dx == 1 && dy == -1) return CollisionDataFlag.BLOCK_MOVEMENT_NORTH_WEST;
		if (dx == -1 && dy == 1) return CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_EAST;
		if (dx == 1 && dy == 1) return CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_WEST;
		return 0;
	}

	/** Build the step order: cardinals first in their fixed order
	 *  (W, E, S, N), then diagonals in the seeded permuted order. */
	private static List<int[]> buildStepOrder(BfsConfig cfg)
	{
		List<int[]> order = new ArrayList<>(8);
		for (BfsConfig.Cardinal c : cfg.cardinalOrder())
		{
			order.add(cardinalStep(c));
		}
		for (BfsConfig.Diagonal d : cfg.diagonalOrder())
		{
			order.add(diagonalStep(d));
		}
		return order;
	}

	private static int[] cardinalStep(BfsConfig.Cardinal c)
	{
		switch (c)
		{
			case W: return new int[]{-1, 0};
			case E: return new int[]{1, 0};
			case S: return new int[]{0, -1};
			case N: return new int[]{0, 1};
			default: throw new IllegalArgumentException("unknown cardinal: " + c);
		}
	}

	private static int[] diagonalStep(BfsConfig.Diagonal d)
	{
		switch (d)
		{
			case SW: return new int[]{-1, -1};
			case SE: return new int[]{1, -1};
			case NW: return new int[]{-1, 1};
			case NE: return new int[]{1, 1};
			default: throw new IllegalArgumentException("unknown diagonal: " + d);
		}
	}

	private static List<WorldPoint> reconstruct(Map<Long, Long> parent, int sx, int sy,
												int gx, int gy, int plane)
	{
		List<WorldPoint> reversed = new ArrayList<>();
		long cur = pack(gx, gy);
		long start = pack(sx, sy);
		while (cur != start)
		{
			int x = unpackX(cur);
			int y = unpackY(cur);
			reversed.add(new WorldPoint(x, y, plane));
			Long parentKey = parent.get(cur);
			if (parentKey == null)
			{
				// Should not happen; defensive.
				break;
			}
			cur = parentKey;
		}
		reversed.add(new WorldPoint(sx, sy, plane));
		Collections.reverse(reversed);
		return reversed;
	}

	private static long pack(int x, int y)
	{
		return ((long) x << 32) | (y & 0xFFFFFFFFL);
	}

	private static int unpackX(long k) { return (int) (k >> 32); }
	private static int unpackY(long k) { return (int) (k & 0xFFFFFFFFL); }
}
