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

    private TrailPath currentPath;
    private int legIdx;
    private WorldPoint lastSeenPosition;
    private long lastMovementMs;
    private long lastClickMs;
    private long lastInteractMs;
    private int lastClickLegIdx = -1;
    private WorldPoint lastWalkPick;

    public TrailWalker(Client client, ClientThread clientThread,
                       HumanizedInputDispatcher dispatcher)
    {
        this.client = client;
        this.clientThread = clientThread;
        this.dispatcher = dispatcher;
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
    }

    public int currentLegIndex() { return legIdx; }

    /** Pick the active leg index for the given player position. Pure
     *  function — exposed package-public for unit testing. */
    static int chooseLegIndex(TrailPath path, int minIdx, WorldPoint pos)
    {
        List<Leg> legs = path.legs();
        // Single-step monotonic advance: only flip to leg i+1 if the
        // player is in i+1's tile-set. Multi-step skipping (e.g. across
        // a transport) requires re-entering tick after each advance —
        // this prevents skipping past an unfinished transport when its
        // post-tile aliases a tile in the active leg.
        int idx = minIdx;
        while (idx < legs.size() - 1 && legContainsTile(legs.get(idx + 1), pos))
        {
            idx++;
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

        // Humanization: jitter within the LAST quarter of the in-range
        // window, never beyond farthestIdx. With a typical leg of 50+
        // tiles and a 16-tile hop, this produces 4-5 candidate tiles —
        // enough variety that consecutive passes click different tiles
        // while still reliably advancing toward the leg's end.
        int windowSize = farthestIdx - firstAhead + 1;
        int jitterSpan = Math.max(1, windowSize / 4);
        int jitterStart = farthestIdx - jitterSpan + 1;
        int pickIdx = jitterStart + rng.nextInt(jitterSpan);
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

    private Status handleTransportLeg(Leg.Transport leg, WorldPoint pos, long now)
        throws InterruptedException
    {
        WorldPoint t = leg.tile();
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
