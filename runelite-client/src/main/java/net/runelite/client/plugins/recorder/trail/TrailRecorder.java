package net.runelite.client.plugins.recorder.trail;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Live capture of a {@link Trail}. Skeleton — Phase 2 fills in the event
 * bus subscription + session lifecycle. The static {@link #isTransportVerb}
 * method is package-stable so Phase 4 (graph) can reference it.
 */
public final class TrailRecorder
{
    /** The verbs that mark a region-transition click. Mirror of the spec. */
    public static final Set<String> TRANSPORT_VERBS = Set.of(
        "Open", "Close",
        "Climb-up", "Climb-down", "Climb", "Climb-over",
        "Cross", "Pass",
        "Squeeze-through", "Squeeze-past",
        "Jump",
        "Enter", "Exit",
        "Pay-toll");

    private static final Set<String> TRANSPORT_VERBS_LOWER = Set.copyOf(
        TRANSPORT_VERBS.stream().map(s -> s.toLowerCase()).toList());

    public static boolean isTransportVerb(String option)
    {
        if (option == null) return false;
        return TRANSPORT_VERBS_LOWER.contains(option.toLowerCase());
    }

    private final AtomicReference<Session> session = new AtomicReference<>(null);

    public boolean isRecording() { return session.get() != null; }

    public String currentName()
    {
        Session s = session.get();
        return s == null ? null : s.name;
    }

    static final class Session
    {
        final String name;
        final long startMs;
        Session(String name, long startMs) { this.name = name; this.startMs = startMs; }
    }
}
