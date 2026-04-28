package net.runelite.client.plugins.recorder.walker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.transport.Waypoint;

/**
 * Scripter-facing path description. A {@code PathSpec} is an ordered
 * sequence of {@link Waypoint waypoints} — typically a mix of
 * {@link Waypoint.Kind#WALK_AREA} cells (the path follows the waypoint
 * areas) and {@link Waypoint.Kind#TRANSPORT} hops (climb-up, climb-down,
 * gates, agility shortcuts).
 *
 * <p>Build a spec via {@link #builder()}:
 * <pre>{@code
 * PathSpec lumbridgeBank = PathSpec.builder("lumbridge-bank-out")
 *     .walkNamed("castle-yard", new WorldArea(3219, 3217, 9, 4, 0))
 *     .walkNamed("bridge", new WorldArea(3237, 3225, 7, 2, 0))
 *     .gate(new WorldPoint(3243, 3236, 0))   // Goblin fence gate
 *     .walkNamed("approach", new WorldArea(3238, 3289, 3, 8, 0))
 *     .walkNamed("pen", new WorldArea(3225, 3290, 13, 12, 0))
 *     .build();
 * }</pre>
 *
 * <p>Reuses the existing {@link Waypoint} type so the rest of the codebase
 * (route file format, overlays, tests) does not have to learn a new model.
 * The builder is sugar that translates verb-shaped intentions
 * ({@link Builder#gate}, {@link Builder#climbUp}) into the right
 * {@code Waypoint.transport(...)} calls. The default verb whitelist for
 * gates is {@code Open} — the runtime walks through if a gate exposes
 * only {@code Close} (already-open) by deferring that decision to the
 * {@link UniversalWalker}.
 */
public final class PathSpec
{
    private final String name;
    private final List<Waypoint> waypoints;

    private PathSpec(String name, List<Waypoint> waypoints)
    {
        this.name = name;
        this.waypoints = Collections.unmodifiableList(new ArrayList<>(waypoints));
    }

    public static Builder builder() { return new Builder(null); }
    public static Builder builder(@Nullable String name) { return new Builder(name); }

    /** Wrap a pre-built list of waypoints. Use this when adapting an
     *  existing route file or porting a hand-coded path. */
    public static PathSpec of(@Nullable String name, List<Waypoint> waypoints)
    {
        if (waypoints == null) throw new IllegalArgumentException("waypoints null");
        return new PathSpec(name, waypoints);
    }

    @Nullable public String name() { return name; }
    public List<Waypoint> waypoints() { return waypoints; }
    public int size() { return waypoints.size(); }

    /** Fluent builder for path specs. Each method returns {@code this}. */
    public static final class Builder
    {
        private final String name;
        private final List<Waypoint> wps = new ArrayList<>();

        Builder(@Nullable String name) { this.name = name; }

        /** Add an unnamed walk-area waypoint. */
        public Builder walk(WorldArea area)
        {
            wps.add(Waypoint.walkArea(null, area));
            return this;
        }

        /** Add a named walk-area waypoint — the name shows up in walker
         *  log lines and overlays. */
        public Builder walk(String name, WorldArea area)
        {
            wps.add(Waypoint.walkArea(name, area));
            return this;
        }

        /** Walk into an irregular tile set (not necessarily rectangular).
         *  Useful for non-rectangular safe-spots, alcoves, or any cell
         *  shape carved out by the annotator. */
        public Builder walkTiles(@Nullable String name, Set<WorldPoint> tiles)
        {
            wps.add(Waypoint.walkArea(name, tiles));
            return this;
        }

        /** Add a single-tile WALK waypoint. The walker still navigates to
         *  the tile via Reachability + the closest-projecting tile heuristic;
         *  use this when the path passes through a specific chokepoint. */
        public Builder walkTile(WorldPoint tile)
        {
            wps.add(Waypoint.walk(tile));
            return this;
        }

        /** Climb-up transport (stairs, ladders going up). The walker clicks
         *  the matched object and waits for the plane to change. */
        public Builder climbUp(WorldPoint tile)
        {
            wps.add(Waypoint.transport(tile, Waypoint.TransportKind.CLIMB_UP, "Climb-up"));
            return this;
        }

        /** Climb-down transport. */
        public Builder climbDown(WorldPoint tile)
        {
            wps.add(Waypoint.transport(tile, Waypoint.TransportKind.CLIMB_DOWN, "Climb-down"));
            return this;
        }

        /** A gate (or door, or fence with a swing). The runtime issues
         *  "Open" — if the gate is already open (only "Close" verb visible),
         *  the walker counts adjacency as arrival and walks through without
         *  clicking. */
        public Builder gate(WorldPoint tile)
        {
            wps.add(Waypoint.transport(tile, Waypoint.TransportKind.OPEN, "Open"));
            return this;
        }

        /** Generic transport for any verb the walker doesn't have a
         *  dedicated helper for (shortcuts, charters, agility verbs).
         *  The walker treats this exactly the same as climbUp/climbDown:
         *  click the object, wait for the player to move past the tile. */
        public Builder transport(WorldPoint tile, String verb)
        {
            if (verb == null || verb.isBlank())
                throw new IllegalArgumentException("transport verb empty");
            wps.add(Waypoint.transport(tile, Waypoint.TransportKind.INTERACT, verb));
            return this;
        }

        public PathSpec build() { return new PathSpec(name, wps); }
    }
}
