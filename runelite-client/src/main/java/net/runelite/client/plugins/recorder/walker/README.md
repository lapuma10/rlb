# Walker framework

Reusable walking subsystem for the recorder plugin's bot scripts. Lets you describe a route as a sequence of `WorldArea` waypoints (plus stairs/gates between them) and have the player driven along it. Modeled on the dax/OSBot pattern at smaller scope: hand-supplied macro path + per-tick BFS micro-stepping over engine collision flags.

## What's in here

| Class | Role |
|---|---|
| `Reachability` | Pure 8-connected BFS over `CollisionData` flags. Returns a `ReachabilityMap` (per-tile distance + parent pointer + frontier set). No client-thread ops in the hot path beyond reading the flag grid. |
| `StepClickPicker` | Combines `ReachabilityMap` with canvas/minimap projection to choose a click target. Two methods: `pick(area)` for direct walking when the destination is reachable in one BFS, `pickTowards(target)` for step-toward when it isn't. |
| `PathSpec` | Scripter-facing builder. Methods: `walk(name, area)`, `walkTiles(name, tiles)`, `climbUp(tile)`, `climbDown(tile)`, `gate(tile)`, `transport(tile, verb)`. Wraps `transport.Waypoint` underneath. |
| `ObstacleHandler` | Frontier-tile scanner with a verb whitelist. Defaults to `Open / Close / Climb-up / Climb-down / Cross / Pass / Squeeze-through / Jump-over / Pay-toll`. Configurable via `withVerbs(...)`. |
| `UniversalWalker` | The state machine. Per-tick: BFS, monotonic landmark progression, click/interact throttle, 15s stuck timer. Returns `IN_PROGRESS / ARRIVED / STUCK / ERROR` to the caller. |

## Layering rule (important)

**The walker only walks.** It does not handle banking, combat, inventory, login, or any other concern. Those stay with the calling script.

```
┌──────────────────────────────────────────────┐
│ Script (e.g. ChickenFarmV2Script)            │
│  outer FSM: BANKING / OUTBOUND / AT_PEN /   │
│             RETURN                           │
│  ┌────────────────────────────────────────┐  │
│  │ BANKING  → BankInteraction primitives  │  │
│  │ OUTBOUND → walker.tick(OUTBOUND_SPEC)  │  │
│  │ AT_PEN   → ChickenCombatLoop           │  │
│  │ RETURN   → walker.tick(RETURN_SPEC)    │  │
│  └────────────────────────────────────────┘  │
└──────────────────────────────────────────────┘
```

If you find yourself adding `BankInteraction` or `ChickenCombatLoop` references inside `walker/`, stop — that's a layering violation. Banking and combat live in `farm/` and `combat/`; the walker calls neither.

## Minimal usage

```java
// One-time per script: build the path.
PathSpec outbound = PathSpec.builder("my-outbound")
    .climbDown(stairsTile)
    .walk("checkpoint-1", checkpointArea1)
    .walk("checkpoint-2", checkpointArea2)
    .gate(gateTile)
    .walk("destination", destArea)
    .build();

// Once per tick, on a worker thread:
UniversalWalker.Status st = walker.tick(outbound);
switch (st) {
    case ARRIVED:    walker.reset(); transitionToNextPhase(); break;
    case STUCK:      abort(); break;
    case IN_PROGRESS: break;
}
```

`walker.tick()` is monotonic-forward — if you start mid-route, it picks the closest landmark already passed and walks forward from there. It does not backtrack, so don't expect the walker to "find its way home" if the player wanders off.

## How click targets are picked

Per tick the walker:

1. Reads player position and computes a fresh `ReachabilityMap` (8-connected BFS, depth 16, uses engine collision flags so it stops at walls / closed gates / water).
2. Asks the picker for a click target:
   - If the destination area has a reachable tile that projects to canvas or minimap → `pick(area)` chooses one inside it (canvas-preferred).
   - Otherwise → `pickTowards(target)` chooses the BFS-reachable tile closest to target that strictly improves over the player's current distance. **Minimap-only** for this fallback — canvas clicks resolve through whatever's rendered at the pixel (a tree there → "Chop", a log → "Take", an NPC → "Attack"), and step-toward picks intermediate tiles where pixel-overlap is the rule, not the exception.
3. Dispatches a humanized click via `HumanizedInputDispatcher`. For Transport steps (stairs / gates / agility shortcuts) the dispatcher's right-click → menu-pick flow is used so the correct verb is invoked even when it isn't the L-click default.

## Why no global graph (yet)

OSBot's webwalker bakes a graph of every walkable tile in OSRS, runs A* between named source/sink nodes. That's the right design for "walk anywhere from anywhere" bots. We don't need it: each script defines its own short, fixed route. The macro plan IS the graph, hand-supplied as a `PathSpec`. If a future bot needs cross-region navigation the framework can grow a global layer; for now we get 95% of the value with 5% of the code.

## Tests

`runelite-client/src/test/java/net/runelite/client/plugins/recorder/walker/` — 31 cases covering:

- BFS correctness across walls, planes, edge of scene
- Frontier detection (tiles where expansion stopped due to collision)
- `PathSpec` builder variants and the round-trip via `transport.Waypoint`
- Verb whitelist filter
- Spiral ring offset ordering for `ObstacleHandler`
- `StepClickPicker` strict-progress invariant

Run: `./gradlew :client:test --tests 'net.runelite.client.plugins.recorder.walker.*'`

## Concrete consumers

| Script | Status |
|---|---|
| `scripts/ChickenFarmV2Script.java` | Active. Uses `UniversalWalker` for OUTBOUND/RETURN; uses `BankInteraction` for BANKING; uses `ChickenCombatLoop` for AT_PEN. The reference example. |
| `scripts/LumbridgeBankPenScript.java` | Active. The original hand-coded V1. Does not use the framework. Kept around for side-by-side comparison and as a working fallback. |

Both are wired into the same "Chicken farm" tab in `RecorderPanel` so you can run either or compare them. They have independent dispatchers, so starting one doesn't block the other.
