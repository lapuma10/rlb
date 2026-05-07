# WorldMemory v1 — design spec

**Date:** 2026-05-06
**Status:** locked. Implementation plan to follow in next session via `superpowers:writing-plans`.
**Companion brainstorm:** `recorder/AUTOMAP-PROPOSAL.md` (the "should we, and what's there" doc).

---

## Summary

WorldMemory is a parallel navigation system that learns the world from passive scene observation, then plans paths to entity- or coord-targeted destinations. v1 ships the static-tile + entity-sighting layers and the LOS-aware destination picker; cross-scene routing and transport composition wait for v2.

The v1 proof is concrete: **cooking script can stand anywhere within the same OSRS region as the kitchen target on Lumbridge ground floor and ask for the Range/Cook interaction; the planner returns a reachable, LOS-valid stand tile, and the script no longer hangs when wall geometry hides the previous 3×3 arrival window.**

WorldMemory lives in a new package, takes nothing away from existing systems, and is opt-in per script via a config flag — so existing scripts keep working unchanged and side-by-side comparison is a one-toggle test.

A small companion refactor (separately scoped, also v1) extracts `Walker` as an interface so future walker implementations — including the v3 human-simulation walker — can be swapped in for all scripts uniformly.

---

## Goal

Replace the brittle "walk into a 3×3 `WorldArea` near the target" pattern with "walk to a tile that's walkable + in interaction range + has LOS to the target." The planner consumes a persistent, ever-growing memory of:

- collision flags (per-tile, per-plane)
- LOS-blocking flags (per-tile)
- objects (id, name, last-seen tile, actions)
- NPC sightings (id, name, last-seen tile, sighting count)

…and emits a `PathSpec` the existing `UniversalWalker` already knows how to execute.

## Non-goals (v1)

- No global cross-scene planner. Same-plane, same-region only.
- No transport-edge composition in the graph. (Recorded named trails *do* contain transports — those still work via `TrailWalker`. The v1 graph itself doesn't yet stitch transports into A*.)
- No teleports / boats / agility shortcuts.
- No quest-/item-state gating.
- No SQLite. JSON-per-region only.
- No `confidence` field. Use `seenCount` + `lastSeenAt`; derive in code if anyone needs it.
- No automatic trail reversal. (Pure walking is reversible via collision; transports stay one-way until observed.)
- No bootstrap data shipped in the jar.
- No NPC clustering / centroid-and-radius. Just last-seen tile per `(name, region)`.
- **No removal or modification of `recorder/trail/*`.** TrailWalker, TrailRecorder, TrailRegistry, TrailIO, Route, Trail — all stay exactly as they are.
- No new "Start recording" UX on the WorldMemory side. Existing TrailRecorder UX is unchanged. v2 introduces named-trail-as-highway integration.
- No mouse/camera capture or human-simulation walker. That's v3.

## Isolation constraint

User requirement: *"separate from current TrailWalker so I can switch it out and test it properly — not just for cooking but for all scripts."*

| What | Status under v1 |
|---|---|
| `recorder/trail/*` (TrailWalker, TrailRecorder, etc.) | Untouched. Zero diff. |
| `recorder/walker/UniversalWalker.java` | One small refactor: implements the new `Walker` interface (no behavioral change). Otherwise untouched. |
| `recorder/walker/Reachability.java` | Untouched. WorldMemory adds a sibling A* class; doesn't fork BFS. |
| `recorder/walker/PathSpec.java` | Untouched. WorldMemory consumes the existing builder. |
| `recorder/walker/Walker.java` | **NEW** — small interface extracted from `UniversalWalker`'s public surface so impls can be swapped uniformly. |
| `transport/TransportResolver.java` | Untouched. Unused by v1; v2 will use as-is for transport edges. |
| Cooking script V3 | One callsite changes when the toggle is on; default is off. |
| Other scripts that use `UniversalWalker` | Constructor field type goes from `UniversalWalker` to `Walker`. No behavioral change. |
| `RecorderConfig` | One new boolean: `useWorldMemoryPlanner` (default false). |

A/B testing flow:

```
Run with useWorldMemoryPlanner=false → CookingScriptV3 builds the v3-cook PathSpec from l.cookArea() (current behavior).
Toggle to true                       → CookingScriptV3 builds the v3-cook PathSpec from MapPlanner.planToInteractTile(...) instead.
Compare: stuck rate, time-to-first-cook, off-canvas incident count.
```

Same script binary; same walker; only the path source differs. The diff in CookingScriptV3 is a single block at line ~645.

---

## Walker interface (v1 companion refactor)

A small mechanical refactor that runs alongside the WorldMemory work. Goal: enable swapping the walker implementation across all scripts at once, in v3 (human-simulation walker) and beyond.

**Scope:**

- New file `recorder/walker/Walker.java` — interface extracted from `UniversalWalker`'s public methods (`tick(PathSpec)`, `reset()`, `state()`, `currentSpec()`, status enums).
- `UniversalWalker implements Walker` — no behavioral change.
- Every script field/constructor of type `UniversalWalker` becomes `Walker`. No behavioral change.

**Out of scope:**

- `TrailWalker` does not implement `Walker` in v1. Its contract (replay a recorded `Trail`) is different from `Walker.tick(PathSpec)`. Bridging is v3 work if/when the human-simulation walker needs to consume Trails too.
- No new `Walker` impls in v1. The point is to *unblock* future impls (especially the v3 human-simulation walker), not to write any.
- **Timing/threshold constants stay on `UniversalWalker`.** The public `static final long` constants (`RECLICK_AFTER_MS`, `STILL_THRESHOLD_MS`, `INTERACT_THROTTLE_MS`, `STUCK_AFTER_MS`, `TRANSPORT_ARRIVAL_RADIUS`, `OPEN_GATE_SEARCH_RADIUS`, `BFS_DEPTH`) are NOT lifted to the `Walker` interface — they're impl detail of `UniversalWalker`. No recorder script reads them externally today; verified via grep.
- **Walker contract is `tick(PathSpec)` only.** How an impl tracks step state, last-clicked-transport indices, click cadence, etc. between ticks is its own internal concern. The interface does not require any specific bookkeeping. A v3 human-simulation walker may track entirely different state.

**Why now:** the user's framing is "switch it out for all scripts." Doing the interface extraction in v1 means the v3 walker swap is a one-line change per script (constructor injection); deferring it means a much bigger refactor later.

**Why small:** the interface has ~6 methods; `UniversalWalker` already has them; the only churn is type names in script constructors. Mechanical, no logic changes, low risk.

---

## Existing repo primitives WorldMemory reuses

| Existing class | What WorldMemory uses it for |
|---|---|
| `walker/Reachability.compute(wv, origin, depth)` | Same BFS over collision flags. v1 wraps the per-step traversal logic in a sibling A* (`MapAStar`); the `WorldArea.canTravelInDirection` semantic is identical. |
| `walker/PathSpec.builder()` | Planner emits `PathSpec` via `walkTile(plannerResult)` or `walkTiles(name, candidateSet)`. No new spec format. |
| `walker/UniversalWalker.tick(spec)` | Executes the planner output. WorldMemory does **not** ship a new walker; UniversalWalker becomes the v1 default `Walker` impl. |
| `walker/StepClickPicker` | Existing canvas-projection picker. **StepClickPicker still owns canvas projectability per tick at execution time.** The planner only validates walkability + LOS from collision data; the picker re-validates against live camera state. |
| `transport/TransportResolver.findTransport(tile, verb)` | Used at execution time when a v2 transport edge is traversed. Not used in v1's same-region scope. The schema we lock for transport edges in v1 is wire-compatible with what TransportResolver already expects. |
| `trail/TrailRegistry` + `trail/TrailIO` | Pattern reference for `~/.runelite/recorder/worldmap/` JSON I/O. WorldMemory clones the pattern; no shared code. |
| `trail/TrailEvent.Transport` (record) | Field-by-field reference for the v2 transport-edge persistence schema. v1 does not write transport edges. |
| `RecorderPlugin` | Plugin lifecycle host. The `SceneScraper` is registered alongside existing recorder hooks. |
| `RecorderConfig` | One new boolean: `useWorldMemoryPlanner` (default false). |
| `client.callback.ClientThread.invokeLater(...)` | Used by `UniversalWalker.readSnapshot()` already; WorldMemory's scraper uses the same pattern. |

The build effort is closer to "write a new package that consumes existing primitives" than "implement six subsystems from scratch." Most of the heavy lifting (BFS over collision, click pipeline, transport resolution, JSON-per-X persistence) already exists.

---

## Architecture

Three layers; each layer has a separate writer and a separate store.

```
recorder/worldmap/                   (v1 — new package)
│
├── Layer 1: STATIC MAP (geometry)
│   ├── SceneScraper.java                — scans loaded scene each Nth tick
│   ├── RegionChunkBuilder.java          — mutable, package-private; scraper-only
│   ├── RegionChunkSnapshot.java         — immutable, public; planner-only
│   ├── MapStore.java                    — load/cache/flush + AtomicReference<RegionChunkSnapshot> per region
│   └── MapAStar.java                    — Dijkstra/A* over collision flags, multi-target capable
│
├── Layer 2: ENTITY MEMORY (sightings)
│   ├── EntityScraper.java               — sweeps wv.npcs() each tick
│   ├── EntitySighting.java              — record: kind, id, name, tile, seenCount, seenAt
│   └── EntityIndex.java                 — name → list<EntitySighting>; in-memory + persisted
│
├── Layer 3: PLANNER (consumes layers 1+2 to emit PathSpec)
│   └── MapPlanner.java                  — entry point; resolves target → reachable LOS tile → PathSpec
│
└── WorldMemoryConfig.java               — scrape cadence, flush interval, scrape window size, A* caps, ranking weights
```

Each layer can be tested in isolation; each persists to its own subdirectory.

Persistence root: `~/.runelite/recorder/worldmap/`
- `regions/<regionId>.json` — one file per region, plane-bundled. Contains tiles **and** object sightings (objects co-located here because they're scraped from the same per-tick scene pass).
- `entities/<regionId>.json` — one file per region for NPC sightings.

`EntityIndex` builds an in-memory by-name lookup from **both** sources at startup: NPCs from `entities/*.json`, objects from `regions/*.json`'s `objects[]` array. Same `EntitySighting` shape exposed; callers don't see the persistence split.

### Data flow

```
                    ┌─────────────────────────────────────────────────┐
client thread:      │  GameTick (or every Nth tick)                   │
                    │  ── SceneScraper.scan(wv, scene)  ────────►──────┐ writes
                    │  ── EntityScraper.scan(wv)        ────────►──────┤
                    │                                                  │
                    │  Snapshot: WorldPoint, sceneSize, etc.           │
                    └─────────────────────────────────────────────────┘
                                                                       │
                                                                       ▼
                    ┌─────────────────────────────────────────────────┐
in-memory:          │  RegionChunkBuilder (mutable, scraper-only)      │
                    │  ── publish() → fresh RegionChunkSnapshot ───────┐
                    │  AtomicReference<RegionChunkSnapshot>            │
                    │  EntityIndex      (name → sightings list)        │
                    │                                                  │
                    │  flushDaemon (every ~30s): dirty chunks → JSON   │
                    └─────────────────────────────────────────────────┘
                                                                       │
                                                                       ▼
                    ┌─────────────────────────────────────────────────┐
worker thread:      │  Script asks: "stand-tile near target T,         │
                    │                LOS required, Chebyshev R"        │
                    │                                                  │
                    │  MapPlanner.planToInteractTile(player,T,R,true)  │
                    │   ── reads cached chunk for T's region           │
                    │   ── enumerates candidates in (R+1)² window      │
                    │   ── filters: walkable, reachable from player,   │
                    │     LOS-clear to T                               │
                    │   ── ranks: distance to player + distance to T   │
                    │   ── returns Optional<PathSpec>                  │
                    └─────────────────────────────────────────────────┘
                                                                       │
                                                                       ▼
                    ┌─────────────────────────────────────────────────┐
script thread:      │  spec.ifPresent(walker::tick)                    │
                    │                                                  │
                    │  walker is type `Walker`; v1 default is          │
                    │  UniversalWalker.                                │
                    └─────────────────────────────────────────────────┘
```

---

## Threading model

Per `CLAUDE.md`'s threading section. This is the rule that prevents silent deadlocks:

| Component | Runs on | Reads | Writes |
|---|---|---|---|
| `SceneScraper.scan` | client thread (called directly from `@Subscribe onGameTick` — RuneLite fires GameTick on the client thread, so no `invokeLater` marshalling is needed; hopping back via `invokeLater` would queue the work for a *future* frame and break per-tick semantics) | `wv.getCollisionMaps()`, `scene.getTiles()` | `RegionChunkBuilder` (mutable, package-private) |
| `EntityScraper.scan` | client thread (same handler, after SceneScraper) | `wv.npcs()` | `EntityIndex` (sighting maps) |
| `MapStore.publish(regionId, builder)` | client thread (end of scrape) | builder | constructs immutable `RegionChunkSnapshot`; atomic-swaps `AtomicReference` |
| `flushDaemon` | dedicated daemon thread (low priority) | dirty-chunk set (immutable snapshots) | JSON files under `~/.runelite/recorder/worldmap/` |
| `MapPlanner.findInteractTile` / `planWithin` / `planToInteractTile` | worker thread (script's thread or any non-client worker) | in-memory **immutable** chunk snapshots; **no live client API** | nothing |
| `MapAStar.search` | same worker thread | same | nothing |
| `UniversalWalker.tick` (consumer) | already a worker thread (per existing usage) | hops to client thread for `getLocalPlayer()`, `getCollisionMaps()`, etc. | dispatches via `HumanizedInputDispatcher` |

**Hard rule 1:** `MapPlanner` and `MapAStar` must not directly touch `Client`, `Scene`, `WorldView`, `NPC`, `Tile`, `Widget`. They read only the in-memory immutable snapshots. If `findInteractTile` is called for a target whose region isn't loaded, the planner returns `Optional.empty()` and the script falls back to the legacy path.

**Hard rule 2:** `MapStore` exposes **immutable snapshots** to readers via two-class design (F2 from spec QC):

- `RegionChunkBuilder` — mutable, **package-private** to `worldmap/`. Used **only** by `SceneScraper` (and `EntityScraper` for in-region object writes). Has accumulator methods like `setTile(x, y, plane, movementFlags)`, `addObject(...)`. Never visible to readers.
- `RegionChunkSnapshot` — immutable, public. Returned by `MapStore.snapshotFor(regionId)`. Has read-only methods (`tile(x,y,plane)`, `objects()`, `isStandable(tile)`). All collections are unmodifiable views.
- `MapStore` holds `Map<Integer, AtomicReference<RegionChunkSnapshot>>` keyed by regionId. Scrape flow per region per tick: scraper calls `mapStore.builderFor(regionId)` → mutates → calls `mapStore.publish(regionId, builder)` which constructs a fresh `RegionChunkSnapshot` and atomically swaps the `AtomicReference`. The planner reads `mapStore.snapshotFor(regionId)` which dereferences the `AtomicReference` once and operates on the immutable snapshot for the rest of the call. No mid-publish window — the `AtomicReference.set` happens after the snapshot is fully constructed.

Planner therefore never observes a torn write. The cost is the snapshot build (O(tiles in region)) on every publish; with regions of ~4096 tiles and "every 2 ticks" scrape cadence, this is well under 1ms per publish.

The scrape step itself is cheap — an 18×18 collision read + an `npcs()` enumeration are microseconds. Doing it on the client thread once per N ticks is fine.

Cadence (initial values, tunable in `WorldMemoryConfig`):
- Scrape: every 2 ticks (~1.2s)
- Flush dirty chunks: every 30s
- Scrape window: 18×18 around the player
- Per-tick budget cap: skip scrape if it would exceed 2ms (logged as warning). **Skipped scrapes do not advance `lastScrapedAt`** — the next tick's scrape covers the same window. The `RegionChunkSnapshot` from before the skipped scrape remains live until the next successful publish.

---

## Storage format

JSON-per-region. Greppable, diff-friendly, no new deps.

### Region IDs

Use the engine's region scheme:

```
regionId = ((x >> 6) << 8) | (y >> 6)
```

One file per `regionId`. Plane is a per-tile field, not a separate file.

### `regions/<regionId>.json`

```json
{
  "schema": 1,
  "regionId": 12850,
  "gameRevision": 238,
  "lastScrapedAt": 1714960000000,
  "tiles": [
    {
      "x": 3208,
      "y": 3213,
      "plane": 0,
      "movement": 16777216,
      "los": 0
    }
  ],
  "objects": [
    {
      "id": 12345,
      "name": "Range",
      "x": 3209,
      "y": 3214,
      "plane": 0,
      "actions": ["Cook", "Examine"],
      "seenCount": 7,
      "lastSeenAt": 1714960000000
    },
    {
      "id": 1530,
      "name": "Door",
      "x": 3208,
      "y": 3216,
      "plane": 0,
      "actions": ["Open"],
      "seenCount": 4,
      "lastSeenAt": 1714960000000
    }
  ]
}
```

Field notes:
- `movement` — the 32-bit int from `CollisionData.getFlags()[localX][localY]` for this tile. Persisted as decimal int. The unpack happens in the planner via the same wall/diagonal semantics that `WorldArea.canTravelInDirection` uses (synthesized from a chunk read instead of a live `WorldView`).
- `los` — **populated in v1.** LOS bits live in the same `CollisionData` int as movement flags (`BLOCK_LINE_OF_SIGHT_NORTH/EAST/SOUTH/WEST` in `runelite-api/.../CollisionDataFlag.java`). v1 stores `movement` only; LOS is derived from the same int at planner time via either: (a) bit-test the LOS-block flags directly per Bresenham step (preferred — cheap, no extra read at scrape time, same data path as movement), or (b) call `WorldArea.hasLineOfSightTo(wv, target)` (requires live `WorldView`, which means a client-thread hop — defeats the planner's "no live client API" rule). v1 picks (a). The `los` field stays in the JSON schema as reserved space; v1 always writes 0.
- `gameRevision` — bumps every OSRS update. **Load-time log warning only** — `MapPlanner` does not gate runtime behavior on revision mismatch. A user upgrading without clearing `~/.runelite/recorder/worldmap/` accepts the risk that scraped flags may be stale until v2 ships invalidate-and-re-scrape.
- `seenAt` per-tile — **omitted in v1.** Chunk-level `lastScrapedAt` covers freshness. Per-tile `seenAt` would add ~12 bytes × thousands of tiles for no v1 use case.

**JSON forward-compat:** readers must ignore unknown fields (Gson lenient mode or Jackson `FAIL_ON_UNKNOWN_PROPERTIES=false`). v2 may add optional fields that v1 binaries silently ignore.

### `entities/<regionId>.json`

```json
{
  "schema": 1,
  "regionId": 12850,
  "lastUpdatedAt": 1714960000000,
  "npcs": [
    {
      "id": 4626,
      "name": "Cook",
      "lastTile": { "x": 3208, "y": 3213, "plane": 0 },
      "seenCount": 12,
      "lastSeenAt": 1714960000000
    }
  ]
}
```

Just last-seen for v1. NPCs that wander a region show up at whatever tile they were on at scrape time; the planner's `findInteractTile` searches a small radius around `lastTile`, and the script does a live scan after arriving.

### `transport_edges.json` — schema reserved (v2; not written in v1)

Locked now for forward-compat:

```json
{
  "schema": 1,
  "edges": [
    {
      "from":         { "x": 3208, "y": 3216, "plane": 0 },
      "to":           { "x": 3208, "y": 3217, "plane": 0 },
      "objectId":     1530,
      "objectName":   "Door",
      "verb":         "Open",
      "approachTile": { "x": 3208, "y": 3216, "plane": 0 },
      "seenCount":    1,
      "lastUsedAt":   1714960000000
    }
  ]
}
```

Mirrors `TrailEvent.Transport` field-for-field minus the trail-event-specific stuff (`msSinceStart`, `menuRowsAtClick`).

---

## v1 API surface

Module: `net.runelite.client.plugins.recorder.worldmap`

```java
public final class MapPlanner {
    /**
     * Find a stand tile near {@code target} from which the player can
     * interact with it, optionally requiring line-of-sight.
     *
     * @param player              player's current position
     * @param target              object/NPC tile we want to interact with
     * @param maxDistance         Chebyshev radius around target; typical: 1 (range), 2 (booth)
     * @param requireLineOfSight  if true, only tiles with clear LOS to target qualify
     * @return                    best stand tile, or empty if no candidate is reachable + valid
     *
     * Runs entirely off the in-memory chunk cache. Never touches live Client.
     * If the target's region isn't cached, returns empty.
     */
    public Optional<WorldPoint> findInteractTile(
        WorldPoint player,
        WorldPoint target,
        int maxDistance,
        boolean requireLineOfSight);

    /**
     * Plan a walk path from player to goal within the same region, same plane.
     * v1: returns empty if cross-region.
     */
    public Optional<PathSpec> planWithin(
        WorldPoint player,
        WorldPoint goal);

    /**
     * Convenience: find an interact tile, then build a PathSpec to it.
     * Returns empty if no valid stand tile exists. Caller does NOT need
     * to manually call findInteractTile + PathSpec.builder.
     *
     * This is the method CookingScriptV3 uses — one call, fallback if empty.
     */
    public Optional<PathSpec> planToInteractTile(
        WorldPoint player,
        WorldPoint target,
        int maxDistance,
        boolean requireLineOfSight,
        String pathName);
}

public final class EntityIndex {
    /** All sightings of an NPC by name, sorted most-recent first. */
    public List<EntitySighting> findNpcsByName(String name);

    /** All sightings of an object by name, sorted most-recent first. */
    public List<EntitySighting> findObjectsByName(String name);

    /** Sighting nearest to {@code from}, breaking ties by recency. */
    public Optional<EntitySighting> nearestNpc(String name, WorldPoint from);

    public Optional<EntitySighting> nearestObject(String name, WorldPoint from);
}
```

```java
// Companion refactor — recorder/walker/Walker.java (NEW)
public interface Walker {
    UniversalWalker.Status tick(PathSpec spec) throws InterruptedException;
    void reset();
    UniversalWalker.InternalState state();
    int currentStepIndex();
    PathSpec currentSpec();
}
```

(The `Status` and `InternalState` enums currently nested on `UniversalWalker` may be lifted to the interface or kept where they are — that's plan-level mechanical detail. The interface signature itself is locked.)

Internal (not exposed; impl-plan owns these): `SceneScraper`, `EntityScraper`, `RegionChunk`, `EntitySighting`, `MapStore`, `WorldMemoryConfig`, `MapAStar`.

The cooking-script v1 use case calls only `MapPlanner.planToInteractTile(...)`. The richer entity API (`EntityIndex.nearestNpc`) ships in v1 too because it's small (a HashMap lookup) and gives v2 a stable contract to integrate against.

---

## LOS-aware destination picker

This is the v1 centerpiece. Algorithm — **single-pass Dijkstra**, not per-candidate A*:

```
findInteractTile(player, target, R, requireLOS):
    snap = mapStore.snapshotFor(target.region)
    if snap == null:                       return empty   // target's region not yet scraped
    if not snap.contains(player.region):   return empty   // require same-region for v1

    // Phase 1: enumerate viable destination tiles (no path search yet).
    candidates = tiles within Chebyshev R of target, on same plane
    keep c in candidates if:
        snap.isStandable(c)
        and (not requireLOS  or  hasLineOfSight(c, target))

    if candidates is empty:                return empty

    // Phase 2: SINGLE Dijkstra from `player`, terminates when every
    // candidate is either visited or proven beyond `maxPathLength` /
    // `maxExpandedTiles` caps. distMap[c] = shortest path length from
    // player to c, or -1 if unreachable within caps.
    distMap = dijkstraToAny(snap, player, candidates,
                            maxPathLength, maxExpandedTiles)

    // Phase 3: rank by actual path length, pick best.
    reachable = candidates.filter(c -> distMap[c] >= 0)
    if reachable is empty:                 return empty

    rank by:  0.6 * distMap[c]  +  0.4 * chebyshev(c, target)
    return argmin(reachable)
```

**Cost:** one Dijkstra sweep covers all candidates. Caps bound runtime independently of candidate count. Worst case at v1 caps: O(10,000 log 10,000) ≈ 130k ops, sub-millisecond on a worker thread. Per-candidate A* would have been 25× this for a 5×5 candidate window — F1 from spec QC.

Where:

- `isStandable(tile)`:
  - tile exists in the chunk (was actually scraped, not just enumerated as a coordinate)
  - tile is not floor-blocked (the "you can never stand here" bit; e.g., wall interiors, pits)
  - at least one neighbor can legally travel into this tile under the same wall/diagonal semantics `Reachability` uses

  This is stricter than "8-direction blocked" — a tile with all 8 outgoing directions blocked but one *incoming* path is still standable (think: trapped-in tiles you can be pushed into).

- `dijkstraToAny(snap, origin, goals, maxLen, maxExp)` — Dijkstra over the chunk's collision flags. Same wall/diagonal semantics as `Reachability` (`WorldArea.canTravelInDirection`). Caps from `WorldMemoryConfig`:
  - `maxPathLength`: 128 tiles (region diagonal is ~90 Chebyshev; 128 surfaces "different connectivity component" early)
  - `maxExpandedTiles`: 10,000 nodes
  - same plane, same region only

  Returns: `Map<WorldPoint, Integer>` for tiles in `goals` set, value = path length or -1 if unreached when caps hit.

- `hasLineOfSight(c, target)` — bit-test the `BLOCK_LINE_OF_SIGHT_*` flags in `c.movement` along a Bresenham walk to `target`. No client thread hop. Implementation can mirror RuneLite's own `WorldArea.hasLineOfSightTo` algorithm, but operates on the persisted chunk's flags rather than a live `WorldView`.

The ranking weights (0.6/0.4) are seed values; `WorldMemoryConfig` exposes them for live tuning.

**What the planner DOES validate:** walkability + LOS from persisted collision data.
**What the planner does NOT validate:** canvas/screen projectability — that depends on live camera state. `StepClickPicker` retains ownership of canvas projectability per tick at execution time. The walker's existing fallback (re-BFS in live scene if the planner's tile is blocked) handles the case where the planner's tile is correct in-graph but obstructed in-scene.

**Output PathSpec shape (F5):** `planToInteractTile` and `planWithin` emit a single-leg `PathSpec` containing `Waypoint.walkArea(name, 1×1 area around tile)` — **not** `Waypoint.walk(tile)`. This matches the existing `arrivalWindowAround(target)` pattern and ensures `UniversalWalker.handleWalk`'s `WALK_AREA` code path (which the picker + chooseStep both rely on) handles it cleanly. A bare `WALK` waypoint goes through a different lifecycle that hasn't been validated for v1's call shape.

### What this fixes

CookingScriptV3 today (line 631-650):

```java
private PathSpec currentCookPath() {
    ...
    WorldPoint target = pickCookTargetTile(l);   // tile of fire / logs / range
    if (target == null) {
        // fallback: walk to whole cookArea
    } else {
        WorldArea around = arrivalWindowAround(target);   // 3×3 box centered on target
        tripCookPath = PathSpec.builder("v3-cook-target-...")
            .walk("v3-cook", around)
            .build();
    }
}
```

When `arrivalWindowAround(target)` is a 3×3 box and wall geometry hides every projectable tile in it (the off-canvas bug), `StepClickPicker.pick` returns null, walker stalls, script hangs.

Migration when `useWorldMemoryPlanner` is true:

```java
private PathSpec currentCookPath() {
    if (tripCookPath != null) return tripCookPath;
    CookingLocation l = location.get();
    WorldPoint target = pickCookTargetTile(l);

    if (target == null) {
        // unchanged: walk to full cookArea (legacy behavior when target unknown)
        tripCookPath = PathSpec.builder("v3-cook-area-" + System.currentTimeMillis())
            .walk("v3-cook", l.cookArea())
            .build();
    } else if (config.useWorldMemoryPlanner()) {
        Optional<PathSpec> spec = mapPlanner.planToInteractTile(
            playerPos, target,
            /* maxDistance */ 2,
            /* requireLineOfSight */ true,
            /* pathName */ "v3-cook-wm-" + System.currentTimeMillis());
        if (spec.isPresent()) {
            tripCookPath = spec.get();
        } else {
            // Planner returned empty — region not yet scraped, or no LOS-valid
            // tile reachable. Fall back to the WIDE cookArea (NOT the buggy 3×3),
            // which gives StepClickPicker more candidates and reduces off-canvas
            // hit rate. F7 from spec QC.
            tripCookPath = PathSpec.builder("v3-cook-area-" + System.currentTimeMillis())
                .walk("v3-cook", l.cookArea())
                .build();
        }
    } else {
        // Toggle off — preserve the existing 3×3 path exactly as today.
        WorldArea around = arrivalWindowAround(target);
        tripCookPath = PathSpec.builder("v3-cook-target-" + System.currentTimeMillis())
            .walk("v3-cook", around)
            .build();
    }
    return tripCookPath;
}
```

The toggle's purpose is to *replace* the buggy 3×3 path with a planner-picked LOS-valid tile when the planner has data. When the planner can't help (cold start, region not yet populated), the fallback is the **wide cookArea**, not the 3×3 — that gives the picker more candidates and reduces the same wall-geometry off-canvas risk the 3×3 is prone to. The toggle-off behavior is unchanged from current production.

---

## Recording — passive only in v1

v1 ships passive scraping only. No "Start recording" button on the WorldMemory side, no named trails on the WorldMemory side. Reasoning:

- The user's vision treats named trails as a separate, useful artifact. v1 does not need them — the cooking proof point is single-region, no cross-scene routing.
- Named trails already exist via `recorder/trail/TrailRecorder`. If the user wants a named trail, the existing pipeline records it. WorldMemory observes the same player walks (passively) regardless, so its data fills in either way.
- Layering named trails into the planner (as preferred "highways") is v2 work.

So in v1 the only UX change is one config toggle. Everything else is invisible until v2.

---

## Observations vs truth

The line that anchors the design:

> WorldMemory stores observations. The live client validates truth at execution time.

Concretely:
- The planner returns a tile based on the scraped chunk. The live tile may be blocked (an NPC standing on it, a player, a closed door not yet observed).
- `UniversalWalker` doesn't trust the spec's tile — every tick it re-BFSes from the live `WorldView`. If the planner's tile is unreachable in the live scene, the walker falls back to its existing "pick the reachable tile closest to the area centre" path.
- Entity sightings expire implicitly: the script always does a live scan after arriving (`cook.findHeatSource(...)`, `npcSelector.findNearest(...)`). The DB tells the planner *where to look*; the live scene tells the script *what's there*.

WorldMemory accelerates planning; it never replaces live verification.

---

## A/B testing plan

The proof-point setup, ordered:

1. **Baseline run:** `useWorldMemoryPlanner=false`. Run cooking script for 30 minutes in Lumbridge with the camera angles that produce off-canvas projection. Log every tick where `StepClickPicker.pick` returns null; log every walker `STUCK` event; log time-to-first-cook.

2. **Cold run:** flip `useWorldMemoryPlanner=true` with **no scraped data** (delete `~/.runelite/recorder/worldmap/`). Verify graceful fallback: the planner returns empty for the first 1-2 scrape cycles (~1-3 seconds) while in-memory chunks populate; the script falls through to the legacy `walk(area)` path; once the chunk is populated in memory, the planner takes over. Planner does not wait for JSON flush — only for the in-memory cache. No hang.

3. **Warm run:** with scraped data persisted, flip the toggle and run the same 30-minute session. Compare:
   - off-canvas incident count (should drop to ~0)
   - time-to-first-cook (should be ≤ baseline)
   - walker `STUCK` events (should drop)
   - any new failure modes introduced by the planner

If warm-run metrics are better than baseline and there are no new regressions, v1 is validated.

---

## Phasing

### v1 (this spec)
- `recorder/worldmap/` package: `SceneScraper`, `EntityScraper`, `RegionChunkBuilder` (pkg-private), `RegionChunkSnapshot`, `EntitySighting`, `MapStore`, `EntityIndex`, `MapAStar`, `MapPlanner`, `WorldMemoryConfig`.
- Same-region, same-plane planning only.
- LOS-aware `findInteractTile` and `planToInteractTile`.
- `MapPlanner.planWithin` for "go to tile T" calls.
- Cooking script V3 migration via `useWorldMemoryPlanner` toggle.
- JSON-per-region persistence.
- Entity index built and persisted, but not yet exercised by the cooking proof.
- **Walker interface companion refactor:** `walker/Walker.java` extracted from `UniversalWalker`; all scripts that depend on `UniversalWalker` take `Walker` instead.

**Done when:** cooking proof-point passes (off-canvas incidents drop, no hangs).

### v2
- Cross-region routing: planner stitches chunks for paths that cross region boundaries.
- Transport-edge graph: doors, gates, stairs, ladders. Schema already locked above.
- Named-trail integration: `TrailRegistry` trails exposed to the planner as preferred "highways."
- Bootstrap data: ship a scraped baseline of common F2P areas (Lumbridge, GE, Varrock, Falador) inside the jar.
- `EntityIndex` clustering: aggregate sightings into centroid + radius for wandering NPCs.
- `gameRevision` mismatch handling: invalidate + re-scrape stale chunks.

**Done when:** cooking quest can run from GE — script starts at GE, asks for Cook, planner stitches recorded `GE → Lumbridge` trail with local Lumbridge chunk + entity sighting, walker executes.

### v3
- **Human-simulation walker (HumanWalker).** A new `Walker` impl that captures mouse traces, click pause distributions, camera yaw/pitch deltas relative to player heading during normal play, and replays them during scripted walks. Goal is *simulated* human play (not 1:1 record-replay) — sample from the captured distributions, jitter, vary per-script. Drop-in replacement for `UniversalWalker` because it implements `Walker`. The capture format and replay model both spec'd in v3.
- Teleports (Home Teleport, Glory amulet, etc.) as edges with item-state preconditions.
- Boats / charters / agility shortcuts.
- Quest-state and item-state gating on transport edges.
- Confidence scoring on entity sightings.

---

## Open implementation questions for the plan session

These need decisions during plan-writing, not now:

1. **A* implementation site.** Add an `aStar(...)` method to `Reachability` (single-class extension) or build `MapAStar` as a sibling? Lean: sibling. A* needs a goal-predicate API that BFS doesn't, and Reachability is currently used by the walker without one.

2. **LOS Bresenham implementation.** The spec already pins: derive LOS from `BLOCK_LINE_OF_SIGHT_*` bits in the same `movement` int (no separate read at scrape time). Plan-level: the per-step Bresenham routine. Mirror RuneLite's own algorithm in `WorldArea.hasLineOfSightTo` but operate on the persisted `movement` int rather than a live `WorldView`. Unit-test against a fixture with a known wall layout.

3. **MapStore eviction policy.** LRU on chunk count, or always-resident for the player's current region + neighbors? Lean: always-resident for player region + 8 neighbors (24 chunks max ≈ 100KB), LRU beyond that.

4. **Dirty-chunk tracking + snapshot publication.** Mark dirty on every mutation, or batch-diff at flush time? Lean: mutation-time mark — flush is then "find dirty, write, clear bit." Snapshot publication is independent: after each scrape, swap a fresh immutable snapshot atomically (`AtomicReference<RegionChunkSnapshot>` or similar).

5. **Synthetic collision adapter.** A* / Dijkstra needs the wall/diagonal logic. Either implement a lightweight `WorldView` adapter that reads from a `RegionChunkSnapshot`, or unpack the flags ourselves and replicate the logic. Lean: unpack ourselves (the wall/diagonal logic is well-tested truth-table code; a `WorldView` mock is fragile because `WorldView` has a wide surface).

6. **Scraper batching when player teleports.** First scrape after a teleport may straddle scene boundaries. Skip first tick or handle partial chunks? Lean: skip first tick; second-tick scrape covers it.

7. **Test fixtures.** Hand-author 2-3 small Lumbridge kitchen fixtures (one with the wall geometry that triggers the off-canvas bug) and unit-test `findInteractTile` against them.

These are tactical; the spec doesn't pre-decide them so the plan session can pick based on the implementer's read of existing code patterns.

---

## What the spec does NOT specify

These are intentionally not in scope:

- File-by-file diffs for the plan steps (that's the implementation plan)
- Per-method test enumeration (plan)
- Exact `RecorderPanel` UI for the toggle (a checkbox; trivial)
- How `RecorderPlugin` registers `SceneScraper` (one `@Subscribe` method; trivial)
- Logger names / log lines (mirror existing recorder modules)
- Migration of CookingScriptV3 line numbers (will drift before plan starts)
- Whether `Walker.Status` and `Walker.InternalState` are nested on the interface or remain on `UniversalWalker` (mechanical, plan-level)

---

## Repo touchpoints (summary)

```
NEW:
runelite-client/src/main/java/net/runelite/client/plugins/recorder/worldmap/
   SceneScraper.java
   EntityScraper.java
   RegionChunkBuilder.java         (mutable, package-private)
   RegionChunkSnapshot.java        (immutable, public)
   EntitySighting.java
   MapStore.java
   EntityIndex.java
   MapPlanner.java
   MapAStar.java
   WorldMemoryConfig.java
runelite-client/src/main/java/net/runelite/client/plugins/recorder/walker/
   Walker.java                — NEW interface (companion refactor)

MODIFIED (small):
   walker/UniversalWalker.java    — now `implements Walker`. No behavioral change.
   RecorderPlugin.java            — add @Subscribe for the scraper tick + lifecycle wiring
   RecorderConfig.java            — add boolean useWorldMemoryPlanner (default false)
   scripts/CookingScriptV3.java   — single block in currentCookPath() gated by toggle
   RecorderPanel.java             — one new checkbox

REFACTOR (mechanical, no behavioral change):
   Every script field/constructor of type `UniversalWalker` becomes type `Walker`.
   Concrete list determined at plan time via grep "UniversalWalker" in recorder/.

UNTOUCHED:
   trail/*  (TrailWalker, TrailRecorder, TrailRegistry, TrailIO, Route, Trail, ...)
   walker/Reachability.java, walker/PathSpec.java, walker/StepClickPicker.java, walker/ObstacleHandler.java
   transport/TransportResolver.java
   sequence/* engine
```
