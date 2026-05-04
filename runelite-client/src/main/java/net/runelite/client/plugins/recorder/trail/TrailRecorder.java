package net.runelite.client.plugins.recorder.trail;

import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.eventbus.Subscribe;

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
@Slf4j
@RequiredArgsConstructor(onConstructor_ = {@javax.inject.Inject})
public final class TrailRecorder
{
    /** The verbs that mark a region-transition click. Mirror of the spec.
     *
     *  <p>"Bottom-floor" / "Top-floor" / "Ground-floor" are spiral-staircase
     *  shortcuts (e.g. Lumbridge castle south staircase) that teleport
     *  through the intermediate plane in one click. Without them in the
     *  whitelist, a user picking "Bottom-floor" instead of plain "Climb-down"
     *  silently records a plane jump with NO transport event and the walker
     *  has nothing to click — see lumby_bank_to_cook regression of 2026-05-03. */
    public static final Set<String> TRANSPORT_VERBS = Set.of(
        "Open", "Close",
        "Climb-up", "Climb-down", "Climb", "Climb-over",
        "Bottom-floor", "Top-floor", "Ground-floor",
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

    private final Client client;

    /** No-arg constructor for tests — client will be null. */
    public TrailRecorder() { this(null); }

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

    // ──────── event-bus subscriptions ────────

    @Subscribe
    public void onGameTick(GameTick e)
    {
        if (!isRecording()) return;
        if (client == null) return;
        var local = client.getLocalPlayer();
        if (local == null) return;
        var here = local.getWorldLocation();
        if (here == null) return;
        recordTileAtAbsoluteMs(System.currentTimeMillis(), here);
    }

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked e)
    {
        if (!isRecording()) return;
        if (client == null) return;
        MenuEntry entry = e.getMenuEntry();
        if (entry == null) return;
        String option = entry.getOption();
        if (!isTransportVerb(option)) return;
        // CC_OP / CC_OP_LOW_PRIORITY are widget-button clicks (e.g. closing
        // the GE interface). param0/param1 are component IDs, not scene coords,
        // so resolveClickTile produces garbage world points for them. Widget
        // interactions are never region transitions — skip entirely.
        if (entry.getType() != null && entry.getType().toString().startsWith("CC_OP")) return;
        // Resolve the click's world tile from param0/param1 (scene coords)
        // → world via the player's current scene base. Falls back to the
        // player's tile if the scene math fails (rare; means the click
        // wasn't on a tile-anchored object).
        var local = client.getLocalPlayer();
        if (local == null || local.getWorldLocation() == null) return;
        net.runelite.api.coords.WorldPoint tile = resolveClickTile(entry, local);

        // Snapshot the current menu rows so we can faithfully reproduce
        // the click context offline. The MenuOptionClicked event fires
        // BEFORE the engine consumes the click, so the menu is still in
        // memory. getMenuEntries() includes 'Cancel' at index 0.
        java.util.List<String> rows = new ArrayList<>();
        try
        {
            MenuEntry[] entries = client.getMenu().getMenuEntries();
            for (MenuEntry me : entries)
            {
                String opt = me.getOption() == null ? "" : me.getOption();
                String tgt = me.getTarget() == null ? "" : me.getTarget();
                rows.add((opt + " " + tgt).trim());
            }
        }
        catch (Throwable t) { log.debug("trail: failed to snapshot menu", t); }

        recordTransportAtAbsoluteMsIfWhitelisted(
            System.currentTimeMillis(),
            tile,
            option,
            entry.getTarget() == null ? "" : entry.getTarget(),
            entry.getIdentifier(),
            entry.getType() == null ? "" : entry.getType().toString(),
            entry.getType() == null ? 0 : entry.getType().getId(),
            entry.getParam0(),
            entry.getParam1(),
            rows);
    }

    private net.runelite.api.coords.WorldPoint resolveClickTile(
        MenuEntry entry, net.runelite.api.Player local)
    {
        try
        {
            // For game-object / wall / decorative menu entries the engine
            // packs scene coords into param0/param1. Translate to world via
            // the player's current scene base.
            int sx = entry.getParam0();
            int sy = entry.getParam1();
            net.runelite.api.coords.LocalPoint lp =
                net.runelite.api.coords.LocalPoint.fromScene(
                    sx, sy, client.getTopLevelWorldView());
            net.runelite.api.coords.WorldPoint wp =
                net.runelite.api.coords.WorldPoint.fromLocal(client, lp);
            if (wp != null) return wp;
        }
        catch (Throwable ignored) { /* fall through */ }
        return local.getWorldLocation();
    }

    static final class Session
    {
        final String name;
        final long startMs;
        final java.util.List<TrailEvent> events = new java.util.ArrayList<>();
        Session(String name, long startMs) { this.name = name; this.startMs = startMs; }
    }
}
