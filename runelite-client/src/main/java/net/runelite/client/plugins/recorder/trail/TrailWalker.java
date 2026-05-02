package net.runelite.client.plugins.recorder.trail;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
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
    /** Chebyshev distance within which a visible transport object is clicked
     *  directly (OSRS pathfinds and queues the verb). Beyond this the walker
     *  walks closer first to ensure the engine can route the player. */
    public static final int TRANSPORT_DIRECT_CLICK_TILES = 13;
    /** Distance-to-pick must improve at least once within this window for
     *  the click to be considered "still making progress". When neither
     *  movement nor distance progress has been observed AND the reclick
     *  cooldown has elapsed, we treat the click as dropped and re-issue.
     *  Without this gate, a player slow-walking past obstacles for >2.5s
     *  would trigger redundant reclicks even though the engine was
     *  faithfully routing them. */
    public static final long PROGRESS_TIMEOUT_MS = 4_000;

    private final Client client;
    private final ClientThread clientThread;
    private final HumanizedInputDispatcher dispatcher;
    private final net.runelite.client.plugins.recorder.transport.TransportResolver transportResolver;
    /** Pluggable on-canvas probe. Default delegates to {@link #computeIsTileOnCanvas}
     *  which uses {@link Perspective#localToCanvas}. Tests swap in a fixed
     *  predicate via {@link #setOnCanvasProbeForTest} because Mockito-driven
     *  Perspective math requires stubbing the full camera/viewport state. */
    private java.util.function.Predicate<WorldPoint> onCanvasProbe = this::computeIsTileOnCanvas;

    private TrailPath currentPath;
    private int legIdx;
    private WorldPoint lastSeenPosition;
    private long lastMovementMs;
    private long lastClickMs;
    private long lastInteractMs;
    private int lastClickLegIdx = -1;
    private WorldPoint lastWalkPick;
    /** Best (smallest) Chebyshev distance from the player to
     *  {@link #lastWalkPick} observed since the click was issued. When
     *  this strictly decreases, {@link #lastProgressMs} is bumped — the
     *  engine is faithfully routing us, so don't fire a redundant
     *  reclick. Reset to {@link Integer#MAX_VALUE} on every new click. */
    private int bestDistToPick = Integer.MAX_VALUE;
    /** Wall-clock of the last observation that the player closed distance
     *  to the current pick. Used by {@link #handleWalkLeg}'s stale check. */
    private long lastProgressMs;
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
        bestDistToPick = Integer.MAX_VALUE;
        lastProgressMs = System.currentTimeMillis();
        transportVerbMissingTicks = 0;
        // Clear the debug overlay so a stale path/pick doesn't linger
        // after the walker is reset between scripts / sessions.
        TrailOverlay.publishActiveTrail(null);
        TrailOverlay.publishCurrentPick(null);
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
        // Publish to the debug overlay (no-op when unregistered). Done
        // every tick so the path stays visible across leg advances and
        // catches the case where the script hands us a fresh path
        // mid-run (return-trail vs. outbound-trail swap).
        TrailOverlay.publishActiveTrail(path);
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
            onLegAdvancedReset(now);
            rotateCameraToActiveLeg();
        }
        if (legIdx >= path.size()) return Status.ARRIVED;

        // Interaction-ready handoff. The OSRS pathfinder may land the
        // player on a tile adjacent to (but not equal to) a WALK leg's
        // last tile. Without this peek-ahead, the walker stays on the
        // WALK leg and burns 2-6 ticks issuing 1-tile micro-walks before
        // the player happens to land on the exact recorded tile,
        // chooseLegIndex finally advances, and the TRANSPORT click fires.
        // If the next leg is a TRANSPORT whose verb is callable RIGHT
        // NOW from the player's position, skip those tail-end clicks —
        // OSRS will path the player to the object as part of the verb
        // dispatch.
        if (tryAdvanceToReachableTransport(pos, now)) {
            // Fall through with the new legIdx; let the TRANSPORT
            // handler take it from here.
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
        // Update the distance-progress tracker BEFORE the click decision
        // so a tick where the player is closing distance counts as
        // progress even if we haven't crossed a tile boundary yet.
        if (lastWalkPick != null && pos.getPlane() == lastWalkPick.getPlane())
        {
            int distToPick = chebyshev(pos, lastWalkPick);
            if (distToPick < bestDistToPick)
            {
                bestDistToPick = distToPick;
                lastProgressMs = now;
            }
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
        //      STILL_THRESHOLD_MS AND no distance progress to the pick
        //      within PROGRESS_TIMEOUT_MS. The progress gate matters when
        //      the player is squeezing past an NPC / through a doorway:
        //      they may not change tiles for >2.5s but distance to the
        //      pick is still falling, so the engine is on it — don't
        //      reclick.
        boolean legChanged = legIdx != lastClickLegIdx;
        boolean reachedPrevPick = lastWalkPick != null && pos.equals(lastWalkPick);
        long sinceClick = lastClickMs == 0 ? Long.MAX_VALUE : now - lastClickMs;
        long sinceMove = now - lastMovementMs;
        long sinceProgress = lastProgressMs == 0 ? Long.MAX_VALUE : now - lastProgressMs;
        boolean stillWalking = sinceMove < STILL_THRESHOLD_MS;
        boolean recentClick = sinceClick < RECLICK_AFTER_MS;
        boolean recentProgress = lastWalkPick != null && sinceProgress < PROGRESS_TIMEOUT_MS;
        boolean staleClick = !recentClick && !stillWalking && !recentProgress;
        if (!legChanged && !reachedPrevPick && !staleClick)
        {
            // Engine is still walking the player toward our previous click.
            // Don't pick a new tile and don't click — wait.
            return Status.IN_PROGRESS;
        }
        WorldPoint pick = pickAheadTile(leg, pos, ThreadLocalRandom.current());
        WorldPoint legEnd = leg.tiles().get(leg.tiles().size() - 1);
        log.info("trail-walker: WALK leg {} pick={} reason={} dist-to-pick={} dist-to-legEnd={} "
            + "sinceClick={}ms sinceMove={}ms sinceProgress={}ms",
            legIdx, pick,
            legChanged ? "leg-changed"
                : reachedPrevPick ? "reached-prev"
                : "stale",
            chebyshev(pos, pick), chebyshev(pos, legEnd),
            sinceClick == Long.MAX_VALUE ? -1 : sinceClick,
            sinceMove,
            sinceProgress == Long.MAX_VALUE ? -1 : sinceProgress);
        ActionRequest req = ActionRequest.builder()
            .kind(ActionRequest.Kind.WALK)
            .channel(ActionRequest.Channel.MOUSE)
            .tile(pick)
            .build();
        dispatcher.dispatch(req);
        lastClickMs = now;
        lastClickLegIdx = legIdx;
        lastWalkPick = pick;
        // New pick → reset the progress tracker. Seed with the current
        // distance so the first time it strictly decreases counts as
        // progress.
        bestDistToPick = chebyshev(pos, pick);
        lastProgressMs = now;
        TrailOverlay.publishCurrentPick(pick);
        return Status.IN_PROGRESS;
    }

    private static int chebyshev(WorldPoint a, WorldPoint b)
    {
        return Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getY() - b.getY()));
    }

    /** Common bookkeeping when {@code legIdx} jumps to a new leg, whether
     *  via {@link #chooseLegIndex} or {@link #tryAdvanceToReachableTransport}.
     *  Clears the per-pick state (so the next tick picks fresh) and seeds
     *  the progress tracker for the new leg. */
    private void onLegAdvancedReset(long now)
    {
        lastWalkPick = null;
        bestDistToPick = Integer.MAX_VALUE;
        lastProgressMs = now;
        TrailOverlay.publishCurrentPick(null);
    }

    /** Dispatches to {@link #onCanvasProbe} so tests can substitute a
     *  deterministic answer. Production code calls only this method. */
    private boolean isTileOnCanvas(WorldPoint tile)
    {
        return tile != null && onCanvasProbe.test(tile);
    }

    /** True iff the given world tile's centre projects to a non-null
     *  canvas point under the current camera. Used to gate
     *  CLICK_GAME_OBJECT — when the verb-bearing object is in the loaded
     *  scene (verb PRESENT) but the tile is off-canvas (camera mid-rotate
     *  or pointed away), {@link
     *  net.runelite.client.sequence.dispatch.PixelResolver} can't sample
     *  a screen pixel from the hull or tile poly and the dispatcher logs
     *  "pixel unresolvable". Walking closer / waiting for the camera to
     *  finish rotating is the correct response, not retrying the click.
     *
     *  <p>Marshals to the client thread because all canvas projection
     *  reads require it. Returns {@code false} on any null intermediate
     *  (no world view, tile out of scene, projection off-screen). */
    private boolean computeIsTileOnCanvas(WorldPoint tile)
    {
        Boolean visible = onClient(() -> {
            WorldView wv = client.getTopLevelWorldView();
            if (wv == null) return Boolean.FALSE;
            LocalPoint lp = LocalPoint.fromWorld(wv, tile);
            if (lp == null) return Boolean.FALSE;
            net.runelite.api.Point p = Perspective.localToCanvas(client, lp, tile.getPlane());
            return p != null;
        });
        return Boolean.TRUE.equals(visible);
    }

    /** Test-only hook. Swaps the on-canvas probe so handoff / transport
     *  decisions can be exercised without stubbing the full Perspective
     *  camera+viewport state. Package-private — production code never
     *  calls this. */
    void setOnCanvasProbeForTest(java.util.function.Predicate<WorldPoint> probe)
    {
        this.onCanvasProbe = probe;
    }

    /** Rotate the camera toward a tile inside the now-active leg so we
     *  don't walk staring at the back of our head. Cheap and best-effort —
     *  any failure is logged and swallowed. */
    private void rotateCameraToActiveLeg()
    {
        if (currentPath == null || legIdx >= currentPath.size()) return;
        WorldPoint focus = legFocusTile(currentPath.legs().get(legIdx));
        if (focus == null) return;
        try { dispatcher.rotateCameraToward(focus); }
        catch (Throwable t) { log.debug("camera rotate failed", t); }
    }

    /** If the current leg is a WALK and the next leg is a TRANSPORT whose
     *  verb is callable from {@code pos}, advance {@code legIdx} past the
     *  WALK leg. Returns {@code true} when an advance happened.
     *
     *  <p>This is the fix for the "WALK → TRANSPORT handoff lag" bug —
     *  the OSRS pathfinder routinely lands the player on a tile adjacent
     *  to (but not equal to) a WALK leg's last recorded tile, which
     *  caused the walker to spend several ticks issuing 1-tile micro-walks
     *  trying to reach the exact recorded tile before chooseLegIndex
     *  finally advanced. By peeking ahead, we let the TRANSPORT click
     *  fire as soon as it's reachable; the engine pathfinds the player
     *  to the object as part of dispatching the verb. */
    private boolean tryAdvanceToReachableTransport(WorldPoint pos, long now)
    {
        if (currentPath == null) return false;
        if (legIdx >= currentPath.size() - 1) return false;
        Leg cur = currentPath.legs().get(legIdx);
        if (!(cur instanceof Leg.Walk)) return false;
        Leg next = currentPath.legs().get(legIdx + 1);
        if (!(next instanceof Leg.Transport tr)) return false;
        if (pos.getPlane() != tr.tile().getPlane()) return false;
        // Cheap range check before the client-thread hop. The verb won't
        // pathfind from beyond minimap range either, so there's no point
        // probing the resolver.
        int dist = chebyshev(pos, tr.tile());
        if (dist > TRANSPORT_DIRECT_CLICK_TILES) return false;
        // The verb-presence probe matters because the resolver returns
        // PRESENT only when a loaded scene object actually advertises the
        // recorded verb. UNKNOWN (tile not loaded) and MISSING (object
        // already in post-state) both mean "don't fast-forward" — let
        // handleTransportLeg apply its existing grace + walk-closer logic.
        TransportVerbState verbState = checkVerbPresence(tr.tile(), tr.verb());
        if (verbState != TransportVerbState.PRESENT) return false;
        // PRESENT (in scene) is necessary but not sufficient: the camera
        // may still be rotating, leaving the object off-canvas for the
        // first few ticks. {@link
        // net.runelite.client.sequence.dispatch.PixelResolver} returns
        // null when neither the convex hull nor the canvas tile poly
        // projects, and the dispatcher logs "pixel unresolvable" — the
        // 2026-05-02 STUCK loop on the Lumbridge p=2 stairs was exactly
        // this. Require the tile center to project to canvas before
        // handing off; otherwise the WALK leg keeps running, the player
        // closes distance, and the camera follow brings the object into
        // view naturally.
        if (!isTileOnCanvas(tr.tile()))
        {
            log.debug("trail-walker: handoff deferred — transport tile {} "
                + "not on-canvas yet (verb={} dist={})", tr.tile(), tr.verb(), dist);
            return false;
        }
        log.info("trail-walker: handoff WALK leg {} → TRANSPORT leg {} "
            + "(verb='{}' tile={} dist={}) — skipping tail-end micro-walks",
            legIdx, legIdx + 1, tr.verb(), tr.tile(), dist);
        legIdx++;
        onLegAdvancedReset(now);
        rotateCameraToActiveLeg();
        return true;
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

        // Check verb presence BEFORE deciding whether to walk or click.
        // When the object is PRESENT in the scene, dispatch CLICK_GAME_OBJECT
        // directly — OSRS pathfinds the player to it and executes the verb,
        // exactly like a human clicking stairs from 5 tiles away. The old
        // "walk adjacent first, then click" path always fell back to minimap
        // because hovering the transport tile shows the verb (e.g.
        // "Climb-down"), never "Walk here", causing oscillation near the
        // object as minimap routing overshot or undershot the 1-tile adjacency
        // threshold. UNKNOWN (tile not yet in scene) keeps the walk-adjacent
        // fallback so we don't spin dispatching a verb the resolver can't find.
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

        int distToTransport = Math.max(
            Math.abs(pos.getX() - t.getX()), Math.abs(pos.getY() - t.getY()));
        boolean withinDirectClickRange = distToTransport <= TRANSPORT_DIRECT_CLICK_TILES;
        // Object can be in scene (verb PRESENT) yet off-canvas — camera
        // is mid-rotate, or pointed away after a teleport. Trying to fire
        // CLICK_GAME_OBJECT in that state burns 5+ ticks on
        // "pixel unresolvable" because PixelResolver can't sample the
        // hull or tile poly. When that's the case, fall back to the
        // walk-closer branch (minimap WALK works fine for off-canvas
        // world tiles) and re-issue the camera rotate so the next tick
        // can see the object.
        boolean onCanvas = adjacent || isTileOnCanvas(t);

        if (!adjacent && (verbState == TransportVerbState.UNKNOWN
                || !withinDirectClickRange
                || !onCanvas))
        {
            // Walk closer first: either the object isn't loaded in scene yet,
            // we're beyond the direct-click range, or the camera hasn't
            // brought the object into view.
            if (shouldClick(now, t))
            {
                log.info("trail-walker: walk-to-transport {} (verb {}, dist={}, verbState={}, onCanvas={})",
                    t, leg.verb(), distToTransport, verbState, onCanvas);
                ActionRequest req = ActionRequest.builder()
                    .kind(ActionRequest.Kind.WALK)
                    .channel(ActionRequest.Channel.MOUSE)
                    .tile(t)
                    .build();
                dispatcher.dispatch(req);
                lastClickMs = now;
                lastClickLegIdx = legIdx;
                lastWalkPick = t;
                // Re-issue the camera rotate when off-canvas so the next
                // tick can see the object. Cheap: if the camera is
                // already pointed at the tile, the dispatcher no-ops.
                if (!onCanvas) rotateCameraToActiveLeg();
            }
            return Status.IN_PROGRESS;
        }

        // Object is PRESENT (or UNKNOWN but adjacent) — click it.
        if (now - lastInteractMs < INTERACT_THROTTLE_MS) return Status.IN_PROGRESS;
        // When already adjacent, wait for idle pose so we don't interrupt a
        // walk animation with a second click mid-step. When not adjacent but
        // PRESENT, skip idle — the game queues the verb and executes it on
        // arrival, so clicking while walking is correct and expected.
        if (adjacent)
        {
            Boolean settled = onClient(() -> {
                Player self = client.getLocalPlayer();
                return self != null && self.getPoseAnimation() == self.getIdlePoseAnimation();
            });
            if (settled == null || !settled) return Status.IN_PROGRESS;
        }
        log.info("trail-walker: CLICK_GAME_OBJECT verb '{}' tile {} id {} (adjacent={})",
            leg.verb(), t, leg.objectId(), adjacent);
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
