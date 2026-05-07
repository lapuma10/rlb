# Navigator interface — V1/V2 coexistence

**Date:** 2026-05-07
**Status:** Architecture **locked**. V2 round-1 internals **locked**.
**Decided by:** user, in conversation 2026-05-07
**Supersedes / amends:** nothing yet — this is the first nav-architecture doc

---

## TL;DR

The bot has one walker today (TrailWalker, "V1") that replays recorded
trails deterministically. Deterministic replay is brittle: when the
recorded tile is blocked, claimed by an entity, or stale relative to
live world state, V1 has no recovery path other than re-running the
same fixed leg. We are building a second walker ("V2") that uses
WorldMemory + dynamic A* + transport edges + route and click variety
to make navigation less brittle.

**V1 remains the supported fallback indefinitely. V2 is a separate
implementation. A single switch picks which one a script uses.**

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

### 6. V1 remains the supported fallback indefinitely

- No active deprecation path, no migration plan, no removal of V1
  on the round-1 timeline.
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

1. Walking is less brittle than deterministic trail replay:
   - routes can vary when equivalent alternatives exist in the world
     model
   - click targets are selected from currently valid, reachable tiles
     (live state, not the recording)
   - executor avoids repeating stale or unsafe click points and
     recovers when a click does not register
2. Pre-planned routes that can alternate between cycles when more
   than one route is known to be viable.
3. Track what is where (NPCs, objects, transports) in a persistent
   world memory so the planner has real data to work with.

### Known building blocks (already in the repo)

- `worldmap/SceneScraper` — passively scrapes 18×18 collision +
  objects around the player every 2 ticks.
- `worldmap/MapStore` — LRU in-memory + persistent under
  `~/.runelite/recorder/worldmap/regions/<id>.json`.
- `worldmap/MapPlanner` — A* within one region, one plane, no
  transports. Used today only by CookingScript V3 stand-tile pick.

### Known blockers in `MapPlanner.java` (resolved in Phase 4 by MultiRegionAStar)

- Lines 32–35: cross-plane reject.
- Lines 45–50: cross-region reject.
- No transport edges in the A* graph.

**Resolution (Phase 4):** MapPlanner is unchanged. V2's
`MultiRegionAStar` composes single-region MapPlanner-equivalent
expansions plus transport edges into one A* graph; the cross-region /
cross-plane / transport-aware logic lives in `MultiRegionAStar`, not
inside MapPlanner. MapPlanner stays as the single-region helper used by
existing CookingScript V3.

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
  - Seed routes: **Lumby bank ↔ chicken pen** (north and south
    alternates both required), **Lumby ↔ GE**, **Lumby ↔ Draynor**
    (both directions where we have trails).
  - Seed pass doubles as a regression test for V1 (still walks fine)
    and a capture-validation test for V2 (data shows up where
    expected).
  - **Acceptance tests for V2 (in order):**
    1. **Primary:** flip the switch to V2 and run ChickenFarmV3
       through several Lumby bank ↔ chicken pen cycles. V2 must
       complete the loop using its own world model, AND the route-
       selection layer must demonstrably pick both north and south
       alternates across cycles (visible in inspection logs / dump
       output, not just in code).
    2. **Secondary:** with V2 still active, walk **Draynor →
       Lumbridge** using only the scraped world model from the seed
       pass. If V2 reaches Lumby without falling back to V1, the
       cross-region planning works.
    Primary must pass before secondary is treated as load-bearing.

### What V2 captures (LOCKED)

V2 captures **everything via live observation**. No reading of trail
JSONs at runtime, no dependency on V1's file format.

- **Collision + static / interactive objects:** today's
  `SceneScraper` already populates `regions/<id>.json` with this.
  V2 keeps it. Tile collision flags + GameObject records (id,
  position, action verbs) are the source of truth for the planner's
  walk graph.
- **Entity sightings (NPCs + object/entity lookup records)** persist
  separately under `~/.runelite/recorder/worldmap/entities/<id>.json`.
  This is the single source of truth for `EntityIndex` and entity
  inspection dumps. **NPCs are NOT duplicated into `regions/`**, even
  if a tile happened to have an NPC standing on it at scrape time —
  that ephemeral occupancy is not part of the static map.

Source-of-truth rule (LOCKED so dumps and lookups don't drift):

```
regions/<id>.json   collision + static / interactive objects (planner walk graph)
entities/<id>.json  NPC sightings + entity lookup records (entity index)
inspect/...         may combine both into one report — read-only consumer of the two above
```
- **Transport edges:** new component `TransportObserver` (in
  `worldmap/`) subscribes to `MenuOptionClicked` in parallel to
  TrailRecorder. Same source event TrailRecorder uses, just a
  parallel sink. V1 and TrailRecorder are not touched.

#### TransportObserver capture lifecycle

Transport edges cannot be recorded as a single instantaneous event.
Stairs, ladders, doors, gates: the click happens on tick T, the
player position/plane only changes some ticks later when the
animation/script resolves. The observer therefore captures in two
phases:

1. **Pending interaction (on `MenuOptionClicked`)** — record:
   - `fromTile` (player tile at click time)
   - clicked object / NPC / menu target id
   - verb (Open / Close / Climb-up / Climb-down / Cross /
     Pay-toll / etc.)
   - menu params (param0, param1, target kind)
   - timestamp
2. **Resolution (on next position / plane / state change)** — fill
   in:
   - `toTile` (player tile after the action settles)
   - plane delta
   - success / failure
   - observed duration

If no movement, plane change, or relevant state change occurs within
a bounded timeout (implementation determines exact tick count),
discard the pending capture and log it as unresolved. Do NOT persist
half-formed transport edges — they would corrupt the planner.

Persisted transport edges live with their region snapshot OR in a
top-level `~/.runelite/recorder/worldmap/transports.json` index
(implementation determines which; both are acceptable).

- **Why no trail-JSON ingestion at boot:** the seed pass IS how V2
  gets initial data. If the live listener misses anything during the
  seed pass we want it to surface immediately so we fix the
  listener — not paper over it with backup ingestion. A one-shot
  trail-import utility can be added later if useful, but never as a
  runtime path.

#### Persistence layout (LOCKED)

```
~/.runelite/recorder/worldmap/regions/<id>.json     region snapshots (collision + objects + NPCs)
~/.runelite/recorder/worldmap/entities/<id>.json    entity sightings per region
~/.runelite/recorder/worldmap/transports.json       transport graph (or per-region; impl decides)
~/.runelite/recorder/inspect/                        inspection dumps (separate from world memory)
~/.runelite/recorder/trails/                         V1 trail JSONs — UNCHANGED
```

World memory data lives under `worldmap/`. Inspection output is
separate so it never competes with the read path.

### V2 leg-executor click behavior (LOCKED)

The leg executor turns "go from A to B via these waypoints" into
actual cursor moves and clicks. Variety lives here, scoped to what
the executor can do safely without coupling to widget-state edge
cases that haven't been validated yet.

Round-1 active modalities:

- **Canvas click** (default). Pick a tile along the visible path at
  a weighted-random distance — long (12–16 tiles), mid (6–8), short
  (2–3) — and click it. Position inside the tile poly is jittered.
- **Minimap click** (occasional alternate). Click the corresponding
  spot on the minimap. Used to avoid entity-hover ambiguity in busy
  areas where canvas candidates keep getting filtered out.

Round-1 deferred modalities:

- **Worldmap click — DEFERRED to V2.5.** The Navigator interface and
  modal-selection layer reserve a slot for `WORLDMAP` mode, but no
  worldmap click code ships in round 1. Reasons: widget open/close
  state, coordinate transforms, possible interference with script UI
  state, additional failure modes during seed validation. Reactivate
  in V2.5 once the canvas + minimap path is proven and the seed
  pass shows clean capture.

Round-1 executor robustness/variety extras:

- **Catch-up clicks.** On a small fraction of legs, V2 may issue a
  bounded redundant re-click on a tile already mid-path when
  movement progress stalls or the executor has reason to doubt the
  previous click registered. NOT a per-cycle decoration — a
  recovery mechanism gated on stalled progress signals. Bound the
  number per leg.
- **Run toggle (interface only in round 1, behavior deferred).**
  The Navigator interface exposes a run-toggle hook so future work
  can wire it without API churn. Round-1 executor does NOT toggle
  run state by default — it leaves whatever state the user/script
  configured. Activation requires implementation to confirm the
  varbit/widget path is stable AND a follow-up sign-off; until
  then, run state is untouched. This avoids run-toggle bugs
  contaminating planner/executor proof.

#### HARD CONSTRAINT: every canvas click MUST resolve to a "Walk here" left-click at the actual click point

NON-NEGOTIABLE. The user surfaced this explicitly.

The **authoritative check** is the live menu at the intended click
pixel, immediately before press. Static tile/object filtering is a
**pre-filter only** — it cuts obviously bad candidates cheaply, but
does not by itself certify a tile as safe to click. Camera angle,
click point inside the tile poly, model bounds, and live entity
movement can all change which menu verb the click pixel resolves to,
even on a tile that looked clean to the pre-filter.

Every canvas click MUST satisfy ALL of:

1. **Pre-filter (cheap, advisory).** Drop candidates with obviously
   problematic content on the tile: any NPC standing on it, any
   ground item, any GameObject whose first menu verb is plausibly
   non-walk (doors, stairs, ladders, etc.). Use live
   `Scene` / `WorldView` reads on the client thread at pick time.
   This filter is conservative — it may reject some technically-
   clickable tiles, that's fine.
2. **Authoritative live verification at press time.** The dispatcher
   already exposes an `isLeftClickWalk` pre-check that reads the
   actual menu the engine would build at the intended click pixel.
   V2 MUST gate the press on this check. If it fails, V2 MUST abort
   the press, pick a different canvas tile, and retry — NOT
   auto-fall-back to minimap, NOT force a menu verb. Different tile
   is the only correct response. The pre-filter passing does not
   override this — `isLeftClickWalk` is the truth.
3. **Reachable.** The tile is on V2's planned path AND is currently
   walkable per the live collision flags (not just the snapshot).

Minimap clicks have their own preconditions:

- minimap is visible on screen
- no blocking interface or modal dialog covering the minimap region
- the target minimap point falls inside the clickable minimap
  bounds (not on the border, orb, compass, etc.)
- live reachability + progress validation after the click (player
  actually starts moving; if not, treat as a click failure and
  classify per the Snapshot Invalidation rules)

So minimap is safer than canvas with respect to entity-hover
ambiguity, but not unconditional. Worldmap (deferred to V2.5) would
have analogous preconditions when activated.

**Two distinct rules — don't conflate them:**

- *Per-click failure:* if THIS canvas-click target fails the
  pre-filter or `isLeftClickWalk` re-verify, the immediate response
  is **pick a different canvas tile**. Not minimap. Not a forced
  verb.
- *Between-leg modal selection:* when picking the next leg's click
  modality (canvas / minimap), V2 may bias toward minimap if the
  current area has been producing lots of pre-filter or
  `isLeftClickWalk` rejects (busy NPCs, ground items everywhere).
  This is a higher-level scheduling decision, not a fallback from a
  single failed click.

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
of perfect alternation. Both deterministic patterns ("always north"
AND "perfect ABABAB") are out — both are equally brittle replays
that fail the moment one route becomes unviable.

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

Before each canvas/minimap walk click (worldmap deferred to V2.5),
the executor validates the chosen tile/edge against the live scene:

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
