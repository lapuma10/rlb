# V21 trail-guided dynamic navigation — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop V21's plane-mismatch handler from greedily clicking the nearest `Climb-up` object. Replace it with a layered strategy: trail-guided anchors first (ordered route milestones derived from recorded `TrailEvent.Transport` legs), then a destination-aware `TransportIndex` router (with multi-plane collision reachability when the scene allows), then planar projection only as a last resort. Same-plane discipline is tightened: sticky pending subtasks, strict-walk on transport approach, corridor-aware perimeter exits, no off-path walks. Goal-aware dead-end memory persists across runs and is **never** cleared on success — only by TTL. No Lumbridge / castle / general-store hardcoding.

**Architecture:**
- A new `TrailGuide` decomposes a recorded `Trail` into (a) the corridor — its ordered `TrailEvent.Tile` points — and (b) the interaction anchors — its `TrailEvent.Transport` records in order, each enriched with an **`observedDestPlane`** derived from the next chronological `Tile` event in the recording. Anchors carry `objectId + verb + objectTile + approachTile + observedDestPlane`. The corridor is used only as a tiebreaker for perimeter-exit ranking; walks between anchors are computed live by BFS, never replayed tile-for-tile.
- Anchor classification is by **observed plane change**, not by verb. If `observedDestPlane != objectTile.plane`, it's a transport anchor; otherwise it's a local (door / gate) anchor. Verb-based classification is the fallback only when `observedDestPlane` is unknown (malformed trail).
- `V21Navigator.tick()` adopts a clear priority chain (see "Tick priorities" below). The trail anchor selection rung sits ahead of the `TransportRouter` rung; the router only runs when no anchor is in scene or the current anchor is dead-end-marked. Anchor selection requires **approach reachability**, not just object-in-scene visibility — `StaticPlanner` must be able to plan a path to `anchor.approachTile` on the current plane (Success or BlockedEdge; `NoCandidate` rejects).
- When no anchor is currently active and the player isn't on the goal plane, the navigator first tries the `TransportRouter`. If the router has nothing, it walks toward the next **same-plane anchor's approach tile in trail order** (not just the next plane-advancing one — earlier same-plane gates/doors solve first). Planar projection of the goal centroid is the last fallback before a typed `FAILED`.
- `TransportRouter` performs a small multi-plane BFS over known `TransportEdge`s. Walk segments between transports use collision-BFS reachability when both endpoints fall inside the current 4-plane scene capture; Chebyshev gating is only the last-resort estimator when an endpoint is outside the scene window. `findNext` returns an `Optional<TransportCandidate>` where the candidate's `edge` is always a **real** entry in `TransportIndex` — never a synthetic placeholder.
- The pending-subtask field type is `BlockerCandidate` for both sticky slots (`pendingExit` and `pendingTransport`). Anchors and router picks both reduce to `BlockerCandidate` for execution. The router's `TransportCandidate` is a return-only diagnostic wrapper — it carries the real `TransportEdge` and an `executable: BlockerCandidate`, and the navigator stores only the latter. **No synthetic `TransportEdge`** is ever constructed; anchors that don't correspond to an existing edge are stored as plain `BlockerCandidate`s without an edge, and `TransportObserver` writes the real edge after the click.
- `GoalDeadEndMemory` persists goal-aware bad anchors and bad edges to `~/.runelite/recorder/worldmap/v21-deadends.json`. TTL-only expiry (7 days). No clear-on-success — a successful run through the castle stairs does not re-enable the general-store ladder for the next run. The key shape is built from `(objectId, verb, fromTile)` plus goal context; same key works for either a router edge or an anchor.
- Successful arrivals record a `RouteSkeleton` (the sequence of `TransportEdge.key()` values actually traversed, looked up live from `TransportIndex` after each plane change) to `v21-skeletons.json`. Recorder-only this round; replay is a follow-up branch.
- `V21Env` captures `LiveCollisionView` for all four planes per tick instead of one. Cheap (bytes), unlocks reachability checks for multi-plane routing.
- `StaticPlanner.PlanResult.BudgetExhausted` is enriched to carry the best-visited frontier tile + the BFS path to it. V21Navigator on BudgetExhausted walks the path toward the frontier (using `walkAlong`, not `walkTo(centroid)`); if no frontier improved on the player tile, it returns a typed `FAILED`. **No more `walker.walkTo(goal.centroid())`** as a fallback — that line is removed.
- Sticky subtasks own their slot until they fire or fail. The planner is not consulted while a pending subtask is active. The dispatcher's `strict` mode is used on transport-approach final walks. All other `walkAlong` callers feed BFS-validated paths; the BudgetExhausted change removes the last off-path walk site.

**Tech Stack:** Java 17, RuneLite plugin tree (`runelite-client/`), JUnit 5 for unit tests, manual in-game verification for end-to-end. No new external deps.

**Out of scope for this branch:**
- Skeleton **replay** (record only this round).
- Explicit `NavState` enum. State remains encoded as sticky-field nullability + early-return invariants at the top of `tick()`.
- Planner-tile cost biasing toward the corridor (weighted Dijkstra). Anchors-as-subgoals chains short same-plane segments; the shortest BFS path within each is corridor-aligned in practice.
- Exploratory transport policy (unknown-ladder learning during scripts). Conservative-only.
- Broader minimap-fallback path-membership assertion inside the dispatcher. After this branch, every V21 walk dispatch is fed from a fresh BFS path (transport approach uses strict-walk explicitly; everything else trivially since the off-path BudgetExhausted call site is removed). Promoting this from an emergent property to a dispatcher-enforced invariant is future work.
- Refactoring `BlockerScanner.findClimbInScene` away. Deprecated, unused by V21, kept for inspection-time callers.

**Tick priorities:**

```
1. Snapshot world (multi-plane collision).
2. Goal satisfied → ARRIVED (also: write RouteSkeleton).
3. Dispatcher busy → RUNNING.
4. solver.evaluatePending → progressed/failed transitions, dead-end signal when applicable.
5. HARD_STALL check.
6. pendingExit  → handlePendingExit (sticky).
7. pendingTransport → handlePendingTransport (sticky).
8. Trail anchor (in order): if guide present, next anchor on current plane, reachable, not dead-end
   → adopt as pendingTransport (transport class) or pendingExit (local class).
9. StaticPlanner.plan(playerTile, effectiveSameplaneGoal):
   - Success → walker.walkAlong path.
   - BlockedEdge → handleBlockedEdge (perimeter-exit ranking uses corridor proximity when guide present).
   - PlaneMismatch → handlePlaneMismatch:
       a. TransportRouter.findNext (uses multi-plane collision when scene covers both endpoints).
       b. If guide present, walk to next anchor-on-current-plane approach tile (in trail order).
       c. If guide present and no current-plane anchor, walk to nearest corridor tile (on current plane) closest to the next anchor / trail terminus.
       d. Planar projection of goal centroid on current plane.
       e. FAILED typed.
   - BudgetExhausted → walker.walkAlong(pathToFrontier). If no frontier improvement → FAILED typed.
   - NoCandidate → FAILED.
```

The "effective same-plane goal" passed to the planner at step 9 is, when `trailGuide` is present, the first unfinished anchor's `approachTile` if it's on the current plane; otherwise the trail's terminal area; otherwise the request's final goal centroid. Without a trail, it's just the final goal.

---

## File map

**Create**
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v21/TrailGuide.java`
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v21/InteractionAnchor.java`
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v21/AnchorSelector.java`
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v21/TransportCandidate.java` (router-internal diagnostic wrapper, see Task 6)
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v21/TransportRouter.java`
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v21/GoalDeadEndKey.java`
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v21/GoalDeadEndMemory.java`
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v21/RouteSkeleton.java`
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v21/RouteSkeletonStore.java`
- Tests under `runelite-client/src/test/java/net/runelite/client/plugins/recorder/nav/v21/`:
  - `TrailGuideTest.java`, `AnchorSelectorTest.java`, `TransportRouterTest.java`, `GoalDeadEndMemoryTest.java`

**Modify**
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v21/V21Env.java` — multi-plane collision capture, new deps.
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v21/LiveCollisionView.java` — `captureAllPlanes` factory.
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v21/StaticPlanner.java` — `PlanResult.BudgetExhausted` enriched with frontier path.
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v21/V21Navigator.java` — anchor rung, transport rung, sticky `BlockerCandidate` slots, dead-end signal, skeleton write on arrival, BudgetExhausted handler.
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v21/BlockerScanner.java` — `findPerimeterExits` accepts optional corridor; new `findObjectInScene` helper. Deprecate `findClimbInScene`.
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v21/WalkExecutor.java` — `walkAlongStrict` wrapper + freshness assertion (first path tile == playerNow).
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPlugin.java` — wire deps; register flush callback for dead-ends + skeletons.

**Untouched**
- `ReactiveSolver` — 20s short-term blacklist is the right tool for door spam; dead-end memory is a separate, longer-lived layer.
- `BlockerScanner.findClimbInScene` and `pickNearestWithVerb` — left in place, deprecated, unreferenced by V21 callers.

---

## Task 1: Plumb new deps into `V21Env`

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v21/V21Env.java`
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v21/LiveCollisionView.java`
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPlugin.java` (`buildV21Navigator`)

- [ ] **Step 1: Multi-plane collision capture**

Add `public static LiveCollisionView[] captureAllPlanes(WorldView wv)` to `LiveCollisionView` — returns a 4-element array indexed by plane. Loops 0..3 and calls the existing per-plane capture for each; planes without flag data return `EMPTY` rather than throwing.

- [ ] **Step 2: Snapshot carries the 4-plane array**

Update `V21Env.Snapshot` to add `LiveCollisionView[] collisionByPlane`. The existing `collision` field stays as the player-plane shorthand, equal to `collisionByPlane[plane]`. All existing callers keep working.

- [ ] **Step 3: Inject new deps**

`V21Env` constructor becomes `(Client, ClientThread, HumanizedInputDispatcher, TransportIndex, TrailRegistry, GoalDeadEndMemory, RouteSkeletonStore)`. Add getters: `transports()`, `trails()`, `deadEnds()`, `skeletons()`. `Objects.requireNonNull` each.

- [ ] **Step 4: Wire it in `RecorderPlugin.buildV21Navigator`**

Pass `transportIndex` (already at line ~431), `trailRegistry` (field at line ~158), plus two new stores constructed at plugin init.

- [ ] **Step 5: Compile and commit**

Run: `JAVA_HOME=$(dirname $JBIN)/.. ./gradlew :client:compileJava`
Commit: `feat(nav-v21): inject TransportIndex/TrailRegistry/dead-end+skeleton stores into V21Env`

---

## Task 2: `WalkExecutor.walkAlongStrict` + freshness assertion

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v21/WalkExecutor.java`

- [ ] **Step 1: Inspect dispatcher's strict-walk API**

Run: `grep -n "strict\|isLeftClickWalk" runelite-client/src/main/java/net/runelite/client/sequence/dispatch/HumanizedInputDispatcher.java | head -30`

Confirm where `strict` is set — `ActionRequest` builder or per-call. The existing log line `strict walk at (X,Y) — top='Climb-up Ladder' not WALK; aborting (no minimap fallback)` confirms the path.

- [ ] **Step 2: Add `walkAlongStrict(path, playerNow)`**

Identical to `walkAlong` but the dispatched walk has `strict=true` (no minimap fallback when canvas hover isn't WALK).

- [ ] **Step 3: Freshness assertion**

In both `walkAlong` and `walkAlongStrict`, add `if (path.isEmpty() || !path.get(0).equals(playerNow)) { log.warn("v21.walk: stale path — first tile {} != playerNow {}", path.isEmpty() ? null : path.get(0), playerNow); return; }`. This catches callers feeding a stale path from a previous tick.

- [ ] **Step 4: Deprecate `walkTo(WorldPoint, WorldPoint)`**

After Task 10 Step 9 removes the only V21 caller of `walker.walkTo`, mark the method `@Deprecated` with a javadoc note: `@deprecated v21 dispatches walks only through walkAlong / walkAlongStrict with a BFS-validated path. Any new call site must opt into bypassing path validation — prefer constructing a path with StaticPlanner first.` The method body stays so other parts of the tree can compile; the deprecation surfaces accidental new uses.

- [ ] **Step 5: Compile and commit**

Commit: `feat(nav-v21): walkAlongStrict + path-freshness sanity check + deprecate walkTo`

---

## Task 3: Perimeter-exit ranking accepts corridor tiebreaker

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v21/BlockerScanner.java`

- [ ] **Step 1: Extend `findPerimeterExits` signature**

New overload: `List<BlockerCandidate> findPerimeterExits(WorldPoint from, WorldPoint goalCentroid, int radius, CollisionView col, @Nullable List<WorldPoint> corridor)`. The old 4-arg overload delegates with `corridor=null`.

- [ ] **Step 2: Tiebreak rule**

When two exits' Chebyshev-to-goal differ by ≤1, prefer the one whose `objectTile` has lower Chebyshev to the nearest corridor tile. With `corridor=null`, behavior is unchanged.

- [ ] **Step 3: Audit V21Navigator's call site**

The existing call (V21Navigator.java:248–250) currently passes 4 args. Leave it pointing at the new overload's `null`-corridor delegate; Task 10 wires the active corridor in.

- [ ] **Step 4: Compile and commit**

Commit: `feat(nav-v21): corridor-aware tiebreak in BlockerScanner.findPerimeterExits`

---

## Task 4: `TrailGuide` + `InteractionAnchor` (with observed dest plane)

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v21/InteractionAnchor.java`
- Create: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v21/TrailGuide.java`
- Test: `runelite-client/src/test/java/net/runelite/client/plugins/recorder/nav/v21/TrailGuideTest.java`

- [ ] **Step 1: `InteractionAnchor` record**

```
public record InteractionAnchor(
    int objectId,
    String verb,
    WorldPoint objectTile,
    WorldPoint approachTile,
    String targetKind,
    @Nullable Integer observedDestPlane)   // null if trail data didn't capture a post-transport Tile

  boolean isTransportAnchor()
    // true if observedDestPlane != null && observedDestPlane != objectTile.getPlane();
    // false if observedDestPlane != null && observedDestPlane == objectTile.getPlane();
    // VERB FALLBACK only if observedDestPlane is null:
    //   - verb in LOCAL_VERBS (Open, Pass, Pay, Push, Pick-lock, Close) → local
    //   - else → assume transport
```

`LOCAL_VERBS` is a static `Set<String>` on `InteractionAnchor`.

- [ ] **Step 2: `TrailGuide` record + derivation**

```
public record TrailGuide(
    List<WorldPoint> corridor,        // unmodifiable copy of all Tile event tiles
    List<InteractionAnchor> anchors)  // in trail order
```

Factory `TrailGuide.fromTrail(Trail trail)` walks `trail.events()`. For each event:
- `TrailEvent.Tile t` → append `t.tile()` to corridor.
- `TrailEvent.Transport tr` → look ahead in the event list for the next `TrailEvent.Tile` (any index after `tr`). If found, `observedDestPlane = nextTile.tile().getPlane()`. If the trail ends with this transport (no further Tile), `observedDestPlane = null`. Build the anchor and append.

Empty trail yields an empty guide. All lists are defensively unmodifiable.

- [ ] **Step 3: Tests**

Fixtures (build `Trail` objects in-memory with a small list of events):
1. Empty trail → empty guide.
2. Walk-only trail → empty anchors, populated corridor.
3. One transport followed by tiles on the same plane → anchor with `observedDestPlane == objectTile.plane`; `isTransportAnchor() == false`.
4. One transport followed by tiles on a different plane → anchor with `observedDestPlane != objectTile.plane`; `isTransportAnchor() == true`.
5. Transport at end of trail (no following Tile) → anchor with `observedDestPlane == null`; verb fallback decides.
6. Multi-transport trail → anchors in trail order; each has its own observedDestPlane derived from the next Tile event AFTER that specific transport.

Run: `./gradlew :client:test --tests TrailGuideTest`
Expected: FAIL pre-implementation, PASS after Step 2.

- [ ] **Step 4: Commit**

Commit: `feat(nav-v21): TrailGuide + InteractionAnchor with observed dest plane`

---

## Task 5: `AnchorSelector` with approach reachability

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v21/AnchorSelector.java`
- Test: `runelite-client/src/test/java/net/runelite/client/plugins/recorder/nav/v21/AnchorSelectorTest.java`

**Behaviour (pure compute):**

```
selectActive(guide,
             startSearchIndex,
             playerTile,
             snap,                                  // for collision + scene reads
             isDeadEnd: Predicate<InteractionAnchor>,
             findInScene: (objectId, near, radius) -> TileObject|null
            ) -> Optional<Active>

where Active = (anchor, indexInGuide, sceneObject).

for i = startSearchIndex .. guide.anchors().size() - 1:
    a = guide.anchors().get(i)
    if isDeadEnd.test(a): continue
    if a.approachTile().getPlane() != playerTile.getPlane(): continue
    TileObject obj = findInScene(a.objectId(), a.objectTile(), SCENE_PROBE_RADIUS)
    if obj == null: continue
    # Reachability check: can StaticPlanner plan to approachTile on current plane?
    PlanResult pr = new StaticPlanner(snap.collision()).plan(
        playerTile, new Goal.Area(a.approachTile(), 1))
    if (!(pr instanceof PlanResult.Success)): continue   # require direct reachability now
    return Optional.of(new Active(a, i, obj))
return Optional.empty()

SCENE_PROBE_RADIUS = 8     # scan within 8 tiles of recorded objectTile
```

Only `PlanResult.Success` counts. `PlanResult.BlockedEdge` means the approach isn't directly walkable right now — let the regular planner pipeline hit the BlockedEdge and `handleBlockedEdge` open the door first; once the door is open, the next tick's selector will see `Success` and adopt the anchor. Anchor selection means "currently approachable," not "maybe approachable after reactive solving." `NoCandidate` / `BudgetExhausted` / `PlaneMismatch` likewise skip.

- [ ] **Step 1: Tests first**

Cases:
1. Empty guide → empty.
2. First anchor matches plane, reachable, in scene → return index 0.
3. First anchor on different plane → skip; if next matches plane & reachable & in scene, return that.
4. First anchor dead-end-marked → skipped.
5. First anchor object not in scene → skipped.
6. First anchor in scene but `StaticPlanner` returns `NoCandidate` for its approach → skipped.
7. First anchor in scene and `StaticPlanner` returns `BlockedEdge` for its approach → **skipped** (only Success counts; the regular planner pipeline opens the door first, anchor becomes selectable on a subsequent tick).
8. `startSearchIndex` past end → empty.

Tests pass a fake `findInScene` to inject scene state and a fake collision to inject reachability.

Run: `./gradlew :client:test --tests AnchorSelectorTest`

- [ ] **Step 2: Implement**

Pure compute. Selector does not click — it returns the anchor for the navigator to set up as `pendingExit` or `pendingTransport` based on `anchor.isTransportAnchor()`.

- [ ] **Step 3: Commit**

Commit: `feat(nav-v21): AnchorSelector with plane match + approach reachability`

---

## Task 6: `TransportCandidate` + `TransportRouter`

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v21/TransportCandidate.java`
- Create: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v21/TransportRouter.java`
- Test: `runelite-client/src/test/java/net/runelite/client/plugins/recorder/nav/v21/TransportRouterTest.java`

**`TransportCandidate` (router-internal return type, NOT a sticky field type):**

```
public record TransportCandidate(
    TransportEdge edge,            // always a real edge from TransportIndex; never synthetic
    BlockerCandidate executable,   // the click target the navigator stores in pendingTransport
    double estimatedTotalCost,
    int chainLength)

  static TransportCandidate of(TransportEdge edge, TileObject obj,
                               WorldPoint playerApproach, double cost, int chain)
```

The navigator extracts `executable` and stores it in `pendingTransport`. `edge`, `estimatedTotalCost`, `chainLength` are kept only for the immediate log line that announces the selection. `TransportCandidate` is never long-lived state.

**Router algorithm:**

```
findNext(playerTile, goalCentroid, index, collisionByPlane, sceneFinder, isBlacklisted)
  -> Optional<TransportCandidate>

best = null
for each TransportEdge e in index.getAll():
    if e.fromTile.plane != playerTile.plane: continue
    if isBlacklisted.test(e): continue
    # Require the edge's object to be in the current scene
    TileObject obj = sceneFinder.find(e.objectId(), e.fromTile(), SCENE_PROBE_RADIUS)
    if obj == null: continue
    # Walk-to-source: must be reachable on player plane via collision BFS
    walkCost = bfsDistance(playerTile, e.approachTile(), collisionByPlane[playerTile.plane])
    if walkCost == ∞: continue
    forwardCost = estimateForwardCost(
        e.toTile(), goalCentroid, index, collisionByPlane, isBlacklisted,
        visited = {e.key()}, depth = 0)
    if forwardCost == ∞: continue
    total = walkCost + TRANSPORT_STEP_COST + forwardCost
    if best == null or total < best.estimatedTotalCost:
        best = TransportCandidate.of(e, obj, playerTile, total, depth+1)
return Optional.ofNullable(best)

estimateForwardCost(from, goal, index, collisionByPlane, isBlacklisted, visited, depth):
    if from.plane == goal.plane:
        return bfsOrChebyshev(from, goal, collisionByPlane[goal.plane])
    if depth >= MAX_CHAIN_DEPTH: return ∞
    best = ∞
    for each TransportEdge e2 in index.getAll():
        if visited.contains(e2.key()): continue
        if e2.fromTile.plane != from.plane: continue
        if isBlacklisted.test(e2): continue
        walk = bfsOrChebyshev(from, e2.approachTile(), collisionByPlane[from.plane])
        if walk > MAX_INTER_TRANSPORT_WALK: continue
        next = estimateForwardCost(e2.toTile(), goal, index, collisionByPlane,
                                   isBlacklisted, visited ∪ {e2.key()}, depth + 1)
        if next == ∞: continue
        best = min(best, walk + TRANSPORT_STEP_COST + next)
    return best

bfsOrChebyshev(a, b, view):
    if view != null and !view.isEmpty() and bothInside(a, b, view):
        d = bfsDistance(a, b, view)
        return d   # ∞ if blocked, finite if reachable
    return chebyshev(a, b)   # scene doesn't cover, fall back to estimate
```

`sceneFinder.find` is `(id, near, r) -> env.onClient(() -> env.scanner().findObjectInScene(id, near, r))` injected by the navigator. The router's `executable: BlockerCandidate` is constructed as `new BlockerCandidate(obj, edge.verb(), playerTile)` so the blacklist key matches what `ReactiveSolver` produces.

Constants: `TRANSPORT_STEP_COST = 4`, `MAX_CHAIN_DEPTH = 4`, `MAX_INTER_TRANSPORT_WALK = 64`, `SCENE_PROBE_RADIUS = 8`.

`bfsDistance` is a small distance-only BFS in `TransportRouter` (no need to refactor `StaticPlanner` for this — same primitive `SkretzoBfsKernel.canMove`, returns an int).

- [ ] **Step 1: TDD tests**

Cases:
1. Empty index → empty.
2. Single edge, wrong source plane → empty.
3. Single edge, source on player plane, destination on goal plane, scene contains object → returned with non-null `executable`.
4. Two edges both reach goal plane; the one whose approach is reachable via collision wins over the one whose approach is BFS-blocked.
5. Two-hop chain through known transports → returned.
6. Two-hop chain where the only intermediate destination is provably blocked on its plane (BFS=∞) → returned empty.
7. Same as 6 but destination plane has empty collision view → falls back to Chebyshev, returns candidate.
8. Blacklisted edge → skipped.
9. Edge's object not in scene → skipped.

Run: `./gradlew :client:test --tests TransportRouterTest`

- [ ] **Step 2: Implement**

Pure Java. `TransportCandidate.executable` populated only when a real `TileObject` was found via `sceneFinder`. Recursion bounded by `MAX_CHAIN_DEPTH`. Visited set copied on branch.

- [ ] **Step 3: Tests pass**

- [ ] **Step 4: Commit**

Commit: `feat(nav-v21): TransportRouter — multi-plane known-edge BFS with collision reachability`

---

## Task 7: `GoalDeadEndKey` + `GoalDeadEndMemory`

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v21/GoalDeadEndKey.java`
- Create: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v21/GoalDeadEndMemory.java`
- Test: `runelite-client/src/test/java/net/runelite/client/plugins/recorder/nav/v21/GoalDeadEndMemoryTest.java`

```
record GoalDeadEndKey(
    @Nullable String routeKey,
    int objectId,
    String verb,
    int fromBucketX, int fromBucketY, int fromPlane,    // BUCKET_BITS = 4
    int goalPlane,
    int goalBucketX, int goalBucketY)

  static GoalDeadEndKey fromEdge(String routeKey, TransportEdge e, WorldPoint goalCentroid)
  static GoalDeadEndKey fromAnchor(String routeKey, InteractionAnchor a, WorldPoint goalCentroid)
  static GoalDeadEndKey fromBlocker(String routeKey, BlockerCandidate b, WorldPoint goalCentroid)
  String toJsonString()
  static GoalDeadEndKey parseJson(String s)
```

Three factories so the key shape stays consistent across edge / anchor / live blocker without duplicating field-extraction logic.

`GoalDeadEndMemory`:

```
record Entry(GoalDeadEndKey key, String reason, int failCount,
             long firstFailedAtMs, long lastFailedAtMs)

boolean isDeadEnd(GoalDeadEndKey k, long nowMs)
void markDeadEnd(GoalDeadEndKey k, String reason, long nowMs)
boolean takeDirty()
Collection<Entry> snapshot()
void replaceAll(Collection<Entry> loaded)

TTL_MS = 7L * 24 * 60 * 60 * 1000
```

**No `clearForRoute`.** Negative memory expires only via TTL.

**Persistence:** flat JSON array at `~/.runelite/recorder/worldmap/v21-deadends.json`. Mirror `TransportIO`'s style; flushed by the existing `FlushDaemon` via a one-line `addFlushCallback(Runnable)` if not already present.

- [ ] **Step 1: Tests first**

1. Empty → `isDeadEnd` false.
2. After `markDeadEnd`, pre-TTL → true.
3. After TTL → false.
4. `takeDirty` true after mark, false on subsequent call.
5. JSON round-trip.
6. Keys with same `routeKey` but different goal buckets → independent.
7. `fromEdge` and `fromBlocker` for the same underlying interaction produce equal keys (sanity).

Run: `./gradlew :client:test --tests GoalDeadEndMemoryTest`

- [ ] **Step 2: Implement key + memory + IO**

- [ ] **Step 3: Wire `FlushDaemon` callback**

- [ ] **Step 4: Tests pass**

- [ ] **Step 5: Commit**

Commit: `feat(nav-v21): GoalDeadEndMemory with TTL-only expiry and JSON sidecar`

---

## Task 8: `RouteSkeleton` + `RouteSkeletonStore` (record only)

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v21/RouteSkeleton.java`
- Create: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v21/RouteSkeletonStore.java`

```
record RouteSkeleton(
    @Nullable String routeKey,
    WorldPoint goalCentroid,
    int goalPlane,
    List<String> transportEdgeKeys,
    long recordedAtMs)

class RouteSkeletonStore:
    void recordSuccess(RouteSkeleton s)        # replaces existing for same (routeKey, goalBucket)
    Collection<RouteSkeleton> snapshot()
    boolean takeDirty()
```

Persisted to `~/.runelite/recorder/worldmap/v21-skeletons.json`. Flushed on the same hook as dead-ends. No replay this round.

- [ ] **Step 1: Implement + round-trip test**

`RouteSkeletonStoreTest.java` has one round-trip test.

- [ ] **Step 2: Commit**

Commit: `feat(nav-v21): RouteSkeleton recorder (write-only) for successful runs`

---

## Task 9: Enrich `StaticPlanner.PlanResult.BudgetExhausted`

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v21/StaticPlanner.java`
- Modify (sibling): `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v21/PlanResult.java`

- [ ] **Step 1: Add fields to `PlanResult.BudgetExhausted`**

Current type holds `int expanded` only. Replace with:

```
record BudgetExhausted(
    int expanded,
    WorldPoint bestVisited,             // the BFS frontier tile closest in Chebyshev to goal centroid
    List<WorldPoint> pathToBestVisited  // reconstructed from parent map; first tile == start
) implements PlanResult
```

If no expansion was performed (start key was the only visited), `bestVisited == start` and `pathToBestVisited == List.of(start)`.

- [ ] **Step 2: Populate at the budget-exhausted site in `StaticPlanner.plan`**

The BFS loop already tracks `bestVisitedKey` and `bestVisitedDist` (`StaticPlanner.java:111–129`). At the `expanded++ >= MAX_EXPANSIONS` branch (line 117–120), reconstruct the path from `parent` and return the enriched record. Same reconstruction helper as `inferBlockedEdge` uses.

- [ ] **Step 3: Audit callers**

The only current consumer of `PlanResult.BudgetExhausted` is `V21Navigator.tick` at lines 180–186 — its handler is rewritten in Task 10 Step 9.

- [ ] **Step 4: Compile**

Commit: `feat(nav-v21): enrich BudgetExhausted with best-visited frontier path`

---

## Task 10: V21Navigator integration

This is the central behavioral change. We add the anchor and transport rungs, the sticky transport handler, the dead-end signal, the skeleton write, and the new BudgetExhausted handler. **No synthetic `TransportEdge`s anywhere.**

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v21/V21Navigator.java`

- [ ] **Step 1: New fields**

```
@Nullable private BlockerCandidate pendingTransport;   // BlockerCandidate, not TransportCandidate
@Nullable private TrailGuide trailGuide;
private int nextAnchorIndex = 0;
@Nullable private InteractionAnchor activeAnchor;       // the anchor whose click is sticky right now
// For inverse-transport detection + skeleton recording:
@Nullable private TransportEdge lastFiredEdge;
@Nullable private String pendingEdgeKey;                // edge.key() if the pending was router-picked
private long lastFiredAtMs;
// Captured AT DISPATCH TIME (inside attemptStickyClick, NOT each tick).
// Preserved across ticks until the pending resolves (PROGRESSED or FAILED),
// then cleared. Reading them in evaluatePending's result handler tells us
// what the world looked like before we clicked.
private int pendingStartPlane = -1;
@Nullable private WorldPoint pendingStartTile;
private long pendingStartedAtMs;
private final List<String> skeletonInProgress = new ArrayList<>();
```

- [ ] **Step 2: New-request reset**

In the existing `if (!Objects.equals(request, activeRequest))` block (V21Navigator.java:84–93):

```
String trailName = (request != null) ? request.trailName() : null;
trailGuide = (trailName != null && env.trails().byName(trailName) != null)
             ? TrailGuide.fromTrail(env.trails().byName(trailName))
             : null;
nextAnchorIndex = 0;
activeAnchor = null;
pendingTransport = null;
pendingEdgeKey = null;
lastFiredEdge = null;
pendingStartPlane = -1;
pendingStartTile = null;
pendingStartedAtMs = 0L;
skeletonInProgress.clear();
```

- [ ] **Step 3: `attemptStickyClick` helper — single capture site**

Add a private helper that wraps every `solver.attempt` call site so the pre-dispatch state is captured atomically with the dispatch:

```
private void attemptStickyClick(BlockerCandidate b, V21Env.Snapshot snap) {
    pendingStartPlane = snap.plane();
    pendingStartTile = snap.playerTile();
    pendingStartedAtMs = snap.nowMs();
    solver.attempt(b, snap.playerTile(), snap.nowMs());
}
```

**Every** `solver.attempt(...)` call inside `V21Navigator` is replaced with `attemptStickyClick(b, snap)`. Call sites: `handlePendingExit` (existing, line ~327), `handleBlockedEdge` blocker attempt (existing, line ~280), the new `handlePendingTransport` (Task 10 Step 5), and the anchor rung (Task 10 Step 6) — but the latter two go through `handlePendingExit` / `handlePendingTransport`, so updating those two methods covers all anchor-firing sites too. **Do NOT** capture inside the tick loop or before `evaluatePending`; the captures must persist from the dispatch tick to the resolution tick.

- [ ] **Step 4: Dead-end + skeleton signal on solver outcome**

In the existing solver-outcome block (V21Navigator.java:115–120):

```
if (out == PROGRESSED) {
    boolean planeChanged = (pendingStartPlane != -1
                            && pendingStartPlane != snap.plane());
    if (planeChanged && (activeAnchor != null || pendingEdgeKey != null)) {
        // Find the real TransportEdge that just fired by querying TransportIndex
        // for edges whose key matches what we dispatched. For anchor-driven dispatches,
        // TransportObserver may have written the edge during this tick; for router-driven
        // ones, pendingEdgeKey was set at dispatch.
        String firedKey = (pendingEdgeKey != null) ? pendingEdgeKey
                          : findEdgeKeyByObjectAndApproach(env.transports(),
                                activeAnchor.objectId(), activeAnchor.approachTile(),
                                activeAnchor.verb());
        if (firedKey != null) skeletonInProgress.add(firedKey);

        // Re-check forward reachability with the same predicates as the planner uses.
        Predicate<TransportEdge> isEdgeBad = buildEdgeBlacklistPredicate(snap, goal, routeKey());
        boolean canContinue =
            (trailGuide != null && AnchorSelector.selectActive(
                trailGuide, nextAnchorIndex,
                snap.playerTile(), snap,
                a -> deadEnds.isDeadEnd(GoalDeadEndKey.fromAnchor(routeKey(), a, goal.centroid()),
                                        snap.nowMs()),
                this::findInSceneOnClient).isPresent())
            || TransportRouter.findNext(snap.playerTile(), goal.centroid(),
                env.transports(), snap.collisionByPlane(),
                this::findInSceneOnClient, isEdgeBad).isPresent();

        if (!canContinue) {
            markDeadEndForFiredTransport(snap.nowMs(), "DESTINATION_NO_KNOWN_PROGRESS");
        }

        // Inverse-transport detection: forced reverse within 6 ticks.
        TransportEdge fired = (firedKey != null) ? env.transports().byKey(firedKey) : null;
        if (fired != null && lastFiredEdge != null
            && snap.nowMs() - lastFiredAtMs < 6 * 600
            && fired.fromTile().equals(lastFiredEdge.toTile())
            && fired.toTile().equals(lastFiredEdge.fromTile())
            && fired.objectId() == lastFiredEdge.objectId()) {
            markDeadEnd(lastFiredEdge, "FORCED_INVERSE_WITHIN_6_TICKS", snap.nowMs());
        }

        // Advance state
        if (activeAnchor != null) nextAnchorIndex++;
        activeAnchor = null;
        pendingTransport = null;
        pendingEdgeKey = null;
        lastFiredEdge = fired;
        lastFiredAtMs = snap.nowMs();
        // Clear pre-dispatch capture — pending resolved.
        pendingStartPlane = -1;
        pendingStartTile = null;
    } else if (!planeChanged) {
        // No plane change. If we expected one (transport class), clear pending
        // and let the next tick replan. If this was a local door anchor,
        // PROGRESSED just means we walked through — also clear and continue.
        pendingTransport = null;
        activeAnchor = null;
        pendingEdgeKey = null;
        pendingStartPlane = -1;
        pendingStartTile = null;
    }
}
if (out == FAILED) {
    // Solver already short-blacklisted. Clear sticky state; do NOT mark dead-end
    // here — that's the long-term layer and shouldn't fire on every solver miss.
    pendingTransport = null;
    activeAnchor = null;
    pendingEdgeKey = null;
    pendingStartPlane = -1;
    pendingStartTile = null;
}
```

Helpers (all live on V21Navigator):
- `routeKey()` → `(activeRequest != null) ? activeRequest.trailName() : null`.
- `findInSceneOnClient(id, near, r)` → `env.onClient(() -> env.scanner().findObjectInScene(id, near, r))`.
- `buildEdgeBlacklistPredicate(snap, goal, routeKey)` → `(e) -> { BlockerCandidate atHere = blockerForEdge(e, snap.playerTile()); if (atHere != null && solver.isBlacklisted(atHere, snap.nowMs())) return true; return deadEnds.isDeadEnd(GoalDeadEndKey.fromEdge(routeKey, e, goal.centroid()), snap.nowMs()); }`.
- `blockerForEdge(e, playerTile)` → finds the live `TileObject` for `e.objectId()` near `e.fromTile()` via the scanner; returns `new BlockerCandidate(obj, e.verb(), playerTile)` or null if not in scene.
- `findEdgeKeyByObjectAndApproach(...)` → calls `index.getOutgoing(approachTile)` and matches by `objectId` + `verb`; returns the matched edge's `key()` or null.
- `markDeadEndForFiredTransport(now, reason)` → builds the key from `activeAnchor` (preferred) or from the edge by `pendingEdgeKey`, and calls `deadEnds.markDeadEnd`.
- `TransportIndex.byKey(key)` → small new helper that returns the edge with that key, or null.

- [ ] **Step 5: Sticky transport handler**

```
@Nullable handlePendingTransport(BlockerCandidate t, V21Env.Snapshot snap):
    BlockerCandidate atHere = new BlockerCandidate(
        t.object(), t.verb(), snap.playerTile())   // freshen approach to current player tile
    if (solver.isBlacklisted(atHere, snap.nowMs())) {
        pendingTransport = null; pendingEdgeKey = null; return null;
    }
    if (activeAnchor != null
        && deadEnds.isDeadEnd(GoalDeadEndKey.fromAnchor(routeKey(), activeAnchor, goal.centroid()),
                              snap.nowMs())) {
        pendingTransport = null; activeAnchor = null; return null;
    }
    if (pendingEdgeKey != null) {
        TransportEdge e = env.transports().byKey(pendingEdgeKey);
        if (e != null
            && deadEnds.isDeadEnd(GoalDeadEndKey.fromEdge(routeKey(), e, goal.centroid()),
                                  snap.nowMs())) {
            pendingTransport = null; pendingEdgeKey = null; return null;
        }
    }

    int dist = chebyshev(snap.playerTile(), t.objectTile())
    if (dist > 2) {
        Goal sub = new Goal.Area(t.objectTile(), 1);
        PlanResult sub = new StaticPlanner(snap.collision()).plan(snap.playerTile(), sub);
        if (sub instanceof PlanResult.Success s):
            walker.walkAlongStrict(s.tiles(), snap.playerTile()); return NavStatus.RUNNING;
        if (sub instanceof PlanResult.BlockedEdge be):
            return handleBlockedEdge(be, snap);
        # NoCandidate / PlaneMismatch / BudgetExhausted at this scale → give up the pending,
        # let the main tick replan from scratch.
        pendingTransport = null; pendingEdgeKey = null; return null;
    }
    attemptStickyClick(atHere, snap)        // captures pendingStartPlane/Tile/Ms then solver.attempt
    return NavStatus.RUNNING
```

`t.objectTile()` is the object's recorded tile; the approach is implicit (snap.playerTile within 2 tiles of objectTile means the dispatcher can resolve the verb). `attemptStickyClick` is the only entry point that dispatches — never call `solver.attempt` directly from V21Navigator.

- [ ] **Step 6: Anchor rung (in-order, reachability-checked, no synthetic edges)**

Inserted between the existing `pendingTransport` early-return and the planner call:

```
if (trailGuide != null && pendingTransport == null && pendingExit == null) {
    Optional<AnchorSelector.Active> picked = AnchorSelector.selectActive(
        trailGuide, nextAnchorIndex, snap.playerTile(), snap,
        a -> deadEnds.isDeadEnd(GoalDeadEndKey.fromAnchor(routeKey(), a, goal.centroid()),
                                snap.nowMs()),
        this::findInSceneOnClient);
    if (picked.isPresent()) {
        InteractionAnchor a = picked.get().anchor();
        TileObject obj = picked.get().sceneObject();
        BlockerCandidate bc = new BlockerCandidate(obj, a.verb(), snap.playerTile());
        activeAnchor = a;
        if (!a.isTransportAnchor()) {
            pendingExit = bc;
            NavStatus s = handlePendingExit(pendingExit, snap);
            return (s != null) ? s : NavStatus.RUNNING;
        } else {
            pendingTransport = bc;
            // pendingEdgeKey stays null — anchor may or may not correspond to an index edge.
            // The PROGRESSED handler resolves the actual fired edge via TransportObserver/index.
            NavStatus s = handlePendingTransport(pendingTransport, snap);
            return (s != null) ? s : NavStatus.RUNNING;
        }
    }
}
```

- [ ] **Step 7: Rewrite `handlePlaneMismatch`**

```
handlePlaneMismatch(snap):
    goal = activeGoal
    String rk = routeKey()

    # 1. TransportRouter on known graph.
    Predicate<TransportEdge> isEdgeBad = buildEdgeBlacklistPredicate(snap, goal, rk)
    Optional<TransportCandidate> next = TransportRouter.findNext(
        snap.playerTile(), goal.centroid(), env.transports(),
        snap.collisionByPlane(), this::findInSceneOnClient, isEdgeBad)
    if (next.isPresent()):
        pendingTransport = next.get().executable()
        pendingEdgeKey = next.get().edge().key()
        log.info("v21.transport: chosen edge {} chain={} cost={}",
            pendingEdgeKey, next.get().chainLength(), next.get().estimatedTotalCost())
        NavStatus s = handlePendingTransport(pendingTransport, snap)
        return (s != null) ? s : NavStatus.RUNNING

    # 2. Trail-guided same-plane progression toward the next anchor in trail order
    #    whose approachTile is on the current plane (respects anchor order — does not
    #    skip earlier same-plane gates/doors in favor of a later plane-changing anchor).
    if (trailGuide != null):
        InteractionAnchor next = nextAnchorOnCurrentPlane(trailGuide, nextAnchorIndex, snap.plane())
        if (next != null):
            Goal sub = new Goal.Area(next.approachTile(), 1)
            PlanResult planar = new StaticPlanner(snap.collision()).plan(snap.playerTile(), sub)
            if (planar instanceof PlanResult.Success s): walker.walkAlong(s.tiles(), snap.playerTile()); return RUNNING
            if (planar instanceof PlanResult.BlockedEdge be): return handleBlockedEdge(be, snap)
            if (planar instanceof PlanResult.BudgetExhausted bx):
                walker.walkAlong(bx.pathToBestVisited(), snap.playerTile()); return RUNNING

    # 3. Trail-corridor fallback on current plane (when no anchor remains on this
    #    plane — bot is between anchors, or all upcoming anchors are on other
    #    planes and we need to make corridor-aligned progress before the right
    #    transport comes into scene). Still a better guide than raw goal-XY
    #    projection.
    if (trailGuide != null):
        WorldPoint corridorTarget = trailCorridorTargetOnCurrentPlane(
            trailGuide, nextAnchorIndex, snap.playerTile(), goal.centroid(), snap.collision())
        if (corridorTarget != null):
            PlanResult cor = new StaticPlanner(snap.collision()).plan(
                snap.playerTile(), new Goal.Area(corridorTarget, 1))
            if (cor instanceof PlanResult.Success s): walker.walkAlong(s.tiles(), snap.playerTile()); return RUNNING
            if (cor instanceof PlanResult.BlockedEdge be): return handleBlockedEdge(be, snap)
            if (cor instanceof PlanResult.BudgetExhausted bx):
                walker.walkAlong(bx.pathToBestVisited(), snap.playerTile()); return RUNNING

    # 4. Planar projection of goal centroid (last resort before failure).
    WorldPoint planarTarget = new WorldPoint(goal.centroid().getX(), goal.centroid().getY(), snap.plane())
    Goal planarGoal = new Goal.Area(planarTarget, 1)
    PlanResult planar = new StaticPlanner(snap.collision()).plan(snap.playerTile(), planarGoal)
    if (planar instanceof PlanResult.Success s): walker.walkAlong(s.tiles(), snap.playerTile()); return RUNNING
    if (planar instanceof PlanResult.BlockedEdge be): return handleBlockedEdge(be, snap)
    if (planar instanceof PlanResult.BudgetExhausted bx):
        walker.walkAlong(bx.pathToBestVisited(), snap.playerTile()); return RUNNING

    # 5. Typed FAILED.
    log.warn("v21: NO_KNOWN_TRANSPORT_ROUTE_TO_GOAL player={} goal={} routeKey={}",
        snap.playerTile(), goal.centroid(), rk)
    return NavStatus.FAILED
```

`nextAnchorOnCurrentPlane(guide, fromIndex, plane)` iterates `anchors.subList(fromIndex, size)` and returns the first whose `approachTile.getPlane() == plane`, or null. Same-plane gates and doors are returned before plane-changing stairs naturally because they come first in trail order.

`trailCorridorTargetOnCurrentPlane(guide, fromIndex, player, goalCentroid, collision)` chooses a single corridor tile to walk toward when no anchor remains on the current plane:

```
WorldPoint referenceTile;
if (fromIndex < guide.anchors().size()):
    referenceTile = guide.anchors().get(fromIndex).approachTile()
else:
    referenceTile = goalCentroid                # past the last anchor, head toward the goal

WorldPoint best = null
int bestDistToRef = Integer.MAX_VALUE
for (WorldPoint t : guide.corridor()):
    if (t.getPlane() != player.getPlane()) continue
    if (t.equals(player)) continue
    if (chebyshev(player, t) > 32) continue      # out of scene / practical walk range
    int dToRef = chebyshev(t, referenceTile)
    if (dToRef < bestDistToRef):
        bestDistToRef = dToRef
        best = t
if (best == null) return null
PlanResult p = new StaticPlanner(collision).plan(player, new Goal.Area(best, 1))
return (p instanceof PlanResult.Success) ? best : null
```

Cost: O(N) corridor scan + one BFS for the chosen tile. Returns null if nothing reachable; caller falls through to planar projection. Bias toward the corridor tile **closest to the next anchor** (or to the goal, if past the last anchor) means natural forward progress along the recorded route.

- [ ] **Step 8: Pass corridor to `findPerimeterExits`**

In `handleBlockedEdge`'s `findPerimeterExits` call, pass `trailGuide != null ? trailGuide.corridor() : null`.

- [ ] **Step 9: BudgetExhausted handler in the main tick**

Replace V21Navigator.java:180–186:

```
if (plan instanceof PlanResult.BudgetExhausted bx) {
    List<WorldPoint> path = bx.pathToBestVisited();
    if (path == null || path.size() <= 1 || path.get(path.size() - 1).equals(snap.playerTile())) {
        log.warn("v21: BUDGET_EXHAUSTED_NO_PROGRESS expanded={} player={} goal={}",
            bx.expanded(), snap.playerTile(), goal.centroid());
        return NavStatus.FAILED;
    }
    walker.walkAlong(path, snap.playerTile());
    return NavStatus.RUNNING;
}
```

**Removed:** the previous `walker.walkTo(goal.centroid(), snap.playerTile())` call. There is no remaining off-path walk site in V21.

- [ ] **Step 10: Skeleton write on ARRIVED**

In the existing `if (goal.isSatisfied(snap.playerTile()))` block (V21Navigator.java:100–105):

```
if (!skeletonInProgress.isEmpty()) {
    env.skeletons().recordSuccess(new RouteSkeleton(
        routeKey(), goal.centroid(), goal.centroid().getPlane(),
        List.copyOf(skeletonInProgress), snap.nowMs()));
}
skeletonInProgress.clear();
```

No call to `deadEnds.clearForRoute(...)` — that method does not exist.

- [ ] **Step 11: Compile**

Run: `JAVA_HOME=$(dirname $JBIN)/.. ./gradlew :client:compileJava`

- [ ] **Step 12: Commit**

Commit: `feat(nav-v21): trail-guided dynamic navigation — anchors-by-observed-plane, no synthetic edges, BudgetExhausted walks frontier, anchor order respected`

---

## Task 11: Deprecate `findClimbInScene` callers within v21

- [ ] **Step 1: Grep for in-package callers**

Run: `grep -rn "findClimbInScene" runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v21/`
Expected: zero callers.

- [ ] **Step 2: `@deprecated` note** on the method itself (`BlockerScanner.findClimbInScene`):

`@deprecated v21 plane-mismatch no longer scans for nearby Climb verbs — see TrailGuide anchors + TransportRouter.`

- [ ] **Step 3: Commit**

Commit: `chore(nav-v21): deprecate findClimbInScene`

---

## Task 12: Perimeter-exit stickiness audit

- [ ] **Step 1: Verify `pendingExit` lifecycle**

- Set in `handleBlockedEdge` only when previously null.
- Cleared on new request, solver PROGRESSED/FAILED, blacklisted/unreachable in `handlePendingExit`.
- Consulted before the planner.
- Task 10 Step 6 only sets `pendingExit` when it's currently null.

- [ ] **Step 2: Commit if any tightening was needed; otherwise skip**

Commit (if needed): `chore(nav-v21): tighten pendingExit non-overwrite invariant`

---

## Task 13: Manual acceptance test

- [ ] **Step 1: Prepare baseline**

Confirm `~/.runelite/recorder/worldmap/transports.json` contains entries for the Lumbridge castle ground→floor1 and floor1→bank staircases. If not, manually run V1 trail or play through once.

Delete `~/.runelite/recorder/worldmap/v21-deadends.json` and `v21-skeletons.json` for a clean baseline.

Confirm `pen_to_lumby_bank` exists at `~/.runelite/recorder/trails/pen_to_lumby_bank.json`.

- [ ] **Step 2: Build the shadow jar (asked-for build only)**

```
JAVA_HOME=$(dirname $JBIN)/.. ./gradlew :client:shadowJar
$JBIN -ea --add-opens java.desktop/com.apple.eawt=ALL-UNNAMED \
  --add-opens java.desktop/com.apple.eawt.event=ALL-UNNAMED \
  --add-opens java.base/java.lang=ALL-UNNAMED \
  -jar runelite-client/build/libs/client-*.jar --developer-mode &
```

- [ ] **Step 3: Run the chicken farm script from the pen**

Expected log sequence (condensed):
1. `nav-v21: new request trailName=pen_to_lumby_bank`, then `v21.guide: anchors=N corridor=M`.
2. Anchor 0 fires — likely the pen gate via `pendingExit` (`isTransportAnchor=false`).
3. Walker proceeds along the road; subsequent anchors fire in trail order.
4. `objectId=16683` (general-store ladder) does NOT appear in any `v21.transport: chosen edge` or anchor-fire log.
5. `unfiltered fallback` does NOT appear anywhere.
6. Bot arrives at Lumbridge bank floor 2 → `nav-v21: ARRIVED`; `v21-skeletons.json` has one entry whose `transportEdgeKeys` lists the castle stair path.
7. `v21-deadends.json` is empty.

- [ ] **Step 4: Negative case — force a wrong transport observation**

Manually climb the general-store ladder once in a fresh session so `TransportObserver` records the edge. Restart the chicken farm script from the pen.

Either:
- Multi-plane collision rejects the general-store edge up front (player is close enough that plane=1 collision proves the upper floor doesn't reach the castle stair's approach), and you see `v21.router: candidate {key} rejected — forward unreachable`. Or:
- Player is too far for plane=1 collision to cover both endpoints; the router picks the edge by Chebyshev fallback; the bot climbs once, can't continue → `DESTINATION_NO_KNOWN_PROGRESS` fires; `v21-deadends.json` gains one entry; next attempt skips it and uses castle stair.

Either way, the SECOND cycle uses the castle stair.

- [ ] **Step 5: Stability — 3 consecutive cycles**

- No HARD_STALL warnings.
- `v21-deadends.json` size stable.
- `v21-skeletons.json` has at most one entry per direction per goal-bucket (replaces on re-success).

- [ ] **Step 6: Save a log excerpt**

Save key lines from Step 3 to `docs/superpowers/specs/2026-05-19-v21-destination-aware-transports-acceptance.md`.

Commit: `docs(nav-v21): acceptance excerpt for trail-guided dynamic navigation`

---

## Self-review

1. **Spec coverage** — each correction maps to a task:
   - "trail = corridor + anchors, no exact replay" → Tasks 4, 5, 10 Step 6, 7
   - "anchor selection in trail order, not just in-scene" → Task 10 Step 7 (`nextAnchorOnCurrentPlane` iterates from `nextAnchorIndex` in order)
   - "anchor reachability check, not just visibility" → Task 5 (`StaticPlanner.plan` rejects `NoCandidate`)
   - "classify by observed dest plane, not verb" → Task 4 Step 1 (`isTransportAnchor()` uses `observedDestPlane`)
   - "no synthetic TransportEdge" → Task 6 (TransportCandidate is router-internal; navigator stores BlockerCandidate); Task 10 Step 1 (`pendingTransport: BlockerCandidate`); Step 6 (anchor rung builds BlockerCandidate directly)
   - "no clear-on-success of dead-ends" → Task 7 has no `clearForRoute`; Task 10 Step 10 explicitly does not clear
   - "BudgetExhausted does not click goal centroid" → Task 9 enriches BudgetExhausted; Task 10 Step 9 walks the path; FAILED if no progress
   - "anchor order respected (WP7)" → Task 10 Step 7 (`nextAnchorOnCurrentPlane`)
   - "typed FAILED when no data" → Task 10 Step 7 rung 5 (`NO_KNOWN_TRANSPORT_ROUTE_TO_GOAL`)
   - "multi-plane collision" → Task 1 Step 1
   - "sticky pending subtask" → Task 10 Steps 5, 6 (early-return at top of tick)
   - "pre/post transport state captured at dispatch, not each tick" → Task 10 Step 1 (renamed fields `pendingStartPlane / pendingStartTile / pendingStartedAtMs`); Step 3 (`attemptStickyClick` helper is the only dispatch entry point and captures atomically); Step 4 reads those preserved values from the previous-tick dispatch.
   - "AnchorSelector requires Success, not BlockedEdge" → Task 5 logic + tests (case 7 explicitly skips BlockedEdge approaches).
   - "trail corridor fallback before planar projection" → Task 10 Step 7 rung 3 (`trailCorridorTargetOnCurrentPlane`).
   - "walkTo deprecated; off-path walk site removed" → Task 2 Step 4 (`@Deprecated` note); Task 10 Step 9 (BudgetExhausted no longer calls it).
2. **Minimap claim — narrowed.** This branch adds strict-walk to transport-approach final walks (Task 2) and the freshness assertion to `walkAlong` (Task 2 Step 3). It removes the only off-path walk site by changing the BudgetExhausted handler (Task 10 Step 9). Together that makes path membership an emergent property of V21 walk dispatches. **It is not a dispatcher-level enforced invariant** — promoting it to one is called out under "Out of scope."
3. **Placeholders** — none. Every step has concrete code, behavior, or a concrete command.
4. **Type consistency** —
   - `InteractionAnchor(objectId, verb, objectTile, approachTile, targetKind, observedDestPlane)` used consistently in Tasks 4, 5, 10. `isTransportAnchor()` referenced in Task 10 Step 6.
   - `TransportCandidate(edge, executable, estimatedTotalCost, chainLength)` used consistently in Tasks 6, 10 Step 7.
   - `pendingTransport: BlockerCandidate` consistent in Task 10 Steps 1, 5, 6, 7. No remaining reference to `TransportCandidate` as a long-term storage type.
   - `PlanResult.BudgetExhausted(expanded, bestVisited, pathToBestVisited)` consistent in Tasks 9, 10 Step 9.
   - `GoalDeadEndKey` factories `fromEdge` / `fromAnchor` / `fromBlocker` all return the same record shape. Used in Tasks 7, 10.
5. **Threading invariants** — `TransportRouter`, `TrailGuide`, `InteractionAnchor`, `AnchorSelector`, `GoalDeadEndMemory`, `RouteSkeletonStore`, `StaticPlanner` are all pure compute. Client-thread hops happen only via `env.onClient(...)` for scanner reads (the `findInSceneOnClient` helper). Blocking flows (clicks, walks) run on the dispatcher worker exactly as today.
6. **Risk** — biggest unknowns:
   - Multi-plane collision capture cost. Profile in Task 1; cache non-current planes lazily if >2ms p99.
   - `TransportRouter` BFS on destination planes the scene partially covers. Mitigations: `MAX_INTER_TRANSPORT_WALK=64` (matches scene radius), Chebyshev fallback when view is empty.
   - Dead-end memory growth across many trails. Mitigation: 7-day TTL + bucket coarseness.
   - Anchor-fired-but-no-edge-in-index ordering: when an anchor fires before `TransportObserver` writes the edge, `findEdgeKeyByObjectAndApproach` returns null and we skip recording that edge in the skeleton for this run. The next successful run picks it up. Acceptable.
