package net.runelite.client.plugins.recorder.trail;

import java.util.Collections;
import java.util.List;

/** A single recorded trail. Immutable: name, recording timestamp (epoch
 *  millis), and an unmodifiable list of {@link TrailEvent}s ordered by
 *  {@link TrailEvent#msSinceStart()} ascending. */
public final class Trail
{
    private final String name;
    private final long recordedAt;
    private final List<TrailEvent> events;

    public Trail(String name, long recordedAt, List<TrailEvent> events)
    {
        if (name == null || name.isBlank())
            throw new IllegalArgumentException("Trail name blank");
        if (events == null) throw new IllegalArgumentException("events null");
        this.name = name;
        this.recordedAt = recordedAt;
        this.events = Collections.unmodifiableList(List.copyOf(events));
    }

    public String name() { return name; }
    public long recordedAt() { return recordedAt; }
    public List<TrailEvent> events() { return events; }
}
