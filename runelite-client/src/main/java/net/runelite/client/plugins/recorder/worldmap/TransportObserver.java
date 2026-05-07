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
     *  discarded. ~10 ticks ≈ 6s wall clock — long enough for stair
     *  / ladder animations to settle, short enough that a missed
     *  resolution does not contaminate a later click. */
    public static final long DEFAULT_RESOLUTION_TIMEOUT_TICKS = 10;

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

        capturePending(fromTile, verb,
            entry.getTarget() == null ? "" : entry.getTarget(),
            entry.getIdentifier(),
            entry.getParam0(),
            entry.getParam1(),
            entry.getType() == null ? "" : entry.getType().toString(),
            System.currentTimeMillis());
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

    /** Per-tick resolver. Compares the new player tile with the one
     *  observed on the previous tick; any pending whose {@code
     *  fromTile} matches the previous tile counts as resolved and is
     *  flushed to the index as a complete {@link TransportEdge}. */
    void advanceTick(@Nullable WorldPoint here, long timestampMs)
    {
        tickCount++;
        if (here != null)
        {
            if (lastSeenPlayerTile != null && !lastSeenPlayerTile.equals(here))
            {
                resolveTransitionFrom(lastSeenPlayerTile, here, timestampMs);
            }
            lastSeenPlayerTile = here;
        }
        expireStalePendings();
    }

    private void resolveTransitionFrom(WorldPoint prevTile, WorldPoint curTile, long nowMs)
    {
        Iterator<Map.Entry<Long, Pending>> it = pending.entrySet().iterator();
        while (it.hasNext())
        {
            Pending p = it.next().getValue();
            if (!p.fromTile.equals(prevTile)) continue;

            long durationMs = Math.max(0L, nowMs - p.clickTimeMs);
            TransportEdge edge = new TransportEdge(
                p.fromTile, curTile,
                p.objectId,
                p.target == null ? "" : p.target,
                p.verb,
                p.param0, p.param1,
                p.targetKind == null ? "" : p.targetKind,
                p.fromTile, // approachTile == fromTile for round 1
                p.fromTile.getRegionID(),
                1, nowMs, durationMs);
            index.add(edge);
            it.remove();
            log.info("transport-obs: resolved {} at {} → {} (plane Δ={}) in {}ms",
                p.verb, p.fromTile, curTile,
                curTile.getPlane() - p.fromTile.getPlane(), durationMs);
        }
    }

    // ──────── pending capture (test-friendly entry point) ────────

    /** Mirrors what {@link #onMenuOptionClicked} does after unpacking
     *  {@link MenuEntry}. Tests drive this directly to exercise the
     *  pending-phase contract without standing up a Client +
     *  MenuEntry mock chain. */
    void capturePending(WorldPoint fromTile, String verb, String target,
                        int objectId, int param0, int param1,
                        String targetKind, long timestampMs)
    {
        if (fromTile == null || verb == null || verb.isBlank()) return;
        if (!TrailRecorder.isTransportVerb(verb)) return;
        long id = nextPendingId.getAndIncrement();
        pending.put(id, new Pending(id, fromTile, verb, target, objectId,
            param0, param1, targetKind, timestampMs, tickCount));
        log.debug("transport-obs: pending #{} {} at {} obj={}", id, verb, fromTile, objectId);
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

    static final class Pending
    {
        final long id;
        final WorldPoint fromTile;
        final String verb;
        @Nullable final String target;
        final int objectId;
        final int param0;
        final int param1;
        @Nullable final String targetKind;
        final long clickTimeMs;
        final long clickTickCount;

        Pending(long id, WorldPoint fromTile, String verb, @Nullable String target,
                int objectId, int param0, int param1, @Nullable String targetKind,
                long clickTimeMs, long clickTickCount)
        {
            this.id = id;
            this.fromTile = fromTile;
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
