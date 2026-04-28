package net.runelite.client.plugins.recorder.trail;

import java.util.Collections;
import java.util.List;

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
}
