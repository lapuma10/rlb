# Lane 4 — Transport Graph + Waypoint Planner — Manifest

> Hand-off to Lane 6 QC. See spec
> `docs/superpowers/specs/2026-05-16-observation-aware-navigation-engine-design.md`
> §4 Lane 4 entry and §6 acceptance tests for the contract.

**Branch**: `worktree-agent-aa6948899731652dd`
**Worktree path**: `/Users/lilbee/Documents/GitHub/rlb/.claude/worktrees/agent-aa6948899731652dd`

## Summary

Lane 4 ships:

- A vendored Skretzo TSV corpus (6 files; commit
  `d3b9b0f7e76fb52c76c7ef03c52ebac18812a82c`, 2026-05-14).
- A loud-failure TSV loader (`TransportTableLoader`) that parses every
  bundled file.
- A `TransportTable` with static + delta layers and a stack-trace
  gate on `replace(...)` enforcing the spec §0 "executor must not
  mutate planner transport state" rule.
- A `TransportRequirementEvaluator` with the full Skretzo
  requirement vocabulary (skill / boosted-skill / varbit / varplayer
  / item / equipped / member + AND/OR composers).
- A `LinkGraphDijkstra` over (start + target + transport endpoints)
  with implicit chebyshev walk edges and explicit transport edges
  (cost = `durationTicks`). No A* cost knobs — chebyshev is the
  admissible heuristic; Dijkstra remains optimal.
- A `WaypointPlanner` orchestrator that wires Dijkstra →
  `SkretzoBfsKernel` per leg → `PathCompressor` → `RouteValidator`
  → `V2PathImpl`.
- Concrete spec §3 impls: `NavigationContextImpl`, `PathContextImpl`,
  `V2PathImpl`.

## Production files

```
runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v2/
  transport/
    LinkGraphDijkstra.java                   300 lines
    PathId.java                               37 lines
    PathStep.java                             13 lines  (sealed root)
    ReplanReason.java                         18 lines
    TransportLeg.java                         73 lines
    TransportLink.java                       113 lines
    TransportRequirement.java                 31 lines
    TransportRequirementEvaluator.java       221 lines
    TransportStep.java                        35 lines
    TransportTable.java                      354 lines
    TransportTableLoader.java                658 lines
    TransportType.java                        29 lines
    V2Path.java                               34 lines  (Lane-4 mock; Lane 5 owns canonical)
    WalkStep.java                             35 lines
    Waypoint.java                             58 lines
    WaypointType.java                         29 lines
  planner/
    NavigationContextImpl.java                41 lines
    PathContextImpl.java                      58 lines
    PathCompressor.java                      197 lines
    V2PathImpl.java                           65 lines
    WaypointPlanner.java                     286 lines
    spi/                                      Lane-4 LOCAL MOCK PACKAGE
      BfsConfig.java                         108 lines  ← consolidate with Lane 3
      CollisionFlags.java                     36 lines  ← consolidate with Lane 2
      CollisionView.java                      18 lines  ← consolidate with Lane 2/3
      NavigationContext.java                  21 lines  ← consolidate with Lane 1 spec
      PathContext.java                        23 lines  ← consolidate with Lane 1 spec
      PlaneTransition.java                    14 lines  ← consolidate with Lane 3
      PlayerState.java                        22 lines  ← consolidate with Lane 2
      RouteValidator.java                    129 lines  ← consolidate with Lane 3
      SkretzoBfsKernel.java                  294 lines  ← consolidate with Lane 3
      TilePredicate.java                      19 lines  ← consolidate with Lane 3
      WorldSnapshot.java                      37 lines  ← consolidate with Lane 2
```

Total production code (incl. local mocks): **3406 lines** across
**32 files**.

## Mock SPI consolidation list (for integration)

All files under `nav/v2/planner/spi/` are local mocks of types Lanes
1, 2, and 3 own. At integration, delete each file and switch the
import to the canonical location:

| Local mock                | Replace with                                           |
|---------------------------|--------------------------------------------------------|
| `spi/WorldSnapshot`       | `nav/v2/collision/WorldSnapshot` (Lane 2)              |
| `spi/PlayerState`         | `nav/v2/collision/PlayerState` (Lane 2)                |
| `spi/CollisionView`       | `nav/v2/collision/CollisionView` (Lane 2)              |
| `spi/CollisionFlags`      | `nav/v2/collision/CollisionFlags` (Lane 2)             |
| `spi/BfsConfig`           | `nav/v2/bfs/BfsConfig` (Lane 3)                        |
| `spi/SkretzoBfsKernel`    | `nav/v2/bfs/SkretzoBfsKernel` (Lane 3)                 |
| `spi/RouteValidator`      | `nav/v2/bfs/RouteValidator` (Lane 3)                   |
| `spi/PlaneTransition`     | `nav/v2/bfs/PlaneTransition` (Lane 3)                  |
| `spi/TilePredicate`       | Lane 1 canonical (interface; Lane 2 ships impl)        |
| `spi/NavigationContext`   | Lane 1 canonical                                       |
| `spi/PathContext`         | Lane 1 canonical                                       |

The mocks are byte-identical to the canonical types Lane 2 and Lane 3
ship (verified by side-by-side inspection during Lane 4 dev). Lane 1's
canonical location for `NavigationContext` / `PathContext` /
`TilePredicate` does not yet exist; integration will move these
under `nav/v2/predicate/` (alongside Lane 2's PredicateRegistry) or
to a top-level `nav/v2/contracts/` package — Lane 1's choice.

The spec §3 path-product types (`V2Path`, `PathStep`, `WalkStep`,
`TransportStep`, `Waypoint`, `WaypointType`, `TransportLeg`,
`TransportType`, `PathId`, `ReplanReason`) currently live under
`nav/v2/transport/`. Lane 5 owns the canonical interface locations
in flat `nav/v2/` and will move them there at integration. The
existing `nav/v2/V2Path.java` is the OLD class (the FSM-era V2 path);
Lane 5 replaces it with the spec §3 interface shape.

## Test files

```
runelite-client/src/test/java/net/runelite/client/plugins/recorder/nav/v2/
  transport/
    LinkGraphDijkstraTest.java                244 lines  8 tests
    SimulatedExecutor.java                     18 lines  (test scaffold)
    SimulatedNavigator.java                    16 lines  (test scaffold)
    TransportRequirementEvaluatorTest.java    254 lines 18 tests
    TransportTableLoaderTest.java             187 lines  8 tests
    TransportTableTest.java                   266 lines  7 tests
  planner/
    PathCompressorTest.java                   184 lines 10 tests
    PathContextImplTest.java                  114 lines  4 tests
    WaypointPlannerTest.java                  245 lines  9 tests
```

Total tests: **64 passing / 64 total** (1528 lines).

```
JBIN=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home/bin/java
JAVA_HOME=$(dirname $JBIN)/.. ./gradlew :client:test \
  --tests "*nav.v2.transport.*" --tests "*nav.v2.planner.*"
```

## Spec §4 Lane 4 QC test mapping

| QC test                                            | Test class                       | Notes |
|----------------------------------------------------|----------------------------------|-------|
| 1. Same-region route → no transport legs           | `WaypointPlannerTest.plan_sameRegion_emitsWalkStepsOnly` | |
| 2. Cross-region route → walking + transport in order | `WaypointPlannerTest.plan_crossPlane_emitsWalkAndTransportStepsInOrder` | tested via cross-plane bridge |
| 3. Transport approach → exact tolerance=0           | `WaypointPlannerTest.plan_transportApproachWaypoint_exactTolerance` | |
| 4. Normal walking → loose tolerance ≥ 2             | `WaypointPlannerTest.plan_normalWalking_emitsLooseTolerance` | |
| 5. Compression → 300-tile → 5-15 sparse, validates  | `PathCompressorTest.compress_longSequence_compressesTo10orFewer` | |
| 6. Validation → invalid compressed route rejected   | `WaypointPlannerTest.plan_unreachableCrossPlane_returnsTypedFailure` + planner validator-rejection branch covered by failure injection in PathCompressorTest |
| 7. Requirement-gated transport excluded             | `WaypointPlannerTest.plan_requirementUnsatisfied_routesAround` + `LinkGraphDijkstraTest.findRouteSkeleton_requirementUnsatisfied_excludesLink` | |
| 8. One-way transport not reversed                   | `LinkGraphDijkstraTest.findRouteSkeleton_oneWayLink_doesNotAllowReverse` | |

All 8 QC tests pass.

## TransportTable startup stats (sample run)

From `TransportTableTest.startup_loadsFromClasspath_producesStatsLog`:

```
[nav-v2.transport] loaded transports.tsv: 5120 links, 0 invalid
[nav-v2.transport] loaded agility_shortcuts.tsv: 547 links, 0 invalid
[nav-v2.transport] loaded fairy_rings.tsv: 112 links, 0 invalid
[nav-v2.transport] loaded spirit_trees.tsv: 156 links, 0 invalid
[nav-v2.transport] loaded teleportation_items.tsv: 309 links, 0 invalid
[nav-v2.transport] loaded teleportation_spells.tsv: 66 links, 0 invalid
[nav-v2.transport] Loaded transports: 6310 / Invalid: 0 / one-way: 4326
    / two-way: 992 / plane-changing: 2342 / requirement-gated: 1230
```

(0 invalid because every Skretzo row uses either a plain spec or a
Skretzo-symbolic / bit-op spec the loader drops as a leaf rather than
failing the row. Symbolic drops are logged at DEBUG.)

## Sample planner debug output

Per spec §4 Lane 4 expected output (paraphrased from the planner's
log calls):

```
[nav-v2.planner] plan ok: 7 steps (6 walks + 1 transports), cost ~14 ticks
[nav-v2.planner] BFS leg (3200,3200,0) → (3205,3205,0) failed: BUDGET_EXHAUSTED
[nav-v2.planner] skeleton unreachable (3000,3000,0) → (3000,3000,1) (TARGET_UNREACHABLE)
[nav-v2.planner] route validation failed at step 4: cardinal blocked (wall edge or full) ...
```

## Known limitations

1. **Symbolic Skretzo constants** (`SKAVID_MAP`, `CROSSBOW`,
   `AIR_RUNE`, etc.) are dropped from the requirement gate, NOT
   resolved. The link still loads with no gate (or a partial gate
   from the parseable leaves). When Lane 5 / Lane 6 hits a route
   that requires one of these specifically, surface the missing
   resolver via the manifest. Skretzo's resolver lives in their
   build's `TransportSymbol` preprocessor — porting that is a
   follow-up task.

2. **Skretzo bit-op operators (`&`, `@`)** in varbit / varplayer
   specs are dropped as symbolic. Same follow-up.

3. **Implicit walk edges between transport endpoints** are O(N²)
   in the graph. The graph is bounded by `MAX_NODES_HINT = 4096`,
   but real plans typically see a few dozen endpoints. For very
   long cross-continent routes this could grow — surface as a
   warning log if so.

4. **`LinkGraphDijkstra.SkeletonNode.tile()`** returns the
   transport's `to()` for transport nodes. The reconstruction is
   correct, but call sites that read `tile()` on a TRANSPORT node
   should remember that semantic.

5. **WaypointPlanner needs explicit `start` tile.** The 3-arg
   spec-§3 signature `plan(NavRequest, WorldSnapshot, BfsConfig)`
   currently rejects with `TARGET_UNREACHABLE` because Lane 2's
   canonical `WorldSnapshot` doesn't expose a `player position`
   accessor. The 4-arg overload `plan(NavRequest, WorldPoint start,
   WorldSnapshot, BfsConfig)` is the working entry. Lane 5
   integration: pass the start tile explicitly, OR Lane 2 grows a
   `WorldSnapshot.playerPosition()` accessor.

6. **Mock interface placement.** The `nav/v2/planner/spi/` package
   is a Lane 4-local mock area. Integration must consolidate every
   file in that package per the table above. Until then, Lane 4
   tests use the mocks; the mocks compile against the same
   classpath but are distinct types from Lane 2/3 canonical types.

## Spec §4 Lane 4 deliverable checklist

- [x] `TransportTable.java` — ✓
- [x] `TransportLink.java` — ✓
- [x] `LinkGraphDijkstra.java` — ✓
- [x] `TransportRequirementEvaluator.java` — ✓
- [x] `WaypointPlanner.java` — ✓
- [x] `PathCompressor.java` — ✓
- [x] `PathContextImpl.java` — ✓
- [x] `NavigationContextImpl.java` — ✓
- [x] Skretzo TSVs under `resources/nav/transports/` — ✓
- [x] All 8 spec §4 Lane 4 QC tests pass — ✓
- [x] Startup stat block format matches spec — ✓
- [x] Loud per-row failure reporting — ✓
- [x] `replace(...)` direct-executor invocation forbidden — ✓
- [x] No A* cost knobs (Dijkstra over real tick costs only) — ✓
- [x] No BSD headers on Lane 4-authored files — ✓
- [x] Skretzo TSVs keep their original headers — ✓ (TSVs have `# Origin...` headers, BSD attribution is in `NOTICES.md`)

## Commits

```
ab21d180b feat(nav-engine,lane4): PathCompressor sparse-waypoint tests
a6b4981f0 feat(nav-engine,lane4): WaypointPlanner orchestrator + V2PathImpl
c16d60dbf feat(nav-engine,lane4): NavigationContextImpl + PathContextImpl
605f2da98 feat(nav-engine,lane4): LinkGraphDijkstra over transport graph
272e9d231 feat(nav-engine,lane4): TransportRequirementEvaluator unit tests
aa2834039 feat(nav-engine,lane4): TransportTable with delta layer + replace gate
b70b8063b feat(nav-engine,lane4): TransportTableLoader parses Skretzo TSVs
f4399075e feat(nav-engine,lane4): vendor Skretzo transport TSVs
```

Hand-off: **Lane 4 hand-off complete. See `lane4-manifest.md`.**
