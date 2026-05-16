# Lane 3 — BFS Kernel + Route Validator Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: `superpowers:executing-plans` if dispatched as a single agent.
> **Master plan**: `docs/superpowers/plans/2026-05-16-nav-engine-master.md`
> **Spec**: `docs/superpowers/specs/2026-05-16-observation-aware-navigation-engine-design.md`
> **Required reading**: spec §0 (Master Direction — drift gate), §3 (Locked Contracts), §4 Lane 3 entry, §6 (Acceptance Tests).

**Goal**: Build the tile-level BFS kernel forked from Skretzo (cardinal-first expansion, wall-edge + pillar corner handling, 128×128 bounded) plus an independent `RouteValidator` that re-checks every BFS output for correctness.

**Architecture**: Pure-function BFS over `CollisionView` (from Lane 2; mocked via interface until Lane 2 lands). Cardinal expansion order `W/E/S/N/SW/SE/NW/NE` matching Jagex's server algorithm — **validated by property tests, not assumed**. Diagonal tie-break order is configurable (`BfsConfig.routeSeed`); cardinal order is always fixed. RouteValidator runs after BFS and never trusts the kernel's own output.

**Tech**: Java 17, RuneLite-API (`WorldPoint`, `CollisionDataFlag`), Gradle. No `Client` dependency — Lane 3 operates on collision flags only and is fully unit-testable.

---

## File structure

**Create (production):**
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v2/bfs/BfsConfig.java`
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v2/bfs/SkretzoBfsKernel.java`
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v2/bfs/RouteValidator.java`

**Tests** (mirror under `src/test/java/.../nav/v2/bfs/`):
- `BfsConfigTest.java`
- `SkretzoBfsKernelTest.java`
- `RouteValidatorTest.java`
- `JagexServerAlignmentTest.java` (property tests against OSRS-wiki reference cases)

---

## Task 1 — BfsConfig

**Files**: `bfs/BfsConfig.java`, `BfsConfigTest.java`.

Behavior: Immutable configuration record/class holding:
- `int maxExpandedTiles` (default 65536 — generous within 128×128 bound).
- `int maxRadius` (default 128 — matches Jagex bound).
- `long routeSeed` — seeds diagonal tie-break order. Same seed → byte-identical path output.
- `DiagonalOrder diagonalOrder()` — derived from seed; one of 24 permutations of `{SW, SE, NW, NE}`. Cardinal order is always `W, E, S, N` and not configurable.

Tests:
- `withSeed_sameSeed_sameDiagonalOrder` — determinism.
- `withSeed_differentSeeds_canProduceDifferentOrders` — variety.
- `diagonalOrder_is24Permutations` — across enough seeds, all 24 permutations are reachable.
- `cardinalOrderIsFixed` — `W/E/S/N` regardless of seed.

Run: `./gradlew :client:test --tests "*BfsConfigTest"`

Commit: `feat(nav-engine,lane3): BfsConfig with seeded tie-break`

---

## Task 2 — SkretzoBfsKernel

**Files**: `bfs/SkretzoBfsKernel.java`, `SkretzoBfsKernelTest.java`.

Behavior: Pure function `plan(CollisionView collision, WorldPoint from, WorldPoint to, BfsConfig cfg, TilePredicate predicate) → BfsResult`.

`BfsResult` is a small value type: `{Status status, List<WorldPoint> tiles, int expanded, ReplanReason reasonIfFailed}` where `Status ∈ {PATH_FOUND, UNREACHABLE, BUDGET_EXHAUSTED}`.

Algorithm:
1. Start queue at `from`, mark visited.
2. Pop tile; if equal to `to`, reconstruct path via parent map, return `PATH_FOUND`.
3. Expand neighbors in fixed cardinal order (`W, E, S, N`) then `cfg.diagonalOrder()` (permuted).
4. For each candidate neighbor:
   - Check `CollisionView.flagsAt(from)` and `flagsAt(neighbor)` for movement-allowed in that direction. Walls block movement *from* a direction — verify by bitmask per `CollisionDataFlag`.
   - For diagonals: also check the two perpendicular tiles for pillar/corner blocking. Diagonal SW→NE requires both S→E and W→N paths clear.
   - Apply `predicate.accept(neighbor, ctx)` — reject if false.
   - Apply chebyshev-distance bound `cfg.maxRadius`.
5. If queue exhausts → `UNREACHABLE` with `TARGET_UNREACHABLE` reason.
6. If expanded count exceeds `cfg.maxExpandedTiles` → `BUDGET_EXHAUSTED`.

**Important**: do NOT do tile-cost weighting. No A*. No heuristic. Pure BFS.

Tests:
- `plan_straightCorridor_returnsDirectPath`.
- `plan_wallBetweenTwoTiles_doesNotCrossWall` — verify wall-edge bitmask correctness.
- `plan_diagonalNearBlockedCorner_routesAround` — pillar corner case.
- `plan_pillarCornerObstacle_routesAround_notThrough`.
- `plan_fullyBlockedTarget_returnsUnreachable`.
- `plan_sameInputSameSeed_byteIdenticalOutput` — determinism (compare path lists).
- `plan_sameInputDifferentSeed_pathStillValid` — variety: validate result is a valid path even if different from original.
- `plan_budgetExhausted_returnsTypedFailure`.

Run: `./gradlew :client:test --tests "*SkretzoBfsKernelTest"`

Commit: `feat(nav-engine,lane3): SkretzoBfsKernel cardinal-first BFS`

---

## Task 3 — RouteValidator

**Files**: `bfs/RouteValidator.java`, `RouteValidatorTest.java`.

Behavior: Pure function `validate(List<WorldPoint> path, CollisionView collision, TilePredicate predicate) → ValidationResult`. Runs after BFS, never trusts BFS output. Checks every consecutive pair:
- Tiles are adjacent (`chebyshev = 1`).
- Collision flags permit movement in that direction (consult `collision.flagsAt(from)` + `flagsAt(to)`).
- Same plane (or a TransportLeg explains the change — Validator takes optional `List<TransportLeg>` parameter).
- No diagonal cut through a blocked corner.
- `predicate.accept(to, ctx)` is true.

`ValidationResult = {boolean ok, int firstFailureIndex, String reason}`.

Tests:
- `validate_validBfsOutput_passes` — golden path validates.
- `validate_handCraftedInvalidPath_failsLoudly` — adjacency violation caught.
- `validate_diagonalThroughBlockedCorner_fails`.
- `validate_planeJumpWithoutTransport_fails`.
- `validate_planeJumpWithTransport_passes` — given a `TransportLeg` covering the jump.
- `validate_predicateRejectsTile_fails` — chained predicate rejection.

Run: `./gradlew :client:test --tests "*RouteValidatorTest"`

Commit: `feat(nav-engine,lane3): RouteValidator independent path check`

---

## Task 4 — Jagex server alignment property tests

**File**: `bfs/JagexServerAlignmentTest.java`.

Behavior: Property tests against known OSRS-wiki-documented reference cases. Inputs: hand-crafted collision fixtures matching documented Jagex scenarios (corner diagonal rule, wall-edge cardinal rule, pillar blocking). Outputs: BFS kernel result vs documented expected behavior.

**Important**: these tests are the *validation* of "server-aligned." If any fail, fix the kernel in our fork — that's why we forked.

Reference cases to cover:
- W/E/S/N expansion order produces the same first-found path as Jagex's server for a 5×5 open grid (`cardinal-preferred-over-diagonal` documented behavior).
- Wall-edge block: tile at (5,5) has W-wall flag; movement from (5,5)→(4,5) is blocked; movement from (4,5)→(5,5) is also blocked (wall blocks both directions).
- Pillar corner: pillar at NE corner of (5,5) blocks diagonal (5,5)→(6,6).
- Distance cap: target at chebyshev=130 from start, all walkable in between → `UNREACHABLE` not `PATH_FOUND` (Jagex 128×128 bound).

Tests:
- `expansion_5x5OpenGrid_matchesJagexCardinalPreference`.
- `wallEdge_blocksFromBothSides`.
- `pillarCorner_blocksDiagonal`.
- `distanceBound_beyond128_returnsUnreachable`.

Run: `./gradlew :client:test --tests "*JagexServerAlignmentTest"`

Commit: `test(nav-engine,lane3): Jagex server-alignment property tests`

---

## Task 5 — Hand to Lane 6

**File**: `docs/superpowers/plans/lane3-manifest.md`.

Manifest:
- Production files + line counts.
- Test files + count passing.
- Sample BFS trace output (for Lane 6 to wire into route traces).
- Server-alignment test status (must be all-passing or document divergence in manifest).
- Known limitations.

Commit: `docs(nav-engine,lane3): manifest for Lane 6 QC`

---

## Self-test acceptance

Lane 3 is done when:

- [ ] All 3 production files exist and `./gradlew :client:compileJava` succeeds.
- [ ] `./gradlew :client:test --tests "*nav.v2.bfs.*"` — all pass.
- [ ] Spec §4 Lane 3's 8 QC tests pass (mapped across tasks 2–4).
- [ ] All Jagex server-alignment property tests pass OR any divergence is documented loudly in `lane3-manifest.md`.
- [ ] Manifest written.
- [ ] No files outside `nav/v2/bfs/` touched.
- [ ] No edits to spec §3.

Hand-off: announce "Lane 3 hand-off complete. See `lane3-manifest.md`."
