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
        if (player == null || filter == null || rng == null) return null;

        List<WorldPoint> walkTiles = collectWalkTiles(path);
        if (walkTiles.isEmpty()) return null;

        int playerIdx = closestIndex(walkTiles, player);
        // Available forward tiles after the player's projected index.
        // walkTiles[playerIdx] is "where the player is on the path"; we
        // want strictly forward of it.
        int forwardCount = walkTiles.size() - playerIdx - 1;
        if (forwardCount <= 0) return null;

        if (!variableDistance)
        {
            // Short-bucket-only path: always pick the nearest valid forward
            // tile. The filter still applies — entity contamination rules
            // are independent of the bucket choice.
            return pickInBucket(walkTiles, playerIdx, 0, filter, rng);
        }

        // Try the buckets in weighted-random order, then fall through
        // to any forward tile if every bucket is empty post-filter.
        int[] bucketOrder = pickBucketOrder(rng);
        for (int b : bucketOrder)
        {
            WorldPoint pick = pickInBucket(walkTiles, playerIdx, b, filter, rng);
            if (pick != null) return pick;
        }
        return null;
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
