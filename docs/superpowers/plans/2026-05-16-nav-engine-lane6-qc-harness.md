# Lane 6 — QC + Test Harness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: `superpowers:executing-plans` if dispatched as a single agent.
> **Master plan**: `docs/superpowers/plans/2026-05-16-nav-engine-master.md`
> **Spec**: `docs/superpowers/specs/2026-05-16-observation-aware-navigation-engine-design.md`
> **Required reading**: spec §0 (Master Direction — drift gate), §3 (Locked Contracts), §4 Lane 6 entry, §5 (Definition of Done — observability bar), §6 (Acceptance Tests — your deliverable).

**Goal**: Build the independent QC harness + 9 acceptance tests that gate the engine's merge. Lane 6 has **blocking authority**: nothing ships if any of the 9 acceptance tests fail.

**Architecture**: Lane 6 builds against the locked contracts in spec §3 and reads each other lane's outputs as they land. Owns NO production code (only test harness utilities). Produces the route trace format that everyone else's debug logs feed into. Each of the 9 acceptance tests in spec §6 becomes one JUnit test class with its concrete pass criteria.

**Tech**: Java 17, JUnit, Gradle, the locked contract interfaces.

---

## File structure

**Create (harness utilities):**
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v2/qc/NavigationTestHarness.java`
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v2/qc/RouteTraceRecorder.java`
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v2/qc/RouteReplayValidator.java`
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v2/qc/OverlayTraceExporter.java`
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v2/qc/GoldenRouteFixtures.java`
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v2/qc/LiveAcceptanceChecklist.java`

**Tests** (acceptance — `src/test/java/net/runelite/client/plugins/recorder/nav/v2/acceptance/`):
- `SameRegionRouteTest.java` (Test 1)
- `CrossRegionRouteTest.java` (Test 2)
- `TransportInteractionTest.java` (Test 3)
- `SingleTileBlockerTest.java` (Test 4)
- `CorridorBlockedTest.java` (Test 5)
- `PredicateTest.java` (Test 6)
- `RouteCompressionTest.java` (Test 7)
- `BankPenRegressionTest.java` (Test 8 — un-skips `V2BankPenLiveDataTest`)
- `TraceQualityTest.java` (Test 9)

---

## Task 1 — NavigationTestHarness

**File**: `qc/NavigationTestHarness.java`.

Behavior: Programmatic harness for running `WaypointPlanner` + `V2Executor` + `V2Navigator` end-to-end against fixture inputs. Methods:
- `runRoute(NavRequest req, WorldSnapshot snap, PlayerState ps, BfsConfig cfg) → RunRecord` — invokes planner, walks the resulting `V2Path` step-by-step via a simulated executor (or the real one with a fake `Client`), records every decision.
- `runRouteLive(NavRequest req, Client client, ClientThread ct) → RunRecord` — connects to a real client for live runs (manual acceptance use case).
- `RunRecord = {V2Path planned, List<ExecutorTickResult> ticks, List<RouteTrace> traces, Optional<ReplanReason> failureReason, long totalMs}`.

No tests for the harness itself in this task; it's exercised by every acceptance test.

Commit: `feat(nav-engine,lane6): NavigationTestHarness`

---

## Task 2 — RouteTraceRecorder

**File**: `qc/RouteTraceRecorder.java`.

Behavior: Hooks into the locked-contract surface (`PredicateRegistry.firstRejectorOf`, `V2Executor` per-tick debug log, `WaypointPlanner` planner debug log, `CollisionView.source(...)`). For each tick, emits a `RouteTrace` record:
```
RouteTrace {
  String tickId;                         // matches ExecutorTickResult.debugTraceId
  long tickEpochMs;
  WorldPoint playerAt;
  Optional<Waypoint> currentWaypoint;
  Optional<TransportLeg> currentTransport;
  List<WorldPoint> candidatesConsidered;
  List<Rejected> candidatesRejected;     // tile + reason
  Optional<WorldPoint> candidateChosen;
  boolean sidestepUsed;
  ExecutorResult result;
  Optional<ReplanReason> replanReason;
}
```

Writes JSONL by default to `~/.runelite/nav-traces/<runId>.jsonl`.

Commit: `feat(nav-engine,lane6): RouteTraceRecorder JSONL output`

---

## Task 3 — RouteReplayValidator

**File**: `qc/RouteReplayValidator.java`.

Behavior: Read a JSONL trace, re-run `RouteValidator` (Lane 3) on every step transition. Output: `ReplayValidationResult` with per-tick OK/FAIL. Used by acceptance tests to gate "every recorded path validates against collision."

Commit: `feat(nav-engine,lane6): RouteReplayValidator`

---

## Task 4 — OverlayTraceExporter

**File**: `qc/OverlayTraceExporter.java`.

Behavior: Reads N JSONL traces of the same route. Produces an overlay image / SVG / text-diff showing where they overlap vs diverge. Used by Test 9 (trace quality) to verify cycle-to-cycle distinctness. Format choice: SVG (light dependency, easy to inspect).

Commit: `feat(nav-engine,lane6): OverlayTraceExporter`

---

## Task 5 — GoldenRouteFixtures

**File**: `qc/GoldenRouteFixtures.java`.

Behavior: Static factory producing fixture `WorldSnapshot` + `PlayerState` + `NavRequest` for known scenarios:
- `bankPenStart()` — bank tile start; pen tile target; standard chicken-farm context.
- `lumbridgeToDraynor()` — cross-region.
- `straightCorridorWithBlocker()` — single-tile blocker scenario.
- `fullyBlockedCorridor()` — corridor blocked scenario.
- `transportApproachAtGate()` — gate interaction scenario.

Fixtures pull from real data wherever possible (existing `V2BankPenLiveDataTest`'s `~/.runelite/recorder/worldmap/` is one source).

Commit: `feat(nav-engine,lane6): GoldenRouteFixtures`

---

## Task 6 — LiveAcceptanceChecklist

**File**: `qc/LiveAcceptanceChecklist.java`.

Behavior: A driver class for *manual* live runs (Phase 4 of master plan dispatch order). Reads a YAML-shaped checklist:
- Per-route: start tile, target tile, expected outcome, max cycles, pass criteria.
- On execution: drives `NavigationTestHarness.runRouteLive(...)` cycle-by-cycle; logs each cycle's pass/fail with reason.

For Test 8 (bank↔pen 10 cycles), this is the runner.

Commit: `feat(nav-engine,lane6): LiveAcceptanceChecklist runner`

---

## Tasks 7-15 — The 9 acceptance tests

Each test corresponds to a spec §6 test. Same shape: arrange fixture → run via `NavigationTestHarness` → assert per spec criteria.

### Task 7 — Test 1: SameRegionRouteTest

**File**: `acceptance/SameRegionRouteTest.java`.

Behavior: 10 runs of fixture `GoldenRouteFixtures.straightCorridor()`. For each:
- Assert `V2Path.steps()` contains `WalkStep` only (no `TransportStep`).
- Assert `RouteValidator` (Lane 3) passes.
- Assert executor reaches target.
- Assert no `NEEDS_REPLAN` emitted.

Pass: 10/10 success, 0 invalid steps, 0 replans.

Commit: `test(nav-engine,lane6,acceptance): Test 1 same-region routing`

### Task 8 — Test 2: CrossRegionRouteTest

**File**: `acceptance/CrossRegionRouteTest.java`.

Behavior: 5 runs of `GoldenRouteFixtures.lumbridgeToDraynor()`. Assert:
- `V2Path.steps()` interleaves `WalkStep` + `TransportStep`.
- Region boundary handled (no coord/plane corruption).
- At least 1 of the 5 routes is executed live (via `LiveAcceptanceChecklist`) — manual run.

Pass: 5/5 generated, 1/5 live-validated, all validators pass.

Commit: `test(nav-engine,lane6,acceptance): Test 2 cross-region routing`

### Task 9 — Test 3: TransportInteractionTest

**File**: `acceptance/TransportInteractionTest.java`.

Behavior: Fixture `transportApproachAtGate()`. Assert:
- Planner emits a `TRANSPORT_APPROACH` waypoint before the transport.
- Executor reaches the exact approach tile.
- Executor performs typed action.
- Path continues after transport.
- Failure (when gate is locked, fixture variant) produces typed `TRANSPORT_UNAVAILABLE` not silent stuck.

Commit: `test(nav-engine,lane6,acceptance): Test 3 transport interaction`

### Task 10 — Test 4: SingleTileBlockerTest

**File**: `acceptance/SingleTileBlockerTest.java`.

Behavior: Fixture `straightCorridorWithBlocker()` — one blocker on the ideal next tile. Assert:
- `V2Path` remains valid (planner doesn't re-plan).
- Executor's trace log shows `sidestep=true`.
- `ExecutorTickResult.result == WAYPOINT_REACHED`, NOT `NEEDS_REPLAN`.
- Progress toward target continues.

Commit: `test(nav-engine,lane6,acceptance): Test 4 single-tile blocker sidestep`

### Task 11 — Test 5: CorridorBlockedTest

**File**: `acceptance/CorridorBlockedTest.java`.

Behavior: Fixture `fullyBlockedCorridor()`. Assert:
- Executor returns `NEEDS_REPLAN` with `ReplanReason.NO_LOCAL_WALKABLE_TILE`.
- Navigator triggers a replan (within budget).
- No infinite clicking; no random off-route movement; no fake success.

Commit: `test(nav-engine,lane6,acceptance): Test 5 corridor-blocked typed replan`

### Task 12 — Test 6: PredicateTest

**File**: `acceptance/PredicateTest.java`.

Behavior: Same-region fixture; register a custom predicate via `addTileCondition(tile, predicate)` that disallows tile X. Assert:
- BFS path does NOT contain X.
- Executor never picks X even when reachable.
- If no route exists without X → planner returns `TARGET_UNREACHABLE`.

Commit: `test(nav-engine,lane6,acceptance): Test 6 custom tile predicate`

### Task 13 — Test 7: RouteCompressionTest

**File**: `acceptance/RouteCompressionTest.java`.

Behavior: Fixture with a long BFS-resolved tile sequence (300 tiles). Assert:
- Compressed `V2Path.steps()` has 5-15 waypoints.
- Every `TRANSPORT_APPROACH` from the original sequence is preserved exact.
- Every plane change is preserved.
- `RouteValidator` passes on compressed path.

Commit: `test(nav-engine,lane6,acceptance): Test 7 route compression`

### Task 14 — Test 8: BankPenRegressionTest

**File**: `acceptance/BankPenRegressionTest.java`.

Behavior: Un-skip the existing `V2BankPenLiveDataTest` (currently gated by `Assume.assumeTrue` on local data presence). Wrap it in a 10-cycle loop via `LiveAcceptanceChecklist`. Assert:
- 10/10 cycles complete.
- No manual intervention required.
- Every cycle produces a debug trace.
- No untyped failures.

For CI: provide a way to mark this test as "live-only" so CI doesn't break on machines without the recorder data; but the manual checklist requires this to pass before Phase 4 default-flip.

Commit: `test(nav-engine,lane6,acceptance): Test 8 bank↔pen 10-cycle regression`

### Task 15 — Test 9: TraceQualityTest

**File**: `acceptance/TraceQualityTest.java`.

Behavior: Run the same route 5 times via `NavigationTestHarness`. For each run, record a JSONL trace. Then:
- Use `RouteReplayValidator` to verify every trace validates against collision.
- Use `OverlayTraceExporter` to produce an SVG overlay.
- Assert: every trace completes; every trace validates; no trace is a hardcoded tile replay (i.e. the 5 traces are not all byte-identical — verified by checking trace hash distinctness across runs where dynamic blockers were present).

**Framing**: this is route QUALITY (per spec memory `feedback_no_evasion_framing`). Not detection-evasion. Pass criterion is "robust pathing under varying conditions," not "looks human."

Commit: `test(nav-engine,lane6,acceptance): Test 9 trace quality`

---

## Task 16 — Final hand-off and merge gate

**File**: `docs/superpowers/plans/lane6-manifest.md`.

Manifest:
- All harness files + line counts.
- All 9 acceptance tests + status.
- Live acceptance checklist results from manual runs.
- Trace samples (1 per acceptance test).
- Final verdict: **GO** (all 9 passing) or **NO-GO** (which test failed + why).

**This is the merge gate.** If verdict is NO-GO, surface to Lane 1 (spec) + the failing lane's owner. Do not merge.

Commit: `docs(nav-engine,lane6): final manifest + merge verdict`

---

## Self-test acceptance

Lane 6 is done when:

- [ ] All 6 harness production files exist and `./gradlew :client:compileJava` succeeds.
- [ ] All 9 acceptance test classes exist.
- [ ] **All 9 acceptance tests pass** (the merge gate).
- [ ] Manifest written with **GO** verdict.
- [ ] No edits to files under `nav/v2/collision/`, `nav/v2/bfs/`, `nav/v2/transport/`, `nav/v2/planner/`, `nav/v2/predicate/`, `nav/v2/executor/`, or existing `nav/v2/V2*.java`.
- [ ] No edits to spec §3.

Hand-off: announce "Lane 6 acceptance complete. Verdict: GO. Engine clear to flip default per Phase 4 of master plan."
