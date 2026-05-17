# Collision-aware Dijkstra Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development (or superpowers:executing-plans). Spec at `docs/superpowers/specs/2026-05-17-collision-aware-dijkstra-design.md`. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate V2's `BFS leg UNREACHABLE` failures caused by Dijkstra picking abstract routes that cross collision boundaries (e.g., chicken-pen fence).

**Architecture:** Precompute connectivity components from `GlobalCollisionSnapshot` once per session. Add a component-equality filter to `LinkGraphDijkstra`'s walk-edge construction. Pass the precomputed components through `WorldSnapshot` so the planner consumes them inline. No retry loops, no top-K — Dijkstra cannot pick BFS-infeasible walks because the edges don't exist.

**Tech Stack:** Java 17, RuneLite plugin model (existing — `:client:compileJava`). Unit tests with JUnit 5 + Mockito (existing pattern in `nav/v2/` tests).

**Constraint from user:** DO NOT rebuild shadow jar or relaunch the running client. Compile-only (`:client:compileJava`) plus targeted unit-test runs to verify the work.

---

## File map

```
runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v2/
  collision/
    ConnectivityComponents.java         NEW — flood-fill + componentOf API
    GlobalCollisionSnapshot.java        MODIFY — add loadedRegionKeys() accessor
    WorldSnapshot.java                  MODIFY — add components() default → null
    WorldSnapshotBuilder.java           MODIFY — accept ComponentMap and pass through
  transport/
    LinkGraphDijkstra.java              MODIFY — accept optional ConnectivityComponents
  planner/
    WaypointPlanner.java                MODIFY — pass snap.components() to Dijkstra
  RecorderPlugin.java                   MODIFY — schedule background precompute, hold result

runelite-client/src/test/java/net/runelite/client/plugins/recorder/nav/v2/
  collision/
    ConnectivityComponentsTest.java     NEW — unit
  transport/
    LinkGraphDijkstraComponentsTest.java NEW — integration (synthetic table)
  planner/
    WaypointPlannerPenRouteTest.java     NEW — regression (real snapshot + real table)
```

---

## Task 1: ConnectivityComponents skeleton + first failing test

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v2/collision/ConnectivityComponents.java`
- Create: `runelite-client/src/test/java/net/runelite/client/plugins/recorder/nav/v2/collision/ConnectivityComponentsTest.java`

- [ ] **Step 1: Write the failing test for the trivial behaviour.**

`ConnectivityComponentsTest.java`. Test asserts: same tile passed twice returns same component id (>= 0) when the tile is walkable in the snapshot. Use a tiny in-memory `GlobalCollisionSnapshot` test fixture or read the bundled resource — pick the path with less boilerplate. The fixture should have ONE walkable region.

- [ ] **Step 2: Run, verify it fails (class does not exist yet).**

`./gradlew :client:test --tests "*ConnectivityComponentsTest*"` → expect compile failure.

- [ ] **Step 3: Create the class with the API shape from the spec.**

```java
public final class ConnectivityComponents {
    public static ConnectivityComponents fromSnapshot(GlobalCollisionSnapshot snap);
    public int componentOf(WorldPoint p);
    public int componentOf(int x, int y, int plane);
    public boolean sameComponent(WorldPoint a, WorldPoint b);
    public int componentCount();
}
```

Empty bodies (return -1, false, 0). Class must compile.

- [ ] **Step 4: Run, verify test now fails for the right reason.**

Compile passes; assertion fails because `componentOf` returns -1 unconditionally.

- [ ] **Step 5: Commit.**

Message: `feat(nav-v2): ConnectivityComponents skeleton + first failing test`.

## Task 2: GlobalCollisionSnapshot region-key accessor

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v2/collision/GlobalCollisionSnapshot.java`

**Why:** Flood-fill needs to enumerate every loaded region. The existing API only lets you ASK if a region is loaded; it doesn't list them.

- [ ] **Step 1: Add `Set<Integer> loadedRegionKeys()` accessor.**

Returns `Collections.unmodifiableSet(regions.keySet())`. Package-private is fine for now — only `ConnectivityComponents` needs it, and it lives in the same package.

- [ ] **Step 2: Add a tiny test asserting the bundled snapshot reports >0 regions and a known Lumbridge region key is present.**

Lumbridge bank tile `(3209, 3220)` lives in region `regionX=50, regionY=50` (since `3209/64 = 50`, `3220/64 = 50`). Packed key = `50 | (50 << 16) = 3276850`. Assert `loadedRegionKeys().contains(3276850)`.

- [ ] **Step 3: Run, verify passes.**

- [ ] **Step 4: Commit.**

Message: `feat(nav-v2): expose GlobalCollisionSnapshot.loadedRegionKeys() for flood-fill`.

## Task 3: ConnectivityComponents — flood-fill implementation (8-direction, kernel-shared canMove)

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v2/collision/ConnectivityComponents.java`
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v2/bfs/SkretzoBfsKernel.java` (widen `canMove` to public OR add a public delegating overload — see step 3)
- Modify: `runelite-client/src/test/java/net/runelite/client/plugins/recorder/nav/v2/collision/ConnectivityComponentsTest.java`

- [ ] **Step 1: Add three unit tests.**

a) **Wall row partitions a 5×5 region.** Synthetic snapshot, plane 0, wall row at y=2. Assert `componentOf(0,0,0)` ≠ `componentOf(0,4,0)`, both ≥ 0.

b) **Diagonal corner-touch keeps two tiles in the same component.** Fixture: two walkable tiles connected only via NE-step where both cardinal stepping-stone tiles are walkable (pillar rule legal). Assert: SAME component.

c) **`canMove` parity with BFS kernel.** For every adjacent walkable-tile pair in a small synthetic fixture, assert `SkretzoBfsKernel.canMove(view, src, dst)` ↔ `components.sameComponent(src, dst)` (within-one-step). This is the drift-prevention guard.

If a synthetic `GlobalCollisionSnapshot` seam doesn't exist, add a package-private `forTesting(int[][][] flagsByPlane, int baseRegionX, int baseRegionY)` static factory to `GlobalCollisionSnapshot`. NOT for production use — annotate `@VisibleForTesting`.

- [ ] **Step 2: Run the tests, verify they fail.**

- [ ] **Step 3: Expose kernel `canMove` for reuse (or widen visibility).**

In `SkretzoBfsKernel.java`, the existing `canMove(CollisionView, int x, int y, int plane, int dx, int dy)` is package-private. Either:
- (a) widen its visibility to `public` with a small JavaDoc clarifying it's the shared connectivity rule, OR
- (b) add a `public static boolean canStep(CollisionView, int x, int y, int plane, int dx, int dy)` that delegates.

Pick (a) — keeps one method, one source of truth. Adjust JavaDoc to call out this dual use.

- [ ] **Step 4: Implement storage.**

Per-region storage: `private final Map<Integer, int[][]> components` where `components.get(regionKey)[plane][y*64 + x]` returns the component id, or 0 for unwalkable/unlabeled. Component ids are 1-based internally (so 0 == "no data"); `componentOf` subtracts 1 before returning, mapping unwalkable → `-1`.

Region key uses the same `packRegion` convention as `GlobalCollisionSnapshot`.

- [ ] **Step 5: Implement flood-fill (8-direction, kernel-shared canMove).**

Build a single static-only `CollisionView` once: `new CollisionView(globalSnap, LiveSceneCollisionOverlay.empty())`. The empty overlay is constructed by `LiveSceneCollisionOverlay`'s existing zero-arg / empty-bounds path; if no such path exists, add a tiny `LiveSceneCollisionOverlay.empty()` factory that returns an instance where `containsTile(any) == false` (then `CollisionView` falls through to global, by its own contract).

```
nextComponentId = 1
for each loaded region key:
  for plane in 0..3:
    for each tile in region:
      if walkable (BLOCK_MOVEMENT_FULL bit clear) AND unlabeled:
        BFS-flood from this tile, label every reachable tile with nextComponentId
        nextComponentId += 1
```

BFS expansion: 8 directions, ordered as `(W, E, S, N, SW, SE, NW, NE)` for stability (order doesn't change components, only label-assignment determinism). For each candidate neighbour, call `SkretzoBfsKernel.canMove(staticView, x, y, plane, dx, dy)` — this is the same rule the production BFS uses, by construction. Pillar rule + diagonal flags + cardinal exit/entry bits are all handled inside the kernel.

Cross-region neighbours: `staticView.flagsAt(neighbour)` falls through to `globalSnap`, which looks up the correct neighbouring region.

- [ ] **Step 5: Implement `componentOf` / `sameComponent` / `componentCount`.**

```
componentOf(x, y, p): look up region → component[plane] → id; subtract 1; return.
sameComponent(a, b): both >= 0 AND equal.
componentCount(): nextComponentId - 1.
```

- [ ] **Step 6: Run all `ConnectivityComponentsTest` tests, verify pass.**

- [ ] **Step 7: Add a perf-smoke test (informational, asserts only that the bundled snapshot completes flood-fill in < 5s).**

If it takes longer in CI we revisit — but the 1-2s budget should be comfortable.

- [ ] **Step 8: Commit.**

Message: `feat(nav-v2): ConnectivityComponents flood-fill over GlobalCollisionSnapshot`.

## Task 4: LinkGraphDijkstra — component-aware walk edges

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v2/transport/LinkGraphDijkstra.java`
- Create: `runelite-client/src/test/java/net/runelite/client/plugins/recorder/nav/v2/transport/LinkGraphDijkstraComponentsTest.java`

- [ ] **Step 1: Write the failing integration test.**

Build a 2-component synthetic world (use the test seam from Task 3). Build a `TransportTable` with **exactly one** transport bridging the two components. Call `findRouteSkeleton(ctx, table, fromInCompA, toInCompB, components)`.

Assert: `status == OK`, skeleton contains exactly one `TRANSPORT` node, that node's `transport.from()` is in component A and `transport.to()` is in component B.

Add a counter-test: same setup but pass `components = null`. Assert: skeleton contains ZERO transport nodes (Dijkstra picks the abstract walk). This is the regression guard — the new param must default to status-quo behaviour when null.

- [ ] **Step 2: Run, verify it fails.**

Test won't compile yet (no overload taking `components`).

- [ ] **Step 3: Add the overload.**

```java
public static SkeletonResult findRouteSkeleton(
    NavigationContext ctx, TransportTable table,
    WorldPoint from, WorldPoint to,
    @Nullable ConnectivityComponents components);
```

Existing 4-arg method delegates with `components = null`. Walk-edge loop (the `for (j = 0; j < nodes.size(); j++)` inner loop) gains:

```java
if (components != null && !components.sameComponent(a, b)) continue;
```

- [ ] **Step 4: Run, verify both tests pass.**

- [ ] **Step 5: Commit.**

Message: `feat(nav-v2): LinkGraphDijkstra component-aware walk edges`.

## Task 5: WorldSnapshot carries components, WaypointPlanner consumes them

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v2/collision/WorldSnapshot.java`
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v2/collision/WorldSnapshotBuilder.java`
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v2/planner/WaypointPlanner.java`

- [ ] **Step 1: Add `components()` to `WorldSnapshot`.**

```java
@javax.annotation.Nullable
default ConnectivityComponents components() { return null; }
```

Default to null keeps every existing test fixture / anonymous impl compatible.

- [ ] **Step 2: Wire `WorldSnapshotBuilder` to accept components — mint-time resolution.**

Add a new overload of `fromComponents(...)` that accepts a `ConnectivityComponents` and stores it on the impl. Existing overloads continue to default to null.

`captureOnClientThread(...)` accepts a `Supplier<ConnectivityComponents>` (or directly a reference to the plugin holder's volatile field). It **reads the supplier ONCE during capture** and stores the resolved value (possibly null) on the new `WorldSnapshotImpl` field `final ConnectivityComponents components`. The impl's `components()` returns this stored reference — it does NOT call the supplier again. Per architect pin: same snapshot, same `components()` value forever.

`fromClient(...)` accepts the same supplier and forwards it. Callers from `RecorderPlugin` pass `() -> this.v2Components`.

- [ ] **Step 3: Pass through in `WaypointPlanner.plan`.**

At the `LinkGraphDijkstra.findRouteSkeleton` call site (line ~164), pass `snap.components()` as the new fifth arg. No other planner changes — BFS / RouteValidator / leg construction is unchanged.

- [ ] **Step 4: Compile.**

`./gradlew :client:compileJava` — must pass.

- [ ] **Step 5: Commit.**

Message: `feat(nav-v2): WorldSnapshot carries ConnectivityComponents; planner passes to Dijkstra`.

## Task 6: RecorderPlugin background precompute

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPlugin.java`

**Architect pin:** single-writer (precompute thread) / many-reader (snapshot mint) → `volatile` is sufficient. The reference is read ONCE at snapshot mint time and stored on the snapshot impl; the holder is never re-read after that. This is what `WorldSnapshotBuilder.captureOnClientThread` is responsible for (Task 5, step 2).

- [ ] **Step 1: Add the holder.**

```java
private volatile ConnectivityComponents v2Components;
```

- [ ] **Step 2: Schedule background precompute in `startUp` (or wherever `GlobalCollisionSnapshot` is loaded today).**

Spawn a dedicated thread (NOT the client thread, NOT the scheduled executor used for ping). Set name "nav-v2-components-precompute", daemon true.

```java
Thread t = new Thread(() -> {
    long t0 = System.nanoTime();
    ConnectivityComponents c = ConnectivityComponents.fromSnapshot(globalSnap);
    v2Components = c;  // volatile single-writer
    log.info("[nav-v2.components] precompute done: {} components in {} ms",
        c.componentCount(), (System.nanoTime() - t0) / 1_000_000);
}, "nav-v2-components-precompute");
t.setDaemon(true);
t.start();
```

The holder is null until precompute finishes; `WorldSnapshotBuilder.captureOnClientThread` reads the holder ONCE at mint time and stores the (possibly null) reference on the snapshot impl. Dijkstra's filter is a no-op while it's null — matches old behaviour during the warm-up window.

- [ ] **Step 3: Add a getter `componentsHolder()` (or a method on the plugin that returns the current value) for `WorldSnapshotBuilder.fromClient` to call.**

- [ ] **Step 4: Compile.**

`./gradlew :client:compileJava` — must pass. **Do NOT shadowJar. Do NOT relaunch.**

- [ ] **Step 5: Commit.**

Message: `feat(nav-v2): background-precompute ConnectivityComponents at plugin start`.

## Task 7a: Live-overlay-passable regression test (architect-review addition)

**Files:**
- Create: `runelite-client/src/test/java/net/runelite/client/plugins/recorder/nav/v2/planner/WaypointPlannerLiveOverlayRegressionTest.java`

This makes the accepted regression explicit: a route where the global snapshot says "blocked" but the live overlay says "passable" (and no transport bridges the spot) was reachable on master and is **not** reachable after this change. The test documents and pins that behaviour so it doesn't get silently re-broken later.

- [ ] **Step 1: Build the fixture.**

Synthetic snapshot with two regions separated by a globally-blocked wall on one tile. Live overlay clears the block on that tile. NO transport entry crosses the wall. Call `WaypointPlanner.plan(snap, from, to)`.

- [ ] **Step 2: Assert.**

With `components != null` in the snapshot: `plan.failed() == true`, reason `TARGET_UNREACHABLE`. With `components == null`: plan succeeds (status quo on master). This proves we know the tradeoff and have made it deliberate.

- [ ] **Step 3: Commit.**

Message: `test(nav-v2): pin live-overlay-passable accepted regression for collision-aware Dijkstra`.

## Task 7b: Pen-route regression test

**Files:**
- Create: `runelite-client/src/test/java/net/runelite/client/plugins/recorder/nav/v2/planner/WaypointPlannerPenRouteTest.java`

- [ ] **Step 1: Write the failing test using real bundled data.**

```java
GlobalCollisionSnapshot snap = GlobalCollisionSnapshot.fromBundledResource();
ConnectivityComponents components = ConnectivityComponents.fromSnapshot(snap);
TransportTable table = TransportTableLoader.loadBundled(); // existing helper
WorldPoint from = new WorldPoint(3209, 3220, 2);   // Lumbridge bank
WorldPoint to   = new WorldPoint(3235, 3295, 0);   // chicken pen
// build a stub WorldSnapshot wiring snap + components + table
V2Path plan = WaypointPlanner.plan(stubSnap, from, to);

assertThat(plan.failed()).isFalse();
assertThat(plan.steps()).extracting(PathStep::kind).contains(StepKind.TRANSPORT);
// stronger: at least one transport leg uses the pen south gate (object 1560)
```

Inspect the actual transport-leg objects in the path; assert at least one has `object id == 1560` (the pen gate). This is the load-bearing assertion — the plan must include the gate transport.

- [ ] **Step 2: Run before this task's other work to confirm it fails on master.**

Actually it should run AFTER Tasks 1-6 — at that point the failing test becomes the "does the fix actually work" check. Run it.

Expected on master (without this work): plan.failed() == true.
Expected after this work: plan.failed() == false AND gate transport included.

- [ ] **Step 3: If the test fails after the fix, debug.**

Most likely cause: components don't connect the pen-gate's destination tile `(3236, 3295, p=0)` to the actual target `(3235, 3295, p=0)`. Check: are those two tiles in the same component? They should be (one cardinal step apart, both walkable).

If not, the bug is in the flood-fill BFS step or the canMove logic. Fix and re-run.

- [ ] **Step 4: Commit.**

Message: `test(nav-v2): regression for bank↔pen route via collision-aware Dijkstra`.

## Task 8: Two-subagent QC (opus)

**Per user instruction.** After all code commits land and `./gradlew :client:compileJava` is clean, dispatch two opus subagents IN PARALLEL:

- **Subagent A — spec compliance:** does the code match every section of the design spec? Are any non-trivial deviations documented?
- **Subagent B — code quality:** does the implementation hold up under tdd-guide / code-reviewer standards? Race conditions, memory leaks, missing tests, ugly abstractions.

Each returns a verdict + actionable list. The controller (me) addresses any blocker findings before reporting to the user. No client rebuild — only the source tree is reviewed.

## Out-of-plan items

These are NOT in this plan; tracked separately:

- Shadow jar rebuild + relaunch. Deferred — user is testing live.
- Persisting components to disk (sidecar cache). Deferred per spec.
- Replacing two-tier with flat A*. Deferred per spec.
