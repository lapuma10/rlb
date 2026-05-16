# Observation-Aware Navigation Engine — Master Coordination Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` to dispatch lanes per the dispatch order below.

**Goal**: Coordinate 5 implementation lanes + 1 independent QC lane producing the new navigation engine per design spec `docs/superpowers/specs/2026-05-16-observation-aware-navigation-engine-design.md`.

**Architecture**: Mode B parallel-lanes (per `feedback_inline_impl_subagent_qc`). Lane 1 (contracts/spec) is complete and locked. Lanes 2–5 implement against locked contracts (§3 of spec) under strict file ownership (§4); Lane 6 QC blocks merge on the 9 acceptance tests in §6.

**Tech stack**: Java 17 (run with `-ea` + `--add-opens java.desktop/com.apple.eawt=ALL-UNNAMED`), RuneLite-API (`Client`, `Scene`, `WorldView`, `CollisionData`, `Player`, `NPC`, `WorldPoint`, `Skill`, `ItemContainer`), Gradle (compile `:client:compileJava`, test `:client:test`, shadow jar `:client:shadowJar`), JUnit, BSD-2-Clause fork of Skretzo's shortest-path plugin.

---

## Per-lane plans

| Lane | Plan file | Scope |
|------|-----------|-------|
| 1 | (spec §3) | Locked contracts — done at commit 674e5b744 |
| 2 | `2026-05-16-nav-engine-lane2-collision-predicates.md` | CollisionView, WorldSnapshotBuilder, PlayerStateBuilder, PredicateRegistry, BuiltInPredicates |
| 3 | `2026-05-16-nav-engine-lane3-bfs-validator.md` | SkretzoBfsKernel, BfsConfig, RouteValidator |
| 4 | `2026-05-16-nav-engine-lane4-transport-planner.md` | TransportTable, LinkGraphDijkstra, WaypointPlanner, PathCompressor, PathContextImpl, NavigationContextImpl |
| 5 | `2026-05-16-nav-engine-lane5-executor-integration.md` | V2Executor + V2ExecutorEnv + V2Navigator modifications, InvalidationClassifier fold, SidestepResolver new |
| 6 | `2026-05-16-nav-engine-lane6-qc-harness.md` | NavigationTestHarness, RouteTraceRecorder, RouteReplayValidator, OverlayTraceExporter, GoldenRouteFixtures, LiveAcceptanceChecklist, 9 acceptance tests |

---

## Dependency graph

```
Lane 2 (Collision + Predicates)         no production deps; starts immediately
Lane 3 (BFS + Validator)                no production deps; starts immediately
Lane 5 (Executor Integration)           no production deps; works against locked contracts; starts immediately
Lane 6 (QC + Harness)                   no production deps; builds harness against contracts; reads other lanes' outputs as they land
Lane 4 (Transport + Planner)            depends on Lane 2 (WorldSnapshot) + Lane 3 (SkretzoBfsKernel); can use stubs/mocks until they land
```

---

## Dispatch order

- **Phase 0 — SPEC LOCKED** (commit 674e5b744). Lanes 2–6 may now dispatch.
- **Phase 1 — parallel**: Lane 2, Lane 3, Lane 5, Lane 6 dispatch concurrently against the locked contracts. Each branch off `master` into a per-lane worktree.
- **Phase 2 — Lane 4**: dispatch once Lane 2 and Lane 3 have shipped at least stubs (interfaces + first test passing). Lane 4 may begin earlier with mocks if the lane agent prefers.
- **Phase 3 — acceptance gate**: Lane 6 runs the 9 acceptance tests against integrated lanes. Blocks merge to `master` until all 9 pass.
- **Phase 4 — default flip**: `enableWaypointPlanner` config flag flipped to ON; old V2 planner accessible via config only for emergency rollback.

---

## Cross-lane coordination

- **File ownership is strict** per spec §4. No edits across lane boundaries without Lane 1 approval. If a lane agent finds they need to edit another lane's file, they must STOP, surface the conflict, and request Lane 1 (spec) adjudication. **Never silently edit across lanes.**
- **Contracts (§3 of spec) are locked.** Any contract change requires Lane 1 sign-off and a spec edit committed first, then propagated to dependent lanes.
- Each lane plan ends with a **"Hand to Lane 6"** task producing a manifest at `docs/superpowers/plans/laneN-manifest.md`: artifacts list + test status + sample debug output + known limitations.
- Lane 6 reads all hand-off manifests and gates merge on the 9 acceptance tests.
- **The master direction text** in spec §0 is the drift gate. Every lane agent gets briefed with §0 verbatim at dispatch. Hard prohibitions are non-negotiable.

---

## Acceptance gate

Per spec §6, 9 tests must pass before the new engine becomes default:

1. Same-region walking (10/10 success)
2. Cross-region walking (5/5 routes generated, ≥1 live-validated)
3. Transport interaction (typed leg, typed failure on error)
4. Single-tile blocker → sidestep, not replan
5. Corridor blocked → typed replan with `NO_LOCAL_WALKABLE_TILE`
6. Predicate denies tile → BFS + executor both avoid it
7. Route compression — sparse waypoints, anchors preserved
8. **bank↔pen regression — 10 consecutive cycles, no manual intervention**
9. Trace quality — 5 runs, all validate, all complete, no exact replay

Lane 6 has blocking authority. All 9 must pass.

---

## Self-review

- [x] All 5 lane plans referenced; will be committed alongside this master plan.
- [x] Dependency graph covers every lane (Lane 4 is the only one with hard dependencies).
- [x] Dispatch order respects dependencies (Lane 4 last or stubbed).
- [x] Acceptance gate concrete — spec §6, 9 tests with pass criteria.
- [x] No placeholders; every lane points at a real plan file and specific scope.
- [x] Cross-lane coordination rule (file ownership) repeated for emphasis.

---

## Next step

After all 5 lane plans are committed alongside this master plan, invoke `superpowers:subagent-driven-development` to dispatch the lanes per Phase 1 + Phase 2 ordering above. Each dispatched agent receives:

1. Master direction text (spec §0) verbatim
2. Locked contracts (spec §3) verbatim
3. Their lane's entry in spec §4
4. Their lane plan
5. The 9 acceptance tests in spec §6 (so they know what Lane 6 will be checking)
