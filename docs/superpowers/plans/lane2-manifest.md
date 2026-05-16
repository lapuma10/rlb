# Lane 2 — Hand-off Manifest

**Status**: complete. Lane 6 may now consume Lane 2 outputs.
**Spec**: `docs/superpowers/specs/2026-05-16-observation-aware-navigation-engine-design.md`
**Plan**: `docs/superpowers/plans/2026-05-16-nav-engine-lane2-collision-predicates.md`
**Branch**: `worktree-agent-a6f252738a49469d2`

---

## Files shipped

### Production (Java) — `nav/v2/collision/`

| File                                | LOC | Purpose |
|-------------------------------------|-----|---------|
| `CollisionFlags.java`               |  61 | Boxed (flags, source, tile) tuple — return type of `WorldSnapshot.collisionAt`. |
| `CollisionView.java`                | 142 | Merges global snapshot + live overlay; `flagsAt(WorldPoint)`, `source(WorldPoint)`, `canMoveTo(...)`, `describeTile(...)`. |
| `GlobalCollisionSnapshot.java`      | 319 | Loads bundled `collision-map.zip`; FlagMap decode; translates Skretzo's 2-bit-per-tile (n/e) to RuneLite-style `CollisionDataFlag` bits. |
| `LiveSceneCollisionOverlay.java`    | 136 | Defensive copy of `Client.getCollisionMaps()` per plane; bounds-checked `flagsAt`, `containsTile`. |
| `PlayerState.java`                  |  44 | Spec §3 contract interface (skill levels, varbits, inventory, isMember). |
| `PlayerStateBuilder.java`           | 179 | Eagerly captures skills + member flag; lazy varbit/varplayer with `ClientThread` marshalling. |
| `WorldSnapshot.java`                |  62 | Spec §3 contract interface (collision, blocking sets, transports, predicates). |
| `WorldSnapshotBuilder.java`         | 271 | Two entry points: `fromComponents(...)` for tests; `fromClient(...)` for marshalled capture. `BlockingActorPolicy` seam. |

### Production (Java) — `nav/v2/predicate/`

| File                                | LOC | Purpose |
|-------------------------------------|-----|---------|
| `BuiltInPredicates.java`            | 132 | 8 factory methods per spec §4 Lane 2. All pure functions. |
| `PathContext.java`                  |  44 | Spec §3 contract interface (navigation, currentPath, currentWaypoint, routeSeed). |
| `PredicateRegistry.java`            | 156 | Ordered named-predicate registry; `accepts(...)`, `firstRejectorOf(...)`, `addTileCondition(...)`. |
| `TilePredicate.java`                |  26 | Spec §3 contract interface — `accept(WorldPoint, PathContext)`. |

Production total: **1572 LOC** across 12 files.

### Resources

| File                                | Size      | Notes |
|-------------------------------------|-----------|-------|
| `src/main/resources/nav/collision/collision-map.zip` | 1 164 284 B | Skretzo `master`@`d3b9b0f` (2026-05-14). SHA256 `b08f3a558ca270d48f0b2c2ba394be3cf6a71ef67e593cbc7d89850b5667067b`. |
| `src/main/resources/nav/collision/MANIFEST.md`        | text       | Source URL + SHA + license + refresh procedure. |
| `NOTICES.md` (root)                                   | text       | Skretzo attribution + BSD-2-Clause license text. |

### Tests

| File                                         | Tests | Status |
|----------------------------------------------|-------|--------|
| `CollisionViewTest.java`                     |  7    | passing |
| `GlobalCollisionSnapshotTest.java`           |  8    | passing |
| `LiveSceneCollisionOverlayTest.java`         |  8    | passing |
| `PlayerStateBuilderTest.java`                |  6    | passing |
| `WorldSnapshotBuilderTest.java`              |  6    | passing |
| `BuiltInPredicatesTest.java`                 | 18    | passing |
| `PredicateRegistryTest.java`                 | 12    | passing |
| **Total**                                    | **65**| **65/65 passing** |

Run command:
```
JBIN=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home/bin/java
JAVA_HOME=$(dirname $JBIN)/.. ./gradlew :client:test \
  --tests "*nav.v2.collision.*" --tests "*nav.v2.predicate.*"
```

---

## Spec §4 Lane 2 QC tests — mapping

The five spec-mandated QC tests for Lane 2 are covered as follows:

| Spec QC | Test class | Test method |
|---------|------------|-------------|
| 1. Same tile in global + overlay → live wins | `CollisionViewTest` | `flagsAt_sameTileInBoth_liveWins` |
| 2. Tile outside scene → fallback to global | `CollisionViewTest` | `flagsAt_outsideLoadedScene_fallsBackToGlobal` |
| 3. Plane mismatch → no cross-plane leak | `CollisionViewTest` | `flagsAt_planeMismatch_doesNotLeakCrossPlane` |
| 4. Region edge → no coord corruption | `CollisionViewTest` | `flagsAt_regionEdge_returnsConsistentResult` |
| 5. Snapshot immutability | `CollisionViewTest`, `WorldSnapshotBuilderTest` | `flagsAt_immutability_repeatedCallsConsistent` and `build_returnsImmutable_externalActorMoveAfterBuildDoesNotChangeSnapshot` |

All 5 pass.

---

## Sample debug output

Spec §4 Lane 2 mandates the per-tile debug format for Lane 6 traces.
`CollisionView.describeTile(...)` produces:

```
plane=0 source=LIVE_OVERLAY flags=0x0 neighbors=[N=y@LIVE_OVERLAY, S=y@LIVE_OVERLAY, E=y@LIVE_OVERLAY, W=y@LIVE_OVERLAY, NE=y@LIVE_OVERLAY, NW=y@LIVE_OVERLAY, SE=y@LIVE_OVERLAY, SW=y@LIVE_OVERLAY]
```

For a tile blocked by a directional wall, the format reflects which neighbours are reachable:

```
plane=0 source=LIVE_OVERLAY flags=0x82 neighbors=[N=n@LIVE_OVERLAY, S=y@LIVE_OVERLAY, E=y@LIVE_OVERLAY, W=n@LIVE_OVERLAY, NE=n@LIVE_OVERLAY, NW=n@LIVE_OVERLAY, SE=y@LIVE_OVERLAY, SW=n@LIVE_OVERLAY]
```

(`flags=0x82` = `BLOCK_MOVEMENT_NORTH | BLOCK_MOVEMENT_WEST` — corner wall.)

Lane 6 traces should call `view.describeTile(tile)` for every rejected
tile and emit it alongside the predicate that rejected. The
`PredicateRegistry.firstRejectorOf(...)` API returns the predicate name.

---

## Known limitations forwarded to Lane 6

### 1. Contracts §3 placed in Lane 2 subpackages

Per spec §3, Lane 1 owns the interface contracts but Lane 1 didn't
emit Java files. Lane 2 created the following interfaces in its own
subpackages (per file-ownership boundary) so downstream lanes can
import them:

- `nav/v2/collision/WorldSnapshot.java`
- `nav/v2/collision/PlayerState.java`
- `nav/v2/collision/CollisionFlags.java` (concrete value type, not interface)
- `nav/v2/predicate/TilePredicate.java`
- `nav/v2/predicate/PathContext.java`

Lane 4 and Lane 5 are expected to either import these as-is or
coordinate with Lane 1 to relocate (cross-lane edit). Lane 6: if
either downstream lane wants them in a flat `nav/v2/` location,
flag this for coordination.

### 2. `WorldSnapshot.transports()` typed as `Object`

Lane 4 owns `TransportTable` per spec §4. Until Lane 4's
`nav/v2/transport/TransportTable.java` lands, our `WorldSnapshot`
interface declares `transports()` returning `Object`. Consumers
must cast. Lane 4 can tighten this via a coordinated cross-lane
edit (one-line return-type change).

### 3. `PathContext` methods typed as `Object` / `Optional<Object>`

`PathContext.navigation()`, `currentPath()`, and `currentWaypoint()`
return `Object` / `Optional<Object>` until Lanes 4 and 5 ship
`NavigationContext`, `V2Path`, and `Waypoint` respectively.

### 4. `blockingObjectTiles()` empty by default

`WorldSnapshotBuilder.fromClient(...)` does NOT iterate
`Tile.getGameObjects()` to populate `blockingObjectTiles`. Object
collision is already reflected in `CollisionDataFlag.BLOCK_MOVEMENT_OBJECT`
in the live overlay; maintaining a duplicate set would risk drift.

The set is exposed for predicate use (and tests pass blocking-object
sets through `fromComponents(...)`); production callers can populate
it explicitly if/when needed.

### 5. `BlockingActorPolicy.NONE` is the default

`WorldSnapshotBuilder.fromClient(...)` treats NO NPCs as blockers by
default. This is conservative — the planner / executor will route
through NPC-occupied tiles unless a caller provides a tighter policy.

Lane 6 acceptance tests are expected to surface specific NPCs that
should be added (e.g. cows in chicken pen, NPCs in narrow doorways).
The seam to do that is `BlockingActorPolicy`:

```java
WorldSnapshotBuilder.fromClient(client, ct, global, transports, preds,
    npc -> npc.getName() != null
        && Set.of("Cow", "Goblin").contains(npc.getName()));
```

### 6. `notDangerousArea` wilderness bounds are conservative

`BuiltInPredicates.notDangerousArea(false)` only checks
`y >= 3520 && plane == 0`. Other PvP areas (Mage Arena tiles,
PvP-world overrides, Castle Wars lobby) are NOT excluded. Lane 6
or downstream consumers should layer additional predicates as
needed.

### 7. Resource path deviation from spec §9

Spec §9 wrote `runelite-client/src/main/resources/runelite/nav/...`.
The `runelite-gradle-plugin` `IndexTask` iterates `runelite/*` and
`parseInt`s every subdirectory name (it expects numeric archive IDs),
which broke the build with a `NumberFormatException: For input string: "nav"`.

Resources moved to top-level `nav/collision/` to avoid the conflict.
Documented in `NOTICES.md` and `MANIFEST.md`. Lane 4's TSVs should
follow the same convention: `src/main/resources/nav/transports/`.

### 8. Skretzo BFS-cardinal-order is what we trust

Skretzo's `OrdinalDirection` order is W/E/S/N/SW/SE/NW/NE. Our
collision view exposes both raw flags and `canMoveTo(...)` cardinal
helpers. Lane 3 will consume these for BFS expansion and confirm
the order matches Jagex's server algorithm per spec §10 mitigation.
Lane 2 does NOT assert the ordering is correct — that's Lane 3's
property test.

---

## Lane 4 stub usage notes

Lane 4 can begin against the Lane 2 surface area without further changes:

1. **Build a `WorldSnapshot` for tests**:
   ```java
   WorldSnapshot snap = WorldSnapshotBuilder.fromComponents(
       collisionView, Set.of(), Set.of(),
       /*transports=*/ myTransportTable,
       new PredicateRegistry(),
       System.currentTimeMillis());
   ```

2. **Mock `Client` for `PlayerState`**:
   ```java
   Client client = Mockito.mock(Client.class);
   Mockito.when(client.isClientThread()).thenReturn(true);
   Mockito.when(client.getRealSkillLevels()).thenReturn(new int[Skill.values().length]);
   PlayerState ps = PlayerStateBuilder.fromClient(client, null);
   ```

3. **`TransportTable` is loosely typed for now** — when Lane 4
   ships its interface, update `WorldSnapshot.transports()` return
   type via a coordinated cross-lane edit.

4. **`PathContext` is provided by Lane 4**'s `PathContextImpl`. The
   minimum implementation lives in Lane 2 tests (anonymous-inner
   `PathContext` returning empty Optionals) — Lane 4's impl will
   replace it with the real navigation context.

---

## Acceptance gate self-check

- [x] All 12 production files exist and `./gradlew :client:compileJava` succeeds.
- [x] All 7 test classes pass: 65/65 tests passing.
- [x] Spec §4 Lane 2's 5 QC tests mapped to specific test methods, all passing.
- [x] Manifest file written (this file).
- [x] No files under `nav/v2/bfs/`, `nav/v2/transport/`, `nav/v2/planner/`, `nav/v2/executor/`, or `nav/v2/qc/` touched.
- [x] No edits to existing `nav/v2/V2*.java` files.
- [x] No new entries to spec §3 (interfaces match the §3 shape verbatim, modulo `Object` typing for cross-lane forward decls).

---

## Commits (this lane)

```
f62c53d1b feat(nav-engine,lane2): vendor Skretzo collision-map snapshot
d57d16ddd feat(nav-engine,lane2): GlobalCollisionSnapshot from bundled data
2e6d59285 feat(nav-engine,lane2): LiveSceneCollisionOverlay captures loaded scene
e665c2c61 feat(nav-engine,lane2): CollisionView merges global + live overlay
6b6d5bdb2 feat(nav-engine,lane2): WorldSnapshotBuilder captures immutable snapshot
fb014324c feat(nav-engine,lane2): PlayerStateBuilder captures immutable player state
640be4e60 feat(nav-engine,lane2): PredicateRegistry composes predicates
10cd0b1be feat(nav-engine,lane2): 8 built-in tile predicates
```

Plus the docs commit landing this manifest.

---

**Lane 2 hand-off complete. See this file for the full inventory.**
