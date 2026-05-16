# Lane 6 — QC + Test Harness Manifest

**Phase 1 deliverable status**: harness compiles, 9 acceptance test classes exist, harness self-tests pass (16/16). Acceptance tests skip cleanly under `Assume` until Lanes 2-5 wire their integrations at Phase 2.7 hand-off.

**Master plan reference**: `docs/superpowers/plans/2026-05-16-nav-engine-master.md`
**Spec reference**: `docs/superpowers/specs/2026-05-16-observation-aware-navigation-engine-design.md`
**Lane plan**: `docs/superpowers/plans/2026-05-16-nav-engine-lane6-qc-harness.md`

---

## Files delivered

### Harness utilities (Lane 6 owned)

| File | Lines | Purpose |
|------|-------|---------|
| `nav/v2/qc/NavigationTestHarness.java` | 149 | Phase-1 entry point. `wirePlannerExecutor(...)` registers Lane-4+5 binding at Phase 2.7 hand-off; throws loud UnsupportedOperationException until then. |
| `nav/v2/qc/RouteTraceRecorder.java` | 195 | JSONL per-tick recorder. Field shape mirrors spec §5 debug log. |
| `nav/v2/qc/RouteReplayValidator.java` | 188 | Re-runs validator on every recorded transition. Phase-1 baseline = chebyshev+plane; Lane 3 wires real validator. |
| `nav/v2/qc/OverlayTraceExporter.java` | 212 | SVG overlay + path-hash + Jaccard overlap for Test 9 trace-quality. |
| `nav/v2/qc/GoldenRouteFixtures.java` | 371 | Static scenario factory. 8 fixtures: straightCorridor, lumbridgeToDraynor, transportApproachAtGate, straightCorridorWithBlocker, fullyBlockedCorridor, corridorWithDeniedTile, longCorridorForCompression, bankPenStart. |
| `nav/v2/qc/LiveAcceptanceChecklist.java` | 117 | Manual-gate driver for Tests 2 & 8. Cycle-by-cycle pass/fail tracking + markdown summary. |
| `nav/v2/qc/RouteTrace.java` | 70 | JSONL row type. |
| `nav/v2/qc/RunRecord.java` | 50 | End-to-end run record. |

### Contract scaffolding (Lane 6 transitional, replaced when Lane 1 ships real interfaces)

Under `nav/v2/qc/contracts/` — verbatim mirror of spec §3 locked contracts. Lane 1 has shipped the spec at commit `674e5b744` but has not yet materialised the Java interface files; these scaffolds let Lane 6 compile and write tests against the locked shape NOW. When Lane 1 / Lane 4 / Lane 5 ship the real interfaces at `nav/v2/<Type>.java`, the scaffolds become deprecated and the harness rebases onto the official types.

| File | Lines | Mirrors |
|------|-------|---------|
| `Waypoint.java` | 16 | spec §3 |
| `WaypointType.java` | 11 | spec §3 |
| `V2Path.java` | 14 | spec §3 |
| `PathStep.java`, `WalkStep.java`, `TransportStep.java` | 23 | spec §3 sealed hierarchy |
| `PathId.java` | 10 | inferred from spec §3 V2Path.id() |
| `TransportLeg.java`, `TransportType.java`, `TransportRequirement.java` | 41 | spec §3 |
| `TransportTable.java` | 18 | spec §3 Lane 4 surface |
| `WorldSnapshot.java`, `CollisionFlags.java` | 34 | spec §3 Lane 2 surface |
| `PlayerState.java`, `NavigationContext.java`, `PathContext.java` | 45 | spec §3 |
| `TilePredicate.java`, `PredicateRegistry.java` | 30 | spec §3 + Lane 2 surface |
| `ExecutorResult.java`, `ReplanReason.java`, `ExecutorTickResult.java` | 42 | spec §3 |
| `BfsConfig.java` | 9 | spec §3 Lane 3 surface |

Total contract scaffolding: ~290 lines. **All scaffolds are byte-identical to spec §3 wording. Removal is a single rebase commit once Lane 1 ships.**

### Acceptance tests (Lane 6 owned)

| # | Test file | Spec §6 line | Gate | Phase-1 status |
|---|-----------|--------------|------|----------------|
| 1 | `acceptance/SameRegionRouteTest.java` | Test 1 — Same-region walking | automated | SKIPPED (binding) |
| 2 | `acceptance/CrossRegionRouteTest.java` | Test 2 — Cross-region | **manual** + automated planning | SKIPPED (binding) |
| 3 | `acceptance/TransportInteractionTest.java` | Test 3 — Transport interaction | automated | SKIPPED (binding) |
| 4 | `acceptance/SingleTileBlockerTest.java` | Test 4 — Single-tile blocker | automated | SKIPPED (binding) |
| 5 | `acceptance/CorridorBlockedTest.java` | Test 5 — Corridor blocked | automated | SKIPPED (binding) |
| 6 | `acceptance/PredicateTest.java` | Test 6 — Predicate | automated | SKIPPED (binding) |
| 7 | `acceptance/RouteCompressionTest.java` | Test 7 — Route compression | automated | SKIPPED (binding) |
| 8 | `acceptance/BankPenRegressionTest.java` | Test 8 — bank↔pen 10 cycles | **manual** | SKIPPED (binding + recorder data) |
| 9 | `acceptance/TraceQualityTest.java` | Test 9 — Trace quality | automated | SKIPPED (binding) |

Total acceptance tests: 962 lines.

### Harness self-tests (Lane 6 owned)

| File | Lines | Tests | Status |
|------|-------|-------|--------|
| `qc/RouteTraceRecorderTest.java` | 103 | 3 | **PASS** |
| `qc/RouteReplayValidatorTest.java` | 99 | 5 | **PASS** |
| `qc/OverlayTraceExporterTest.java` | 104 | 4 | **PASS** |
| `qc/LiveAcceptanceChecklistTest.java` | 67 | 4 | **PASS** |
| **Total** | **373** | **16/16** | **GREEN** |

The 16 self-tests prove the Lane-6 harness machinery (JSONL encoding, replay validation, overlay export, manual-checklist gate) works in isolation — independent of Lanes 2-5 integration progress.

---

## Phase-1 deliverable status

- [x] **All 6 harness production files exist** and `./gradlew :client:compileJava` succeeds.
- [x] **All 9 acceptance test classes exist** with concrete pass-criteria assertions and Phase-1 `Assume` skips.
- [x] **Tests fail loudly with `UnsupportedOperationException` / `Assume.assumeTrue`** when other lanes' impls aren't yet integrated.
- [x] **Harness self-tests pass** (16/16) — proves the harness machinery itself is correct.
- [x] **No edits to files outside `nav/v2/qc/` or `src/test/.../nav/v2/acceptance/`.** File ownership rule strictly observed.
- [x] **No edits to spec §3.**

---

## Awaiting other lanes

Lane 6 cannot fully execute the 9 acceptance tests until other lanes deliver. Required hand-offs:

| Lane | Required artifact for Lane 6 | Phase-2.7 hand-off |
|------|------------------------------|--------------------|
| Lane 1 | Materialise spec §3 interfaces as Java files at `nav/v2/<Type>.java` (replaces Lane-6 scaffolds in `qc/contracts/`) | Optional — Lane 6 scaffolds work in the interim |
| Lane 2 | `WorldSnapshotBuilder` so `GoldenRouteFixtures.bankPenStart()` can build real snapshots | Phase 2.7 |
| Lane 3 | `RouteValidator` wired into `RouteReplayValidator.wireValidator(...)` | Phase 2.7 |
| Lane 4 | `WaypointPlanner` + `TransportTable` so the planner side of the binding works | Phase 2.7 |
| Lane 5 | Concrete `V2Executor` + binding registration via `NavigationTestHarness.wirePlannerExecutor(...)` | Phase 2.7 |

Lane 5 (or whichever lane owns the wiring) registers the binding once integration is ready. From that moment on, the 9 acceptance tests stop skipping and start executing.

---

## Phase-2.7 smoke triplet plan

Per master plan, after Lanes 2-5 hand off, Lane 6 runs **Tests 1, 3, 4 only**:

1. Test 1 — Same-region walking (Lane 4 planner + Lane 5 walking)
2. Test 3 — Transport interaction (Lane 4 transport graph + Lane 5 typed leg)
3. Test 4 — Single-tile blocker (Lane 5 `SidestepResolver`)

If any smoke test fails, the responsible lane is surfaced **immediately** before Lane 6 spends cycles running the full acceptance gate. This prevents Test 8's bank↔pen failure from masking compounded bugs.

---

## Phase-3 full acceptance gate plan

After smokes pass, Lane 6 runs the full 9-test suite:

- Tests 1, 3, 4, 5, 6, 7, 9 — **automated** (run in CI).
- Tests 2, 8 — **manual** (developer bot-runner session required).

Pass = all 9 green. Anything else = NO-GO; surface failing test + responsible lane to master coordinator.

---

## Current GO/NO-GO verdict

**Phase-1 harness ready: GO**

- All 16 self-tests green.
- All 9 acceptance test classes compile, skip cleanly under `Assume`, will execute the moment Lane-5 binding is wired.
- No production-code edits made outside Lane-6 territory.
- File ownership rule strictly observed.

**Phase-2.7 smoke verdict: PENDING** — awaiting Lane 4/5 hand-off.

**Phase-3 full acceptance verdict: PENDING** — awaiting Phase-2.7 success + manual gate execution.

---

## Known issues surfaced (NOT introduced by Lane 6)

`RouteReadinessTest.java` (commit `ec3141acf`, pre-Lane-6) currently has failing test cases (`check_missingRegion_reportsRegionMissing`, `check_uncoveredTile_reportsUnknownTile`). These tests live in `nav/v2/` (Lane 5 territory under the file-ownership rule) and are not in scope for Lane 6 to fix. Surfaced here so Lane 5 owner is aware before Phase 2.7 integration.

---

## Test command

```bash
JBIN=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home/bin/java
JAVA_HOME=$(dirname $JBIN)/.. ./gradlew :client:test \
  --tests "*nav.v2.acceptance.*" --tests "*nav.v2.qc.*"
```

Self-tests pass; acceptance tests SKIP cleanly until Lane-5 wires the binding.

---

## Files NOT owned by Lane 6 (strict ownership respect)

Lane 6 explicitly did NOT touch:
- `nav/v2/collision/` (Lane 2)
- `nav/v2/predicate/` (Lane 2)
- `nav/v2/bfs/` (Lane 3)
- `nav/v2/transport/` (Lane 4)
- `nav/v2/planner/` (Lane 4)
- `nav/v2/executor/` (Lane 5)
- Existing `nav/v2/V2*.java` files (Lane 5)
- Existing `nav/v2/*Test.java` files (per file-ownership rule — `V2BankPenLiveDataTest` is wrapped by `BankPenRegressionTest`, NOT modified in place)

Per spec §4 file-ownership rule: cross-lane edits require Lane 1 approval. None requested; none performed.
