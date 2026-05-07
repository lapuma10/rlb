# Auto-map / global pathfinding — proposal & handoff

**Status:** brainstormed, not started. This doc is a self-contained handoff
for continuing the design discussion in a fresh Claude session.

## The problem we're solving

Today every "go from A to B" task is one recorded `*.json` trail (see
`~/.runelite/recorder/trails/`) executed by `TrailWalker`. The user
re-records a trail per route — bank↔kitchen, bank↔GE, etc. — and tunes
TrailWalker's knobs (`corridorRadius`, `noRepeat`) per script. When
those knobs are wrong:

- `corridorRadius=3 + noRepeat=true` makes the walker pick tiles up to
  3 away from the recorded centerline. Inside Lumbridge castle that
  bounces picks through the kitchen door, around the wall, back in.
- The corridor reachability filter (`TrailWalker.maybeApplyCorridorPick`
  → `Reachability.compute`) accepts ANY tile within `centerlineDist+1`
  BFS hops. BFS happily routes through a doorway and around the wall —
  hop budget treats "12 tiles around the building" the same as
  "1 tile through the wall."
- One trail = one centerline. "More variety" requires recording more
  trails or widening the corridor; widening the corridor causes the
  bug above. No good knob.

User's vision: **passively snapshot tile data while moving (collision
flags, NPCs, game objects in an 18×18 window each tick); accumulate a
persistent graph; pathfind globally between any two world points.** No
more per-route recording, no more corridor knobs.

## Feasibility — yes

`WorldView.getCollisionMaps()` returns per-plane `CollisionData` (`int[][]`
of 8-direction movement flags + LOS flags) for the live 104×104 scene,
indexed by scene-local x/y. `WorldArea.canTravelInDirection(wv, dx, dy)`
already encodes the full wall/diagonal matrix. `Scene.getTiles()
[plane][sx][sy]` exposes per-tile `GameObject[]`, `WallObject`,
`DecorativeObject`, `GroundObject`. `WorldView.npcs()` enumerates loaded
NPCs. `Scene.isInstance()` lets us skip POH/raids cleanly. Plane-change
edges are already represented by `TrailEvent.Transport` (option +
targetId + targetKind + param0/param1) — reusable as-is.

The 18×18 window is well inside the 104×104 scene budget. Storage at
~40 k tiles ≈ 4 MB packed — trivial.

## Existing infrastructure (~70% already there)

All paths under `runelite-client/src/main/java/net/runelite/client/plugins/recorder/`.

| Component | File | What it gives us |
|---|---|---|
| Collision BFS | `trail/Reachability.java` | 8-connected BFS over `getCollisionMaps()`, frontier extraction. Currently scene-local only — that's the gap. |
| Junction-stitching graph | `trail/TrailGraph.java` | Bucketed adjacency map, transport-edge payload. Data model is essentially what an auto-map needs. |
| Edge schema | `trail/Leg.java` (`Leg.Transport` record), `trail/TrailEvent.java` (`TrailEvent.Transport`) | verb / objectId / targetKind / param0 / param1 / approachTile — reusable verbatim as graph edge type. |
| Persistence | `trail/TrailRegistry.java` + `trail/TrailIO.java` | Load/save under `~/.runelite/recorder/trails/`. Pattern lifts directly to a new `worldmap/` directory. |
| Verb→object lookup | `transport/TransportResolver.java` | Already used by `UniversalWalker`. Resolves "Climb-up" / "Open" / etc. to a live `GameObject` at a tile. |
| Path executor | `walker/UniversalWalker.java` | Drives a `PathSpec` of waypoints + transports with BFS picker, throttled re-clicks, gate-already-open detection, stuck recovery. **This is exactly the executor a global graph needs.** |
| Scene scan | `scene/SceneScanner.java` | Boilerplate for scene-local enumeration of GameObjects by name. |
| Recorder integration | `RecorderPlugin.java` | The plugin lifecycle hook — where a `SceneScraper` would subscribe to ticks. |

`TrailRecorder.java` already hooks `MenuOptionClicked` to capture
TRANSPORT events with the right metadata. New transport-edge recorder
just plugs into the same channel.

## Greenfield (~30%)

1. Persistent global tile-graph data structure (region-bucketed,
   plane-aware).
2. Per-tick scrape of an 18×18 window around the player → graph merge.
3. Cross-scene A* honoring transport edges + plane changes.
4. `PathSpec` synthesis from graph route → hand to `UniversalWalker`.
5. UI / plugin glue (record-map toggle, debug "walk to (x, y, plane)").

## Recommendation: persistent passive map + reuse executor

**Build it.** Specifically:

- **Persistent passive map** of collision flags + transport edges.
  Always-on scrape during normal play (no separate "record a trail"
  step). Region-bucketed JSON store under
  `~/.runelite/recorder/worldmap/`.
- **A\* pathfinder** over collected data. Treats 8-neighbour walkable
  edges as implicit (computed on demand from collision flags); only
  persists transport edges as graph edges. Heuristic = Chebyshev +
  plane-change penalty.
- **Reuse `UniversalWalker`** as the executor. Compress the planner's
  walk-spine into `PathSpec` waypoints (run-length the spine into
  WALK_AREA boxes; each transport becomes a TRANSPORT waypoint).
- **Retire the corridor / noRepeat knobs from new scripts.** Leave
  `TrailWalker` for legacy / debug; new scripts call
  `MapPlanner.plan(start, end)`.

### Rejected alternatives

- **Pure recorded trails (status quo)**: re-records per route, knob
  tuning is per-script. The current pain.
- **Full auto-map from scratch, ignoring the codebase**: 30+ days; we'd
  re-implement `Reachability`, the transport edge schema,
  `UniversalWalker`. Pointless.
- **Static OSRS map dump (OSRSBox / RuneLite Wiki)**: data drifts with
  engine updates, license-grey, and we have the live data for free.
  Skip.
- **"Just fix the corridor picker first"**: tempting, but the corridor
  concept exists because trails are paths, not maps. Once we have a
  graph, the corridor parameter disappears entirely. Don't sink effort
  into perfecting a thing we'll delete.

## Implementation plan (5–8 engineering days)

All new code in a new package: `recorder/map/`.

1. **`RegionTile` + `RegionChunk` POJO.** 8-bit movement flags, plane,
   optional object-id list. Bucket by (plane, regionId) using OSRS
   region-id (`x>>6, y>>6`, 64×64 tiles ≈ 4 KB packed).

2. **`MapStore`.** JSON-per-region under
   `~/.runelite/recorder/worldmap/`. Lazy load on demand; flush dirty
   chunks every ~30s. Mirrors `TrailRegistry`'s pattern.

3. **`SceneScraper`.** Runs every 1–2 game ticks on the client thread
   (subscribed via `RecorderPlugin`). Reads
   `wv.getCollisionMaps()[plane].getFlags()` for an 18×18 window
   around the player; merges into the active region chunk. Skips if
   `scene.isInstance()`. Records `WallObject`/`GameObject` ids only
   when they advertise a movement verb (Open/Climb/Cross/...) — keeps
   storage lean.

4. **`TransportEdgeRecorder`.** Hooks `MenuOptionClicked` (same as
   `TrailRecorder` does today) to capture transport edges with
   target/param0/param1 — identical schema to `TrailEvent.Transport`.
   Edge written to `MapStore` keyed by (fromTile, toTile-after-plane-
   change). Approach tile = pre-click standing tile.

5. **`WorldGraph`.** Adapter that exposes
   `getCollisionFlags(WorldPoint)` + `transportsAt(WorldPoint)` to a
   planner. Reuses `TrailGraph`'s adjacency-map shape; treats
   collision-derived 8-neighbour edges as implicit (computed on demand
   from flags), only persists transport edges as graph edges.

6. **`MapPlanner`.** A* from start → end across `WorldGraph`.
   Heuristic = Chebyshev + plane-change penalty. Output:
   `List<WorldPoint>` walk-spine with transport markers. Compress to
   `PathSpec` waypoints; hand to existing `UniversalWalker`.

7. **`RecorderPanel` / `RecorderPlugin` glue.** "Record map" toggle
   (default on), debug "Walk to (x, y, plane)" action. Migrate
   `ChickenFarmV3` and either cooking script (V3 or `PizzaScript`)
   from `Route`-based walking to `MapPlanner.plan(start, end)` as the
   proof points.

## Open questions / risks to think through in the next session

- **Plane change semantics.** Adjacency across planes is via TRANSPORT
  objects, not Chebyshev. Graph edges must encode plane changes + the
  verb required. Shape should mirror `Leg.Transport` exactly — same
  payload schema, just stored in a different container. Confirm
  `param0/param1` actually pin the object identity across world
  reloads (they're scene coords; should be stable).
- **Doors that auto-close.** Need "edge requires Open verb" + "if door
  closed, dispatch Open before walking through." `UniversalWalker`
  already handles this for trail transports. Verify the same path
  works when the transport originates from `MapPlanner` rather than a
  recorded trail.
- **Instances (POH, raids).** `Scene.isInstance()` excludes them
  cleanly — scraper skips, planner errors out if start or end is
  instance-local. Confirmed feasible; just skip.
- **Scene streaming bounds.** Tiles loaded only within ~52×52 around
  player, so 18×18 window is always fully populated when player is
  centered. Edges of the window may straddle scene boundaries on
  player teleport — first scrape after a teleport may have stale data
  for 1 tick. Acceptable; second scrape covers it.
- **Object-name → verb map.** A door object has the "Open" verb in its
  composition; a staircase has "Climb-up" / "Climb-down". `findHeatSource`
  uses a name lookup; transport recording will need a verb predicate.
  `TransportResolver.findTransport` already does this for known verbs;
  reuse, don't reinvent.
- **What does the user start with?** A cold install has zero map data
  — first run still needs trails. Idea: ship recorded trails as
  bootstrap (replay them at startup to seed the map), then disable
  trail recording once the map is dense enough. Not blocking, but
  worth deciding before step 7.
- **Verification approach.** Suggested first proof point is a script
  the user already runs (cooking V3 or pizza). Take it off `Route`,
  put it on `MapPlanner.plan(...)`, watch it cook a session. If
  pathing matches or improves on hand-recorded trails, the design is
  validated.

## RuneLite API surface to study (next session)

In the `runelite-api/` module:

- `api/Scene.java` — `getTiles()`, `isInstance()`.
- `api/Tile.java` — `getGameObjects()`, `getWallObject()`,
  `getDecorativeObject()`, `getGroundObject()`.
- `api/CollisionData.java` — `getFlags()` (the 8-direction bitfield
  per tile).
- `api/coords/WorldArea.java` — `canTravelInDirection(...)` (already
  encodes the wall/diagonal matrix; reuse, don't re-derive).
- `api/WorldView.java` — `getCollisionMaps()`, `npcs()`,
  `getTopLevelWorldView()`.

In this repo:

- `recorder/trail/Reachability.java` — read this first; it's the BFS
  prototype. The auto-map is "this, but persistent and cross-scene."
- `recorder/walker/UniversalWalker.java` — read the `PathSpec` shape
  to understand what `MapPlanner` needs to produce.
- `recorder/trail/TrailGraph.java` — graph data model precedent.
- `recorder/RecorderPlugin.java` — plugin lifecycle / event hooks.

## How to continue this in a new chat

Copy this file's contents (or just paste the path) into a fresh
session. Ask: "read this proposal, then write a compact implementation
plan for steps 1–4 (region store + scraper + transport recorder)." See
the user's note in `~/.claude/projects/-Users-lilbee-Documents-GitHub-rlb/memory/feedback_compact_plans.md`
for the plan-style they want — 300–700 lines, behavior-first, no
pasted Java sources, no API guesses.

The brainstorm answered "should we build this, and what do we have." The
next session's job is "exactly how do we build steps 1–4," not
"should we." Don't re-litigate the recommendation.
