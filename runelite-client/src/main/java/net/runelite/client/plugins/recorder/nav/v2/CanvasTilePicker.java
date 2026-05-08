package net.runelite.client.plugins.recorder.nav.v2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;

/** Pick the next canvas-click target along a {@link V2Path}. The pick
 *  is at a weighted-random distance — short, mid, or long — and only
 *  candidates that pass the supplied empty-tile filter are returned.
 *
 *  <p>Bucket distances follow the spec's "long (12–16), mid (6–8),
 *  short (2–3)". Bucket weights bias slightly toward mid so the
 *  default cadence feels natural — long picks "lean ahead", short
 *  picks correct course mid-walk; mid is the steady state.
 *
 *  <p>Returns {@code null} when no candidate in any bucket passes the
 *  filter — that is the modality-switch signal for the executor (try
 *  minimap modality next tick instead of repeating the canvas attempt
 *  on the same crowded area). */
@Slf4j
public final class CanvasTilePicker
{
    public static final int SHORT_MIN = 2;
    public static final int SHORT_MAX = 3;
    public static final int MID_MIN = 6;
    public static final int MID_MAX = 8;
    public static final int LONG_MIN = 12;
    public static final int LONG_MAX = 16;

    /** Cumulative bucket weights (must sum to 1.0). */
    private static final double WEIGHT_SHORT = 0.25;
    private static final double WEIGHT_MID = 0.50;
    // long = 1.0 - SHORT - MID = 0.25

    /** Pick the next canvas tile from the path's forward tiles, gated
     *  by {@code filter}. Returns null when every shortlisted candidate
     *  fails the filter (caller switches to minimap modality). */
    public WorldPoint pickNext(V2Path path, WorldPoint player,
                               Predicate<WorldPoint> filter, Random rng)
    {
        return pickNext(path, player, filter, rng, true);
    }

    /** Phase-13 toggle variant: when {@code variableDistance} is false,
     *  the picker only considers the short bucket — always closest
     *  walkable forward tile. Used when the user disables variable click
     *  distance via {@code RecorderConfig.enableV2VariableDistance} to
     *  isolate modality-specific failures. */
    public WorldPoint pickNext(V2Path path, WorldPoint player,
                               Predicate<WorldPoint> filter, Random rng,
                               boolean variableDistance)
    {
        if (path == null || path.isEmpty()) return null;
        return pickNextInTiles(collectWalkTiles(path), player, filter, rng, variableDistance);
    }

    /** Per-leg overload — operates on an explicit tile list (typically
     *  one walk leg's tiles) instead of flattening the whole path. */
    public WorldPoint pickNextInTiles(List<WorldPoint> walkTiles, WorldPoint player,
                                      Predicate<WorldPoint> filter, Random rng,
                                      boolean variableDistance)
    {
        return pickNextInTilesAfter(walkTiles, -1, player, filter, rng, variableDistance);
    }

    /** Progress-monotonic overload: only considers candidates whose path
     *  index is strictly greater than {@code minIdxExclusive}. The V2
     *  executor maintains a per-leg progress cursor and passes it here so
     *  consecutive picks never regress to a tile already passed.
     *
     *  <p>Pass {@code -1} to disable the floor (equivalent to the old
     *  closestIndex-only behavior).
     *
     *  <p>Round-2 stabilization: the long bucket (12–16 tiles) is gated
     *  off by default — it routinely overshoots the engine's pathfinder
     *  on unfamiliar terrain, producing the "5 forward, 2 back" stall
     *  flap. Re-enable later behind a snapshot-reachability gate. */
    public WorldPoint pickNextInTilesAfter(List<WorldPoint> walkTiles, int minIdxExclusive,
                                           WorldPoint player,
                                           Predicate<WorldPoint> filter, Random rng,
                                           boolean variableDistance)
    {
        if (walkTiles == null || walkTiles.isEmpty()) return null;
        if (player == null || filter == null || rng == null) return null;

        int playerIdx = closestIndex(walkTiles, player);
        // Floor by progress cursor: never regress past the leg's high-water mark.
        int floor = Math.max(playerIdx, minIdxExclusive);
        int forwardCount = walkTiles.size() - floor - 1;
        if (forwardCount <= 0) return null;

        if (!variableDistance)
        {
            return pickInBucket(walkTiles, floor, 0, filter, rng);
        }

        // Round-2: long bucket disabled. Try short, then mid, then any
        // forward tile that passes the filter. Long-distance picks
        // produced the worst-case overshoot stalls in live testing.
        int[] bucketOrder = pickShortMidBucketOrder(rng);
        for (int b : bucketOrder)
        {
            WorldPoint pick = pickInBucket(walkTiles, floor, b, filter, rng);
            if (pick != null) return pick;
        }
        return null;
    }

    /** Round-2 bucket order: short + mid only, weighted toward mid.
     *  Long bucket reserved for a future opt-in path. */
    private static int[] pickShortMidBucketOrder(Random rng)
    {
        // ~33% short, ~67% mid. Either way, both get tried.
        boolean midFirst = rng.nextDouble() >= 0.33;
        return midFirst ? new int[] {1, 0} : new int[] {0, 1};
    }

    private static List<WorldPoint> collectWalkTiles(V2Path path)
    {
        List<WorldPoint> out = new ArrayList<>();
        for (V2Leg leg : path.legs())
        {
            if (leg instanceof V2Leg.Walk w) out.addAll(w.tiles());
            // Transport legs are clicked separately (verb-click on object),
            // not picked from here. Still inserts the from/to tiles into
            // the path's allTiles() but we deliberately skip them — the
            // executor handles transports as their own modality.
        }
        return out;
    }

    /** Index of the path tile closest to {@code player} (Chebyshev). On
     *  ties, picks the earliest. */
    private static int closestIndex(List<WorldPoint> tiles, WorldPoint player)
    {
        int best = 0;
        int bestDist = Integer.MAX_VALUE;
        for (int i = 0; i < tiles.size(); i++)
        {
            WorldPoint t = tiles.get(i);
            int d = Math.max(Math.abs(t.getX() - player.getX()),
                             Math.abs(t.getY() - player.getY()))
                    + (t.getPlane() == player.getPlane() ? 0 : 1000);
            if (d < bestDist) { bestDist = d; best = i; }
        }
        return best;
    }

    /** Bucket constants: 0=short, 1=mid, 2=long. Order them by weighted
     *  pick so the most-likely bucket is tried first; falls through if
     *  empty post-filter. */
    private static int[] pickBucketOrder(Random rng)
    {
        double r = rng.nextDouble();
        int first;
        if (r < WEIGHT_SHORT) first = 0;
        else if (r < WEIGHT_SHORT + WEIGHT_MID) first = 1;
        else first = 2;
        // Try first, then the other two in random order so a filtered
        // bucket doesn't always skew to the same alternate.
        int[] all = {0, 1, 2};
        all[0] = first;
        int second = (first == 0 ? (rng.nextBoolean() ? 1 : 2)
                    : first == 1 ? (rng.nextBoolean() ? 0 : 2)
                    : (rng.nextBoolean() ? 0 : 1));
        int third = 0 + 1 + 2 - first - second;
        all[1] = second;
        all[2] = third;
        return all;
    }

    private static WorldPoint pickInBucket(List<WorldPoint> tiles, int playerIdx,
                                           int bucket,
                                           Predicate<WorldPoint> filter, Random rng)
    {
        int min, max;
        switch (bucket)
        {
            case 0 -> { min = SHORT_MIN; max = SHORT_MAX; }
            case 1 -> { min = MID_MIN; max = MID_MAX; }
            case 2 -> { min = LONG_MIN; max = LONG_MAX; }
            default -> { return null; }
        }
        int forwardEnd = tiles.size() - 1;
        int rangeStart = Math.min(playerIdx + min, forwardEnd);
        int rangeEnd = Math.min(playerIdx + max, forwardEnd);
        if (rangeStart > rangeEnd) return null;

        // Shuffle indices in this bucket and return the first that
        // passes the filter. Random shuffle keeps tile-level variation
        // across consecutive picks; sequential first-pass would always
        // pick the same offset.
        List<Integer> idx = new ArrayList<>();
        for (int i = rangeStart; i <= rangeEnd; i++) idx.add(i);
        Collections.shuffle(idx, rng);
        for (int i : idx)
        {
            WorldPoint candidate = tiles.get(i);
            if (filter.test(candidate)) return candidate;
        }
        return null;
    }
}
