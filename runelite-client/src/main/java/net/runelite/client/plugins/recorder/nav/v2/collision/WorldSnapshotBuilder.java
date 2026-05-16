package net.runelite.client.plugins.recorder.nav.v2.collision;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.WorldType;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.recorder.nav.v2.predicate.PredicateRegistry;

/** Builds an immutable {@link WorldSnapshot} at one client-thread instant.
 *
 *  <p>Two entry points:
 *  <ul>
 *    <li>{@link #fromComponents(CollisionView, Set, Set, Object, PredicateRegistry, long)}
 *        — accepts pre-captured pieces; useful for tests and for callers
 *        that already hold the client thread.
 *    <li>{@link #fromClient(Client, ClientThread, Object, PredicateRegistry, BlockingActorPolicy)}
 *        — does the full marshalled capture: live collision overlay,
 *        actor occupancy, object occupancy, time.
 *  </ul>
 *
 *  <p>{@link BlockingActorPolicy} is the configurable seam for "what
 *  counts as a blocking NPC". The default policy ({@link
 *  BlockingActorPolicy#NONE}) treats no NPCs as blockers — Lane 6
 *  will surface specific NPCs that should be added (e.g. cows in a
 *  pen) from acceptance failures.
 *
 *  <p>Threading: {@link #fromClient} accepts non-client-thread callers
 *  and marshalls the reads via {@link ClientThread#invoke(Runnable)}.
 *  Per CLAUDE.md threading rules, this read MUST happen on the client
 *  thread — we don't ship work that depends on the runtime guard
 *  catching it. */
@Slf4j
public final class WorldSnapshotBuilder
{
    /** Policy for "what counts as a movement-blocking NPC". */
    public interface BlockingActorPolicy
    {
        boolean isBlocking(NPC npc);

        BlockingActorPolicy NONE = npc -> false;
    }

    private WorldSnapshotBuilder() {}

    /** Builds a snapshot from already-captured pieces. Used by tests and
     *  by callers that already hold the client thread (e.g. a planner
     *  that pre-captures its inputs). Does NOT touch {@link Client}.
     *
     *  <p>Calls {@link #fromComponents(CollisionView, Set, Set, Object,
     *  PredicateRegistry, long, WorldPoint)} with a null player position.
     *  This entry remains compatible with pre-integration tests; new
     *  callers (Lane 4 planner / integration) should pass the player
     *  position via the 7-arg overload. */
    public static WorldSnapshot fromComponents(
        CollisionView view,
        Set<WorldPoint> actorTiles,
        Set<WorldPoint> objectTiles,
        @Nullable Object transports,
        PredicateRegistry predicates,
        long capturedAtMs)
    {
        return fromComponents(view, actorTiles, objectTiles, transports,
            predicates, capturedAtMs, /*playerPosition*/ null);
    }

    /** 7-arg overload that includes the player position. Integration
     *  callers (Lane 4 {@code WaypointPlanner}, Lane 5 {@code V2Navigator})
     *  use this entry so the planner can read {@link
     *  WorldSnapshot#playerPosition()} without taking a separate
     *  {@code start} parameter. */
    public static WorldSnapshot fromComponents(
        CollisionView view,
        Set<WorldPoint> actorTiles,
        Set<WorldPoint> objectTiles,
        @Nullable Object transports,
        PredicateRegistry predicates,
        long capturedAtMs,
        @Nullable WorldPoint playerPosition)
    {
        if (view == null) throw new IllegalArgumentException("view");
        if (actorTiles == null) throw new IllegalArgumentException("actorTiles");
        if (objectTiles == null) throw new IllegalArgumentException("objectTiles");
        if (predicates == null) throw new IllegalArgumentException("predicates");
        return new WorldSnapshotImpl(
            view,
            Collections.unmodifiableSet(new HashSet<>(actorTiles)),
            Collections.unmodifiableSet(new HashSet<>(objectTiles)),
            transports,
            predicates,
            capturedAtMs,
            playerPosition);
    }

    /** Full capture on the client thread. If the caller is not the client
     *  thread, marshalls via {@link ClientThread#invoke(Runnable)} and
     *  blocks until the snapshot is built. */
    public static WorldSnapshot fromClient(
        Client client,
        ClientThread clientThread,
        GlobalCollisionSnapshot global,
        @Nullable Object transports,
        PredicateRegistry predicates,
        BlockingActorPolicy actorPolicy)
    {
        if (client == null) throw new IllegalArgumentException("client");
        if (clientThread == null) throw new IllegalArgumentException("clientThread");
        if (global == null) throw new IllegalArgumentException("global");
        if (predicates == null) throw new IllegalArgumentException("predicates");
        BlockingActorPolicy policy = actorPolicy == null ? BlockingActorPolicy.NONE : actorPolicy;

        if (client.isClientThread())
        {
            return captureOnClientThread(client, global, transports, predicates, policy);
        }

        // Off-client marshall — block until the runnable executes.
        final WorldSnapshot[] result = new WorldSnapshot[1];
        final Throwable[] error = new Throwable[1];
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

        clientThread.invoke(() ->
        {
            try
            {
                result[0] = captureOnClientThread(client, global, transports, predicates, policy);
            }
            catch (Throwable t)
            {
                error[0] = t;
            }
            finally
            {
                latch.countDown();
            }
        });

        try
        {
            // Generous: 2s. The client thread budget per spec §10 is ~10ms,
            // so 2s only matters under extreme client hang.
            if (!latch.await(2000, java.util.concurrent.TimeUnit.MILLISECONDS))
            {
                throw new IllegalStateException("WorldSnapshot capture timed out (2s)");
            }
        }
        catch (InterruptedException ie)
        {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("WorldSnapshot capture interrupted", ie);
        }
        if (error[0] != null)
        {
            throw new IllegalStateException("WorldSnapshot capture failed", error[0]);
        }
        return result[0];
    }

    /** Capture the snapshot — must be called on the client thread. */
    private static WorldSnapshot captureOnClientThread(
        Client client,
        GlobalCollisionSnapshot global,
        @Nullable Object transports,
        PredicateRegistry predicates,
        BlockingActorPolicy policy)
    {
        assert client.isClientThread() : "captureOnClientThread off client thread";

        WorldView wv = client.getTopLevelWorldView();
        int baseX = wv != null ? wv.getBaseX() : 0;
        int baseY = wv != null ? wv.getBaseY() : 0;
        int plane = wv != null ? wv.getPlane() : 0;
        LiveSceneCollisionOverlay overlay = LiveSceneCollisionOverlay.capture(
            wv != null ? wv.getCollisionMaps() : null, baseX, baseY, plane);

        CollisionView view = new CollisionView(global, overlay);

        Set<WorldPoint> actorTiles = new HashSet<>();
        if (wv != null)
        {
            for (NPC npc : wv.npcs())
            {
                if (npc == null) continue;
                if (!policy.isBlocking(npc)) continue;
                WorldPoint loc = npc.getWorldLocation();
                if (loc != null) actorTiles.add(loc);
            }
        }

        // Object occupancy: we don't iterate Tile.getGameObjects() here
        // because object collision is already reflected in the live
        // CollisionData[] array (the engine writes BLOCK_MOVEMENT_OBJECT
        // when a game object lands on a tile). Sustaining an explicit
        // set of object-blocking tiles would duplicate the data and
        // introduce drift; Lane 6 inspects {@code source()} on the
        // collision view to attribute object blocks back to the live
        // overlay.
        //
        // The blockingObjectTiles() set is empty in the initial release
        // and grows as Lane 6 surfaces cases where object collision
        // and live overlay disagree.
        Set<WorldPoint> objectTiles = Set.of();

        // Capture the live player position. Null if the player isn't on
        // a loaded scene (e.g., login flow in progress); planner converts
        // null → REGION_NOT_LOADED replan reason.
        WorldPoint playerPosition = null;
        if (client.getLocalPlayer() != null)
        {
            playerPosition = client.getLocalPlayer().getWorldLocation();
        }

        return new WorldSnapshotImpl(
            view,
            Collections.unmodifiableSet(actorTiles),
            Collections.unmodifiableSet(new HashSet<>(objectTiles)),
            transports,
            predicates,
            System.currentTimeMillis(),
            playerPosition);
    }

    /** Concrete implementation of {@link WorldSnapshot}. Package-private. */
    static final class WorldSnapshotImpl implements WorldSnapshot
    {
        private final CollisionView view;
        private final Set<WorldPoint> actorTiles;
        private final Set<WorldPoint> objectTiles;
        @Nullable private final Object transports;
        private final PredicateRegistry predicates;
        private final long capturedAtMs;
        @Nullable private final WorldPoint playerPosition;

        WorldSnapshotImpl(
            CollisionView view,
            Set<WorldPoint> actorTiles,
            Set<WorldPoint> objectTiles,
            @Nullable Object transports,
            PredicateRegistry predicates,
            long capturedAtMs)
        {
            this(view, actorTiles, objectTiles, transports, predicates,
                capturedAtMs, /*playerPosition*/ null);
        }

        WorldSnapshotImpl(
            CollisionView view,
            Set<WorldPoint> actorTiles,
            Set<WorldPoint> objectTiles,
            @Nullable Object transports,
            PredicateRegistry predicates,
            long capturedAtMs,
            @Nullable WorldPoint playerPosition)
        {
            this.view = view;
            this.actorTiles = actorTiles;
            this.objectTiles = objectTiles;
            this.transports = transports;
            this.predicates = predicates;
            this.capturedAtMs = capturedAtMs;
            this.playerPosition = playerPosition;
        }

        @Override
        public CollisionFlags collisionAt(WorldPoint p)
        {
            return new CollisionFlags(view.flagsAt(p), view.source(p), p);
        }

        @Override
        public net.runelite.client.plugins.recorder.nav.v2.bfs.CollisionView collisionView()
        {
            return view;
        }

        @Override
        public Set<WorldPoint> blockingActorTiles()
        {
            return actorTiles;
        }

        @Override
        public Set<WorldPoint> blockingObjectTiles()
        {
            return objectTiles;
        }

        @Override
        public Object transports()
        {
            return transports;
        }

        @Override
        public PredicateRegistry predicates()
        {
            return predicates;
        }

        @Override
        public long capturedAtMs()
        {
            return capturedAtMs;
        }

        @Override
        @Nullable
        public WorldPoint playerPosition()
        {
            return playerPosition;
        }

        /** Helper for tests + log: indicate whether the player is on a
         *  members' world per {@link WorldType#MEMBERS}. Not part of
         *  WorldSnapshot — see {@link PlayerState} for capability state. */
        @SuppressWarnings("unused")
        static boolean isMembersWorld(Client client)
        {
            return client.getWorldType().contains(WorldType.MEMBERS);
        }
    }
}
