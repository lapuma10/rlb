# Rooftop Agility Script — v1 Design

**Status:** LOCKED 2026-05-21. Implementation-ready after Draynor tile/object capture.
**Scope:** Draynor Rooftop only. Single-course operation. Manual user-driven
course selection. Stop at configurable target level. Pick up marks of grace
on the current rooftop only. Eat food if HP drops, else stop.

## 1. Goal

Train Agility on the Draynor Rooftop course without manual input until the
configured target level is reached. Robust against the failure modes that
have bitten previous gameplay scripts:

- Self-correcting against the player ending up off-route (fall, manual move,
  resume mid-course).
- Conservative mark-of-grace pickup that never misroutes the player onto a
  rooftop they can't reach.
- No silent freezes from stuck right-click menus or stale dialogs.

## 2. Non-goals (v1)

- Inter-region travel. The user starts the script with the player already on
  Draynor; the script never walks to a different course or visits a bank.
- Course switching at level thresholds. A future v2 may add Al Kharid,
  Varrock, Canifis profiles and an "auto-progress" toggle; v1 ships only the
  `DRAYNOR` profile.
- Banking for food. If inventory has any edible food, eat it; if not, stop.
- XP/hour estimation, boosts, stamina potions, weight management.
- Unit tests. Per project memory (`feedback_no_tests_for_bot_scripts`),
  simple gameplay scripts are validated by manual in-game runs. Manual
  acceptance criteria are listed in §15.

## 3. File layout

Single file: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/scripts/RooftopAgilityScript.java`.

Modeled on `FletchingScript.java` (528 lines, committed 2026-05-20). Same
shape: package, `@Slf4j` logger tag `[rooftop-agility]`, enum state machine,
embedded data, `@Subscribe onGameTick` driver, dispatch via
`HumanizedInputDispatcher`.

Panel control is added to the existing `RecorderPanel` alongside the other
script entries, not as a new panel.

## 4. Threading model

Per `CLAUDE.md` §"Threading model":

| Operation | Thread |
|-----------|--------|
| `onGameTick` driver | client thread (event subscriber) |
| Read `WorldPoint`, varbits, inventory, widget visibility | client thread, via `clientThread.invoke` or already-on-client subscriber |
| Build `ActionRequest`, compare snapshot fields, pick the next decision | either thread (pure compute) |
| Multi-step input flows (right-click → menu pick → cursor → click) | dispatcher worker (`HumanizedInputDispatcher`) |
| Panel `Start`/`Stop` button handlers | EDT; spawn `Thread.start()` for any work that awaits dispatcher idle |

The tick loop reads snapshot fields, decides, updates `state` / `nextActionAt`,
and enqueues one `ActionRequest` per tick at most. It never sleeps and never
blocks on the dispatcher.

## 5. State machine

```java
enum State { IDLE, RUNNING, PICKING_MARK }
```

`IDLE` — script not started, or stopped. `RUNNING` — normal lap progression
including recovery walks. `PICKING_MARK` — mark-of-grace pickup dispatched,
waiting for confirmation.

There is no `RECOVERING` or `ABORTED` state. Recovery is a helper inside
`RUNNING`; stopping the script just sets `running = false` and `state = IDLE`.

## 6. Course profile data model

Tile lists are used for authoring (so coords pasted from the tile-marker
plugin are readable). At construction, lists are converted into `Set` and
`Map` for runtime lookups — every per-tick check is O(1).

```java
enum RooftopCourseId { DRAYNOR }   // v1 ships one entry

static final class RooftopCourse {
    final RooftopCourseId id;
    final String          label;
    final int             levelReq;

    final Set<WorldPoint>           startTiles;     // recovery destinations; ⊆ node[0].stageTiles
    final Set<WorldPoint>           validTiles;     // every tile that is "on route"
    final Set<WorldPoint>           fallTiles;      // street-level landing tiles after a failure
    final Set<WorldPoint>           lapEndTiles;    // where the final obstacle lands the player
    final List<RooftopNode>         nodes;          // ordered: first obstacle → last obstacle
    final Map<WorldPoint, Integer>  stageByTile;    // built from nodes; player tile → stage index
}

static final class RooftopNode {
    final String              label;                // "Rough wall", "Tightrope", ...
    final int                 objectId;
    final String              action;               // verb on right-click, e.g. "Climb"
    final Set<WorldPoint>     objectTiles;          // exact tile(s) of the obstacle object — disambiguates repeats
    final Set<WorldPoint>     stageTiles;           // player ∈ these → we are at this stage
    final Set<WorldPoint>     successTiles;         // optional; empty → next node's stageTiles / lapEndTiles
    final Set<WorldPoint>     reachableMarkTiles;   // marks pickable from this stage
    final long                timeoutMs;            // per-node, longer for tightropes
}
```

Construction takes the authoring lists and builds `stageByTile` once:

```java
Map<WorldPoint, Integer> stageByTile = new HashMap<>();
for (int i = 0; i < nodes.size(); i++) {
    for (WorldPoint t : nodes.get(i).stageTiles) {
        stageByTile.put(t, i);
    }
}
```

Authoring helper:

```java
private static Set<WorldPoint> tiles(int... xyp) {
    // xyp = x, y, plane triples; builds an unmodifiable Set<WorldPoint>
}
```

`successTiles` is optional. Resolved at lookup time:

```java
Set<WorldPoint> expectedSuccessTiles(int stage) {
    RooftopNode n = course.nodes.get(stage);
    if (!n.successTiles.isEmpty()) return n.successTiles;
    if (stage + 1 < course.nodes.size()) return course.nodes.get(stage + 1).stageTiles;
    return course.lapEndTiles;  // final obstacle landed the player at lap end
}
```

## 6a. Course construction validation

Course profiles are hand-pasted tile data; one typo can produce confusing
runtime behavior. `validateCourse(course)` runs once during course
construction (in the static initializer) and throws
`IllegalStateException` with a precise message on any of:

- `nodes.isEmpty()`.
- Any node with `objectId <= 0`, blank `action`, or empty `stageTiles`,
  `objectTiles`.
- `startTiles.isEmpty()` or `lapEndTiles.isEmpty()`.
- A `startTile` that is **not** in `nodes.get(0).stageTiles` — recovery
  must land us where the script will click the first obstacle. (We don't
  introduce a separate `recoveryTiles` field; we enforce the simpler
  invariant.)
- Any tile in `stageTiles`, `successTiles`, `lapEndTiles`, or
  `reachableMarkTiles` (across all nodes / the course) that is not in
  `validTiles`. Catches the most common paste mistake — forgetting to
  add a tile to the `validTiles` superset.
- Any tile that appears in **two different** node `stageTiles` (built via
  `stageByTile.put(t, i) != null` returning a different stage). Duplicate
  stage tiles silently break stage detection — fail loud at startup.
- Any `successTiles` entry that is also in the **same** node's
  `stageTiles` (would prevent timeout from clearing).

The script refuses to start if validation throws — the panel surfaces the
exception message as `status`.

## 7. Click throttling

A single `long nextActionAt` field; every branch that dispatches sets it
forward:

| Branch | Delay range |
|--------|-------------|
| After clicking an obstacle | `randomBetween(600, 1200)` ms |
| After dispatching a recovery walk | `randomBetween(1200, 2500)` ms |
| After clicking a mark-of-grace | `randomBetween(600, 1000)` ms |
| After eating food | `randomBetween(900, 1400)` ms |
| After detecting `UNKNOWN` stage on a valid tile | `600` ms (one tick) |

Top of `onTick`:

```java
if (now < nextActionAt) return;
```

This prevents the script from spamming the same obstacle every tick while
the player walks toward it.

## 8. Tick loop

Pseudocode of `onTick`, in execution order:

```
if (!running)                          return

if (handleTargetLevel())               return   # cheap; reads varbit, no dispatch
if (dispatcher.isBusy())               return   # gate ALL dispatch-enqueueing branches below
if (handleBlockingDialog())            return   # Escape stuck menus; only if dispatcher free
if (handleLowHp())                     return   # eat or stop; respects throttle internally

if (state == PICKING_MARK):                     # finish the mini-state before anything else dispatches
    handlePickingMark()
    return

if (maybeEnableRun())                  return   # at most ONE ActionRequest per tick; returns true if it enqueued
if (now < nextActionAt)                return   # throttle gate; everything below dispatches

if (isPlayerBusy())                    return   # pose != idle, moving, animating

if (handleObstacleTimeout())           return   # may walk to recovery; sets nextActionAt
if (handleFallOrInvalidPosition())     return   # may walk to recovery; sets nextActionAt
if (handleUnmappedValidTile())         return   # valid tile, no stage match — waits or stops

if (tryPickupReachableMark())          return   # sets PICKING_MARK + nextActionAt

int stage = detectCurrentStage()
clickObstacle(stage, course.nodes.get(stage))   # sets lastClicked* + nextActionAt
```

Five things to note:

1. **One `ActionRequest` per tick.** `maybeEnableRun` returns `true` if it
   enqueued a run-toggle, and the function returns immediately — no other
   branch dispatches in the same tick. The same `return-on-dispatch`
   discipline holds for every other branch.
2. **`dispatcher.isBusy()` gates everything that dispatches.** It comes
   *before* `handleBlockingDialog` because dialog Escapes are dispatched
   too. `handleTargetLevel` is the only check above it because it does no
   dispatch (just `stop()`).
3. **`handleObstacleTimeout` runs before fall detection** — a stuck
   obstacle is the most common failure mode and must be resolved before
   classifying the player's tile.
4. **`handleUnmappedValidTile`** (new) — separates the "we forgot to mark
   this tile" case from the "fell" case. Waits one tick; if the same
   unmapped tile persists for more than 8 seconds, the script stops with
   the exact coordinates so we can extend the stage tile lists.
5. **`detectCurrentStage` is safe to call without an `UNKNOWN` guard at
   the bottom** because `handleFallOrInvalidPosition` and
   `handleUnmappedValidTile` have already handled every non-stage case.

## 9. Stage detection

```java
private int detectCurrentStage() {
    WorldPoint p = playerLocation();
    return course.stageByTile.getOrDefault(p, UNKNOWN);
}
```

This is the only authority for "where are we." There is **no**
`currentStage++` counter anywhere — incrementing manually breaks under
falls, lag, misclicks, and mark interruptions.

## 10. Fall / invalid position

```java
private boolean handleFallOrInvalidPosition() {
    WorldPoint p = playerLocation();

    if (course.fallTiles.contains(p)) {
        status = "Fell — recovering to start";
        walkToNearestStartTile();
        nextActionAt = now + randomBetween(1200, 2500);
        return true;
    }

    if (!course.validTiles.contains(p) && !course.startTiles.contains(p)) {
        status = "Outside course — recovering to start";
        walkToNearestStartTile();
        nextActionAt = now + randomBetween(1200, 2500);
        return true;
    }

    return false;
}
```

`walkToNearestStartTile()` enqueues a single tile-walk `ActionRequest`
targeting the closest tile in `course.startTiles`. The dispatcher's normal
pipeline handles tile-click → minimap-fallback if the direct click resolves
to a different verb (per CLAUDE.md §"Dispatcher quirks").

### 10a. Unmapped-valid-tile handler

Carved out of the fall/invalid handler so a tile we forgot to mark doesn't
silently freeze the script.

```java
WorldPoint unknownStageTile;        // last tile that returned UNKNOWN-on-valid
long       unknownStageSince;

private boolean handleUnmappedValidTile() {
    WorldPoint p = playerLocation();
    if (course.stageByTile.containsKey(p)) {
        unknownStageTile = null;     // reset
        return false;                // detectCurrentStage will resolve it normally
    }

    // We're on a validTile (fall/invalid was handled before us) but no stage match.
    if (!p.equals(unknownStageTile)) {
        unknownStageTile  = p;
        unknownStageSince = now;
    }

    if (now - unknownStageSince > 8_000) {
        stop("Unmapped valid course tile: " + p);
        return true;
    }

    status       = "Waiting for known stage (at " + p + ")";
    nextActionAt = now + 600;
    return true;
}
```

The stop message includes the exact tile coords so adding it to the course
data is a single paste.

## 11. Obstacle click & timeout

`clickObstacle(stage, node)` enqueues a `GAME_OBJECT_CLICK` request and
records the click. The scene-object selection is constrained to **both**
`objectId` and `objectTiles` so repeated obstacle types (Draynor has two
tightropes) cannot be confused:

```java
private void clickObstacle(int stage, RooftopNode node) {
    GameObject target = sceneObjectsByTile(node.objectTiles).stream()
        .filter(o -> o.getId() == node.objectId)
        .findFirst()
        .orElse(null);
    if (target == null) {
        status = "Obstacle not on scene: " + node.label;
        nextActionAt = now + 600;
        return;
    }
    dispatcher.enqueueGameObjectClick(target, node.action);

    lastClickedNode      = node;
    lastClickedStage     = stage;
    lastObstacleClickAt  = now;
    nextActionAt         = now + randomBetween(600, 1200);
}
```

Timeout check runs **before** fall detection:

```java
private boolean handleObstacleTimeout() {
    if (lastClickedNode == null) return false;
    if (now - lastObstacleClickAt < lastClickedNode.timeoutMs) return false;

    WorldPoint p = playerLocation();

    if (expectedSuccessTiles(lastClickedStage).contains(p)) {
        clearLastObstacle();          // worked late, no recovery needed
        return false;
    }

    int stage = detectCurrentStage();
    if (stage != UNKNOWN) {
        clearLastObstacle();          // resynced — player on a different stage tile
        return false;
    }

    status = "Obstacle timeout — recovering to start";
    clearLastObstacle();
    walkToNearestStartTile();
    nextActionAt = now + randomBetween(1200, 2500);
    return true;
}
```

`clearLastObstacle()` nulls out the three `lastClicked*` fields.

`timeoutMs` per node is set from observed completion times +50% margin —
about 4000ms for short hops, 6000–7000ms for tightropes. Values are tuned
during manual acceptance, not derived analytically.

## 12. Marks of grace

```java
private boolean tryPickupReachableMark() {
    if (!pickupMarks) return false;
    int stage = detectCurrentStage();
    if (stage == UNKNOWN) return false;

    RooftopNode node    = course.nodes.get(stage);
    WorldPoint  player  = playerLocation();

    for (TileItem mark : findMarksOfGrace()) {
        WorldPoint markTile = markWorldPoint(mark);
        if (markTile.getPlane() != player.getPlane())          continue;
        if (!node.reachableMarkTiles.contains(markTile))       continue;

        clickTileItem(mark);                                   // GROUND_ITEM_CLICK ActionRequest
        state               = PICKING_MARK;
        markTileClicked     = markTile;
        markClickAt         = now;
        markCountBefore     = inventoryCount(MARK_OF_GRACE);
        nextActionAt        = now + randomBetween(600, 1000);
        status              = "Picking up mark";
        return true;
    }
    return false;
}
```

`handlePickingMark` runs at the top of the `state == PICKING_MARK` branch
of the tick loop. Three exit conditions, any of which clears the state back
to `RUNNING`:

1. The mark tile no longer has a `MARK_OF_GRACE` ground item — pickup
   succeeded, the mark despawned, or scene state changed.
2. `inventoryCount(MARK_OF_GRACE) > markCountBefore` — explicit confirm.
3. `now - markClickAt > 6_000` — timeout; we'll resync naturally on the
   next tick via stage detection.

Two-roof-mark trap (the case that motivated the conservative rule): a mark
spawns on the rooftop above the player's tightrope. Without `reachableMark
Tiles`, the script would click the mark, the dispatcher would resolve it
to "Walk here," and the player drifts off-route. With `reachableMarkTiles`,
the off-roof mark is filtered out before any dispatch.

## 13. HP / food / run energy

```java
private boolean handleLowHp() {
    if (currentHp() > eatAtHp) return false;
    if (eatAnyFoodInInventory()) {
        nextActionAt = now + randomBetween(900, 1400);
        return true;
    }
    stop("Low HP and no food");
    return true;
}
```

`eatAnyFoodInInventory()` walks the inventory container looking for any
item with the `"Eat"` menu action; on first hit, enqueues an
`INV_SLOT_CLICK` `ActionRequest` with verb `"Eat"` and returns true. No
food preference, no priority — first edible item wins.

Run energy. Returns `true` if it enqueued a toggle; the tick loop returns
immediately on `true` so no second `ActionRequest` is enqueued in the same
tick. A small post-toggle throttle prevents repeated toggles if the first
request is ignored.

```java
long nextRunToggleAt;          // earliest time we may attempt another run-toggle

private boolean maybeEnableRun() {
    if (isRunEnabled())             return false;
    if (now < nextRunToggleAt)      return false;

    int energyPercent = client.getEnergy() / 100;   // client.getEnergy() returns 0..10000
    if (energyPercent < runOnAtLeast) return false;

    dispatcher.enqueueRunToggle();
    nextRunToggleAt = now + 2_000;
    return true;
}
```

`runOnAtLeast` is reseeded to a fresh `randomBetween(20, 40)` each time the
script observes run off (i.e. it's re-rolled on the falling edge of
`isRunEnabled`).

## 14. Target level stop

```java
static final int DEFAULT_TARGET_LEVEL = 20;   // Draynor exit level; panel pre-populates this

private boolean handleTargetLevel() {
    if (client.getRealSkillLevel(Skill.AGILITY) < targetLevel) return false;
    stop("Target level reached");
    return true;
}
```

Real level only (`getRealSkillLevel`), not boosted level. Boosts are out of
scope for v1. The panel field defaults to `DEFAULT_TARGET_LEVEL`; the user
can override before pressing Start.

## 15. Lap tracking

```java
int  lapsCompleted;
long lastLapCompletedAt;
long startedAt;                // set when Start is pressed; cleared on Stop
```

`startedAt` lets the panel render runtime as `(now - startedAt)` formatted
`HH:MM:SS` for diagnostics during the acceptance runs.

Incremented when:

```java
lastClickedNode == course.nodes.get(course.nodes.size() - 1)
&& course.lapEndTiles.contains(playerLocation())
```

i.e. we clicked the final obstacle and the player has landed on a
`lapEndTile`. Hooked into `handleObstacleTimeout`'s success branch and
into the regular post-success clear in `clickObstacle` flow — wherever
`clearLastObstacle()` is about to run, we first check the lap-end
condition. Used only for status display and debugging.

## 16. Panel UI

Added to `RecorderPanel`, same layout style as the fletching section:

```
[Rooftop Agility]
Course:           [ Draynor ▾ ]      // v1: only DRAYNOR in dropdown
Target level:     [ 20  ]            // stop when real Agility level ≥ target
Pick up marks:    [✓]
Eat below HP:     [ 8  ]              // eats first inventory item with an "Eat" action
[ Start ] [ Stop ]

Status: Running — stage 3/7 (Tightrope) — 12 laps — 4 marks — 18m
```

Pre-flight validation in `Start`:

1. Course profile passes `validateCourse(course)` (already enforced at
   construction; surfaced again here so a profile reload would catch it).
2. Course selected (only `DRAYNOR` available in v1).
3. `targetLevel > client.getRealSkillLevel(Skill.AGILITY)`.
4. Player tile ∈ `validTiles ∪ startTiles ∪ fallTiles ∪ lapEndTiles`
   (anywhere we can recover from).
5. `client.getGameState() == LOGGED_IN`.

If validation fails, the panel sets `status` to the reason and refuses to
start. No exception thrown.

## 17. Capture workflow

To populate Draynor's tile sets, you (the user) use the existing
tile-marker workflow:

1. **Stage tiles** — Mark every player tile that constitutes stage 0
   (where you stand before clicking the first obstacle). Right-click →
   "Mark group" → name `draynor.stage.0`. Repeat for stages 1..6.
2. **Object tiles** — Stand off the obstacle, use the click-inspector to
   read each obstacle's world tile, mark it. Name group
   `draynor.object.0`..`draynor.object.6`. This is what disambiguates the
   two tightropes.
3. **Fall tiles** — Mark every street-level tile the player can land on
   after a failed obstacle. Name group `draynor.fall`.
4. **Valid tiles** — Mark every tile that's "on the rooftop route"
   (catch-all for valid non-fall positions including landing tiles
   between obstacles). Name group `draynor.valid`. Must be a superset
   of every `stage.N` group.
5. **Start tiles** — Mark every tile the player can stand on at the
   lap-start spot. Name group `draynor.start`. Must be a subset of
   `draynor.stage.0` (enforced by `validateCourse`).
6. **Lap-end tiles** — After the final crate, mark where the player
   lands. Name group `draynor.lapend`.
7. **Reachable mark tiles** — For each obstacle, walk a couple of laps,
   note where marks of grace spawn that you'd pick up from that stage,
   mark those tiles. Name group `draynor.marks.0`..`draynor.marks.6`.
   Every entry must be in `draynor.valid` (enforced).

Export the tile-marker JSON; the user pastes the coords into a
`tiles(int... xyp)` call per group inside `RooftopAgilityScript`. No new
parser code in v1.

A future v2 can add a `Loader` class that reads a course JSON file at
startup and populates the same `RooftopCourse` type — no other script code
changes, the data model is already shaped to support it.

## 18. Object IDs and verbs (Draynor)

From the OSRS Wiki — confirm by hovering each obstacle in-game during the
capture session and noting `objectId` from the click-inspector:

| Idx | Label                | Object name (wiki)         | Verb         |
|-----|----------------------|----------------------------|--------------|
| 0   | Rough wall           | Rough wall                 | Climb        |
| 1   | Tightrope            | Tightrope                  | Cross        |
| 2   | Tightrope            | Tightrope                  | Cross        |
| 3   | Narrow wall          | Narrow wall                | Balance      |
| 4   | Wall (jump)          | Wall                       | Jump-up      |
| 5   | Gap (jump)           | Gap                        | Jump         |
| 6   | Crate (descend)      | Crate                      | Climb-down   |

The exact `objectId` numbers are filled in during capture, not guessed
here. The click-inspector plugin already exists in the repo for this
purpose.

## 19. Diagnostic logging

All status changes (state transitions, lap counters, recovery walks,
obstacle timeouts, HP eats) log at INFO with the `[rooftop-agility]` tag,
matching the style of `FletchingScript`. No DEBUG noise in the hot path —
the tick loop itself only logs when a decision is made.

## 20. Failure-mode register

What we expect to go wrong and how the script handles it:

| Failure | Detection | Response |
|---------|-----------|----------|
| Obstacle click dropped (dispatcher busy) | `nextActionAt` not exceeded → retry next tick | normal flow |
| Obstacle click landed but action failed (rare) | `lastObstacleClickAt + timeoutMs` exceeded, player not on success tiles | recover to start |
| Player fell | tile ∈ `fallTiles` | recover to start |
| Player walked off route manually | tile ∉ `validTiles` | recover to start |
| Player tile on rooftop we forgot to mark | tile ∈ `validTiles` but ∉ `stageByTile` | `handleUnmappedValidTile`: wait up to 8s, then stop with the exact tile coords for paste-back |
| Mark of grace on unreachable rooftop | plane mismatch or not in `reachableMarkTiles` | ignored |
| Mark of grace disappeared during pickup | mark tile empty + inv count unchanged + 6s timeout | exit `PICKING_MARK`, normal flow (despawn, scene change, or pickup failed) |
| Course profile typo (duplicate stage tile, etc.) | `validateCourse` throws at construction | script refuses to start; panel shows the exception message |
| Low HP with food | `currentHp ≤ eatAtHp` and inv has edible | eat |
| Low HP without food | `currentHp ≤ eatAtHp` and no edible | stop with explicit reason |
| Stuck right-click menu | `handleBlockingDialog` at top of tick | `tapKey(VK_ESCAPE)` via dispatcher |

## 21. Manual acceptance plan

Exercised on a low-level alt by you, in order. Each item has a one-line
pass criterion. Failure halts the test plan; we fix and rerun.

1. **Cold start at start tile** — pass: one full lap, `lapsCompleted = 1`.
2. **Resume mid-course** — Stop halfway up, restart. Pass: `detectCurrentStage` returns a non-UNKNOWN index and the lap completes.
3. **Forced fall** — manually fail a tightrope. Pass: bot walks to start, resumes.
4. **Manually walk off-route** — walk to a Draynor street tile not in `validTiles` or `fallTiles`. Pass: bot recovers.
5. **Mark on current stage** — pass: bot picks it up, returns to `RUNNING`, inventory count increases by 1.
6. **Mark on other rooftop** — pass: bot ignores it (logs the filter reason at DEBUG only if enabled, otherwise no log).
7. **Obstacle timeout** — block the player (e.g. drag screen to lose focus, then return). Pass: after `timeoutMs`, bot recovers.
8. **Low HP with food** — drop HP to 5 with shark in inv. Pass: bot eats, continues.
9. **Low HP without food** — drop HP to 5 with no food. Pass: bot stops with `"Low HP and no food"`.
10. **Target level reached** — set target to current level + 1, gain a level. Pass: bot stops with `"Target level reached"`.
11. **Click throttle** — eyeball during one lap. Pass: no obstacle re-clicked within 600 ms.
12. **Stuck menu** — open a right-click menu manually, press Start. Pass: `handleBlockingDialog` Escapes the menu before the first obstacle click.
13. **Unmapped valid tile** — manually walk to a `validTiles` tile that is not in any `stageByTile`. Pass: bot waits up to 8 s, then stops with `"Unmapped valid course tile: <x,y,p>"` matching the player's tile.
14. **Course validation** — temporarily duplicate a tile across two stage groups in the course profile, attempt Start. Pass: panel shows the `IllegalStateException` message; script does not start.
15. **Repeated-obstacle disambiguation** — stand between the two tightropes (both share `objectId` and verb). Pass: bot picks the obstacle whose tile matches the current stage's `objectTiles`, not the wrong one.
16. **One ActionRequest per tick** — instrument the dispatcher (or eyeball logs) during a lap with run disabled. Pass: never two enqueues from `RooftopAgilityScript` in the same tick.

## 22. Out of scope (named explicitly to prevent scope creep)

- Al Kharid, Varrock, Canifis, Falador profiles.
- Auto-switching courses at level thresholds.
- Banking for food, weight management, stamina potions.
- Boost-aware course gating (e.g. summer pie at 49 to enter Canifis).
- XP/hour estimate, breaks, schedule.
- Recorder-driven JSON loading (data model supports it; loader is not in v1).
- Hostile-NPC handling (none on Draynor route).
- Random-event handlers (handled by existing recorder infrastructure if any; not this script's concern).

## 23. Open questions

None blocking. The following are deferred to v2 and tracked here so we
don't lose them:

- Should `pickupMarks` be on by default? (Currently planned: yes.)
- Should the script auto-restart after `stop("Target level reached")` if a
  v2 introduces course chaining? (Currently planned: no, stop is final.)
- Should `eatAtHp` default scale with the player's max HP? (Currently
  planned: fixed 8 in panel default.)
