package net.runelite.client.plugins.recorder.nav.v2;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/** Bounded ring of recent route choices. The {@link
 *  net.runelite.client.plugins.recorder.nav.v2.TopKRouter} consults
 *  this when ranking candidate routes — repeating the same route
 *  earns a multiplicative cost penalty so the planner alternates
 *  organically without falling into a perfect ABABAB pattern.
 *
 *  <p>Penalty schedule (per spec lines 286-296):
 *  <ul>
 *    <li>0 occurrences in window → 1.0 (no penalty)</li>
 *    <li>1 occurrence → {@link #MILD_PENALTY}</li>
 *    <li>2 occurrences → {@link #MEDIUM_PENALTY}</li>
 *    <li>3+ occurrences → {@link #STRONG_PENALTY}</li>
 *  </ul>
 *
 *  Window is bounded to {@link #WINDOW_SIZE} entries — old choices
 *  fall out so the bot can revisit a route once the alternates have
 *  had their turn. */
public final class RouteHistory
{
    public static final int WINDOW_SIZE = 5;
    public static final double MILD_PENALTY = 1.3;
    public static final double MEDIUM_PENALTY = 1.7;
    public static final double STRONG_PENALTY = 2.5;

    private final Deque<String> ring = new ArrayDeque<>(WINDOW_SIZE);

    /** Push {@code routeId} onto the history ring. Oldest entries roll
     *  out when the ring is full. Synchronized on {@code this} — the
     *  router calls record() from the worker thread; readers (tests,
     *  inspection dumps) may call from anywhere. */
    public synchronized void record(String routeId)
    {
        if (routeId == null) return;
        if (ring.size() >= WINDOW_SIZE) ring.removeFirst();
        ring.addLast(routeId);
    }

    /** Return the multiplier the top-K layer applies to the route's
     *  base cost when ranking candidates. {@code 1.0} for unseen
     *  routes, increasing with recent re-use. */
    public synchronized double penaltyFor(String routeId)
    {
        if (routeId == null) return 1.0;
        int count = 0;
        for (String r : ring) if (routeId.equals(r)) count++;
        if (count >= 3) return STRONG_PENALTY;
        if (count == 2) return MEDIUM_PENALTY;
        if (count == 1) return MILD_PENALTY;
        return 1.0;
    }

    /** Snapshot of the ring contents in insertion order. Used by the
     *  inspection dump for debugging route selection. */
    public synchronized List<String> recentEntries()
    {
        return Collections.unmodifiableList(new ArrayList<>(ring));
    }

    public synchronized int size() { return ring.size(); }
}
