# Lane 2 — Collision + Predicates Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: `superpowers:executing-plans` if dispatched as a single agent; otherwise work tasks sequentially.
> **Master plan**: `docs/superpowers/plans/2026-05-16-nav-engine-master.md`
> **Spec**: `docs/superpowers/specs/2026-05-16-observation-aware-navigation-engine-design.md`
> **Required reading before starting**: spec §0 (Master Direction — drift gate, verbatim), §3 (Locked Contracts), §4 Lane 2 entry, §6 (Acceptance Tests — Lane 6's bar).

**Goal**: Build the collision/world-snapshot layer and the runtime tile-predicate system that the planner and executor consult during plan and execution.

**Architecture**: Bundle Skretzo's global collision snapshot (BSD-2-Clause) as a resource. At plan entry, copy the loaded scene's live `client.getCollisionMaps()` over the top to produce an immutable `WorldSnapshot`. Build `PlayerState` from `client` at the same instant. Predicates are pure functions; the registry composes them; built-ins enforce "movement-blocking" semantics (per spec §4 Lane 2 SceneClean clarification), not "any-entity" semantics.

**Tech**: Java 17, RuneLite-API (`Client`, `WorldView`, `CollisionData`, `CollisionDataFlag`, `Skill`, `ItemContainer`, `WorldType`), Gradle.

---

## File structure

**Create (production):**
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v2/collision/GlobalCollisionSnapshot.java`
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v2/collision/LiveSceneCollisionOverlay.java`
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v2/collision/CollisionView.java`
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v2/collision/WorldSnapshotBuilder.java`
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v2/collision/PlayerStateBuilder.java`
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v2/predicate/PredicateRegistry.java`
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v2/predicate/BuiltInPredicates.java`

**Create (resources):**
- `runelite-client/src/main/resources/runelite/nav/collision/collision-map.zip` (vendored from Skretzo release)
- `runelite-client/src/main/resources/runelite/nav/collision/MANIFEST.md` (version + source URL + SHA256 + license)

**Modify:**
- `NOTICES.md` at repo root — add Skretzo attribution stanza.

**Tests** (mirror under `src/test/java/net/runelite/client/plugins/recorder/nav/v2/`):
- `collision/GlobalCollisionSnapshotTest.java`
- `collision/LiveSceneCollisionOverlayTest.java`
- `collision/CollisionViewTest.java`
- `collision/WorldSnapshotBuilderTest.java`
- `collision/PlayerStateBuilderTest.java`
- `predicate/PredicateRegistryTest.java`
- `predicate/BuiltInPredicatesTest.java`

---

## Task 1 — Vendor Skretzo's collision-map.zip

**Files**: `resources/runelite/nav/collision/collision-map.zip`, `MANIFEST.md`, root `NOTICES.md`.

Behavior: Download Skretzo's latest tagged `collision-map.zip` from `https://github.com/Skretzo/shortest-path/releases`. Place under the resources path. Write `MANIFEST.md` with: source URL, release tag, SHA256 of the zip, license note (BSD-2-Clause), date pulled. Add an attribution stanza to repo-root `NOTICES.md` referencing Skretzo's name + license + repo URL.

Compile check: `JAVA_HOME=$(dirname $JBIN)/.. ./gradlew :client:compileJava` (resource bundling integrity).

Commit: `feat(nav-engine,lane2): vendor Skretzo collision-map snapshot`

---

## Task 2 — GlobalCollisionSnapshot

**Files**: `collision/GlobalCollisionSnapshot.java`, `collision/GlobalCollisionSnapshotTest.java`.

Behavior: Load the bundled `collision-map.zip` at construction (consider classpath resource stream + `ZipInputStream`). Decompress into a per-plane `Map<RegionKey, int[][]>` keyed by region (or equivalent — the Skretzo format dictates; inspect the actual zip contents). Expose:
- `int flagsAt(WorldPoint p)` — returns collision flag bits; `BLOCK_MOVEMENT_FULL` if tile out of bounds.
- `boolean isLoaded(WorldPoint p)` — region present in the snapshot.
- `String mapVersion()` — read from `MANIFEST.md` at load time.

Tests:
- `loadFromResource_succeeds` — load yields non-empty region set; no exception.
- `flagsAt_knownTiles_returnsExpectedFlags` — 5 sample tiles with known flags (lumbridge_castle_courtyard walkable; varrock_west_wall blocked; draynor_bank_door walkable but flag set; falador_park flag set; edgeville_bank_entrance walkable). Treat sample values as test fixtures; adjust to match Skretzo data after inspection.
- `flagsAt_outOfBounds_returnsBlocked` — coordinates outside any loaded region → `BLOCK_MOVEMENT_FULL`.
- `mapVersion_matchesManifest` — `mapVersion()` equals what `MANIFEST.md` declares (CI-friendly version pin).

Run: `./gradlew :client:test --tests "*GlobalCollisionSnapshotTest"`

Commit: `feat(nav-engine,lane2): GlobalCollisionSnapshot from bundled data`

---

## Task 3 — LiveSceneCollisionOverlay

**Files**: `collision/LiveSceneCollisionOverlay.java`, `LiveSceneCollisionOverlayTest.java`.

Behavior: Snapshot of `client.getCollisionMaps()` (one `CollisionData[]` per plane) captured at construction. Copy the flag arrays (defensive copy — DO NOT hold the live reference). Methods:
- `int flagsAt(WorldPoint p)` — flags for tiles inside the loaded 104×104 scene.
- `boolean containsTile(WorldPoint p)` — scene-bound check.
- Plane handling: respect `p.getPlane()`; missing-plane returns "not contained."

Tests:
- `flagsAt_insideLoadedScene_returnsLiveFlags` — given a faked `CollisionData[]` with known flag arrays, returns them.
- `containsTile_outsideLoadedScene_returnsFalse` — bound check at scene edges.
- `immutable_afterCapture_externalChangesIgnored` — modify the source `CollisionData[]` after capture; overlay results unchanged.
- `planeMismatch_returnsNotContained` — request plane 2 when only plane 0 is loaded.

Run: `./gradlew :client:test --tests "*LiveSceneCollisionOverlayTest"`

Commit: `feat(nav-engine,lane2): LiveSceneCollisionOverlay captures loaded scene`

---

## Task 4 — CollisionView merge

**Files**: `collision/CollisionView.java`, `CollisionViewTest.java`. Also a tiny `CollisionFlags` value-type (or just `int` with documented bit semantics — pick the simpler one after inspecting how the kernel will consume it; document the choice).

Behavior: Composes `GlobalCollisionSnapshot` + `LiveSceneCollisionOverlay`. Methods:
- `int flagsAt(WorldPoint p)` — live overlay wins inside loaded scene; global snapshot is fallback; `BLOCK_MOVEMENT_FULL` if both miss.
- `Source source(WorldPoint p)` — enum `{LIVE_OVERLAY, GLOBAL_SNAPSHOT, OUT_OF_RANGE}` for debug.
- Sub-ms per call (no allocation in hot path).

Tests (mirror spec §4 Lane 2 QC tests):
- `flagsAt_sameTileInBoth_liveWins` — flags differ between sources → result equals live.
- `flagsAt_outsideLoadedScene_fallsBackToGlobal` — overlay says "not contained" → snapshot consulted.
- `flagsAt_planeMismatch_doesNotLeakCrossPlane` — request plane 1, only plane-1 sources consulted.
- `flagsAt_regionEdge_returnsConsistentResult` — at loaded/unloaded boundary, no coord corruption (canonicalize via `WorldPoint`).
- `flagsAt_immutability_repeatedCallsConsistent` — same `WorldPoint` queried twice within one CollisionView instance → same result, even if external state changes.
- `source_returnsCorrectSource` — for in-scene and out-of-scene tiles.

Run: `./gradlew :client:test --tests "*CollisionViewTest"`

Commit: `feat(nav-engine,lane2): CollisionView merges global + live overlay`

---

## Task 5 — WorldSnapshotBuilder + WorldSnapshot impl

**Files**: `collision/WorldSnapshotBuilder.java`, `collision/WorldSnapshotImpl.java`, `WorldSnapshotBuilderTest.java`.

Behavior: `build(Client client, ClientThread ct, TransportTable transports, PredicateRegistry preds) → WorldSnapshot`. Marshals to client thread via `ct.invokeLater(...)` if not already on it. Captures:
- `CollisionView` for this tick (new instance).
- `blockingActorTiles()` — `WorldView.npcs()` iterated; for each NPC, the tiles it occupies (`getWorldLocation()` ± size); filter to those whose size/orientation implies movement-blocking. **Conservative default**: NPCs with `getName()` matching a configurable whitelist OR NPCs with `BLOCK_MOVEMENT_FULL` orientation flags. Lane agents should leave this whitelist empty initially and grow it from acceptance failures.
- `blockingObjectTiles()` — `Tile.getGameObjects()` for tiles in scene; filter by collision flag.
- `transports()` and `predicates()` — store the params, return them via the interface.
- `capturedAtMs() = System.currentTimeMillis()` at entry.
- All sets `Collections.unmodifiableSet(...)`.

`WorldSnapshotImpl` is a final-fielded record-shaped class.

Tests:
- `build_capturesActorsAndObjects_atSameInstant` — actors moving mid-iteration produce a stable result.
- `build_returnsImmutable_externalActorMoveAfterBuildDoesNotChangeSnapshot`.
- `build_onWorkerThread_marshalsToClient` — call from worker; verify it didn't read off-thread (use a test-double `ClientThread` that asserts).
- `blockingActorTiles_emptyWhitelist_excludesAllNpcs` — default conservative behavior.

Run: `./gradlew :client:test --tests "*WorldSnapshotBuilderTest"`

Commit: `feat(nav-engine,lane2): WorldSnapshotBuilder captures immutable snapshot`

---

## Task 6 — PlayerStateBuilder + PlayerState impl

**Files**: `collision/PlayerStateBuilder.java`, `collision/PlayerStateImpl.java`, `PlayerStateBuilderTest.java`.

Behavior: `build(Client client, ClientThread ct) → PlayerState`. On client thread, captures:
- `skillLevel(Skill s) = client.getRealSkillLevels()[s.ordinal()]`
- `boostedLevel(Skill s) = client.getBoostedSkillLevels()[s.ordinal()]`
- `varbit(int id) = client.getVarbitValue(id)` — eager cache: capture the entire varbit table is unrealistic; instead, lazy on-demand with a per-call client-thread marshall (acceptable since requirements check is plan-time, not per-tick). Document the latency.
- `varplayer(int id) = client.getVarpValue(id)` — same.
- `inventory()` — wrap `client.getItemContainer(InventoryID.INVENTORY)` in an immutable view (copy items at capture time).
- `equipment()` — same with `InventoryID.EQUIPMENT`.
- `isMember()` — `client.getWorldType().contains(WorldType.MEMBERS)`.

Tests:
- `build_capturesSkills` — given a fake client with known levels, all 23 skills queried.
- `build_capturesVarbits_byId` — fake client, varbit 123 → expected value.
- `build_inventory_immutable` — mutate live container post-build; PlayerState.inventory() unchanged.
- `build_offClientThread_throws` — direct call from worker fails loudly (or marshals; pick one and test it).

Run: `./gradlew :client:test --tests "*PlayerStateBuilderTest"`

Commit: `feat(nav-engine,lane2): PlayerStateBuilder captures immutable player state`

---

## Task 7 — PredicateRegistry

**Files**: `predicate/PredicateRegistry.java`, `PredicateRegistryTest.java`.

Behavior: Holds an ordered list of named `TilePredicate`. Methods:
- `register(String name, TilePredicate pred)` — duplicate name throws.
- `unregister(String name)`.
- `boolean accepts(WorldPoint tile, PathContext ctx)` — all-must-accept; short-circuits on first reject.
- `Optional<String> firstRejectorOf(WorldPoint tile, PathContext ctx)` — debug aid; Lane 6 uses this in route traces.
- Predicates that throw are treated as REJECT and logged at WARN (conservative per spec).
- **`addTileCondition(WorldPoint tile, boolean allow)`** — script-friendly thin wrapper. Registers an auto-named predicate that returns `allow` for `tile`, accepts all others. Used by spec §6 Test 6 acceptance test. Name format: `script-tile-cond-<UUID>`. Returns the generated name so the script can `unregister(...)` it later.
- **`addTileCondition(WorldPoint tile, TilePredicate predicate)`** — overload: wraps a custom predicate scoped to a single tile.

Tests:
- `accepts_allTrue_returnsTrue`.
- `accepts_oneFalse_returnsFalse_andShortCircuits` — second predicate's `accept` is never called.
- `firstRejectorOf_returnsCorrectName`.
- `register_duplicateName_throws`.
- `predicate_throwsException_treatsAsRejectedAndLogs` — verify log line emitted at WARN.
- `addTileCondition_disallowedTile_rejectedByAccepts`.
- `addTileCondition_returnsGeneratedName_allowsUnregister`.
- `addTileCondition_overload_customPredicateScopedToTile`.

Run: `./gradlew :client:test --tests "*PredicateRegistryTest"`

Commit: `feat(nav-engine,lane2): PredicateRegistry composes predicates`

---

## Task 8 — BuiltInPredicates

**Files**: `predicate/BuiltInPredicates.java`, `BuiltInPredicatesTest.java`.

Behavior: Static factory methods producing the 8 built-ins per spec §4 Lane 2:

```java
public static TilePredicate notBlocked();
public static TilePredicate liveCollisionAllows();
public static TilePredicate sceneClean();        // MOVEMENT-BLOCKING ONLY
public static TilePredicate notOccupiedByBlockingActor();
public static TilePredicate notOccupiedByBlockingObject();
public static TilePredicate interactionModeWorld();
public static TilePredicate notDangerousArea();  // wilderness/PvP, default conservative
public static TilePredicate scriptAllowed();     // pass-through, always-true
```

Tests (one happy + one sad per predicate, plus the SceneClean clarification):
- `notBlocked_walkableTile_accepts`, `notBlocked_blockedTile_rejects`.
- `sceneClean_groundItemOnTile_accepts` — non-blocking entity present, predicate accepts (spec §4 clarification).
- `sceneClean_blockingNpcOnTile_rejects` — blocking actor on tile, predicate rejects.
- `interactionModeWorld_modeWorld_accepts`, `interactionModeWorld_modeBanking_rejects`.
- `notDangerousArea_wildernessTile_rejects` by default.
- `scriptAllowed_alwaysAccepts`.

Run: `./gradlew :client:test --tests "*BuiltInPredicatesTest"`

Commit: `feat(nav-engine,lane2): 8 built-in tile predicates`

---

## Task 9 — Hand to Lane 6

**File**: `docs/superpowers/plans/lane2-manifest.md`.

Write a manifest listing:
- Production files created (with line counts).
- Test files created + count passing.
- Sample debug output from `CollisionView.flagsAt(...)` showing the per-call format (source + plane + flags + 8 neighbors) — see spec §4 Lane 2 debug-output requirement.
- Known limitations (e.g. "actor blocking whitelist empty by default; Lane 6 to surface NPCs that should be added").
- Lane 4's stub usage notes — what Lane 4 can mock until Lane 2 lands fully.

Commit: `docs(nav-engine,lane2): manifest for Lane 6 QC`

---

## Self-test acceptance

Lane 2 is done when:

- [ ] All 7 production files exist and `./gradlew :client:compileJava` succeeds.
- [ ] All 7 test classes pass: `./gradlew :client:test --tests "*nav.v2.collision.*" --tests "*nav.v2.predicate.*"`
- [ ] Spec §4 Lane 2's 5 QC tests pass (mapped to tasks 4 + 5).
- [ ] Manifest file written.
- [ ] No files under `nav/v2/bfs/`, `nav/v2/transport/`, `nav/v2/planner/`, `nav/v2/executor/`, or `nav/v2/qc/` touched.
- [ ] No edits to existing `nav/v2/V2*.java` files (those are Lane 5's).
- [ ] No new entries to spec §3 (those are Lane 1's).

Hand-off: announce "Lane 2 hand-off complete. See `lane2-manifest.md`."
