# Session handoff — chicken-loop fixes, walking-graph design

Date: 2026-04-27
Worktree: `.claude/worktrees/sequence-engine`

## TL;DR

- Chicken combat loop has off-thread NPC-accessor bugs (OSBot port artifact). Fixes
  applied to `ChickenCombatLoop.java` but **not yet compiled** — build is blocked
  on a parallel agent's mid-stream login work (`LoginError` symbol missing).
- Mining loop is clean — already uses the right pattern (DTO snapshots → primitives).
- Login package is being rewritten by another agent. **Hands off.** Earlier comment
  edits to `LoginAssistant.java` were reverted by the rewrite; that's expected.
- Designed a world-graph travel system (named nodes + recorded edges + Dijkstra
  pathfinding + reverse-edge generator) to replace anonymous flat-text routes.
  Not started.

## What was applied this session

### `runelite-client/.../plugins/devtools/WidgetInspector.java` (master + worktree)

- Picker no longer crashes on the login screen; logs a clean info instead.
- New "Dump All" button next to Revalidate. Recursively logs every widget root
  + child to `client.log` ignoring the "Hide hidden" filter. Empirically validated
  the OSRS title screen has zero widget roots → confirms `client.changeWorld`
  (engine setter) is the correct pre-login world-switching path.

### `runelite-client/.../plugins/recorder/combat/ChickenCombatLoop.java`

- New constants/fields: `NOT_READY_GRACE_TICKS = 3`, `justEnteredSelecting`,
  `notReadyTicks`.
- `setState(SELECTING)` now flips `justEnteredSelecting = true` so the
  diagnostic fires once per entry.
- `doSelect()`:
  - Early abort if `self == null || playerPos == null` for ≥3 polls (~1.8s).
  - First-miss-after-entry diagnostic via new `logSelectionDiagnostic(snap)`
    helper. Prints `"pick miss at <pos> (plane N): X chicken(s) total,
    closest dist=Y, rejected outOfRange=A wrongPlane=B dying=C
    engagedByOther=D (range=6)"`.
- **Threading fixes (the actual bug)** — wrapped two call sites in `onClient(...)`
  because the OSBot port called NPC accessors off the client thread:
  - `selector.pick(...)` (line ~258) — `getWorldLocation`/`getInteracting`/
    `getHealthRatio`/`getComposition` all assert `isClientThread()` under `-ea`.
  - `tracker.observe(locked, snap.self)` in `combatTick(...)` — same accessors.
- Imports added: `java.awt.Rectangle` is unused here; the relevant adds are
  `net.runelite.api.Actor` + `net.runelite.api.NPCComposition`.

### Audit results (every symbol verified to exist)

| What | Where | Status |
|---|---|---|
| `Actor`, `NPCComposition` | `runelite-api/.../Actor.java`, `NPCComposition.java` | ✓ |
| `Actor.getInteracting/getHealthRatio/getWorldLocation/getAnimation` | `Actor.java:83,92,111,142` | ✓ |
| `NPC.getComposition`, `NPC.getName` | `NPC.java:43,61` | ✓ |
| `WorldPoint.distanceTo(WorldPoint)` | `WorldPoint.java:449` | ✓ |
| `NpcSelector.DEFAULT_RANGE` | `NpcSelector.java:52` | ✓ |
| `dispatcher.runOnClient(Supplier<T>)` | `HumanizedInputDispatcher.java:786` | ✓ |
| `ItemContainer.count()` / `.count(int)` | `ItemContainer.java:91,77` | ✓ |
| `InventoryID.INV` | `InventoryID.java:100` (= 93) | ✓ |

## Build blocker

`runelite-client/.../sequence/login/StateResult.java:38` and `LoginContext.java:51,79,80`
reference type `LoginError` which has no source file. The parallel agent is
implementing the login plan (task list shows ~18 pending tasks; Tasks 2 & 3 —
`StateResult`, `LoginContext` — shipped earlier this session). `LoginAssistant.java`
was rewritten as a thin facade delegating to `LoginRunner` + `LoginStates`. **Do
not touch the login package.**

Build will compile once the parallel agent ships their `LoginError` definition.
Until then:

```
./gradlew :client:shadowJar
> error: cannot find symbol: class LoginError
```

## Architectural follow-up (chicken combat)

The `onClient(...)` wraps in `ChickenCombatLoop` are correct but treat the symptom.
The proper fix mirrors what `MiningLoop` already does:

| | Mining (correct) | Chicken combat (today) |
|---|---|---|
| Snapshot shape | record of primitives | references to live `NPC`/`Player` |
| Tracker input | primitives | live `NPC` |
| Selector input | value-type `Candidate` | live `NPC` list |

Refactor: extract `Snapshot.npcs: List<NPC>` into `Snapshot.npcs: List<NpcView>`
where `NpcView` is `(int index, String name, WorldPoint loc, int healthRatio,
@Nullable Player interactingPlayer)` captured inside `takeSnapshot()`'s
client-thread closure. `NpcSelector.pick` and `CombatStateTracker.observe`
operate on `NpcView` instead. Existing tests with mocked NPCs need updating to
mock `NpcView` instead. ~80 lines net. Removes the threading bug class entirely
and aligns with the mining pattern.

## World-graph travel system (designed, not started)

Replaces the current flat-text routes (`~/.runelite/sequencer/routes/<name>.txt`)
with a single composable graph at `~/.runelite/sequencer/world-graph.json`.

### Schema

```json
{
  "version": 1,
  "nodes": {
    "lumbridge_bank":         { "tile": [3208, 3220, 2], "radius": 4, "tags": ["bank","f2p"] },
    "lumbridge_chicken_pen":  { "tile": [3230, 3296, 0], "radius": 3, "tags": ["combat","f2p"] },
    "ge":                     { "tile": [3164, 3486, 0], "radius": 5, "tags": ["bank","ge"] }
  },
  "edges": [
    {
      "id": "lumb_bank__to__chicken_pen__v1",
      "from": "lumbridge_bank",
      "to":   "lumbridge_chicken_pen",
      "waypoints": [ /* List<Waypoint> serialized form */ ],
      "recordedAt": "2026-04-26T22:31:00Z",
      "lastValidated": "2026-04-26T22:31:00Z",
      "meanDurationMs": 28400
    }
  ]
}
```

### Key properties

- **Edges are directional.** Reverse is opt-in via the auto-generator below.
- **Multiple edges per pair allowed** (`__v1`, `__v2`, …). The picker rotates
  with a recency weight so the bot doesn't re-walk the same tiles back-to-back.
  Directly addresses the "corridor sampling" rule from
  `2026-04-26-chicken-routine.md`.
- **Pathfinding is Dijkstra** on the node graph; edge weight = `meanDurationMs`,
  fallback `waypoints.size()`. Returns `List<Edge>` whose waypoints concat into
  the existing `RecorderPanel.walkRoute(List<Waypoint>)` driver. Zero changes to
  the walk mechanics.

### Auto-reverse heuristics

| Source kind | Reversed | Notes |
|---|---|---|
| `WALK` | reverse the list | trivial |
| `OPEN: door` | same tile, same verb | doors are bidirectional |
| `CLIMB_UP` ↔ `CLIMB_DOWN` | swap verb at same tile | works for ladders/stairs |
| `INTERACT: <custom>` | **bail — needs manual** | unknown semantics |

If any waypoint is `INTERACT`, auto-reverse is disabled and the user records
the return path explicitly. Everything else generates a clean reverse with one click.

### Capture UX (the "no hardcoding" goal)

The recorder *already* captures every tile-click via `EventBusCapture`
(`minimap_walk` + silent left-click destinations via
`getLocalDestinationLocation`). Lift that into named edges:

1. User clicks **"Start journey"** → dropdown of existing nodes (or "create
   new from current tile").
2. User plays normally.
3. User clicks **"End journey"** → dropdown for destination (or create new).
4. System slices the recorder buffer between the two timestamps, dedupes,
   prompts to confirm + name, writes edge to `world-graph.json`.
5. If reversal-eligible: "create reverse edge too?" — one click.

### Activity integration

```java
// before:
chickenLoop.start();   // assumes you walked there

// after:
travel.to("lumbridge_chicken_pen");   // Dijkstra from current tile, blocks on arrival
chickenLoop.start();
// on combat abort:
travel.to("lumbridge_bank");
banker.depositAll();
```

`travel.to(name)` ≈ 40 lines: find nearest node from current player tile →
Dijkstra → for each edge in path, `walkRoute(edge.waypoints)`.

### Phasing

| Phase | What | Effort |
|---|---|---|
| **1** | `WorldGraph` model + JSON load/save + panel section to add nodes from current tile and edges from current textarea content | ~1 day |
| **2** | `Pathfinder` (Dijkstra) + reverse-edge generator + "Travel to node" button | ~½ day |
| **3** | Capture-from-recorder UX (Start journey / End journey, buffer-slice extraction) | ~½ day |
| **4** | `travel.to(name)` API + use it in the chicken activity orchestrator | ~½ day |

### Pre-graph: chicken activity orchestrator (still open)

A "Start chicken activity" button (separate from "Start chicken loop") that
walks bank→pen → starts combat → on abort, walks pen→bank. ~150 lines in
`RecorderPanel.java`. Makes the existing flat-text routes usable end-to-end
*without* the graph. Could ship before Phase 1 of the graph if the user wants
the chicken loop fully autonomous sooner. Was paused mid-design when this
session pivoted.

## Build / run reference

```bash
# Build (worktree)
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
cd /Users/lilbee/Documents/GitHub/rlb/.claude/worktrees/sequence-engine
./gradlew :client:shadowJar --console=plain

# Launch (with developer mode for Widget Inspector + Dump All)
java \
  --add-exports=java.desktop/sun.awt=ALL-UNNAMED \
  --add-exports=java.desktop/com.apple.eawt=ALL-UNNAMED \
  --add-exports=java.desktop/com.apple.eio=ALL-UNNAMED \
  --add-opens=java.desktop/com.apple.eawt=ALL-UNNAMED \
  -ea \
  -jar /Users/lilbee/Documents/GitHub/rlb/.claude/worktrees/sequence-engine/runelite-client/build/libs/client-*-shaded.jar \
  --developer-mode

# Tail combat-relevant log lines
tail -F /Users/lilbee/.runelite/logs/client.log | grep -E "ChickenCombatLoop|MiningLoop"
```

Gradle project name is `:client` (directory `runelite-client`). Login screen
has zero widget roots (engine-painted) — pre-login world switching uses
`client.changeWorld(World)` (validated via Widget Inspector "Dump All").

## File pointers

- Chicken combat loop: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/combat/ChickenCombatLoop.java`
- Combat state tracker (off-thread call site): `.../combat/CombatStateTracker.java:65,83,90`
- NPC selector (off-thread call sites): `.../combat/NpcSelector.java:95-109`
- Mining loop (reference clean implementation): `.../mining/MiningLoop.java`
- Mining state tracker: `.../mining/MiningStateTracker.java`
- Recorder panel + walk driver: `.../recorder/RecorderPanel.java` (`walkRoute` line 692)
- Waypoint types: `.../recorder/transport/Waypoint.java`
- Route parser: `.../recorder/transport/RouteParser.java`
- Routes dir (existing): `~/.runelite/sequencer/routes/<name>.txt`
- Future graph file: `~/.runelite/sequencer/world-graph.json`
- Widget inspector patches: `runelite-client/src/main/java/net/runelite/client/plugins/devtools/WidgetInspector.java` (master and worktree both patched)

## What NOT to touch

- `runelite-client/src/main/java/net/runelite/client/sequence/login/` — actively
  rewritten by a parallel agent. `LoginAssistant.java` is now a facade.
  `StateResult.java`, `LoginContext.java` exist but reference a not-yet-shipped
  `LoginError`. Wait for that agent to finish their task list before touching.
