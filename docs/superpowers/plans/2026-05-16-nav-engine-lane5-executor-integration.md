# Lane 5 — Executor Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: `superpowers:executing-plans` if dispatched as a single agent.
> **Master plan**: `docs/superpowers/plans/2026-05-16-nav-engine-master.md`
> **Spec**: `docs/superpowers/specs/2026-05-16-observation-aware-navigation-engine-design.md`
> **Required reading**: spec §0 (Master Direction — drift gate), §3 (Locked Contracts), §4 Lane 5 entry, §6 (Acceptance Tests).
> **No production dependencies** — operates against locked contracts. Starts immediately concurrent with Lanes 2/3/6.

**Goal**: Restructure `V2Executor` + `V2ExecutorEnv` + `V2Navigator` to consume sparse waypoints (`V2Path` with `List<PathStep>`), return typed `ExecutorTickResult`, sidestep within tolerance buckets, and remove the `wantsReplanFromHere` cross-layer flag plus the executor's mid-route `TransportTable` mutation.

**Architecture**: Keep `V2Executor`'s proven core (strict-walk gate, leg-scoped FSM, transport leg execution, wall-edge gate detection, dispatcher seams). Add a `SidestepResolver` that picks the furthest-forward walkable+clean tile within the current waypoint's tolerance bucket, honoring sidestep rules from spec §4 Lane 5. Fold `InvalidationClassifier` into a `TilePredicate` provider so its concerns flow through the predicate registry instead of via mid-route mutation. Audit `V2ExecutorEnv.onClient` latch budget down from 4-6/tick to ≤2/tick.

**Tech**: Java 17, RuneLite-API, Gradle. Modifies existing files in place.

---

## File structure

**Modify (existing files — Lane 5 owns these):**
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v2/V2Executor.java`
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v2/V2ExecutorEnv.java`
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v2/V2Navigator.java`
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v2/InvalidationClassifier.java`
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v2/V2Path.java` (rename interface methods to match §3 locked contract: `steps()` returning `List<PathStep>`)
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v2/V2Leg.java` (may rename or replace — read first to assess)

**Create (new):**
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v2/executor/SidestepResolver.java`
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v2/executor/ExecutorTickResultImpl.java`
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v2/executor/PathStepCursor.java` (cursor that tracks current `PathStep` index within `V2Path`)

**Tests:**
- `V2ExecutorTest.java` (existing — adapt; new test cases per spec §4 Lane 5 QC)
- `executor/SidestepResolverTest.java`
- `executor/PathStepCursorTest.java`
- `executor/OnClientLatchBudgetTest.java` (instrumented test for ≤2/tick budget)

---

## Task 1 — Audit V2ExecutorEnv.onClient latch budget

**Files**: `V2ExecutorEnv.java`, `executor/OnClientLatchBudgetTest.java`.

Behavior: Read current `V2Executor.tick(...)` and enumerate all `V2ExecutorEnv.onClient(...)` call-sites. Target: collapse from 4-6 latch waits per executor tick to ≤2/tick. Strategy:
- Batch related reads into a single `onClient(...)` block returning a small value-type (e.g. `TickReadOut` containing `playerLoc + isPlausiblyClean + liveCollisionAllows + dynamicEntityOnTile` for the current candidate tile, in one marshalled call).
- Move static reads (`PixelResolver.canMinimapClick()`) out of `onClient` where they don't need client-thread.

Add instrumentation: `V2ExecutorEnv` exposes `onClientCallsThisTick()` for testing. Reset per tick boundary.

Tests:
- `onClient_per_tick_normalWaypoint_atMost2Calls`.
- `onClient_per_tick_transportLeg_atMost2Calls`.
- `onClient_per_tick_stalled_atMost2Calls`.

Run: `./gradlew :client:test --tests "*OnClientLatchBudgetTest"`

Commit: `perf(nav-engine,lane5): collapse onClient latch budget to <=2/tick`

---

## Task 2 — V2Path interface alignment to §3 contract

**Files**: `V2Path.java`, `executor/PathStepCursor.java`, `executor/PathStepCursorTest.java`.

Behavior: Update `V2Path` interface to match §3 locked contract: methods `steps() → List<PathStep>`, `id() → PathId`, `planEpochMs()`. Remove the separate `waypoints()` + `transports()` lists. `PathStep` is sealed with `WalkStep` + `TransportStep` permitted subtypes (these interfaces live in §3).

Build `PathStepCursor`: tracks current index within `V2Path.steps()`; methods `current()`, `advance()`, `peek(int offset)`, `remainingSteps()`. Replaces the executor's `legIdx` integer-only tracking.

Tests:
- `cursor_current_returnsCurrentStep`.
- `cursor_advance_movesForward`.
- `cursor_peek_offset_returnsFutureStep`.
- `cursor_peek_beyondEnd_returnsEmpty`.
- `cursor_isAtEnd_afterAdvancePastLast`.

Run: `./gradlew :client:test --tests "*PathStepCursorTest"`

Commit: `refactor(nav-engine,lane5): V2Path uses PathStep list per locked contract`

---

## Task 3 — SidestepResolver

**Files**: `executor/SidestepResolver.java`, `executor/SidestepResolverTest.java`.

Behavior: `resolve(Waypoint current, WorldPoint playerAt, WorldSnapshot snap, PredicateRegistry preds, PathContext ctx) → ResolveResult`. Picks the furthest-forward walkable+clean tile within the current waypoint's tolerance bucket. Sidestep rule per spec §4 Lane 5:
- Tile is in `current.target() ± current.toleranceRadius()` chebyshev.
- Collision permits the move (per `snap.collisionView()`).
- All active predicates accept the tile.
- Tile does not skip a `TRANSPORT_APPROACH`, `OBJECT_INTERACTION`, or `SAFETY_ANCHOR` (read via `ctx.currentPath()`).
- Tile does not increase distance-to-next-waypoint beyond `sidestepCostThreshold` (configurable; default 2 tiles).
- Sidestep up to ±2 tiles perpendicular to direction-of-travel.

`ResolveResult = {Status, Optional<WorldPoint> chosen, List<RejectedCandidate> rejected, boolean sidestepUsed}` where `Status ∈ {OK, NO_LOCAL_WALKABLE_TILE}`.

Tests (mirror spec §4 Lane 5 QC tests):
- `resolve_normalWaypoint_picksValidTileInTolerance`.
- `resolve_oneBlockedTileInCorridor_picksAdjacentValidTile_sidestepTrue`.
- `resolve_wholeCorridorBlocked_returnsNoLocalWalkableTile`.
- `resolve_exactTransportApproachBlocked_returnsNoLocalWalkableTile` (no fake success).
- `resolve_doesNotSkipSafetyAnchor`.
- `resolve_doesNotIncreaseDistanceBeyondThreshold`.

Run: `./gradlew :client:test --tests "*SidestepResolverTest"`

Commit: `feat(nav-engine,lane5): SidestepResolver picks furthest-forward in bucket`

---

## Task 4 — V2Executor refactor to ExecutorTickResult

**Files**: `V2Executor.java`, `executor/ExecutorTickResultImpl.java`, `V2ExecutorTest.java` (heavily updated).

Behavior: Refactor `V2Executor.tick(...)` to:
- Consume `V2Path` via `PathStepCursor`.
- Per tick, switch on current step type:
  - `WalkStep` → call `SidestepResolver` → dispatch click → return `ExecutorTickResult` with `result ∈ {WAYPOINT_REACHED, NEEDS_REPLAN, STUCK}` per outcome.
  - `TransportStep` → existing transport leg execution restructured to accept `TransportLeg` input (current `V2Leg` may need adapter or replacement) → return `TRANSPORT_COMPLETED` or `TRANSPORT_UNAVAILABLE`.
- Build `ExecutorTickResult` via `ExecutorTickResultImpl` factory. Always emit a `debugTraceId` (UUID per tick; logged so Lane 6 can correlate).
- **Remove** `wantsReplanFromHere` field, callback, and all callsites. The executor returns `NEEDS_REPLAN` in `ExecutorTickResult` and `V2Navigator` decides.
- **Remove** the executor's `correctTransportEdge(...)` mutation; `InvalidationClassifier` (Task 6) takes over.
- Emit per-tick debug log per spec §4 Lane 5 format.

Tests:
- `tick_normalWaypoint_returnsWaypointReached_chosenTile`.
- `tick_blockedCorridor_returnsNeedsReplan_with_NO_LOCAL_WALKABLE_TILE`.
- `tick_transportApproachBlocked_returnsStuck`.
- `tick_transportLegSucceeded_returnsTransportCompleted`.
- `tick_transportActionFailed_returnsTransportUnavailable`.
- `tick_neverEditsTransportTable` (assertion fixture: spy on TransportTable, assert no `replace(...)` calls).
- `tick_emitsDebugTraceId_nonNullNonEmpty`.

Run: `./gradlew :client:test --tests "*V2ExecutorTest"`

Commit: `refactor(nav-engine,lane5): V2Executor returns typed ExecutorTickResult`

---

## Task 5 — V2Navigator typed-result consumption

**Files**: `V2Navigator.java`, `V2NavigatorTest.java` (existing — adapted).

Behavior: `V2Navigator.tick(NavRequest req)` reads `ExecutorTickResult` from `V2Executor.tick(...)`. Decision tree:
- `PATH_COMPLETED` → emit `NavStatus.SUCCEEDED`.
- `WAYPOINT_REACHED` / `TRANSPORT_COMPLETED` → advance cursor; tick again next time.
- `NEEDS_REPLAN` → invoke `WaypointPlanner.plan(...)` again from current player position with budget; if exhausted, fail with the `ReplanReason`.
- `STUCK` / `FAILED` → emit `NavStatus.FAILED` with the `ReplanReason`.
- **No more reading `wantsReplanFromHere`** — that flag is gone.

Tests:
- `navigator_executorReturnsNeedsReplan_invokesPlannerOnce`.
- `navigator_replanBudgetExhausted_returnsFailed`.
- `navigator_executorReturnsStuck_propagatesReason`.
- `navigator_executorReturnsPathCompleted_returnsSucceeded`.

Run: `./gradlew :client:test --tests "*V2NavigatorTest"`

Commit: `refactor(nav-engine,lane5): V2Navigator consumes typed ExecutorTickResult`

---

## Task 6 — Fold InvalidationClassifier into predicate provider

**Files**: `InvalidationClassifier.java`, `V2Executor.java`, `V2Navigator.java`. Possibly new file `predicate/InvalidationPredicates.java` IF it's clearly Lane 5's (boundary call — confirm with Lane 1 if uncertain; default to Lane 5's `executor/` package).

Behavior: `InvalidationClassifier` currently classifies executor click failures into 4 categories and maintains a blacklist + transient penalty map. Refactor:
- Keep the classification logic (named verb + transport-state-mismatch + dynamic-blocker + unknown).
- The blacklist becomes a `TilePredicate` exposed via `PredicateRegistry.register("invalidation-blacklist", ...)`.
- Transient penalties become time-decaying entries in the blacklist predicate (decay over N ticks per existing convention).
- Transport-state-mismatch failures emit a `TransportTable.replace(...)` request via a typed callback to `V2Navigator`, NOT directly from the executor. Navigator decides whether to apply the correction and whether to replan.

Tests:
- `classifier_dynamicBlocker_addsTransientPenaltyToBlacklist`.
- `classifier_staticCollisionMismatch_signalsReplan` (via ExecutorTickResult, not via TransportTable mutation).
- `classifier_transportMismatch_emitsCorrectionRequest_navigatorAppliesIt`.
- `classifier_doesNotCallTransportTable_replaceDirectly` (assertion via spy).

Run: `./gradlew :client:test --tests "*InvalidationClassifierTest"`

Commit: `refactor(nav-engine,lane5): InvalidationClassifier folded as predicate provider`

---

## Task 7 — Hand to Lane 6

**File**: `docs/superpowers/plans/lane5-manifest.md`.

Manifest:
- Modified files + diff stats.
- New files + line counts.
- Test files + count passing.
- Sample per-tick debug log from a manual run (so Lane 6 wires its `RouteTraceRecorder` to consume the format).
- Confirmation that `wantsReplanFromHere` is fully removed (grep returns 0 hits).
- Confirmation that executor never calls `TransportTable.replace(...)`.
- `onClient` latch budget measurement.
- Known limitations.

Commit: `docs(nav-engine,lane5): manifest for Lane 6 QC`

---

## Self-test acceptance

Lane 5 is done when:

- [ ] All modified files + new files compile: `./gradlew :client:compileJava`.
- [ ] All Lane 5 tests pass: `./gradlew :client:test --tests "*V2ExecutorTest" --tests "*V2NavigatorTest" --tests "*InvalidationClassifierTest" --tests "*nav.v2.executor.*"`.
- [ ] Spec §4 Lane 5's 7 QC tests pass (mapped across tasks 3 + 4 + 5 + 6).
- [ ] `grep -r "wantsReplanFromHere" runelite-client/src/main/java/` returns 0 hits.
- [ ] `grep -r "transportTable.replace\|TransportTable.replace" runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v2/V2Executor` returns 0 hits.
- [ ] `OnClientLatchBudgetTest` passes (≤2/tick).
- [ ] Manifest written.
- [ ] No edits to files under `nav/v2/collision/`, `nav/v2/bfs/`, `nav/v2/transport/`, `nav/v2/planner/`, `nav/v2/predicate/`, `nav/v2/qc/`.
- [ ] No edits to spec §3.

Hand-off: announce "Lane 5 hand-off complete. See `lane5-manifest.md`."
