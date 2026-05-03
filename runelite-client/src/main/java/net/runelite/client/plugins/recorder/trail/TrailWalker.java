package net.runelite.client.plugins.recorder.trail;

import java.awt.Shape;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.recorder.transport.TransportResolver;
import net.runelite.client.plugins.recorder.walker.Reachability;
import net.runelite.client.plugins.recorder.walker.Reachability.ReachabilityMap;
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
 *    <li>WALK leg → click a chosen tile from the ahead window. Optional
 *        corridor walking may replace that pick with a nearby safe tile
 *        around the same forward target.</li>
 *    <li>TRANSPORT leg → walk to the transport tile if not directly
 *        clickable; once reachable, dispatch CLICK_GAME_OBJECT with the
 *        recorded verb / objectId.</li>
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
     *  directly. Beyond this the walker walks closer first. */
    public static final int TRANSPORT_DIRECT_CLICK_TILES = 13;

    /** v2 cap for opportunistic transport clicks. Set just below
     *  {@link #TRANSPORT_DIRECT_CLICK_TILES}=13 so that inside this radius
     *  the OSRS server's pathfind from the click target stays close to the
     *  recorded trail's intended route; beyond it, the engine may detour
     *  through unintended tiles. The visibility-gated transport click in
     *  {@link #handleTransportLeg} uses this constant instead of the
     *  legacy {@link #TRANSPORT_ADJACENCY}=1 rule. */
    public static final int MAX_TRANSPORT_CLICK_DISTANCE = 12;

    /** Distance-to-pick must improve at least once within this window for
     *  the click to be considered still making progress. */
    public static final long PROGRESS_TIMEOUT_MS = 4_000;

    /** Maximum Chebyshev distance the engine's minimap walk-click reliably
     *  routes. Picks beyond this are out of range. */
    static final int MAX_HOP_TILES = 16;

    /** A transport that has been fired but whose effect we must wait for
     *  before treating a missing verb as already-complete. */
    static final long TRANSPORT_VERB_GRACE_TICKS = 4;

    /** Hard cap for experimental corridor offset. Radius 1–2 is the useful
     *  range; radius 3 is allowed for testing but should not be the default. */
    static final int MAX_CORRIDOR_RADIUS = 3;

    private final Client client;
    private final ClientThread clientThread;
    private final HumanizedInputDispatcher dispatcher;
    private final net.runelite.client.plugins.recorder.transport.TransportResolver transportResolver;

    /** Pluggable on-canvas probe. Default delegates to {@link #computeIsTileOnCanvas}. */
    private Predicate<WorldPoint> onCanvasProbe = this::computeIsTileOnCanvas;

    /** Pluggable walkability probe. Default is conservative/no-op: true.
     *  Wire this to a collision-aware helper once the repo's movement helper
     *  is identified. Corridor mode is off by default, so this cannot affect
     *  existing behavior unless explicitly enabled. */
    private Predicate<WorldPoint> walkableProbe = tile -> true;

    /** Corridor walking master toggle. Default on in v2 — the
     *  {@link Reachability}-backed probe wired in
     *  {@link #maybeApplyCorridorPick} validates each candidate against
     *  the engine's collision flags, so off-trail walls are excluded
     *  automatically. Opt out per walker via
     *  {@link #setCorridorWalkingEnabled}. */
    private boolean corridorWalkingEnabled = true;

    /** v1 holdover: when true, compute and log corridor candidates but
     *  keep clicking the original trail pick. v2 default is false (the
     *  corridor pick actually applies). Tests still flip this on to
     *  exercise the picker independently of the dispatcher. */
    private boolean corridorDebugOnly = false;

    /** Radius around the normal forward pick. Default 1, max 3. */
    private int corridorRadius = 1;

    /** Chance to replace the original pick when corridor mode is enabled
     *  and not debug-only. v2 raises the default to 100 — every walk pick
     *  is corridor-selected when a candidate exists, falling back to the
     *  centerline when not. The 20% v1 default existed because the
     *  walkable probe was a no-op and clicking arbitrary nearby tiles
     *  was risky; with the BFS-backed probe wired the risk is gone. */
    private int corridorChancePercent = 100;

    /** v2: visibility checker for opportunistic transport clicks. Default
     *  binds to the live client; tests inject {@link ObjectVisibility#alwaysVisible()}. */
    private ObjectVisibility objectVisibility;

    private TrailPath currentPath;
    private int legIdx;
    private WorldPoint lastSeenPosition;
    private long lastMovementMs;
    private long lastClickMs;
    private long lastInteractMs;
    private int lastClickLegIdx = -1;
    private WorldPoint lastWalkPick;

    /** Best distance to {@link #lastWalkPick} observed since the click was issued. */
    private int bestDistToPick = Integer.MAX_VALUE;

    /** Wall-clock of the last observation that the player closed distance to the current pick. */
    private long lastProgressMs;

    /** Missing-verb grace counter for already-completed transports. */
    private int transportVerbMissingTicks;

    /** v2 transport flow: have we already rotated the camera once for
     *  the active transport leg? Reset on leg advance. Prevents tight
     *  re-rotation loops when the visibility check still fails after
     *  the streamed yaw arrives (rotation takes 500-1500 ms; multiple
     *  ticks pass during it). */
    private boolean rotatedThisLeg;

    /** v2 route state — see {@link #walkRoute(Route)}. {@code activeRoute}
     *  is the route the caller most recently asked us to walk;
     *  {@code activeRoutePath} is the {@link TrailPath} compiled from
     *  the trail we picked from that route; {@code lastPickedTrail} is
     *  remembered across calls for {@link Route#noRepeat()} support. */
    @Nullable private Route activeRoute;
    @Nullable private TrailPath activeRoutePath;
    @Nullable private Trail lastPickedTrail;

    public TrailWalker(Client client, ClientThread clientThread,
                       HumanizedInputDispatcher dispatcher)
    {
        this(client, clientThread, dispatcher,
            client == null ? ObjectVisibility.alwaysVisible() : ObjectVisibility.forClient(client));
    }

    /** Test/integration constructor allowing a non-default
     *  {@link ObjectVisibility} (typically {@link ObjectVisibility#alwaysVisible()}
     *  in unit tests where there's no live client to read viewport/HUD from). */
    public TrailWalker(Client client, ClientThread clientThread,
                       HumanizedInputDispatcher dispatcher,
                       ObjectVisibility objectVisibility)
    {
        this.client = client;
        this.clientThread = clientThread;
        this.dispatcher = dispatcher;
        this.transportResolver = client == null ? null
            : new TransportResolver(client);
        this.objectVisibility = objectVisibility == null
            ? ObjectVisibility.alwaysVisible()
            : objectVisibility;
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
        rotatedThisLeg = false;
        // Note: activeRoute / activeRoutePath / lastPickedTrail are NOT
        // reset here. reset() runs on path swap inside tick(); leaving
        // route state alone lets walkRoute manage its own lifecycle.
        TrailOverlay.publishActiveTrail(null);
        TrailOverlay.publishCurrentPick(null);
    }

    public int currentLegIndex()
    {
        return legIdx;
    }

    /** Runtime toggle for testing corridor walking without changing constructor wiring. */
    public void setCorridorWalkingEnabled(boolean enabled)
    {
        this.corridorWalkingEnabled = enabled;
    }

    /** Runtime toggle. When true, corridor picks are computed/logged but not clicked. */
    public void setCorridorDebugOnly(boolean debugOnly)
    {
        this.corridorDebugOnly = debugOnly;
    }

    /** Runtime radius setter. Clamped to 0..3. */
    public void setCorridorRadius(int radius)
    {
        this.corridorRadius = Math.max(0, Math.min(MAX_CORRIDOR_RADIUS, radius));
    }

    /** Runtime probability setter. Clamped to 0..100. */
    public void setCorridorChancePercent(int chancePercent)
    {
        this.corridorChancePercent = Math.max(0, Math.min(100, chancePercent));
    }

    /** Test / integration hook for collision-aware corridor walking. */
    void setWalkableProbeForTest(Predicate<WorldPoint> probe)
    {
        this.walkableProbe = probe == null ? tile -> true : probe;
    }

    /** Test/integration hook to inject an alternate {@link ObjectVisibility}
     *  (e.g. {@link ObjectVisibility#alwaysVisible()} so unit tests don't
     *  need a live viewport). Production callers pick up the live-client
     *  binding via the default constructor. */
    public void setObjectVisibility(ObjectVisibility v)
    {
        this.objectVisibility = v == null ? ObjectVisibility.alwaysVisible() : v;
    }

    /** Pick a trail from {@code route} (weighted-random, with
     *  {@link Route#noRepeat()} respected against the trail this walker
     *  most recently picked) and drive it to ARRIVED/STUCK/ERROR. Caller
     *  invokes once per outer-loop tick; the walker tracks which trail
     *  it's currently on across calls and re-picks once the trail
     *  finishes (or breaks).
     *
     *  <p>The route's {@link Route#corridorRadius()} is applied to this
     *  walker for the trail's lifetime — a side effect, but the simplest
     *  way to let different routes carry different corridor preferences.
     *  When the trail finishes, the radius stays at whatever the route
     *  set it to until a new route changes it.
     *
     *  <p>{@code lastPickedTrail} is reset to {@code null} on route
     *  switch <i>only</i> if the new route doesn't include the last
     *  pick — keeps no-repeat tracking working across callers that flip
     *  between two overlapping routes. */
    public Status walkRoute(Route route) throws InterruptedException
    {
        if (route == null) return Status.ERROR;

        if (activeRoute != route || activeRoutePath == null)
        {
            // Switching routes: drop the no-repeat memory if the
            // previously-picked trail isn't part of the new route, so
            // the new route's first pick isn't artificially constrained.
            if (activeRoute != route && lastPickedTrail != null)
            {
                boolean stillRelevant = route.entries().stream()
                    .anyMatch(e -> e.trail() == lastPickedTrail);
                if (!stillRelevant) lastPickedTrail = null;
            }
            activeRoute = route;
            Trail picked = route.pickWeightedRandom(
                route.noRepeat() ? lastPickedTrail : null,
                ThreadLocalRandom.current());
            lastPickedTrail = picked;
            activeRoutePath = TrailPath.fromTrail(picked);
            setCorridorRadius(route.corridorRadius());
            log.info("trail-walker: route picked trail '{}' (route entries={}, corridor radius={}, noRepeat={})",
                picked.name(), route.entries().size(),
                route.corridorRadius(), route.noRepeat());
        }

        Status s = tick(activeRoutePath);
        if (s == Status.ARRIVED || s == Status.STUCK || s == Status.ERROR)
        {
            // Next walkRoute call will pick a fresh trail.
            activeRoutePath = null;
        }
        return s;
    }

    /** Most recently picked trail across all {@link #walkRoute} calls.
     *  Exposed for tests + scripts that want to see the no-repeat state. */
    @Nullable public Trail lastPickedTrail() { return lastPickedTrail; }

    /** Test-only hook. Swaps the on-canvas probe so handoff / transport
     *  decisions can be exercised without stubbing the full Perspective state. */
    void setOnCanvasProbeForTest(Predicate<WorldPoint> probe)
    {
        this.onCanvasProbe = probe == null ? this::computeIsTileOnCanvas : probe;
    }

    /** Pick the active leg index for the given player position. Pure function. */
    static int chooseLegIndex(TrailPath path, int minIdx, WorldPoint pos)
    {
        List<Leg> legs = path.legs();
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

            int hop = -1;
            for (int j = idx + 2; j < legs.size(); j++)
            {
                if (legContainsTile(legs.get(j), pos))
                {
                    hop = j;
                    break;
                }
            }

            if (hop < 0 && !legHasPlane(cur, pos.getPlane()))
            {
                for (int j = idx + 1; j < legs.size(); j++)
                {
                    if (legHasPlane(legs.get(j), pos.getPlane()))
                    {
                        hop = j;
                        break;
                    }
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

    /** Choose a tile inside {@code leg} that is ahead of the player. */
    static WorldPoint pickAheadTile(Leg.Walk leg, WorldPoint player, Random rng)
    {
        List<WorldPoint> tiles = leg.tiles();
        int closestIdx = -1;
        int closestDist = Integer.MAX_VALUE;
        for (int i = 0; i < tiles.size(); i++)
        {
            WorldPoint t = tiles.get(i);
            if (t.getPlane() != player.getPlane()) continue;
            int d = chebyshev(player, t);
            if (d < closestDist)
            {
                closestDist = d;
                closestIdx = i;
            }
        }

        if (closestIdx < 0) return tiles.get(tiles.size() - 1);
        int firstAhead = closestIdx + 1;
        if (firstAhead >= tiles.size()) return tiles.get(tiles.size() - 1);

        int farthestIdx = closestIdx;
        for (int i = firstAhead; i < tiles.size(); i++)
        {
            WorldPoint t = tiles.get(i);
            int d = chebyshev(player, t);
            if (d > MAX_HOP_TILES) break;
            farthestIdx = i;
        }

        if (farthestIdx <= closestIdx)
        {
            return tiles.get(tiles.size() - 1);
        }

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

    /** Experimental corridor tile selector. Candidates are around the normal
     *  forward pick, not around the player. This keeps the recorded trail as
     *  the centerline while allowing a small walkable corridor around it.
     *
     *  <p>A candidate must:
     *  <ul>
     *    <li>be on the player's plane</li>
     *    <li>be within {@link #MAX_HOP_TILES} of the player (minimap-clickable)</li>
     *    <li>be within {@code effectiveRadius} of some tile in the leg
     *        (the corridor invariant — no off-trail wandering)</li>
     *    <li>not lose forward progress: its Chebyshev distance to the leg's
     *        end tile must be {@code <= playerDistToEnd + 1}</li>
     *    <li>pass the {@code walkable} probe (collision-aware in production)</li>
     *  </ul>
     */
    static Optional<WorldPoint> pickCorridorTile(
        Leg.Walk leg,
        WorldPoint player,
        WorldPoint originalPick,
        int radius,
        Random rng,
        Predicate<WorldPoint> walkable)
    {
        if (leg == null || player == null || originalPick == null) return Optional.empty();
        if (radius <= 0) return Optional.empty();
        int effectiveRadius = Math.min(MAX_CORRIDOR_RADIUS, radius);

        List<WorldPoint> tiles = leg.tiles();
        if (tiles == null || tiles.isEmpty()) return Optional.empty();

        WorldPoint legEnd = tiles.get(tiles.size() - 1);
        int playerDistToEnd = chebyshev(player, legEnd);
        List<WorldPoint> candidates = new ArrayList<>();

        for (int dx = -effectiveRadius; dx <= effectiveRadius; dx++)
        {
            for (int dy = -effectiveRadius; dy <= effectiveRadius; dy++)
            {
                if (dx == 0 && dy == 0) continue;

                WorldPoint c = new WorldPoint(
                    originalPick.getX() + dx,
                    originalPick.getY() + dy,
                    originalPick.getPlane());

                if (c.getPlane() != player.getPlane()) continue;
                if (chebyshev(player, c) > MAX_HOP_TILES) continue;
                // Corridor invariant: reject tiles that drift more than
                // effectiveRadius from any leg tile. This subsumes the old
                // canReconnectSoon check (which was strictly more permissive
                // and therefore redundant after this filter).
                if (distanceToNearestTile(c, tiles) > effectiveRadius) continue;

                int candidateDistToEnd = chebyshev(c, legEnd);
                if (candidateDistToEnd > playerDistToEnd + 1) continue;

                if (walkable != null && !walkable.test(c)) continue;

                candidates.add(c);
            }
        }

        if (candidates.isEmpty()) return Optional.empty();
        return Optional.of(candidates.get(rng.nextInt(candidates.size())));
    }

    static int distanceToNearestTile(WorldPoint p, List<WorldPoint> tiles)
    {
        int best = Integer.MAX_VALUE;
        for (WorldPoint t : tiles)
        {
            if (t.getPlane() != p.getPlane()) continue;
            best = Math.min(best, chebyshev(p, t));
        }
        return best;
    }

    public Status tick(TrailPath path) throws InterruptedException
    {
        if (path == null || path.isEmpty()) return Status.ARRIVED;
        if (currentPath != path)
        {
            reset();
            currentPath = path;
        }

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

        if (tryAdvanceToReachableTransport(pos, now))
        {
            // Fall through with the new legIdx; let the TRANSPORT handler take it from here.
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
            return Status.IN_PROGRESS;
        }

        if (lastWalkPick != null && pos.getPlane() == lastWalkPick.getPlane())
        {
            int distToPick = chebyshev(pos, lastWalkPick);
            if (distToPick < bestDistToPick)
            {
                bestDistToPick = distToPick;
                lastProgressMs = now;
            }
        }

        if (dispatcher.isBusy()) return Status.IN_PROGRESS;

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
            return Status.IN_PROGRESS;
        }

        WorldPoint originalPick = pickAheadTile(leg, pos, ThreadLocalRandom.current());
        WorldPoint pick = maybeApplyCorridorPick(leg, pos, originalPick);
        WorldPoint legEnd = leg.tiles().get(leg.tiles().size() - 1);

        log.info("trail-walker: WALK leg {} pick={} originalPick={} reason={} dist-to-pick={} "
            + "dist-to-legEnd={} sinceClick={}ms sinceMove={}ms sinceProgress={}ms corridorEnabled={} debugOnly={}",
            legIdx, pick, originalPick,
            legChanged ? "leg-changed"
                : reachedPrevPick ? "reached-prev"
                : "stale",
            chebyshev(pos, pick), chebyshev(pos, legEnd),
            sinceClick == Long.MAX_VALUE ? -1 : sinceClick,
            sinceMove,
            sinceProgress == Long.MAX_VALUE ? -1 : sinceProgress,
            corridorWalkingEnabled,
            corridorDebugOnly);

        ActionRequest req = ActionRequest.builder()
            .kind(ActionRequest.Kind.WALK)
            .channel(ActionRequest.Channel.MOUSE)
            .tile(pick)
            .build();
        dispatcher.dispatch(req);

        lastClickMs = now;
        lastClickLegIdx = legIdx;
        lastWalkPick = pick;
        bestDistToPick = chebyshev(pos, pick);
        lastProgressMs = now;
        TrailOverlay.publishCurrentPick(pick);
        return Status.IN_PROGRESS;
    }

    private WorldPoint maybeApplyCorridorPick(Leg.Walk leg, WorldPoint pos, WorldPoint originalPick)
    {
        if (!corridorWalkingEnabled || corridorRadius <= 0)
        {
            return originalPick;
        }

        Random rng = ThreadLocalRandom.current();

        // v2: snapshot a Reachability map from the player and compose
        // the existing walkableProbe with two BFS gates — candidate
        // must be in the visited set, and its hop-distance must be
        // within `centerlineDist + 1` (no detours). Tolerance of 1
        // absorbs BFS-vs-server tile-ordering quirks. Snapshot is
        // cheap (~256 expansions at depth 16) and runs on the client
        // thread because canTravelInDirection reads collision flags
        // from the loaded scene.
        final ReachabilityMap reach = onClient(() -> {
            WorldView wv = client == null ? null : client.getTopLevelWorldView();
            if (wv == null) return null;
            return Reachability.compute(wv, pos);
        });
        final int centerlineDist = reach == null ? -1 : reach.distance(originalPick);

        Predicate<WorldPoint> compoundProbe = candidate -> {
            if (walkableProbe != null && !walkableProbe.test(candidate)) return false;
            if (reach == null) return true;            // can't validate, allow
            if (centerlineDist < 0) return true;       // centerline itself off-BFS, allow
            if (!reach.isReachable(candidate)) return false;
            return reach.distance(candidate) <= centerlineDist + 1;
        };

        Optional<WorldPoint> candidate = pickCorridorTile(
            leg,
            pos,
            originalPick,
            corridorRadius,
            rng,
            compoundProbe);

        boolean chancePasses = corridorChancePercent >= 100
            || (corridorChancePercent > 0 && rng.nextInt(100) < corridorChancePercent);
        boolean accepted = candidate.isPresent() && chancePasses && !corridorDebugOnly;

        // Only INFO-log when we actually swapped the pick — that's a
        // behavior-changing decision worth seeing in normal operation.
        // The compute-and-skip path runs on every walk click and is only
        // useful when actively debugging the corridor selector.
        if (accepted)
        {
            log.info("trail-walker: corridor APPLIED originalPick={} selected={} radius={} chance={}",
                originalPick, candidate.get(),
                corridorRadius, corridorChancePercent);
            return candidate.get();
        }
        if (log.isDebugEnabled())
        {
            log.debug("trail-walker: corridor skipped originalPick={} candidate={} radius={} chance={} debugOnly={} chancePassed={}",
                originalPick,
                candidate.map(Object::toString).orElse("none"),
                corridorRadius, corridorChancePercent,
                corridorDebugOnly, chancePasses);
        }
        return originalPick;
    }

    private static int chebyshev(WorldPoint a, WorldPoint b)
    {
        return Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getY() - b.getY()));
    }

    private void onLegAdvancedReset(long now)
    {
        lastWalkPick = null;
        bestDistToPick = Integer.MAX_VALUE;
        lastProgressMs = now;
        rotatedThisLeg = false;
        TrailOverlay.publishCurrentPick(null);
    }

    private boolean isTileOnCanvas(WorldPoint tile)
    {
        return tile != null && onCanvasProbe.test(tile);
    }

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

    private void rotateCameraToActiveLeg()
    {
        if (currentPath == null || legIdx >= currentPath.size()) return;
        WorldPoint focus = legFocusTile(currentPath.legs().get(legIdx));
        if (focus == null) return;
        try
        {
            dispatcher.rotateCameraToward(focus);
        }
        catch (Throwable t)
        {
            log.debug("camera rotate failed", t);
        }
    }

    private boolean tryAdvanceToReachableTransport(WorldPoint pos, long now)
    {
        if (currentPath == null) return false;
        if (legIdx >= currentPath.size() - 1) return false;
        Leg cur = currentPath.legs().get(legIdx);
        if (!(cur instanceof Leg.Walk)) return false;
        Leg next = currentPath.legs().get(legIdx + 1);
        if (!(next instanceof Leg.Transport tr)) return false;
        if (pos.getPlane() != tr.tile().getPlane()) return false;

        int dist = chebyshev(pos, tr.tile());
        if (dist > TRANSPORT_DIRECT_CLICK_TILES) return false;

        TransportVerbState verbState = checkVerbPresence(tr.tile(), tr.verb());
        if (verbState != TransportVerbState.PRESENT) return false;

        if (!isTileOnCanvas(tr.tile()))
        {
            log.debug("trail-walker: handoff deferred — transport tile {} not on-canvas yet (verb={} dist={})",
                tr.tile(), tr.verb(), dist);
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

    /** v2 transport flow — opportunistic, visibility-gated, with one
     *  camera rotation per leg before falling back to walking closer.
     *
     *  <p>Old v1 rule: walk until Chebyshev≤1, then click.
     *  New v2 rule: click as soon as the matched object is reachable AND
     *  visible AND within {@link #MAX_TRANSPORT_CLICK_DISTANCE}. The OSRS
     *  server pathfinds and triggers the verb; saves the dead time the v1
     *  rule spent on tail-end micro-walks toward the transport tile. */
    private Status handleTransportLeg(Leg.Transport leg, WorldPoint pos, long now)
        throws InterruptedException
    {
        WorldPoint t = leg.tile();
        if (dispatcher.isBusy()) return Status.IN_PROGRESS;

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
            log.debug("trail-walker: verb '{}' not found at {} (tick {}/{}) — waiting for object",
                leg.verb(), t, transportVerbMissingTicks, TRANSPORT_VERB_GRACE_TICKS);
            return Status.IN_PROGRESS;
        }
        transportVerbMissingTicks = 0;

        int distToTransport = chebyshev(pos, t);
        boolean withinClickRange = distToTransport <= MAX_TRANSPORT_CLICK_DISTANCE;
        boolean adjacent = distToTransport <= TRANSPORT_ADJACENCY;

        // Walk closer when too far OR when verb state is unknown (resolver
        // couldn't see the tile yet — scene streaming race). Adjacent +
        // UNKNOWN is the v1 fallback case: trust the recorded verb,
        // click anyway, skip the visibility gate (the hull lookup would
        // also have returned null for the same reason as the verb miss).
        if (!adjacent && (verbState == TransportVerbState.UNKNOWN || !withinClickRange))
        {
            walkCloserToTransport(t, leg.verb(), now, distToTransport, verbState,
                "out-of-range-or-unknown");
            return Status.IN_PROGRESS;
        }

        // From here we're either: verb PRESENT + withinClickRange, OR
        // adjacent (regardless of verb state). The latter falls through
        // to the click using the recorded verb without a visibility check.
        boolean trustRecordedAdjacent = adjacent && verbState != TransportVerbState.PRESENT;

        if (!trustRecordedAdjacent)
        {
            // Standard v2 path: snapshot hull + reach + run visibility,
            // ALL in one client-thread hop. {@code whyHidden} reads
            // viewport, menu, varbits, HUD widgets — every one of those
            // asserts the client thread under -ea. Splitting snapshot
            // (on-client) from whyHidden (off-client, the bug we hit)
            // would trip the assertion the moment a real visibility
            // pipeline ran. Mirrors ChickenCombatLoop's selector.pick
            // pattern that wraps the visibility call inside onClient.
            // Pass `pos` directly (already client-thread-read at top of
            // tick) — never hand a live Player to the visibility check.
            // VisResult conveys both "snapshot was unresolvable" and
            // "visible/why-hidden" outcomes back from a single client-thread
            // hop. Returning a sentinel from {@code onClient} (which itself
            // can return null on interrupt) would conflate three states;
            // a tiny holder keeps each one explicit.
            VisResult vr = onClient(() -> {
                TransportSnapshot snap = snapshotTransport(t, leg.verb(), pos);
                if (snap == null) return VisResult.UNRESOLVED;
                ObjectVisibility.Reason r = objectVisibility.whyHidden(
                    t, snap.hull, pos, snap.reach);
                return r == null ? VisResult.VISIBLE : VisResult.hidden(r);
            });
            // onClient returned null = thread was interrupted; bail.
            if (vr == null || vr == VisResult.UNRESOLVED)
            {
                return Status.IN_PROGRESS;
            }
            ObjectVisibility.Reason reason = vr.reason();
            if (reason != null)
            {
                if (reason.fixableByRotation() && !rotatedThisLeg)
                {
                    log.info("trail-walker: rotating camera toward transport {} (reason={}, dist={})",
                        t, reason, distToTransport);
                    try
                    {
                        dispatcher.rotateCameraToward(t, true);
                    }
                    catch (Throwable th)
                    {
                        log.debug("trail-walker: rotateCameraToward failed", th);
                    }
                    rotatedThisLeg = true;
                    return Status.IN_PROGRESS;
                }

                // Either rotation can't fix it (HUD / menu / unreachable / plane)
                // OR rotation already attempted and the visibility check still
                // failed. Walk closer and try again next tick.
                walkCloserToTransport(t, leg.verb(), now, distToTransport, verbState,
                    "vis=" + reason + (rotatedThisLeg ? " (post-rotate)" : ""));
                return Status.IN_PROGRESS;
            }
        }

        // Visible (or adjacent-trust fallback) — settle on idle pose, then click.
        if (now - lastInteractMs < INTERACT_THROTTLE_MS) return Status.IN_PROGRESS;
        Boolean settled = onClient(() -> {
            Player self = client.getLocalPlayer();
            return self != null && self.getPoseAnimation() == self.getIdlePoseAnimation();
        });
        if (settled == null || !settled) return Status.IN_PROGRESS;

        log.info("trail-walker: CLICK_GAME_OBJECT verb '{}' tile {} id {} (dist={}, mode={})",
            leg.verb(), t, leg.objectId(), distToTransport,
            trustRecordedAdjacent ? "adjacent-trust" : "opportunistic");
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

    /** Snapshot taken on the client thread before the visibility check.
     *  Bundles the resolved object's hull (or null if no object matched
     *  the recorded verb) and a fresh BFS reachability map from the
     *  player's tile. The player's WorldPoint is NOT included here —
     *  callers already have it from {@code readPlayerTile()} and we
     *  must not pass a live {@code Player} reference into a worker-
     *  thread context (its accessors assert client thread). */
    private static final class TransportSnapshot
    {
        @Nullable final Shape hull;
        @Nullable final ReachabilityMap reach;
        TransportSnapshot(@Nullable Shape hull, @Nullable ReachabilityMap reach)
        {
            this.hull = hull; this.reach = reach;
        }
    }

    /** Three-way visibility outcome bundled into a single value so the
     *  on-client hop in {@link #handleTransportLeg} can return all three
     *  cases (unresolved / visible / hidden-with-reason) without
     *  overloading null. The two non-hidden states are singletons; only
     *  identity comparison is used (see {@code vr == UNRESOLVED}). */
    private static final class VisResult
    {
        static final VisResult UNRESOLVED = new VisResult(null);
        static final VisResult VISIBLE    = new VisResult(null);

        @Nullable private final ObjectVisibility.Reason reason;

        private VisResult(@Nullable ObjectVisibility.Reason r) { this.reason = r; }
        static VisResult hidden(ObjectVisibility.Reason r) { return new VisResult(r); }
        @Nullable ObjectVisibility.Reason reason() { return reason; }
    }

    /** Client-thread: resolve the transport object on {@code tile} and
     *  pull its convex hull. Walls / game objects / decorative / ground
     *  each have their own {@code getConvexHull()} accessor; the {@code
     *  TransportResolver.Match} encodes which one matched. */
    @Nullable
    private TransportSnapshot snapshotTransport(WorldPoint tile, String verb, WorldPoint playerPos)
    {
        if (client == null) return null;

        Shape hull = null;
        if (transportResolver != null)
        {
            try
            {
                TransportResolver.Match m = transportResolver.findTransport(tile, verb);
                if (m != null && m.isSuccess())
                {
                    if (m.wallObject() != null)            hull = m.wallObject().getConvexHull();
                    else if (m.gameObject() != null)       hull = m.gameObject().getConvexHull();
                    else if (m.decorativeObject() != null) hull = m.decorativeObject().getConvexHull();
                    else if (m.groundObject() != null)     hull = m.groundObject().getConvexHull();
                }
            }
            catch (Throwable th)
            {
                log.debug("trail-walker: snapshotTransport resolver threw", th);
            }
        }

        WorldView wv = client.getTopLevelWorldView();
        ReachabilityMap reach = wv == null ? null : Reachability.compute(wv, playerPos);
        return new TransportSnapshot(hull, reach);
    }

    /** Issue a WALK toward the transport tile. Throttled by
     *  {@link #shouldClick}. Extracted from {@link #handleTransportLeg}
     *  so the v2 fall-back branches share one implementation. */
    private void walkCloserToTransport(WorldPoint t, String verb, long now,
                                       int dist, TransportVerbState verbState,
                                       String reason)
        throws InterruptedException
    {
        if (!shouldClick(now, t)) return;
        log.info("trail-walker: walk-to-transport {} (verb {}, dist={}, verbState={}, why={})",
            t, verb, dist, verbState, reason);
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

    enum TransportVerbState { PRESENT, MISSING, UNKNOWN }

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
                return TransportVerbState.MISSING;
            }
            catch (Throwable th)
            {
                return TransportVerbState.UNKNOWN;
            }
        });
        return s == null ? TransportVerbState.UNKNOWN : s;
    }

    private void advanceLegAfterAlreadyDone()
    {
        legIdx = Math.min(legIdx + 1, currentPath == null ? legIdx + 1 : currentPath.size());
        lastWalkPick = null;
        lastClickLegIdx = -1;
        transportVerbMissingTicks = 0;
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
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        clientThread.invokeLater(() -> {
            try
            {
                ref.set(sup.get());
            }
            catch (Throwable th)
            {
                log.warn("trail-walker: onClient threw", th);
            }
            finally
            {
                latch.countDown();
            }
        });
        try
        {
            latch.await();
        }
        catch (InterruptedException ie)
        {
            Thread.currentThread().interrupt();
            return null;
        }
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
