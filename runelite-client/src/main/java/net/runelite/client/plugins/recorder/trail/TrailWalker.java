package net.runelite.client.plugins.recorder.trail;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import net.runelite.client.sequence.internal.ActionRequest;

/** Drives a {@link TrailPath} to completion. Same shape as
 *  {@link net.runelite.client.plugins.recorder.walker.UniversalWalker}:
 *  outer thread calls {@link #tick(TrailPath)} every ~600 ms and watches
 *  the returned {@link Status}.
 *
 *  <p>Per tick:
 *  <ol>
 *    <li>Read the player's tile on the client thread.</li>
 *    <li>{@link #chooseLegIndex} — monotonic-forward leg pick.</li>
 *    <li>WALK leg → click a randomly-chosen tile from the "ahead" window
 *        (humanizes which tile we click on each pass).</li>
 *    <li>TRANSPORT leg → walk to the transport tile if not adjacent;
 *        once adjacent, dispatch CLICK_GAME_OBJECT with the recorded
 *        verb / objectId.</li>
 *    <li>If the player hasn't moved for {@link #STUCK_AFTER_MS}, return
 *        {@link Status#STUCK}.</li>
 *  </ol>
 */
@Slf4j
public final class TrailWalker
{
    public enum Status { IN_PROGRESS, ARRIVED, STUCK, ERROR }

    /** Re-issue a click only if the leg's pick changed OR this many ms
     *  have passed since the last click without movement. */
    public static final long RECLICK_AFTER_MS = 3_000;
    public static final long STILL_THRESHOLD_MS = 2_500;
    public static final long INTERACT_THROTTLE_MS = 3_000;
    public static final long STUCK_AFTER_MS = 15_000;
    public static final int TRANSPORT_ADJACENCY = 1;

    private final Client client;
    private final ClientThread clientThread;
    private final HumanizedInputDispatcher dispatcher;
    private final net.runelite.client.plugins.recorder.transport.TransportResolver transportResolver;

    private TrailPath currentPath;
    private int legIdx;
    private WorldPoint lastSeenPosition;
    private long lastMovementMs;
    private long lastClickMs;
    private long lastInteractMs;
    private int lastClickLegIdx = -1;
    private WorldPoint lastWalkPick;
    /** When the verb on a TRANSPORT leg can't be found on any object at the
     *  recorded tile (e.g. "Open" on a gate that's already open and now
     *  shows "Close"), we treat the transport as already done and advance
     *  past it. This counter records how many ticks we've waited for the
     *  verb to reappear before giving up; without a small grace window we
     *  could skip a transport whose object hasn't loaded into the scene yet. */
    private int transportVerbMissingTicks;

    public TrailWalker(Client client, ClientThread clientThread,
                       HumanizedInputDispatcher dispatcher)
    {
        this.client = client;
        this.clientThread = clientThread;
        this.dispatcher = dispatcher;
        this.transportResolver = client == null ? null
            : new net.runelite.client.plugins.recorder.transport.TransportResolver(client);
        reset();
    }

    public void reset()
    {
        currentPath = null;
        legIdx = 0;
        lastSeenPosition = null;
        lastMovementMs = System.currentTimeMillis();
        lastClickMs = 0;
        lastInteractMs = 0;
        lastClickLegIdx = -1;
        lastWalkPick = null;
        transportVerbMissingTicks = 0;
    }

    public int currentLegIndex() { return legIdx; }

    /** Pick the active leg index for the given player position. Pure
     *  function — exposed package-public for unit testing. */
    static int chooseLegIndex(TrailPath path, int minIdx, WorldPoint pos)
    {
        List<Leg> legs = path.legs();
        // Single-step monotonic advance: flip to leg i+1 if either
        //   (a) the player is already standing in leg i+1's tile-set, or
        //   (b) leg i is a WALK and the player has reached its final tile
        //       (otherwise the walker stalls at the end of a walk leg
        //       whose successor is a TRANSPORT — the transport's tile is
        //       not the same as the walk's last tile, so condition (a)
        //       never fires).
        int idx = minIdx;
        while (idx < legs.size() - 1)
        {
            if (legContainsTile(legs.get(idx + 1), pos))
            {
                idx++;
                continue;
            }
            Leg cur = legs.get(idx);
            if (cur instanceof Leg.Walk w
                && pos.equals(w.tiles().get(w.tiles().size() - 1)))
            {
                idx++;
                continue;
            }
            // Forward-scan: the player's tile may live more than one leg
            // ahead. Two cases this catches:
            //   - After a TRANSPORT: the engine teleported the player past
            //     a 1-tile post-stair WALK leg (the post-stair tile also
            //     belongs to the next WALK leg). Without this the walker
            //     sits on the transport leg whose action has already fired.
            //   - After an unexpected plane change mid-WALK leg (user
            //     manually climbed stairs, an external action moved us):
            //     the current leg's tiles are on a plane the player is no
            //     longer on, so the single-step checks above all miss. Hop
            //     to the first future leg the player can be located on.
            int hop = -1;
            for (int j = idx + 2; j < legs.size(); j++)
            {
                if (legContainsTile(legs.get(j), pos)) { hop = j; break; }
            }
            // Plane-based fallback: no future leg contains the exact
            // player tile, but the current leg has no tiles on the
            // player's plane — means the player drifted off the trail's
            // plane. Hop to the first future leg that has any tile on the
            // player's plane so the walker can resume on the right floor.
            if (hop < 0 && !legHasPlane(cur, pos.getPlane()))
            {
                for (int j = idx + 1; j < legs.size(); j++)
                {
                    if (legHasPlane(legs.get(j), pos.getPlane())) { hop = j; break; }
                }
            }
            if (hop > idx)
            {
                log.warn("trail-walker: forward-scan recovered — leg {} → {} "
                    + "(player at {}, current leg {})",
                    idx, hop, pos, cur.kind());
                idx = hop;
                continue;
            }
            break;
        }
        return idx;
    }

    private static boolean legContainsTile(Leg l, WorldPoint pos)
    {
        if (l instanceof Leg.Walk w)
        {
            for (WorldPoint t : w.tiles())
            {
                if (t.equals(pos)) return true;
            }
            return false;
        }
        if (l instanceof Leg.Transport t)
        {
            return t.tile().equals(pos);
        }
        return false;
    }

    private static boolean legHasPlane(Leg l, int plane)
    {
        if (l instanceof Leg.Walk w)
        {
            for (WorldPoint t : w.tiles())
            {
                if (t.getPlane() == plane) return true;
            }
            return false;
        }
        if (l instanceof Leg.Transport t)
        {
            return t.tile().getPlane() == plane;
        }
        return false;
    }

    /** Choose a tile inside {@code leg} that is "ahead" of the player.
     *  Ahead = later in {@code leg.tiles()} than the closest tile to the
     *  player. From those candidates, pick one uniformly at random — this
     *  is the click humanization that makes a real player's "I'll click
     *  somewhere over there" different on every pass.
     *
     *  <p>If the player isn't in the leg's tile list at all, we fall back
     *  to the farthest tile (== the leg's destination), matching the
     *  spec's "farthest tile in this leg that is still ahead of the
     *  player". */
    /** Maximum Chebyshev distance the engine's minimap walk-click reliably
     *  routes (default OSRS minimap zoom). Picks beyond this are out of
     *  range — the click would resolve to "Cancel". */
    static final int MAX_HOP_TILES = 16;

    static WorldPoint pickAheadTile(Leg.Walk leg, WorldPoint player, java.util.Random rng)
    {
        List<WorldPoint> tiles = leg.tiles();
        // Closest tile to the player on the same plane.
        int closestIdx = -1;
        int closestDist = Integer.MAX_VALUE;
        for (int i = 0; i < tiles.size(); i++)
        {
            WorldPoint t = tiles.get(i);
            if (t.getPlane() != player.getPlane()) continue;
            int dx = Math.abs(t.getX() - player.getX());
            int dy = Math.abs(t.getY() - player.getY());
            int d = Math.max(dx, dy);
            if (d < closestDist) { closestDist = d; closestIdx = i; }
        }
        if (closestIdx < 0) return tiles.get(tiles.size() - 1);
        int firstAhead = closestIdx + 1;
        if (firstAhead >= tiles.size()) return tiles.get(tiles.size() - 1);

        // Walk forward through the leg, tracking the FARTHEST tile in the
        // leg that is still within minimap walk range of the player. That
        // tile is our preferred click target — beyond it the engine drops
        // the click. If the leg's last tile is within range, we want to
        // be able to pick IT (which advances the walker past this leg).
        int farthestIdx = closestIdx;
        for (int i = firstAhead; i < tiles.size(); i++)
        {
            WorldPoint t = tiles.get(i);
            int dx = Math.abs(t.getX() - player.getX());
            int dy = Math.abs(t.getY() - player.getY());
            int d = Math.max(dx, dy);
            if (d > MAX_HOP_TILES) break;
            farthestIdx = i;
        }
        if (farthestIdx <= closestIdx)
        {
            // No tile ahead is within hop range — the leg is far away or
            // we're at the very end. Fall back to the leg's last tile.
            return tiles.get(tiles.size() - 1);
        }

        // Humanization: pick uniformly within the LAST HALF of the
        // in-range ahead window. This trades off:
        //   - consistent far hops (always ~half the window or more —
        //     for a typical 16-tile in-range window, every pick is at
        //     least 8-9 game tiles ahead, never 3-5) so the bot doesn't
        //     crawl when travelling
        //   - enough candidate tiles (8 for a 16-tile window) that
        //     consecutive cycles don't click the exact same tile,
        //     breaking the "always (3220,3219) → (3232,3219) → ..."
        //     pattern.
        // Earlier iterations had a "mid hop" branch picking from the
        // first half of the window — that produced the 3-5 tile hops
        // the user flagged. Real players always click far when
        // travelling; the only randomization that matters is WHICH
        // far tile, not whether to click far at all.
        int windowSize = farthestIdx - firstAhead + 1;
        int pickIdx;
        if (windowSize <= 3)
        {
            pickIdx = firstAhead + rng.nextInt(windowSize);
        }
        else
        {
            int halfSpan = Math.max(2, windowSize / 2);
            int halfStart = farthestIdx - halfSpan + 1;
            pickIdx = halfStart + rng.nextInt(halfSpan);
        }
        return tiles.get(pickIdx);
    }

    public Status tick(TrailPath path) throws InterruptedException
    {
        if (path == null || path.isEmpty()) return Status.ARRIVED;
        if (currentPath != path)
        {
            reset();
            currentPath = path;
        }
        if (legIdx >= path.size()) return Status.ARRIVED;

        WorldPoint pos = readPlayerTile();
        if (pos == null) return Status.IN_PROGRESS;

        long now = System.currentTimeMillis();
        if (lastSeenPosition == null || !lastSeenPosition.equals(pos))
        {
            lastSeenPosition = pos;
            lastMovementMs = now;
        }

        // Locate the active leg. Monotonic forward.
        int newIdx = chooseLegIndex(path, legIdx, pos);
        if (newIdx > legIdx)
        {
            log.info("trail-walker: advancing leg {} → {} (player at {})",
                legIdx, newIdx, pos);
            legIdx = newIdx;
            lastWalkPick = null;
            // Camera rotate to a tile inside the new leg so we don't walk
            // staring at the back of our head.
            WorldPoint focus = legFocusTile(path.legs().get(legIdx));
            if (focus != null)
            {
                try { dispatcher.rotateCameraToward(focus); }
                catch (Throwable t) { log.debug("camera rotate failed", t); }
            }
        }
        if (legIdx >= path.size()) return Status.ARRIVED;

        Leg active = path.legs().get(legIdx);
        Status s;
        if (active instanceof Leg.Walk wl) s = handleWalkLeg(wl, pos, now);
        else if (active instanceof Leg.Transport tr) s = handleTransportLeg(tr, pos, now);
        else s = Status.ERROR;

        if (s == Status.IN_PROGRESS && now - lastMovementMs > STUCK_AFTER_MS)
        {
            log.warn("trail-walker: STUCK — no movement for {}ms at leg {} ({})",
                now - lastMovementMs, legIdx, active.kind());
            return Status.STUCK;
        }

        // ARRIVED only when the very last leg is satisfied — player must be
        // standing on the leg's final tile.
        if (s == Status.IN_PROGRESS && legIdx == path.size() - 1
            && active instanceof Leg.Walk fin
            && pos.equals(fin.tiles().get(fin.tiles().size() - 1)))
        {
            return Status.ARRIVED;
        }
        return s;
    }

    private Status handleWalkLeg(Leg.Walk leg, WorldPoint pos, long now) throws InterruptedException
    {
        if (legContainsTile(leg, pos) && pos.equals(leg.tiles().get(leg.tiles().size() - 1)))
        {
            // Reached the final tile of THIS leg — let the next tick
            // advance to the next leg.
            return Status.IN_PROGRESS;
        }
        // Don't dispatch while the previous click is still in flight; the
        // dispatcher silently drops re-entrant requests and we'd burn the
        // 3s reclick timer on dropped clicks.
        if (dispatcher.isBusy()) return Status.IN_PROGRESS;
        // Click cadence: only re-pick + re-click when there's a real reason
        // to. Calling pickAheadTile every tick produces a NEW random target
        // each call (humanization), and dispatching a fresh WALK click on
        // each new target redirects the engine's pathfinder mid-walk —
        // the player oscillates between tiles and never makes progress.
        // Reasons to click:
        //   1. Leg changed (we just advanced past a transport / another leg)
        //   2. Player reached the previous pick tile (advance the target)
        //   3. RECLICK_AFTER_MS elapsed AND player has been still for
        //      STILL_THRESHOLD_MS (engine dropped our previous click).
        boolean legChanged = legIdx != lastClickLegIdx;
        boolean reachedPrevPick = lastWalkPick != null && pos.equals(lastWalkPick);
        long sinceClick = lastClickMs == 0 ? Long.MAX_VALUE : now - lastClickMs;
        long sinceMove = now - lastMovementMs;
        boolean stillWalking = sinceMove < STILL_THRESHOLD_MS;
        boolean recentClick = sinceClick < RECLICK_AFTER_MS;
        boolean staleClick = !recentClick && !stillWalking;
        if (!legChanged && !reachedPrevPick && !staleClick)
        {
            // Engine is still walking the player toward our previous click.
            // Don't pick a new tile and don't click — wait.
            return Status.IN_PROGRESS;
        }
        WorldPoint pick = pickAheadTile(leg, pos, ThreadLocalRandom.current());
        log.info("trail-walker: WALK leg {} → tile {} ({})",
            legIdx, pick,
            legChanged ? "leg-changed"
                : reachedPrevPick ? "reached-prev"
                : "stale");
        ActionRequest req = ActionRequest.builder()
            .kind(ActionRequest.Kind.WALK)
            .channel(ActionRequest.Channel.MOUSE)
            .tile(pick)
            .build();
        dispatcher.dispatch(req);
        lastClickMs = now;
        lastClickLegIdx = legIdx;
        lastWalkPick = pick;
        return Status.IN_PROGRESS;
    }

    /** A transport that has been fired but whose effect we must wait for
     *  (e.g. stair animation + plane change). After this many ms with no
     *  observed player movement, retry the click. */
    static final long TRANSPORT_VERB_GRACE_TICKS = 4;

    private Status handleTransportLeg(Leg.Transport leg, WorldPoint pos, long now)
        throws InterruptedException
    {
        WorldPoint t = leg.tile();
        // Don't fire ANY clicks while the dispatcher's previous one is
        // still in flight — its busy flag silently drops re-entrant
        // dispatches and we'd block forever on the verb retry.
        if (dispatcher.isBusy()) return Status.IN_PROGRESS;
        boolean adjacent = pos.getPlane() == t.getPlane()
            && Math.abs(pos.getX() - t.getX()) <= TRANSPORT_ADJACENCY
            && Math.abs(pos.getY() - t.getY()) <= TRANSPORT_ADJACENCY;
        if (!adjacent)
        {
            // Walk toward the transport tile.
            if (shouldClick(now, t))
            {
                log.info("trail-walker: walk-to-transport {} (verb {})", t, leg.verb());
                ActionRequest req = ActionRequest.builder()
                    .kind(ActionRequest.Kind.WALK)
                    .channel(ActionRequest.Channel.MOUSE)
                    .tile(t)
                    .build();
                dispatcher.dispatch(req);
                lastClickMs = now;
                lastClickLegIdx = legIdx;
                lastWalkPick = t;
            }
            return Status.IN_PROGRESS;
        }
        if (now - lastInteractMs < INTERACT_THROTTLE_MS) return Status.IN_PROGRESS;
        // Wait until the player's pose is idle before clicking the verb —
        // mirrors UniversalWalker's "don't open menu mid-walk" rule.
        Boolean settled = onClient(() -> {
            Player self = client.getLocalPlayer();
            return self != null && self.getPoseAnimation() == self.getIdlePoseAnimation();
        });
        if (settled == null || !settled) return Status.IN_PROGRESS;
        // Pre-flight: is the verb actually present on any object at this
        // tile? When a gate is already open, the recorded "Open" verb
        // disappears (the impostor advertises "Close" instead). Without
        // this check the dispatcher's findTransport silently fails and we
        // burn the 3s INTERACT_THROTTLE_MS retrying forever. If the verb
        // is missing for several consecutive ticks, treat the transport as
        // already done and advance — the next leg's WALK will route the
        // player through the now-open gate.
        TransportVerbState verbState = checkVerbPresence(t, leg.verb());
        if (verbState == TransportVerbState.MISSING)
        {
            transportVerbMissingTicks++;
            if (transportVerbMissingTicks >= TRANSPORT_VERB_GRACE_TICKS)
            {
                log.info("trail-walker: verb '{}' absent at {} for {} ticks — "
                    + "treating transport as already complete; advancing leg {}",
                    leg.verb(), t, transportVerbMissingTicks, legIdx);
                advanceLegAfterAlreadyDone();
                return Status.IN_PROGRESS;
            }
            log.debug("trail-walker: verb '{}' not found at {} (tick {}/{}) — "
                + "waiting for object", leg.verb(), t,
                transportVerbMissingTicks, TRANSPORT_VERB_GRACE_TICKS);
            return Status.IN_PROGRESS;
        }
        transportVerbMissingTicks = 0;
        log.info("trail-walker: CLICK_GAME_OBJECT verb '{}' tile {} id {}",
            leg.verb(), t, leg.objectId());
        ActionRequest req = ActionRequest.builder()
            .kind(ActionRequest.Kind.CLICK_GAME_OBJECT)
            .channel(ActionRequest.Channel.MOUSE)
            .tile(t)
            .verb(leg.verb())
            .build();
        dispatcher.dispatch(req);
        lastInteractMs = now;
        lastClickMs = now;
        return Status.IN_PROGRESS;
    }

    /** Result of probing whether a given verb is callable at the given
     *  tile. Used to short-circuit transports whose effect has already
     *  taken place (open gates, doors that swung open last trip). */
    enum TransportVerbState { PRESENT, MISSING, UNKNOWN }

    /** Look at every object on {@code tile} and report whether {@code verb}
     *  matches any of their actions. {@code UNKNOWN} when the scene isn't
     *  loaded or the resolver is unavailable — caller treats UNKNOWN as
     *  "dispatch anyway, the engine's hover will tell us the truth". Only
     *  {@code MISSING} (tile loaded, no object on it advertises the verb)
     *  triggers the already-completed advance path. */
    private TransportVerbState checkVerbPresence(WorldPoint tile, String verb)
    {
        if (transportResolver == null || tile == null || verb == null) return TransportVerbState.UNKNOWN;
        TransportVerbState s = onClient(() -> {
            try
            {
                var match = transportResolver.findTransport(tile, verb);
                if (match == null) return TransportVerbState.UNKNOWN;
                if (match.isSuccess()) return TransportVerbState.PRESENT;
                String fail = match.failure();
                if (fail == null
                    || fail.startsWith("no tile at")
                    || fail.startsWith("null tile")
                    || fail.startsWith("empty verb"))
                {
                    return TransportVerbState.UNKNOWN;
                }
                // Tile is loaded but no object on it has the verb —
                // almost always means the transport is already in its
                // post-state (gate already open).
                return TransportVerbState.MISSING;
            }
            catch (Throwable th) { return TransportVerbState.UNKNOWN; }
        });
        return s == null ? TransportVerbState.UNKNOWN : s;
    }

    /** Mark the current TRANSPORT leg as already-done and bump the leg
     *  pointer so the next tick handles the following leg. Resets the
     *  transient verb-grace counter. */
    private void advanceLegAfterAlreadyDone()
    {
        legIdx = Math.min(legIdx + 1, currentPath == null ? legIdx + 1 : currentPath.size());
        lastWalkPick = null;
        lastClickLegIdx = -1;
        transportVerbMissingTicks = 0;
        // Reset movement timer — we just made progress in the path even
        // though the player didn't physically move. Without this the STUCK
        // timer would fire if the next leg's first click is delayed by
        // dispatcher idle / camera rotate.
        lastMovementMs = System.currentTimeMillis();
    }

    private boolean shouldClick(long now, WorldPoint pick)
    {
        if (legIdx != lastClickLegIdx) return true;
        if (lastWalkPick == null || !lastWalkPick.equals(pick)) return true;
        long sinceClick = lastClickMs == 0 ? Long.MAX_VALUE : now - lastClickMs;
        long sinceMove = now - lastMovementMs;
        boolean stillWalking = sinceMove < STILL_THRESHOLD_MS;
        boolean recentClick = sinceClick < RECLICK_AFTER_MS;
        return !recentClick && !stillWalking;
    }

    @Nullable
    private WorldPoint readPlayerTile()
    {
        return onClient(() -> {
            Player self = client.getLocalPlayer();
            return self == null ? null : self.getWorldLocation();
        });
    }

    @Nullable
    private <T> T onClient(java.util.function.Supplier<T> sup)
    {
        AtomicReference<T> ref = new AtomicReference<>();
        java.util.concurrent.CountDownLatch latch =
            new java.util.concurrent.CountDownLatch(1);
        clientThread.invokeLater(() -> {
            try { ref.set(sup.get()); }
            catch (Throwable th) { log.warn("trail-walker: onClient threw", th); }
            finally { latch.countDown(); }
        });
        try { latch.await(); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); return null; }
        return ref.get();
    }

    @Nullable
    private static WorldPoint legFocusTile(Leg leg)
    {
        if (leg instanceof Leg.Walk w) return w.tiles().get(w.tiles().size() - 1);
        if (leg instanceof Leg.Transport t) return t.tile();
        return null;
    }
}
