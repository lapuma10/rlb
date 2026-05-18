package net.runelite.client.plugins.recorder.nav.v21;

import net.runelite.api.CollisionData;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.nav.v2.bfs.CollisionView;

/** Single-plane snapshot of live collision flags, safe to read off the
 *  client thread.
 *
 *  <p>Captured at tick start via {@link #capture}, then handed to
 *  {@link StaticPlanner} on the worker thread. Copying the plane (one
 *  {@code int[104][104]}, ~43 KB) avoids the data race where the engine
 *  mutates {@code CollisionData.getFlags()} mid-BFS. Heap pressure is
 *  acceptable — one snapshot per tick per script.
 *
 *  <p>Tiles outside the snapshot return {@link
 *  CollisionDataFlag#BLOCK_MOVEMENT_FULL} per the {@link CollisionView}
 *  contract. This is honest: BFS can't reason about tiles it can't
 *  see, and treating them as fully blocked makes it stop at the scene
 *  boundary. The reactive layer's replan-after-walking handles the
 *  scene-edge crossing case.
 *
 *  <p>Only the player's plane is captured. Cross-plane goals are
 *  reached via the planner's PlaneMismatch path (Climb verb on a
 *  stair/ladder), so the snapshot doesn't need other planes. */
public final class LiveCollisionView implements CollisionView
{
	/** Singleton "everything blocked" view — used when the client has no
	 *  loaded world (login flow, etc.) so {@link CollisionView#flagsAt}
	 *  is still well-defined. */
	public static final LiveCollisionView EMPTY =
		new LiveCollisionView(null, 0, 0, -1);

	private final int[][] flagsXY;  // scene-local, indexed [sceneX][sceneY]
	private final int baseX;
	private final int baseY;
	private final int plane;

	private LiveCollisionView(int[][] flagsXY, int baseX, int baseY, int plane)
	{
		this.flagsXY = flagsXY;
		this.baseX = baseX;
		this.baseY = baseY;
		this.plane = plane;
	}

	/** Capture the live collision for {@code plane} from the current
	 *  WorldView. Must be called on the client thread. Returns
	 *  {@link #EMPTY} when no scene is loaded. */
	public static LiveCollisionView capture(WorldView wv, int plane)
	{
		if (wv == null) return EMPTY;
		CollisionData[] maps = wv.getCollisionMaps();
		if (maps == null) return EMPTY;
		if (plane < 0 || plane >= maps.length) return EMPTY;
		CollisionData cd = maps[plane];
		if (cd == null) return EMPTY;
		int[][] src = cd.getFlags();
		if (src == null) return EMPTY;
		int[][] copy = new int[src.length][];
		for (int i = 0; i < src.length; i++)
		{
			if (src[i] != null) copy[i] = src[i].clone();
		}
		return new LiveCollisionView(copy, wv.getBaseX(), wv.getBaseY(), plane);
	}

	@Override
	public int flagsAt(WorldPoint p)
	{
		if (flagsXY == null) return CollisionDataFlag.BLOCK_MOVEMENT_FULL;
		if (p.getPlane() != plane) return CollisionDataFlag.BLOCK_MOVEMENT_FULL;
		int sx = p.getX() - baseX;
		int sy = p.getY() - baseY;
		if (sx < 0 || sy < 0 || sx >= flagsXY.length) return CollisionDataFlag.BLOCK_MOVEMENT_FULL;
		int[] col = flagsXY[sx];
		if (col == null || sy >= col.length) return CollisionDataFlag.BLOCK_MOVEMENT_FULL;
		return col[sy];
	}
}
