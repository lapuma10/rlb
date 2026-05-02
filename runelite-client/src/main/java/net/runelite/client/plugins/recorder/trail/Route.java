package net.runelite.client.plugins.recorder.trail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import javax.annotation.Nullable;

/** A weighted set of equivalent trails for the same logical trip
 *  (e.g. "bank → cook"). The walker calls {@link #pickWeightedRandom}
 *  once per trip and walks the chosen trail; each entry carries a
 *  weight so callers can prefer one route most of the time and take
 *  the alternate occasionally.
 *
 *  <p>Immutable. Builder validates everything at build time so a
 *  malformed route never reaches {@code walker.walkRoute}.
 *
 *  <p>The walker tracks the previously-picked trail externally and
 *  passes it to {@link #pickWeightedRandom} when {@link #noRepeat()}
 *  is true; the route itself stays stateless. */
public final class Route
{
    /** Per-trail entry. {@code weight} is a positive integer; the
     *  picker uses cumulative-sum + {@code nextInt(total)}. Relative
     *  scale is what matters — {@code (77, 23)} and {@code (770, 230)}
     *  produce identical distributions. */
    public static final class Entry
    {
        private final Trail trail;
        private final int weight;
        Entry(Trail trail, int weight) { this.trail = trail; this.weight = weight; }
        public Trail trail() { return trail; }
        public int weight() { return weight; }
    }

    private final List<Entry> entries;
    private final int corridorRadius;
    private final boolean noRepeat;

    private Route(List<Entry> entries, int corridorRadius, boolean noRepeat)
    {
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
        this.corridorRadius = corridorRadius;
        this.noRepeat = noRepeat;
    }

    public List<Entry> entries() { return entries; }
    public int corridorRadius() { return corridorRadius; }
    public boolean noRepeat() { return noRepeat; }

    /** Pick one trail using cumulative-weight sampling. When
     *  {@link #noRepeat()} is true and {@code previous} matches one
     *  of the entries, exclude it from the pool — unless excluding
     *  it would leave the pool empty (single-trail Route), in which
     *  case return {@code previous}. */
    public Trail pickWeightedRandom(@Nullable Trail previous, Random rng)
    {
        if (entries.size() == 1) return entries.get(0).trail;

        int total = 0;
        for (Entry e : entries)
        {
            if (noRepeat && previous != null && e.trail == previous) continue;
            total += e.weight;
        }
        // Pool collapsed to nothing — should be unreachable since we
        // handled size==1 above and Builder requires >=1 entry, but
        // defend against future changes that allow zero-weight entries.
        if (total <= 0) return entries.get(0).trail;

        int r = rng.nextInt(total);
        int acc = 0;
        for (Entry e : entries)
        {
            if (noRepeat && previous != null && e.trail == previous) continue;
            acc += e.weight;
            if (r < acc) return e.trail;
        }
        // Cumulative sum exhausted without a hit — only possible via
        // RNG edge cases on a frozen rng. Fall back to last eligible.
        for (int i = entries.size() - 1; i >= 0; i--)
        {
            Entry e = entries.get(i);
            if (!(noRepeat && previous != null && e.trail == previous)) return e.trail;
        }
        return entries.get(0).trail;
    }

    public static Builder builder() { return new Builder(); }

    /** Convenience: build a Route from every trail in {@code candidates}
     *  whose {@link Trail#name() name} starts with {@code prefix}, each
     *  with weight 1. Iteration order is preserved.
     *
     *  <p>Throws {@link IllegalArgumentException} when zero trails
     *  match — silently building an empty Route would produce a
     *  walker that immediately fails with a confusing "Route must
     *  have at least one trail" deep in script-time. */
    public static Route fromTrails(Iterable<Trail> candidates, String prefix)
    {
        if (candidates == null) throw new IllegalArgumentException("candidates null");
        if (prefix == null || prefix.isEmpty())
            throw new IllegalArgumentException("prefix empty");
        Builder b = builder();
        int matched = 0;
        for (Trail t : candidates)
        {
            if (t != null && t.name() != null && t.name().startsWith(prefix))
            {
                b.trail(t, 1);
                matched++;
            }
        }
        if (matched == 0)
        {
            throw new IllegalArgumentException(
                "No trails match prefix '" + prefix + "'");
        }
        return b.build();
    }

    /** Production convenience that pulls candidates from a live
     *  {@link TrailRegistry}. Equivalent to
     *  {@code fromTrails(registry.all(), prefix)}. */
    public static Route fromRegistry(TrailRegistry registry, String prefix)
    {
        if (registry == null) throw new IllegalArgumentException("registry null");
        return fromTrails(registry.all(), prefix);
    }

    public static final class Builder
    {
        private final List<Entry> entries = new ArrayList<>();
        private int corridorRadius = 1;
        private boolean noRepeat = false;

        private Builder() {}

        public Builder trail(Trail trail, int weight)
        {
            if (trail == null) throw new IllegalArgumentException("trail null");
            if (weight <= 0)
                throw new IllegalArgumentException("weight must be > 0, got " + weight);
            entries.add(new Entry(trail, weight));
            return this;
        }

        /** Add a trail with weight 1 (equal-weight shortcut). */
        public Builder trail(Trail trail) { return trail(trail, 1); }

        /** Corridor half-width in tiles; clamped to {@code [0, 3]}.
         *  0 disables corridor walking for this Route (walker still
         *  picks centerline tiles). Default 1. */
        public Builder corridorRadius(int radius)
        {
            this.corridorRadius = Math.max(0, Math.min(3, radius));
            return this;
        }

        public Builder noRepeat(boolean noRepeat)
        {
            this.noRepeat = noRepeat;
            return this;
        }

        public Route build()
        {
            if (entries.isEmpty())
                throw new IllegalArgumentException("Route must have at least one trail");
            return new Route(entries, corridorRadius, noRepeat);
        }
    }
}
