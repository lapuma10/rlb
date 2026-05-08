package net.runelite.client.plugins.recorder.nav.v2;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.recorder.transport.TransportResolver;
import net.runelite.client.plugins.recorder.worldmap.MapStore;
import net.runelite.client.plugins.recorder.worldmap.RegionChunkSnapshot;
import net.runelite.client.plugins.recorder.worldmap.RegionIds;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import net.runelite.client.sequence.internal.ActionRequest;

/** Production binding for {@link V2Executor.Env}. Wraps live RuneLite
 *  state and the dispatcher so the executor can read game state and
 *  enqueue clicks without knowing about the threading model.
 *
 *  <p>Reads ({@link #playerLoc}, {@link #isPlausiblyClean},
 *  {@link #canMinimapClick}, {@link #snapshotSaysWalkable},
 *  {@link #liveCollisionAllows}, {@link #dynamicEntityOnTile}) marshal
 *  to the client thread via {@link ClientThread#invokeLater} and block
 *  the calling worker until the result is back. RuneLite asserts on
 *  the client thread for {@link net.runelite.api.Scene} /
 *  {@link WorldView} reads.
 *
 *  <p>Writes ({@link #dispatchWalk}, {@link #dispatchMinimap}) hand
 *  the request off to the dispatcher's worker thread. They return
 *  promptly; the caller polls {@link #dispatcherBusy} to know when
 *  the chain has finished. Neither one is itself a blocking flow. */
@Slf4j
public final class V2ExecutorEnv implements V2Executor.Env
{
    private final Client client;
    private final ClientThread clientThread;
    private final HumanizedInputDispatcher dispatcher;
    private final EmptyTileFilter emptyTileFilter;
    private final MinimapClicker minimapClicker;
    private final MapStore mapStore;
    private final TransportResolver transportResolver;

    public V2ExecutorEnv(Client client, ClientThread clientThread,
                         HumanizedInputDispatcher dispatcher,
                         EmptyTileFilter emptyTileFilter,
                         MinimapClicker minimapClicker,
                         MapStore mapStore)
    {
        this.client = client;
        this.clientThread = clientThread;
        this.dispatcher = dispatcher;
        this.emptyTileFilter = emptyTileFilter;
        this.minimapClicker = minimapClicker;
        this.mapStore = mapStore;
        this.transportResolver = new TransportResolver(client);
    }

    @Override
    @Nullable
    public WorldPoint playerLoc()
    {
        return onClient(() -> {
            Player self = client.getLocalPlayer();
            return self == null ? null : self.getWorldLocation();
        });
    }

    @Override
    public boolean isPlausiblyClean(WorldPoint tile)
    {
        return Boolean.TRUE.equals(onClient(() -> emptyTileFilter.isPlausiblyClean(tile)));
    }

    @Override
    public boolean canMinimapClick(WorldPoint tile)
    {
        return Boolean.TRUE.equals(onClient(() -> minimapClicker.canClick(tile)));
    }

    @Override
    public boolean dispatchWalk(WorldPoint tile)
    {
        if (dispatcher.isBusy()) return false;
        ActionRequest req = ActionRequest.builder()
            .kind(ActionRequest.Kind.WALK)
            .channel(ActionRequest.Channel.MOUSE)
            .tile(tile)
            .strictWalk(true)
            .build();
        dispatcher.dispatch(req);
        return true;
    }

    @Override
    public boolean dispatchMinimap(WorldPoint tile)
    {
        if (dispatcher.isBusy()) return false;
        // MinimapClicker does the resolve on the client thread before
        // enqueuing CLICK_BOUNDS; marshal accordingly.
        return Boolean.TRUE.equals(onClient(() -> minimapClicker.dispatch(tile)));
    }

    @Override
    public boolean dispatcherBusy()
    {
        return dispatcher.isBusy();
    }

    @Override
    @Nullable
    public String lastDispatchError()
    {
        // dispatcher.lastErrorMessage() is read-and-clear by design.
        // Calling it here every tick after the dispatcher settles
        // means we either consume the message (and act on it) or it
        // stays null until the next failed dispatch.
        return dispatcher.lastErrorMessage();
    }

    @Override
    public long nowMs()
    {
        return System.currentTimeMillis();
    }

    @Override
    public boolean snapshotSaysWalkable(WorldPoint tile)
    {
        if (tile == null) return false;
        int regionId = RegionIds.regionIdFor(tile.getX(), tile.getY());
        RegionChunkSnapshot snap = mapStore.snapshotFor(regionId);
        if (snap == null) return false;   // unknown chunk → conservative
        return snap.isStandableLocal(tile.getX(), tile.getY(), tile.getPlane());
    }

    @Override
    public boolean liveCollisionAllows(WorldPoint tile)
    {
        if (tile == null) return false;
        return Boolean.TRUE.equals(onClient(() -> {
            WorldView wv = client.getTopLevelWorldView();
            if (wv == null) return false;
            CollisionData[] maps = wv.getCollisionMaps();
            if (maps == null) return false;
            int plane = tile.getPlane();
            if (plane < 0 || plane >= maps.length) return false;
            CollisionData cd = maps[plane];
            if (cd == null) return false;
            int[][] flags = cd.getFlags();
            if (flags == null) return false;
            int sx = tile.getX() - wv.getBaseX();
            int sy = tile.getY() - wv.getBaseY();
            if (sx < 0 || sy < 0 || sx >= flags.length || sy >= flags[sx].length) return false;
            return (flags[sx][sy] & net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_FULL) == 0;
        }));
    }

    /** Search the recorded {@code fromTile} first, then the 8-neighbor
     *  ring (clockwise from north), for a live object whose menu actions
     *  contain {@code verb}. Returns the matched tile, or null if no
     *  candidate carries the verb. Marshalled to the client thread —
     *  scene reads asserted under -ea otherwise. */
    @Override
    @Nullable
    public WorldPoint resolveTransportClickTile(int objectId, String verb,
                                                WorldPoint fromTile)
    {
        if (fromTile == null || verb == null || verb.isBlank()) return null;
        return onClient(() -> {
            // 1) Direct hit on fromTile — the standard staircase/ladder case.
            TransportResolver.Match m = transportResolver.findTransport(fromTile, verb);
            if (m != null && m.isSuccess()) return fromTile;
            // 2) 8-neighbor ring — covers WallObject doors / gates whose
            //    object tile is adjacent to the player's standing tile.
            int[][] dirs = { {0,1}, {1,0}, {0,-1}, {-1,0},
                             {1,1}, {1,-1}, {-1,1}, {-1,-1} };
            for (int[] d : dirs)
            {
                WorldPoint near = new WorldPoint(
                    fromTile.getX() + d[0], fromTile.getY() + d[1], fromTile.getPlane());
                TransportResolver.Match nm = transportResolver.findTransport(near, verb);
                if (nm != null && nm.isSuccess()) return near;
            }
            log.debug("v2-executor-env: resolveTransportClickTile MISS verb='{}' objectId={} fromTile={}",
                verb, objectId, fromTile);
            return null;
        });
    }

    @Override
    public boolean dispatchTransport(WorldPoint clickTile, String verb)
    {
        if (clickTile == null || verb == null || verb.isBlank()) return false;
        if (dispatcher.isBusy()) return false;
        ActionRequest req = ActionRequest.builder()
            .kind(ActionRequest.Kind.CLICK_GAME_OBJECT)
            .channel(ActionRequest.Channel.MOUSE)
            .tile(clickTile)
            .verb(verb)
            .build();
        dispatcher.dispatch(req);
        return true;
    }

    @Override
    public boolean dynamicEntityOnTile(WorldPoint tile)
    {
        if (tile == null) return false;
        return Boolean.TRUE.equals(onClient(() -> {
            WorldView wv = client.getTopLevelWorldView();
            if (wv == null) return false;
            try
            {
                for (NPC npc : wv.npcs())
                {
                    if (npc == null) continue;
                    WorldPoint loc = npc.getWorldLocation();
                    if (loc != null && loc.equals(tile)) return true;
                }
            }
            catch (Throwable th)
            {
                log.debug("dynamicEntityOnTile: npc iteration threw", th);
            }
            return false;
        }));
    }

    /** Synchronous client-thread read with a bounded wait. The caller
     *  (V2Executor) tolerates a null return as "try again next tick",
     *  so a missed deadline collapses to a one-tick no-op rather than
     *  hanging the worker. Bound matches HumanizedInputDispatcher's
     *  own onClient timeout (2× one tick) — generous enough to absorb
     *  scene-load contention without becoming a deadlock vector during
     *  shutdown. */
    private static final long ONCLIENT_TIMEOUT_MS = 1500;

    @Nullable
    private <T> T onClient(Supplier<T> sup)
    {
        // Fast path: if the caller is already on the client thread, run
        // the supplier directly. Without this guard, a future caller
        // that lands on the client thread (e.g. a Sequence engine
        // Step.check() or a legacy event handler) would self-deadlock
        // for ONCLIENT_TIMEOUT_MS per call and silently return null —
        // exactly the silent-freeze class CLAUDE.md flags. Production
        // callers (V2Executor on a worker) take the slow path as before.
        if (client.isClientThread())
        {
            try { return sup.get(); }
            catch (Throwable th)
            {
                log.warn("v2-executor-env: onClient (inline) threw", th);
                return null;
            }
        }
        AtomicReference<T> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        clientThread.invokeLater(() -> {
            try { ref.set(sup.get()); }
            catch (Throwable th) { log.warn("v2-executor-env: onClient threw", th); }
            finally { latch.countDown(); }
        });
        try
        {
            if (!latch.await(ONCLIENT_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS))
            {
                log.warn("v2-executor-env: onClient timed out after {} ms — returning null",
                    ONCLIENT_TIMEOUT_MS);
                return null;
            }
        }
        catch (InterruptedException ie)
        {
            Thread.currentThread().interrupt();
            return null;
        }
        return ref.get();
    }
}
