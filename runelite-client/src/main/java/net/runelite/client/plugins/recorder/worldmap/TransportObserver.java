package net.runelite.client.plugins.recorder.worldmap;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuEntry;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.recorder.trail.TrailRecorder;

/** Passive capture of transport edges (stairs, ladders, gates, doors,
 *  climb actions, pay-toll, etc.) into {@link TransportIndex} via the
 *  RuneLite event bus. Runs in parallel to {@link TrailRecorder} —
 *  consumes the same {@link MenuOptionClicked} event but does not
 *  modify the recorder.
 *
 *  <p>Two-phase lifecycle per the spec:
 *  <ol>
 *    <li><b>Pending</b> (on {@link MenuOptionClicked}): record fromTile,
 *        verb, object metadata, click timestamp. The {@link #pending}
 *        map holds entries keyed by an internal id so multiple in-flight
 *        clicks coexist.</li>
 *    <li><b>Resolution</b> (on {@link GameTick}, Task 2.3): observe
 *        position / plane change; build a complete {@link
 *        TransportEdge} and publish to the index. Entries that don't
 *        resolve within {@link #resolutionTimeoutTicks} are discarded —
 *        half-formed edges are never persisted.</li>
 *  </ol>
 *
 *  <p>This file lands the pending phase only. Resolution arrives in
 *  Task 2.3; until then the {@link #pending} map fills up but nothing
 *  is published to the index. */
@Slf4j
public final class TransportObserver
{
    /** Default ticks an entry waits in {@link #pending} before being
     *  discarded. ~20 ticks ≈ 12s wall clock — long enough that a
     *  click on a transport ten tiles away (~6s walk) plus the
     *  arrival + settle + transport effect (3+ ticks) all fit, short
     *  enough that a missed resolution does not contaminate a later
     *  click. */
    public static final long DEFAULT_RESOLUTION_TIMEOUT_TICKS = 20;

    private final Client client;
    private final TransportIndex index;
    private final long resolutionTimeoutTicks;

    /** Pending clicks keyed by an internal monotonic id, so clicking
     *  the same transport twice in quick succession does not overwrite
     *  the first capture. */
    private final Map<Long, Pending> pending = new ConcurrentHashMap<>();
    private final AtomicLong nextPendingId = new AtomicLong(1);

    /** Tick counter — a coarse clock used to expire pending entries. */
    private long tickCount;

    /** Player tile observed on the previous tick. The transition
     *  {@code lastSeenPlayerTile → currentTile} is what resolves a
     *  pending — when the player leaves the {@link Pending#fromTile}
     *  on which they clicked, we record the destination as the
     *  transport's {@code toTile}. */
    @Nullable private WorldPoint lastSeenPlayerTile;

    public TransportObserver(Client client, TransportIndex index)
    {
        this(client, index, DEFAULT_RESOLUTION_TIMEOUT_TICKS);
    }

    public TransportObserver(Client client, TransportIndex index, long resolutionTimeoutTicks)
    {
        if (index == null) throw new IllegalArgumentException("index null");
        this.client = client;
        this.index = index;
        this.resolutionTimeoutTicks = Math.max(1L, resolutionTimeoutTicks);
    }

    // ──────── event-bus subscriptions ────────

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked e)
    {
        if (client == null) return;
        if (client.getGameState() != GameState.LOGGED_IN) return;
        MenuEntry entry = e == null ? null : e.getMenuEntry();
        if (entry == null) return;
        String verb = entry.getOption();
        if (!TrailRecorder.isTransportVerb(verb)) return;
        // CC_OP* menu types are widget-button clicks (e.g. closing the
        // GE interface). param0/param1 are component ids, not scene
        // coords — same skip rule TrailRecorder applies.
        if (entry.getType() != null && entry.getType().toString().startsWith("CC_OP")) return;

        Player local = client.getLocalPlayer();
        if (local == null) return;
        WorldPoint fromTile = local.getWorldLocation();
        if (fromTile == null) return;

        // Resolve the click target's actual world tile from scene
        // coords. Without this we'd treat the player's location at
        // click time as the transport's fromTile, but the engine
        // routes the player to the click target first — the captured
        // edge would point at wherever the player was standing instead
        // of the staircase / door / gate. Falls back to the player's
        // tile when the engine can't resolve scene coords (rare; same
        // fallback TrailRecorder uses).
        WorldPoint clickTargetTile = resolveClickTargetTile(entry, fromTile);

        capturePending(fromTile, clickTargetTile, verb,
            entry.getTarget() == null ? "" : entry.getTarget(),
            entry.getIdentifier(),
            entry.getParam0(),
            entry.getParam1(),
            entry.getType() == null ? "" : entry.getType().toString(),
            System.currentTimeMillis());
    }

    private WorldPoint resolveClickTargetTile(MenuEntry entry, WorldPoint fallback)
    {
        if (client == null) return fallback;
        try
        {
            WorldView wv = client.getTopLevelWorldView();
            if (wv == null) return fallback;
            LocalPoint lp = LocalPoint.fromScene(entry.getParam0(), entry.getParam1(), wv);
            WorldPoint wp = WorldPoint.fromLocal(client, lp);
            return wp == null ? fallback : wp;
        }
        catch (Throwable th)
        {
            log.debug("transport-obs: resolveClickTargetTile threw; using fallback", th);
            return fallback;
        }
    }

    @Subscribe
    public void onGameTick(GameTick e)
    {
        long now = System.currentTimeMillis();
        WorldPoint here = null;
        if (client != null)
        {
            Player local = client.getLocalPlayer();
            here = local == null ? null : local.getWorldLocation();
        }
        advanceTick(here, now);
    }

    /** Per-tick resolver. Drives the per-pending state machine
     *  (WAITING_TO_ARRIVE → ARRIVED → READY) and applies two
     *  resolution rules:
     *
     *  <ul>
     *    <li><b>Strong</b>: plane change or {@code Chebyshev > 1}
     *        between previous and current tile. Unambiguous transport
     *        (stairs, ladders, teleports). Resolves regardless of
     *        state.</li>
     *    <li><b>Weak</b>: 1-tile same-plane change while state is
     *        {@code READY}. Covers doors, gates, stiles — where the
     *        post-arrival walk-through tile move IS the transport.
     *        The state machine ensures we don't resolve on the
     *        engine-routed walk to the click target itself.</li>
     *  </ul>
     */
    void advanceTick(@Nullable WorldPoint here, long timestampMs)
    {
        tickCount++;
        if (here != null)
        {
            WorldPoint prev = lastSeenPlayerTile;
            boolean strong = prev != null && (
                prev.getPlane() != here.getPlane()
                || chebyshev(prev, here) > 1);
            boolean tileChanged = prev != null && !prev.equals(here);

            Iterator<Map.Entry<Long, Pending>> it = pending.entrySet().iterator();
            while (it.hasNext())
            {
                Pending p = it.next().getValue();
                // Strong rule (plane change / teleport) only fires once
                // the pending has reached its click target. Without
                // this gate, a teleport from elsewhere — or, in
                // back-to-back stair runs, a fresh pending captured
                // while the observer is still seeing the previous
                // run's terminal tile — would consume the strong
                // signal even though the player hasn't yet walked to
                // the new click target.
                boolean stateAdvanced = (p.state == PendingState.ARRIVED
                    || p.state == PendingState.READY);
                boolean strongResolve = strong && stateAdvanced;
                boolean weakResolve = (p.state == PendingState.READY) && tileChanged;
                if (strongResolve || weakResolve)
                {
                    publishResolved(p, prev, here, timestampMs,
                        strongResolve ? "strong" : "weak");
                    it.remove();
                    continue;
                }
                advancePendingState(p, here);
            }
            lastSeenPlayerTile = here;
        }
        expireStalePendings();
    }

    private void advancePendingState(Pending p, WorldPoint here)
    {
        switch (p.state)
        {
            case WAITING_TO_ARRIVE:
                if (isAtClickTarget(here, p)) p.state = PendingState.ARRIVED;
                break;
            case ARRIVED:
                // One settle tick after arrival before we accept weak
                // resolutions — keeps the engine-routed walk to the
                // click target from being misread as the transport.
                p.state = PendingState.READY;
                break;
            case READY:
                // Stays READY until a tile change resolves it (or TTL
                // expires).
                break;
        }
    }

    private static boolean isAtClickTarget(WorldPoint here, Pending p)
    {
        if (p.clickTargetTile == null) return true;
        if (here.getPlane() != p.clickTargetTile.getPlane()) return false;
        return chebyshev(here, p.clickTargetTile) <= 1;
    }

    private static int chebyshev(WorldPoint a, WorldPoint b)
    {
        return Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getY() - b.getY()));
    }

    private void publishResolved(Pending p, WorldPoint prevTile, WorldPoint curTile,
                                 long nowMs, String rule)
    {
        long durationMs = Math.max(0L, nowMs - p.clickTimeMs);
        // fromTile is the player's tile JUST BEFORE the transport
        // effect — for stairs / ladders that's the staircase tile
        // itself; for doors / gates that's the approach tile. This is
        // exactly what the planner wants: "to use this transport, be
        // standing here."
        WorldPoint resolvedFrom = prevTile != null ? prevTile : p.fromTile;
        TransportEdge edge = new TransportEdge(
            resolvedFrom, curTile,
            p.objectId,
            p.target == null ? "" : p.target,
            p.verb,
            p.param0, p.param1,
            p.targetKind == null ? "" : p.targetKind,
            resolvedFrom, // approachTile == fromTile for round 1
            resolvedFrom.getRegionID(),
            1, nowMs, durationMs);
        index.add(edge);
        log.info("transport-obs: resolved {} at {} → {} (plane Δ={}) in {}ms via {} rule",
            p.verb, resolvedFrom, curTile,
            curTile.getPlane() - resolvedFrom.getPlane(), durationMs, rule);
    }

    // ──────── pending capture (test-friendly entry point) ────────

    /** Mirrors what {@link #onMenuOptionClicked} does after unpacking
     *  {@link MenuEntry}. Tests drive this directly to exercise the
     *  pending-phase contract without standing up a Client +
     *  MenuEntry mock chain. The {@code clickTargetTile} parameter
     *  carries the world tile resolved from the menu's scene coords
     *  — production passes it from {@link #resolveClickTargetTile};
     *  tests pass it directly. {@code null} disables the
     *  arrival-gating heuristic (every weak transition resolves) and
     *  is intended for tests that don't care about the state machine. */
    void capturePending(WorldPoint fromTile, @Nullable WorldPoint clickTargetTile,
                        String verb, String target,
                        int objectId, int param0, int param1,
                        String targetKind, long timestampMs)
    {
        if (fromTile == null || verb == null || verb.isBlank()) return;
        if (!TrailRecorder.isTransportVerb(verb)) return;
        long id = nextPendingId.getAndIncrement();
        pending.put(id, new Pending(id, fromTile, clickTargetTile, verb, target, objectId,
            param0, param1, targetKind, timestampMs, tickCount));
        log.debug("transport-obs: pending #{} {} at {} target={} obj={}",
            id, verb, fromTile, clickTargetTile, objectId);
    }

    /** Pre-Phase-2.6 overload kept so existing tests that don't pass
     *  a click-target tile keep working. The state machine treats a
     *  null clickTargetTile as "always at target" so weak resolution
     *  fires on the first tile change after one settle tick. */
    void capturePending(WorldPoint fromTile, String verb, String target,
                        int objectId, int param0, int param1,
                        String targetKind, long timestampMs)
    {
        capturePending(fromTile, null, verb, target,
            objectId, param0, param1, targetKind, timestampMs);
    }

    private void expireStalePendings()
    {
        Iterator<Map.Entry<Long, Pending>> it = pending.entrySet().iterator();
        while (it.hasNext())
        {
            Pending p = it.next().getValue();
            long age = tickCount - p.clickTickCount;
            if (age > resolutionTimeoutTicks)
            {
                log.debug("transport-obs: discarding pending #{} {} at {} (age {} ticks)",
                    p.id, p.verb, p.fromTile, age);
                it.remove();
            }
        }
    }

    // ──────── inspection / test accessors ────────

    public int pendingCount()
    {
        return pending.size();
    }

    /** Test hook: drive a synthetic GameTick with no player position. */
    void tickForTest()
    {
        advanceTick(null, System.currentTimeMillis());
    }

    /** Test hook: drive a synthetic GameTick with a known player tile.
     *  Use to walk a fixture through capture → move → resolve. */
    void tickForTest(@Nullable WorldPoint here, long timestampMs)
    {
        advanceTick(here, timestampMs);
    }

    /** Lifecycle of a pending click. {@code WAITING_TO_ARRIVE} until
     *  the player reaches at-or-adjacent the click-target tile;
     *  {@code ARRIVED} for one settle tick; {@code READY} thereafter,
     *  at which point the next 1-tile same-plane change is treated as
     *  the transport's effect. Plane changes / multi-tile teleports
     *  bypass the state machine entirely (they are unambiguous). */
    enum PendingState { WAITING_TO_ARRIVE, ARRIVED, READY }

    static final class Pending
    {
        final long id;
        final WorldPoint fromTile;
        @Nullable final WorldPoint clickTargetTile;
        final String verb;
        @Nullable final String target;
        final int objectId;
        final int param0;
        final int param1;
        @Nullable final String targetKind;
        final long clickTimeMs;
        final long clickTickCount;
        PendingState state = PendingState.WAITING_TO_ARRIVE;

        Pending(long id, WorldPoint fromTile, @Nullable WorldPoint clickTargetTile,
                String verb, @Nullable String target,
                int objectId, int param0, int param1, @Nullable String targetKind,
                long clickTimeMs, long clickTickCount)
        {
            this.id = id;
            this.fromTile = fromTile;
            this.clickTargetTile = clickTargetTile;
            this.verb = verb;
            this.target = target;
            this.objectId = objectId;
            this.param0 = param0;
            this.param1 = param1;
            this.targetKind = targetKind;
            this.clickTimeMs = clickTimeMs;
            this.clickTickCount = clickTickCount;
        }
    }
}
