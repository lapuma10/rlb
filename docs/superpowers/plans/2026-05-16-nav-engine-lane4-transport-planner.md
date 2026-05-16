# Lane 4 — Transport Graph + Waypoint Planner Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: `superpowers:executing-plans` if dispatched as a single agent.
> **Master plan**: `docs/superpowers/plans/2026-05-16-nav-engine-master.md`
> **Spec**: `docs/superpowers/specs/2026-05-16-observation-aware-navigation-engine-design.md`
> **Required reading**: spec §0 (Master Direction — drift gate), §3 (Locked Contracts), §4 Lane 4 entry, §6 (Acceptance Tests).
> **Depends on**: Lane 2 (`WorldSnapshot`, `PlayerState`, `CollisionView`), Lane 3 (`SkretzoBfsKernel`, `RouteValidator`). May begin with **stubs/mocks** of these and switch to real implementations as those lanes land.

**Goal**: Build the top-tier link-graph Dijkstra + bottom-tier BFS orchestrator that emits sparse waypoints + typed transport legs as `V2Path`. This is where the new planning model lives.

**Architecture**: Load Skretzo's transport TSVs at startup into a `TransportTable`. Build a directed link graph (transports + region adjacency). Run Dijkstra over the link graph for the high-level route skeleton; for each inter-link tile leg call `SkretzoBfsKernel`; compress the assembled tile sequence into sparse waypoints preserving exact anchors (transport approaches, plane changes, region bridges); validate the entire path via `RouteValidator`; emit `V2Path` as a `List<PathStep>` with explicit walk/transport interleaving.

**Tech**: Java 17, RuneLite-API (`WorldPoint`), Gradle. No direct `Client` dependency — Lane 4 operates on `WorldSnapshot` + `PlayerState` (Lane 2) + BFS (Lane 3).

---

## File structure

**Create (production):**
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v2/transport/TransportLink.java`
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v2/transport/TransportTable.java`
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v2/transport/TransportTableLoader.java` (TSV parsing)
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v2/transport/LinkGraphDijkstra.java`
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v2/transport/TransportRequirementEvaluator.java`
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v2/planner/NavigationContextImpl.java`
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v2/planner/PathContextImpl.java`
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v2/planner/WaypointPlanner.java`
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v2/planner/V2PathImpl.java` (concrete `V2Path` implementation; interface owned by Lane 5)
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v2/planner/PathCompressor.java`

**Create (resources):**
- `runelite-client/src/main/resources/runelite/nav/transports/*.tsv` (vendored from Skretzo; one file per category — agility, fairy rings, spirit trees, doors, stairs, etc.)
- `runelite-client/src/main/resources/runelite/nav/transports/MANIFEST.md` (version + sources)

**Tests:**
- `transport/TransportTableLoaderTest.java`
- `transport/TransportTableTest.java`
- `transport/LinkGraphDijkstraTest.java`
- `transport/TransportRequirementEvaluatorTest.java`
- `planner/PathContextImplTest.java`
- `planner/WaypointPlannerTest.java`
- `planner/PathCompressorTest.java`

---

## Task 1 — Vendor Skretzo's transport TSVs

**Files**: `resources/runelite/nav/transports/*.tsv`, `MANIFEST.md`. Update repo-root `NOTICES.md`.

Behavior: Pull Skretzo's transport TSVs from their release. Place under the resource path. Write `MANIFEST.md` with: source URL, release tag, per-file SHA256, license note. Append to `NOTICES.md` (or extend the Lane 2 stanza).

Commit: `feat(nav-engine,lane4): vendor Skretzo transport TSVs`

---

## Task 2 — TransportLink + TransportTableLoader

**Files**: `transport/TransportLink.java`, `transport/TransportTableLoader.java`, `TransportTableLoaderTest.java`.

`TransportLink` shape:
```java
record TransportLink(
  WorldPoint from, WorldPoint to,
  TransportType type,
  Optional<Integer> objectId,
  Optional<String> action,
  TransportRequirement requirement,
  boolean bidirectional,
  String sourceFile, int sourceLine    // for loud failure reporting
)
```

`TransportTableLoader.load(InputStream tsv) → List<TransportLink>`:
- Parse Skretzo TSV format (adapt to whatever format the actual data uses — inspect first).
- Per-row: build a `TransportLink`. Skill/quest/item requirements are parsed into `TransportRequirement` instances (see Task 4).
- **Loud failure**: any row that fails to parse is logged at ERROR with file:line + reason, then dropped. NEVER silently dropped.
- Count of accepted + dropped reported via `LoadStats`.

Tests:
- `load_validTsv_returnsExpectedLinks`.
- `load_malformedRow_reportsFile_line_andContinues`.
- `load_requirementsParseCorrectly` — sample rows with skill/quest/item gates.
- `load_bidirectionalFlag_respected`.

Run: `./gradlew :client:test --tests "*TransportTableLoaderTest"`

Commit: `feat(nav-engine,lane4): TransportTableLoader parses Skretzo TSVs`

---

## Task 3 — TransportTable

**Files**: `transport/TransportTable.java`, `TransportTableTest.java`.

Behavior: At construction, runs `TransportTableLoader.load(...)` on every TSV under `resources/runelite/nav/transports/`. Holds in-memory:
- `Map<WorldPoint, List<TransportLink>> linksFrom` — for graph lookup.
- An in-memory `delta` layer for `TransportObserver`-captured runtime links (Lane 4 owns the delta; existing `TransportObserver` only appends).
- Startup log at INFO per spec §4 Lane 4: `Loaded transports: X / Invalid: Y / one-way: A / two-way: B / plane-changing: C / requirement-gated: D`.

Methods:
- `linksFrom(WorldPoint p) → List<TransportLink>` — query.
- `appendLiveLink(TransportLink link)` — runtime delta from observer.
- `replace(TransportLink old, TransportLink corrected)` — **explicitly disallowed for callers outside InvalidationClassifier**. Document this in the javadoc. Lane 5's executor MUST NOT call this directly per spec.

Tests:
- `startup_loadsAllTsvFiles_logsStats`.
- `linksFrom_returnsExpectedLinks`.
- `appendLiveLink_appearsInLinksFrom`.
- `replace_invokedFromExecutor_throwsOrLogsForbidden` — anti-mutation guard. **Specifically tests that direct executor → `TransportTable.replace(...)` is forbidden. `V2Navigator` IS permitted to call `replace(...)` when given a `TransportCorrectionRequest` (Lane 5 type); test both paths.**

Run: `./gradlew :client:test --tests "*TransportTableTest"`

Commit: `feat(nav-engine,lane4): TransportTable with delta layer`

---

## Task 4 — TransportRequirementEvaluator

**Files**: `transport/TransportRequirementEvaluator.java`, `TransportRequirementEvaluatorTest.java`.

Behavior: Concrete `TransportRequirement` builders + `satisfiedBy(NavigationContext)`:
- `requireSkill(Skill s, int level)` — uses `ctx.player().skillLevel(s) >= level` or `boostedLevel` per spec convention (default real; opt-in boosted).
- `requireVarbit(int id, int value)` — uses `ctx.player().varbit(id) == value` or comparator.
- `requireItem(int itemId, int quantity)` — checks `ctx.player().inventory()`.
- `requireEquipped(int itemId)` — checks `ctx.player().equipment()`.
- `requireMember()` — `ctx.player().isMember()`.
- `requireAnd(req1, req2, ...)` — composer.
- `requireOr(req1, req2, ...)` — composer.

Tests (one per builder + composers):
- `requireSkill_levelMet_passes`, `..._levelNotMet_fails`.
- `requireVarbit_exactValue_passes`.
- `requireItem_inventoryHasIt_passes`.
- `requireAnd_allMet_passes`, `requireAnd_oneFail_fails`.
- `requireOr_oneMet_passes`.

Run: `./gradlew :client:test --tests "*TransportRequirementEvaluatorTest"`

Commit: `feat(nav-engine,lane4): TransportRequirementEvaluator composable gates`

---

## Task 5 — LinkGraphDijkstra

**Files**: `transport/LinkGraphDijkstra.java`, `LinkGraphDijkstraTest.java`.

Behavior: `findRouteSkeleton(NavigationContext ctx, WorldPoint from, WorldPoint to) → SkeletonResult`. Runs Dijkstra over a virtual graph where:
- **Nodes** are tiles connected by links. Effectively a sparse graph keyed by transport endpoints (not every walkable tile; that's BFS's job).
- **Edges** are `TransportLink` instances (with their tick cost) PLUS implicit "walk to next transport" edges. Walk-cost between two transport endpoints uses chebyshev distance as a heuristic (admissible; Dijkstra remains optimal).
- **Filter** out `TransportLink`s whose `requirement.satisfiedBy(ctx)` returns false.

`SkeletonResult` = `{Status, List<SkeletonNode>}` where each `SkeletonNode` is either a "walk to tile X" anchor or a `TransportLink` to traverse.

Tests:
- `findRouteSkeleton_simpleAtoBviaTransport_picksTransport`.
- `findRouteSkeleton_requirementUnsatisfied_excludesLink`.
- `findRouteSkeleton_unreachable_returnsTypedFailure`.
- `findRouteSkeleton_oneWayLink_doesNotAllowReverse`.
- `findRouteSkeleton_planeChangingLink_changesPlaneCorrectly`.

Run: `./gradlew :client:test --tests "*LinkGraphDijkstraTest"`

Commit: `feat(nav-engine,lane4): LinkGraphDijkstra over transport graph`

---

## Task 6 — NavigationContextImpl + PathContextImpl

**Files**: `planner/NavigationContextImpl.java`, `planner/PathContextImpl.java`, `PathContextImplTest.java`.

Behavior: Concrete implementations of the §3 interfaces. Records with builders. `NavigationContextImpl` composes `WorldSnapshot` (from Lane 2) + `PlayerState` (from Lane 2) + `NavRequest`. `PathContextImpl` adds plan-in-progress state.

Tests:
- `pathContext_navigationAccessible`.
- `pathContext_currentWaypoint_setOnPlanProgress`.
- `pathContext_routeSeed_derivedFromBfsConfig`.

Run: `./gradlew :client:test --tests "*PathContextImplTest"`

Commit: `feat(nav-engine,lane4): NavigationContextImpl + PathContextImpl`

---

## Task 7 — WaypointPlanner

**Files**: `planner/WaypointPlanner.java`, `planner/V2PathImpl.java`, `WaypointPlannerTest.java`.

Behavior: The orchestrator. **`plan(NavigationRequest req, WorldSnapshot snap, BfsConfig cfg) → V2Path`** (per spec §3 — `PlayerState` is reached via `NavigationContext` built from `WorldSnapshot`, NOT passed as a separate parameter). `V2PathImpl` is the concrete `V2Path` implementation Lane 4 ships (interface lives in flat `nav/v2/V2Path.java`, owned by Lane 5; impl lives here because Lane 4 constructs instances).

Algorithm:
1. Build `NavigationContext` from inputs.
2. `LinkGraphDijkstra.findRouteSkeleton(ctx, req.from, req.to)`.
3. For each skeleton node pair:
   - If walk: `SkretzoBfsKernel.plan(snap.collisionView(), prev, next, cfg, predicates)` → tile sequence.
   - If transport: emit a `TransportStep` with the `TransportLink`.
4. Pass full tile sequence + transport interleaving through `PathCompressor` (Task 8).
5. Run `RouteValidator.validate(...)` (Lane 3) on the compressed path.
6. Emit `V2Path` with `List<PathStep>` (ordered, interleaved walk + transport).
7. If any step fails: return `V2Path.failed(ReplanReason)`.

Tests:
- `plan_sameRegion_emitsWalkStepsOnly`.
- `plan_crossRegion_emitsWalkAndTransportStepsInOrder`.
- `plan_transportApproach_emitsExactToleranceWaypoint`.
- `plan_normalWalking_emitsLooseToleranceWaypoint`.
- `plan_requirementUnsatisfied_routesAroundOrFails`.
- `plan_unreachableTarget_returnsTypedFailure`.

Run: `./gradlew :client:test --tests "*WaypointPlannerTest"`

Commit: `feat(nav-engine,lane4): WaypointPlanner orchestrator`

---

## Task 8 — PathCompressor

**Files**: `planner/PathCompressor.java`, `PathCompressorTest.java`.

Behavior: `compress(List<TileSequence> tileLegs, List<TransportLink> transports) → List<PathStep>`. Compression rules per spec §4 Lane 4:
- Long straight runs collapse into a single `WalkStep` with loose tolerance (radius 2-3).
- **Preserve exact tiles** for: `TRANSPORT_APPROACH`, plane-change anchors, `REGION_BRIDGE`, predicate-edge anchors. Tolerance = 0 (exact).
- Direction changes (turn corners) become anchor waypoints with mid tolerance (radius 1-2).
- Output is interleaved with `TransportStep`s in original order.

Tests:
- `compress_straightRun_emitsOneWalkStep`.
- `compress_transportApproach_preservedExact`.
- `compress_planeChange_preservedExact`.
- `compress_300TileSequence_emits5to15Waypoints`.
- `compress_invariants` — no exactRequired waypoint becomes loose; no plane-change skipped.

Run: `./gradlew :client:test --tests "*PathCompressorTest"`

Commit: `feat(nav-engine,lane4): PathCompressor sparse waypoint emission`

---

## Task 9 — Hand to Lane 6

**File**: `docs/superpowers/plans/lane4-manifest.md`.

Manifest:
- Production files + line counts.
- Test files + count passing.
- Sample planner debug output (link path + tile legs + compression decisions per spec §4 Lane 4 expected output).
- TransportTable startup stats from a real run.
- Known limitations (e.g. "agility shortcut TSV may need Skretzo's release N+1 if Jagex moved a course").

Commit: `docs(nav-engine,lane4): manifest for Lane 6 QC`

---

## Self-test acceptance

Lane 4 is done when:

- [ ] All 9 production files exist and `./gradlew :client:compileJava` succeeds.
- [ ] All Lane 4 tests pass: `./gradlew :client:test --tests "*nav.v2.transport.*" --tests "*nav.v2.planner.*"`
- [ ] Spec §4 Lane 4's 8 QC tests pass (mapped across tasks 5 + 7 + 8).
- [ ] `TransportTable` startup log produces expected stats format.
- [ ] Manifest written.
- [ ] No edits to files in `nav/v2/collision/`, `nav/v2/bfs/`, `nav/v2/predicate/`, `nav/v2/executor/`, `nav/v2/qc/`.
- [ ] No edits to existing `nav/v2/V2*.java` files (those are Lane 5's).
- [ ] No edits to spec §3.

Hand-off: announce "Lane 4 hand-off complete. See `lane4-manifest.md`."
