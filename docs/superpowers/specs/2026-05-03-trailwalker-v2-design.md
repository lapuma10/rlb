# TrailWalker v2 — Routes, Corridor, Opportunistic Transport

## Goal

Three improvements to the existing TrailWalker, shipped together as
"v2." All three serve one outcome: trails that no longer look identical
trip to trip, and trails that finish faster by skipping unnecessary
"walk to adjacency before clicking" delays.

1. **Routes** — group N equivalent trails under one logical name with
   per-trail weights. Each trip picks one weighted-random.
2. **Corridor walking** — widen each trail by ±1 tile, validated by
   `Reachability` BFS over engine collision flags. The dead-by-default
   experimental code in TrailWalker becomes the production path.
3. **Opportunistic transport interaction** — click a transport
   (stair / banker / door / gate) as soon as it is visible AND
   BFS-reachable, not when the player is exactly 1 tile away. The OSRS
   server pathfinds and triggers the action.

## Non-goals

- Branching / divergent-middle trails. Two record-twice flat trails
  cover the use case at a fraction of the complexity.
- Mid-trip trail swapping if the chosen trail gets stuck. Caller's
  problem; v2 returns STUCK like v1.
- Recorder changes. Existing recording flow is fine.
- Fixing the Cook's Assistant quest. The Route API enables the fix
  (clean call site for "walk somewhere reliably"), but the quest
  itself is a separate change.

## API surface

```java
// Build a route from one or more trail files with weights
Route bankToCook = Route.builder()
    .trail("lumby-bank-to-cook-north.json", 77)
    .trail("lumby-bank-to-cook-south.json", 23)
    .corridorRadius(1)         // optional, default 1
    .noRepeat(true)            // optional, default false
    .build();

// Convenience: load every trail matching a glob, equal weights
Route bankToCook = Route.fromPattern("lumby-bank-to-cook-*");

// Walk it
Status status = walker.walkRoute(bankToCook);

// Existing single-trail entry point unchanged
walker.start(trail);
```

`walker.walkRoute(route)` is a thin wrapper: pick one trail using the
weights, call the existing `walker.start(trail)`, return whatever
`tick()` eventually produces.

## Behavior changes

### Route picking

- **Weighted random** per call to `walkRoute`. Weights are integers;
  picker uses cumulative-sum + `nextInt(total)`.
- **`noRepeat`** (default off): if true, exclude the previously-picked
  trail. Falls through to "pick any" when the route has only one trail.
- **Resolution at build time**: missing trail files fail in
  `Route.builder().build()` with `IllegalArgumentException` naming the
  missing path. No silent fallback.

### Corridor (the existing experimental feature, now live)

- Default `corridorRadius = 1`, `corridorChancePercent = 100`. Always
  corridor-pick when a valid candidate exists.
- **`walkableProbe`** swapped from `t -> true` to a
  `Reachability`-backed predicate:
  - Walker snapshots a `ReachabilityMap` once per Walk-leg dispatch
    (BFS depth 16, on the client thread inside the existing `onClient`
    lambda). Microseconds; cheaper than the click it precedes.
  - Candidate is accepted iff:
    1. `reach.isReachable(candidate)` — BFS-visited.
    2. `reach.distance(candidate) <= reach.distance(centerlineTile) + 1`
       — no detours. Tolerance = 1 absorbs BFS-vs-server ordering quirks
       without admitting actual reroutes.
  - If no candidate passes, fall back to the centerline pick (existing
    `pickAheadTile`). Walker never blocks on corridor failure.
- **`corridorDebugOnly = false`** by default. The `setCorridorDebugOnly`
  setter stays for opt-out / debugging.
- Existing `pickAheadTile` jitter still runs on the centerline tile
  selection — the corridor adds *lateral* variance on top.

### Opportunistic transport interaction

Today (TrailWalker.java:657-735), the Transport leg handler waits
until the player is Chebyshev ≤ 1 from the recorded target tile,
then clicks. v2 clicks as soon as the **object** is properly visible
(camera-rotated if needed) and BFS-reachable.

#### Visibility pipeline — `ObjectVisibility`

A new component mirroring the combat package's `TargetVisibility`
(see `combat/TargetVisibility.java`). Same 6-stage cull pipeline,
adapted from NPC-tile-poly to game-object convex hull:

| Stage | Reason if culled | Adapted from TargetVisibility how |
|---|---|---|
| 0 | `PLANE_MISMATCH` | identical (player vs target tile plane) |
| 1 | `NOT_REACHABLE` | reuses `Reachability` snapshot the corridor builds; same `WorldArea.canTravelInDirection` primitive TargetVisibility uses |
| 2 | `OFF_CANVAS` | `obj.getConvexHull() == null` (instead of `npc.getCanvasTilePoly() == null`) — hull is the model's actual rendered shape, more accurate than tile poly for multi-tile objects (banker desks, large stairs) |
| 3 | `OUTSIDE_VIEWPORT` | hull's centroid pixel inside `client.getViewportXOffset/YOffset/Width/Height` rect — identical logic |
| 4 | `UNDER_OPEN_MENU` | hull centroid inside `client.getMenu()` rect when `isMenuOpen()` — identical logic |
| 5 | `UNDER_HUD` | resizable mode only; recursive descent of `HUD_CONTAINER_FRONT` looking for contentful (sprite/text/item/model) widget covering the centroid — copied verbatim from `ClientVisibility.containsContentfulChild` |

Returns `Reason` enum (or `null` if visible). Production binding
`forClient(client)`; tests use `alwaysVisible()` stub.

#### Transport click flow in v2

```
verb = TransportResolver.findTransport(target, recordedVerb)
if verb is null:
    fall back to walk-closer (existing transportVerbMissingTicks grace path)

reach = (the snapshot the corridor walker already built this tick)
if not reach.isReachable(target):
    fall back to walk-closer
if Chebyshev(player, target) > MAX_TRANSPORT_CLICK_DISTANCE:  // 12
    fall back to walk-closer

reason = objectVisibility.whyHidden(transportObject, player)
switch (reason):
  case null:  // visible — click it
    if pose != idle: return IN_PROGRESS
    dispatch CLICK_GAME_OBJECT
    return IN_PROGRESS

  case OFF_CANVAS, OUTSIDE_VIEWPORT:  // camera can fix this
    if not yet rotated this leg:
      dispatcher.rotateCameraToward(target, force=true)
      mark "rotated this leg"
    return IN_PROGRESS  // re-check next tick after rotation settles

  case UNDER_OPEN_MENU, UNDER_HUD, NOT_REACHABLE, PLANE_MISMATCH:
    fall back to walk-closer  // rotation can't fix these
```

The OSRS server then pathfinds the player to the object and triggers
the verb. Same dispatch primitive (`CLICK_GAME_OBJECT`); only the gate
changes.

`MAX_TRANSPORT_CLICK_DISTANCE = 12` is set just below the existing
`TRANSPORT_DIRECT_CLICK_TILES = 13`. Inside that radius the engine
will route the player on roughly the same path the recorded trail
took; beyond it the engine may detour through unintended tiles.

The existing `transportVerbMissingTicks` grace counter (4 ticks) is
unchanged — verb-missing still gives the engine time to re-stream
the object before bailing.

`force=true` on rotation is the same flag cooking uses
(`CookingInteraction.java:329` + `:459`) — it skips the
"tile poly inside viewport rect" early-exit, which is exactly the
case for "object behind a wall but tile geometrically projects to
the viewport." The "rotated this leg" guard prevents re-rotating
every tick if the visibility check still fails post-rotation
(rotation takes ~500-1500ms streaming; multiple ticks pass).

## Files

| Path | Change |
|---|---|
| `runelite-client/src/main/java/.../recorder/trail/Route.java` | NEW (~80 lines): builder, weighted picker, no-repeat tracker, validation |
| `runelite-client/src/main/java/.../recorder/trail/TrailWalker.java` | MODIFY: add `walkRoute(Route)`; default `corridorDebugOnly=false`; wire `Reachability`-backed `walkableProbe`; relax transport-click adjacency |
| `runelite-client/src/main/java/.../recorder/trail/TrailRepository.java` | NEW (~40 lines): `Route.fromPattern` glob loader scoped to `~/.runelite/recorder/trails/` |
| `runelite-client/src/main/java/.../recorder/trail/ObjectVisibility.java` | NEW (~180 lines): mirror of `combat/TargetVisibility` for game objects via `obj.getConvexHull()`; reuses the same `Reason` enum shape |
| `runelite-client/src/test/java/.../recorder/trail/ObjectVisibilityTest.java` | NEW: per-stage cull tests; rotation prompts on OFF_CANVAS/OUTSIDE_VIEWPORT but not on UNDER_HUD/UNDER_MENU |
| `runelite-client/src/test/java/.../recorder/trail/RouteTest.java` | NEW: weighted picker (chi-square style sample distribution), no-repeat toggle, missing-trail validation, single-trail degenerate case |
| `runelite-client/src/test/java/.../recorder/trail/TrailWalkerCorridorTest.java` | UPDATE existing: assert corridor active when `walkableProbe` accepts; assert fallback to centerline when probe rejects all candidates |
| `runelite-client/src/test/java/.../recorder/trail/TrailWalkerTransportTest.java` | NEW: opportunistic click fires when target is visible+reachable+within distance; falls back to walk-closer outside any of those conditions |

## Threading

No new threading model. `Reachability.compute` runs inside the
existing `onClient(...)` block in `TrailWalker.tick` — same client-
thread hop, nothing added. Route picking is pure compute on the
caller's thread. Dispatch model unchanged.

## Migration

- Existing `walker.start(Trail)` callers keep working unchanged. They
  pick up corridor walking as a side effect (radius 1).
- If corridor causes a regression for any specific caller, opt out
  with `walker.setCorridorWalkingEnabled(false)` after construction.
- Opportunistic transport clicking applies to all callers. There is no
  per-caller opt-out for this; if a regression emerges it'll be a
  fixed bug, not a knob.

## Sharp edges

1. **`canTravelInDirection` returns false for tiles outside the
   loaded scene** (`getCollisionMaps()` is null beyond ~104×104 around
   the player). Corridor candidates ±1 from the centerline are always
   inside the scene by construction, so this is a non-issue for v2.
   Documented for future readers.
2. **BFS distance is an approximation of server-side path cost.** They
   agree on reachable / not-reachable (same flag matrix), but the
   exact tile count can differ by 1 due to A*-vs-BFS tile ordering.
   Tolerance = 1 in the corridor accept rule absorbs this.
3. **Opportunistic click + recorded-trail divergence.** Within
   MAX_TRANSPORT_CLICK_DISTANCE the server's path will be very close
   to the trail; beyond that risk of "engine took a different route"
   grows. We cap at 12 tiles to stay inside the safe envelope.
4. **Route file watching.** `Route.fromPattern` resolves files at
   build time. If a trail file is added/removed/edited after build,
   the Route does not see the change. Rebuild the route. (Trails
   rarely change at runtime, so no watcher.)
5. **Opportunistic click before idle pose.** The `pose != idle` gate
   stays. Without it, mid-walk menu computation can swap the top entry
   between "Walk here" and the transport verb between hover and click.

## What we're *not* protecting against

- **Random NPC walks under the cursor at click time.** Already handled
  by the dispatcher's `isLeftClickWalk` pre-check + minimap fallback.
- **Closed door blocks the engine's chosen path.** Corridor picks are
  candidates within radius 1 of the recorded centerline; they're
  walking through the same doorway the recorded trail did. If that
  door closes between recordings and runs, that's a TransportResolver
  /trail-content problem, not a walker problem.

## Risks

| Risk | Mitigation |
|---|---|
| Corridor variance breaks an existing trail (unforeseen interaction with a tight corridor in a recorded trail) | Per-walker opt-out via `setCorridorWalkingEnabled(false)`; corridor radius defaults to 1 (smallest non-zero); fallback to centerline when probe rejects all candidates |
| Opportunistic transport click triggers an unexpected engine path | Capped at MAX_TRANSPORT_CLICK_DISTANCE = 12 + reachability check + on-canvas check; if engine routes oddly the existing PROGRESS_TIMEOUT_MS = 4000 catches it and re-clicks |
| Route picker biased by RNG seed | Test asserts uniform distribution within ±5% over 10 000 picks at 50/50; weighted distributions tested similarly |
| `Route.fromPattern` picks up unintended files | Glob is restricted to `~/.runelite/recorder/trails/` and matches only `.json`. Builder logs which files matched. |

## Out of scope (track separately)

- Cook's Assistant quest fix (uses Route API once implemented).
- Mid-trip trail swap on STUCK (call site retries with fresh Route
  pick today; engine-level swap can come later).
- Recorder UX for marking trail variants ("this is the alt for X").
  Naming convention (`<route>-<variant>.json`) handles 95% of cases;
  formal metadata can wait.
