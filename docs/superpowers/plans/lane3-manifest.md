# Lane 3 Manifest — BFS Kernel + Route Validator

**Date**: 2026-05-16
**Branch**: `worktree-agent-a34ddb35cbfc9d360`
**Status**: All 5 tasks complete. Hand-off to Lane 6.
**Plan**: `docs/superpowers/plans/2026-05-16-nav-engine-lane3-bfs-validator.md`
**Spec**: `docs/superpowers/specs/2026-05-16-observation-aware-navigation-engine-design.md`

## Production files (`runelite-client/src/main/java/.../nav/v2/bfs/`)

| File | Lines | Role |
|------|-------|------|
| `BfsConfig.java` | 134 | Immutable config: 128 maxRadius, 65536 budget, seeded diagonal-tie-break (one of 24 permutations of {SW,SE,NW,NE}); cardinal order fixed W,E,S,N. |
| `SkretzoBfsKernel.java` | 338 | Pure-function BFS over `CollisionView`. Cardinal-first expansion, wall-edge + pillar/corner rule, 128-chebyshev bound, typed `BfsResult{Status,List<WorldPoint>,expanded,reasonIfFailed}`. |
| `RouteValidator.java` | 151 | Independent validator. Re-checks every consecutive pair: adjacency, collision flags, same plane (or `PlaneTransition` explains the jump), predicate. Returns typed `ValidationResult{ok, firstFailureIndex, reason}`. |
| `CollisionView.java` | 28 | Lane-3-local mock interface (single `flagsAt(WorldPoint)` method). Subsumed when Lane 2 lands its canonical `CollisionView`. |
| `TilePredicate.java` | 26 | Lane-3-local mock interface (single `accept(WorldPoint, Object ctx)` method). Subsumed when Lane 1/2 land canonical `TilePredicate`. |
| `LocalReplanReason.java` | 19 | Lane-3-local enum: `TARGET_UNREACHABLE`, `EXECUTOR_TIMEOUT`. Names match spec §3 `ReplanReason` for 1-to-1 mapping. |
| `PlaneTransition.java` | 18 | Lane-3-local marker for legitimate plane jumps (single `from()`/`to()` methods). Spec §3 `TransportLeg` adapts to it directly. |

**Total**: 714 lines across 7 files. No external coupling: no `Client` dep, no `Skill` reads, no `Slf4j` logging in the kernel hot path (intentional — kernel is pure).

## Mock interfaces — migration notes for Lane 1/2

Lane 3's local mocks are minimal-shape proxies for Lane 1/2's canonical contracts. They live in `bfs/` to keep Lane 3 unit-testable in isolation. Migration is straightforward when Lane 2 lands:

| Lane 3 local | Spec §3 / Lane 2 canonical | Migration shape |
|---|---|---|
| `bfs.CollisionView.flagsAt(WorldPoint)` | `collision.CollisionView.collisionAt(WorldPoint)` (note name diff) | trivial rename or type alias |
| `bfs.TilePredicate.accept(WorldPoint, Object)` | `predicate.TilePredicate.accept(WorldPoint, PathContext)` | replace `Object` with `PathContext` |
| `bfs.LocalReplanReason` | `nav.v2.ReplanReason` (spec §3) | drop in favor of canonical enum |
| `bfs.PlaneTransition` | `nav.v2.TransportLeg` (spec §3) | `TransportLeg implements PlaneTransition` or adapter call site |

**Lane 4 note**: the planner calls `SkretzoBfsKernel.plan(...)` per leg; `SkretzoBfsKernel`'s parameters match the mocks above. Suggested adapter when Lane 2's `CollisionView` lands:

```java
// planner.java
SkretzoBfsKernel.plan(
    worldSnapshot::collisionAt, // method reference adapts spec §3 CollisionView
    leg.from(),
    leg.to(),
    cfg,
    (tile, ctx) -> registry.acceptAll(tile, ctx)
);
```

`SkretzoBfsKernel.canMove(...)` is package-private (called by `RouteValidator`). If Lane 4 also needs to call it from outside the package, promote to public — but the validator is the only intended caller from outside the kernel.

## Test files (`runelite-client/src/test/java/.../nav/v2/bfs/`)

| File | Tests | Lines | Coverage |
|------|-------|-------|----------|
| `BfsConfigTest.java` | 8 | 113 | determinism, variety (24 perms reachable), fixed cardinal order, immutability, default bounds |
| `SkretzoBfsKernelTest.java` | 11 | 293 | straight corridor, wall-edge avoidance, pillar/corner rule, fully-blocked target, determinism, variety (different seeds still valid), budget exhaustion, trivial same-tile, predicate rejection, plane mismatch |
| `RouteValidatorTest.java` | 10 | 216 | valid bfs output, hand-crafted invalid step, diagonal through blocked corner, plane jump without/with transport, predicate rejection, empty/single-tile path, blocked wall step, fully blocked destination |
| `JagexServerAlignmentTest.java` | 9 | 280 | cardinal expansion order on open grid, NE-diagonal target, wall-edge symmetry, pillar corner (two variants), 128 chebyshev cap (inclusive), perpendicular-direction wall insensitivity, diagonal block flag on dest |

**Total**: 38 tests, 902 lines, **all passing**.

## Spec §4 Lane 3 QC tests — mapping (8/8 PASS)

| Spec §4 test | Implemented in | Status |
|---|---|---|
| 1. Straight corridor → direct path | `SkretzoBfsKernelTest#plan_straightCorridor_returnsDirectPath` | PASS |
| 2. Wall between two tiles → does not cross wall edge | `SkretzoBfsKernelTest#plan_wallBetweenTwoTiles_doesNotCrossWall` | PASS |
| 3. Diagonal near blocked corner → routes around | `SkretzoBfsKernelTest#plan_diagonalNearBlockedCorner_routesAround` + `JagexServerAlignmentTest#pillarCorner_blocksDiagonal` | PASS |
| 4. Pillar / corner obstacle → routes around, not through | `SkretzoBfsKernelTest#plan_pillarCornerObstacle_routesAround_notThrough` | PASS |
| 5. Fully blocked target → returns unreachable with reason | `SkretzoBfsKernelTest#plan_fullyBlockedTarget_returnsUnreachable` | PASS |
| 6. Same input + same tie-break seed → byte-identical output | `SkretzoBfsKernelTest#plan_sameInputSameSeed_byteIdenticalOutput` | PASS |
| 7. Same input + alternate tie-break seed → still valid path | `SkretzoBfsKernelTest#plan_sameInputDifferentSeed_pathStillValid` | PASS |
| 8. RouteValidator catches hand-crafted invalid path → rejects loudly | `RouteValidatorTest#validate_handCraftedInvalidPath_failsLoudly` (+9 sibling cases) | PASS |

## Server-alignment property tests (Task 4) — all PASS

All 9 Jagex-aligned property tests pass with NO divergence found:

- `expansion_5x5OpenGrid_matchesJagexCardinalPreference` — cardinal-preferred on open grid
- `expansion_diagonalTarget_prefersCardinalsBeforeDiagonals` — shortest 4-tile NE diagonal returned
- `wallEdge_blocksFromBothSides` — wall flag symmetry
- `pillarCorner_blocksDiagonal` (E half-step blocked) — diagonal cancelled
- `pillarCorner_secondVariant_northBlocked` (N half-step blocked) — diagonal cancelled
- `distanceBound_beyond128_returnsUnreachable` — 128 cap enforced
- `distanceBound_at128_returnsPathFound` — cap is inclusive (≤ 128)
- `wallEdge_diagonalThroughWall_blocked` — diagonal block flag (BLOCK_MOVEMENT_SOUTH_WEST etc.) honored on dest
- `wallEdge_perpendicularDirection_notBlocked` — wall on W face only blocks W/E, not N/S at the same tile

**No divergences from documented Jagex behavior were detected.** If a divergence is found in subsequent live testing (Lane 6), fix in fork and update this section.

## Sample BFS trace output (for Lane 6 to wire into route traces)

The kernel returns `BfsResult`:

```
BfsResult{
  status: PATH_FOUND | UNREACHABLE | BUDGET_EXHAUSTED,
  tiles: [WorldPoint(...), WorldPoint(...), ...],  // empty when status != PATH_FOUND
  expanded: 42,                                     // tile expansions used
  reasonIfFailed: TARGET_UNREACHABLE | EXECUTOR_TIMEOUT | null
}
```

`RouteValidator.ValidationResult`:

```
ValidationResult{
  ok: true | false,
  firstFailureIndex: -1 (when ok) | int >= 1 (the step index that failed),
  reason: null (when ok) | "non-adjacent step ...", "cardinal blocked (wall edge or full) ...",
          "diagonal blocked (pillar/corner or diagonal flag) ...",
          "plane change ... not explained by any transport leg",
          "predicate rejected tile ..."
}
```

Lane 6 should log on every plan call:
- `expanded`, `status`, `reasonIfFailed` for BFS
- `ok`, `firstFailureIndex`, `reason` for validator (especially when ok=false)

These are emitted as plain fields; no logging framework dependency in Lane 3 hot paths.

## Known limitations

1. **Local mock interfaces**: `CollisionView`, `TilePredicate`, `LocalReplanReason`, `PlaneTransition` are Lane-3-local. When Lane 1/2 land, these become thin re-exports or are deleted in favor of canonical types. Migration table above.
2. **No `Client` dependency**: Lane 3 cannot perform live-scene collision lookups. That is Lane 2's job (`LiveSceneCollisionOverlay`). Lane 3 trusts whatever `CollisionView.flagsAt(...)` returns.
3. **Plane-bound BFS**: cross-plane queries return `UNREACHABLE` immediately. The planner (Lane 4) is responsible for stringing per-leg BFS calls together across transports.
4. **`canMove` is package-private**: `RouteValidator` calls it from the same package. Lane 4/5 needing the same predicate from outside should either (a) call `RouteValidator.validate(...)` for any-shape route, or (b) request Lane 1 promote `canMove` to public.
5. **Maximum radius is inclusive**: a Chebyshev distance == `maxRadius()` is reachable; only distances strictly greater are bound-rejected. The 128-cap test confirms this; if the Jagex bound is actually exclusive, the off-by-one is loud (test 7 `distanceBound_at128_returnsPathFound` would need to assert UNREACHABLE).

## Commits this lane

| SHA (short) | Message |
|---|---|
| `6a549adac` | feat(nav-engine,lane3): BfsConfig with seeded tie-break |
| `1031c8862` | feat(nav-engine,lane3): SkretzoBfsKernel cardinal-first BFS |
| `06cd916a2` | feat(nav-engine,lane3): RouteValidator independent path check |
| `c00cf5c5e` | test(nav-engine,lane3): Jagex server-alignment property tests |
| (this commit) | docs(nav-engine,lane3): manifest for Lane 6 QC |

## Self-test acceptance

- [x] All 3 production files exist (plus 4 lane-local mocks) and `./gradlew :client:compileJava` succeeds.
- [x] `./gradlew :client:test --tests "*nav.v2.bfs.*"` — all 38 pass.
- [x] Spec §4 Lane 3's 8 QC tests pass (mapped above).
- [x] All Jagex server-alignment property tests pass (9/9). No divergence documented.
- [x] Manifest written.
- [x] No files outside `nav/v2/bfs/` touched (production). One docs file in `docs/superpowers/plans/`.
- [x] No edits to spec §3.

**Lane 3 hand-off complete. See `lane3-manifest.md`.**
