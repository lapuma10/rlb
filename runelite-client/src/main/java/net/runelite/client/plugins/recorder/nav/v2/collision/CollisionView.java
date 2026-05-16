package net.runelite.client.plugins.recorder.nav.v2.collision;

import java.util.Objects;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.coords.WorldPoint;

/** Merges a {@link GlobalCollisionSnapshot} (bundled) with a
 *  {@link LiveSceneCollisionOverlay} (captured this tick) into a single
 *  immutable collision view. The live overlay wins inside the loaded
 *  104×104 scene; the global snapshot is the fallback outside; both
 *  miss → {@link CollisionDataFlag#BLOCK_MOVEMENT_FULL}.
 *
 *  <p>This is what the BFS kernel and tile predicates consult — they
 *  see one merged source, not two sources. Source attribution is
 *  exposed for debug output ({@link #source(WorldPoint)}, used by
 *  Lane 6 traces and by {@link #describeTile(WorldPoint)}).
 *
 *  <p>Implements Lane 3's narrow {@code nav.v2.bfs.CollisionView}
 *  interface so the BFS kernel + RouteValidator can consume Lane 2's
 *  concrete view directly. The narrow interface has only
 *  {@code flagsAt(WorldPoint)}; this class satisfies it.
 *
 *  <p>This class is part of the Lane 2 deliverable for the
 *  observation-aware navigation engine (spec §4 Lane 2). */
public final class CollisionView
    implements net.runelite.client.plugins.recorder.nav.v2.bfs.CollisionView
{
    public enum Source { LIVE_OVERLAY, GLOBAL_SNAPSHOT, OUT_OF_RANGE }

    private final GlobalCollisionSnapshot global;
    private final LiveSceneCollisionOverlay live;

    public CollisionView(GlobalCollisionSnapshot global, LiveSceneCollisionOverlay live)
    {
        this.global = Objects.requireNonNull(global, "global");
        this.live = Objects.requireNonNull(live, "live");
    }

    /** Returns the collision flags for {@code p}. Live overlay wins; global
     *  is the fallback; both miss → {@link CollisionDataFlag#BLOCK_MOVEMENT_FULL}. */
    public int flagsAt(WorldPoint p)
    {
        if (live.containsTile(p)) return live.flagsAt(p);
        if (global.isLoaded(p))   return global.flagsAt(p);
        return CollisionDataFlag.BLOCK_MOVEMENT_FULL;
    }

    /** Coordinate overload. */
    public int flagsAt(int worldX, int worldY, int plane)
    {
        return flagsAt(new WorldPoint(worldX, worldY, plane));
    }

    /** Which source produced the flag for {@code p}. Used in route traces. */
    public Source source(WorldPoint p)
    {
        if (live.containsTile(p)) return Source.LIVE_OVERLAY;
        if (global.isLoaded(p))   return Source.GLOBAL_SNAPSHOT;
        return Source.OUT_OF_RANGE;
    }

    /** Debug-output formatter — emits the per-tile representation Lane 6
     *  expects per spec §4 Lane 2:
     *  {@code plane: N, source: S, flags: 0xX, neighbors: [...]}. */
    public String describeTile(WorldPoint p)
    {
        int flags = flagsAt(p);
        Source src = source(p);

        // 8 neighbours: N, S, E, W, NE, NW, SE, SW.
        int[][] dxdy = { {0,1}, {0,-1}, {1,0}, {-1,0}, {1,1}, {-1,1}, {1,-1}, {-1,-1} };
        String[] labels = { "N", "S", "E", "W", "NE", "NW", "SE", "SW" };

        StringBuilder neighbors = new StringBuilder("[");
        for (int i = 0; i < dxdy.length; i++)
        {
            if (i > 0) neighbors.append(", ");
            WorldPoint np = new WorldPoint(p.getX() + dxdy[i][0], p.getY() + dxdy[i][1], p.getPlane());
            boolean walkable = canMoveTo(p, dxdy[i][0], dxdy[i][1]);
            Source nsrc = source(np);
            neighbors.append(labels[i])
                .append('=').append(walkable ? 'y' : 'n')
                .append('@').append(nsrc);
        }
        neighbors.append(']');

        return String.format(
            "plane=%d source=%s flags=0x%x neighbors=%s",
            p.getPlane(), src, flags, neighbors);
    }

    /** Helper: would the move from {@code from} by ({@code dx}, {@code dy})
     *  be permitted by the cardinal-direction bits at {@code from} (or by
     *  the diagonal-corner rule for diagonals)? Pure read of {@link
     *  #flagsAt} — does not consult predicates. */
    public boolean canMoveTo(WorldPoint from, int dx, int dy)
    {
        int f = flagsAt(from);
        // Diagonals first.
        if (dx == 1 && dy == 1)   return checkDiag(from, dx, dy, CollisionDataFlag.BLOCK_MOVEMENT_NORTH | CollisionDataFlag.BLOCK_MOVEMENT_EAST);
        if (dx == -1 && dy == 1)  return checkDiag(from, dx, dy, CollisionDataFlag.BLOCK_MOVEMENT_NORTH | CollisionDataFlag.BLOCK_MOVEMENT_WEST);
        if (dx == 1 && dy == -1)  return checkDiag(from, dx, dy, CollisionDataFlag.BLOCK_MOVEMENT_SOUTH | CollisionDataFlag.BLOCK_MOVEMENT_EAST);
        if (dx == -1 && dy == -1) return checkDiag(from, dx, dy, CollisionDataFlag.BLOCK_MOVEMENT_SOUTH | CollisionDataFlag.BLOCK_MOVEMENT_WEST);
        if (dx == 0 && dy == 1)   return (f & CollisionDataFlag.BLOCK_MOVEMENT_NORTH) == 0
                                    && (flagsAt(neighbour(from, dx, dy)) & CollisionDataFlag.BLOCK_MOVEMENT_FULL) == 0;
        if (dx == 0 && dy == -1)  return (f & CollisionDataFlag.BLOCK_MOVEMENT_SOUTH) == 0
                                    && (flagsAt(neighbour(from, dx, dy)) & CollisionDataFlag.BLOCK_MOVEMENT_FULL) == 0;
        if (dx == 1 && dy == 0)   return (f & CollisionDataFlag.BLOCK_MOVEMENT_EAST) == 0
                                    && (flagsAt(neighbour(from, dx, dy)) & CollisionDataFlag.BLOCK_MOVEMENT_FULL) == 0;
        if (dx == -1 && dy == 0)  return (f & CollisionDataFlag.BLOCK_MOVEMENT_WEST) == 0
                                    && (flagsAt(neighbour(from, dx, dy)) & CollisionDataFlag.BLOCK_MOVEMENT_FULL) == 0;
        return false; // non-adjacent: not handled here
    }

    /** Diagonal corner check: the two adjacent cardinal moves must also be
     *  legal (you can't cut through a corner). Mirrors Skretzo's nw/ne/sw/se
     *  formula. */
    private boolean checkDiag(WorldPoint from, int dx, int dy, int blockMask)
    {
        int f = flagsAt(from);
        if ((f & blockMask) != 0) return false;
        // Adjacent cardinal moves must also be open.
        if (dx != 0)
        {
            if (!canMoveTo(from, dx, 0)) return false;
        }
        if (dy != 0)
        {
            if (!canMoveTo(from, 0, dy)) return false;
        }
        // And the target tile itself must not be a fully-blocked tile.
        if ((flagsAt(neighbour(from, dx, dy)) & CollisionDataFlag.BLOCK_MOVEMENT_FULL) != 0) return false;
        return true;
    }

    private static WorldPoint neighbour(WorldPoint p, int dx, int dy)
    {
        return new WorldPoint(p.getX() + dx, p.getY() + dy, p.getPlane());
    }

    /** Visible for advanced consumers (Lane 3 BFS) — access to the
     *  underlying global snapshot. */
    public GlobalCollisionSnapshot global() { return global; }

    /** Visible for advanced consumers (Lane 3 BFS) — access to the
     *  underlying live overlay. */
    public LiveSceneCollisionOverlay live() { return live; }
}
