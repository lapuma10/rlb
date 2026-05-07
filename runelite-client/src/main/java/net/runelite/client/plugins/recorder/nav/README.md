# Navigator

Single abstraction over "get the player from A to B." Scripts depend
on the `Navigator` interface, never on a concrete walker class. A
single switch site (`NavigatorFactory`) builds a `HybridNavigator`
that routes each request to V1 (TrailWalker), V2 (WorldMemory), or
both, based on the live `RecorderConfig.navigatorMode()` setting.

## Modes (`RecorderConfig.NavigatorMode`)

| Mode | Behavior |
|---|---|
| `V1_ONLY` (default) | Always use V1 (`TrailNavigator` → `TrailWalker`). Frozen, known-good fallback. |
| `V2_WITH_V1_FALLBACK` | Try V2 first; on `FAILED`, log the reason and delegate the same request to V1 for the rest of the leg. Sticky per-request — once fallen back, V1 carries the request to completion. |
| `V2_STRICT` | V2 only. On `FAILED`, surface the reason cleanly and stop. |

## Request shapes (`NavRequest`)

| Factory | Used by | Behavior |
|---|---|---|
| `toPoint(WorldPoint, BehaviorMode)` | V2 | Plan to a concrete tile. V1 rejects (no trail name). |
| `byTrail(String, BehaviorMode)` | V1 | Replay a named trail. V2 rejects (no point or entity). |
| `compose(String, WorldPoint, BehaviorMode)` | V1 + V2 | Both forms — trail name for V1, target tile for V2. ChickenFarmV3's bank↔pen uses this. |
| `toEntity(String, EntityKind, BehaviorMode)` | V2 | Resolve to nearest known sighting via `EntityIndex`. V1 fails (no trail). |
| `toEntity(String, EntityKind, String action, BehaviorMode)` | V2 | Same with an advisory action verb (recorded to inspection logs). |

## V2 stack

```
V2Navigator
  ├── V2Planner               — deterministic A* OR top-K + noisy A*
  │     ├── MultiRegionAStar  (cross-region + transport-edge A*)
  │     ├── TopKRouter        (repeated A* with edge-reuse penalties)
  │     └── RouteHistory      (recent-route memory + penalty schedule)
  ├── V2Executor              — per-tick state machine
  │     ├── CanvasTilePicker  (weighted distance bucket pick)
  │     ├── EmptyTileFilter   (advisory pre-filter)
  │     ├── MinimapClicker    (modality alternate + preconditions)
  │     ├── InvalidationClassifier  (4-class typed failures + blacklist)
  │     └── Toggles           (per-sub-step disable flags)
  └── EntityIndex             — Phase-16 entity resolution
```

### Round-1 toggles (`RecorderConfig`)

| Flag | Default | Effect when off |
|---|---|---|
| `enableV2RouteVariation` | OFF | V2 returns the deterministic shortest path; no top-K, no noisy A*, no recent-route memory. Stable, easy to debug regressions. |
| `enableV2VariableDistance` | ON | `CanvasTilePicker` always picks from the short bucket (closest forward path tile). |
| `enableV2MinimapModality` | ON | `V2Executor` stays on canvas; canvas exhaustion FAILs the leg instead of falling back to minimap. |
| `enableV2CatchupClicks` | ON | `V2Executor` FAILs on the first stall classification instead of bounded re-clicks. |

### Failure tags

The "explicit reason on every FAILED transition" contract is enforced by three coordinated enums:

| Layer | Enum | Meaning |
|---|---|---|
| Per-tile recovery | `InvalidationClassifier.FailureClass` | `STATIC_COLLISION_MISMATCH` / `DYNAMIC_BLOCKER` / `TRANSPORT_STATE_MISMATCH` / `UNKNOWN_FAILURE`. The classifier mutates blacklist + transient-penalty state in response. |
| Executor terminal | `V2Executor.FailureReason` | `TRANSPORT_EXECUTOR_MISSING` / `CROSS_PLANE_CANDIDATES_EXHAUSTED` / `UNSAFE_CANVAS_CLICK_EXHAUSTED` / `STALL_CLASSIFIER_REPLAN` / `CATCHUP_EXHAUSTED` / `PLAYER_LOC_LOST` / `NO_CANDIDATE_AVAILABLE` / `OTHER`. Set on `Status.FAILED`; cleared on the next successful `setPath`. |
| Navigator surface | `V2Navigator.FailureReason` | `BAD_REQUEST` / `NO_PLAYER_LOC` / `ENTITY_NOT_FOUND` / `NO_ROUTE` / `EXECUTOR_FAILED`. Scripts and the panel read this. When `EXECUTOR_FAILED` is set, `V2Navigator.lastExecutorFailureReason()` surfaces the underlying executor reason for precise log lines. |

`HybridNavigator`'s failure log lines pull these tags live so a single grep tells the full story (mode + handler + V2 reason + executor reason).

### HARD CONSTRAINT: every canvas click resolves to "Walk here"

Per the spec's non-negotiable rule, the chain is:

1. **Pre-filter** (`EmptyTileFilter`) drops tiles with NPCs, ground items, or game objects whose first action isn't "Walk here." Conservative — false negatives are fine.
2. **Authoritative live check** (`isLeftClickWalk` inside `HumanizedInputDispatcher`) is the gate at press time. Set via `ActionRequest.strictWalk(true)` from `V2ExecutorEnv.dispatchWalk`.
3. On rejection, the dispatcher aborts and stashes a string in `lastError`. The executor reads it next tick, blacklists the tile via `InvalidationClassifier`, and picks a different canvas tile **on the same tick**. After `MAX_CLICK_REJECTS_PER_LEG = 5` consecutive rejections, FAIL with `UNSAFE_CANVAS_CLICK_EXHAUSTED` so the navigator can replan.

## Diagnostics (`RouteReadiness` + `InspectionDumper`)

`RouteReadiness.check(from, to)` is a pure-data pre-flight that reports loaded vs missing region chunks, endpoint walkability, BFS reachability, the nearest known tile to the goal, and the first reason a connected corridor breaks (`BreakReason`). Used by:

- Panel button **Check V2 readiness** (per-route preset combo for bank↔pen north/south, Lumby↔Draynor, Lumby↔GE).
- Panel button **Dump corridor** writes per-row JSON under `~/.runelite/recorder/inspect/corridor-<from>-to-<to>-<ts>.json`.
- Panel button **Plan A→B** runs the planner and writes the result.

The readiness service uses `V2Planner.planDeterministic` so the yes/no answer is independent of the user's variation toggle.

## Where to read more

- Spec: `docs/superpowers/specs/2026-05-07-navigator-interface-architecture.md` — what V2 is, why each constraint exists, the full Phase 7–17 rollout.
- Plan: `docs/superpowers/plans/2026-05-07-navigator-v2-implementation.md` — phase-by-phase how it was built and the seed-pass / acceptance procedure.
- Test corpus: `runelite-client/src/test/java/net/runelite/client/plugins/recorder/nav/`.
