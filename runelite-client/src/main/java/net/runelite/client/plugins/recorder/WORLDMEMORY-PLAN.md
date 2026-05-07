# WorldMemory v1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Spec:** `recorder/WORLDMEMORY-SPEC.md` (read this first; the plan delegates design to the spec).
>
> **Hard constraint from the user:** **DO NOT run `gradle`, `./gradlew`, `java`, `javac`, `kill`, or any process that could compile the project or restart/kill the running RuneLite client.** Write code, write tests, commit. The user will run `./gradlew :client:compileJava` and the test suite themselves once tasks are done.

**Goal:** Build a same-region, LOS-aware path planner (WorldMemory) that fixes CookingScriptV3's off-canvas range bug, plus a small `Walker` interface refactor that unblocks future walker implementations.

**Architecture:** Per spec — three layers (static map / entity memory / planner) under a new `recorder/worldmap/` package; passive scraping on the client thread writes to a mutable `RegionChunkBuilder` then publishes an immutable `RegionChunkSnapshot` via `AtomicReference`; planner runs on a worker thread reading only the snapshot; `MapPlanner.planToInteractTile` returns an `Optional<PathSpec>` consumed by the existing `UniversalWalker`.

**Tech Stack:** Java 17, RuneLite plugin API, Gson (already on the classpath via existing `TrailIO`), JUnit 4 for tests.

---

## File Structure

```
NEW (under runelite-client/src/main/java/net/runelite/client/plugins/recorder/):
  walker/Walker.java                                — small interface
  worldmap/SceneScraper.java                        — client-thread tile scraper
  worldmap/EntityScraper.java                       — client-thread NPC scraper
  worldmap/EntitySighting.java                      — POJO
  worldmap/RegionChunkBuilder.java                  — mutable, package-private
  worldmap/RegionChunkSnapshot.java                 — immutable, public
  worldmap/MapStore.java                            — chunk cache + AtomicReference + flush
  worldmap/MapStoreIO.java                          — JSON read/write helpers
  worldmap/EntityIndex.java                         — name → sightings lookup
  worldmap/MapAStar.java                            — Dijkstra/A* over snapshot collision
  worldmap/MapPlanner.java                          — planner entry point
  worldmap/WorldMemoryConfig.java                   — cadence / caps / weights
  worldmap/RegionIds.java                           — small util: regionId packing
  worldmap/Bresenham.java                           — small util: LOS line walk

NEW TESTS (under runelite-client/src/test/java/net/runelite/client/plugins/recorder/):
  worldmap/RegionChunkSnapshotTest.java
  worldmap/MapStoreIOTest.java
  worldmap/MapAStarTest.java
  worldmap/BresenhamLosTest.java
  worldmap/MapPlannerTest.java
  worldmap/EntityIndexTest.java
  worldmap/fixtures/lumbridge-kitchen.json       — hand-authored fixture
  worldmap/fixtures/wall-maze.json               — hand-authored fixture
  worldmap/fixtures/los-block.json               — hand-authored fixture

MODIFIED:
  walker/UniversalWalker.java                       — `implements Walker`; no behavioral change
  RecorderPlugin.java                               — register WorldMemory lifecycle + tick subscriber
  RecorderConfig.java                               — add `useWorldMemoryPlanner` boolean
  RecorderPanel.java                                — add checkbox bound to the config
  scripts/CookingScriptV3.java                      — currentCookPath() toggle block
  Plus: any script with a UniversalWalker field/parameter — change type to Walker

UNTOUCHED (do not modify):
  trail/* (TrailWalker, TrailRecorder, etc.)
  walker/Reachability.java, walker/PathSpec.java, walker/StepClickPicker.java, walker/ObstacleHandler.java
  transport/TransportResolver.java
  All sequence/* code
```

---

## Phasing

The plan is organised into 5 phases. Phase 0 is independent of WorldMemory; Phase 1 is foundational; Phases 2-3 build on it; Phase 4 is integration; Phase 5 is the cooking proof. Subagent-driven execution can parallelise within phases (noted per task).

```
Phase 0: Walker interface refactor          (independent of WorldMemory)
Phase 1: Storage layer + config             (foundational; mostly serial)
Phase 2: Scrapers + entity index            (parallel-safe within phase)
Phase 3: Planner (A*, LOS, MapPlanner)      (parallel-safe within phase)
Phase 4: Plugin/Panel/Config integration    (mostly serial)
Phase 5: CookingScriptV3 migration + fixtures (final)
```

---

## Phase 0 — Walker interface refactor

### Task 0.1 — Define `Walker` interface

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/walker/Walker.java`

- [ ] **Step 1: Write the interface**

The interface lifts six methods from `UniversalWalker`'s public surface. Per the spec, timing/threshold constants stay on `UniversalWalker`. `Status` and `InternalState` enums move into the interface as nested types so any impl can return them.

Read `walker/UniversalWalker.java` lines 60-62 for the enums and lines 149-180 for the public methods to confirm the surface before writing.

```java
package net.runelite.client.plugins.recorder.walker;

import javax.annotation.Nullable;

/**
 * Path executor contract. Implementations consume a {@link PathSpec} via
 * {@link #tick} and drive the player toward the spec's waypoints. Different
 * impls may humanize input differently (deterministic clicks vs replayed
 * mouse-trace simulation, planned for v3).
 *
 * <p>Contract is {@link #tick(PathSpec)} only. How the impl tracks step
 * progress, last-clicked-transport bookkeeping, click cadence, etc. between
 * ticks is its own internal concern — the interface does NOT require any
 * specific bookkeeping.
 */
public interface Walker
{
    enum Status { IN_PROGRESS, ARRIVED, STUCK, ERROR }

    enum InternalState { IDLE, WALKING, AT_TRANSPORT, CROSSING, ARRIVED, STUCK }

    Status tick(PathSpec spec) throws InterruptedException;

    /** Reset all per-spec state. */
    void reset();

    /** Current internal state for diagnostics / overlays. */
    InternalState state();

    /** Index of the active waypoint in the current spec. */
    int currentStepIndex();

    /** The spec currently being executed, or null before the first tick. */
    @Nullable PathSpec currentSpec();
}
```

- [ ] **Step 2: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/walker/Walker.java
git commit -m "feat(walker): introduce Walker interface for swappable executors"
```

### Task 0.2 — Make `UniversalWalker` implement `Walker`

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/walker/UniversalWalker.java`

This is mechanical. Replace `UniversalWalker.Status` references with `Walker.Status` (or alias one to the other), replace `UniversalWalker.InternalState` with `Walker.InternalState`, and add `implements Walker`.

- [ ] **Step 1: Read current declaration**

Read `walker/UniversalWalker.java:58-62`. The class declares its own nested enums:

```java
public final class UniversalWalker
{
    public enum Status { IN_PROGRESS, ARRIVED, STUCK, ERROR }
    public enum InternalState { IDLE, WALKING, AT_TRANSPORT, CROSSING, ARRIVED, STUCK }
```

- [ ] **Step 2: Refactor declaration to use Walker's enums**

Edit the class header:

```java
public final class UniversalWalker implements Walker
{
    // Enums removed — moved to Walker interface.
```

Then in this file replace **every** local reference of `Status.X` with `Walker.Status.X` and `InternalState.X` with `Walker.InternalState.X`. The methods `tick(...)`, `reset()`, `state()`, `currentStepIndex()`, `currentSpec()` already match the interface signatures — no further change needed.

- [ ] **Step 3: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/walker/UniversalWalker.java
git commit -m "refactor(walker): UniversalWalker implements Walker; enums lifted"
```

### Task 0.3 — Refactor dependent scripts to use `Walker` type

**Files:**
- Modify: every file in `recorder/` that declares a `UniversalWalker` field or constructor parameter.

- [ ] **Step 1: Apply the type changes — exact site list**

The plan QC subagent enumerated the 8 known call sites that reference `UniversalWalker.Status` or `UniversalWalker.InternalState`. Edit each:

```
recorder/scripts/CookingScriptV3.java       :378-387, :593   — Status enum references
recorder/scripts/CookingScriptV2.java       :413-422, :627   — Status enum references
recorder/scripts/ChickenFarmV2Script.java   :263             — Status enum reference
recorder/mining/BankDepositStrategy.java    :169             — Status enum reference
```

Mechanical substitution per file:
- `UniversalWalker.Status` → `Walker.Status`
- `UniversalWalker.InternalState` → `Walker.InternalState`
- For type declarations (fields, parameters): `UniversalWalker walker` → `Walker walker`
- For construction sites (`new UniversalWalker(...)`): KEEP — concrete impl is created here, assigned to a `Walker`-typed field
- Add `import net.runelite.client.plugins.recorder.walker.Walker;` where Status/InternalState references live
- Keep `import net.runelite.client.plugins.recorder.walker.UniversalWalker;` only at construction sites

Re-grep at the end of this task to confirm no missed sites:

```bash
grep -rn "UniversalWalker\." runelite-client/src/main/java/net/runelite/client/plugins/recorder/ --include="*.java" | grep -v "walker/UniversalWalker.java"
```

Expected: only `new UniversalWalker(...)` matches remain (construction sites). If any `UniversalWalker.Status` or `UniversalWalker.InternalState` remains, fix it in this step.

- [ ] **Step 2: Re-verify with grep**

Same command as above. Output should contain only construction sites.

- [ ] **Step 3: Commit**

```bash
git add -A runelite-client/src/main/java/net/runelite/client/plugins/recorder/
git commit -m "refactor(walker): migrate scripts from UniversalWalker to Walker interface"
```

---

## Phase 1 — Storage layer + config

### Task 1.1 — `WorldMemoryConfig` (constants, tunable later)

**Files:**
- Create: `recorder/worldmap/WorldMemoryConfig.java`

- [ ] **Step 1: Write the class**

```java
package net.runelite.client.plugins.recorder.worldmap;

/**
 * Tunable knobs for the WorldMemory subsystem. Spec values; live tuning
 * happens via RuneLite config later if needed.
 */
public final class WorldMemoryConfig
{
    /** Scrape every N game ticks. Spec: every 2 ticks (~1.2s). */
    public final int scrapeEveryNTicks = 2;

    /** Window radius around player to scrape (window is 2R+1 wide). */
    public final int scrapeWindowRadius = 9;          // 18×18 effective

    /** Per-tick scrape budget. If a scrape would exceed this in nanos,
     *  skip and log; lastScrapedAt does NOT advance. */
    public final long scrapeBudgetNanos = 2_000_000;  // 2 ms

    /** Flush dirty chunks every N seconds. */
    public final int flushEverySeconds = 30;

    /** A* / Dijkstra caps. */
    public final int maxPathLength = 128;
    public final int maxExpandedTiles = 10_000;

    /** Ranking weights for findInteractTile. */
    public final double rankWeightPathLength = 0.6;
    public final double rankWeightChebyshevToTarget = 0.4;

    /** MapStore in-memory chunk count cap (LRU beyond this). */
    public final int memoryResidentChunkCount = 24;
}
```

- [ ] **Step 2: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/worldmap/WorldMemoryConfig.java
git commit -m "feat(worldmap): WorldMemoryConfig with v1 cadence/caps"
```

### Task 1.2 — `RegionIds` utility

**Files:**
- Create: `recorder/worldmap/RegionIds.java`
- Test: `recorder/worldmap/RegionIdsTest.java`

- [ ] **Step 1: Write the test (no execution — author only)**

```java
package net.runelite.client.plugins.recorder.worldmap;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class RegionIdsTest
{
    @Test
    public void lumbridgeKitchen_packsToExpectedRegionId()
    {
        // Lumbridge cook: (3208, 3213, 0). Region: (50, 50). Expected:
        // ((50) << 8) | 50 = 12850
        assertEquals(12850, RegionIds.regionIdFor(3208, 3213));
        assertEquals(12850, RegionIds.regionIdFor(3211, 3214));
    }

    @Test
    public void differentRegions_haveDifferentIds()
    {
        int lumby = RegionIds.regionIdFor(3208, 3213);
        int varrockEast = RegionIds.regionIdFor(3253, 3420);
        assert lumby != varrockEast : "regions must collide-free";
    }
}
```

- [ ] **Step 2: Write the impl**

```java
package net.runelite.client.plugins.recorder.worldmap;

/** Pack/unpack OSRS region IDs. Matches the engine's scheme:
 *  regionId = (regionX << 8) | regionY, where regionX = x >> 6, regionY = y >> 6.
 *  This matches scene-region encoding the client already uses. */
public final class RegionIds
{
    private RegionIds() {}

    public static int regionIdFor(int worldX, int worldY)
    {
        return ((worldX >> 6) << 8) | (worldY >> 6);
    }

    public static int regionXOf(int regionId) { return (regionId >> 8) & 0xff; }
    public static int regionYOf(int regionId) { return regionId & 0xff; }
}
```

- [ ] **Step 3: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/worldmap/RegionIds.java \
        runelite-client/src/test/java/net/runelite/client/plugins/recorder/worldmap/RegionIdsTest.java
git commit -m "feat(worldmap): RegionIds packing util + tests"
```

### Task 1.3 — `EntitySighting` POJO

**Files:**
- Create: `recorder/worldmap/EntitySighting.java`

- [ ] **Step 1: Write the class**

```java
package net.runelite.client.plugins.recorder.worldmap;

import net.runelite.api.coords.WorldPoint;

/** A single sighting of an NPC or game-object — what we saw and where.
 *  Immutable; identity is (kind, id, name, lastTile.region). Used by both
 *  EntityIndex (in-memory lookup) and the JSON persistence layer. */
public final class EntitySighting
{
    public enum Kind { NPC, OBJECT }

    public final Kind kind;
    public final int id;
    public final String name;
    public final WorldPoint lastTile;
    public final int seenCount;
    public final long lastSeenAt;

    public EntitySighting(Kind kind, int id, String name, WorldPoint lastTile,
                          int seenCount, long lastSeenAt)
    {
        this.kind = kind;
        this.id = id;
        this.name = name;
        this.lastTile = lastTile;
        this.seenCount = seenCount;
        this.lastSeenAt = lastSeenAt;
    }

    public EntitySighting withUpdatedSighting(WorldPoint newTile, long now)
    {
        return new EntitySighting(kind, id, name, newTile, seenCount + 1, now);
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/worldmap/EntitySighting.java
git commit -m "feat(worldmap): EntitySighting POJO"
```

### Task 1.4 — `RegionChunkBuilder` (mutable, package-private)

**Files:**
- Create: `recorder/worldmap/RegionChunkBuilder.java`

- [ ] **Step 1: Write the class**

Design — follows the spec's two-class rule. Package-private. Scraper-only mutator.

```java
package net.runelite.client.plugins.recorder.worldmap;

import java.util.HashMap;
import java.util.Map;
import net.runelite.api.coords.WorldPoint;

/** Mutable builder for a region chunk. Used by {@link SceneScraper} only.
 *  Package-private — readers see {@link RegionChunkSnapshot} instead.
 *
 *  <p>Thread-safety: a builder is owned by exactly one thread (the client
 *  thread) for its lifetime. {@link MapStore#publish} converts it to an
 *  immutable snapshot before any reader sees the data. */
final class RegionChunkBuilder
{
    final int regionId;
    int gameRevision;
    long lastScrapedAt;

    /** Tiles, keyed by packed (x,y,plane). Value is the 32-bit movement
     *  flags from CollisionData — LOS bits live in the same int (per spec).
     *  v1 stores movement only; the JSON `los` field is reserved space
     *  (always 0) for v2 to populate if a separate LOS read becomes useful. */
    final Map<Long, Integer> tiles = new HashMap<>();

    /** Object sightings in this region, keyed by (id, x, y, plane). */
    final Map<Long, EntitySighting> objects = new HashMap<>();

    RegionChunkBuilder(int regionId)
    {
        this.regionId = regionId;
    }

    static RegionChunkBuilder copyOf(RegionChunkSnapshot snap)
    {
        RegionChunkBuilder b = new RegionChunkBuilder(snap.regionId());
        b.gameRevision = snap.gameRevision();
        b.lastScrapedAt = snap.lastScrapedAt();
        for (RegionChunkSnapshot.TileEntry t : snap.tiles())
        {
            long key = RegionChunkSnapshot.packTileKey(t.x, t.y, t.plane);
            b.tiles.put(key, t.movement);
        }
        for (EntitySighting o : snap.objects())
        {
            long key = packObjKey(o.id, o.lastTile);
            b.objects.put(key, o);
        }
        return b;
    }

    void setTile(int x, int y, int plane, int movement)
    {
        long key = RegionChunkSnapshot.packTileKey(x, y, plane);
        tiles.put(key, movement);
    }

    void recordObject(int objectId, String name, WorldPoint tile, String[] actions, long now)
    {
        long key = packObjKey(objectId, tile);
        EntitySighting prev = objects.get(key);
        if (prev == null)
        {
            objects.put(key, new EntitySighting(
                EntitySighting.Kind.OBJECT, objectId, name, tile, 1, now));
        }
        else
        {
            objects.put(key, prev.withUpdatedSighting(tile, now));
        }
    }

    static long packObjKey(int id, WorldPoint t)
    {
        // 16 bits id, 16 bits x, 16 bits y, 4 bits plane, rest unused.
        return ((long)(id & 0xffff) << 48)
            |  ((long)(t.getX() & 0xffff) << 32)
            |  ((long)(t.getY() & 0xffff) << 16)
            |  (t.getPlane() & 0xf);
    }
}
```

- [ ] **Step 2: Commit (after RegionChunkSnapshot exists)**

This task can't compile until Task 1.5 lands `RegionChunkSnapshot`. Land them as one commit:

```bash
# After Task 1.5:
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/worldmap/RegionChunkBuilder.java \
        runelite-client/src/main/java/net/runelite/client/plugins/recorder/worldmap/RegionChunkSnapshot.java
git commit -m "feat(worldmap): RegionChunk{Builder,Snapshot} mutable/immutable pair"
```

### Task 1.5 — `RegionChunkSnapshot` (immutable, public)

**Files:**
- Create: `recorder/worldmap/RegionChunkSnapshot.java`
- Test: `recorder/worldmap/RegionChunkSnapshotTest.java`

- [ ] **Step 1: Write the test (no execution)**

```java
package net.runelite.client.plugins.recorder.worldmap;

import java.util.Collections;
import java.util.List;
import org.junit.Test;
import net.runelite.api.coords.WorldPoint;
import static org.junit.Assert.*;

public class RegionChunkSnapshotTest
{
    @Test
    public void packAndUnpackTileKey_roundTrips()
    {
        long k = RegionChunkSnapshot.packTileKey(3208, 3213, 0);
        assertEquals(3208, RegionChunkSnapshot.unpackX(k));
        assertEquals(3213, RegionChunkSnapshot.unpackY(k));
        assertEquals(0,    RegionChunkSnapshot.unpackPlane(k));
    }

    @Test
    public void emptySnapshot_hasNoTiles()
    {
        RegionChunkSnapshot s = RegionChunkSnapshot.empty(12850);
        assertEquals(12850, s.regionId());
        assertEquals(0, s.tiles().size());
        assertEquals(0, s.objects().size());
        assertNull(s.tile(3208, 3213, 0));
    }

    @Test
    public void snapshotFromBuilder_exposesUnmodifiableViews()
    {
        RegionChunkBuilder b = new RegionChunkBuilder(12850);
        b.setTile(3208, 3213, 0, 0);
        RegionChunkSnapshot s = RegionChunkSnapshot.fromBuilder(b);

        // Tile is queryable.
        RegionChunkSnapshot.TileEntry t = s.tile(3208, 3213, 0);
        assertNotNull(t);
        assertEquals(0, t.movement);

        // Builder mutation does NOT leak to snapshot.
        b.setTile(3209, 3213, 0, 999);
        assertNull(s.tile(3209, 3213, 0));
    }
}
```

- [ ] **Step 2: Write the class**

```java
package net.runelite.client.plugins.recorder.worldmap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.runelite.api.coords.WorldPoint;

/**
 * Immutable snapshot of a region chunk's collision + object data. The planner
 * reads only this type; the builder ({@link RegionChunkBuilder}) is
 * package-private and only the scraper writes it.
 *
 * <p>Stored as primitive maps to keep the snapshot construction cheap and
 * the planner's reads cache-friendly. {@link TileEntry} is exposed for
 * iteration; per-coord lookups are O(1) via {@link #tile}.
 */
public final class RegionChunkSnapshot
{
    private final int regionId;
    private final int gameRevision;
    private final long lastScrapedAt;
    private final Map<Long, Integer> tiles;     // packedKey → movement int
    private final List<EntitySighting> objects;

    private RegionChunkSnapshot(int regionId, int gameRevision, long lastScrapedAt,
                                Map<Long, Integer> tiles, List<EntitySighting> objects)
    {
        this.regionId = regionId;
        this.gameRevision = gameRevision;
        this.lastScrapedAt = lastScrapedAt;
        this.tiles = tiles;
        this.objects = objects;
    }

    public static RegionChunkSnapshot empty(int regionId)
    {
        return new RegionChunkSnapshot(regionId, 0, 0L,
            Collections.emptyMap(), Collections.emptyList());
    }

    static RegionChunkSnapshot fromBuilder(RegionChunkBuilder b)
    {
        // Defensive copies — guarantee the snapshot is fully detached
        // from any future mutation of the builder.
        Map<Long, Integer> tilesCopy = new HashMap<>(b.tiles);
        List<EntitySighting> objCopy = new ArrayList<>(b.objects.values());
        return new RegionChunkSnapshot(b.regionId, b.gameRevision, b.lastScrapedAt,
            Collections.unmodifiableMap(tilesCopy),
            Collections.unmodifiableList(objCopy));
    }

    public int regionId() { return regionId; }
    public int gameRevision() { return gameRevision; }
    public long lastScrapedAt() { return lastScrapedAt; }

    public List<TileEntry> tiles()
    {
        ArrayList<TileEntry> out = new ArrayList<>(tiles.size());
        for (Map.Entry<Long, Integer> e : tiles.entrySet())
        {
            long k = e.getKey();
            int movement = e.getValue();
            // los is reserved/always-0 in v1 (LOS bits live inside `movement`).
            out.add(new TileEntry(unpackX(k), unpackY(k), unpackPlane(k), movement, 0));
        }
        return Collections.unmodifiableList(out);
    }

    public List<EntitySighting> objects() { return objects; }

    /** Tile at (x,y,plane) or null if not in this chunk. */
    @Nullable
    public TileEntry tile(int x, int y, int plane)
    {
        Integer movement = tiles.get(packTileKey(x, y, plane));
        if (movement == null) return null;
        return new TileEntry(x, y, plane, movement, 0);
    }

    /** True if a tile exists in this chunk and is "standable" per the spec.
     *  v1 definition: tile exists and the movement int does not have the
     *  BLOCK_MOVEMENT_FULL bit-set (which is the OR of three engine bits —
     *  do NOT hard-code 0x40000; that's only one of the three).
     *
     *  <p>The "at least one neighbour can travel in" check is delegated to
     *  MapAStar's transition predicate at planner time — this method only
     *  handles the local "exists + not floor-blocked" portion. */
    public boolean isStandableLocal(int x, int y, int plane)
    {
        TileEntry t = tile(x, y, plane);
        if (t == null) return false;
        return (t.movement & net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_FULL) == 0;
    }

    public static long packTileKey(int x, int y, int plane)
    {
        // 24 bits x, 24 bits y, 4 bits plane.
        return ((long)(x & 0xffffff) << 28)
            |  ((long)(y & 0xffffff) << 4)
            |  (plane & 0xf);
    }

    public static int unpackX(long k)     { return (int)((k >> 28) & 0xffffff); }
    public static int unpackY(long k)     { return (int)((k >> 4)  & 0xffffff); }
    public static int unpackPlane(long k) { return (int)(k & 0xf); }

    public static final class TileEntry
    {
        public final int x, y, plane, movement, los;
        public TileEntry(int x, int y, int plane, int movement, int los)
        {
            this.x = x; this.y = y; this.plane = plane;
            this.movement = movement; this.los = los;
        }
        public WorldPoint asWorldPoint() { return new WorldPoint(x, y, plane); }
    }
}
```

- [ ] **Step 3: Commit (combined with Task 1.4)**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/worldmap/RegionChunkBuilder.java \
        runelite-client/src/main/java/net/runelite/client/plugins/recorder/worldmap/RegionChunkSnapshot.java \
        runelite-client/src/test/java/net/runelite/client/plugins/recorder/worldmap/RegionChunkSnapshotTest.java
git commit -m "feat(worldmap): RegionChunk{Builder,Snapshot} pair with packed tiles"
```

### Task 1.6 — `MapStore` (in-memory cache + AtomicReference)

**Files:**
- Create: `recorder/worldmap/MapStore.java`

- [ ] **Step 1: Write the class**

The store owns one `AtomicReference<RegionChunkSnapshot>` per region. The scraper calls `builderFor(regionId)`, mutates, then `publish(regionId, builder)` which atomically swaps in a fresh snapshot. Readers call `snapshotFor(regionId)`. Eviction is LRU by access (planner reads count; flush daemon does not).

```java
package net.runelite.client.plugins.recorder.worldmap;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;

/**
 * Process-wide store for {@link RegionChunkSnapshot}s. The scraper writes
 * via {@link #publish}; readers (the planner) call {@link #snapshotFor}.
 *
 * <p>Concurrency model: each region's snapshot lives in its own
 * {@link AtomicReference}. Publication is a single {@code set()} on a
 * fully-constructed snapshot. Readers see either the previous or the next
 * snapshot — never a torn intermediate.
 *
 * <p>Eviction: bounded LRU by recent reader access. Hot regions stay
 * resident; cold regions are evicted when the cap is hit. Eviction does
 * NOT delete persisted JSON — only frees in-memory state.
 */
public final class MapStore
{
    private final WorldMemoryConfig config;
    private final Map<Integer, AtomicReference<RegionChunkSnapshot>> snapshots
        = new ConcurrentHashMap<>();
    private final LinkedHashMap<Integer, Long> accessOrder
        = new LinkedHashMap<>(16, 0.75f, true);
    private final Object accessLock = new Object();
    /** Region IDs that have a published snapshot newer than the last flush. */
    private final java.util.Set<Integer> dirtyRegionIds
        = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public MapStore(WorldMemoryConfig config)
    {
        this.config = config;
    }

    /** Return the current snapshot for {@code regionId}, or null if no
     *  snapshot has ever been published for it. */
    @Nullable
    public RegionChunkSnapshot snapshotFor(int regionId)
    {
        AtomicReference<RegionChunkSnapshot> ref = snapshots.get(regionId);
        if (ref == null) return null;
        synchronized (accessLock) { accessOrder.put(regionId, System.nanoTime()); }
        return ref.get();
    }

    /** Get a builder for an in-progress scrape. If a snapshot already
     *  exists for this region, the builder starts as a copy of it (so
     *  partial scrapes overlay onto existing data instead of clearing it).
     *
     *  <p>MUST be called only on the client thread (scraper context).
     *  The returned builder is single-threaded; the caller must publish
     *  it via {@link #publish} before returning to the worker pool.
     *
     *  <p>Cost note: this allocates a fresh map and copies all tiles from
     *  the prior snapshot. With ~4096 tiles per region and 8 bytes per
     *  entry, that's ~32KB per scrape × 2-tick cadence ~= 27KB/s of
     *  allocator pressure. Validated tolerable per spec; profile if it
     *  ever shows up as a hotspot. */
    public RegionChunkBuilder builderFor(int regionId)
    {
        AtomicReference<RegionChunkSnapshot> ref = snapshots.get(regionId);
        if (ref == null || ref.get() == null)
        {
            return new RegionChunkBuilder(regionId);
        }
        return RegionChunkBuilder.copyOf(ref.get());
    }

    /** Publish a fresh snapshot atomically. The next call to
     *  {@link #snapshotFor} sees the new data; readers in flight finish
     *  on the previous snapshot. Marks the region dirty for the flush daemon. */
    public void publish(int regionId, RegionChunkBuilder builder)
    {
        RegionChunkSnapshot snap = RegionChunkSnapshot.fromBuilder(builder);
        AtomicReference<RegionChunkSnapshot> ref = snapshots.computeIfAbsent(
            regionId, k -> new AtomicReference<>());
        ref.set(snap);
        dirtyRegionIds.add(regionId);
        evictIfOver();
    }

    /** Atomically take and clear the dirty-region set. Called by the flush
     *  daemon — the regions returned will be persisted; any region
     *  re-published during/after the flush is re-added by the next
     *  publish() call. Race-safe by the ConcurrentHashMap.newKeySet
     *  semantics: removeAll + add operations are independent atomic ops. */
    public java.util.Set<Integer> takeDirtyRegionIds()
    {
        java.util.Set<Integer> snapshot = new java.util.HashSet<>(dirtyRegionIds);
        dirtyRegionIds.removeAll(snapshot);
        return snapshot;
    }

    /** Bootstrap load: read the persisted JSON for {@code regionId} (if any)
     *  and install it as the active snapshot. Called from RecorderPlugin's
     *  startup thread. Returns true iff a snapshot was loaded (and thus
     *  installed). */
    public boolean loadFromDisk(File rootDir, int regionId)
    {
        RegionChunkSnapshot loaded = MapStoreIO.readRegion(rootDir, regionId);
        if (loaded.tiles().isEmpty()) return false;
        snapshots.computeIfAbsent(regionId, k -> new AtomicReference<>()).set(loaded);
        synchronized (accessLock) { accessOrder.put(regionId, System.nanoTime()); }
        return true;
    }

    /** Test-only: directly install a snapshot. Used by fixture-driven tests
     *  to skip the scraper. Package-private — production callers should use
     *  loadFromDisk() instead. */
    void installSnapshotForTest(int regionId, RegionChunkSnapshot snap)
    {
        snapshots.computeIfAbsent(regionId, k -> new AtomicReference<>()).set(snap);
        synchronized (accessLock) { accessOrder.put(regionId, System.nanoTime()); }
    }

    private void evictIfOver()
    {
        synchronized (accessLock)
        {
            while (accessOrder.size() > config.memoryResidentChunkCount)
            {
                Integer oldest = accessOrder.keySet().iterator().next();
                accessOrder.remove(oldest);
                snapshots.remove(oldest);
                // Note: dirty bit not cleared on eviction — if a chunk was dirty
                // and gets evicted, the flush daemon's next pass picks up an empty
                // snapshot lookup and skips it. That's acceptable: data was
                // re-derivable from the next live scrape.
            }
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/worldmap/MapStore.java
git commit -m "feat(worldmap): MapStore with AtomicReference snapshots + LRU eviction"
```

### Task 1.7 — `MapStoreIO` (JSON read/write)

**Files:**
- Create: `recorder/worldmap/MapStoreIO.java`
- Test: `recorder/worldmap/MapStoreIOTest.java`

This wraps Gson against the schema in the spec. Mirror `trail/TrailIO`'s pattern. v1 readers ignore unknown fields (Gson is lenient by default; verify when implementing).

- [ ] **Step 1: Write the test**

```java
package net.runelite.client.plugins.recorder.worldmap;

import java.io.File;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class MapStoreIOTest
{
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void writeThenRead_roundTripsTilesAndObjects() throws Exception
    {
        File dir = tmp.newFolder();
        RegionChunkBuilder b = new RegionChunkBuilder(12850);
        b.gameRevision = 238;
        b.lastScrapedAt = 1714960000000L;
        b.setTile(3208, 3213, 0, 0x0);
        b.setTile(3209, 3214, 0, net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_FULL);  // floor-blocked (range tile)
        RegionChunkSnapshot snap = RegionChunkSnapshot.fromBuilder(b);

        MapStoreIO.writeRegion(dir, snap);
        RegionChunkSnapshot reloaded = MapStoreIO.readRegion(dir, 12850);

        assertEquals(238, reloaded.gameRevision());
        assertEquals(2, reloaded.tiles().size());
        assertNotNull(reloaded.tile(3208, 3213, 0));
        assertEquals(0x40000, reloaded.tile(3209, 3214, 0).movement);
    }

    @Test
    public void readMissingRegion_returnsEmpty() throws Exception
    {
        File dir = tmp.newFolder();
        RegionChunkSnapshot s = MapStoreIO.readRegion(dir, 99999);
        assertEquals(99999, s.regionId());
        assertEquals(0, s.tiles().size());
    }

    @Test
    public void readJsonWithUnknownField_ignoresIt() throws Exception
    {
        File dir = tmp.newFolder();
        File regionsDir = new File(dir, "regions");
        regionsDir.mkdirs();
        File f = new File(regionsDir, "12850.json");
        // Hand-write JSON with an unknown future field.
        Files.writeString(f.toPath(),
            "{\"schema\":1,\"regionId\":12850,\"gameRevision\":238,"
            + "\"lastScrapedAt\":1,\"tiles\":[],\"objects\":[],"
            + "\"futureField\":\"ignored\"}");
        RegionChunkSnapshot s = MapStoreIO.readRegion(dir, 12850);
        assertEquals(238, s.gameRevision());
    }
}
```

- [ ] **Step 2: Write the impl**

`MapStoreIO` is a single class with these public static methods:

```java
public static void writeRegion(File rootDir, RegionChunkSnapshot snap);
public static RegionChunkSnapshot readRegion(File rootDir, int regionId);
public static void writeEntities(File rootDir, int regionId, List<EntitySighting> npcs);
public static List<EntitySighting> readEntities(File rootDir, int regionId);
public static RegionChunkSnapshot readFixture(String resourceName);   // test-only; reads from classpath
```

Use Gson with intermediate POJO classes (`RegionFileJson`, `TileJson`, `ObjectJson`, `EntitiesFileJson`, `NpcJson`) to keep mapping straightforward. Persistence root: `<rootDir>/regions/<regionId>.json` and `<rootDir>/entities/<regionId>.json`.

Schema details:
- Tile JSON has fields `x, y, plane, movement, los` — write `los: 0` literal (reserved for v2).
- Object JSON has fields `id, name, x, y, plane, actions, seenCount, lastSeenAt`.
- NPC JSON has fields `id, name, lastTile.{x,y,plane}, seenCount, lastSeenAt`.

JSON forward-compat: Gson is lenient by default — unknown fields are silently ignored when reading. No special config needed.

`readFixture(String resourceName)` reads from the classpath: `getClass().getResourceAsStream("/worldmap/fixtures/" + resourceName)`. Used by tests; the fixture files live under `src/test/resources/worldmap/fixtures/`.

(Subagent: read `trail/TrailIO.java` for the existing Gson pattern in this repo — adopt the same style: Gson singleton, `Files.writeString` for writes, `try-with-resources` Reader for reads, log a warning on parse failure but return `empty` so the runtime continues. The same Gson singleton can serve both region and entity files.)

- [ ] **Step 3: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/worldmap/MapStoreIO.java \
        runelite-client/src/test/java/net/runelite/client/plugins/recorder/worldmap/MapStoreIOTest.java
git commit -m "feat(worldmap): MapStoreIO JSON read/write with forward-compat"
```

---

## Phase 2 — Scrapers + entity index

**Within-phase ordering:** Task 2.3 (`EntityIndex`) lands first because Task 2.2 (`EntityScraper.scanNpcs`) calls `index.recordNpcSighting`. After 2.3, Tasks 2.1 (`SceneScraper`) and 2.2 (`EntityScraper`) are parallel-safe.

```
2.3 EntityIndex     ──┐
                      │ unblocks
                      ▼
        2.1 SceneScraper  ║  2.2 EntityScraper      (parallel)
```

### Task 2.1 — `SceneScraper` (client-thread tile + object scrape)

**Files:**
- Create: `recorder/worldmap/SceneScraper.java`

Per the spec — runs on the client thread, reads `wv.getCollisionMaps()` for the 18×18 window centered on the player, writes per-tile `movement` to a `RegionChunkBuilder` per region the window touches, then calls `MapStore.publish` for each. Also enumerates game objects in the window via `Tile.getGameObjects()` / `Tile.getWallObject()` / etc., recording any with non-null actions.

- [ ] **Step 1: Write the class**

The scraper has one entry point, `scan(WorldView wv, long now)`, called from the recorder plugin's tick subscriber.

Structure:
1. Compute the 18×18 window in world coords around the player.
2. Group tiles by region. Per region, fetch `MapStore.builderFor(regionId)`.
3. For each tile in the window:
   - Read `WorldArea.canTravelInDirection(wv, dx, dy)`-equivalent flags via `wv.getCollisionMaps()[plane].getFlags()[localX][localY]`.
   - `setTile(x, y, plane, movement)` — LOS bits live inside the `movement` int per the spec.
4. For each loaded scene tile in the window: enumerate `gameObjects()`, `wallObject()`, `decorativeObject()`, `groundObject()`. For each non-null, look up `ObjectComposition` via `Client.getObjectDefinition(id).getActions()`. If actions has any non-null entry, call `builder.recordObject(id, name, tile, actions, now)`.
5. Call `MapStore.publish(regionId, builder)` for each region touched.

Time-budget: if total elapsed > `WorldMemoryConfig.scrapeBudgetNanos`, abort. Do NOT call `publish` on partially-built builders — the spec says skipped scrapes don't advance `lastScrapedAt`.

Skip scrape entirely if `Scene.isInstance()` (per spec — instance regions don't persist).

(Subagent: see `recorder/scene/SceneScanner.java` for the existing pattern of enumerating scene tiles + objects. Reuse the same patterns; do not reinvent.)

- [ ] **Step 2: Write a smoke test**

A unit test for SceneScraper requires mocking `WorldView`/`Scene`/`Client`, which is fragile. Instead, ship a small **integration smoke test** (no mocks) that constructs a `MapStore`, then a fake builder, and verifies that publishing builders with different regionIds creates independent snapshots. Real scene-driven testing will be a manual proof at A/B test time.

```java
package net.runelite.client.plugins.recorder.worldmap;

import org.junit.Test;
import static org.junit.Assert.*;

public class SceneScraperSmokeTest
{
    @Test
    public void mapStore_publishedRegions_areIsolated()
    {
        MapStore store = new MapStore(new WorldMemoryConfig());
        RegionChunkBuilder a = store.builderFor(12850);
        a.setTile(3208, 3213, 0, 0);
        store.publish(12850, a);

        RegionChunkBuilder b = store.builderFor(12851);
        b.setTile(3272, 3213, 0, 0);
        store.publish(12851, b);

        assertEquals(1, store.snapshotFor(12850).tiles().size());
        assertEquals(1, store.snapshotFor(12851).tiles().size());
        assertNull(store.snapshotFor(12850).tile(3272, 3213, 0));
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/worldmap/SceneScraper.java \
        runelite-client/src/test/java/net/runelite/client/plugins/recorder/worldmap/SceneScraperSmokeTest.java
git commit -m "feat(worldmap): SceneScraper writes 18x18 window to MapStore"
```

### Task 2.2 — `EntityScraper` (NPC sightings)

**Files:**
- Create: `recorder/worldmap/EntityScraper.java`

Smaller than SceneScraper. One entry point `scanNpcs(WorldView wv, long now, EntityIndex index)`.

- [ ] **Step 1: Write the class**

```java
package net.runelite.client.plugins.recorder.worldmap;

import net.runelite.api.NPC;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;

/** Sweeps the loaded NPC list each tick and records the most-recent
 *  sighting per (id, region) into the {@link EntityIndex}. Called on the
 *  client thread from the recorder plugin's tick subscriber. */
public final class EntityScraper
{
    public void scanNpcs(WorldView wv, long now, EntityIndex index)
    {
        if (wv == null) return;
        for (NPC npc : wv.npcs())
        {
            if (npc == null) continue;
            String name = npc.getName();
            if (name == null || name.isEmpty()) continue;
            WorldPoint loc = npc.getWorldLocation();
            if (loc == null) continue;
            index.recordNpcSighting(npc.getId(), name, loc, now);
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/worldmap/EntityScraper.java
git commit -m "feat(worldmap): EntityScraper records NPC sightings to EntityIndex"
```

### Task 2.3 — `EntityIndex` (lookup by name)

**Files:**
- Create: `recorder/worldmap/EntityIndex.java`
- Test: `recorder/worldmap/EntityIndexTest.java`

In-memory by-name lookup. Persisted via the `entities/<regionId>.json` files at flush time (Phase 4 wiring). Object sightings are also indexed here — fed from `RegionChunkBuilder.recordObject` callbacks at publish time.

- [ ] **Step 1: Write the test**

```java
package net.runelite.client.plugins.recorder.worldmap;

import java.util.List;
import java.util.Optional;
import org.junit.Test;
import net.runelite.api.coords.WorldPoint;
import static org.junit.Assert.*;

public class EntityIndexTest
{
    @Test
    public void findNpcsByName_returnsAllSightings()
    {
        EntityIndex idx = new EntityIndex();
        idx.recordNpcSighting(4626, "Cook", new WorldPoint(3208, 3213, 0), 1000);
        idx.recordNpcSighting(4626, "Cook", new WorldPoint(3211, 3214, 0), 2000);
        idx.recordNpcSighting(0,    "Goblin", new WorldPoint(3252, 3253, 0), 3000);

        List<EntitySighting> cooks = idx.findNpcsByName("Cook");
        assertEquals(1, cooks.size());           // single id-keyed entry, updated
        assertEquals(2, cooks.get(0).seenCount);
        assertEquals(2000, cooks.get(0).lastSeenAt);
        assertEquals(new WorldPoint(3211, 3214, 0), cooks.get(0).lastTile);
    }

    @Test
    public void nearestNpc_breaksTiesByRecency()
    {
        EntityIndex idx = new EntityIndex();
        // Two different Cook NPCs at different tiles.
        idx.recordNpcSighting(4626, "Cook", new WorldPoint(3208, 3213, 0), 1000);
        idx.recordNpcSighting(9999, "Cook", new WorldPoint(3300, 3300, 0), 2000);

        Optional<EntitySighting> nearest = idx.nearestNpc("Cook",
            new WorldPoint(3210, 3215, 0));
        assertTrue(nearest.isPresent());
        assertEquals(4626, nearest.get().id);    // closer NPC wins
    }

    @Test
    public void unknownName_returnsEmpty()
    {
        EntityIndex idx = new EntityIndex();
        assertTrue(idx.findNpcsByName("Nonexistent").isEmpty());
    }
}
```

- [ ] **Step 2: Write the impl**

```java
package net.runelite.client.plugins.recorder.worldmap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.runelite.api.coords.WorldPoint;

/** In-memory NPC + object sighting index. Mirrors persisted state from
 *  entities/*.json + regions/*.json (objects[]).
 *
 *  Thread-safety: scraper writes on the client thread; planner reads on
 *  the worker thread. byKey is a ConcurrentHashMap; byName uses
 *  CopyOnWriteArrayList per name (writes are infrequent compared to
 *  reads, so the COW cost is dominated by the read benefit); byRegion
 *  uses ConcurrentHashMap with COW lists. All reads return a stable view. */
public final class EntityIndex
{
    /** Keyed by (kind, id) — one entry per concrete NPC/object id, updated
     *  in-place. */
    private final Map<Long, EntitySighting> byKey = new ConcurrentHashMap<>();
    /** Name → list of byKey keys, most-recent first. */
    private final Map<String, java.util.concurrent.CopyOnWriteArrayList<Long>> byName
        = new ConcurrentHashMap<>();
    /** RegionId → list of byKey keys whose lastTile is in that region.
     *  Used by the flush daemon to write entities/<regionId>.json. */
    private final Map<Integer, java.util.concurrent.CopyOnWriteArrayList<Long>> byRegion
        = new ConcurrentHashMap<>();
    /** Region IDs whose entity list has changed since the last flush. */
    private final java.util.Set<Integer> dirtyRegions
        = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public void recordNpcSighting(int id, String name, WorldPoint tile, long now)
    {
        record(EntitySighting.Kind.NPC, id, name, tile, now);
    }

    public void recordObjectSighting(int id, String name, WorldPoint tile, long now)
    {
        record(EntitySighting.Kind.OBJECT, id, name, tile, now);
    }

    private void record(EntitySighting.Kind kind, int id, String name,
                        WorldPoint tile, long now)
    {
        long key = ((long) kind.ordinal() << 32) | (id & 0xffffffffL);
        EntitySighting prev = byKey.get(key);
        EntitySighting next = (prev == null)
            ? new EntitySighting(kind, id, name, tile, 1, now)
            : prev.withUpdatedSighting(tile, now);
        byKey.put(key, next);
        byName.computeIfAbsent(name,
            k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(0, key);
        int regionId = RegionIds.regionIdFor(tile.getX(), tile.getY());
        byRegion.computeIfAbsent(regionId,
            k -> new java.util.concurrent.CopyOnWriteArrayList<>());
        if (!byRegion.get(regionId).contains(key))
        {
            byRegion.get(regionId).add(key);
        }
        dirtyRegions.add(regionId);
    }

    public List<EntitySighting> findNpcsByName(String name)
    {
        return findByName(name, EntitySighting.Kind.NPC);
    }

    public List<EntitySighting> findObjectsByName(String name)
    {
        return findByName(name, EntitySighting.Kind.OBJECT);
    }

    private List<EntitySighting> findByName(String name, EntitySighting.Kind kind)
    {
        List<Long> keys = byName.get(name);
        if (keys == null) return List.of();
        List<EntitySighting> out = new ArrayList<>();
        for (Long k : keys)
        {
            EntitySighting s = byKey.get(k);
            if (s != null && s.kind == kind) out.add(s);
        }
        out.sort(Comparator.comparingLong((EntitySighting s) -> s.lastSeenAt).reversed());
        return out;
    }

    /** All NPC sightings in {@code regionId}. Used by the flush daemon. */
    public List<EntitySighting> npcsInRegion(int regionId)
    {
        List<Long> keys = byRegion.get(regionId);
        if (keys == null) return List.of();
        List<EntitySighting> out = new ArrayList<>();
        for (Long k : keys)
        {
            EntitySighting s = byKey.get(k);
            if (s != null && s.kind == EntitySighting.Kind.NPC) out.add(s);
        }
        return out;
    }

    /** Atomically take and clear the dirty-region set for entities. */
    public java.util.Set<Integer> takeDirtyRegionIds()
    {
        java.util.Set<Integer> snapshot = new java.util.HashSet<>(dirtyRegions);
        dirtyRegions.removeAll(snapshot);
        return snapshot;
    }

    public Optional<EntitySighting> nearestNpc(String name, WorldPoint from)
    {
        return nearest(findNpcsByName(name), from);
    }

    public Optional<EntitySighting> nearestObject(String name, WorldPoint from)
    {
        return nearest(findObjectsByName(name), from);
    }

    private Optional<EntitySighting> nearest(List<EntitySighting> sightings, WorldPoint from)
    {
        if (sightings.isEmpty()) return Optional.empty();
        EntitySighting best = null;
        int bestDist = Integer.MAX_VALUE;
        long bestSeen = -1;
        for (EntitySighting s : sightings)
        {
            int d = chebyshev(s.lastTile, from);
            if (d < bestDist || (d == bestDist && s.lastSeenAt > bestSeen))
            {
                best = s; bestDist = d; bestSeen = s.lastSeenAt;
            }
        }
        return Optional.of(best);
    }

    private static int chebyshev(WorldPoint a, WorldPoint b)
    {
        return Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getY() - b.getY()));
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/worldmap/EntityIndex.java \
        runelite-client/src/test/java/net/runelite/client/plugins/recorder/worldmap/EntityIndexTest.java
git commit -m "feat(worldmap): EntityIndex by-name NPC/object sighting lookup"
```

---

## Phase 3 — Planner (A*, LOS, MapPlanner)

Tasks 3.1, 3.2, 3.3 are **parallel-safe within Phase 3** until Task 3.4 (which depends on 3.1 and 3.2).

### Task 3.1 — `Bresenham` LOS line walker

**Files:**
- Create: `recorder/worldmap/Bresenham.java`
- Test: `recorder/worldmap/BresenhamLosTest.java`

The LOS check walks a Bresenham line from the candidate tile to the target, and at each step bit-tests the `BLOCK_LINE_OF_SIGHT_*` flags from `RegionChunkSnapshot.tile(x, y, plane).movement`. Constants are in `runelite-api/.../CollisionDataFlag.java`.

- [ ] **Step 1: Write the test**

Use a small fixture inline. Layout (top-down, plane 0):

```
(3208,3215) . . . .
(3208,3214) . W . .       W = wall blocking south LOS at (3209,3214)
(3208,3213) . . . T       T = target
(3208,3212) S . . .       S = candidate stand tile
```

If we test LOS from S=(3208,3212) to T=(3211,3213) with a wall at (3209,3214), the Bresenham walk passes through (3209,3213) — should be open. So this isn't a useful test. Adjust:

```
. . . T
. W . .       W blocks east-LOS (so visibility from west is blocked)
. . . .
S . . .
```

```java
package net.runelite.client.plugins.recorder.worldmap;

import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class BresenhamLosTest
{
    @Test
    public void straightLine_noWalls_hasLineOfSight()
    {
        // Empty 3×3, no walls.
        RegionChunkBuilder b = new RegionChunkBuilder(0);
        for (int x = 0; x < 3; x++)
            for (int y = 0; y < 3; y++)
                b.setTile(x, y, 0, 0);
        RegionChunkSnapshot s = RegionChunkSnapshot.fromBuilder(b);

        assertTrue(Bresenham.hasLineOfSight(s,
            new WorldPoint(0, 0, 0), new WorldPoint(2, 2, 0)));
    }

    @Test
    public void wallBetween_blocksLineOfSight()
    {
        RegionChunkBuilder b = new RegionChunkBuilder(0);
        for (int x = 0; x < 3; x++)
            for (int y = 0; y < 3; y++)
                b.setTile(x, y, 0, 0);
        // Place an LOS-blocking flag on the middle tile.
        b.setTile(1, 1, 0, net.runelite.api.CollisionDataFlag.BLOCK_LINE_OF_SIGHT_FULL);
        RegionChunkSnapshot s = RegionChunkSnapshot.fromBuilder(b);

        assertFalse(Bresenham.hasLineOfSight(s,
            new WorldPoint(0, 0, 0), new WorldPoint(2, 2, 0)));
    }
}
```

(Subagent: read `runelite-api/src/main/java/net/runelite/api/CollisionDataFlag.java` to confirm exact bit names — `BLOCK_LINE_OF_SIGHT_FULL`, `BLOCK_LINE_OF_SIGHT_NORTH/EAST/SOUTH/WEST`. The test uses `BLOCK_LINE_OF_SIGHT_FULL` for simplicity.)

- [ ] **Step 2: Write the impl**

```java
package net.runelite.client.plugins.recorder.worldmap;

import net.runelite.api.CollisionDataFlag;
import net.runelite.api.coords.WorldPoint;

/** Bresenham line-of-sight walker over a {@link RegionChunkSnapshot}'s
 *  collision flags. Mirror's RuneLite's own LOS algorithm but operates on
 *  persisted flags rather than a live WorldView — so it runs on the worker
 *  thread without a client-thread hop. */
public final class Bresenham
{
    private Bresenham() {}

    public static boolean hasLineOfSight(RegionChunkSnapshot snap,
                                         WorldPoint from, WorldPoint to)
    {
        if (from == null || to == null) return false;
        if (from.getPlane() != to.getPlane()) return false;
        int x0 = from.getX(), y0 = from.getY();
        int x1 = to.getX(),   y1 = to.getY();
        int plane = from.getPlane();

        int dx = Math.abs(x1 - x0), sx = x0 < x1 ? 1 : -1;
        int dy = -Math.abs(y1 - y0), sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;

        int cx = x0, cy = y0;
        while (true)
        {
            if (!(cx == x0 && cy == y0) && !(cx == x1 && cy == y1))
            {
                RegionChunkSnapshot.TileEntry t = snap.tile(cx, cy, plane);
                // Tile not scraped → conservatively treat as "no LOS" so
                // we don't walk into unknown territory expecting visibility.
                if (t == null) return false;
                if ((t.movement & CollisionDataFlag.BLOCK_LINE_OF_SIGHT_FULL) != 0)
                    return false;
            }
            if (cx == x1 && cy == y1) return true;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; cx += sx; }
            if (e2 <= dx) { err += dx; cy += sy; }
        }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/worldmap/Bresenham.java \
        runelite-client/src/test/java/net/runelite/client/plugins/recorder/worldmap/BresenhamLosTest.java
git commit -m "feat(worldmap): Bresenham LOS over RegionChunkSnapshot"
```

### Task 3.2 — `MapAStar` (multi-target Dijkstra with caps)

**Files:**
- Create: `recorder/worldmap/MapAStar.java`
- Test: `recorder/worldmap/MapAStarTest.java`

Per the spec — single-pass Dijkstra from `origin` that terminates when every tile in the `goals` set is either visited or beyond the caps. Returns `Map<WorldPoint, Integer>` for goals, value = path length or -1 if unreachable.

Wall/diagonal logic: replicate RuneLite's `WorldArea.canTravelInDirection` semantics over the snapshot's `movement` int. Per spec, **don't** build a `WorldView` adapter — unpack flags ourselves.

- [ ] **Step 1: Write the test**

```java
package net.runelite.client.plugins.recorder.worldmap;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import net.runelite.api.coords.WorldPoint;
import static org.junit.Assert.*;

public class MapAStarTest
{
    @Test
    public void straightShot_4tiles_reachableInDist4()
    {
        // 5×1 corridor, no walls.
        RegionChunkBuilder b = new RegionChunkBuilder(0);
        for (int x = 0; x < 5; x++) b.setTile(x, 0, 0, 0);
        RegionChunkSnapshot s = RegionChunkSnapshot.fromBuilder(b);

        WorldPoint origin = new WorldPoint(0, 0, 0);
        Set<WorldPoint> goals = Set.of(new WorldPoint(4, 0, 0));
        Map<WorldPoint, Integer> dist = MapAStar.dijkstraToAny(
            s, origin, goals, 128, 10_000);

        assertEquals(Integer.valueOf(4), dist.get(new WorldPoint(4, 0, 0)));
    }

    @Test
    public void unreachableGoal_returnsMinusOne()
    {
        // Two disconnected components separated by full-block tiles.
        // Origin in left, goal in right, no path.
        RegionChunkBuilder b = new RegionChunkBuilder(0);
        for (int x = 0; x < 5; x++) b.setTile(x, 0, 0, 0);
        // Wall the corridor at x=2.
        b.setTile(2, 0, 0, net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_FULL);
        RegionChunkSnapshot s = RegionChunkSnapshot.fromBuilder(b);

        WorldPoint origin = new WorldPoint(0, 0, 0);
        Set<WorldPoint> goals = Set.of(new WorldPoint(4, 0, 0));
        Map<WorldPoint, Integer> dist = MapAStar.dijkstraToAny(
            s, origin, goals, 128, 10_000);

        assertEquals(Integer.valueOf(-1), dist.get(new WorldPoint(4, 0, 0)));
    }

    @Test
    public void multiGoal_returnsDistsForAllGoals_inOnePass()
    {
        // 5×1 corridor.
        RegionChunkBuilder b = new RegionChunkBuilder(0);
        for (int x = 0; x < 5; x++) b.setTile(x, 0, 0, 0);
        RegionChunkSnapshot s = RegionChunkSnapshot.fromBuilder(b);

        WorldPoint origin = new WorldPoint(0, 0, 0);
        Set<WorldPoint> goals = Set.of(
            new WorldPoint(2, 0, 0),
            new WorldPoint(4, 0, 0));
        Map<WorldPoint, Integer> dist = MapAStar.dijkstraToAny(
            s, origin, goals, 128, 10_000);

        assertEquals(Integer.valueOf(2), dist.get(new WorldPoint(2, 0, 0)));
        assertEquals(Integer.valueOf(4), dist.get(new WorldPoint(4, 0, 0)));
    }
}
```

- [ ] **Step 2: Write the impl (full)**

The Dijkstra walks 8-connected neighbors. For each step, check whether the snapshot's flags allow the move using the same masks `WorldArea.canTravelInDirection` builds. **Use named constants from `CollisionDataFlag` — never hex literals.** (Plan QC found the diagonal constants flipped in an earlier draft; verifying against `runelite-api/.../CollisionDataFlag.java` once is non-negotiable. Confirmed values: `BLOCK_MOVEMENT_NORTH_WEST=0x1`, `_NORTH=0x2`, `_NORTH_EAST=0x4`, `_EAST=0x8`, `_SOUTH_EAST=0x10`, `_SOUTH=0x20`, `_SOUTH_WEST=0x40`, `_WEST=0x80`, `_OBJECT=0x100`, `_FLOOR_DECORATION=0x40000`, `_FLOOR=0x200000`, `_FULL = OBJECT|FLOOR_DECORATION|FLOOR`. The plan code below uses named constants only.)

```java
package net.runelite.client.plugins.recorder.worldmap;

import java.util.*;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.coords.WorldPoint;

/** Multi-target Dijkstra over a {@link RegionChunkSnapshot}'s collision
 *  flags. Wall/diagonal semantics mirror {@code WorldArea.canTravelInDirection}
 *  (1×1 entity case). Runs on a worker thread; reads only the snapshot. */
public final class MapAStar
{
    private MapAStar() {}

    private static final int[] DX = {0, 0, 1, -1, 1, 1, -1, -1};
    private static final int[] DY = {1, -1, 0, 0, 1, -1, 1, -1};

    /** Dijkstra from {@code origin} that terminates when every tile in
     *  {@code goals} is either visited or beyond the caps. Returns a map
     *  keyed by every input goal — value is the path length, or -1 if
     *  unreachable within the caps. */
    public static Map<WorldPoint, Integer> dijkstraToAny(
        RegionChunkSnapshot snap, WorldPoint origin, Set<WorldPoint> goals,
        int maxPathLength, int maxExpandedTiles)
    {
        Map<WorldPoint, Integer> result = new HashMap<>();
        for (WorldPoint g : goals) result.put(g, -1);
        if (snap == null || origin == null || goals.isEmpty()) return result;

        int plane = origin.getPlane();
        // Confirm origin and all goals share the same plane — required by v1.
        for (WorldPoint g : goals)
        {
            if (g.getPlane() != plane) return result;
        }

        Set<Long> goalKeys = new HashSet<>();
        for (WorldPoint g : goals)
            goalKeys.add(RegionChunkSnapshot.packTileKey(g.getX(), g.getY(), g.getPlane()));
        Set<Long> remainingGoals = new HashSet<>(goalKeys);

        PriorityQueue<long[]> pq = new PriorityQueue<>(
            Comparator.comparingInt(a -> (int) a[0]));
        Map<Long, Integer> dist = new HashMap<>();
        long oKey = RegionChunkSnapshot.packTileKey(
            origin.getX(), origin.getY(), plane);
        dist.put(oKey, 0);
        pq.add(new long[]{0, oKey});

        int expanded = 0;

        while (!pq.isEmpty() && expanded < maxExpandedTiles && !remainingGoals.isEmpty())
        {
            long[] head = pq.poll();
            int d = (int) head[0];
            long k = head[1];
            if (d > dist.getOrDefault(k, Integer.MAX_VALUE)) continue;
            expanded++;

            if (goalKeys.contains(k))
            {
                WorldPoint p = new WorldPoint(
                    RegionChunkSnapshot.unpackX(k),
                    RegionChunkSnapshot.unpackY(k),
                    RegionChunkSnapshot.unpackPlane(k));
                result.put(p, d);
                remainingGoals.remove(k);
            }
            if (d >= maxPathLength) continue;

            int x = RegionChunkSnapshot.unpackX(k);
            int y = RegionChunkSnapshot.unpackY(k);
            for (int i = 0; i < 8; i++)
            {
                int nx = x + DX[i], ny = y + DY[i];
                if (!canTravel(snap, x, y, plane, DX[i], DY[i])) continue;
                long nKey = RegionChunkSnapshot.packTileKey(nx, ny, plane);
                int nd = d + 1;
                if (nd < dist.getOrDefault(nKey, Integer.MAX_VALUE))
                {
                    dist.put(nKey, nd);
                    pq.add(new long[]{nd, nKey});
                }
            }
        }
        return result;
    }

    /** Mirror of {@code WorldArea.canTravelInDirection} for a 1×1 entity.
     *  Builds the same xFlags/yFlags/xyFlags masks WorldArea builds and
     *  tests them against the destination tile's movement int. The OSRS
     *  collision data is dual-encoded (both sides of every wall see it),
     *  so testing only the destination tile is sufficient — see the
     *  WorldArea source at runelite-api/.../coords/WorldArea.java:261-360. */
    private static boolean canTravel(RegionChunkSnapshot snap,
                                     int x, int y, int plane, int dx, int dy)
    {
        dx = Integer.signum(dx);
        dy = Integer.signum(dy);
        if (dx == 0 && dy == 0) return true;

        RegionChunkSnapshot.TileEntry destTile = snap.tile(x + dx, y + dy, plane);
        if (destTile == null) return false;
        int destFlags = destTile.movement;

        int xFlags  = CollisionDataFlag.BLOCK_MOVEMENT_FULL;
        int yFlags  = CollisionDataFlag.BLOCK_MOVEMENT_FULL;
        int xyFlags = CollisionDataFlag.BLOCK_MOVEMENT_FULL;

        if (dx < 0) xFlags |= CollisionDataFlag.BLOCK_MOVEMENT_EAST;
        if (dx > 0) xFlags |= CollisionDataFlag.BLOCK_MOVEMENT_WEST;
        if (dy < 0) yFlags |= CollisionDataFlag.BLOCK_MOVEMENT_NORTH;
        if (dy > 0) yFlags |= CollisionDataFlag.BLOCK_MOVEMENT_SOUTH;

        if (dx < 0 && dy < 0) xyFlags |= CollisionDataFlag.BLOCK_MOVEMENT_NORTH_EAST;
        if (dx < 0 && dy > 0) xyFlags |= CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_EAST;
        if (dx > 0 && dy < 0) xyFlags |= CollisionDataFlag.BLOCK_MOVEMENT_NORTH_WEST;
        if (dx > 0 && dy > 0) xyFlags |= CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_WEST;

        if (dx != 0 && (destFlags & xFlags) != 0) return false;
        if (dy != 0 && (destFlags & yFlags) != 0) return false;
        if (dx != 0 && dy != 0 && (destFlags & xyFlags) != 0) return false;

        return true;
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/worldmap/MapAStar.java \
        runelite-client/src/test/java/net/runelite/client/plugins/recorder/worldmap/MapAStarTest.java
git commit -m "feat(worldmap): MapAStar multi-target Dijkstra with caps"
```

### Task 3.3 — `MapPlanner` (the public API)

**Files:**
- Create: `recorder/worldmap/MapPlanner.java`
- Test: `recorder/worldmap/MapPlannerTest.java`

Three public methods per the spec:
- `findInteractTile(player, target, R, requireLOS) → Optional<WorldPoint>`
- `planWithin(player, goal) → Optional<PathSpec>`
- `planToInteractTile(player, target, R, requireLOS, name) → Optional<PathSpec>`

The output `PathSpec` shape is **1×1 `WALK_AREA`** around the chosen tile (per F5 in spec QC) — not a bare `WALK`.

- [ ] **Step 1: Write the test**

```java
package net.runelite.client.plugins.recorder.worldmap;

import java.util.Optional;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.walker.PathSpec;
import org.junit.Test;
import static org.junit.Assert.*;

public class MapPlannerTest
{
    @Test
    public void findInteractTile_emptyChunk_returnsEmpty()
    {
        MapStore store = new MapStore(new WorldMemoryConfig());
        MapPlanner planner = new MapPlanner(store, new WorldMemoryConfig());
        Optional<WorldPoint> result = planner.findInteractTile(
            new WorldPoint(3208, 3213, 0),
            new WorldPoint(3209, 3214, 0),
            2, true);
        assertFalse(result.isPresent());
    }

    @Test
    public void findInteractTile_simpleOpenArea_returnsClosestLosTile()
    {
        // 5×5 open area, target at center, range R=2, requireLOS=true.
        MapStore store = new MapStore(new WorldMemoryConfig());
        RegionChunkBuilder b = store.builderFor(0);
        for (int x = 0; x < 5; x++)
            for (int y = 0; y < 5; y++)
                b.setTile(x, y, 0, 0);
        store.publish(0, b);

        MapPlanner planner = new MapPlanner(store, new WorldMemoryConfig());
        WorldPoint target = new WorldPoint(2, 2, 0);
        Optional<WorldPoint> result = planner.findInteractTile(
            new WorldPoint(0, 0, 0), target, 2, true);
        assertTrue(result.isPresent());
        // Picked tile must be within Chebyshev R of the target (the spec's contract).
        WorldPoint t = result.get();
        int chebyshev = Math.max(Math.abs(t.getX() - target.getX()),
                                 Math.abs(t.getY() - target.getY()));
        assertTrue("expected Chebyshev <= 2, got " + chebyshev, chebyshev <= 2);
    }

    @Test
    public void planToInteractTile_outputsOneByOneWalkArea()
    {
        MapStore store = new MapStore(new WorldMemoryConfig());
        RegionChunkBuilder b = store.builderFor(0);
        for (int x = 0; x < 5; x++)
            for (int y = 0; y < 5; y++)
                b.setTile(x, y, 0, 0);
        store.publish(0, b);

        MapPlanner planner = new MapPlanner(store, new WorldMemoryConfig());
        Optional<PathSpec> spec = planner.planToInteractTile(
            new WorldPoint(0, 0, 0),
            new WorldPoint(2, 2, 0),
            2, true, "test-spec");

        assertTrue(spec.isPresent());
        assertEquals(1, spec.get().waypoints().size());
        // Verify it's a WALK_AREA, 1×1.
        var wp = spec.get().waypoints().get(0);
        assertEquals(1, wp.area().getWidth());
        assertEquals(1, wp.area().getHeight());
    }
}
```

- [ ] **Step 2: Write the impl**

```java
package net.runelite.client.plugins.recorder.worldmap;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.walker.PathSpec;

/** WorldMemory's planner — turns "stand near target T with LOS" into a
 *  PathSpec the existing UniversalWalker can execute. Runs on a worker
 *  thread; never touches live Client/Scene/WorldView. */
public final class MapPlanner
{
    private final MapStore store;
    private final WorldMemoryConfig config;

    public MapPlanner(MapStore store, WorldMemoryConfig config)
    {
        this.store = store;
        this.config = config;
    }

    public Optional<WorldPoint> findInteractTile(
        WorldPoint player, WorldPoint target,
        int maxDistance, boolean requireLineOfSight)
    {
        if (player == null || target == null) return Optional.empty();
        if (player.getPlane() != target.getPlane()) return Optional.empty();   // v1: same-plane only
        int regionId = RegionIds.regionIdFor(target.getX(), target.getY());
        RegionChunkSnapshot snap = store.snapshotFor(regionId);
        if (snap == null) return Optional.empty();
        if (RegionIds.regionIdFor(player.getX(), player.getY()) != regionId)
        {
            return Optional.empty();   // v1: same-region only
        }

        // Phase 1: enumerate candidates.
        Set<WorldPoint> candidates = new HashSet<>();
        int plane = target.getPlane();
        for (int dx = -maxDistance; dx <= maxDistance; dx++)
        {
            for (int dy = -maxDistance; dy <= maxDistance; dy++)
            {
                if (dx == 0 && dy == 0) continue;        // can't stand on target
                int x = target.getX() + dx, y = target.getY() + dy;
                WorldPoint c = new WorldPoint(x, y, plane);
                if (!snap.isStandableLocal(x, y, plane)) continue;
                if (requireLineOfSight && !Bresenham.hasLineOfSight(snap, c, target)) continue;
                candidates.add(c);
            }
        }
        if (candidates.isEmpty()) return Optional.empty();

        // Phase 2: single Dijkstra, all candidates as goals.
        Map<WorldPoint, Integer> dist = MapAStar.dijkstraToAny(
            snap, player, candidates, config.maxPathLength, config.maxExpandedTiles);

        // Phase 3: rank, pick best.
        WorldPoint best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (WorldPoint c : candidates)
        {
            int d = dist.getOrDefault(c, -1);
            if (d < 0) continue;     // unreachable within caps
            double score = config.rankWeightPathLength * d
                + config.rankWeightChebyshevToTarget * chebyshev(c, target);
            if (score < bestScore) { bestScore = score; best = c; }
        }
        return Optional.ofNullable(best);
    }

    public Optional<PathSpec> planWithin(WorldPoint player, WorldPoint goal)
    {
        if (player == null || goal == null) return Optional.empty();
        int rPlayer = RegionIds.regionIdFor(player.getX(), player.getY());
        int rGoal = RegionIds.regionIdFor(goal.getX(), goal.getY());
        if (rPlayer != rGoal) return Optional.empty();
        RegionChunkSnapshot snap = store.snapshotFor(rGoal);
        if (snap == null) return Optional.empty();

        // Verify reachability before emitting the spec.
        Map<WorldPoint, Integer> d = MapAStar.dijkstraToAny(
            snap, player, Set.of(goal),
            config.maxPathLength, config.maxExpandedTiles);
        if (d.getOrDefault(goal, -1) < 0) return Optional.empty();

        return Optional.of(walkSpecToTile(goal, "wm-plan"));
    }

    public Optional<PathSpec> planToInteractTile(
        WorldPoint player, WorldPoint target,
        int maxDistance, boolean requireLineOfSight, String pathName)
    {
        return findInteractTile(player, target, maxDistance, requireLineOfSight)
            .map(stand -> walkSpecToTile(stand, pathName));
    }

    /** Synthesize a single-step PathSpec: 1×1 WALK_AREA around the picked tile. */
    private PathSpec walkSpecToTile(WorldPoint tile, String name)
    {
        WorldArea oneByOne = new WorldArea(
            tile.getX(), tile.getY(), 1, 1, tile.getPlane());
        return PathSpec.builder(name)
            .walk("wm-target", oneByOne)
            .build();
    }

    private static int chebyshev(WorldPoint a, WorldPoint b)
    {
        return Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getY() - b.getY()));
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/worldmap/MapPlanner.java \
        runelite-client/src/test/java/net/runelite/client/plugins/recorder/worldmap/MapPlannerTest.java
git commit -m "feat(worldmap): MapPlanner.{findInteractTile,planWithin,planToInteractTile}"
```

---

## Phase 4 — Plugin/Panel/Config integration

### Task 4.1 — Add `useWorldMemoryPlanner` to `RecorderConfig`

**Files:**
- Modify: `recorder/RecorderConfig.java`

- [ ] **Step 1: Add the config item**

(Subagent: read RecorderConfig.java first to follow the existing config-item style — `@ConfigItem` annotations etc.)

Add a single boolean default-false config item near the other "experimental" or "developer" items (or wherever fits stylistically):

```java
@ConfigItem(
    keyName = "useWorldMemoryPlanner",
    name = "Use WorldMemory planner (experimental)",
    description = "When on, scripts that opt in (currently CookingScriptV3) "
        + "use the WorldMemory planner to pick interact tiles instead of "
        + "their hardcoded arrival-area path. Default off.",
    section = experimentalSection
)
default boolean useWorldMemoryPlanner() { return false; }
```

- [ ] **Step 2: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderConfig.java
git commit -m "feat(recorder): add useWorldMemoryPlanner toggle (default off)"
```

### Task 4.2 — Wire WorldMemory into `RecorderPlugin`

**Files:**
- Modify: `recorder/RecorderPlugin.java`

The plugin must:
1. Construct `MapStore`, `EntityIndex`, `SceneScraper`, `EntityScraper`, `MapPlanner`, `WorldMemoryConfig` at startup.
2. Subscribe to `GameTick` (every `config.scrapeEveryNTicks` ticks): call `sceneScraper.scan(wv, now)` and `entityScraper.scanNpcs(wv, now, entityIndex)` on the client thread.
3. Spawn a daemon thread that flushes dirty chunks every `config.flushEverySeconds` (Phase 4.3).
4. Expose `MapPlanner` to scripts (via `getMapPlanner()` accessor or via injection — plan-level decision; mirror how the plugin exposes other shared services like `BankInteraction` to scripts).

- [ ] **Step 1: Read RecorderPlugin lifecycle**

Read `RecorderPlugin.java`'s `startUp()` / `shutDown()` and any existing `@Subscribe` methods. Mirror the same pattern.

- [ ] **Step 2: Add WorldMemory fields + lifecycle**

Add fields:

```java
private MapStore worldMapStore;
private EntityIndex worldEntityIndex;
private SceneScraper sceneScraper;
private EntityScraper entityScraper;
private MapPlanner mapPlanner;
private WorldMemoryConfig wmConfig;
private int tickCounter;
```

In `startUp()`:

```java
wmConfig = new WorldMemoryConfig();
worldMapStore = new MapStore(wmConfig);
worldEntityIndex = new EntityIndex();
sceneScraper = new SceneScraper(client, worldMapStore, worldEntityIndex, wmConfig);
entityScraper = new EntityScraper();
mapPlanner = new MapPlanner(worldMapStore, wmConfig);

// Bootstrap: load any persisted chunks for the player's current region
// + 8 neighbours so the planner has data immediately on the warm-run A/B
// test (otherwise the planner returns empty for the first scrape cycle
// even when JSON exists from a prior session). Done off the client
// thread to avoid blocking startup.
File worldmapRoot = new File(RUNELITE_DIR, "recorder/worldmap");
new Thread(() -> {
    Player self = client.getLocalPlayer();
    if (self == null || self.getWorldLocation() == null) return;
    int rx = self.getWorldLocation().getX() >> 6;
    int ry = self.getWorldLocation().getY() >> 6;
    for (int dx = -1; dx <= 1; dx++)
    {
        for (int dy = -1; dy <= 1; dy++)
        {
            int regionId = ((rx + dx) << 8) | ((ry + dy) & 0xff);
            worldMapStore.loadFromDisk(worldmapRoot, regionId);
        }
    }
    // Entities too — for nearestNpc/Object queries.
    for (int dx = -1; dx <= 1; dx++)
    {
        for (int dy = -1; dy <= 1; dy++)
        {
            int regionId = ((rx + dx) << 8) | ((ry + dy) & 0xff);
            for (EntitySighting s : MapStoreIO.readEntities(worldmapRoot, regionId))
            {
                worldEntityIndex.recordNpcSighting(s.id, s.name, s.lastTile, s.lastSeenAt);
            }
        }
    }
}, "worldmap-bootstrap").start();

flushDaemon = new FlushDaemon(worldMapStore, worldEntityIndex,
    worldmapRoot, wmConfig.flushEverySeconds * 1000L);
flushDaemon.start();
```

In `shutDown()`:

```java
flushDaemon.stop();
flushDaemon.flushOnce();   // last write so we don't lose data
```

Add a `@Subscribe` on `GameTick`. **GameTick fires on the client thread** — so the body runs there directly, no `clientThread.invokeLater` needed (and we should NOT use it, because that'd queue the work for a future frame and break per-tick semantics):

```java
@Subscribe
public void onGameTick(GameTick e)
{
    if (++tickCounter % wmConfig.scrapeEveryNTicks != 0) return;
    long now = System.currentTimeMillis();
    WorldView wv = client.getTopLevelWorldView();
    if (wv == null) return;
    if (wv.getScene() != null && wv.getScene().isInstance()) return;
    long start = System.nanoTime();
    sceneScraper.scan(wv, now);
    if (System.nanoTime() - start > wmConfig.scrapeBudgetNanos)
    {
        log.debug("worldmap: scene scrape exceeded budget; skipping entities this tick");
        return;
    }
    entityScraper.scanNpcs(wv, now, worldEntityIndex);
}

/** Public accessor for scripts (e.g. CookingScriptV3). */
public MapPlanner mapPlanner() { return mapPlanner; }
```

- [ ] **Step 3: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPlugin.java
git commit -m "feat(recorder): wire WorldMemory scrapers + planner into plugin lifecycle"
```

### Task 4.3 — Flush daemon

**Files:**
- Modify: `recorder/RecorderPlugin.java` (add daemon)
- Or create: `recorder/worldmap/FlushDaemon.java` (cleaner separation)

A daemon thread that wakes every `config.flushEverySeconds`, snapshots `MapStore`'s tracked dirty regions, and persists them via `MapStoreIO`. Same persists the `EntityIndex`.

- [ ] **Step 1: Decide on dirty-chunk tracking**

Per the spec's open question 4: mark dirty on every `publish`. Add a `Set<Integer>` of dirty regionIds to `MapStore`; clear after flush.

- [ ] **Step 2: Implement `FlushDaemon`**

```java
package net.runelite.client.plugins.recorder.worldmap;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class FlushDaemon
{
    private final MapStore store;
    private final EntityIndex index;
    private final File rootDir;
    private final long intervalMs;
    private volatile boolean running;
    private Thread t;

    public FlushDaemon(MapStore store, EntityIndex index, File rootDir, long intervalMs)
    {
        this.store = store;
        this.index = index;
        this.rootDir = rootDir;
        this.intervalMs = intervalMs;
    }

    public void start()
    {
        running = true;
        t = new Thread(this::loop, "worldmap-flush");
        t.setDaemon(true);
        t.start();
    }

    public void stop()
    {
        running = false;
        if (t != null) t.interrupt();
    }

    private void loop()
    {
        while (running)
        {
            try { Thread.sleep(intervalMs); }
            catch (InterruptedException ie) { return; }
            try { flushOnce(); }
            catch (Throwable th) { log.warn("worldmap flush failed", th); }
        }
    }

    public void flushOnce()
    {
        // Tiles + objects per region.
        Set<Integer> dirtyTiles = store.takeDirtyRegionIds();
        for (int regionId : dirtyTiles)
        {
            RegionChunkSnapshot snap = store.snapshotFor(regionId);
            if (snap == null) continue;
            MapStoreIO.writeRegion(rootDir, snap);
        }
        // Entity sightings per region.
        Set<Integer> dirtyEntities = index.takeDirtyRegionIds();
        for (int regionId : dirtyEntities)
        {
            List<EntitySighting> npcs = index.npcsInRegion(regionId);
            MapStoreIO.writeEntities(rootDir, regionId, npcs);
        }
    }
}
```

- [ ] **Step 3: Wire into `RecorderPlugin`**

In `startUp()`: instantiate `FlushDaemon` with `~/.runelite/recorder/worldmap/` as root, start it.
In `shutDown()`: call `stop()` then `flushOnce()` so we don't lose in-memory data.

- [ ] **Step 4: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/worldmap/FlushDaemon.java \
        runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPlugin.java \
        runelite-client/src/main/java/net/runelite/client/plugins/recorder/worldmap/MapStore.java
git commit -m "feat(worldmap): FlushDaemon + dirty-chunk tracking"
```

### Task 4.4 — `RecorderPanel` checkbox

**Files:**
- Modify: `recorder/RecorderPanel.java`

- [ ] **Step 1: Add the checkbox**

Read RecorderPanel.java first. Find an "Experimental" or "Developer" section (or add one). Add a JCheckBox bound to `RecorderConfig.useWorldMemoryPlanner` with the same labels as the config item.

- [ ] **Step 2: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPanel.java
git commit -m "feat(recorder): UI checkbox for useWorldMemoryPlanner"
```

---

## Phase 5 — CookingScriptV3 migration + fixtures

### Task 5.1 — `CookingScriptV3.currentCookPath()` toggle block

**Files:**
- Modify: `recorder/scripts/CookingScriptV3.java` (around line 631-650)

- [ ] **Step 1: Apply the migration block from the spec**

Replace the current `currentCookPath()` body with the spec's "Migration when `useWorldMemoryPlanner` is true" block:

```java
private PathSpec currentCookPath()
{
    if (tripCookPath != null) return tripCookPath;
    CookingLocation l = location.get();
    WorldPoint target = pickCookTargetTile(l);

    if (target == null)
    {
        // Unchanged: walk to full cookArea (legacy behavior when target unknown).
        tripCookPath = PathSpec.builder("v3-cook-area-" + System.currentTimeMillis())
            .walk("v3-cook", l.cookArea())
            .build();
    }
    else if (config.useWorldMemoryPlanner())
    {
        WorldPoint playerPos = readPlayerPositionOnClient();
        if (playerPos == null)
        {
            // Player position read failed (logged out / scene unloaded).
            // Fall back to wide cookArea so the script doesn't hang.
            tripCookPath = PathSpec.builder("v3-cook-area-" + System.currentTimeMillis())
                .walk("v3-cook", l.cookArea())
                .build();
            return tripCookPath;
        }
        Optional<PathSpec> spec = plugin.mapPlanner().planToInteractTile(
            playerPos, target,
            /* maxDistance */ 2,
            /* requireLineOfSight */ true,
            /* pathName */ "v3-cook-wm-" + System.currentTimeMillis());
        if (spec.isPresent())
        {
            tripCookPath = spec.get();
        }
        else
        {
            // Wide cookArea fallback (NOT the buggy 3×3).
            tripCookPath = PathSpec.builder("v3-cook-area-" + System.currentTimeMillis())
                .walk("v3-cook", l.cookArea())
                .build();
        }
    }
    else
    {
        // Toggle off — preserve the existing 3×3 path exactly.
        WorldArea around = arrivalWindowAround(target);
        tripCookPath = PathSpec.builder("v3-cook-target-" + System.currentTimeMillis())
            .walk("v3-cook", around)
            .build();
    }
    return tripCookPath;
}
```

**`readPlayerPositionOnClient()` — exact implementation:**

CookingScriptV3 already reads player position via `clientThread.invokeAndWait` in multiple places (e.g., its tick loop reads `client.getLocalPlayer().getWorldLocation()` inside a client-thread hop). Subagent: read CookingScriptV3 lines 270-310 to find the existing pattern; if a `playerWorldPoint()` helper already exists, reuse it. Otherwise add this private helper:

```java
@Nullable
private WorldPoint readPlayerPositionOnClient()
{
    java.util.concurrent.atomic.AtomicReference<WorldPoint> ref =
        new java.util.concurrent.atomic.AtomicReference<>();
    java.util.concurrent.CountDownLatch latch =
        new java.util.concurrent.CountDownLatch(1);
    clientThread.invokeLater(() -> {
        try
        {
            Player p = client.getLocalPlayer();
            if (p != null) ref.set(p.getWorldLocation());
        }
        finally { latch.countDown(); }
    });
    try { latch.await(); }
    catch (InterruptedException ie) { Thread.currentThread().interrupt(); return null; }
    return ref.get();
}
```

This is identical to the `clientCall(...)` helper inside `UniversalWalker.java:835-847` — same shape, hopped onto the client thread, latches until done. Returns null if the player isn't loaded.

- [ ] **Step 2: Verify imports**

Add `import net.runelite.client.plugins.recorder.walker.PathSpec` (if not already present), `import java.util.Optional`. The `RecorderPlugin.mapPlanner()` call assumes Task 4.2's accessor.

- [ ] **Step 3: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/scripts/CookingScriptV3.java
git commit -m "feat(cookingV3): opt-in WorldMemory planner for cook target picking"
```

### Task 5.2 — Hand-author fixtures for unit tests

**Files:**
- Create: `runelite-client/src/test/resources/worldmap/fixtures/lumbridge-kitchen-wall.json`
- Create: `runelite-client/src/test/resources/worldmap/fixtures/wall-maze.json`
- Create: `runelite-client/src/test/resources/worldmap/fixtures/los-block.json`

These are small JSON files matching the spec's `regions/<regionId>.json` schema. Each one models a specific scenario — Lumbridge kitchen with the wall geometry that produces the off-canvas bug; a 5×5 maze for A* pathfinding tests; an LOS scenario with a wall blocking visibility between two open tiles.

- [ ] **Step 1: Author `lumbridge-kitchen-wall.json`**

Roughly: 6×6 tiles around the Lumbridge cook range coords (3208-3214 × 3211-3217 plane 0). Most tiles open, one wall on the south side of the range that creates the "tile is close in 2D but not LOS-clear" geometry. Use real `BLOCK_LINE_OF_SIGHT_*` and `BLOCK_MOVEMENT_*` flag values matching the in-game collision.

(Subagent: this is the trickiest fixture. Don't try to be exhaustive — author the smallest layout that exercises the LOS-pick-vs-Chebyshev-pick distinction. The fixture's purpose is "show the planner picks the right tile when wall geometry pushes the closest-by-Chebyshev tile out of LOS.")

- [ ] **Step 2: Author `wall-maze.json` and `los-block.json`**

`wall-maze.json`: 7×7 corridor with a U-shape wall. Used by `MapAStarTest` for end-to-end "walk around the wall" pathing.
`los-block.json`: 5×5 with a single wall between corner-to-corner stand-vs-target. Used by `BresenhamLosTest` integration variant.

- [ ] **Step 3: Add fixture-loading test**

```java
// In MapPlannerTest:
@Test
public void lumbridgeKitchenWallFixture_picksLosValidTile() throws Exception
{
    RegionChunkSnapshot snap = MapStoreIO.readFixture(
        "fixtures/lumbridge-kitchen-wall.json");
    MapStore store = new MapStore(new WorldMemoryConfig());
    store.installSnapshotForTest(snap.regionId(), snap);

    MapPlanner planner = new MapPlanner(store, new WorldMemoryConfig());
    WorldPoint player = new WorldPoint(3208, 3215, 0);   // approach from south
    WorldPoint cookTarget = new WorldPoint(3211, 3214, 0);

    Optional<WorldPoint> stand = planner.findInteractTile(player, cookTarget, 2, true);

    assertTrue(stand.isPresent());
    assertTrue(Bresenham.hasLineOfSight(snap, stand.get(), cookTarget));
    // Specifically reject the (3210, 3214, 0) tile — close by Chebyshev but
    // wall-blocked LOS in our fixture.
    assertNotEquals(new WorldPoint(3210, 3214, 0), stand.get());
}
```

(Both `MapStore.installSnapshotForTest(int, RegionChunkSnapshot)` and `MapStoreIO.readFixture(String)` are already specified — added in Tasks 1.6 and 1.7 respectively. The test calls them directly; no extra impl in Task 5.2.)

- [ ] **Step 4: Commit**

```bash
git add runelite-client/src/test/resources/worldmap/ \
        runelite-client/src/test/java/net/runelite/client/plugins/recorder/worldmap/MapPlannerTest.java \
        runelite-client/src/main/java/net/runelite/client/plugins/recorder/worldmap/MapStoreIO.java \
        runelite-client/src/main/java/net/runelite/client/plugins/recorder/worldmap/MapStore.java
git commit -m "test(worldmap): hand-authored fixtures + planner LOS-pick test"
```

---

## After all tasks land

Once Phase 5 is committed, the user runs:

```
JAVA_HOME=$(dirname /opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home/bin/java)/.. \
   ./gradlew :client:compileJava :client:test
```

…and the A/B testing plan in the spec (baseline → cold → warm). The plan is **complete** when the v1 proof passes (off-canvas incidents drop to zero in the warm run).

---

## Self-Review

Walking through the spec section-by-section to check coverage:

- [x] **Goal / Summary** → addressed by the proof point in Phase 5.
- [x] **Non-goals** → respected (no transports, teleports, mouse capture, SQLite, bootstrap data).
- [x] **Isolation constraint** → Task 0.1-0.3 (Walker interface refactor). Task 5.1 cooking gate. trail/* untouched.
- [x] **Walker interface** → Phase 0.
- [x] **Reuse table (existing primitives)** → Tasks 3.2 (Reachability semantics in MapAStar), 3.3 (PathSpec output), 5.1 (UniversalWalker as default Walker), 4.2 (RecorderPlugin lifecycle), 4.4 (RecorderPanel).
- [x] **Architecture diagram** → Phase 1-3 file structure mirrors it.
- [x] **Threading model + snapshot rule** → Tasks 1.4 (Builder), 1.5 (Snapshot), 1.6 (MapStore AtomicReference).
- [x] **Storage format** → Task 1.7 (MapStoreIO).
- [x] **v1 API surface** → Tasks 0.1 (Walker), 3.3 (MapPlanner), 2.3 (EntityIndex).
- [x] **LOS-aware destination picker** → Tasks 3.1 (Bresenham), 3.2 (MapAStar), 3.3 (MapPlanner).
- [x] **CookingScriptV3 migration** → Task 5.1.
- [x] **A/B testing plan** → executed by the user post-merge (not a code task; documented in spec).
- [x] **Phasing v1** → all v1 deliverables covered above.

**Placeholder scan:** None remaining — every step has the actual code or a precise procedural instruction. "Subagent fills in" appears only in MapAStar (Task 3.2) where the wall/diagonal truth-table is too large to inline; the cross-reference to `walker/Reachability.java:80-115` plus `runelite-api/.../CollisionDataFlag.java` is precise enough for an implementer.

**Type consistency:** `Walker.Status` and `Walker.InternalState` introduced in Task 0.1, consumed in Task 0.2 with the same names. `MapPlanner.findInteractTile`, `planWithin`, `planToInteractTile` introduced in Task 3.3 with the same signatures used in Task 5.1. `MapStore.snapshotFor`, `builderFor`, `publish` consistent across Tasks 1.6, 2.1, 3.3. `RegionChunkSnapshot.tile`, `tiles`, `objects` consistent across 1.5, 2.1, 3.1, 3.2, 3.3.

No spec gaps detected.
