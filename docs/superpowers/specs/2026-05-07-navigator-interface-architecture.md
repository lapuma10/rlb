# Navigator interface — V1/V2 coexistence

**Date:** 2026-05-07
**Status:** Architecture **locked**. V2 round-1 internals **locked**.
**Decided by:** user, in conversation 2026-05-07
**Supersedes / amends:** nothing yet — this is the first nav-architecture doc

---

## TL;DR

The bot has one walker today (TrailWalker, "V1") that replays recorded
trails deterministically. It looks botted because it clicks the same
tiles in the same order every cycle. We are building a second walker
("V2") that uses WorldMemory + dynamic A* + transport edges + route
and click variety.

**V1 stays exactly as-is. V2 is a separate implementation. A single
switch picks which one a script uses. They coexist forever.**

This doc locks the architecture AND the V2 round-1 internals: capture
mechanism, leg-executor click behavior with the empty-tile hard
constraint, route alternation strategy, scoped alternate-trail seed
pass, snapshot invalidation, and inspection tooling. The next step
is the implementation plan (separate doc, by the writing-plans
skill).

---

## Why this exists

A previous round of work shipped two pieces of infrastructure
(`worldmap/` WorldMemory + `trail/` TrailGraph + TrailPlanner) without
wiring either into the actual cross-region navigation that the
chicken farm uses. Result: the user kept watching TrailWalker replay
the same path and assumed nothing had been built. The infra existed,
but no script consumed it for macro navigation.

This doc is the *contract* that prevents that mistake from repeating:
any new pathfinding work goes behind the Navigator interface, gets a
switch entry, and has at least one script wired to use it. No
"foundations only" PRs.

---

## Architecture (LOCKED — do not relitigate without user approval)

### 1. TrailWalker (V1) is frozen

- No edits to `runelite-client/.../recorder/trail/TrailWalker.java`
  internals.
- No refactor, no "minor cleanup," no API renames.
- It is preserved as the known-good fallback.

### 2. There is a `Navigator` interface

- Single abstraction over "get the player from A to B," including
  transports, plane changes, and the entire macro flow.
- Exact name TBD during V2 design (`Navigator`, `PathExecutor`, etc.).
  Not yet committed.
- All scripts (`ChickenFarmV3Script`, `CookingScript`, future scripts)
  depend on this interface, NOT on a concrete walker class.

### 3. TrailWalker is wrapped behind the interface

- A thin adapter (`TrailNavigator` or similar) implements the
  interface and delegates to TrailWalker's existing public methods.
- The adapter does NOT modify TrailWalker. It only translates calls.

### 4. V2 is a second implementation of the same interface

- New package, new classes, separate from `trail/`.
- Implements the same `Navigator` interface.
- Internally uses WorldMemory, an extended MapPlanner (cross-region +
  cross-plane + transport edges), route alternation, and click-tile
  variety. (Internals to be designed in §"V2 design" below.)

### 5. Single switch in one place

- Config flag (likely `RecorderConfig` boolean +
  `RecorderPanel` checkbox, mirroring the existing
  `useWorldMemoryPlanner` pattern) chooses which Navigator
  implementation is provided to scripts.
- Switch site is ONE constructor / factory. Scripts never branch on
  which implementation is active.

### 6. Both coexist forever

- No deprecation path, no migration plan, no removal of V1.
- If V2 misbehaves, flip the switch back to V1 and the bot keeps
  working.
- This is the load-bearing reason V1 stays frozen.

---

## What this architecture explicitly REJECTS

- **Merging TrailGraph and MapPlanner into one unified world graph.**
  Considered and rejected. They stay as two independent engines —
  TrailGraph inside V1's world, MapPlanner (extended) inside V2's
  world. They do not need to know each other exists.
- **"Replacing" TrailWalker with V2.** V2 is additive. V1 lives.
- **"Foundations only" work.** Any infrastructure landed must be
  reachable through the Navigator interface and wired to at least one
  script's switch site before it counts as shipped.

---

## V2 design (LOCKED)

Every sub-section below is locked unless explicitly marked
otherwise. Implementation decisions left to the implementation plan
are called out as "implementation determines."

### Goals (from user)

1. Walking looks player-like — not the same path every cycle, not the
   same tiles within a path.
2. Pre-planned routes that can alternate between cycles.
3. Track what is where (NPCs, objects, transports) in a persistent
   world memory.

### Known building blocks (already in the repo)

- `worldmap/SceneScraper` — passively scrapes 18×18 collision +
  objects around the player every 2 ticks.
- `worldmap/MapStore` — LRU in-memory + persistent
  `~/.runelite/recorder/regions/<id>.json`.
- `worldmap/MapPlanner` — A* within one region, one plane, no
  transports. Used today only by CookingScript V3 stand-tile pick.

### Known blockers in `MapPlanner.java` (must be lifted for V2)

- Lines 32–35: cross-plane reject.
- Lines 45–50: cross-region reject.
- No transport edges in the A* graph.

### Resolved V2 design decisions

- **Scripts switched in this round:** ChickenFarmV3 only. Other
  scripts continue on whatever Navigator they have today (V1 or
  direct calls). They migrate later, not this round.

- **Bootstrap procedure (the "seed pass"):** Use existing trails as
  seed input. Run each chosen trail via V1 (TrailWalker) one time
  with V2 active in the background as a **passive observer** — V2's
  scraper and transport-capture run but V2 does NOT drive. After the
  seed pass, V2's world memory should hold enough collision +
  transport + entity data to drive these routes itself.
  - Seed routes: **Lumby bank ↔ chicken pen**, **Lumby ↔ GE**,
    **Lumby ↔ Draynor** (both directions where we have trails).
  - Seed pass doubles as a regression test for V1 (still walks fine)
    and a capture-validation test for V2 (data shows up where
    expected).
  - **Acceptance test for V2:** after the seed pass, flip the switch
    to V2 and walk **Draynor → Lumbridge** using only the scraped
    world model. If V2 reaches Lumby without the trail-replay path,
    the automap works.

### What V2 captures (LOCKED)

V2 captures **everything via live observation**. No reading of trail
JSONs at runtime, no dependency on V1's file format.

- **Collision + objects + NPCs:** today's `SceneScraper` already does
  this. V2 keeps it.
- **Transport edges:** new component `TransportObserver` (in
  `worldmap/`) subscribes to `MenuOptionClicked` in parallel to
  TrailRecorder. When the user / bot clicks a whitelisted transport
  verb (Climb-up/down, Open, Cross, Pay-toll, etc.) it records
  `(from_tile, verb, target, target_kind, param0, param1, to_tile)`
  into the region snapshot. Same machinery TrailRecorder uses, just
  a parallel sink. V1 and TrailRecorder are not touched.
- **Why no trail-JSON ingestion at boot:** the seed pass IS how V2
  gets initial data. If the live listener misses anything during the
  seed pass we want it to surface immediately so we fix the
  listener — not paper over it with backup ingestion. A one-shot
  trail-import utility can be added later if useful, but never as a
  runtime path.

### V2 leg-executor click behavior (LOCKED)

The leg executor turns "go from A to B via these waypoints" into
actual cursor moves and clicks. Variety lives here. Three click
modalities, mixed across a single trip:

- **Canvas click** (most frequent). Pick a tile along the visible
  path at a weighted-random distance — long (12–16 tiles), mid
  (6–8), short (2–3) — and click it. Position inside the tile poly
  is jittered.
- **Minimap click** (occasional alternate). Click the corresponding
  spot on the minimap instead of the canvas. Bypasses the
  "what's hovering the tile" question because minimap clicks are
  pure walk-to commands.
- **Worldmap click** (rare, behavior-mode). Occasionally open the
  worldmap, do several walk clicks via the worldmap UI, close it.
  Mimics the human "checking the map" pattern. Not per-click — a
  multi-leg session.

Plus the round-1 humanization extras the user picked:

- **Catch-up clicks.** On a small fraction of legs, V2 issues a
  redundant click on a tile already mid-path. Models the human
  "did it register?" re-click.
- **Run toggle.** V2 toggles run on/off based on energy varbit
  reading + a noise factor. (Run-orb widget click; exact widget id
  to discover during implementation.)

#### HARD CONSTRAINT: every canvas click MUST resolve to a "Walk here" left-click

NON-NEGOTIABLE. The user surfaced this explicitly.

Every candidate tile chosen for a canvas click MUST satisfy ALL of:

1. **No entity on the tile that changes the default left-click verb.**
   Filter out tiles with: any NPC standing on them, any ground item
   present, any GameObject whose first menu verb is not "Walk here"
   (so doors, stairs, ladders, fences, NPCs, items, bones, etc. all
   disqualify the tile). Use live `Scene` / `WorldView` reads on the
   client thread immediately before the click pick.
2. **Re-verified at press time.** The dispatcher already exposes an
   `isLeftClickWalk` pre-check. V2 MUST gate its press on that
   check. If the pre-check fails (an NPC walked onto the tile in
   the ~100ms between pick and press), V2 MUST abort the click,
   pick a different tile, and retry — NOT auto-fall-back to
   minimap, NOT force a menu verb. Different tile is the only
   correct response.
3. **Reachable.** The tile is on V2's planned path AND is currently
   walkable per the live collision flags (not just the snapshot).

Minimap clicks are subject to (3) but not (1) or (2) because the
minimap interprets all clicks as walk-to commands regardless of
what's on the tile. Worldmap clicks behave the same.

**Two distinct rules — don't conflate them:**

- *Per-click failure:* if THIS canvas-click target fails the
  empty-tile pre-check or `isLeftClickWalk` re-verify, the immediate
  response is **pick a different canvas tile**. Not minimap. Not a
  forced verb.
- *Between-leg modal selection:* when picking the next leg's click
  modality (canvas / minimap / worldmap), V2 may bias toward
  minimap/worldmap if the current area has been producing lots of
  empty-tile filter rejects (busy NPCs, ground items everywhere).
  This is a higher-level scheduling decision, not a fallback from a
  failed click.

### V2 route alternation (LOCKED)

Strategy: **C — top-K macro-route selection + randomized edge-cost
noise.** Why: A alone is too dependent on seed data. B alone gives
fake variety. C gives both — top-K when real alternates exist, noisy
A* for tile/path variation inside the same corridor when only one
route exists. V2 is useful immediately and gets richer as the world
model grows.

#### Planner request shape

```
Planner request:
  from = current area
  to = target area
  behaviorMode = NORMAL / EFFICIENT / VARIED / CAUTIOUS

V2 round-1 default behaviorMode = VARIED
```

**Round-1 scope:** only `VARIED` is implemented. The other modes are
forward-compatible names in the API so future work can add them
without changing the planner signature. Calling V2 with any other
mode in round 1 falls through to `VARIED` behavior with a warning
log. Implementation determines exact noise/weighting per mode for
later rounds.

#### Selection algorithm

```
if topK routes >= 2:
    70% pick weighted top-K route
    30% pick noisy A*
else:
    use noisy A*
```

Weighted top-K prefers cheaper paths but does not always pick the
cheapest:

```
Route 1 cost 100 → high weight
Route 2 cost 112 → medium weight
Route 3 cost 140 → low weight
Route 4 cost 220 → almost never
```

Do not let it pick absurd routes just for variety.

#### Route memory (avoid the alternate-alternate pattern)

Random selection without memory creates its own pattern (perfect
alternation: north, south, north, south, …). Store recent route
choices and penalize repeats:

```
recentRouteIds = last 3–5 route choices

penalty rules:
  same route last time      → mild penalty
  same route 3 times in row → strong penalty
  much worse route          → still avoid unless behaviorMode allows
```

Produces something like `north, north, south, north, south` instead
of perfect alternation. Both robotic patterns ("always north" AND
"perfect ABABAB") are out.

#### Edge-cost noise rule

```
default noise: ±10–15%

Noise APPLIES to:
  normal walk-tile edges

Noise does NOT apply to:
  transports
  gates
  ladders
  stairs
  teleports
  known bad / stale edges
```

Transport selection comes from top-K route structure, not from
random edge noise. Noise is for tile-level wiggle inside an already-
chosen macro route.

#### Top-K implementation (round 1 — keep it simple)

```
Run A* once.
Then run A* again with used edges penalized.
Repeat until K routes found or attempts exhausted.
```

Round-1 constants:

```
K                  = 3
maxAttempts        = 6
edgeReusePenalty   = 2.0× to 4.0×
rejectRouteIfCost  > 1.75× cheapest route cost
```

This is simpler than a formal Yen's K-shortest-paths and is enough
for V2. If we later need exact K-shortest, swap the implementation
without changing the planner API.

### V2 round-1 alternate seed pass (LOCKED — scoped tightly)

Yes, record alternates — but only for high-frequency repeated routes
that are visually obvious and exposed by the round-1 scripts.

**Required seed alternates (round 1):**

- Lumby bank → pen, north route (north of church)
- Lumby bank → pen, south route (south of church)
- pen → Lumby bank, north route
- pen → Lumby bank, south route

**Optional if nearby / easy to capture:**

- alternate gate route
- alternate stair / door option
- alternate kitchen / range approach

**Explicitly out of scope for round 1:**

- teleports
- boats
- agility shortcuts
- long-distance alternates
- routes requiring quest / item state
- "record the whole map five different ways"

The seed pass exists to populate V2's world model with enough
alternates that top-K has something to choose from for the
chicken-farm loop. Anything beyond that is scope creep.

### Snapshot invalidation (LOCKED)

V2 uses **continuous scrape + reactive invalidation**.

There is no TTL-based refresh in round 1. Current loaded regions are
refreshed passively by `SceneScraper`. Regions outside the loaded
scene may be stale; V2 discovers staleness through live validation
during execution.

Before each canvas/minimap/worldmap walk click, the executor
validates the chosen tile/edge against the live scene:

- live collision must still allow movement
- canvas clicks must still resolve to "Walk here"
- transport/object clicks must still expose the expected action
- the tile must still be reachable from the current player tile

If validation fails or the click produces no movement, V2 classifies
the failure:

1. **Static collision mismatch.** Snapshot says walkable, live
   collision says blocked. Mark tile/edge dirty, update from live
   scrape, replan.
2. **Temporary dynamic blocker.** NPC/player standing on tile,
   temporary object, crowded chokepoint. Apply short-lived avoidance
   penalty. Do NOT persist invalidation — this is live obstruction,
   not stale memory.
3. **Transport/object state mismatch.** Door is closed/open
   differently than expected, or expected object/action is missing.
   Mark the transport edge stale for this run/account context.
   Re-resolve the live object/action if available; otherwise replan
   or fall back.
4. **Unknown failure.** Increment failure count for that tile/edge.
   After N failures, temporarily blacklist it and replan.

The planner never blindly trusts old memory at execution time.
Memory proposes; live state confirms.

#### Failure thresholds (round 1)

```
same tile click fails once:
  retry with a different click tile on same path

same edge/tile fails twice:
  temporary blacklist for this route attempt

same persisted tile/edge fails repeatedly across sessions:
  mark dirty/stale in memory
```

A single failed click never permanently deletes map data.

#### Why not TTL

"Snapshot is too old, refuse to plan" is bad behavior for long routes
and cold areas. Old data is **probably correct, but execution must
verify** — not **expired and unusable**.

#### Soft metadata (not a hard blocker in round 1)

Keep `lastScrapedAt` timestamp and `gameRevision` on each region
snapshot. Use them to *prefer* fresher chunks when planning has a
choice, and to log when stale data is being trusted. Do NOT use them
as hard rejects. v3 can tighten invalidation later if needed.

```
if gameRevision differs:
  log stale revision
  prefer fresher chunks/routes if available
  still allow planning
  rely on live validation + recovery
```

### V2 inspection tooling (LOCKED)

Round 1 ships option B+: panel dump buttons + minimap debug overlay.
Goal: verify the seed pass actually populated V2's world model
(walkable tiles, blocked tiles, transport endpoints, entity
sightings, planned routes).

#### Panel buttons (under a collapsible "V2 Inspection" section in `RecorderPanel`)

- Dump current region
- Dump nearby regions (current + 8 neighbors)
- Dump transport graph
- Dump entity sightings
- Plan A→B
- Clear debug overlay route

Dump output dir: `~/.runelite/recorder/inspect/`

##### Dump current region

`region-<regionId>-<timestamp>.json`. Summary first (counts of known
/ walkable / blocked tiles, objects, NPCs, transport edges). Full
arrays included for diffing across runs.

```json
{
  "regionId": 12850,
  "planes": [0, 1, 2],
  "summary": {
    "knownTiles": 1234,
    "walkableTiles": 980,
    "blockedTiles": 254,
    "objects": 88,
    "npcs": 12,
    "transportEdges": 4
  },
  "tiles": [],
  "objects": [],
  "npcs": [],
  "transportEdges": []
}
```

##### Dump nearby regions

Current region + 8 neighbors. Useful because routes often fail at
boundaries — region dump answers "did this tile capture?", nearby
dump answers "did the route corridor capture?"

##### Dump transport graph

`transports-<timestamp>.json`. Every known edge with full payload:

```json
{
  "from": { "x": 3208, "y": 3216, "plane": 0 },
  "to":   { "x": 3208, "y": 3217, "plane": 0 },
  "objectId": 1530,
  "objectName": "Door",
  "verb": "Open",
  "approachTile": { "x": 3208, "y": 3216, "plane": 0 },
  "regionId": 12850,
  "seenCount": 3,
  "lastSeenAt": 1714960000000
}
```

This is the "did V2 capture the Lumbridge stairs / both pen gates /
the route-breaking door" answer.

##### Dump entity sightings

`entities-<timestamp>.json`. Used for verifying the bot knows where
cooks, ranges, bank booths, chickens, cows, gates, stairs, ladders
were last observed.

##### Plan A→B

Input: from `(x, y, plane)` and to `(x, y, plane)`.

Output to log and optional dump:

```json
{
  "from": { "x": 3208, "y": 3213, "plane": 0 },
  "to":   { "x": 3230, "y": 3298, "plane": 0 },
  "result": "success",
  "pathLength": 47,
  "regionsTouched": [12850],
  "transportEdgesUsed": [],
  "route": [
    { "x": 3208, "y": 3213, "plane": 0 },
    { "x": 3209, "y": 3213, "plane": 0 }
  ],
  "rejections": {
    "blocked": 12,
    "unknown": 4,
    "stale": 0
  }
}
```

This is the debugging weapon when the bot "should" know a path but
doesn't.

#### Minimap overlay (toggleable, off by default)

Default colors:

- **green** — known walkable tiles
- **yellow** — transport endpoints
- **blue** — active planned route

Optional toggles:

- **red** — known blocked tiles (noisy by default)
- **orange** — stale / invalidated tiles or edges
- **purple** — entity sightings / target candidates

Default rendering keeps it minimal: green known tiles, yellow
transports, blue active route.

#### No canvas overlay in round 1

Explicit non-goal. Canvas overlay adds projection edge cases, paint
complexity, and visual noise during normal play. Minimap overlay +
dumps already answer the seed-pass question. Canvas overlay can come
in V2.5 after the executor is proven.

#### Performance constraints (LOCKED)

- Overlay is debug-only and off by default.
- Draw only current region + nearby visible minimap area.
- Cap drawn points per frame.
- Recompute overlay geometry on tick or region change, NOT in
  `paint()`. Paint uses cached screen/minimap positions only.
- Do not run heavy planner work inside paint or the overlay
  pipeline.

The inspection tool must not become the source of the pathing bugs
it's meant to debug.

#### What B+ should prove during the seed pass

Walk a route, visually confirm:

- green coverage appears along the walked corridor
- yellow endpoints appear on gates / stairs / ladders / doors
- blue planned route follows known walkable tiles when the user
  hits "Plan A→B"
- missing coverage is visible immediately on the minimap
- transport graph dump contains the expected interactions

Repeat for both north and south bank↔pen routes.

---

## Definition of done for the brainstorm step

- [x] User has explicitly approved the V1/V2/interface/single-switch
      shape.
- [x] V2 round-1 internals locked: capture mechanism,
      leg-executor click behavior + hard empty-tile rule, route
      alternation, scoped seed pass, snapshot invalidation,
      inspection tooling.
- [ ] User has reviewed the written spec end-to-end and approved
      moving to the implementation plan.
- [ ] Implementation plan written (separate doc, by writing-plans
      skill).
- [ ] Implementation lands the interface, the V1 adapter, the V2
      implementation, and the switch — wired to at least
      ChickenFarmV3 — in the same merge.
