package net.runelite.client.plugins.recorder.nav.v2.qc;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.nav.BehaviorMode;
import net.runelite.client.plugins.recorder.nav.NavRequest;
import net.runelite.client.plugins.recorder.nav.v2.qc.contracts.CollisionFlags;
import net.runelite.client.plugins.recorder.nav.v2.qc.contracts.NavigationContext;
import net.runelite.client.plugins.recorder.nav.v2.qc.contracts.PathContext;
import net.runelite.client.plugins.recorder.nav.v2.qc.contracts.PlayerState;
import net.runelite.client.plugins.recorder.nav.v2.qc.contracts.PredicateRegistry;
import net.runelite.client.plugins.recorder.nav.v2.qc.contracts.TilePredicate;
import net.runelite.client.plugins.recorder.nav.v2.qc.contracts.TransportLeg;
import net.runelite.client.plugins.recorder.nav.v2.qc.contracts.TransportTable;
import net.runelite.client.plugins.recorder.nav.v2.qc.contracts.V2Path;
import net.runelite.client.plugins.recorder.nav.v2.qc.contracts.Waypoint;
import net.runelite.client.plugins.recorder.nav.v2.qc.contracts.WorldSnapshot;

/** Static factory for known-good acceptance-test fixtures.
 *
 *  <p>Each {@code Scenario} carries a {@link NavRequest} + a
 *  {@link WorldSnapshot} + a {@link PlayerState} + the {@code start}
 *  tile (the simulated player position at run entry). Lane-6
 *  acceptance tests invoke
 *  {@link NavigationTestHarness#runRoute(NavRequest, WorldSnapshot, PlayerState, net.runelite.client.plugins.recorder.nav.v2.qc.contracts.BfsConfig)}
 *  with these.
 *
 *  <p>All non-{@code bankPenStart()} fixtures are synthetic
 *  hand-built scenarios — large enough to exercise the planner but
 *  small enough to read by eye. {@code bankPenStart()} pulls real
 *  recorded data from {@code ~/.runelite/recorder/worldmap/} (when
 *  present) for Test 8 regression. */
public final class GoldenRouteFixtures
{
    private GoldenRouteFixtures() {}

    /** A scenario carries everything an acceptance test needs to set up
     *  one harness run. */
    public record Scenario(
        String name,
        NavRequest request,
        WorldSnapshot snapshot,
        PlayerState player,
        WorldPoint startTile,
        WorldPoint targetTile)
    {
    }

    // -------- Test-1 fixture: same-region straight corridor --------

    /** Straight corridor on plane 0, 50 tiles east of start. No
     *  blockers, no transports. */
    public static Scenario straightCorridor()
    {
        WorldPoint start = new WorldPoint(3200, 3200, 0);
        WorldPoint end = new WorldPoint(3250, 3200, 0);
        Set<WorldPoint> walkable = rect(3195, 3195, 3255, 3205, 0);
        return new Scenario(
            "straightCorridor",
            NavRequest.toPoint(end, BehaviorMode.VARIED),
            new FakeWorldSnapshot(walkable, Set.of(), Set.of(), emptyTransports()),
            stubPlayer(),
            start, end);
    }

    // -------- Test-2 fixture: cross-region (Lumbridge to Draynor) --------

    /** Cross-region scenario carrying real OSRS region IDs. The Lane-4
     *  planner is expected to emit at least one {@code TransportStep}
     *  or one {@code REGION_BRIDGE} waypoint. */
    public static Scenario lumbridgeToDraynor()
    {
        WorldPoint start = new WorldPoint(3221, 3219, 0);
        WorldPoint end = new WorldPoint(3093, 3243, 0);
        // Mark a corridor connecting the two through Draynor road.
        Set<WorldPoint> walkable = new HashSet<>();
        walkable.addAll(rect(3085, 3215, 3225, 3250, 0));
        return new Scenario(
            "lumbridgeToDraynor",
            NavRequest.toPoint(end, BehaviorMode.VARIED),
            new FakeWorldSnapshot(walkable, Set.of(), Set.of(), emptyTransports()),
            stubPlayer(),
            start, end);
    }

    // -------- Test-3 fixture: transport approach at a gate --------

    /** A two-leg fixture: walk → gate → walk. Lane-4 planner must
     *  emit a {@code TRANSPORT_APPROACH} waypoint at the gate's
     *  approach tile. */
    public static Scenario transportApproachAtGate()
    {
        WorldPoint start = new WorldPoint(3200, 3200, 0);
        WorldPoint gateNear = new WorldPoint(3210, 3200, 0);
        WorldPoint gateFar = new WorldPoint(3211, 3200, 0);
        WorldPoint end = new WorldPoint(3220, 3200, 0);
        Set<WorldPoint> walkable = rect(3195, 3195, 3225, 3205, 0);
        // Mark gateNear→gateFar as gated: blocked except via transport.
        return new Scenario(
            "transportApproachAtGate",
            NavRequest.toPoint(end, BehaviorMode.VARIED),
            new FakeWorldSnapshot(walkable, Set.of(), Set.of(), emptyTransports()),
            stubPlayer(),
            start, end);
    }

    // -------- Test-4 fixture: single-tile blocker --------

    /** Same as {@link #straightCorridor()} but with one dynamic blocker
     *  on the ideal next tile. Lane-5 executor must sidestep, NOT
     *  trigger replan. */
    public static Scenario straightCorridorWithBlocker()
    {
        WorldPoint start = new WorldPoint(3200, 3200, 0);
        WorldPoint end = new WorldPoint(3250, 3200, 0);
        Set<WorldPoint> walkable = rect(3195, 3195, 3255, 3205, 0);
        Set<WorldPoint> blockers = Set.of(new WorldPoint(3203, 3200, 0));
        return new Scenario(
            "straightCorridorWithBlocker",
            NavRequest.toPoint(end, BehaviorMode.VARIED),
            new FakeWorldSnapshot(walkable, blockers, Set.of(), emptyTransports()),
            stubPlayer(),
            start, end);
    }

    // -------- Test-5 fixture: fully-blocked corridor --------

    /** Corridor where every tile in the current movement bucket is
     *  blocked by dynamic actors. Lane-5 executor must return
     *  {@code NEEDS_REPLAN} with {@code NO_LOCAL_WALKABLE_TILE}. */
    public static Scenario fullyBlockedCorridor()
    {
        WorldPoint start = new WorldPoint(3200, 3200, 0);
        WorldPoint end = new WorldPoint(3250, 3200, 0);
        Set<WorldPoint> walkable = rect(3195, 3195, 3255, 3205, 0);
        // Every tile in a 3×3 right of the player blocked by actors.
        Set<WorldPoint> blockers = new HashSet<>();
        for (int x = 3201; x <= 3204; x++)
            for (int y = 3198; y <= 3202; y++) blockers.add(new WorldPoint(x, y, 0));
        return new Scenario(
            "fullyBlockedCorridor",
            NavRequest.toPoint(end, BehaviorMode.VARIED),
            new FakeWorldSnapshot(walkable, blockers, Set.of(), emptyTransports()),
            stubPlayer(),
            start, end);
    }

    // -------- Test-6 fixture: custom predicate denies tile X --------

    public static Scenario corridorWithDeniedTile(WorldPoint denied)
    {
        WorldPoint start = new WorldPoint(3200, 3200, 0);
        WorldPoint end = new WorldPoint(3250, 3200, 0);
        Set<WorldPoint> walkable = rect(3195, 3195, 3255, 3205, 0);
        TilePredicate denier = new TilePredicate() {
            @Override public boolean accept(WorldPoint tile, PathContext ctx) { return !tile.equals(denied); }
            @Override public String id() { return "TestDenier"; }
        };
        return new Scenario(
            "corridorWithDeniedTile",
            NavRequest.toPoint(end, BehaviorMode.VARIED),
            new FakeWorldSnapshot(walkable, Set.of(), Set.of(),
                emptyTransports(), List.of(denier)),
            stubPlayer(),
            start, end);
    }

    // -------- Test-7 fixture: long corridor for compression --------

    /** Long open corridor — 300 tiles east. Lane 4's {@code PathCompressor}
     *  must emit 5–15 sparse waypoints, not 300. */
    public static Scenario longCorridorForCompression()
    {
        WorldPoint start = new WorldPoint(3000, 3200, 0);
        WorldPoint end = new WorldPoint(3300, 3200, 0);
        Set<WorldPoint> walkable = rect(2995, 3195, 3305, 3205, 0);
        return new Scenario(
            "longCorridorForCompression",
            NavRequest.toPoint(end, BehaviorMode.VARIED),
            new FakeWorldSnapshot(walkable, Set.of(), Set.of(), emptyTransports()),
            stubPlayer(),
            start, end);
    }

    // -------- Test-8 fixture: bank → pen using real recorded data --------

    /** Bank → pen scenario. Uses {@code ~/.runelite/recorder/worldmap/}
     *  when present (real data). Returns {@link Optional#empty()} when
     *  the data is not on disk so the test can be skipped on machines
     *  without the recorder. */
    public static Optional<Scenario> bankPenStart()
    {
        WorldPoint bank = new WorldPoint(3209, 3219, 2);
        WorldPoint pen = new WorldPoint(3235, 3295, 0);
        java.io.File root = new java.io.File(
            System.getProperty("user.home") + "/.runelite/recorder/worldmap");
        if (!root.isDirectory()) return Optional.empty();
        // Phase-1: scenario shape is enough — the real WorldSnapshot is
        // assembled by Lane 2's WorldSnapshotBuilder at runtime when
        // the live binding is wired. Use an empty snapshot as a
        // placeholder so the test can be authored against the contract.
        WorldSnapshot snap = new FakeWorldSnapshot(Set.of(), Set.of(), Set.of(),
            emptyTransports());
        return Optional.of(new Scenario(
            "bankPenStart",
            NavRequest.toPoint(pen, BehaviorMode.VARIED),
            snap,
            stubPlayer(),
            bank, pen));
    }

    // -------- helpers --------

    private static Set<WorldPoint> rect(int xMin, int yMin, int xMax, int yMax, int plane)
    {
        Set<WorldPoint> s = new HashSet<>();
        for (int x = xMin; x <= xMax; x++)
            for (int y = yMin; y <= yMax; y++)
                s.add(new WorldPoint(x, y, plane));
        return s;
    }

    private static TransportTable emptyTransports()
    {
        return new TransportTable() {
            @Override public List<TransportLeg> outgoingFrom(WorldPoint p) { return List.of(); }
            @Override public int totalLinks() { return 0; }
            @Override public int oneWayLinks() { return 0; }
            @Override public int twoWayLinks() { return 0; }
            @Override public int planeChangingLinks() { return 0; }
            @Override public int requirementGatedLinks() { return 0; }
        };
    }

    private static PlayerState stubPlayer()
    {
        return new PlayerState() {
            @Override public int skillLevel(Skill skill) { return 1; }
            @Override public int boostedLevel(Skill skill) { return 1; }
            @Override public int varbit(int varbitId) { return 0; }
            @Override public int varplayer(int varpId) { return 0; }
            @Override public ItemContainer inventory() { return null; }
            @Override public ItemContainer equipment() { return null; }
            @Override public boolean isMember() { return true; }
        };
    }

    /** Plain fake snapshot — Phase-1 placeholder until Lane 2's
     *  builder is wired. Tests use this to define a synthetic world
     *  with explicit walkable tiles / blocked tiles. */
    public static final class FakeWorldSnapshot implements WorldSnapshot
    {
        private final Set<WorldPoint> walkable;
        private final Set<WorldPoint> blockingActors;
        private final Set<WorldPoint> blockingObjects;
        private final TransportTable transports;
        private final List<TilePredicate> predicates;
        private final long capturedAt = System.currentTimeMillis();

        public FakeWorldSnapshot(Set<WorldPoint> walkable,
                                 Set<WorldPoint> blockingActors,
                                 Set<WorldPoint> blockingObjects,
                                 TransportTable transports)
        {
            this(walkable, blockingActors, blockingObjects, transports, List.of());
        }

        public FakeWorldSnapshot(Set<WorldPoint> walkable,
                                 Set<WorldPoint> blockingActors,
                                 Set<WorldPoint> blockingObjects,
                                 TransportTable transports,
                                 List<TilePredicate> predicates)
        {
            this.walkable = Collections.unmodifiableSet(new HashSet<>(walkable));
            this.blockingActors = Collections.unmodifiableSet(new HashSet<>(blockingActors));
            this.blockingObjects = Collections.unmodifiableSet(new HashSet<>(blockingObjects));
            this.transports = transports;
            this.predicates = List.copyOf(predicates);
        }

        public Set<WorldPoint> walkableTiles() { return walkable; }
        public List<TilePredicate> testPredicates() { return predicates; }

        @Override
        public CollisionFlags collisionAt(WorldPoint p)
        {
            CollisionFlags.Source src = walkable.isEmpty()
                ? CollisionFlags.Source.UNKNOWN
                : CollisionFlags.Source.GLOBAL_SNAPSHOT;
            // raw=0 ⇒ walkable in our test convention; raw=0xff ⇒ fully blocked.
            int raw = walkable.contains(p) ? 0 : 0xff;
            return new CollisionFlags(raw, src, p.getPlane());
        }

        @Override public Set<WorldPoint> blockingActorTiles() { return blockingActors; }
        @Override public Set<WorldPoint> blockingObjectTiles() { return blockingObjects; }
        @Override public TransportTable transports() { return transports; }
        @Override public PredicateRegistry predicates() {
            List<TilePredicate> preds = predicates;
            return new PredicateRegistry() {
                @Override public List<TilePredicate> active() { return preds; }
                @Override public Optional<TilePredicate> firstRejectorOf(WorldPoint tile, PathContext ctx) {
                    for (TilePredicate p : preds)
                        if (!p.accept(tile, ctx)) return Optional.of(p);
                    return Optional.empty();
                }
            };
        }
        @Override public long capturedAtMs() { return capturedAt; }
    }

    /** Helpers for tests that need to introspect a V2Path without
     *  Lane-4 wiring. Returns false when the path is null (a planner
     *  failure). */
    public static boolean pathHasTransportStep(V2Path p)
    {
        if (p == null) return false;
        for (var step : p.steps())
            if (step instanceof net.runelite.client.plugins.recorder.nav.v2.qc.contracts.TransportStep) return true;
        return false;
    }

    public static boolean pathContainsTile(V2Path p, WorldPoint tile)
    {
        if (p == null) return false;
        for (var step : p.steps())
        {
            if (step instanceof net.runelite.client.plugins.recorder.nav.v2.qc.contracts.WalkStep ws)
            {
                Waypoint w = ws.waypoint();
                if (w != null && tile.equals(w.target())) return true;
            }
        }
        return false;
    }

    /** Adapter for ad-hoc tests to wrap a single predicate as a
     *  one-element {@link PredicateRegistry}. */
    public static PredicateRegistry registryOf(TilePredicate... preds)
    {
        List<TilePredicate> list = List.of(preds);
        return new PredicateRegistry() {
            @Override public List<TilePredicate> active() { return list; }
            @Override public Optional<TilePredicate> firstRejectorOf(WorldPoint tile, PathContext ctx) {
                for (TilePredicate p : list)
                    if (!p.accept(tile, ctx)) return Optional.of(p);
                return Optional.empty();
            }
        };
    }

    /** Phase-1 minimal {@link NavigationContext} stub so tests can
     *  invoke {@code TransportRequirement.satisfiedBy(...)} or
     *  {@code TilePredicate.accept(...)} without a full plan binding. */
    public static NavigationContext minimalContext(WorldSnapshot snap,
                                                   PlayerState ps,
                                                   NavRequest req)
    {
        return new NavigationContext() {
            @Override public WorldSnapshot world() { return snap; }
            @Override public PlayerState player() { return ps; }
            @Override public NavRequest request() { return req; }
        };
    }
}
