package net.runelite.client.plugins.recorder.trail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.runelite.api.coords.WorldPoint;

/** Output of {@link TrailPlanner}: an ordered sequence of {@link Leg}s
 *  the {@link TrailWalker} executes. Two {@link Leg.Walk} legs never
 *  appear consecutively — the planner coalesces them into one. */
public final class TrailPath
{
    private final List<Leg> legs;

    public TrailPath(List<Leg> legs)
    {
        if (legs == null) throw new IllegalArgumentException("legs null");
        this.legs = Collections.unmodifiableList(List.copyOf(legs));
    }

    public List<Leg> legs() { return legs; }
    public int size() { return legs.size(); }
    public boolean isEmpty() { return legs.isEmpty(); }

    /** Convert a recorded {@link Trail} directly to a {@link TrailPath} —
     *  no graph, no A*, no junction-edge bridging. The trail IS the path.
     *
     *  <p>Stair-style transports (option contains "climb" / "teleport") are
     *  paired with the next plane-changing TILE event: any same-plane TILEs
     *  in between are post-click engine routing (the climb animation walks
     *  the player ONTO the stair tile before teleporting), and treating
     *  them as ordinarily-walkable creates a phantom WALK that the OSRS
     *  pathfinder can't satisfy — the bot ends up clicking near the stairs
     *  instead of clicking the stair object itself. Skipping them forces
     *  the walker to fire the TRANSPORT (Climb-down on the staircase) at
     *  the right time, mirroring V1/V2 behaviour.
     *
     *  <p>Gate-style transports (anything else, e.g. "Open") fire
     *  immediately: the gate doesn't move the player, so we emit it the
     *  moment we see it and let the next walk leg route the player
     *  through the now-open gate. */
    public static TrailPath fromTrail(Trail trail)
    {
        if (trail == null) throw new IllegalArgumentException("trail null");
        List<Leg> legs = new ArrayList<>();
        List<WorldPoint> walkBuf = new ArrayList<>();
        TrailEvent.Transport pendingStairs = null;
        // Tracks the LAST same-plane TILE event seen after a stair TRANSPORT
        // and before the plane change.  This is where the engine routed the
        // player to actually use the stair — i.e. the standing-tile right
        // next to the stair object — and it's the correct "walk closer to
        // the stair" target.  Clicking the stair object's own tile
        // (transport.tile) often pathfinds the player around walls because
        // the object's tile is inside the wall geometry.
        WorldPoint pendingStairsApproach = null;
        int prevPlane = Integer.MIN_VALUE;
        // After a non-stair transport (gate / door), the engine records
        // the player's still-current tile a tick later — a no-op duplicate
        // because the gate click doesn't move the player. If we let that
        // duplicate land in the next walk leg, the walker's chooseLegIndex
        // will see "next leg contains player" and skip past the TRANSPORT
        // without ever firing the Open action. Suppress the duplicate by
        // tracking the pre-gate tile and dropping post-gate TILEs that
        // match it until the player actually moves through the gate.
        WorldPoint suppressEqualTo = null;

        for (TrailEvent ev : trail.events())
        {
            if (ev instanceof TrailEvent.Tile tileEv)
            {
                WorldPoint tile = tileEv.tile();
                int plane = tile.getPlane();
                if (pendingStairs != null)
                {
                    if (prevPlane != Integer.MIN_VALUE && plane != prevPlane)
                    {
                        flushWalk(legs, walkBuf);
                        legs.add(toTransportLeg(pendingStairs, pendingStairsApproach));
                        pendingStairs = null;
                        pendingStairsApproach = null;
                        walkBuf.add(tile);
                        prevPlane = plane;
                    }
                    else
                    {
                        // Same-plane post-click tile — the engine routed
                        // the player onto the stair tile here.  Track the
                        // LAST one as the approach tile (drop the
                        // intermediate steps).
                        pendingStairsApproach = tile;
                    }
                }
                else
                {
                    if (suppressEqualTo != null && tile.equals(suppressEqualTo))
                    {
                        // Engine's no-op duplicate after a gate click — drop.
                        continue;
                    }
                    suppressEqualTo = null;
                    if (walkBuf.isEmpty()
                        || !walkBuf.get(walkBuf.size() - 1).equals(tile))
                    {
                        walkBuf.add(tile);
                    }
                    prevPlane = plane;
                }
            }
            else if (ev instanceof TrailEvent.Transport tr)
            {
                // Widget-button clicks (CC_OP) were incorrectly captured in
                // older recordings — param0/param1 are component IDs, not scene
                // coords, producing garbage world tiles. Skip them.
                if ("CC_OP".equals(tr.targetKind()) || "CC_OP_LOW_PRIORITY".equals(tr.targetKind())) continue;
                if (isStairTransport(tr.option()))
                {
                    pendingStairs = tr;
                    // Seed approach tile with the LAST recorded TILE event
                    // before the click — engine routes the player from
                    // here to the stair tile.  Replaced (and refined) by
                    // any post-click same-plane tile we see next.
                    pendingStairsApproach = walkBuf.isEmpty()
                        ? null : walkBuf.get(walkBuf.size() - 1);
                }
                else
                {
                    WorldPoint preGateTile = walkBuf.isEmpty()
                        ? null : walkBuf.get(walkBuf.size() - 1);
                    flushWalk(legs, walkBuf);
                    // For gate / door transports the standing-tile is the
                    // pre-gate tile (clicking the gate doesn't move the
                    // player); use it as the approach target too.
                    legs.add(toTransportLeg(tr, preGateTile));
                    suppressEqualTo = preGateTile;
                }
            }
        }

        flushWalk(legs, walkBuf);
        if (pendingStairs != null)
        {
            // Trail ended without the expected plane change — emit the
            // transport anyway so the walker at least tries the action.
            legs.add(toTransportLeg(pendingStairs, pendingStairsApproach));
        }
        return new TrailPath(legs);
    }

    /** Drop {@code fromLegIdx} legs from the front. Returns the receiver
     *  unchanged if {@code fromLegIdx <= 0}; an empty path if it
     *  exceeds {@link #size()}. */
    public TrailPath subPath(int fromLegIdx)
    {
        if (fromLegIdx <= 0) return this;
        if (fromLegIdx >= legs.size()) return new TrailPath(List.of());
        return new TrailPath(new ArrayList<>(legs.subList(fromLegIdx, legs.size())));
    }

    /** Find the leg index whose closest tile (Chebyshev, same plane) to
     *  {@code here} is the smallest — i.e. the leg the player is on or
     *  just about to enter. Used to skip the prefix of a trail when the
     *  player resumes mid-route.
     *
     *  <p>When no leg has a tile on the player's plane (e.g. player is on
     *  plane 1 mid-stair but the trail collapses the plane-1 footprint
     *  into a transport), returns the index of the closest TRANSPORT leg
     *  whose source-side / destination-side bracket the player's plane,
     *  so the walker enters at a transport that will land them on the
     *  correct plane rather than stalling on a Walk leg whose tiles are
     *  all on a plane the player can't reach. Returns 0 as a last resort. */
    public int findEntryLeg(WorldPoint here)
    {
        if (here == null) return 0;
        int bestIdx = -1;
        int bestDist = Integer.MAX_VALUE;
        for (int i = 0; i < legs.size(); i++)
        {
            int d = legMinDistance(legs.get(i), here);
            if (d < bestDist) { bestDist = d; bestIdx = i; }
        }
        if (bestIdx >= 0) return bestIdx;
        // No leg has a tile on the player's plane. Find the first
        // transport whose tile is on the player's plane — the walker can
        // walk-to-then-click that transport and the resulting plane
        // change will deliver the player onto a downstream walk leg.
        for (int i = 0; i < legs.size(); i++)
        {
            if (legs.get(i) instanceof Leg.Transport tr
                && tr.tile().getPlane() == here.getPlane())
            {
                return i;
            }
        }
        return 0;
    }

    private static int legMinDistance(Leg leg, WorldPoint here)
    {
        int best = Integer.MAX_VALUE;
        if (leg instanceof Leg.Walk w)
        {
            for (WorldPoint t : w.tiles())
            {
                if (t.getPlane() != here.getPlane()) continue;
                int d = Math.max(Math.abs(t.getX() - here.getX()),
                                 Math.abs(t.getY() - here.getY()));
                if (d < best) best = d;
            }
        }
        else if (leg instanceof Leg.Transport t)
        {
            WorldPoint tile = t.tile();
            if (tile.getPlane() == here.getPlane())
            {
                best = Math.max(Math.abs(tile.getX() - here.getX()),
                                Math.abs(tile.getY() - here.getY()));
            }
        }
        return best;
    }

    private static void flushWalk(List<Leg> legs, List<WorldPoint> buf)
    {
        if (buf.isEmpty()) return;
        legs.add(new Leg.Walk(new ArrayList<>(buf)));
        buf.clear();
    }

    private static Leg.Transport toTransportLeg(TrailEvent.Transport tr,
                                                 WorldPoint approachTile)
    {
        return new Leg.Transport(
            tr.tile(),
            tr.option(),
            tr.targetId(),
            tr.targetKind(),
            tr.param0(),
            tr.param1(),
            approachTile);
    }

    private static boolean isStairTransport(String option)
    {
        if (option == null) return false;
        String lo = option.toLowerCase();
        return lo.contains("climb") || lo.contains("teleport")
            || lo.contains("-floor");
    }
}
