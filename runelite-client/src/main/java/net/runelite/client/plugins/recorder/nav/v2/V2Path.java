package net.runelite.client.plugins.recorder.nav.v2;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.worldmap.TransportEdge;

/** A complete plan from a start tile to a destination tile, decomposed
 *  into walk legs and transport legs.
 *
 *  <p>Lane 5 alignment to spec §3: this class now implements the spec
 *  contract methods {@link #steps()}, {@link #id()},
 *  {@link #planEpochMs()}, while preserving the legacy {@link #legs()}
 *  + {@link #routeId()} + {@link #totalCost()} surface so callers that
 *  haven't migrated keep compiling. The {@code steps()} view is a
 *  derived list of {@link PathStep}-typed adapters over the existing
 *  {@code V2Leg.Walk} / {@code V2Leg.Transport} legs.
 *
 *  <p>{@link #routeId} is a stable hash over the leg sequence
 *  (transport-edge keys + walk start/end tiles). Two paths with the
 *  same routeId are interchangeable from {@link
 *  net.runelite.client.plugins.recorder.nav.v2.RouteHistory}'s point
 *  of view. The hash deliberately ignores intermediate walk tiles —
 *  two A* runs with different tile-level noise on the same macro
 *  route share a routeId. */
public final class V2Path
{
    private final List<V2Leg> legs;
    private final int totalCost;
    private final String routeId;
    /** Spec §3 cached derived view — list of typed {@link PathStep}s
     *  built lazily on first {@link #steps()} call. */
    private volatile List<PathStep> stepsCache;
    /** Plan creation epoch ms (used by Lane 6 trace correlation). */
    private final long planEpochMs;

    public V2Path(List<V2Leg> legs, int totalCost)
    {
        if (legs == null) throw new IllegalArgumentException("legs null");
        this.legs = List.copyOf(legs);
        this.totalCost = totalCost;
        this.routeId = computeRouteId(this.legs);
        this.planEpochMs = System.currentTimeMillis();
    }

    public List<V2Leg> legs() { return legs; }
    public int totalCost() { return totalCost; }
    public String routeId() { return routeId; }
    public boolean isEmpty() { return legs.isEmpty(); }

    /** Spec §3 contract: typed step view of the path. Walk legs become
     *  {@link WalkStep}s wrapping a default {@link Waypoint} at the
     *  leg's end tile; transport legs become {@link TransportStep}s
     *  wrapping a {@link TransportLeg} adapter over the existing
     *  {@link TransportEdge}. */
    public List<PathStep> steps()
    {
        List<PathStep> cached = stepsCache;
        if (cached != null) return cached;
        List<PathStep> built = new ArrayList<>(legs.size());
        for (V2Leg leg : legs)
        {
            if (leg instanceof V2Leg.Walk w)
            {
                built.add(new WalkStepAdapter(w));
            }
            else if (leg instanceof V2Leg.Transport t)
            {
                built.add(new TransportStepAdapter(t));
            }
        }
        List<PathStep> immutable = List.copyOf(built);
        stepsCache = immutable;
        return immutable;
    }

    /** Spec §3 contract: stable {@link PathId} for this path. Derived
     *  from {@link #routeId()} so legacy and new APIs agree. */
    public PathId id() { return PathId.of(routeId); }

    /** Spec §3 contract: epoch ms when this path was constructed.
     *  Lane 6 uses this for tick-trace correlation. */
    public long planEpochMs() { return planEpochMs; }

    /** All tiles in the path, in execution order. Walk-leg tiles are
     *  inlined; transport legs contribute their fromTile and toTile.
     *  Useful for the minimap overlay's blue-route paint. */
    public List<WorldPoint> allTiles()
    {
        return legs.stream()
            .flatMap(leg -> {
                if (leg instanceof V2Leg.Walk w) return w.tiles().stream();
                if (leg instanceof V2Leg.Transport t)
                    return java.util.stream.Stream.of(t.edge().fromTile(), t.edge().toTile());
                return java.util.stream.Stream.empty();
            })
            .collect(Collectors.toUnmodifiableList());
    }

    /** Stable hash over the leg sequence. Walk legs contribute their
     *  start, midpoint, and end — each bucketed to a 4-tile grid so
     *  small tile-level wiggle (noisy A* runs picking slightly
     *  different diagonals) maps to the same routeId, but two
     *  genuinely different corridors (e.g. north vs south of the
     *  Lumby church) produce different routeIds because their
     *  midpoints fall in different buckets. Transport legs contribute
     *  the full {@link TransportEdge#key()}.
     *
     *  <p>Bucket size 4 was picked empirically for the chicken-pen
     *  fixtures: two parallel corridors 3 tiles apart still bucket
     *  apart (3210>>2 = 802, 3213>>2 = 803), while a 1-tile diagonal
     *  noise variant of the same corridor stays in one bucket.
     *
     *  <p>SHA-1 hex truncated to 16 chars — collision probability is
     *  negligible for the small route sets V2 produces. */
    private static String computeRouteId(List<V2Leg> legs)
    {
        StringBuilder sb = new StringBuilder();
        for (V2Leg leg : legs)
        {
            if (leg instanceof V2Leg.Walk w)
            {
                sb.append("W|").append(w.regionId()).append('|');
                WorldPoint mid = w.tiles().get(w.tiles().size() / 2);
                sb.append(bucket(w.start())).append('-')
                  .append(bucket(mid)).append('-')
                  .append(bucket(w.end()));
            }
            else if (leg instanceof V2Leg.Transport t)
            {
                sb.append("T|").append(t.edge().key());
            }
            sb.append(';');
        }
        try
        {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8 && i < digest.length; i++)
                hex.append(String.format("%02x", digest[i] & 0xff));
            return hex.toString();
        }
        catch (NoSuchAlgorithmException e)
        {
            // SHA-1 is in every JRE; fall back to the raw string anyway.
            return Integer.toHexString(sb.toString().hashCode());
        }
    }

    private static String bucket(WorldPoint p)
    {
        return (p.getX() >> 2) + "," + (p.getY() >> 2) + "," + p.getPlane();
    }

    public static final V2Path EMPTY = new V2Path(List.of(), 0);

    // ─────────────────────────────────────────────────────────────────
    // Spec §3 step adapters — view existing V2Leg.{Walk,Transport} as
    // typed PathStep / WalkStep / TransportStep.
    // ─────────────────────────────────────────────────────────────────

    /** Adapter wrapping a {@link V2Leg.Walk} as a {@link WalkStep}.
     *  The waypoint emitted carries the leg's end tile at tolerance
     *  radius 1 — matching the executor's existing "chebyshev <= 1
     *  means arrived" check. Lane 4's real planner will emit waypoints
     *  with appropriate radii. */
    private static final class WalkStepAdapter implements WalkStep
    {
        private final V2Leg.Walk walk;
        private final Waypoint waypoint;

        WalkStepAdapter(V2Leg.Walk walk)
        {
            this.walk = walk;
            WorldPoint endTile = walk.end();
            this.waypoint = new Waypoint()
            {
                @Override public WorldPoint target() { return endTile; }
                @Override public int toleranceRadius() { return 1; }
                @Override public WaypointType type() { return WaypointType.WALK; }
            };
        }

        @Override public Waypoint waypoint() { return waypoint; }

        /** Escape hatch for the executor during the transition — exposes
         *  the underlying legacy walk leg so the existing canvas-picker
         *  + minimap-fallback logic can consume the tile list directly. */
        public V2Leg.Walk legacyWalk() { return walk; }
    }

    /** Adapter wrapping a {@link V2Leg.Transport} as a
     *  {@link TransportStep}. Synthesises a {@link TransportLeg} view
     *  over the existing {@link TransportEdge}. */
    private static final class TransportStepAdapter implements TransportStep
    {
        private final V2Leg.Transport transport;
        private final TransportLeg leg;

        TransportStepAdapter(V2Leg.Transport transport)
        {
            this.transport = transport;
            TransportEdge edge = transport.edge();
            WorldPoint approachTile = edge.approachTile() != null
                ? edge.approachTile() : edge.fromTile();
            int regionId = edge.regionId();
            this.leg = new TransportLeg()
            {
                @Override public WorldPoint from() { return edge.fromTile(); }
                @Override public WorldPoint to() { return edge.toTile(); }
                @Override public TransportType type() { return TransportType.OBJECT_VERB; }
                @Override public java.util.Optional<Integer> objectId()
                { return java.util.Optional.of(edge.objectId()); }
                @Override public java.util.Optional<String> action()
                { return java.util.Optional.ofNullable(edge.verb()); }
                @Override public WorldPoint approach() { return approachTile; }
                @Override public int regionId() { return regionId; }
            };
        }

        @Override public TransportLeg transport() { return leg; }

        /** Escape hatch for the executor during the transition — the
         *  legacy {@link V2Leg.Transport} carries the {@link
         *  TransportEdge} the existing dispatcher integration expects. */
        public V2Leg.Transport legacyTransport() { return transport; }
    }

    /** Returns the legacy {@link V2Leg.Walk} backing a {@link WalkStep},
     *  if this step was created by {@link #steps()} on a {@code V2Path}.
     *  Used by the executor during the migration window to consume the
     *  full tile list. Returns null for non-adapter steps. */
    public static V2Leg.Walk legacyWalkOf(WalkStep step)
    {
        if (step instanceof WalkStepAdapter wa) return wa.legacyWalk();
        return null;
    }

    /** Returns the legacy {@link V2Leg.Transport} backing a
     *  {@link TransportStep}, or null. */
    public static V2Leg.Transport legacyTransportOf(TransportStep step)
    {
        if (step instanceof TransportStepAdapter ta) return ta.legacyTransport();
        return null;
    }
}
