package net.runelite.client.plugins.recorder.nav.v2.planner.spi;

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

/** Spec §3 / Lane 3 contract: tile-level BFS over a
 *  {@link CollisionView}.
 *
 *  <p><b>Local mock</b>: byte-identical to Lane 3's
 *  {@code nav/v2/bfs/SkretzoBfsKernel}. Integration consolidates by
 *  deleting this file and importing Lane 3's. The two
 *  implementations are kept in lockstep so that Lane 4 unit tests
 *  exercise the same expansion order Lane 3 owns at integration.
 *
 *  <p>Pure function. No logging. No side effects. The planner
 *  ({@link net.runelite.client.plugins.recorder.nav.v2.planner.WaypointPlanner})
 *  is the only caller.
 *
 *  <p>Cardinal-first expansion (W, E, S, N) followed by seeded
 *  permutation of diagonals (SW, SE, NW, NE). Same seed →
 *  byte-identical output. */
public final class SkretzoBfsKernel
{
	private SkretzoBfsKernel() {}

	public static final class BfsResult
	{
		private final Status status;
		private final List<WorldPoint> tiles;
		private final int expanded;
		private final FailureReason reasonIfFailed;

		private BfsResult(Status status, List<WorldPoint> tiles, int expanded, FailureReason reasonIfFailed)
		{
			this.status = status;
			this.tiles = tiles == null ? Collections.emptyList() : Collections.unmodifiableList(tiles);
			this.expanded = expanded;
			this.reasonIfFailed = reasonIfFailed;
		}

		public Status status() { return status; }
		public List<WorldPoint> tiles() { return tiles; }
		public int expanded() { return expanded; }
		public FailureReason reasonIfFailed() { return reasonIfFailed; }
	}

	public enum Status { PATH_FOUND, UNREACHABLE, BUDGET_EXHAUSTED }

	/** Mirror of Lane 3's {@code LocalReplanReason} (only the failure
	 *  modes the kernel itself can produce). Integration aligns. */
	public enum FailureReason { TARGET_UNREACHABLE, EXECUTOR_TIMEOUT }

	/** Plan from {@code from} to {@code to} on the same plane.
	 *  Predicate may be null (= no extra gating beyond collision). */
	public static BfsResult plan(CollisionView collision, WorldPoint from, WorldPoint to,
								 BfsConfig cfg, TilePredicate predicate, PathContext ctx)
	{
		if (from.equals(to))
		{
			return new BfsResult(Status.PATH_FOUND, List.of(from), 1, null);
		}
		if (from.getPlane() != to.getPlane())
		{
			return new BfsResult(Status.UNREACHABLE, null, 0, FailureReason.TARGET_UNREACHABLE);
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
				return new BfsResult(Status.BUDGET_EXHAUSTED, null, expanded, FailureReason.EXECUTOR_TIMEOUT);
			}
			long[] head = queue.poll();
			int x = (int) head[0];
			int y = (int) head[1];
			expanded++;

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
		return new BfsResult(Status.UNREACHABLE, null, expanded, FailureReason.TARGET_UNREACHABLE);
	}

	static boolean canMove(CollisionView collision, int x, int y, int plane, int dx, int dy)
	{
		if (dx == 0 && dy == 0) return false;
		int nx = x + dx;
		int ny = y + dy;
		WorldPoint dst = new WorldPoint(nx, ny, plane);
		int dstFlags = collision.flagsAt(dst);
		if ((dstFlags & CollisionDataFlag.BLOCK_MOVEMENT_FULL) != 0)
		{
			return false;
		}
		WorldPoint here = new WorldPoint(x, y, plane);
		int srcFlags = collision.flagsAt(here);
		if (dx == 0 && dy != 0)
		{
			return canMoveCardinal(srcFlags, dstFlags, 0, dy);
		}
		if (dy == 0 && dx != 0)
		{
			return canMoveCardinal(srcFlags, dstFlags, dx, 0);
		}
		// diagonal: pillar rule + diagonal flag on dest
		if (!canMoveCardinal(srcFlags, collision.flagsAt(new WorldPoint(x + dx, y, plane)), dx, 0))
		{
			return false;
		}
		if (!canMoveCardinal(srcFlags, collision.flagsAt(new WorldPoint(x, y + dy, plane)), 0, dy))
		{
			return false;
		}
		if ((collision.flagsAt(new WorldPoint(x + dx, y, plane)) & CollisionDataFlag.BLOCK_MOVEMENT_FULL) != 0)
		{
			return false;
		}
		if ((collision.flagsAt(new WorldPoint(x, y + dy, plane)) & CollisionDataFlag.BLOCK_MOVEMENT_FULL) != 0)
		{
			return false;
		}
		int diagDstBlock = diagonalBlockOnDest(dx, dy);
		if ((dstFlags & diagDstBlock) != 0)
		{
			return false;
		}
		return true;
	}

	private static boolean canMoveCardinal(int srcFlags, int dstFlags, int dx, int dy)
	{
		int srcExit;
		int dstEntry;
		if (dx == -1 && dy == 0)
		{
			srcExit = CollisionDataFlag.BLOCK_MOVEMENT_WEST;
			dstEntry = CollisionDataFlag.BLOCK_MOVEMENT_EAST;
		}
		else if (dx == 1 && dy == 0)
		{
			srcExit = CollisionDataFlag.BLOCK_MOVEMENT_EAST;
			dstEntry = CollisionDataFlag.BLOCK_MOVEMENT_WEST;
		}
		else if (dx == 0 && dy == -1)
		{
			srcExit = CollisionDataFlag.BLOCK_MOVEMENT_SOUTH;
			dstEntry = CollisionDataFlag.BLOCK_MOVEMENT_NORTH;
		}
		else if (dx == 0 && dy == 1)
		{
			srcExit = CollisionDataFlag.BLOCK_MOVEMENT_NORTH;
			dstEntry = CollisionDataFlag.BLOCK_MOVEMENT_SOUTH;
		}
		else
		{
			return false;
		}
		if ((srcFlags & srcExit) != 0) return false;
		if ((dstFlags & dstEntry) != 0) return false;
		if ((dstFlags & CollisionDataFlag.BLOCK_MOVEMENT_FULL) != 0) return false;
		return true;
	}

	private static int diagonalBlockOnDest(int dx, int dy)
	{
		if (dx == -1 && dy == -1) return CollisionDataFlag.BLOCK_MOVEMENT_NORTH_EAST;
		if (dx == 1 && dy == -1) return CollisionDataFlag.BLOCK_MOVEMENT_NORTH_WEST;
		if (dx == -1 && dy == 1) return CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_EAST;
		if (dx == 1 && dy == 1) return CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_WEST;
		return 0;
	}

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
			if (parentKey == null) break;
			cur = parentKey;
		}
		reversed.add(new WorldPoint(sx, sy, plane));
		Collections.reverse(reversed);
		return reversed;
	}

	private static long pack(int x, int y) { return ((long) x << 32) | (y & 0xFFFFFFFFL); }
	private static int unpackX(long k) { return (int) (k >> 32); }
	private static int unpackY(long k) { return (int) (k & 0xFFFFFFFFL); }
}
