# Walker framework — design (2026-04-28)

A reusable, two-tier walking framework for OSRS bots. Macro path supplied by
the scripter as a sequence of WorldArea / transport / gate steps; micro
stepping driven per-tick by an 8-connected BFS over engine collision flags
plus a click picker that prefers canvas, falls back to minimap.

## Package

`net.runelite.client.plugins.recorder.walker`

## Classes

### `Reachability` — pure BFS utility

```java
public final class Reachability {
    public static ReachabilityMap compute(WorldView wv, WorldPoint origin, int depth);
    public static ReachabilityMap compute(WorldView wv, WorldPoint origin, int depth,
                                          int[][] flagsOverride); // for tests
}

public final class ReachabilityMap {
    public WorldPoint origin();
    public int plane();
    public boolean isReachable(WorldPoint p);
    public int distance(WorldPoint p);                // -1 if unreachable
    public List<WorldPoint> pathTo(WorldPoint p);     // empty if unreachable
    public List<WorldPoint> reachableTiles();
    public List<WorldPoint> frontier();               // BFS-stopped boundary
}
```

8-connected (8 neighbour offsets), uses `WorldArea.canTravelInDirection` so
the engine's wall/diagonal flag matrix applies. Frontier = tiles whose 8
neighbour expansion was blocked by collision. Caller passes depth (typical
16-20). Pure logic; testable with a synthetic CollisionData mock.

### `StepClickPicker` — pick the best click target along a BFS

```java
public final class StepClickPicker {
    public StepClickPicker(Client client);
    public ClickTarget pick(ReachabilityMap reach, WorldArea targetArea);
    public ClickTarget pick(ReachabilityMap reach, WorldPoint targetTile);
}

public final class ClickTarget {
    public final WorldPoint tile;
    public final Point canvasPixel;
    public final boolean viaMinimap;
    public final int distanceFromTarget; // Chebyshev to target centre
}
```

Algorithm: among reachable tiles, pick the one closest by Chebyshev to the
target's centre that ALSO projects (canvas preferred for short-range,
minimap fallback within ~20 tiles). Mirrors `pickWalkTarget` in
`LumbridgeBankPenScript` but generalised. Must run on the client thread.

### `PathSpec` — scripter-facing data model

```java
public final class PathSpec {
    public static Builder builder();
    public static Builder builder(String name);
    public List<Waypoint> waypoints();
    public String name();
}

public static final class PathSpec.Builder {
    public Builder walk(WorldArea area);                          // WALK_AREA
    public Builder walk(String name, WorldArea area);
    public Builder walkTiles(String name, Set<WorldPoint> tiles);
    public Builder transport(WorldPoint tile, String verb);       // INTERACT
    public Builder climbUp(WorldPoint tile);
    public Builder climbDown(WorldPoint tile);
    public Builder gate(WorldPoint tile);                         // OPEN, walkthrough-on-already-open
    public PathSpec build();
}
```

Reuses `transport.Waypoint` as the underlying step type — no parallel data
model. The builder is the sugar layer: turning gate-style and climb-style
intentions into the right `Waypoint.transport(...)` calls.

### `ObstacleHandler` — frontier scanner for known traversal verbs

```java
public final class ObstacleHandler {
    public ObstacleHandler(Client client, TransportResolver resolver);
    public ObstacleHandler withVerbs(List<String> verbs);     // override default whitelist
    public ObstacleHandler withRadius(int radius);            // default 20

    public Result findAt(WorldPoint frontierTile, WorldPoint target);
}

public static final class Result {
    public final WorldPoint tile;
    public final TransportResolver.Match match;
    public final Rectangle hullBounds;
}
```

Default whitelist: `Open, Close, Climb-up, Climb-down, Cross, Pass,
Squeeze-through, Climb-over, Jump-over, Use`. Scans tiles within radius of
`frontierTile`, sorted by distance toward `target`. Returns the first
match's hull bounds (caller clicks the centre).

### `UniversalWalker` — drives a `PathSpec` to completion

```java
public final class UniversalWalker {
    public UniversalWalker(Client, ClientThread, HumanizedInputDispatcher, TransportResolver);
    public Status tick(PathSpec spec);            // call from a worker thread
    public void reset();
    public State state();
    public int currentStepIndex();

    public enum Status { IN_PROGRESS, ARRIVED, STUCK, ERROR }
    public enum State { IDLE, WALKING, AT_TRANSPORT, CROSSING, ARRIVED, STUCK }
}
```

Per-tick:

1. Read player position on client thread.
2. Pick the current step: closest waypoint by Chebyshev, monotonic forward —
   never backtrack to an already-passed step.
3. If on TRANSPORT step and player on the transport tile (or adjacent):
   trigger handler — find object by verb, click hull. Wait `~3s` settle.
4. If on WALK step: compute Reachability from player (depth 16), call
   `StepClickPicker.pick` with the next waypoint area. If the click target's
   tile is at the area edge AND the target is unreachable in the BFS,
   delegate to `ObstacleHandler` to look for a frontier traversal verb.
5. Rate limit: re-click only when target tile changed OR > 1.5s no movement.
6. Stuck threshold: 15s without position change → return `STUCK`.

Single-tick contract; an outer thread loops with a small sleep (~600ms
matches dax-style 100ms re-plan cadence well enough for OSRS tick rate).

### `Constants`

Bundled into `Walker` companion or each class — typical values:

- `BFS_DEPTH = 16`
- `OBSTACLE_RADIUS = 20`
- `MINIMAP_FALLBACK_RANGE = 20` tiles
- `STUCK_AFTER_MS = 15_000`
- `RECLICK_AFTER_MS = 1_500`
- `INTERACT_THROTTLE_MS = 3_000`

## Tests

- `ReachabilityTest` — synthetic CollisionData via mocked `WorldView`.
  - empty grid: every tile in a square reachable.
  - wall in middle: tiles past the wall unreachable, frontier hits the wall.
  - diagonal blocker: 8-connected expansion respects diagonal flags.
  - depth cap.
  - path reconstruction (origin → target neighbours).
- `PathSpecBuilderTest` — gate / climb-up / climb-down / walk produce the
  correct underlying `Waypoint.Kind` and verb.
- `ObstacleHandlerTest` — uses `TransportResolver` mocking to verify a gate
  with "Open" gets returned when the BFS frontier hits a wall tile.
- `StepClickPickerTest` — pure tile-picking logic with a stub Reachability
  and minimal Client mock.

## Example script

`runelite-client/src/main/java/net/runelite/client/plugins/recorder/scripts/LumbridgeBankPenWalkerScript.java`

Re-implements the macro path from `LumbridgeBankPenScript` as a `PathSpec`
and calls `UniversalWalker.tick(...)` in a loop. Shares the same combat
loop and bank interaction. Lives alongside the original — does not replace
it. Demonstrates that a scripter can express the chicken-farm route in
about 30 lines of `PathSpec.builder()...build()` calls.

## Out of scope

- Global Dijkstra link graph (dax-style world-wide pathing).
- Auto-camera-rotation on stuck (caller's job; framework returns STUCK).
- Recording / replaying paths from raw mouse input.
- Energy management, run/walk toggle (caller's responsibility).
