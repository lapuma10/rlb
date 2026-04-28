# Trail-Network Web-Walker Design

**Date:** 2026-04-28
**Status:** Draft for user review

## Goal

Replace the V2 walker's hand-coded `PathSpec` with a navigation system driven by **recorded trails**. A user walks a route once; the bot can later traverse that route in either direction, and can chain multiple recorded routes via shared / near-shared tiles.

The user navigates between **landmarks they have recorded**, not arbitrary tile-to-tile travel. Adding a new connection between two existing parts of the world = record one more trail. No flood-fill, no region merging, no chunk store.

## Non-goals (this round)

- Walking to anywhere on the OSRS map. Targets must lie on a recorded trail.
- Collision-aware shortcuts (clicking off-trail to skip a corner). Deferred to v2.
- Replacing `ChickenFarmV2Script` immediately. The trail walker ships **alongside** V2; integration is a follow-up session.
- Sync of trails across machines. Local-only.
- Trail editing UI (split / merge / rename).

## Architecture

Three things on disk, several things in code, one panel.

```
~/.runelite/recorder/trails/
├── lumby-bank-to-pen.json
├── pen-to-lumby-bank.json
└── lumby-to-varrock-west.json
```

```
recorder/
└── trail/                       # new package
    ├── Trail.java               # in-memory trail (list of TrailEvents + name + timestamp)
    ├── TrailEvent.java          # sealed-style: TileEvent | TransportEvent
    ├── TrailIO.java             # Gson read/write
    ├── TrailRegistry.java       # loads all trails from ~/.runelite/recorder/trails/
    ├── TrailRecorder.java       # @Subscribe-driven live capture
    ├── TrailGraph.java          # junction graph built from N trails
    ├── TrailPlanner.java        # A* over the graph
    ├── TrailPath.java           # plan output: ordered Legs
    ├── Leg.java                 # WALK leg (tile list) | TRANSPORT leg (tile + verb + objectId)
    └── TrailWalker.java         # runtime, drives a TrailPath
```

Panel additions on `RecorderPanel`:
- **Record trail** + **Stop & save** buttons (with name input).
- **Walk to ...** dev button: enter target world tile, planner runs from current position, walker drives the plan.

## Data flow

**Recording:**

```
@Subscribe(GameTick)              → if player tile changed, append TileEvent
@Subscribe(MenuOptionClicked)     → if option in transport-verb whitelist, append TransportEvent
                                    with full metadata
User clicks Stop & save           → flush buffer to ~/.runelite/recorder/trails/<name>.json
```

**Planning (lazy, at script start or first request):**

```
TrailRegistry.load()               → reads all *.json under trails/
TrailGraph.build(trails)           → constructs junction graph (in-memory)
TrailPlanner.plan(from, to)        → A* → Optional<TrailPath>
```

**Walking:**

```
TrailWalker.tick(TrailPath) per ~600 ms:
  → identify current Leg
  → if WALK leg: dispatch WALK to farthest still-ahead tile in this leg
  → if TRANSPORT leg: walk to transport tile, then dispatch CLICK_GAME_OBJECT
  → on plan end: ARRIVED
  → on no-movement > STUCK_AFTER_MS: STUCK
```

## Trail JSON schema

```json
{
  "version": 1,
  "name": "lumby-bank-to-pen",
  "recordedAt": 1714247000000,
  "events": [
    {"t": "TILE", "ms": 0,    "x": 3208, "y": 3220, "p": 2},
    {"t": "TILE", "ms": 600,  "x": 3208, "y": 3221, "p": 2},
    {"t": "TRANSPORT", "ms": 12000,
     "x": 3205, "y": 3229, "p": 2,
     "option": "Climb-down",
     "target": "Staircase",
     "targetId": 16671,
     "targetKind": "GameObject",
     "actionId": 3,
     "param0": 53, "param1": 14,
     "menuRowsAtClick": ["Climb-down Staircase", "Cancel", "Examine Staircase"]
    },
    {"t": "TILE", "ms": 12600, "x": 3205, "y": 3229, "p": 1}
  ]
}
```

Field-level notes:
- `events` is time-ordered; `ms` is offset from recording start.
- `TILE` event = player's `WorldPoint` changed. Sampled at most once per game tick.
- `TRANSPORT` event = the user left-clicked a menu entry whose option is in the transport whitelist.
- Transport metadata is the **superset of `Events.MenuClick`** (the existing recorder's menu-click record):
  - `option`, `target`, `targetId`, `targetKind`, `actionId`, `param0`, `param1` come straight from `MenuOptionClicked.getMenuEntry()` — same fields the existing recorder already collects.
  - `x`, `y`, `p`: the resolved world tile of the click target (from `param0`/`param1` decoded as scene coords).
  - `menuRowsAtClick`: the full menu the engine had open when the click fired (diagnostic — same data the debug overlay shows).
- File is human-readable for inspection; not optimised for size. A 30-minute recording sits well under 1 MB.

## Transport verb whitelist

Verbs the recorder treats as region-transition clicks:

```
Open, Close, Climb-up, Climb-down, Cross, Pass, Squeeze-through,
Jump, Climb, Climb-over, Enter, Exit, Pay-toll, Squeeze-past
```

Anything else (`Walk here`, `Talk-to`, `Attack`, `Take`, `Trade`, `Use`, `Examine`, `Cancel`, ...) is filtered. The whitelist lives as a constant in `TrailRecorder` and is easy to extend.

## Junction graph

Built once from all loaded trails.

**Nodes:** every distinct `(x, y, plane)` that appears as a `TILE` event in any trail.

**Edges (all bidirectional):**

1. **Within-trail walk** (cost 1):
   For each pair of consecutive TILE events `(t_i, t_{i+1})` in the same trail.

2. **Junction** (cost 0 or 1):
   For every pair of trail tiles `(a, b)` where `a.plane == b.plane` and Chebyshev `(a, b) ≤ 1`, add an edge of cost = Chebyshev(a, b) ∈ {0, 1}.
   - Cost 0 means "exactly the same tile in two trails" → free transfer.
   - Cost 1 means "tiles 1 step apart" → costs one walk step.

3. **Transport** (cost 1):
   For each `TRANSPORT` event in a trail, the *previous* TILE event and the *next* TILE event become endpoints of a single transport edge. The edge payload carries: `tile`, `verb`, `targetId`, `targetKind`, `param0`, `param1`. The walker uses these to dispatch `CLICK_GAME_OBJECT` regardless of which direction the edge is traversed.

**Performance note:** the junction step is naively O(N²) over all trail tiles. For 10k tiles this is 100M comparisons — slow at startup. Use a spatial hash bucketed by `(plane, x>>4, y>>4)` (16-tile bucket) and only compare a tile against tiles in the same bucket and the 8 neighbours. Brings build to ~O(N) typical-case.

## Path planning (`TrailPlanner`)

A* over the junction graph.

**Heuristic:** Chebyshev distance from the candidate node's tile to the target tile (admissible — every walk edge costs 1, every transport edge costs 1, so Chebyshev is a true minimum).

**Inputs:**
- `playerTile` — must already be on the graph (Chebyshev ≤ 1 from some trail node) or planner returns `Optional.empty()`. Off-trail starts are caller responsibility (script must start the bot at a known landmark).
- `targetTile` — must satisfy the same proximity check.

**Output: `TrailPath`** — ordered list of `Leg`s:

```
sealed Leg = WalkLeg(List<WorldPoint> tiles)
           | TransportLeg(WorldPoint tile, String verb, int objectId, String targetKind,
                          int param0, int param1)
```

Two consecutive `WalkLeg`s never appear (always coalesced into one). A `TransportLeg` is preceded and followed by `WalkLeg`s (possibly empty if the transport is the very start or end).

**No-path case:** returns `Optional.empty()`. Walker reports `"No trail covers this route — record one"` and aborts.

## Walker runtime (`TrailWalker`)

Same shape as `UniversalWalker` — a `tick(TrailPath)` method called every ~600 ms by a worker thread, returning a `Status`.

```
enum Status { IN_PROGRESS, ARRIVED, STUCK, ERROR }
```

Per-tick algorithm:

1. Read player tile on the client thread (single hop, same pattern `UniversalWalker.readSnapshot` uses).
2. **Locate active leg.** Walk forward through the plan from the last known active leg index; advance past any leg whose tile-set the player has already passed (Chebyshev ≤ 1 to a tile *later* in the plan).
3. **WALK leg active:**
   - Find the **farthest tile in this leg that is still ahead of the player.** "Ahead" = later in the leg's `tiles` list than the closest tile to the player. This is the click target.
   - **Camera:** call `dispatcher.rotateCameraToward(target)` once per advance to a new leg. The dispatcher already short-circuits if the target is comfortably visible; the call is a no-op when the player is already facing close enough. The dispatcher applies an off-axis jitter so the destination ends up roughly forward but at a corner-vision angle. Verify the existing jitter falls in the user-specified 120-150° band; tune as part of the walker task if it doesn't.
   - **Click:** dispatch `ActionRequest.WALK` with `tile = target`. The dispatcher's `walkClick` chooses minimap edge for far tiles, main-view for near tiles, with humanized jitter; verifies the engine's left-click action is `WALK`/`SET_HEADING`; minimap-fallback if not.
   - **Pacing (no spam):** re-click only when **(a)** the active leg's farthest-ahead tile changed (player has progressed) **OR** **(b)** `RECLICK_AFTER_MS` (3 s) has passed since last click **AND** player has been still for ≥ `STILL_THRESHOLD_MS` (2.5 s). Otherwise wait — the engine is still walking from the previous click.
4. **TRANSPORT leg active:**
   - If player is not adjacent (Chebyshev > 1) to the leg's tile, treat as a single-tile WALK leg toward that tile.
   - If adjacent and player pose is idle, dispatch `ActionRequest.CLICK_GAME_OBJECT` with the leg's `tile`, `verb`. The dispatcher already finds the matching object by id (via `TransportResolver`), hovers, hull-pixel samples, left-clicks if verb is L-click default, otherwise right-click → menu pick.
   - Throttle between transport clicks: `INTERACT_THROTTLE_MS` (3 s) — same constant `UniversalWalker` uses.
   - Advance leg when the player's tile reaches a tile in the **next** leg.
5. **Stuck detection:** if the player has not moved for `STUCK_AFTER_MS` (15 s), return `STUCK`. Caller decides whether to abort or trigger replan.

## Replan trigger

Inside `tick`, if the player tile is **off-plan** — Chebyshev > 1 from any tile in the active leg AND not adjacent to the leg's transport tile — invoke `TrailPlanner.plan(playerTile, targetTile)` for a fresh path. Replanning is rate-limited to once per 3 seconds. If the new plan is empty, return `STUCK`.

This handles random teleport, player bumps, and "the bot's drifted into the wrong area."

## Existing code we touch

- **`RecorderPanel.java`** — three new buttons (Record / Stop & Save / Walk to...).
- **`RecorderPlugin.java`** — Guice binding for `TrailRecorder`.
- **New package** `recorder/trail/` — all new files.
- **`HumanizedInputDispatcher`** — verify `rotateCameraToward(WorldPoint)` already produces 120-150° off-axis rotation; tweak jitter constants if it currently does something narrower.
- **No changes** to `UniversalWalker`, `PathSpec`, `Reachability`, `StepClickPicker`, `ObstacleHandler`, `ChickenFarmV2Script`, `LumbridgeBankPenScript`. Trail walker ships as a parallel system.

## Testing strategy

**Unit tests** (mirroring existing `walker/*Test.java` patterns — synthetic data, mocked client where needed):

- `TrailIOTest` — Gson roundtrip on a hand-built trail; assert all transport metadata preserved bit-for-bit.
- `TrailRecorderTest` — drive a stub `EventBus` with synthetic GameTick + MenuOptionClicked events; assert correct sequence of TILE / TRANSPORT events captured. Verify non-whitelist verbs (e.g. `Take`, `Talk-to`) are filtered.
- `TrailGraphTest` — build graph from two synthetic trails:
  - That share an exact tile → assert cost-0 junction.
  - That pass within 1 tile → assert cost-1 junction.
  - That pass at distance 2 → assert no junction.
  - With a transport in trail A but not B → assert transport edge present in graph, walkable in both directions.
- `TrailPlannerTest` — 3-trail synthetic graph (lumby ↔ varrock ↔ falador shape with transports between). Plan A→C, assert leg sequence visits both transports in order. Plan A→Z (Z not on any trail) → `Optional.empty()`.
- `TrailWalkerTest` — with a mocked `HumanizedInputDispatcher` and a stubbed client (returns scripted player tiles per tick), drive a 2-leg plan (WALK + TRANSPORT + WALK). Assert: dispatcher receives WALK with farthest-tile target on first tick; no second WALK during STILL_THRESHOLD_MS; CLICK_GAME_OBJECT dispatched once player reaches the transport tile; ARRIVED returned after final WALK leg ends.

**End-to-end smoke (manual, in-game):**

- Record one trail: lumby bank → chicken pen. Click Stop & save.
- Inspect the JSON: visually confirm tiles + transport metadata.
- Use **Walk to ...** panel button: enter pen-area tile from inside lumby bank → bot walks the recorded path. Repeat in reverse.
- Record a second trail: lumby bank → varrock west bank. Walk to varrock from chicken pen → planner chains the two trails via the lumby-bank junction.

## Out of scope this round

- `ChickenFarmV2Script` integration — separate plan after this lands and smokes clean.
- Collision capture / off-trail shortcuts — v2.
- Trail editing (split, merge, rename) via panel.
- Live overlay of the active plan on the world map.
- One-way agility shortcuts — record both directions for now if needed.
- Off-trail start handling — caller's responsibility to start at a recorded landmark.

## Risk notes

- **Dispatcher's `rotateCameraToward` may not currently produce the 120-150° jitter the user spec'd.** Verify before relying on it; if it's narrower (or unrelated), the plan will need a small dispatcher tweak as part of the walker task.
- **Junction cost-1 might over-connect dense areas** (4 trails through Lumbridge bank = many cross-edges). A* still works but plans may zig-zag visually. Acceptable for v1; revisit if real recordings produce visibly weird paths.
- **Replan loop bug risk**: if A* keeps producing an empty plan and the walker keeps replanning, we spin. The 3-second rate-limit prevents CPU spin but the bot still sits idle. Stuck detection at 15 s catches this and surfaces the failure.
- **Trail file naming collisions**: re-using a name overwrites. The Save button should warn before overwrite, but a v1 may simply overwrite silently. Document the behaviour either way.
