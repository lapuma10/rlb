# Navigator V2 Implementation Plan

> **For agentic workers:** This plan is executed **inline** by Claude in the main session per the user's standing rule (`feedback_inline_impl_subagent_qc.md`). After each phase compiles + tests pass + commit lands, Claude dispatches an **independent QC subagent** to review the implemented diff against this plan and the spec. The subagent does NOT re-review the spec, only the implementation.

**Goal:** Ship the V2 dynamic walker as a second implementation of a new `Navigator` interface — V1 (TrailWalker) frozen and wrapped, single switch site, ChickenFarmV3 wired to the interface, V2 capable of completing bank↔pen with route alternation and recovering Draynor→Lumby from the world model alone.

**Architecture:** Spec at `docs/superpowers/specs/2026-05-07-navigator-interface-architecture.md`. Read it before each phase — this plan is the *how*, the spec is the *why* and the *what*.

**Tech stack:** Java 17, RuneLite plugin framework, JUnit 4 (existing test pattern), Gson (existing for trail + region JSON).

---

## Phase ordering (from spec + user review)

1. Navigator interface + V1 adapter + switch + ChickenFarmV3 wiring (V1 path stays default — proves the interface end-to-end without changing runtime behavior).
2. TransportObserver capture (passive, two-phase lifecycle).
3. Inspection tooling (panel dumps + minimap overlay).
4. V2 planner (extended MapPlanner: cross-region + cross-plane + transport edges + top-K + noise + recent-route memory).
5. V2 leg-executor (canvas + minimap modalities, empty-tile HARD rule, snapshot invalidation, catch-up clicks, run-toggle interface stub).
6. Wire V2 to switch + run seed pass + acceptance tests.

Each phase ends with: `:client:compileJava` clean, all new + existing tests pass, commit, QC subagent review.

**Build commands** (from `CLAUDE.md`):
```
JAVA_HOME=$(dirname /opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home/bin/java)/.. \
  ./gradlew :client:compileJava

JAVA_HOME=$(dirname ...)/.. ./gradlew :client:test --tests "<TestClass>"
```

---

## File map (new + modified)

**New files (V1 wrapping + interface plumbing — Phase 1):**
```
runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/Navigator.java
runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/NavRequest.java
runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/NavStatus.java
runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/BehaviorMode.java
runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/TrailNavigator.java
runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/NavigatorFactory.java
runelite-client/src/test/java/net/runelite/client/plugins/recorder/nav/TrailNavigatorTest.java
runelite-client/src/test/java/net/runelite/client/plugins/recorder/nav/NavigatorFactoryTest.java
```

**New files (V2 capture — Phase 2):**
```
runelite-client/src/main/java/net/runelite/client/plugins/recorder/worldmap/TransportObserver.java
runelite-client/src/main/java/net/runelite/client/plugins/recorder/worldmap/TransportEdge.java
runelite-client/src/main/java/net/runelite/client/plugins/recorder/worldmap/TransportIndex.java
runelite-client/src/main/java/net/runelite/client/plugins/recorder/worldmap/TransportIO.java
runelite-client/src/test/java/net/runelite/client/plugins/recorder/worldmap/TransportObserverTest.java
runelite-client/src/test/java/net/runelite/client/plugins/recorder/worldmap/TransportIOTest.java
```

**New files (inspection — Phase 3):**
```
runelite-client/src/main/java/net/runelite/client/plugins/recorder/worldmap/InspectionDumper.java
runelite-client/src/main/java/net/runelite/client/plugins/recorder/worldmap/WorldMapMinimapOverlay.java
runelite-client/src/test/java/net/runelite/client/plugins/recorder/worldmap/InspectionDumperTest.java
```

**New files (V2 planner — Phase 4):**
```
runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v2/V2Planner.java
runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v2/V2Path.java
runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v2/V2Leg.java
runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v2/MultiRegionAStar.java
runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v2/TopKRouter.java
runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v2/RouteHistory.java
runelite-client/src/test/java/net/runelite/client/plugins/recorder/nav/v2/MultiRegionAStarTest.java
runelite-client/src/test/java/net/runelite/client/plugins/recorder/nav/v2/TopKRouterTest.java
runelite-client/src/test/java/net/runelite/client/plugins/recorder/nav/v2/RouteHistoryTest.java
```

**New files (V2 executor — Phase 5):**
```
runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v2/V2Navigator.java
runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v2/V2Executor.java
runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v2/CanvasTilePicker.java
runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v2/MinimapClicker.java
runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v2/EmptyTileFilter.java
runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v2/InvalidationClassifier.java
runelite-client/src/test/java/net/runelite/client/plugins/recorder/nav/v2/EmptyTileFilterTest.java
runelite-client/src/test/java/net/runelite/client/plugins/recorder/nav/v2/CanvasTilePickerTest.java
runelite-client/src/test/java/net/runelite/client/plugins/recorder/nav/v2/InvalidationClassifierTest.java
```

**Modified files (across all phases — exact lines TBD on read at each phase):**
```
runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderConfig.java         (Phase 1: navigatorImpl flag; Phase 3: inspection toggle, overlay toggles)
runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPlugin.java         (Phase 1: factory wiring; Phase 2: TransportObserver lifecycle; Phase 3: overlay register; Phase 6: V2 wiring)
runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPanel.java          (Phase 1: navigator switch; Phase 3: dump buttons + overlay toggle)
runelite-client/src/main/java/net/runelite/client/plugins/recorder/scripts/ChickenFarmV3Script.java  (Phase 1: depend on Navigator instead of TrailWalker directly)
runelite-client/src/main/java/net/runelite/client/plugins/recorder/worldmap/MapPlanner.java    (Phase 4: lift cross-region/cross-plane rejects, accept transport edges)
```

---

## Phase 1 — Navigator interface + TrailNavigator + switch site + ChickenFarmV3 wiring

**Goal:** Land the interface, wrap V1 untouched, wire ChickenFarmV3 through it. V1 stays default — runtime behavior unchanged. This proves the contract end-to-end before V2 exists.

**Files:**
- Create all `nav/Navigator.java` … `nav/NavigatorFactory.java` listed above + matching tests.
- Modify `RecorderConfig.java` — add `navigatorImpl()` returning enum `NavigatorImpl { TRAIL_V1, WORLDMAP_V2 }`, default `TRAIL_V1`.
- Modify `RecorderPlugin.java` — instantiate `NavigatorFactory`, expose to scripts.
- Modify `RecorderPanel.java` — add a labeled dropdown "Navigator: V1 (Trail) / V2 (WorldMemory, experimental)" bound to the config.
- Modify `ChickenFarmV3Script.java` — replace direct `TrailWalker walker` field with `Navigator nav` from the factory; replace direct `walker.tick(currentPath)` with `nav.tick(currentRequest)`.

### Task 1.1 — Define the Navigator interface and value types

- [ ] **Step 1.1.1:** Read the spec sections "Architecture" and "V2 design — Goals" (lines 48-130 of the spec).
- [ ] **Step 1.1.2:** Create `Navigator.java`. Method shape (interface only, no impl):
  - `NavStatus tick(NavRequest request)` — called by scripts each tick; returns RUNNING / ARRIVED / FAILED / IDLE.
  - `void cancel()` — abort current navigation.
  - `boolean isBusy()` — true between non-IDLE ticks.
  - `String name()` — for logs, e.g. `"trail-v1"`, `"worldmap-v2"`.
- [ ] **Step 1.1.3:** Create `NavRequest.java` — immutable. Fields: `WorldPoint to`, `BehaviorMode mode`, optional `String trailName` (only used by TrailNavigator).
- [ ] **Step 1.1.4:** Create `NavStatus.java` — enum: `IDLE, RUNNING, ARRIVED, FAILED`.
- [ ] **Step 1.1.5:** Create `BehaviorMode.java` — enum: `NORMAL, EFFICIENT, VARIED, CAUTIOUS`. Add Javadoc noting only `VARIED` is implemented in round 1; others fall through.
- [ ] **Step 1.1.6:** Compile: `:client:compileJava`. Expected: clean.
- [ ] **Step 1.1.7:** Commit: `feat(nav): introduce Navigator interface + value types`.

### Task 1.2 — TrailNavigator adapter (wraps V1, does NOT modify it)

- [ ] **Step 1.2.1:** Read `trail/TrailWalker.java` to confirm the public surface ChickenFarmV3 currently uses (`tick(TrailPath)`, `cancel()`, status enum). Do NOT modify.
- [ ] **Step 1.2.2:** Read `trail/TrailRegistry.java` + `TrailGraph.java` + `TrailPlanner.java` for the existing trail-name → TrailPath resolution path (so the adapter can convert a `NavRequest` into a TrailPath).
- [ ] **Step 1.2.3:** Write `TrailNavigatorTest.java` first. Tests:
  - `tick_withTrailName_resolvesAndDelegatesToWalker`: stub registry returns a known TrailPath; verify TrailWalker is called with it.
  - `tick_withoutTrailName_failsWithModeNotSupported`: V1 needs a trail name; without one, return FAILED.
  - `cancel_propagatesToWalker`: verify TrailWalker.cancel() is invoked.
  - `name_returnsTrailV1`.
- [ ] **Step 1.2.4:** Run the test. Expected: FAIL — TrailNavigator does not exist.
- [ ] **Step 1.2.5:** Implement `TrailNavigator.java`. Constructor takes `TrailWalker`, `TrailRegistry`, `TrailGraph` (or `TrailPlanner`). Methods translate `NavRequest` ↔ TrailPath / TrailWalker.tick.
- [ ] **Step 1.2.6:** Run the test. Expected: PASS.
- [ ] **Step 1.2.7:** Commit: `feat(nav): TrailNavigator adapter wraps TrailWalker behind Navigator`.

### Task 1.3 — NavigatorFactory + RecorderConfig switch

- [ ] **Step 1.3.1:** Read `RecorderConfig.java` to see the `useWorldMemoryPlanner` config-item pattern.
- [ ] **Step 1.3.2:** Add to `RecorderConfig.java`: a `NavigatorImpl` enum (`TRAIL_V1`, `WORLDMAP_V2`), and a `@ConfigItem` returning `NavigatorImpl` with default `TRAIL_V1`. Item name: "Navigator: V1 (Trail) / V2 (WorldMemory, experimental)".
- [ ] **Step 1.3.3:** Write `NavigatorFactoryTest.java`. Tests:
  - `getNavigator_whenTrailV1_returnsTrailNavigator`.
  - `getNavigator_whenWorldmapV2_andV2NotRegistered_throws` (round-1 only V1 is registered; V2 registers in Phase 6).
  - `getNavigator_isStableWithinSession` (same instance between calls).
- [ ] **Step 1.3.4:** Run. Expected: FAIL.
- [ ] **Step 1.3.5:** Implement `NavigatorFactory.java`. Round-1 surface: holds a single `TrailNavigator`. `getNavigator()` reads `config.navigatorImpl()`; `WORLDMAP_V2` throws `UnsupportedOperationException` with a clear "V2 not yet registered" message.
- [ ] **Step 1.3.6:** Run. Expected: PASS.
- [ ] **Step 1.3.7:** Commit: `feat(nav): NavigatorFactory + RecorderConfig switch`.

### Task 1.4 — Wire factory into RecorderPlugin + dropdown into RecorderPanel

- [ ] **Step 1.4.1:** Read `RecorderPlugin.java` around line 391 (existing `worldmapRoot` wiring) to find the right place to construct `NavigatorFactory`.
- [ ] **Step 1.4.2:** In `RecorderPlugin.startUp()` (or equivalent): instantiate `NavigatorFactory` with the existing `TrailWalker`, `TrailRegistry`, `TrailGraph`. Expose a getter for scripts.
- [ ] **Step 1.4.3:** Modify `RecorderPanel.java`: add a `JComboBox<NavigatorImpl>` bound to the config. On change, persist via `configManager.setConfiguration(...)`.
- [ ] **Step 1.4.4:** Compile: `:client:compileJava`. Expected: clean.
- [ ] **Step 1.4.5:** Commit: `feat(nav): wire NavigatorFactory into plugin + panel dropdown`.

### Task 1.5 — Migrate ChickenFarmV3Script to Navigator

- [ ] **Step 1.5.1:** Read `scripts/ChickenFarmV3Script.java` line 62 (`private final TrailWalker walker`) and line 288 (`walker.tick(currentPath)`). Identify all call sites of `walker`.
- [ ] **Step 1.5.2:** Replace `TrailWalker walker` with `Navigator nav`. Constructor takes `Navigator` from `RecorderPlugin.getNavigatorFactory().getNavigator()`.
- [ ] **Step 1.5.3:** Replace `TrailWalker.Status` references with `NavStatus`. Existing call sites that branched on `TrailWalker.Status.RUNNING` / `ARRIVED` / `FAILED` keep the same semantics.
- [ ] **Step 1.5.4:** Where the script previously built a `TrailPath` directly, build a `NavRequest` instead — it carries the trail name (V1) or just the destination + mode (V2 later).
- [ ] **Step 1.5.5:** Compile: `:client:compileJava`. Expected: clean.
- [ ] **Step 1.5.6:** Smoke run (manual, user does this): launch RuneLite per `CLAUDE.md` build commands, start ChickenFarmV3 with default config (TRAIL_V1). Confirm bot still walks bank↔pen using existing trails. **No behavior change.**
- [ ] **Step 1.5.7:** Commit: `feat(nav): ChickenFarmV3 depends on Navigator interface, V1 still default`.

### Phase 1 exit gate

- [ ] Compile clean: `:client:compileJava`.
- [ ] All Phase-1 tests pass: `./gradlew :client:test --tests "*Navigator*"`.
- [ ] Manual smoke confirmed: ChickenFarmV3 still works under V1 default.
- [ ] Dispatch QC subagent (see "QC subagent prompts" at end of plan).

---

## Phase 2 — TransportObserver (capture, two-phase lifecycle, persistence)

**Goal:** Capture transport edges (stairs, gates, ladders, doors, climb actions, pay-toll, etc.) into `worldmap/transports.json` via passive observation, in parallel to TrailRecorder. Two-phase lifecycle per spec lines 192-228.

**Files:** All new — no modifications outside RecorderPlugin start/stop wiring.

### Task 2.1 — TransportEdge value type + TransportIndex in-memory store

- [ ] **Step 2.1.1:** Re-read spec section "TransportObserver capture lifecycle" (spec lines 192-228) and "Persistence layout" (lines 232-242).
- [ ] **Step 2.1.2:** Create `TransportEdge.java`. Fields: `WorldPoint fromTile`, `WorldPoint toTile`, `int objectId`, `String objectName`, `String verb`, `int param0`, `int param1`, `String targetKind` (GameObject / NPC / GroundItem), `WorldPoint approachTile`, `int regionId`, `int seenCount`, `long lastSeenAtMs`, `long observedDurationMs`. Immutable except for `seenCount` / `lastSeenAtMs` (use a builder + `withResolution(...)`).
- [ ] **Step 2.1.3:** Create `TransportIndex.java`. In-memory map keyed by `(fromTile, verb, objectId)` → `TransportEdge`. Methods: `add(TransportEdge)`, `getAll()`, `getOutgoing(WorldPoint from)`. Thread-safe (uses `ConcurrentHashMap`).
- [ ] **Step 2.1.4:** Compile + write minimal smoke test (`TransportIndexTest`): adding two edges with same key bumps `seenCount` instead of duplicating.
- [ ] **Step 2.1.5:** Commit: `feat(worldmap): TransportEdge + TransportIndex types`.

### Task 2.2 — TransportObserver event listener (pending phase)

- [ ] **Step 2.2.1:** Read `trail/TrailRecorder.java` to understand its `@Subscribe` for `MenuOptionClicked` — same pattern, parallel sink.
- [ ] **Step 2.2.2:** Identify the whitelist of transport verbs (TrailRecorder already has one; copy or share via a `TransportVerbs` constant set). Verbs include: `Open`, `Close`, `Climb-up`, `Climb-down`, `Cross`, `Jump`, `Enter`, `Exit`, `Pay-toll`, `Use` (for ladders), and anything TrailRecorder already whitelists.
- [ ] **Step 2.2.3:** Write `TransportObserverTest.java` first. Tests for the pending phase:
  - `onMenuOptionClicked_whitelistedVerb_storesPending`: simulate click; verify `pending` map has an entry keyed by the clicked target.
  - `onMenuOptionClicked_nonWhitelistedVerb_ignored`: e.g. "Attack" should not store.
  - `onMenuOptionClicked_clientNotLoggedIn_ignored`.
- [ ] **Step 2.2.4:** Run. Expected: FAIL.
- [ ] **Step 2.2.5:** Implement `TransportObserver.java`. `@Subscribe MenuOptionClicked` — checks verb whitelist, captures `fromTile = client.getLocalPlayer().getWorldLocation()` on the client thread (it's already invoked there per RuneLite event semantics), stores into a `pending` map keyed by clicked-target id + clicked tile + tick number. Bounded TTL on pending entries.
- [ ] **Step 2.2.6:** Run pending-phase tests. Expected: PASS.
- [ ] **Step 2.2.7:** Commit: `feat(worldmap): TransportObserver pending-phase capture`.

### Task 2.3 — TransportObserver resolution phase

- [ ] **Step 2.3.1:** Add tests to `TransportObserverTest.java`:
  - `onTick_playerMovedAfterPending_resolvesEdge`: simulate `GameTick`s after a pending entry, with player position changing (or plane changing) within timeout. Verify edge published to `TransportIndex` with correct `toTile`, plane delta, observed duration.
  - `onTick_pendingTimedOut_dropsAndLogs`: pending older than `MAX_RESOLUTION_TICKS` (e.g. 10 ticks) gets discarded; nothing persisted.
  - `onTick_planeChanged_resolves`: stairs case — player plane changes mid-resolution, edge captures plane delta correctly.
- [ ] **Step 2.3.2:** Run. Expected: FAIL.
- [ ] **Step 2.3.3:** Implement resolution: subscribe to `GameTick`. For each pending entry, check: did player position change? plane change? Did the verb's expected effect happen? If yes, build a complete `TransportEdge` and `TransportIndex.add(...)`. If timed out, discard + log.
- [ ] **Step 2.3.4:** Run. Expected: PASS.
- [ ] **Step 2.3.5:** Commit: `feat(worldmap): TransportObserver resolution-phase + bounded-timeout discard`.

### Task 2.4 — TransportIO (Gson read/write to transports.json)

- [ ] **Step 2.4.1:** Read `MapStoreIO.java` for the existing region JSON format conventions (Gson, schema-version field).
- [ ] **Step 2.4.2:** Write `TransportIOTest.java`. Tests: round-trip a transport graph (`writeAll(file, edges)` then `readAll(file)`) preserves all fields; reading a missing file returns empty (no exception).
- [ ] **Step 2.4.3:** Run. Expected: FAIL.
- [ ] **Step 2.4.4:** Implement `TransportIO.java`. Path: `<rootDir>/transports.json` where rootDir is the `worldmapRoot` from `RecorderPlugin.java:391`. Schema-versioned (v1).
- [ ] **Step 2.4.5:** Run. Expected: PASS.
- [ ] **Step 2.4.6:** Commit: `feat(worldmap): TransportIO read/write transports.json`.

### Task 2.5 — Wire TransportObserver into RecorderPlugin lifecycle + flush

- [ ] **Step 2.5.1:** Read `RecorderPlugin.java` near `flushDaemon = new FlushDaemon(...)` (line 421) to understand the flush cadence pattern.
- [ ] **Step 2.5.2:** In `RecorderPlugin.startUp`: instantiate `TransportObserver`, register on `eventBus`. Add a periodic flush in the existing `FlushDaemon` (extend its constructor to also persist `TransportIndex` via `TransportIO.writeAll`) OR a small parallel flush — implementation determines, but must NOT spin up a second daemon thread.
- [ ] **Step 2.5.3:** In `RecorderPlugin.shutDown`: deregister observer, final-flush the index.
- [ ] **Step 2.5.4:** Compile: `:client:compileJava`. Expected: clean.
- [ ] **Step 2.5.5:** Manual smoke: launch RuneLite, click a stair / climb-down with V1 (TrailWalker active), confirm `~/.runelite/recorder/worldmap/transports.json` appears with at least one edge (climb-down, with non-null toTile and plane delta).
- [ ] **Step 2.5.6:** Commit: `feat(worldmap): wire TransportObserver into plugin lifecycle + flush`.

### Phase 2 exit gate

- [ ] Compile clean.
- [ ] All Phase-2 tests pass.
- [ ] Manual smoke confirmed: at least one transport edge persisted to disk.
- [ ] Dispatch QC subagent.

---

## Phase 3 — Inspection tooling (panel buttons + minimap overlay)

**Goal:** Make captured world memory visible. Panel dump buttons + toggleable minimap overlay so the seed pass in Phase 6 has something to validate against. Per spec section "V2 inspection tooling" (lines 444+).

**Files:** New `InspectionDumper.java`, `WorldMapMinimapOverlay.java` + tests; modify `RecorderPanel.java` to add buttons, `RecorderConfig.java` for overlay toggles, `RecorderPlugin.java` to register the overlay.

### Task 3.1 — InspectionDumper service

- [ ] **Step 3.1.1:** Spec re-read: section "Panel buttons" + the JSON shapes given (lines 451-545).
- [ ] **Step 3.1.2:** Write `InspectionDumperTest.java`. Tests:
  - `dumpCurrentRegion_writesSummaryAndArrays`: dump produces a JSON object with the fields listed in spec lines 470-485 (regionId, planes, summary, tiles, objects, npcs, transportEdges).
  - `dumpTransportGraph_writesAllEdges`.
  - `dumpEntities_writesPerRegionEntities`.
  - `planAToB_withCachedPlanner_writesRoutePlusRejectionsBlock`. (Round-1 stub: planner is the existing `MapPlanner` in single-region mode if from/to are in the same region; cross-region returns a "not yet implemented" result. Phase 4 widens this.)
- [ ] **Step 3.1.3:** Run. Expected: FAIL.
- [ ] **Step 3.1.4:** Implement `InspectionDumper.java`. Dependencies: `MapStore`, `EntityIndex`, `TransportIndex`, optional `MapPlanner` for the Plan A→B button. Output dir: `<RUNELITE_DIR>/recorder/inspect/`. File naming: `region-<id>-<timestamp>.json`, `transports-<timestamp>.json`, `entities-<timestamp>.json`, `plan-<from>-to-<to>-<timestamp>.json`.
- [ ] **Step 3.1.5:** Run. Expected: PASS.
- [ ] **Step 3.1.6:** Commit: `feat(worldmap): InspectionDumper writes region/transport/entity/plan dumps`.

### Task 3.2 — Panel buttons

- [ ] **Step 3.2.1:** Read `RecorderPanel.java` for the existing button-section layout pattern.
- [ ] **Step 3.2.2:** Add a collapsible "V2 Inspection" section. Buttons: `Dump current region`, `Dump nearby regions`, `Dump transport graph`, `Dump entity sightings`, `Plan A→B...` (opens a small dialog asking for from/to coords), `Clear debug overlay route`.
- [ ] **Step 3.2.3:** Each button delegates to `InspectionDumper`. UI thread → Swing button click → `Thread.start()` for the dump call (per `CLAUDE.md` threading guidance: don't block EDT).
- [ ] **Step 3.2.4:** Compile: `:client:compileJava`. Expected: clean.
- [ ] **Step 3.2.5:** Commit: `feat(panel): V2 Inspection section with dump buttons`.

### Task 3.3 — Minimap overlay (toggleable, off by default)

- [ ] **Step 3.3.1:** Read existing overlays in this codebase (e.g. `trail/TrailOverlay.java`) for the RuneLite `Overlay` extension pattern + render-position constants.
- [ ] **Step 3.3.2:** Add to `RecorderConfig`: boolean `showWorldMapOverlay()` (default false), and child toggles `overlayShowBlocked`, `overlayShowStale`, `overlayShowEntities` (all default false).
- [ ] **Step 3.3.3:** Implement `WorldMapMinimapOverlay.java`. Uses `Perspective.localToMinimap(...)` for tile→minimap mapping. Default colors per spec lines 553-555: green walkable, yellow transport endpoints, blue active route.
  - Performance constraints (spec lines 573-581): cap drawn points per frame (e.g. 256), recompute the visible-tile geometry on `GameTick` or region change, NOT in `render(...)`. `render()` only iterates a cached list and draws.
- [ ] **Step 3.3.4:** Register the overlay in `RecorderPlugin.startUp()` via `OverlayManager.add(...)`; deregister in `shutDown()`.
- [ ] **Step 3.3.5:** Smoke (manual): toggle the overlay on, confirm green tiles appear along recently-walked tiles, yellow on transport endpoints from Phase 2 captures.
- [ ] **Step 3.3.6:** Commit: `feat(worldmap): minimap debug overlay (off by default)`.

### Phase 3 exit gate

- [ ] Compile clean.
- [ ] All Phase-3 tests pass.
- [ ] Manual smoke: dump buttons produce well-formed JSON; overlay renders without dropping framerate.
- [ ] Dispatch QC subagent.

---

## Phase 4 — V2 planner (extended MapPlanner: cross-region/plane + transports + top-K + noise + recent-route memory)

**Goal:** A planner that can produce route plans across regions and planes via transport edges, with top-K route alternation + noisy A* fallback. Per spec section "V2 route alternation" (lines 235-336).

**Files:** New `nav/v2/V2Planner.java`, `V2Path.java`, `V2Leg.java`, `MultiRegionAStar.java`, `TopKRouter.java`, `RouteHistory.java` + tests. Modify `worldmap/MapPlanner.java` to lift the cross-region + cross-plane rejects (lines 32-50 per spec line 130-133). MapPlanner becomes a single-region helper that the new MultiRegionAStar composes.

### Task 4.1 — Lift MapPlanner's cross-region + cross-plane rejects

- [ ] **Step 4.1.1:** Read `worldmap/MapPlanner.java` lines 32-50 (the rejects).
- [ ] **Step 4.1.2:** Read `worldmap/MapPlannerTest.java` to understand existing test fixtures.
- [ ] **Step 4.1.3:** Decide: does MapPlanner stay single-region as a *helper*, or does it grow multi-region itself? **Decision per spec architecture:** keep MapPlanner single-region (it already is, and the test fixtures depend on it). New multi-region work goes in `MultiRegionAStar`. So in this task we DON'T lift the rejects from MapPlanner — we leave MapPlanner as-is and build `MultiRegionAStar` separately.
- [ ] **Step 4.1.4:** No code change in this task. **Document in the task notes:** the spec line "Known blockers in MapPlanner.java that must be lifted" is satisfied by routing around it via `MultiRegionAStar`, NOT by editing MapPlanner. Update the spec section "Known blockers" at the start of Phase 4 to reflect this resolution.
- [ ] **Step 4.1.5:** Edit spec lines 141-145 to note: "Resolved in Phase 4 — V2's MultiRegionAStar composes single-region MapPlanner instances + transport edges; MapPlanner itself is unchanged."
- [ ] **Step 4.1.6:** Commit: `docs(nav): note MapPlanner blockers resolved by MultiRegionAStar wrapper, no MapPlanner edit`.

### Task 4.2 — V2Path / V2Leg value types

- [ ] **Step 4.2.1:** Create `V2Leg.java`. A leg is either:
  - `WALK { regionId, List<WorldPoint> tiles }` — tile-by-tile walk in one region.
  - `TRANSPORT { TransportEdge edge }` — a single transport invocation.
- [ ] **Step 4.2.2:** Create `V2Path.java`. Holds `List<V2Leg> legs`, `int totalCost`, `String routeId` (stable hash of the leg sequence — used by `RouteHistory`).
- [ ] **Step 4.2.3:** Tiny round-trip test for routeId stability.
- [ ] **Step 4.2.4:** Commit: `feat(nav-v2): V2Leg + V2Path value types`.

### Task 4.3 — MultiRegionAStar

- [ ] **Step 4.3.1:** Write `MultiRegionAStarTest.java`. Tests use hand-authored fixtures (reuse the worldmap test fixture pattern):
  - `plan_sameRegion_samePlane_returnsTilesFromMapPlanner`: smoke — falls through to MapPlanner-equivalent walk.
  - `plan_acrossRegions_viaWalkOnly_stitches`: two regions, no transports, A* over the union of their walkable tiles produces a path that crosses the boundary.
  - `plan_acrossPlanes_viaTransportEdge_inserts_TRANSPORT_leg`: from plane=2 to plane=0, only path is a known stair edge → output has WALK + TRANSPORT + WALK legs.
  - `plan_destinationUnreachable_returnsEmpty`.
- [ ] **Step 4.3.2:** Run. Expected: FAIL.
- [ ] **Step 4.3.3:** Implement `MultiRegionAStar.java`. Graph:
  - Nodes: every walkable tile in any loaded region snapshot, plus the `fromTile` and `toTile` of every TransportEdge.
  - Edges: tile→tile within a region (cost 1, from collision flags); transport edges (cost 1 + small constant); region-boundary tile→tile (cost 1, from the union of two region snapshots).
  - A* heuristic: Chebyshev distance, weighted-zero across plane changes (so cross-plane goals are reachable via transports).
  - The collision flags use the existing `RegionChunkSnapshot` accessors — no new format.
- [ ] **Step 4.3.4:** Run. Expected: PASS.
- [ ] **Step 4.3.5:** Commit: `feat(nav-v2): MultiRegionAStar with transport edges`.

### Task 4.4 — RouteHistory (recent-route penalty)

- [ ] **Step 4.4.1:** Write `RouteHistoryTest.java`. Tests:
  - `recordAndPenalty_sameRouteOnce_mildPenalty`.
  - `recordAndPenalty_sameRouteThreeTimes_strongPenalty`.
  - `recordAndPenalty_differentRoute_zeroPenalty`.
  - History bounded to last 5 entries.
- [ ] **Step 4.4.2:** Implement `RouteHistory.java`. Bounded ring of `(routeId, timestamp)`. `penaltyFor(routeId)` returns a multiplier ∈ [1.0, 2.5].
- [ ] **Step 4.4.3:** Commit: `feat(nav-v2): RouteHistory recent-route penalty`.

### Task 4.5 — TopKRouter (repeated-A* with edge-reuse penalty)

- [ ] **Step 4.5.1:** Write `TopKRouterTest.java`. Tests using fixtures with multiple known viable routes:
  - `topK_returnsUpToKDistinctRoutes`.
  - `topK_rejectsRoutesAboveCostThreshold`: routes with cost > 1.75× cheapest are dropped per spec line 331.
  - `topK_returnsCheapestOnlyWhenNoAlternatesExist`.
  - `pickWeighted_prefers_lowerCost_butNotAlways`: run 100 iterations, verify both top-1 and top-2 get picked some of the time.
- [ ] **Step 4.5.2:** Run. Expected: FAIL.
- [ ] **Step 4.5.3:** Implement `TopKRouter.java`. Algorithm per spec lines 317-336: run A*, penalize used edges 2-4×, repeat. Constants from spec: K=3, maxAttempts=6, edgeReusePenalty range, rejectIfCost > 1.75× cheapest. Weighted-random pick uses costs (cheaper → higher weight) AND the `RouteHistory` penalty.
- [ ] **Step 4.5.4:** Run. Expected: PASS.
- [ ] **Step 4.5.5:** Commit: `feat(nav-v2): TopKRouter (repeated A* + edge penalty + weighted pick)`.

### Task 4.6 — Noisy A* fallback

- [ ] **Step 4.6.1:** Add to `MultiRegionAStar`: an overload `plan(from, to, edgeCostNoise: Double)` that adds ±10-15% random noise to walk-tile edge costs (NOT to transports — per spec lines 296-315).
- [ ] **Step 4.6.2:** Test: `plan_withNoise_producesVariation`: run 50 times, verify path differs at least 30% of the time when alternates exist.
- [ ] **Step 4.6.3:** Commit: `feat(nav-v2): noisy A* mode (walk edges only)`.

### Task 4.7 — V2Planner orchestrator

- [ ] **Step 4.7.1:** Implement `V2Planner.java`. `plan(NavRequest req)` →
  - Resolve `from = playerWorldLocation()`.
  - If `mode == VARIED` (the only round-1 mode; others log warning + fall through):
    - Call TopKRouter, get routes.
    - If ≥2 routes: weighted-random pick (per spec selection algorithm lines 257-264).
    - Else: `MultiRegionAStar.plan(from, to, edgeCostNoise=0.12)`.
  - Record chosen route in `RouteHistory`.
- [ ] **Step 4.7.2:** Smoke test: in-process, with a fixture covering both alternates exist + alternates don't exist.
- [ ] **Step 4.7.3:** Commit: `feat(nav-v2): V2Planner orchestrator`.

### Task 4.8 — Wire planner into InspectionDumper Plan A→B button

- [ ] **Step 4.8.1:** Update `InspectionDumper.planAToB(...)` to call `V2Planner` and write a route + rejections JSON per spec lines 526-544.
- [ ] **Step 4.8.2:** Manual smoke: with seed data in place from Phase 2, hit "Plan A→B" with bank ↔ pen coords, verify dump shows a real route (or "no route" with rejection counts).
- [ ] **Step 4.8.3:** Commit: `feat(worldmap): InspectionDumper Plan A→B uses V2Planner`.

### Phase 4 exit gate

- [ ] Compile clean.
- [ ] All Phase-4 tests pass.
- [ ] Manual smoke: Plan A→B for bank↔pen produces a route through captured transports.
- [ ] Dispatch QC subagent.

---

## Phase 5 — V2 leg-executor (canvas + minimap modalities, empty-tile HARD rule, snapshot invalidation, catch-up clicks, run-toggle interface stub)

**Goal:** Turn a `V2Path` into actual world clicks under the spec's HARD constraints. Per spec sections "V2 leg-executor click behavior" (lines 243+) and "Snapshot invalidation" (lines 463+).

**Files:** New `V2Navigator.java`, `V2Executor.java`, `CanvasTilePicker.java`, `MinimapClicker.java`, `EmptyTileFilter.java`, `InvalidationClassifier.java` + tests.

### Task 5.1 — EmptyTileFilter (advisory pre-filter)

- [ ] **Step 5.1.1:** Spec re-read: HARD CONSTRAINT block (spec lines 286-343).
- [ ] **Step 5.1.2:** Write `EmptyTileFilterTest.java`. Tests with synthetic Scene fixtures:
  - `tileWithNoEntities_passes`.
  - `tileWithNpcStanding_rejected`.
  - `tileWithGroundItem_rejected`.
  - `tileWithGameObject_FirstVerbWalkHere_passes` (e.g. a fountain with first verb "Walk here" — rare but exists).
  - `tileWithDoor_FirstVerbOpen_rejected`.
- [ ] **Step 5.1.3:** Implement `EmptyTileFilter.isPlausiblyClean(WorldPoint, WorldView)`. Uses live `Scene` reads on the client thread (caller's responsibility to invoke on client thread per `CLAUDE.md` threading rules). Conservative — false negatives (rejecting clean tiles) are fine; false positives (accepting bad tiles) are not.
- [ ] **Step 5.1.4:** Commit: `feat(nav-v2): EmptyTileFilter advisory pre-filter`.

### Task 5.2 — CanvasTilePicker (weighted distance + jitter + filter)

- [ ] **Step 5.2.1:** Write `CanvasTilePickerTest.java`. Given a path + current player tile, the picker:
  - `picks_within_visiblePathRange`.
  - `pickedDistance_isWeighted_among_long_mid_short`: sample 200 picks, all distance buckets get hit.
  - `respects_EmptyTileFilter`: tiles failing the filter are excluded from the candidate pool.
  - `returnsNull_whenAllCandidatesFiltered`: signal to caller to switch to minimap modality.
- [ ] **Step 5.2.2:** Implement `CanvasTilePicker.pickNext(V2Path path, WorldPoint player, WorldView wv, EmptyTileFilter filter, Random rng)`.
- [ ] **Step 5.2.3:** Commit: `feat(nav-v2): CanvasTilePicker (weighted distance + filter)`.

### Task 5.3 — MinimapClicker (preconditions + clickable bounds)

- [ ] **Step 5.3.1:** Spec re-read: minimap preconditions (lines 318-330).
- [ ] **Step 5.3.2:** Implement `MinimapClicker.canClick()` checking:
  - Minimap visible (`Widget.isHidden()` chain — use `WidgetActions.isVisible(...)` per `CLAUDE.md`).
  - No blocking modal (`client.getMenu().isOpen() == false`, no chatbox prompt active).
  - Target inside `Perspective.localToMinimap(...)` non-null bounds AND not on the orb / compass border.
- [ ] **Step 5.3.3:** Implement `clickAt(WorldPoint target)` — uses `HumanizedInputDispatcher` via the existing `ActionRequest` path; on click, do not assume movement — caller validates progress on next tick.
- [ ] **Step 5.3.4:** Test contracts (no scene fixture; mock the client).
- [ ] **Step 5.3.5:** Commit: `feat(nav-v2): MinimapClicker with preconditions`.

### Task 5.4 — InvalidationClassifier (typed failure handling)

- [ ] **Step 5.4.1:** Spec re-read: snapshot invalidation typed-failure block (spec lines 489-509).
- [ ] **Step 5.4.2:** Write `InvalidationClassifierTest.java`. Tests:
  - `staticCollisionMismatch_marksTileDirty`: snapshot says walkable, live collision says blocked → returns `STATIC_COLLISION_MISMATCH`.
  - `dynamicBlocker_addsTransientPenalty_doesNotPersist`: NPC on tile but live collision is fine → `DYNAMIC_BLOCKER`.
  - `transportStateMismatch_marksEdgeStale`: expected door verb missing live → `TRANSPORT_STATE_MISMATCH`.
  - `unknownFailure_incrementsFailureCount`.
- [ ] **Step 5.4.3:** Implement `InvalidationClassifier.classify(failedClick, livePlayerLoc, snapshot, transportIndex)`. Returns a `FailureClass` enum + side-effects (mutate `MapStore` for static; add transient penalty to a session-local penalty map for dynamic; mark `TransportEdge` stale for transport).
- [ ] **Step 5.4.4:** Implement the failure-threshold rules from spec lines 410-419: 1× = retry different tile, 2× = blacklist for this route attempt, persisted-fail-across-sessions = mark dirty.
- [ ] **Step 5.4.5:** Commit: `feat(nav-v2): InvalidationClassifier with typed failure recovery`.

### Task 5.5 — V2Executor (modal mix + per-tick state machine)

- [ ] **Step 5.5.1:** Implement `V2Executor.java`. State machine:
  - Holds a `V2Path` + current leg index + tick counter since last click.
  - Each `tick()`:
    1. If progress stalled (player hasn't moved for N ticks): consult `InvalidationClassifier`, possibly emit a catch-up click (bounded count per leg per spec lines 271-276), or replan.
    2. Otherwise pick a modality: bias toward minimap if recent canvas filter rejection rate is high. WORLDMAP slot reserved but throws `UnsupportedOperationException("deferred to V2.5")` if selected — selection layer should never select it in round 1.
    3. Use `CanvasTilePicker` (with EmptyTileFilter) or `MinimapClicker` to choose a click target.
    4. Before press, re-verify via `HumanizedInputDispatcher.isLeftClickWalk(...)` for canvas; for minimap, run `MinimapClicker.canClick()`.
    5. Dispatch click; record outcome on next tick.
- [ ] **Step 5.5.2:** Run-toggle interface stub: `V2Executor.setRunMode(RunMode.UNCHANGED | ON | OFF)` — round-1 implementation only honors `UNCHANGED`. Other values log a warning and fall through to UNCHANGED. Per spec lines 277-284.
- [ ] **Step 5.5.3:** Test contracts (heavy use of mock Client/Scene; integration tests come in Phase 6 against fixtures).
- [ ] **Step 5.5.4:** Commit: `feat(nav-v2): V2Executor leg-by-leg state machine`.

### Task 5.6 — V2Navigator (Navigator interface impl)

- [ ] **Step 5.6.1:** Implement `V2Navigator.java`. Constructor takes `V2Planner`, `V2Executor`. `tick(NavRequest req)`:
  - First tick: call planner, store path in executor.
  - Subsequent ticks: delegate to executor.
  - Returns `NavStatus.RUNNING / ARRIVED / FAILED` based on executor state.
- [ ] **Step 5.6.2:** `name() = "worldmap-v2"`.
- [ ] **Step 5.6.3:** Commit: `feat(nav-v2): V2Navigator wires planner + executor behind Navigator interface`.

### Phase 5 exit gate

- [ ] Compile clean.
- [ ] All Phase-5 tests pass.
- [ ] Dispatch QC subagent.

---

## Phase 6 — Wire V2 to switch + run seed pass + acceptance tests

**Goal:** Register V2Navigator with the factory, run the seed pass, validate primary + secondary acceptance.

### Task 6.1 — Register V2Navigator in NavigatorFactory

- [ ] **Step 6.1.1:** Modify `NavigatorFactory.java`: in the constructor, also instantiate `V2Navigator` (with a `V2Planner` constructed from `MapStore`, `TransportIndex`, `EntityIndex`, etc.). Switch the `WORLDMAP_V2` branch from "throws not-yet-registered" to "returns the V2 instance".
- [ ] **Step 6.1.2:** Update `NavigatorFactoryTest.java`: replace the "throws" test with `getNavigator_whenWorldmapV2_returnsV2Navigator`.
- [ ] **Step 6.1.3:** Compile + tests.
- [ ] **Step 6.1.4:** Commit: `feat(nav): register V2Navigator in factory`.

### Task 6.2 — Seed pass (manual procedure documented in plan)

This is a manual procedure performed by the user. Document it precisely so it's reproducible.

- [ ] **Step 6.2.1:** Set `navigatorImpl = TRAIL_V1` (default). Enable the minimap overlay.
- [ ] **Step 6.2.2:** Run TrailWalker (V1) on each seed route, in this order:
  - `lumby-bank-to-pen` (north route — record this if a north-route trail does not yet exist)
  - `lumby-bank-to-pen` (south route — record alternate)
  - `pen-to-lumby-bank` (north + south)
  - `lumby-to-ge` (existing or record)
  - `lumby-to-draynor` (existing or record)
  - `draynor-to-lumby` (record if missing — needed for the secondary acceptance baseline; the secondary test will then re-walk it via V2 alone)
- [ ] **Step 6.2.3:** After each route, hit `Dump current region` + `Dump nearby regions` + `Dump transport graph`. Diff against the previous dump to confirm new tiles + transports were captured.
- [ ] **Step 6.2.4:** Visually confirm on the minimap overlay: green coverage along the corridor, yellow on stair / gate / door endpoints.
- [ ] **Step 6.2.5:** If any expected transport edge is missing from `transports.json`: investigate `TransportObserver` (the live listener missed it). Do NOT paper over with backup ingestion — fix the listener and re-walk the route.

### Task 6.3 — Primary acceptance test (bank ↔ pen alternation)

- [ ] **Step 6.3.1:** Set `navigatorImpl = WORLDMAP_V2`.
- [ ] **Step 6.3.2:** Start ChickenFarmV3. Run for at least 6 bank↔pen cycles.
- [ ] **Step 6.3.3:** Tail `~/.runelite/logs/client.log` and the InspectionDumper outputs. Pass criteria:
  - All 6 cycles complete (player reaches bank, deposits, returns to pen, attacks, repeats).
  - Across cycles, the route-selection layer demonstrably picks both north and south alternates (visible in V2Planner log lines + the route IDs recorded by `RouteHistory`).
  - The empty-tile filter never produces a stuck loop (no more than 5 consecutive failed canvas picks before falling back to minimap or replanning).
- [ ] **Step 6.3.4:** If failures: classify per `InvalidationClassifier`'s output (the log lines should make this obvious), fix root cause, re-run.

### Task 6.4 — Secondary acceptance test (Draynor → Lumby cross-region)

- [ ] **Step 6.4.1:** With V2 still active, manually walk the player to Draynor (or use the recorded `lumby-to-draynor` trail under V1, then switch to V2).
- [ ] **Step 6.4.2:** Issue a one-shot `NavRequest(to = lumby-bank-coords, mode = VARIED)` via a test panel button or a /command (round-1 expedient: add a small "Walk to (x,y,p) via V2" debug button to RecorderPanel — same shape as the existing Plan A→B button).
- [ ] **Step 6.4.3:** Pass criteria:
  - V2 produces a path from Draynor to Lumby using only the world model + transports captured during the seed pass.
  - Player completes the walk without falling back to V1.
  - At least one transport edge (the Draynor lodestone path's gate / road, or the Lumbridge entry road) is used.
- [ ] **Step 6.4.4:** If the planner returns no route: dump the planner's rejections (the spec's `rejections` block at lines 538-543), identify the missing region or transport, walk it once under V1, retry under V2.

### Task 6.5 — Final cleanup + docs

- [ ] **Step 6.5.1:** Update `runelite-client/.../recorder/CLAUDE.md` (or wherever the per-package docs live) with a one-paragraph "Navigator V2" section pointing at the spec + this plan.
- [ ] **Step 6.5.2:** Add a row to `MEMORY.md` summarizing what V2 actually shipped (project memory). Keep under 150 chars per the index format.
- [ ] **Step 6.5.3:** Final commit: `docs(nav): point package docs + memory at the V2 shipped feature`.

### Phase 6 exit gate

- [ ] Primary acceptance passed.
- [ ] Secondary acceptance passed.
- [ ] Final QC subagent on the cumulative diff against the spec.

---

## QC subagent prompts (one per phase)

After each phase commits, dispatch with:

```
subagent_type: superpowers:code-reviewer (or general-purpose if reviewer unavailable)
description: "QC review Phase N navigator-v2 impl"
prompt: |
  Review the implementation of Phase N of the Navigator V2 plan.

  Spec: docs/superpowers/specs/2026-05-07-navigator-interface-architecture.md
  Plan: docs/superpowers/plans/2026-05-07-navigator-v2-implementation.md
  Phase under review: N — <phase title>
  Diff: git diff <previous-phase-commit>..HEAD

  Check:
  1. Every "must" / "MUST" / HARD CONSTRAINT in the spec sections
     covered by this phase is implemented as specified. List any gaps.
  2. Every Phase-N task in the plan has a corresponding commit + test.
  3. Threading: any code that reads Widget/Scene/Tile/NPC/Menu/varbits
     runs on the client thread (per CLAUDE.md "threading model").
     Any multi-step blocking flow runs on a worker thread, NOT the
     client thread.
  4. No "humanization" / "look human" / "bot tell" framing in code
     comments or log messages (per feedback_no_evasion_framing.md).
  5. No new copyright headers on new files (per feedback_no_bsd_headers.md).
  6. Tests actually exercise the spec'd behavior (not vacuous —
     no "assert true" or asserts that pass on an empty-state stub).

  Output format:
  - PASS / CHANGES_REQUESTED.
  - For each finding: file:line + one-line description + severity (BLOCKER/MINOR).
  - Under 600 words.

  Do NOT re-review the spec itself — it's already approved.
```

---

## Self-review (run before handing the plan to the user)

**Spec coverage:**
- Architecture (V1/V2/interface/single switch) — Phase 1 ✓
- TrailWalker frozen — Phase 1 (no edits, only wrapped) ✓
- Single switch site — Phase 1 (RecorderConfig + factory) ✓
- "Foundations only" rejection — Phase 1 wires ChickenFarmV3 in same phase ✓
- Live-observation capture (TransportObserver, no JSON ingestion) — Phase 2 ✓
- Two-phase capture lifecycle (pending → resolution / discard) — Phase 2 Task 2.3 ✓
- Persistence layout (regions/, entities/, transports.json, inspect/) — Phase 2 + Phase 3 ✓
- regions/ vs entities/ source-of-truth split — honored by not duplicating NPCs into regions/ (TransportObserver and SceneScraper already separate) ✓
- Inspection: panel buttons + minimap overlay, no canvas overlay — Phase 3 ✓
- Top-K + edge-cost noise + recent-route memory — Phase 4 Tasks 4.4 + 4.5 + 4.6 + 4.7 ✓
- Planner constants (K=3, maxAttempts=6, edgeReusePenalty 2-4×, reject>1.75×) — Phase 4 Task 4.5 ✓
- Noise applies to walk edges only — Phase 4 Task 4.6 ✓
- VARIED is the only round-1 mode — Phase 4 Task 4.7 + Phase 5 Task 5.5 ✓
- Canvas + minimap modalities only; worldmap deferred — Phase 5 Tasks 5.2 + 5.3 ✓
- Empty-tile HARD constraint with `isLeftClickWalk` authoritative — Phase 5 Tasks 5.1 + 5.5 ✓
- Minimap preconditions — Phase 5 Task 5.3 ✓
- Run toggle interface stub only — Phase 5 Task 5.5 ✓
- Continuous scrape + reactive invalidation, typed classification, no TTL — Phase 5 Task 5.4 ✓
- Catch-up clicks bounded, on stalled progress — Phase 5 Task 5.5 ✓
- Seed pass with bank↔pen north + south required — Phase 6 Task 6.2 ✓
- Primary acceptance (bank↔pen alternation visible) — Phase 6 Task 6.3 ✓
- Secondary acceptance (Draynor → Lumby) — Phase 6 Task 6.4 ✓

**No placeholders found** in this plan body — every task has a concrete file + behavior + exit criteria. Where the spec said "implementation determines," the plan flags it explicitly (e.g. Task 2.5 transport-flush integration into FlushDaemon, Task 4.1 deciding NOT to edit MapPlanner).

**Type consistency:** `Navigator`, `NavRequest`, `NavStatus`, `BehaviorMode`, `V2Path`, `V2Leg`, `TransportEdge` are referenced consistently across phases.

**Length:** ~700 lines, within the 300-700 budget per `feedback_compact_plans.md`. No Java source paste-bombs; behavior described.
