# Observation-Aware Navigation Engine — Design Spec

**Date**: 2026-05-16
**Status**: design locked pending user file-level review. Lanes 2–6 may not dispatch until this spec is user-approved.
**Supersedes**: nav-v2 planner stack (`V2Planner`, `MultiRegionAStar`, `TopKRouter`, `RouteHistory`, trail-bias coefficient, cost-knob constants).
**Preserves**: `V2Executor` core (modified in place), `V2ExecutorEnv` (modified — latch budget audit), `V2Navigator` (modified — typed-result consumption), `HybridNavigator`, `NavRequest`, `Navigator` interface, `InvalidationClassifier` (folded into predicate provider), V1 `TrailWalker` (fallback during transition).

## 0. Master direction — drift gate

Every implementation lane is briefed with this text **verbatim**. Lanes that drift from it are out of spec, regardless of whether the deviation looks like an improvement.

```
Build one new observation-aware navigation core to replace the active V2 planner.

The goal is NOT to patch the current A*/cost-knob system. The goal is to replace
the planning model with a two-tier navigation system:

  1. High-level route planning over transports / links.
  2. Tile-level BFS over collision data.

The planner outputs sparse waypoints and typed transport legs. The executor owns
concrete tile choice, local sidestep behavior, and click execution.

Required substrate:
  - bundled global collision data (Skretzo snapshot)
  - live local collision overlay from currently loaded scene
  - transport/link data (Skretzo TSV schema)
  - runtime tile predicates
  - sparse waypoint output
  - typed executor results
  - observable debugging/QC logs

Hard prohibitions:
  - Do not build another route-replay system.
  - Do not tune numeric A* costs.
  - Do not make exact tile-by-tile walking the main output.
  - Do not let executor mutate planner transport state mid-route.
```

## 1. Architecture — data flow

Two-tier graph planner forked from Skretzo's open-source RuneLite shortest-path plugin (BSD-2-Clause; compatible with this fork's license). Top tier: Dijkstra over a transport link graph. Bottom tier: BFS over collision tiles, cardinal-first expansion order (W/E/S/N/SW/SE/NW/NE) **intended to align with Jagex's server algorithm** (alignment validated by Lane 3 property tests against OSRS-wiki reference cases, not assumed before validation), bounded by 128×128. Collision data: bundled global snapshot merged with live `client.getCollisionMaps()` overlay for the loaded 104×104 scene at plan time. Runtime tile predicates consulted at BFS expansion **and** at executor tile pick. Variety: BFS diagonal tie-break permuted per cycle, plus executor sidestep-within-bucket producing emergent trace variation.

```
NavigationRequest
   ↓
WorldSnapshot (immutable for one plan call)
   - global collision snapshot
   - live 104×104 scene overlay
   - visible dynamic blockers (actors, objects)
   - transport availability (requirements pre-evaluated)
   - active tile predicates
   ↓
WaypointPlanner.plan(...)
   - LinkGraphDijkstra over transports → high-level route skeleton
   - SkretzoBfsKernel per leg → tile-level walking
   - PathCompressor → sparse waypoint emission
   - RouteValidator → independent collision/adjacency check
   ↓
V2Path
   - List<Waypoint> with tolerances
   - List<TransportLeg> typed
   - replan boundaries
   ↓
V2Executor.tick(...)
   - SidestepResolver picks concrete tile in current bucket
   - dispatches walk or transport
   - returns typed ExecutorResult
   ↓
V2Navigator
   - decides: continue / retry / replan / fail
   - owns replan policy (no cross-layer flags)
```

## 2. Non-negotiable ownership rule

```
Planner owns route intent.
Executor owns concrete movement.
Navigator owns replanning.
```

No layer secretly does all three. Any violation is a spec violation, not a design choice. Lane 6 enforces.

## 3. Locked contracts — Lane 1 deliverable

These interfaces are the contract every other lane builds against. Lane 1 (this spec) owns them. Changes after spec approval require Lane 1 approval; Lanes 2–6 cannot modify these unilaterally.

```java
public interface Waypoint {
  WorldPoint target();
  int toleranceRadius();           // ≥0; 0 = exact required
  WaypointType type();
  boolean exactRequired();          // shortcut for toleranceRadius() == 0
}

public enum WaypointType {
  WALK,                  // tolerance ≥ 1; sidestep allowed
  TRANSPORT_APPROACH,    // exact tile required to interact with transport object
  OBJECT_INTERACTION,    // executor performs an action verb here
  REGION_BRIDGE,         // anchor at region/scene boundary
  SAFETY_ANCHOR          // user/trail-injected required-touch tile (sparse only; see §8)
}

public interface V2Path {
  List<PathStep> steps();          // ORDERED — walk/transport interleaving is explicit
  PathId id();
  long planEpochMs();
  // No tile-by-tile sequence as primary product.
}

public sealed interface PathStep permits WalkStep, TransportStep {}

public non-sealed interface WalkStep extends PathStep {
  Waypoint waypoint();
}

public non-sealed interface TransportStep extends PathStep {
  TransportLeg transport();
}

public interface TransportLeg {
  WorldPoint from();
  WorldPoint to();
  TransportType type();
  Optional<Integer> objectId();
  Optional<String> action();
  TransportRequirement requirement();
}

public enum TransportType {
  DOOR, GATE, STAIRS_UP, STAIRS_DOWN,
  AGILITY_SHORTCUT, FAIRY_RING, SPIRIT_TREE,
  TELEPORT_ITEM, TELEPORT_SPELL,
  CHARTER, REGION_LINK
}

public interface TransportRequirement {
  boolean satisfiedBy(NavigationContext ctx);
  // checks skill levels / quest varbits / inventory / equipment / membership / spellbook
}

public interface WorldSnapshot {
  CollisionFlags collisionAt(WorldPoint p);   // global merged with live overlay
  Set<WorldPoint> blockingActorTiles();        // actors that block movement (non-passable NPCs / players)
  Set<WorldPoint> blockingObjectTiles();       // dynamic objects that block movement
  TransportTable transports();
  PredicateRegistry predicates();
  long capturedAtMs();
  // immutable for the duration of one plan call.
}

public interface PlayerState {
  int skillLevel(Skill skill);
  int boostedLevel(Skill skill);
  int varbit(int varbitId);
  int varplayer(int varpId);
  ItemContainer inventory();
  ItemContainer equipment();
  boolean isMember();
  // Quest progress is read via varbits; no separate Quest API.
  // Spellbook is read via varbits.
  // immutable for the duration of one plan call (snapshotted at plan entry).
}

public interface NavigationContext {
  WorldSnapshot world();
  PlayerState player();
  NavRequest request();
  // Used by TransportRequirement.satisfiedBy(...).
  // Composes world geometry + player capabilities + request intent in one object
  // so requirements can read all three without lane coupling.
}

public interface PathContext {
  NavigationContext navigation();    // world + player + request
  Optional<V2Path> currentPath();
  Optional<Waypoint> currentWaypoint();
  long routeSeed();
  // Passed to TilePredicate.accept(...) during BFS expansion AND executor pick.
}

public interface TilePredicate {
  boolean accept(WorldPoint tile, PathContext ctx);
  // PURE FUNCTION. Evaluated at BFS expansion AND at executor tile pick.
  // No side effects; no route-planning logic.
}

public interface ExecutorTickResult {
  ExecutorResult result();
  Optional<ReplanReason> replanReason();
  Optional<WorldPoint> playerAt();
  Optional<Waypoint> currentWaypoint();
  Optional<TransportLeg> currentTransport();
  String debugTraceId();           // never null/empty; Lane 6 correlates per-tick logs by this
}

public enum ExecutorResult {
  WAYPOINT_REACHED,
  TRANSPORT_COMPLETED,
  PATH_COMPLETED,
  NEEDS_REPLAN,
  STUCK,
  FAILED
}

public enum ReplanReason {
  NO_LOCAL_WALKABLE_TILE,
  TRANSPORT_UNAVAILABLE,
  REGION_NOT_LOADED,
  COLLISION_CHANGED,
  TARGET_UNREACHABLE,
  EXECUTOR_TIMEOUT,
  PREDICATE_DENIED_CORRIDOR
}
```

**Contract ownership rules**:
- Lane 1 owns these interfaces — no edits without Lane 1 approval.
- Implementations live in the appropriate domain lane (e.g., the `PathContext` interface is Lane 1's; the concrete `PathContextImpl` lives in Lane 4's `nav/v2/planner/`).
- `NavigationContext` may be constructed by Lane 4 (planner entry) or by Lane 2 (`WorldSnapshotBuilder`) — both paths produce the same shape.
- `PlayerState` is snapshotted at plan entry by Lane 2 (`WorldSnapshotBuilder`) and is immutable for the duration of one plan call, matching `WorldSnapshot`'s lifecycle.

## 4. Lane structure + file ownership

6 lanes. **No two lanes own the same file.** Cross-lane usage is import-only — Lane B may import from Lane A, but only Lane A may edit Lane A's files. Cross-lane edits require Lane 1 approval.

**Subpackage convention** (deviation from current flat `nav/v2/` layout): each lane's new files live under a subpackage matching its domain. This makes file ownership unambiguous for the parallel team. Flagged in §11 for user sign-off.

### Lane 1 — Spec / Contracts Owner

- **Status**: this document.
- **Owns**: this spec file + the contract interfaces in §3.
- **Deliverable**: lock interfaces before Lane 2–6 dispatch.
- **Authority**: any post-dispatch contract change requires Lane 1 approval.
- **Completes when**: user approves this spec at the file level.

### Lane 2 — Collision + Predicates

- **Owns** (new files):
  - `nav/v2/collision/CollisionView.java`
  - `nav/v2/collision/GlobalCollisionSnapshot.java`
  - `nav/v2/collision/LiveSceneCollisionOverlay.java`
  - `nav/v2/collision/WorldSnapshotBuilder.java`
  - `nav/v2/predicate/TilePredicate.java` (impl + composition utilities)
  - `nav/v2/predicate/PredicateRegistry.java`
  - `nav/v2/predicate/BuiltInPredicates.java`
- **Owns** (resources):
  - `runelite-client/src/main/resources/runelite/nav/collision/` (Skretzo collision snapshot data)
- **Owns** (tests): mirror under `src/test/java/...`

**Note**: `TilePredicate` and `PathContext` are defined in §3 (Lane 1 owns the interfaces). Lane 2 implements `PredicateRegistry` and the built-in predicates; the concrete `PathContextImpl` lives in Lane 4. Cross-lane interface changes require Lane 1 approval.

**Built-in predicates** (all are pure functions):
- `NotBlocked` — collision flag check (movement-blocking only)
- `LiveCollisionAllows` — live overlay supersedes static snapshot
- `SceneClean` — **no movement-blocking actor or dynamic object on tile**. Not literally "no entity exists on tile" — non-blocking entities (ground items, passable NPCs, decorative objects) do not trigger this predicate. Otherwise the planner would avoid valid tiles for no reason.
- `NotOccupiedByBlockingActor` — specifically actor-occupancy that blocks movement
- `NotOccupiedByBlockingObject` — specifically dynamic-object occupancy that blocks movement
- `InteractionModeWorld` — `InteractionObserver.mode() == WORLD`
- `NotDangerousArea` — wilderness / instanced PvP unless opted in
- `ScriptAllowed` — pass-through for script-injected predicates

**QC tests** (Lane 2 ships, Lane 6 inspects):
1. Same tile in global snapshot and live overlay → live overlay wins.
2. Tile outside loaded scene → falls back to global snapshot.
3. Plane mismatch → does not return collision from wrong plane.
4. Region edge → cross-loaded/unloaded boundary does not corrupt coordinates.
5. Snapshot immutability → if live data changes during planning, current plan uses one consistent snapshot.

**Required debug output** (Lane 6 expects this format):
```
flagsAt(WorldPoint p):
  plane: N
  source: GLOBAL_SNAPSHOT | LIVE_OVERLAY
  flags: 0x...
  neighbors: [N,S,E,W,NE,NW,SE,SW] walkable y/n, source per neighbor
```

### Lane 3 — BFS Kernel + Route Validator

- **Owns** (new files):
  - `nav/v2/bfs/SkretzoBfsKernel.java`
  - `nav/v2/bfs/BfsConfig.java` (cardinal order, diagonal tie-break seed)
  - `nav/v2/bfs/RouteValidator.java`
- **Owns** (tests): mirror

**Requirements**:
- BFS over collision tiles, cardinal-first expansion order (W/E/S/N/SW/SE/NW/NE) — **server-alignment with Jagex is the goal, validated by Lane 3 property tests (§10 risks). Not asserted as fact before validation.**
- Wall-edge flag handling (walls block movement from a direction only, not occupancy of the tile).
- Pillar / corner blocking (diagonal cannot cut through a closed corner).
- 128×128 bounded search (intended to match Jagex limit; verify by test).
- Deterministic under fixed `BfsConfig.routeSeed`.
- Optional diagonal tie-break input (24 permutations of SW/SE/NW/NE); cardinal order **always** fixed.

**RouteValidator contract** — runs AFTER BFS, never trusts the kernel's own output:
```
for each step in path:
  - tiles are adjacent (chebyshev = 1)?
  - collision flags permit movement in that direction?
  - same plane, or a TransportLeg explains the change?
  - no diagonal cut through a blocked corner?
  - predicate accepts the tile?
```
Validator failure → path rejected, planner gets `TARGET_UNREACHABLE`. Bug in BFS → loud failure, not silent miswalk.

**QC tests** (Lane 3 ships):
1. Straight corridor → direct path.
2. Wall between two tiles → does not cross wall edge.
3. Diagonal near blocked corner → routes around.
4. Pillar / corner obstacle → routes around, not through.
5. Fully blocked target → returns unreachable with reason.
6. Same input + same tie-break seed → byte-identical output (determinism).
7. Same input + alternate tie-break seed → still valid path (not necessarily identical).
8. RouteValidator catches a hand-crafted invalid path → rejects loudly.

### Lane 4 — Transport Graph + Waypoint Planner

- **Owns** (new files):
  - `nav/v2/transport/TransportTable.java`
  - `nav/v2/transport/TransportLink.java`
  - `nav/v2/transport/LinkGraphDijkstra.java`
  - `nav/v2/transport/TransportRequirementEvaluator.java`
  - `nav/v2/planner/WaypointPlanner.java`
  - `nav/v2/planner/PathCompressor.java`
  - `nav/v2/planner/PathContextImpl.java` (concrete implementation of `PathContext` interface from §3 — Lane 1 owns the interface, Lane 4 owns the impl)
  - `nav/v2/planner/NavigationContextImpl.java` (concrete `NavigationContext` builder; pulls `WorldSnapshot` from Lane 2's `WorldSnapshotBuilder` and `PlayerState` from same)
- **Owns** (resources):
  - `runelite-client/src/main/resources/runelite/nav/transports/` (Skretzo TSVs)
- **Owns** (tests): mirror

**TransportTable startup output** (INFO log, mandatory):
```
Loaded transports: X
Invalid transports: Y (with file:line for each dropped row)
One-way links: A
Two-way links: B
Plane-changing links: C
Requirement-gated links: D
```
If invalid > 0, log per-row reason. **Loud failure on parse errors** — silent drops are forbidden.

**WaypointPlanner.plan(NavigationRequest, WorldSnapshot, BfsConfig) → V2Path**:
1. `LinkGraphDijkstra` over transport+region graph: `from → to` skeleton.
2. For each `(link_i, link_{i+1})` leg: `SkretzoBfsKernel.plan(leg.from, leg.to, predicates, seed)`.
3. `PathCompressor`: collapse tile sequences into sparse waypoints. Preserves:
   - `TRANSPORT_APPROACH` tiles (exact)
   - Plane-change anchors
   - `REGION_BRIDGE` anchors
   - Predicate-edge anchors (where a predicate rejection would change corridor)
4. `RouteValidator` (Lane 3) re-runs across compressed path before emission.
5. Emit `V2Path`.

**Compression invariants** (Lane 6 verifies):
- Does not remove transport approach tiles.
- Does not remove plane-change anchors.
- Does not compress across collision corridors.
- Does not turn `exactRequired = true` waypoints into loose waypoints.

**QC tests** (Lane 4 ships):
1. Same-region route → no transport legs emitted.
2. Cross-region route → walking + transport legs emitted in correct order.
3. Transport approach → exact tolerance=0 waypoint emitted before interaction.
4. Normal walking → loose tolerance ≥ 2 emitted.
5. Compression → 300-tile sequence becomes ~5–15 sparse waypoints, validates.
6. Validation → invalid compressed route rejected with typed reason.
7. Requirement-gated transport → if requirement unsatisfied, transport excluded from graph.
8. One-way transport → does not allow reverse unless explicitly defined.

### Lane 5 — Executor Integration

- **Owns** (modifies existing files):
  - `nav/v2/V2Executor.java`
  - `nav/v2/V2ExecutorEnv.java`
  - `nav/v2/V2Navigator.java`
  - `nav/v2/InvalidationClassifier.java`
- **Owns** (new interface files in flat `nav/v2/`):
  - `nav/v2/PathStep.java` (sealed, permits `WalkStep` + `TransportStep`)
  - `nav/v2/WalkStep.java`
  - `nav/v2/TransportStep.java`
  - `nav/v2/ExecutorTickResult.java` (interface)
  - `nav/v2/TransportCorrectionRequest.java` (typed callback replacing direct executor→TransportTable mutation)
- **Owns** (new impl files in subpackage):
  - `nav/v2/executor/SidestepResolver.java`
  - `nav/v2/executor/ExecutorTickResultImpl.java`
  - `nav/v2/executor/PathStepCursor.java`
- **Owns** (tests): mirror

**Requirements**:
- Consume `V2Path` waypoints (not tile-by-tile sequences).
- Per tick: pick furthest-forward walkable+clean tile within current waypoint's tolerance bucket.
- Sidestep up to ±2 tiles perpendicular to direction-of-travel without escalating.
- Execute typed `TransportLeg` (existing transport-leg execution restructured to accept `TransportLeg` input).
- Return `ExecutorTickResult` (defined in §3) which bundles `result`, optional `replanReason`, optional `playerAt`, optional `currentWaypoint`, optional `currentTransport`, and a required `debugTraceId`. Lane 6 correlates per-tick logs by `debugTraceId`. No bare-enum returns; no out-of-band error channels.
- **Remove** `wantsReplanFromHere` flag — `V2Navigator` reads `ExecutorResult.NEEDS_REPLAN` and decides.
- **Remove** executor's mid-route `TransportTable` mutation — corrections flow through `InvalidationClassifier` (folded as a `TilePredicate` provider).
- `V2ExecutorEnv.onClient` latch waits collapsed from current 4–6/tick to ≤2/tick (instrumented in Lane 5 tests).

**Sidestep rule** (strict, Lane 6 verifies):
A sidestep tile may be picked only if **ALL** of:
- Collision permits the move.
- All active predicates accept the tile.
- Tile is within tolerance corridor of current waypoint OR progresses toward the next.
- Tile does not skip a `TRANSPORT_APPROACH`, `OBJECT_INTERACTION`, or `SAFETY_ANCHOR` waypoint.
- Tile does not increase distance-to-next-waypoint beyond `sidestepCostThreshold` (configurable; default = 2 tiles).

**Required per-tick debug log** (Lane 6 expects):
```
[executor]
  current_waypoint: (x,y,p) tolerance=R type=T
  player_at: (x,y,p)
  candidates_considered: N
  candidates_rejected: [(tile, reason), ...]
  candidate_chosen: (x,y,p)
  sidestep: true|false
  result: WAYPOINT_REACHED | ...
  replan_reason: null | NO_LOCAL_WALKABLE_TILE | ...
```

**QC tests** (Lane 5 ships):
1. Normal waypoint → executor clicks a valid tile inside tolerance.
2. One blocked tile in corridor → executor chooses adjacent valid tile, no replan.
3. Whole corridor blocked → executor returns `NEEDS_REPLAN` with `NO_LOCAL_WALKABLE_TILE`.
4. Exact transport approach tile blocked → executor returns `STUCK`, not fake success.
5. Transport action fails → executor returns `TRANSPORT_UNAVAILABLE`.
6. Executor never edits `TransportTable` (assertion fixture).
7. `onClient` latch waits ≤2/tick (instrumented).

### Lane 6 — Independent QC + Test Harness

- **Owns** (new files):
  - `nav/v2/qc/NavigationTestHarness.java`
  - `nav/v2/qc/RouteTraceRecorder.java`
  - `nav/v2/qc/RouteReplayValidator.java`
  - `nav/v2/qc/OverlayTraceExporter.java`
  - `nav/v2/qc/GoldenRouteFixtures.java`
  - `nav/v2/qc/LiveAcceptanceChecklist.java`
- **Owns** (tests): ALL files under `src/test/java/.../nav/v2/acceptance/`

**Authority**:
- Lane 6 has **blocking power** over the merge. Lane 6 reads everyone's code; owns no production-code files (except harness utilities); cannot ship the engine if any of the 9 acceptance tests fail.

**Inputs Lane 6 consumes**:
- Lane 2: collision debug output, predicate evaluation logs
- Lane 3: validator results on every path
- Lane 4: planner debug output (link path + tile legs + compression decisions)
- Lane 5: per-tick executor log (candidates, chosen, result, replan reason)

## 5. Definition of done

The engine ships when **all** of the following are true. Lane 6 verifies; any missing item blocks merge.

### Functional

- New planner is the active V2 planning path (default ON via `enableWaypointPlanner` flag).
- Old A*/cost-knob planner not used by default; accessible via config for emergency rollback only.
- Planner uses global collision snapshot + live scene overlay.
- Planner can route same-region.
- Planner can route cross-region.
- Planner emits typed transport legs.
- Executor consumes sparse waypoints.
- Executor locally sidesteps minor blockers.
- Executor escalates to replan only for genuine obstruction.
- V1 (`TrailWalker`) fallback remains available behind `HybridNavigator` config.

### Architectural

- Planner owns route shape.
- Executor owns local tile choice.
- Navigator owns replanning.
- Transport data is never mutated by executor.
- Runtime predicates are composable.
- No new magic cost constants replacing the old ones.
- No route replay as primary navigation model.
- No exact tile-by-tile walking as primary output.

### Testing

- BFS unit tests pass (all 8 in Lane 3).
- Collision overlay tests pass (all 5 in Lane 2).
- Transport graph tests pass (all 8 in Lane 4).
- Executor sidestep tests pass (all 7 in Lane 5).
- Predicate tests pass.
- All 9 acceptance tests pass (Lane 6).
- Independent `RouteValidator` passes on every generated path.

### Observability

Every failed route must answer **all** of:
- Where was I?
- Where was I going?
- What did the planner think was possible?
- What did collision say?
- What did the live overlay change?
- What predicates rejected tiles?
- What did the executor try?
- Why did it replan / fail?

Debug traces produced per tick per leg per failure, reviewable by Lane 6. No untyped failures.

## 6. Acceptance tests — Lane 6 deliverable

All 9 must pass before merge. Each is an automated integration test except where marked manual.

### Test 1 — Same-region walking
**Setup**: start tile A, target tile B, no transports needed.
**Expected**: planner emits `WALK` waypoints only; BFS path validates; executor reaches target; no replan.
**Pass**: 10/10 successful runs; no invalid collision steps; no exact-tile-replay requirement.

### Test 2 — Cross-region walking
**Setup**: start Draynor area, target Lumbridge area.
**Expected**: high-level route + BFS segments + sparse waypoints; region boundary handled.
**Pass**: 5/5 successful routes generated; at least one route executed live/manual successfully; no coordinate/plane corruption.

### Test 3 — Transport interaction
**Setup**: start before gate/door/stairs, target after.
**Expected**: planner emits `TRANSPORT_APPROACH` waypoint; executor reaches approach tile; executor performs typed action; path continues.
**Pass**: `TransportLeg` explicit in `V2Path`; executor doesn't treat as normal walking; failure gives typed reason.

### Test 4 — Single-tile blocker (sidestep)
**Setup**: place dynamic blocker on next ideal walking tile.
**Expected**: planner path remains valid; executor chooses nearby valid tile; no full replan.
**Pass**: executor logs `sidestep=true`; result is `WAYPOINT_REACHED`, not `NEEDS_REPLAN`; progress continues.

### Test 5 — Corridor blocked (typed replan)
**Setup**: block all valid local candidates in next movement bucket.
**Expected**: executor cannot sidestep; returns `NEEDS_REPLAN` with `NO_LOCAL_WALKABLE_TILE`; navigator replans.
**Pass**: no infinite clicking; no random off-route movement; no fake success; typed reason emitted.

### Test 6 — Predicate
**Setup**: script marks tile X as disallowed via `addTileCondition`.
**Expected**: BFS avoids X; executor also refuses X.
**Pass**: tile X never in final accepted route; if no route exists without X, planner returns `TARGET_UNREACHABLE`.

### Test 7 — Route compression
**Setup**: long BFS tile sequence.
**Expected**: sparse waypoints output; required exact anchors preserved.
**Pass**: normal walking compressed; transport approaches preserved; plane changes preserved; no invalid shortcut introduced.

### Test 8 — bank↔pen regression (the user's reference route)
**Setup**: the existing problematic route; `V2BankPenLiveDataTest` un-skipped + hardened.
**Expected**: 10 consecutive successful cycles; no wrong-object interaction; no stuck loop; no repeated failure point.
**Pass**: 10/10 cycles; no manual intervention; every cycle produces a debug trace; no untyped failure.

### Test 9 — Trace quality (variety as robustness, NOT detection-evasion)
**Setup**: run same route 5 times.
**Expected**: all 5 traces valid; no trace violates collision; traces don't depend on exact replay; minor local variation is allowed.
**Pass**: every trace completes; every trace validates against collision; no trace is hardcoded tile replay.

## 7. Integration rules — every lane reads these

1. Do not change shared contracts (§3) without Lane 1 approval.
2. Do not introduce new planner cost knobs.
3. Do not let executor own route planning.
4. Do not let planner own click execution.
5. Do not hide transport interactions inside walk paths.
6. Do not make live scene checks optional for the final engine.
7. Do not delete fallback code (V1, old V2 planner) until the new engine passes acceptance.
8. Every route failure must produce a typed reason and a debug trace.
9. Every generated path must pass `RouteValidator` independently — never trust the pathfinder's own output.
10. If a test requires "it probably works," the test is not good enough.

## 8. What we keep / throw

### KEEP (Lane 5 modifies in-place, does not rewrite)
- `V2Executor` strict-walk gate
- Leg-scoped FSM
- Transport leg execution (existing dispatcher integration, restructured to accept `TransportLeg`)
- Wall-edge gate detection
- Dispatcher seams (`HumanizedInputDispatcher` integration)
- `V2ExecutorEnv` marshalling (audit `onClient` latency)
- `HybridNavigator`, `NavRequest`, `Navigator` interface
- `InvalidationClassifier` (folded as a `TilePredicate` provider)
- V1 (`TrailWalker`) — fallback during transition
- V1 recorded trails — consumed as **sparse** `SAFETY_ANCHOR` waypoint hints to `LinkGraphDijkstra`. **Guardrail**: V1 trail hints may produce sparse anchors only. They MAY NOT force exact tile replay, MAY NOT bias BFS expansion at every tile (no edge-cost halving), and MAY NOT collapse top-K variants toward the recorded shape. This is the explicit guard against re-creating the trail-bias misfire (commit 96f4fbbbc). Anchors are *hints* the planner may ignore if a different route is preferable; never *requirements*.

### THROW (Lane 4 / Lane 5 deletes when its replacement is shipped and Lane 6 has signed off)
- `V2Planner`
- `MultiRegionAStar`
- `TopKRouter`
- `RouteHistory`
- Trail-bias coefficient (commit 96f4fbbbc)
- `UNKNOWN_TILE_COST`, `SENTINEL_TILE_COST`, `TRANSPORT_BASE_COST` constants — collision is binary, no scalars to twist
- `wantsReplanFromHere` cross-layer flag
- Executor's mid-route `TransportTable` mutation
- `RouteReadiness` in its current form (concerns either move to `WorldSnapshotBuilder` or to `TilePredicate` evaluation)

## 9. Data shipping + licensing

- Bundle Skretzo's `collision-map.zip` under `runelite-client/src/main/resources/runelite/nav/collision/`.
- Bundle Skretzo's transport TSVs under `runelite-client/src/main/resources/runelite/nav/transports/`.
- License: BSD-2-Clause (both this project and Skretzo). Attribution in `NOTICES.md` at repo root.
- Per memory `feedback_no_bsd_headers`: new files we author skip the BSD header; Skretzo-imported files keep their original headers untouched.
- CI check: snapshot version matches the Skretzo release we forked from. CI fails if mismatched.

## 10. Risks + mitigations

**Skretzo's BFS implementation diverges from Jagex server behavior in a subtle case.**
*Mitigation*: Lane 3 ships property tests against OSRS-wiki-documented Jagex BFS reference cases. If divergence found, fix in fork — that's why we forked, not adopted wholesale.

**Skretzo's transport TSVs become stale after Jagex map updates.**
*Mitigation*: CI snapshot-version check. Upstream sync schedule documented in `NOTICES.md`. `TransportTable` validates all rows at startup with loud per-row failure.

**5 parallel implementation lanes produce conflicting integrations.**
*Mitigation*: file ownership rule (§4). Lane 1 spec is contract lock. Lane 6 QC has blocking power on integration. Cross-lane edits require Lane 1 approval.

**Live `client.getCollisionMaps()` overlay introduces latency per plan call.**
*Mitigation*: snapshot built once at plan-call entry on client thread, then immutable. Sub-ms `flagsAt(WorldPoint)` after build. Latency budget verified in Lane 2 QC.

**Variety mechanism (BFS tie-break) produces server-faithful routes that look identical because cardinal-first dominates.**
*Mitigation*: variety isn't only from tie-break — executor sidestep-within-bucket produces emergent trace variation per tick. Test 9 validates trace distinctness across 5 cycles.

## 11. Open items for user sign-off

Before Lane 2–6 dispatch, three items want explicit approval:

1. **Memory update**: `feedback_inline_impl_subagent_qc` reworded to carve out parallel-lane implementation for engine-core work (this kind), with QC independent and blocking. Inline implementation remains the default for tactical / single-step work.
2. **Subpackage convention**: §4 proposes `nav/v2/{collision,bfs,transport,planner,predicate,executor,qc}/` subpackages instead of the current flat `nav/v2/`. Helps file-ownership clarity; deviates from current style.
3. **Lane 1 completion gate**: this spec marked "design locked pending file-level review." Once approved at file level, Lane 1 is complete; only then may Lanes 2–6 dispatch.

## 12. Next step

After user file-level approval of this spec:

1. Invoke `superpowers:writing-plans` to produce the compact implementation plan — file ownership table, dependency graph between lanes, acceptance gate checklist, lane-by-lane briefing packets (each packet includes §0 master direction + §3 contracts + that lane's §4 entry + §6 acceptance tests it must satisfy).
2. After plan approval → dispatch the 5 parallel lanes (Lane 1 is complete; Lane 6 starts at lane dispatch and runs concurrently with 2–5).
3. Lane 6 holds the merge gate until all 9 acceptance tests pass.
