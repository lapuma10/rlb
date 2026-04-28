package net.runelite.client.plugins.recorder.trail;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Live capture of a {@link Trail}: subscribes to the RuneLite event bus
 * (in production via {@link net.runelite.client.eventbus.EventBus}) and
 * records {@link TrailEvent.Tile} entries on each {@code GameTick} where
 * the player tile changed, plus {@link TrailEvent.Transport} entries on
 * each {@code MenuOptionClicked} whose verb is in the
 * {@link #TRANSPORT_VERBS} whitelist.
 *
 * <p>Wire-up happens in {@code RecorderPlugin#startUp()} via
 * {@code eventBus.register(trailRecorder)}. The recorder is dormant until
 * {@link #start(String)} is called from the side-panel button.
 *
 * <p>Static {@link #isTransportVerb(String)} kept package-public so other
 * components (graph builder, walker debug logging) can use the same rule
 * without a circular dependency.
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

    /** Recording session — null when idle. */
    private final AtomicReference<Session> session = new AtomicReference<>(null);

    /** Returns true if a recording session is active. */
    public boolean isRecording() { return session.get() != null; }

    /** The name the active recording will save under, or null if idle. */
    public String currentName()
    {
        Session s = session.get();
        return s == null ? null : s.name;
    }

    // ──────────────── public API ────────────────

    public synchronized void start(String name)
    {
        startAt(name, System.currentTimeMillis());
    }

    /** Test-friendly variant: pin the start instant. Production callers
     *  always go through {@link #start(String)}. */
    public synchronized void startAt(String name, long startMs)
    {
        if (name == null || name.isBlank())
            throw new IllegalArgumentException("trail name blank");
        if (session.get() != null)
            throw new IllegalStateException("already recording: "
                + session.get().name);
        session.set(new Session(name, startMs));
    }

    public synchronized Trail stopAndBuild()
    {
        Session s = session.get();
        if (s == null) throw new IllegalStateException("not recording");
        Trail t = new Trail(s.name, s.startMs, java.util.List.copyOf(s.events));
        session.set(null);
        return t;
    }

    public synchronized void cancel()
    {
        session.set(null);
    }

    // ──────── tile capture (called by event-bus subscriber) ────────

    /** Append a {@link TrailEvent.Tile} if recording and the tile differs
     *  from the previous one. {@code msSinceStart} is the ms offset from
     *  the recording start; pass 0 for the first tile.
     *
     *  <p>Thread-safety: synchronized on this recorder. The plugin's event
     *  subscribers fire on the client thread, so concurrency is rare —
     *  this is belt + suspenders. */
    public synchronized void recordTile(long msSinceStart, net.runelite.api.coords.WorldPoint tile)
    {
        Session s = session.get();
        if (s == null || tile == null) return;
        TrailEvent last = s.events.isEmpty() ? null : s.events.get(s.events.size() - 1);
        if (last instanceof TrailEvent.Tile lt && lt.tile().equals(tile)) return;
        s.events.add(new TrailEvent.Tile(msSinceStart, tile));
    }

    /** Wall-clock convenience: caller passes the absolute epoch ms; the
     *  recorder converts to {@code msSinceStart}. Used by the live
     *  {@code @Subscribe} handler. */
    public synchronized void recordTileAtAbsoluteMs(long absoluteMs, net.runelite.api.coords.WorldPoint tile)
    {
        Session s = session.get();
        if (s == null) return;
        recordTile(absoluteMs - s.startMs, tile);
    }

    // ──────── transport capture (called by event-bus subscriber) ────────

    /** Append a {@link TrailEvent.Transport} unconditionally. The caller
     *  has already done verb filtering. */
    public synchronized void recordTransport(long msSinceStart,
        net.runelite.api.coords.WorldPoint tile,
        String option, String target, int targetId, String targetKind,
        int actionId, int param0, int param1, java.util.List<String> menuRowsAtClick)
    {
        Session s = session.get();
        if (s == null) return;
        s.events.add(new TrailEvent.Transport(msSinceStart, tile,
            option == null ? "" : option,
            target == null ? "" : target,
            targetId, targetKind == null ? "" : targetKind,
            actionId, param0, param1,
            menuRowsAtClick == null ? java.util.List.of()
                : java.util.List.copyOf(menuRowsAtClick)));
    }

    /** Append a transport iff the verb is in the whitelist. Returns true
     *  if it was kept, false if filtered. */
    public synchronized boolean recordTransportIfWhitelisted(long msSinceStart,
        net.runelite.api.coords.WorldPoint tile,
        String option, String target, int targetId, String targetKind,
        int actionId, int param0, int param1, java.util.List<String> menuRowsAtClick)
    {
        if (!isTransportVerb(option)) return false;
        recordTransport(msSinceStart, tile, option, target, targetId, targetKind,
            actionId, param0, param1, menuRowsAtClick);
        return true;
    }

    public synchronized boolean recordTransportAtAbsoluteMsIfWhitelisted(long absoluteMs,
        net.runelite.api.coords.WorldPoint tile,
        String option, String target, int targetId, String targetKind,
        int actionId, int param0, int param1, java.util.List<String> menuRowsAtClick)
    {
        Session s = session.get();
        if (s == null) return false;
        return recordTransportIfWhitelisted(absoluteMs - s.startMs, tile,
            option, target, targetId, targetKind, actionId, param0, param1,
            menuRowsAtClick);
    }

    static final class Session
    {
        final String name;
        final long startMs;
        final java.util.List<TrailEvent> events = new java.util.ArrayList<>();
        Session(String name, long startMs) { this.name = name; this.startMs = startMs; }
    }
}
