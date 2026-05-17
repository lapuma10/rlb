# Nav-V2 — Debug Overlays, Walk-Test Panel, Data Overrides

**Date**: 2026-05-17 (evening)
**Branch**: `nav-engine-integration`
**Status**: tooling + first data override landed. End-to-end pen↔bank with V2 still requires identifying remaining missing transports.

Builds on `2026-05-17-nav-engine-integration-handoff.md`. Read that first for the engine architecture; this doc only covers what changed today.

---

## Commits since the integration handoff

```
8ea723e4d  fix(nav-v2): wire predicates through BFS + plumb real PlayerState
8868cfcbc  fix(transport): match Skretzo verb against "<action> <objectName>" composite
ef7c97862  fix(nav-hybrid): V1 fallback resumes from nearest trail leg, not leg 0
7cbe79cde  fix(nav-v2): handle door + NPC dispatch rejects correctly, no perma-blacklist
a79c2f9d9  fix(trail-walker): shorter hop when player is stalled
22bd6b28b  fix(trail-walker): goal-aware BFS + long-no-progress repick
057697e16  feat(nav-v2): V2PathOverlay — render V2 routes on the world
068cfec43  feat(nav-v2): CollisionDebugOverlay — render walkability per tile
fc4731c02  fix(nav-v2): CollisionDebugOverlay — correct E/W edge vertex pairs
2d19c32b5  feat(chicken-farm-v3): walk-test debug panel — toggles + force-phase
2a4724bd1  feat(nav-v2): transports-overrides.tsv + chicken pen gate row
```

Group by purpose:

### Planner / executor bug fixes (5)

- **`8ea723e4d`**: V2's `WaypointPlanner` was passing `null` to BFS for the `TilePredicate` and to `PlayerState`. Predicate-registered tile bans had no effect; every requirement-gated transport (agility shortcuts, fairy rings, teleport items, etc.) filtered out by `TransportRequirement.satisfiedBy(ctx)`. Now reads `snap.predicates()` + `snap.player()`.
- **`8868cfcbc`**: `TransportResolver.matchedAction` only compared the wanted verb against `ObjectComposition.getActions()`. Skretzo's TSV stores the verb as `"<action> <object name>"` (the full menu line), but the engine's action array contains only the bare action. Every `Climb-up Staircase`-style transport failed with `TRANSPORT_OBJECT_NOT_FOUND`. Now tries both forms.
- **`ef7c97862`**: V1 fallback (`TrailNavigator`) used to start at leg 0 regardless of player position. When V2 walked the player partway then failed, V1 dispatched a walk to the trail's first tile (off-screen) and stalled. Now picks the trail leg whose closest tile is nearest to the player (Chebyshev, same plane); fails with typed reason if no leg is within 15 tiles.
- **`7cbe79cde`**: `V2Executor` strict-walk rejection always blacklisted. Now branches: openable on the tile → dispatch Open instead; dynamic entity (NPC) → transient penalty only; anything else → permanent blacklist. `InvalidationClassifier.classify` also exits before counter-increment on DYNAMIC_BLOCKER so an NPC sitting on a critical tile (Lumbridge guard on the staircase) doesn't get the tile killed for the route.
- **`a79c2f9d9` + `22bd6b28b`**: V1 stall recovery. Hop cap on `pickAheadTile` now scales by `sinceMove` (16 → 8 → 4 tiles); long-no-progress override forces a re-pick after 8s of no distance improvement even when the player is technically still shuffling; corridor sidestep gained a goal-BFS gate so candidates that look fine from the player but are a long detour to the leg's goal (cow-pen-interior case) get rejected.

### Visualisation overlays (3)

- **`057697e16` — `V2PathOverlay`**: renders the active V2 planned route on the world. Walk-leg tiles coloured per leg (green / cyan / yellow / purple / orange / lime, cycles after 6), transport tiles outlined in magenta with `#N verb` labels, passed tiles dim to grey as the player progresses. Wired into `V2Executor.setPath` and the progress-advance log site. Config: **Trail overlay → Show V2 path overlay** (default ON).
- **`068cfec43` + `fc4731c02` — `CollisionDebugOverlay`**: per-tile collision flag painter. Green tint = walkable; solid red = `BLOCK_MOVEMENT_FULL`; red edge segment = directional wall on that side (`BLOCK_MOVEMENT_NORTH/EAST/SOUTH/WEST`). Reads live `WorldView.getCollisionMaps()` so what you see is exactly what V2's BFS kernel sees. Render radius 30 tiles around the player. Config: **Experimental → Show collision debug overlay** (default OFF — it's debug-only).

### Walk-test debug panel (1)

- **`2d19c32b5`**: Chicken Farm V3 panel gained a "Debug" sub-section under Start/Stop:
  - Checkboxes: `Stop after arrival`, `Skip combat at pen`, `Skip banking`.
  - Buttons: `Walk → Pen` (force-start in OUTBOUND), `Walk → Bank` (force-start in RETURN).
  - All toggles are `AtomicBoolean`s read only by `tickWalk`'s ARRIVED branch; inert during a normal Start cycle. `forceStartState` is consumed once per `start()` so a forced run doesn't leak.

### Data override mechanism (1)

- **`2a4724bd1` — `transports-overrides.tsv`**: new file in `runelite-client/src/main/resources/nav/transports/`, loaded after Skretzo's six bundled files by `TransportTableLoader`. Same TSV format as `transports.tsv`. First row added: **Lumbridge chicken pen south gate (object 1560)** — Skretzo's data has no transport on the pen perimeter, so V2's BFS treated the pen as fenced-in and every pen→bank plan failed `BFS leg ... UNREACHABLE`.

---

## How the V2 visualisation works in practice

To see V2 plan a route end-to-end:

1. **Settings → Recorder plugin → Experimental → Navigator mode**: `V2_WITH_V1_FALLBACK` (or `V2_STRICT` if you want V2 only).
2. **Trail overlay → Show V2 path overlay**: ON (default).
3. **Experimental → Show collision debug overlay**: ON if you want to see why BFS fails at a particular tile.
4. **Recorder panel → Chicken Farm V3 → Debug**: ☑ `Stop after arrival`, then click `Walk → Bank` or `Walk → Pen`.

What you'll see:
- V2 plan succeeds → coloured tile path on the world, magenta transports labelled with verbs, dim-grey tiles trail the player as they progress.
- V2 plan fails → no coloured tiles drawn (the overlay only renders when there's a plan). Log line `BFS leg (A) → (B) failed: UNREACHABLE`: B is the tile V2 couldn't reach. Flip the collision overlay on, walk to B, look for a wall with a gate/door in-game without a magenta transport label — that's a data gap.

---

## Adding a missing transport (the data-override workflow)

When the collision overlay shows a wall where an openable gate/door exists:

1. Note the **two tiles** (the one in front of the gate + the one on the other side) and the **object ID + verb** (use the Click Inspector to read these in-game).
2. Append to `runelite-client/src/main/resources/nav/transports/transports-overrides.tsv`:
   ```
   <fromX> <fromY> <fromPlane>	<toX> <toY> <toPlane>	<Verb> <Target> <objectId>						<duration>
   ```
   Tabs between the three blocks (origin / destination / `action target id`). Duration is 1 for a normal gate, blank ok.
3. For bidirectional gates, add both rows.
4. Rebuild shadow jar + relaunch (no test runner needed — `TransportTableLoader.loadDefaults()` re-reads on plugin startup).

The chicken-pen gate row in the file is the worked example:
```
3236 3296 0	3236 3295 0	Open Gate 1560						1
3236 3295 0	3236 3296 0	Open Gate 1560						1
```

---

## Current state — what works, what doesn't

### Works

- V2 plans + executes the pen↔castle-stairs↔bank route **as long as Skretzo's data covers every gate/door on the route**. Castle stairs are in Skretzo's data; pen gate is in the override now.
- V1 fallback kicks in cleanly when V2 fails (Bug B, `ef7c97862`).
- Door / NPC dispatch rejects no longer permanently kill tiles (Bug 7cbe79cde).
- TrailWalker stall recovery (Bug B-equivalent for V1).

### Doesn't work yet — open data gaps

User reported that **OUTBOUND (bank→pen) walking still fails** after the pen gate override. Probable cause: another gate or door between Lumbridge castle exit and the pen that Skretzo doesn't model. To identify:

1. Run V2_STRICT, click `Walk → Pen` at the bank.
2. Read the log line `BFS leg (A) → (B) failed: UNREACHABLE`. B is the target tile the BFS couldn't reach.
3. Walk a player along the route from A to B with the collision overlay on; the gap will be visible as red-edge wall with an in-game gate.
4. Add the override row, rebuild, retest.

Repeat until both directions plan end-to-end.

### Known false-positives in the diff tool

A one-shot diff against the recorded trail TRANSPORT events found 4 "missing" transports, all Lumbridge castle stairs. They aren't missing — they're recorded with the **impostor object IDs** (`56230`, `56231`) while Skretzo uses the **base IDs** (`16671`, `16672`, `16673`). The impostors are runtime variants the engine serves; the base IDs map to the same staircase. No override row needed for these.

The diff is therefore not a reliable gap-finder. Use the in-game test loop (above) instead.

---

## Build + run

Unchanged from the earlier handoff:

```bash
JBIN=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home/bin/java
JAVA_HOME=$(dirname $JBIN)/.. ./gradlew :client:shadowJar
$JBIN -ea \
  --add-opens java.desktop/com.apple.eawt=ALL-UNNAMED \
  --add-opens java.desktop/com.apple.eawt.event=ALL-UNNAMED \
  --add-opens java.base/java.lang=ALL-UNNAMED \
  -jar runelite-client/build/libs/client-*.jar --developer-mode
```

`transports-overrides.tsv` is bundled into the shadow jar — every rebuild picks it up automatically.

---

## Next steps in order of leverage

1. **Find the next missing transport.** Run V2_STRICT, paste the failing BFS leg, add the override.
2. Once pen↔bank works end-to-end with V2, run 10 consecutive cycles to confirm the planner is stable.
3. Flip `Show V2 path overlay` to default-OFF and `Navigator mode` default to `V2_WITH_V1_FALLBACK` once #2 passes — that promotes V2 to primary with V1 as safety net.
4. Retire spec §8 THROW items (`V2Planner`, `MultiRegionAStar`, `TopKRouter`, `RouteHistory`, trail-bias) per the original handoff's roadmap.
