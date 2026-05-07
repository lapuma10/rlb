# Navigator

Single abstraction over "get the player from A to B". Scripts depend
on the `Navigator` interface, never on a concrete walker class. A
single config flag (`RecorderConfig.navigatorImpl()`) flips between
the two implementations:

| Impl | Class | Behavior |
|---|---|---|
| `TRAIL_V1` | `TrailNavigator` (this package) | Wraps the existing `trail.TrailWalker`. Replays a recorded trail tile-by-tile; well-tested but limited to seeded routes. |
| `WORLDMAP_V2` | `v2.V2Navigator` | Plans dynamically over the world model (`worldmap.MapStore` + `worldmap.TransportIndex`). Top-K macro routes + noisy A* + recent-route memory; canvas/minimap modalities; typed-failure recovery. |

The factory (`NavigatorFactory`) is the single switch site — every
script that opts into the Navigator abstraction goes through it.
ChickenFarmV3 is the round-1 consumer.

## V2 structure (the dynamic side)

```
V2Navigator
  ├── V2Planner            — top-K + noisy A* + RouteHistory
  │     ├── MultiRegionAStar     (worldmap-aware A* with transport edges)
  │     └── TopKRouter / RouteHistory
  └── V2Executor           — per-tick state machine
        ├── CanvasTilePicker     (weighted distance pick along path)
        ├── EmptyTileFilter      (advisory pre-filter — NPC/item/door)
        ├── MinimapClicker       (modality alternate + preconditions)
        └── InvalidationClassifier  (typed failures + blacklist)
```

Per the spec's HARD CONSTRAINT, every canvas click must resolve to a
"Walk here" left-click at the actual click pixel. V2's canvas walks
go through `ActionRequest` with `strictWalk=true`, which gates the
press on `isLeftClickWalk` and aborts with `lastError` (no minimap
fallback) when the menu mismatches — V2's executor picks a different
canvas tile, not a different modality.

V2Planner + RouteHistory are built once per session in
`RecorderPlugin` (planning is stateless across scripts). V2Executor +
its `Env` adapter (`V2ExecutorEnv`) + InvalidationClassifier are
**per-script** so attempt-local recovery state doesn't bleed across
independent walks.

## Where to read more

- Spec: `docs/superpowers/specs/2026-05-07-navigator-interface-architecture.md` — what V2 is, why each constraint exists.
- Plan: `docs/superpowers/plans/2026-05-07-navigator-v2-implementation.md` — phase-by-phase how it was built and the seed-pass / acceptance procedure.
