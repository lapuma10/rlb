# Observation-Aware Nav Engine — Integration Handoff

**Date**: 2026-05-17
**Branch**: `nav-engine-integration` (local only, not pushed)
**Status**: Functionally integrated end-to-end. In-game flow goes through new engine. Manual gates pending.

---

## What landed

5 lanes merged + wiring pass + QC-driven fixes. Engine wiring is:

```
RecorderPlugin.startUp()
   ├── GlobalCollisionSnapshot.fromBundledResource()      // Skretzo collision map
   ├── TransportTable.loadDefaults()                       // Skretzo transport TSVs (6310 links)
   ├── PredicateRegistry  (empty by default — see "Known limitations")
   └── WaypointPlannerShim(client, clientThread, snapshot, table, predicates)
         │
         └── V2Navigator.withPlannerHook(shim, executor, playerLoc, entityIndex)
              ├── setTransportCorrectionSink → shim.applyTransportCorrection
              └── tick(NavRequest)
                   ├── shim.plan(from, to, mode, trail)
                   │     ├── WorldSnapshotBuilder.fromClient(...)
                   │     ├── WaypointPlanner.plan(req, from, snap, BfsConfig.defaults())
                   │     │     ├── LinkGraphDijkstra.findRouteSkeleton(...)
                   │     │     ├── SkretzoBfsKernel.plan(...)  per leg
                   │     │     ├── PathCompressor.compressLeg(...)  → sparse waypoints
                   │     │     ├── RouteValidator.validate(...)
                   │     │     └── V2PathImpl.of(steps, walkLegFlatTiles, transportLegs)
                   │     └── translate to nav/v2/V2Path with V2Leg.Walk(full tiles)
                   │         + V2Leg.Transport(synthesized TransportEdge)
                   └── V2Executor.tick()  // legacy path, consumes V2Path with tiles
```

If `GlobalCollisionSnapshot.fromBundledResource()` or `TransportTable.loadDefaults()` fails, the wiring falls back to legacy `V2Planner` (currently warn-and-fallback per code review; consider failing fast).

---

## Commit history on `nav-engine-integration` (vs master)

```
3c6fb9089 fix(nav-engine,integration): QC criticals — walk leg tiles, null safety, sink
432998b0a feat(nav-engine,integration): wire WaypointPlannerShim into RecorderPlugin
c94492337 feat(nav-engine,integration): WaypointPlannerShim adapts new planner to V2Navigator.PlannerHook
9872c6471 feat(nav-engine,integration): wire WaypointPlanner with canonical types
e1d124978 feat(nav-engine,integration): add WorldSnapshot.playerPosition() contract
cf5ea2e46 chore(nav-engine,integration): delete RouteReadiness per spec §8 THROW
d9b15f4a5 Merge worktree-agent-aa6948899731652dd (Lane 4)
8530c644b Merge worktree-agent-afe99bebe5df9e8fd (Lane 6)
29d7e013b Merge worktree-agent-ac83a1284e53b7ddd (Lane 5)
2ba4454da Merge worktree-agent-a34ddb35cbfc9d360 (Lane 3)
435ffc0ee Merge worktree-agent-a6f252738a49469d2 (Lane 2)
```

Plus 3 master commits before merging: spec + plans + QC fixes.

---

## Build + run commands

```bash
# Compile
JBIN=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home/bin/java
JAVA_HOME=$(dirname $JBIN)/.. ./gradlew :client:compileJava

# Shadow jar (for running the bot)
JAVA_HOME=$(dirname $JBIN)/.. ./gradlew :client:shadowJar

# Tests (currently 374 passing + 12 acceptance skipped via Assume)
JAVA_HOME=$(dirname $JBIN)/.. ./gradlew :client:test --tests "*nav.v2.*"

# Run (classic accounts)
$JBIN -ea \
  --add-opens java.desktop/com.apple.eawt=ALL-UNNAMED \
  --add-opens java.desktop/com.apple.eawt.event=ALL-UNNAMED \
  --add-opens java.base/java.lang=ALL-UNNAMED \
  -jar runelite-client/build/libs/client-*.jar \
  --developer-mode &

# Run (Jagex accounts via the pre-launcher)
$JBIN -cp runelite-client/build/libs/client-*.jar \
  net.runelite.client.launcher.AccountLauncher
```

---

## Manual gate procedure (spec §6 Tests 2 + 8)

These are the real proof. Tests 2 and 8 require a live bot-runner session.

### Test 8 — bank↔pen 10 consecutive cycles (CRITICAL)

1. Build shadow jar.
2. Launch client with developer mode.
3. Log in to a chicken-farm-eligible account at the Lumbridge chicken pen / bank loop.
4. Start ChickenFarmV3 from the panel.
5. **Watch `~/.runelite/logs/client.log` for `[waypoint-shim]` log lines** — these confirm the new engine is the active planner (vs legacy `V2Planner`, which logs `[V2Planner]`).
6. Allow ≥10 full cycles. Log each cycle's outcome.

**Pass criteria**:
- 10/10 cycles complete (no manual intervention, no stuck loops)
- Logs show `[waypoint-shim] plan ... → ... legs=N cost≈M pathId=...` per cycle
- No untyped failures; all replans carry typed `ReplanReason`
- Visually distinct traces cycle-to-cycle (BFS tie-break + sidestep variation)

### Test 2 — Cross-region (Draynor → Lumbridge or similar)

1. From the panel, request a cross-region nav (script-side or manual `NavRequest.toPoint(...)`).
2. Confirm transport legs appear in `V2Path.legs()` (gates, doors, stairs).
3. Bot arrives at destination without intervention.

---

## Phase 4 — Default flip (after manual gates pass)

The new engine is already the default path when Skretzo data loads. To make this an explicit user-visible config:

1. Add `enableWaypointPlanner` config flag to `RecorderConfig` (default `true`).
2. Gate the shim construction in `RecorderPlugin.startUp` lines 510–530 behind that flag.
3. When disabled, the existing `else` branch already falls back to legacy `V2Planner`.

This is a ~30-line change. **Not done yet** (architect QC raised it; deferred to make-it-work focus).

After Phase 4: merge `nav-engine-integration` → `master`.

---

## Known limitations (open from QC pass — not yet fixed)

Severity-ordered. None blocks the bot from running.

### High — should fix before serious use

1. **BFS doesn't consult predicates** (architect critical #1). `WaypointPlanner` passes `(TilePredicate) null` to `SkretzoBfsKernel.plan` and `RouteValidator.validate`. Means: `InvalidationClassifier`'s tile blacklist + any script-injected `addTileCondition(...)` don't affect routing. Bot may route through tiles a human player would avoid.
2. **`PlayerState` is stubbed** (`WaypointPlanner.extractPlayerStateOrNull` always returns null → `stubPlayerState()` with skillLevel=1, isMember=false, empty inventory). Every requirement-gated transport in the TSVs (548 agility shortcuts, 199 spirit-trees, 413 teleport items, 173 teleport spells, etc.) is filtered out by `TransportRequirement.satisfiedBy(ctx)`. Bot can route through doors/stairs/basic gates but not skill-gated or item-gated transports.
3. **`PredicateRegistry` is empty at startup**. `RecorderPlugin` constructs it but never registers `InvalidationClassifier.asTilePredicate()` or built-ins. Even fixing (1) above wouldn't matter until the registry has something registered.
4. **`SidestepResolver` + `PathStepCursor` unwired in `V2Executor.tick()`**. Lane 5 implemented + tested them; they don't run in production. Bot uses legacy `CanvasTilePicker` over the now-fattened tile lists, which is fine, but spec §1's "executor owns concrete movement with sidestep" contract is satisfied only via the legacy picker's bucket logic (which works because walk legs have full tile sequences now), not the formalized SidestepResolver path.

### Medium

5. **Acceptance tests 1-9 still skip via `Assume`**. `NavigationTestHarness.wirePlannerExecutor(...)` has no caller. Reason: harness uses `qc/contracts/*` types (Lane 6's mirrors), production uses `nav/v2/` + `nav/v2/transport/` types. The adapter to bridge them was the work the integration agent stalled on.
6. **`LinkGraphDijkstra` walk-edge construction is `O(N²)` over ~7000 transport endpoints** (backend critical #2). For typical plans this is sub-second but degrades under stress + replan budget.
7. **Type duplication** — `V2Path`, `PathStep`, `WalkStep`, `TransportStep`, `TransportLeg`, `TransportType`, `Waypoint`, `WaypointType`, `PathId`, `ReplanReason` exist in BOTH flat `nav/v2/` (Lane 5 classes) AND `nav/v2/transport/` (Lane 4 interfaces). The shim bridges. Future cleanup pass.

### Low

8. Lane 3's local mock interfaces in `nav/v2/bfs/` (`CollisionView`, `TilePredicate`, `LocalReplanReason`, `PlaneTransition`) shadow canonical types.
9. Lane 4's `nav/v2/planner/spi/*` (11 mock files) shadow canonical types.
10. Lane 6's `nav/v2/qc/contracts/*` (22 mirror files) currently live in `src/main/` — should move to `src/test/` or delete after harness wiring.
11. Symbolic Skretzo constants (`SKAVID_MAP`, `CROSSBOW`, bit-ops) dropped from requirement gates — Skretzo's preprocessor isn't ported. Affects a fraction of transport rows.
12. `MultiRegionAStar`, `TopKRouter`, `RouteHistory`, `V2Planner`, trail-bias — all spec §8 THROW, still in the codebase (kept for legacy fallback + `InspectionDumper`).

---

## QC reports archived

The 5 parallel QC subagent reports (architect, backend, qa, code-review, tech-debt) are in the conversation transcript. Key recurring themes:
- All 4 of 5 returning agents flagged the shim's walk-leg collapse → fixed in commit `3c6fb9089`
- All flagged predicates-not-consulted → known limitation #1 above
- All flagged stubbed PlayerState → known limitation #2 above
- Type duplication is universally observed but accepted as transitional debt

Verdict spread:
- Architect: REQUEST RESTRUCTURE (3 critical issues now fixed, 2 deferred)
- Backend: APPROVE WITH FIXES
- QA: REQUEST RESTRUCTURE (acceptance gate doesn't execute — deferred)
- Code review: APPROVE WITH FIXES
- Tech debt: 17 items inventoried, 1 blocks_in_game (now fixed)

---

## Next-action priorities (post-manual-gate)

If manual gates pass and you want to retire the limitations above, in order:

1. **Predicate plumbing** (M effort) — bridge `PredicateRegistry` → `bfs.TilePredicate` in `WaypointPlanner.plan(...)`. Register `InvalidationClassifier.asTilePredicate()` in `RecorderPlugin` startup.
2. **PlayerState plumbing** (M effort) — replace `WaypointPlanner.extractPlayerStateOrNull` stub with a real call to `PlayerStateBuilder.fromClient(...)`. Plumb it through the shim.
3. **Acceptance test wiring** (M effort) — write the `qc/contracts/*` ↔ canonical adapter, then a `@BeforeClass` that calls `NavigationTestHarness.wirePlannerExecutor(...)`. Un-skips Tests 1-9.
4. **Lane 5 sparse-waypoint wiring** (L effort) — refactor `V2Executor.tick()` to consume `PathStep` directly via `PathStepCursor` + `SidestepResolver`. Retires the legacy walk-leg path.
5. **§8 THROW cleanup** (S each, ~5 items) — delete `MultiRegionAStar`, `TopKRouter`, `RouteHistory`, `V2Planner`, trail-bias. Cascades naturally once steps 1-4 land.
