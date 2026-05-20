# Rooftop Agility (Draynor v1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement `RooftopAgilityScript` per the locked spec at
`docs/superpowers/specs/2026-05-21-rooftop-agility-design.md`. Single
file in `recorder/scripts/`, Draynor-only v1, manual in-game acceptance.

**Architecture:** Single-file enum-FSM script under
`recorder/scripts/RooftopAgilityScript.java`. Driven by
`@Subscribe onGameTick` on the client thread (read state, decide,
enqueue one `ActionRequest` per tick — no internal blocking, so no
worker thread is needed; this is a deliberate departure from
FletchingScript's `Thread.start("fletching-script")` pattern which exists
to support multi-step bank flows the agility script does not have).
Course profile is embedded data with tile-list authoring + Set/Map
runtime; `validateCourse` fails loud on bad data at construction.

**Tech Stack:** Java 17, RuneLite plugin API,
`net.runelite.client.sequence.dispatch.HumanizedInputDispatcher`,
`ActionRequest`, `BankInteraction.onClient` pattern for marshalling reads
to the client thread, Lombok `@Slf4j`, SLF4J logging.

## Build/run policy

**DO NOT** run `./gradlew :client:compileJava`, `./gradlew :client:shadowJar`,
or any other build/run command during plan execution. The user explicitly
held that off until they ask for it. Verification per task is by code
inspection only. Any compile errors will be discovered during the user-
initiated acceptance phase (Phase 9).

## Threading reminder (load-bearing)

Per `CLAUDE.md` §"Threading model":

- `@Subscribe onGameTick` runs on the client thread — `Widget`, `Scene`,
  `Player`, varbits, inventory containers, `TileItem`s on tiles are all
  free to read here.
- Building an `ActionRequest` and calling `dispatcher.dispatch(...)` is
  pure compute + a queue push; safe from the client thread.
- The dispatcher's worker handles the multi-step input flow off-thread.
  Never call any method that sleeps from `onGameTick`.

## File map

- **Create:** `runelite-client/src/main/java/net/runelite/client/plugins/recorder/scripts/RooftopAgilityScript.java`
- **Modify:** `runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPlugin.java` — construct + cleanup hooks alongside FletchingScript.
- **Modify:** `runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPanel.java` — panel tab, field, isRunning poll.

---

## Phase 0 — Capture Draynor tile/object data (USER GATE)

**This phase blocks Phase 2.** The script cannot run without complete
course data. Subagents executing the plan MUST stop after Phase 1 if the
captured data file is not yet on disk.

### Task 0: Capture Draynor course data

**Owner:** user (in-game with tile-marker plugin and click-inspector).

Spec §17 lists the exact capture workflow. The user delivers a single
text artifact: `docs/superpowers/plans/2026-05-21-draynor-tiles.md`
containing the tile-marker JSON groups and the per-obstacle object IDs.

- [ ] **Step 1: User performs capture** — Run RuneLite, log into Draynor,
  use the tile-marker plugin to record each group:
  `draynor.stage.0..6`, `draynor.object.0..6`, `draynor.fall`,
  `draynor.valid`, `draynor.start`, `draynor.lapend`,
  `draynor.marks.0..6`. Use the click-inspector to note each obstacle's
  `objectId`. (See spec §17 for the full list.)
- [ ] **Step 2: User exports/pastes** — save the captured data to
  `docs/superpowers/plans/2026-05-21-draynor-tiles.md` in any readable
  format (e.g., a markdown table per group, or the tile-marker JSON
  pasted verbatim). Each group must be unambiguously labeled.
- [ ] **Step 3: User notifies** — tell the assistant the capture is
  complete. Plan execution resumes from Phase 2.

**If the file is missing when Phase 2 starts, the executing subagent
must halt and ask the user.**

---

## Phase 1 — Class skeleton (no behavior)

Produces a compilable-on-paper file with package, imports, enums,
constants, fields, constructor, and no-op public surface. No tick logic
yet.

### Task 1: Create RooftopAgilityScript with package + imports + class header

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/scripts/RooftopAgilityScript.java`

- [ ] **Step 1: Read FletchingScript for the pattern**

Read `runelite-client/src/main/java/net/runelite/client/plugins/recorder/scripts/FletchingScript.java`
lines 1–60 to lift the import block, `@Slf4j` annotation, and class
declaration shape. Adapt — do not copy `BankInteraction` /
`SidebarTabActions` imports (we don't need them).

- [ ] **Step 2: Write the file shell**

Content of the new file:

```java
package net.runelite.client.plugins.recorder.scripts;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import net.eventbus.Subscribe;            // RuneLite event bus
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import net.runelite.client.sequence.internal.ActionRequest;

@Slf4j
public final class RooftopAgilityScript
{
    // ─── Constants ───────────────────────────────────────────────────────────────
    static final int  DEFAULT_TARGET_LEVEL = 20;     // Draynor exit level
    static final int  UNKNOWN              = -1;
    static final int  DEFAULT_EAT_AT_HP    = 8;
    static final long UNMAPPED_TILE_TIMEOUT_MS = 8_000;
    static final long MARK_PICKUP_TIMEOUT_MS   = 6_000;
    static final long RUN_TOGGLE_THROTTLE_MS   = 2_000;

    // ─── Enums ───────────────────────────────────────────────────────────────────
    public enum RooftopCourseId { DRAYNOR }
    public enum State           { IDLE, RUNNING, PICKING_MARK }

    // ─── Injected dependencies ───────────────────────────────────────────────────
    private final Client                   client;
    private final ClientThread             clientThread;
    private final HumanizedInputDispatcher dispatcher;

    // ─── Run state (mutated only from client thread) ─────────────────────────────
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<State>  state  = new AtomicReference<>(State.IDLE);
    private final AtomicReference<String> status = new AtomicReference<>("idle");

    // ─── Configuration (set by panel before start) ───────────────────────────────
    private RooftopCourseId selectedCourse = RooftopCourseId.DRAYNOR;
    private int             targetLevel    = DEFAULT_TARGET_LEVEL;
    private boolean         pickupMarks    = true;
    private int             eatAtHp        = DEFAULT_EAT_AT_HP;

    // ─── Runtime (populated on start) ────────────────────────────────────────────
    private RooftopCourse course;
    private long          startedAt;
    private long          nextActionAt;
    private long          nextRunToggleAt;
    private int           runOnAtLeast;

    // ─── Last-click bookkeeping ──────────────────────────────────────────────────
    private RooftopNode lastClickedNode;
    private int         lastClickedStage  = UNKNOWN;
    private long        lastObstacleClickAt;

    // ─── Mark-pickup bookkeeping ─────────────────────────────────────────────────
    private WorldPoint markTileClicked;
    private long       markClickAt;
    private int        markCountBefore;

    // ─── Unmapped-tile bookkeeping ───────────────────────────────────────────────
    private WorldPoint unknownStageTile;
    private long       unknownStageSince;

    // ─── Lap tracking ────────────────────────────────────────────────────────────
    private int  lapsCompleted;
    private long lastLapCompletedAt;
    private int  marksPicked;

    public RooftopAgilityScript(Client client, ClientThread clientThread,
                                HumanizedInputDispatcher dispatcher)
    {
        this.client       = client;
        this.clientThread = clientThread;
        this.dispatcher   = dispatcher;
    }

    // ─── Public API (panel accessors) ────────────────────────────────────────────
    public State           state()        { return state.get(); }
    public String          status()       { return status.get(); }
    public boolean         isRunning()    { return running.get(); }
    public RooftopCourseId selectedCourse(){ return selectedCourse; }
    public int             targetLevel()  { return targetLevel; }
    public boolean         pickupMarks()  { return pickupMarks; }
    public int             eatAtHp()      { return eatAtHp; }
    public int             lapsCompleted(){ return lapsCompleted; }
    public int             marksPicked()  { return marksPicked; }
    public long            startedAt()    { return startedAt; }

    public void setSelectedCourse(RooftopCourseId id) { this.selectedCourse = id; }
    public void setTargetLevel(int v)                 { this.targetLevel    = v; }
    public void setPickupMarks(boolean v)             { this.pickupMarks    = v; }
    public void setEatAtHp(int v)                     { this.eatAtHp        = v; }

    public void start() { /* implemented in Task 18 */ }
    public void stop()  { running.set(false); state.set(State.IDLE); status.set("stopped"); }

    @Subscribe
    public void onGameTick(GameTick tick) { /* implemented in Task 9+ */ }
}
```

Note: the spec's `@Subscribe` import path is the RuneLite event bus
package; use the same import as `FletchingScript` (if it has one) or as
other `@Subscribe` consumers in `recorder/`. If the import is different
from `net.eventbus.Subscribe`, fix it during inspection — do not guess.

- [ ] **Step 3: Inspect the file**

Read the file back and confirm: package matches the path, all enum/field
declarations compile in your head, no duplicate names, accessor names are
consistent (e.g. `selectedCourse()` getter + `setSelectedCourse(...)`
setter). **Do not run gradle.**

- [ ] **Step 4: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/scripts/RooftopAgilityScript.java
git commit -m "feat(agility): script skeleton — fields, accessors, no-op start/stop"
```

### Task 2: Add data classes `RooftopCourse` and `RooftopNode` + `tiles(...)` helper

**Files:**
- Modify: `RooftopAgilityScript.java` — append nested classes.

- [ ] **Step 1: Add the inner classes and `tiles(...)`**

Append inside the `RooftopAgilityScript` class body, after the
constructor:

```java
static final class RooftopCourse
{
    final RooftopCourseId               id;
    final String                        label;
    final int                           levelReq;
    final Set<WorldPoint>               startTiles;
    final Set<WorldPoint>               validTiles;
    final Set<WorldPoint>               fallTiles;
    final Set<WorldPoint>               lapEndTiles;
    final List<RooftopNode>             nodes;
    final Map<WorldPoint, Integer>      stageByTile;

    RooftopCourse(RooftopCourseId id, String label, int levelReq,
                  Set<WorldPoint> startTiles, Set<WorldPoint> validTiles,
                  Set<WorldPoint> fallTiles, Set<WorldPoint> lapEndTiles,
                  List<RooftopNode> nodes)
    {
        this.id           = id;
        this.label        = label;
        this.levelReq     = levelReq;
        this.startTiles   = Set.copyOf(startTiles);
        this.validTiles   = Set.copyOf(validTiles);
        this.fallTiles    = Set.copyOf(fallTiles);
        this.lapEndTiles  = Set.copyOf(lapEndTiles);
        this.nodes        = List.copyOf(nodes);

        Map<WorldPoint, Integer> m = new java.util.HashMap<>();
        for (int i = 0; i < this.nodes.size(); i++) {
            for (WorldPoint t : this.nodes.get(i).stageTiles) {
                Integer prev = m.put(t, i);
                if (prev != null && prev != i) {
                    throw new IllegalStateException(
                        "Duplicate stage tile " + t + " maps to stages " + prev + " and " + i);
                }
            }
        }
        this.stageByTile = Map.copyOf(m);
    }
}

static final class RooftopNode
{
    final String              label;
    final int                 objectId;
    final String              action;
    final Set<WorldPoint>     objectTiles;
    final Set<WorldPoint>     stageTiles;
    final Set<WorldPoint>     successTiles;
    final Set<WorldPoint>     reachableMarkTiles;
    final long                timeoutMs;

    RooftopNode(String label, int objectId, String action,
                Set<WorldPoint> objectTiles, Set<WorldPoint> stageTiles,
                Set<WorldPoint> successTiles, Set<WorldPoint> reachableMarkTiles,
                long timeoutMs)
    {
        this.label              = label;
        this.objectId           = objectId;
        this.action             = action;
        this.objectTiles        = Set.copyOf(objectTiles);
        this.stageTiles         = Set.copyOf(stageTiles);
        this.successTiles       = Set.copyOf(successTiles);
        this.reachableMarkTiles = Set.copyOf(reachableMarkTiles);
        this.timeoutMs          = timeoutMs;
    }
}

/** Authoring helper: tiles(x1, y1, p1,  x2, y2, p2, ...) */
static Set<WorldPoint> tiles(int... xyp)
{
    if (xyp.length % 3 != 0) {
        throw new IllegalArgumentException("tiles() takes triples of (x,y,plane)");
    }
    java.util.Set<WorldPoint> out = new java.util.HashSet<>();
    for (int i = 0; i < xyp.length; i += 3) {
        out.add(new WorldPoint(xyp[i], xyp[i+1], xyp[i+2]));
    }
    return out;
}
```

- [ ] **Step 2: Inspect**

Re-read the file. Confirm `Set.copyOf` / `Map.copyOf` / `List.copyOf` are
imported via the standard `java.util` (already imported in Task 1). The
constructor's duplicate-stage-tile check is the **only** validation in
this task — full `validateCourse` lands in Task 4.

- [ ] **Step 3: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/scripts/RooftopAgilityScript.java
git commit -m "feat(agility): RooftopCourse + RooftopNode data classes + tiles() helper"
```

### Task 3: Stub the `COURSES` map and `validateCourse` (no Draynor data yet)

Course data lives in its own static initializer block (added in Task 5
after capture is delivered). For now, declare the map empty and add the
validation function so callers can wire to it.

- [ ] **Step 1: Add the COURSES map and validateCourse**

Append inside the class body:

```java
static final Map<RooftopCourseId, RooftopCourse> COURSES;

static {
    Map<RooftopCourseId, RooftopCourse> m = new java.util.EnumMap<>(RooftopCourseId.class);
    // Populated by Task 5 once Draynor tile capture is delivered.
    COURSES = java.util.Collections.unmodifiableMap(m);
}

/**
 * Throws IllegalStateException with a precise message on bad profile
 * data. See spec §6a for the full invariant list.
 */
static void validateCourse(RooftopCourse c)
{
    if (c.nodes.isEmpty())          throw new IllegalStateException("nodes empty");
    if (c.startTiles.isEmpty())     throw new IllegalStateException("startTiles empty");
    if (c.lapEndTiles.isEmpty())    throw new IllegalStateException("lapEndTiles empty");

    Set<WorldPoint> stage0 = c.nodes.get(0).stageTiles;
    for (WorldPoint t : c.startTiles) {
        if (!stage0.contains(t)) {
            throw new IllegalStateException("startTile " + t + " is not in node[0].stageTiles");
        }
    }

    for (int i = 0; i < c.nodes.size(); i++) {
        RooftopNode n = c.nodes.get(i);
        if (n.objectId <= 0)        throw new IllegalStateException("node["+i+"] objectId<=0");
        if (n.action == null || n.action.isBlank())
                                    throw new IllegalStateException("node["+i+"] blank action");
        if (n.stageTiles.isEmpty()) throw new IllegalStateException("node["+i+"] stageTiles empty");
        if (n.objectTiles.isEmpty())throw new IllegalStateException("node["+i+"] objectTiles empty");

        for (WorldPoint t : n.stageTiles) {
            if (!c.validTiles.contains(t))
                throw new IllegalStateException("stage tile " + t + " not in validTiles (node " + i + ")");
        }
        for (WorldPoint t : n.successTiles) {
            if (!c.validTiles.contains(t))
                throw new IllegalStateException("successTile " + t + " not in validTiles (node " + i + ")");
            if (n.stageTiles.contains(t))
                throw new IllegalStateException("successTile " + t + " also in stageTiles (node " + i + ")");
        }
        for (WorldPoint t : n.reachableMarkTiles) {
            if (!c.validTiles.contains(t))
                throw new IllegalStateException("reachableMarkTile " + t + " not in validTiles (node " + i + ")");
        }
    }
    for (WorldPoint t : c.lapEndTiles) {
        if (!c.validTiles.contains(t))
            throw new IllegalStateException("lapEndTile " + t + " not in validTiles");
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add -u && git commit -m "feat(agility): COURSES enum-map + validateCourse"
```

---

## Phase 2 — Draynor course data (USER-GATE-DEPENDENT)

**STOP HERE if `docs/superpowers/plans/2026-05-21-draynor-tiles.md` does
not exist.** Ask the user for it before proceeding.

### Task 4: Read the captured tile data

- [ ] **Step 1: Read** the user-provided
  `docs/superpowers/plans/2026-05-21-draynor-tiles.md` end-to-end. Note
  per-group tile coordinates and per-obstacle `objectId`s.
- [ ] **Step 2: Sanity-check** — confirm 7 obstacle groups
  (`object.0..6`), 7 stage groups (`stage.0..6`), 7 mark-tile groups
  (`marks.0..6`), and non-empty `start`, `valid`, `fall`, `lapend`
  groups. If anything is missing, ask the user to complete it.

### Task 5: Populate `COURSES` with the Draynor profile

**Files:**
- Modify: `RooftopAgilityScript.java` — fill the static initializer.

- [ ] **Step 1: Replace the static initializer**

Inside the `static { ... }` block, build the Draynor `RooftopCourse`
using the captured coords. Pattern (replace the `...` with actual
captured triples from Task 4):

```java
static {
    Map<RooftopCourseId, RooftopCourse> m = new java.util.EnumMap<>(RooftopCourseId.class);

    List<RooftopNode> draynorNodes = List.of(
        new RooftopNode("Rough wall", /*objectId*/ -1, "Climb",
            tiles(/*draynor.object.0*/ ),
            tiles(/*draynor.stage.0*/  ),
            tiles(/*successTiles or empty*/ ),
            tiles(/*draynor.marks.0*/  ),
            /*timeoutMs*/ 4_000L),
        // ... new RooftopNode for indices 1..6
    );

    RooftopCourse draynor = new RooftopCourse(
        RooftopCourseId.DRAYNOR, "Draynor Rooftop", 1,
        tiles(/*draynor.start*/ ),
        tiles(/*draynor.valid*/ ),
        tiles(/*draynor.fall*/  ),
        tiles(/*draynor.lapend*/),
        draynorNodes);
    validateCourse(draynor);
    m.put(RooftopCourseId.DRAYNOR, draynor);

    COURSES = java.util.Collections.unmodifiableMap(m);
}
```

Replace every `objectId = -1` placeholder with the captured value.
`timeoutMs` per node: 4000 ms for non-tightrope obstacles, 7000 ms for
tightropes (`Cross`). Tune during acceptance.

`successTiles` is empty for every node — the default (next node's
`stageTiles` / `lapEndTiles`) is what we want.

- [ ] **Step 2: Inspect the static initializer**

Re-read the block. Confirm:
- 7 `RooftopNode` entries.
- Every `objectId` is positive.
- `validateCourse(draynor)` runs at the end and is not commented out.

- [ ] **Step 3: Commit**

```bash
git add -u && git commit -m "feat(agility): Draynor course data — captured tiles + object IDs"
```

---

## Phase 3 — Tick driver + cheap top-of-tick handlers

### Task 6: Implement `onGameTick` skeleton with the spec §8 ordering

- [ ] **Step 1: Replace the stub `onGameTick`**

```java
@Subscribe
public void onGameTick(GameTick tick)
{
    if (!running.get()) return;
    long now = System.currentTimeMillis();

    if (handleTargetLevel(now))                    return;
    if (dispatcher.isBusy())                       return;
    if (handleBlockingDialog(now))                 return;
    if (handleLowHp(now))                          return;

    if (state.get() == State.PICKING_MARK) {
        handlePickingMark(now);
        return;
    }

    if (maybeEnableRun(now))                       return;
    if (now < nextActionAt)                        return;

    if (isPlayerBusy())                            return;

    if (handleObstacleTimeout(now))                return;
    if (handleFallOrInvalidPosition(now))          return;
    if (handleUnmappedValidTile(now))              return;

    if (tryPickupReachableMark(now))               return;

    int stage = detectCurrentStage();
    clickObstacle(stage, course.nodes.get(stage), now);
}
```

Add private method stubs **immediately** so the file compiles in your
head as you proceed:

```java
private boolean handleTargetLevel(long now) { return false; }
private boolean handleBlockingDialog(long now) { return false; }
private boolean handleLowHp(long now) { return false; }
private void    handlePickingMark(long now) { }
private boolean maybeEnableRun(long now) { return false; }
private boolean isPlayerBusy() { return false; }
private boolean handleObstacleTimeout(long now) { return false; }
private boolean handleFallOrInvalidPosition(long now) { return false; }
private boolean handleUnmappedValidTile(long now) { return false; }
private boolean tryPickupReachableMark(long now) { return false; }
private int     detectCurrentStage() { return UNKNOWN; }
private void    clickObstacle(int stage, RooftopNode node, long now) { }
```

- [ ] **Step 2: Commit**

```bash
git add -u && git commit -m "feat(agility): onGameTick driver + handler stubs (no behavior yet)"
```

### Task 7: `handleTargetLevel`

- [ ] **Step 1: Replace the stub**

```java
private boolean handleTargetLevel(long now)
{
    if (client.getRealSkillLevel(Skill.AGILITY) < targetLevel) return false;
    status.set("Target level reached");
    log.info("[rooftop-agility] target level {} reached — stopping", targetLevel);
    running.set(false);
    state.set(State.IDLE);
    return true;
}
```

- [ ] **Step 2: Commit**

```bash
git add -u && git commit -m "feat(agility): handleTargetLevel — stop at real Agility level >= target"
```

### Task 8: `handleBlockingDialog` — Escape stuck menus

- [ ] **Step 1: Inspect the dispatcher**

Read `runelite-client/src/main/java/net/runelite/client/sequence/dispatch/HumanizedInputDispatcher.java`
for the public `Kind.KEY` dispatch pattern, and check whether any helper
exists for VK_ESCAPE (e.g. `tapKey(int)`). If a helper exists, use it;
otherwise build an `ActionRequest` with `Kind.KEY`. Note: the dispatcher
already auto-escapes after failed `selectMenuVerb` per CLAUDE.md §8 — so
this handler's job is only to dismiss menus left open by a third party
(player manually right-clicked before pressing Start).

- [ ] **Step 2: Implement**

```java
private boolean handleBlockingDialog(long now)
{
    if (!client.getMenu().isOpen()) return false;
    log.info("[rooftop-agility] menu open — dispatching Escape");
    dispatcher.dispatch(ActionRequest.builder()
        .kind(ActionRequest.Kind.KEY)
        .channel(ActionRequest.Channel.KEYBOARD)
        .key(java.awt.event.KeyEvent.VK_ESCAPE)
        .build());
    nextActionAt = now + 600;
    return true;
}
```

If `ActionRequest.builder().key(...)` doesn't exist, mirror the
keyboard-dispatch pattern used elsewhere — search
`runelite-client/src/main/java/net/runelite/client/sequence/internal/ActionRequest.java`
for `KEY` to find the right setter name.

- [ ] **Step 3: Commit**

```bash
git add -u && git commit -m "feat(agility): handleBlockingDialog — Escape pre-existing menus"
```

### Task 9: `handleLowHp` — eat or stop

- [ ] **Step 1: Inspect the inv-eat pattern**

Search any of the cooking/pizza/pie scripts for an `Eat` dispatch
example — they all share the `CLICK_INV_ITEM` pattern with
`menuOption="Eat"`. Mirror their code, not invent a new shape.

```bash
grep -rnE '"Eat"|menuOption.*Eat|verb.*Eat' \
  runelite-client/src/main/java/net/runelite/client/plugins/recorder/scripts/
```

- [ ] **Step 2: Implement**

```java
private boolean handleLowHp(long now)
{
    int hp = client.getBoostedSkillLevel(Skill.HITPOINTS);
    if (hp > eatAtHp) return false;

    net.runelite.api.ItemContainer inv = client.getItemContainer(
        net.runelite.api.gameval.InventoryID.INV);
    if (inv == null) {
        status.set("Low HP — no inventory");
        running.set(false);
        return true;
    }
    net.runelite.api.Item[] items = inv.getItems();
    for (int slot = 0; slot < items.length; slot++) {
        net.runelite.api.Item it = items[slot];
        if (it == null || it.getId() <= 0) continue;
        net.runelite.api.ItemComposition comp =
            client.getItemDefinition(it.getId());
        if (comp == null) continue;
        for (String action : comp.getInventoryActions()) {
            if ("Eat".equalsIgnoreCase(action)) {
                dispatcher.dispatch(ActionRequest.builder()
                    .kind(ActionRequest.Kind.CLICK_INV_ITEM)
                    .channel(ActionRequest.Channel.MOUSE)
                    .invSlot(slot)
                    .menuOption("Eat")
                    .build());
                long delay = 900L + (long) (Math.random() * 500L);   // 900–1400
                nextActionAt = now + delay;
                log.info("[rooftop-agility] eating slot {} (id {}) at hp {}", slot, it.getId(), hp);
                return true;
            }
        }
    }
    status.set("Low HP and no food");
    log.info("[rooftop-agility] low HP {} <= {} and no food — stopping", hp, eatAtHp);
    running.set(false);
    state.set(State.IDLE);
    return true;
}
```

If the `invSlot` / `menuOption` setter names differ from the actual
`ActionRequest.Builder` surface, fix them to match what
`FletchingScript.java` line 537 onward uses. Don't guess.

- [ ] **Step 3: Commit**

```bash
git add -u && git commit -m "feat(agility): handleLowHp — eat first edible, else stop"
```

### Task 10: `isPlayerBusy`

- [ ] **Step 1: Implement**

```java
private boolean isPlayerBusy()
{
    net.runelite.api.Player p = client.getLocalPlayer();
    if (p == null) return true;
    if (p.getAnimation() != -1) return true;
    return p.getPoseAnimation() != p.getIdlePoseAnimation();
}
```

- [ ] **Step 2: Commit**

```bash
git add -u && git commit -m "feat(agility): isPlayerBusy — animation + pose check"
```

---

## Phase 4 — Stage detection + obstacle click + obstacle timeout

### Task 11: `detectCurrentStage`

- [ ] **Step 1: Replace the stub**

```java
private int detectCurrentStage()
{
    WorldPoint p = client.getLocalPlayer().getWorldLocation();
    return course.stageByTile.getOrDefault(p, UNKNOWN);
}
```

- [ ] **Step 2: Commit**

```bash
git add -u && git commit -m "feat(agility): detectCurrentStage — O(1) lookup via stageByTile"
```

### Task 12: `clickObstacle(stage, node, now)`

- [ ] **Step 1: Inspect GameObject lookup**

```bash
grep -rnE 'getGameObjects|GameObject.*WorldPoint|gameObjectsAt|sceneObjects' \
  runelite-client/src/main/java/net/runelite/client/plugins/recorder/scene/
```

Use the existing `SceneScanner` helper or the equivalent — do not write
new scene-iteration code.

- [ ] **Step 2: Implement**

```java
private void clickObstacle(int stage, RooftopNode node, long now)
{
    net.runelite.api.GameObject target = null;
    net.runelite.api.Scene scene = client.getScene();
    net.runelite.api.Tile[][][] tiles = scene.getTiles();
    int plane = client.getPlane();
    int baseX = client.getBaseX();
    int baseY = client.getBaseY();

    outer:
    for (WorldPoint wp : node.objectTiles) {
        if (wp.getPlane() != plane) continue;
        int sx = wp.getX() - baseX;
        int sy = wp.getY() - baseY;
        if (sx < 0 || sy < 0 || sx >= 104 || sy >= 104) continue;
        net.runelite.api.Tile t = tiles[plane][sx][sy];
        if (t == null) continue;
        for (net.runelite.api.GameObject go : t.getGameObjects()) {
            if (go != null && go.getId() == node.objectId) {
                target = go;
                break outer;
            }
        }
    }

    if (target == null) {
        status.set("Obstacle off-scene: " + node.label);
        nextActionAt = now + 600;
        return;
    }

    dispatcher.dispatch(ActionRequest.builder()
        .kind(ActionRequest.Kind.CLICK_GAME_OBJECT)
        .channel(ActionRequest.Channel.MOUSE)
        .gameObject(target)
        .menuOption(node.action)
        .build());

    lastClickedNode      = node;
    lastClickedStage     = stage;
    lastObstacleClickAt  = now;
    long delay = 600L + (long) (Math.random() * 600L);   // 600–1200
    nextActionAt = now + delay;
    status.set("Clicked " + node.label + " (stage " + stage + ")");
}
```

Builder setter names (`gameObject`, `menuOption`) may differ — match
them against `ActionRequest.Builder` / `FletchingScript.java`'s
`CLICK_GAME_OBJECT` usage (search the repo if present; otherwise look at
`ActionRequest.java`).

- [ ] **Step 3: Commit**

```bash
git add -u && git commit -m "feat(agility): clickObstacle — objectTiles-constrained scene lookup + dispatch"
```

### Task 13: `handleObstacleTimeout` + `expectedSuccessTiles` + `clearLastObstacle`

- [ ] **Step 1: Add the helpers**

```java
private Set<WorldPoint> expectedSuccessTiles(int stage)
{
    RooftopNode n = course.nodes.get(stage);
    if (!n.successTiles.isEmpty()) return n.successTiles;
    if (stage + 1 < course.nodes.size()) return course.nodes.get(stage + 1).stageTiles;
    return course.lapEndTiles;
}

private void clearLastObstacle()
{
    lastClickedNode      = null;
    lastClickedStage     = UNKNOWN;
    lastObstacleClickAt  = 0L;
}
```

- [ ] **Step 2: Implement `handleObstacleTimeout`**

```java
private boolean handleObstacleTimeout(long now)
{
    if (lastClickedNode == null) return false;
    if (now - lastObstacleClickAt < lastClickedNode.timeoutMs) return false;

    WorldPoint p = client.getLocalPlayer().getWorldLocation();

    // Lap completion: final obstacle clicked + player on lapEndTiles
    if (lastClickedNode == course.nodes.get(course.nodes.size() - 1)
        && course.lapEndTiles.contains(p)) {
        lapsCompleted++;
        lastLapCompletedAt = now;
        clearLastObstacle();
        return false;
    }

    if (expectedSuccessTiles(lastClickedStage).contains(p)) {
        clearLastObstacle();
        return false;
    }

    int stage = detectCurrentStage();
    if (stage != UNKNOWN) {
        clearLastObstacle();
        return false;
    }

    status.set("Obstacle timeout — recovering to start");
    log.info("[rooftop-agility] obstacle timeout on {} — recovering", lastClickedNode.label);
    clearLastObstacle();
    walkToNearestStartTile();
    long delay = 1_200L + (long) (Math.random() * 1_300L);   // 1200–2500
    nextActionAt = now + delay;
    return true;
}

private void walkToNearestStartTile() { /* implemented in Task 14 */ }
```

- [ ] **Step 3: Commit**

```bash
git add -u && git commit -m "feat(agility): handleObstacleTimeout + lap counter on final-obstacle success"
```

### Task 14: `walkToNearestStartTile`

- [ ] **Step 1: Implement**

```java
private void walkToNearestStartTile()
{
    WorldPoint here = client.getLocalPlayer().getWorldLocation();
    WorldPoint best = null;
    int bestDist = Integer.MAX_VALUE;
    for (WorldPoint t : course.startTiles) {
        int dx = t.getX() - here.getX();
        int dy = t.getY() - here.getY();
        int d  = dx*dx + dy*dy;
        if (d < bestDist) { bestDist = d; best = t; }
    }
    if (best == null) {
        log.warn("[rooftop-agility] no startTiles in course — cannot recover");
        return;
    }
    dispatcher.dispatch(ActionRequest.builder()
        .kind(ActionRequest.Kind.CLICK_TILE)
        .channel(ActionRequest.Channel.MOUSE)
        .worldPoint(best)
        .build());
}
```

`worldPoint(...)` setter name may differ — match against any existing
`CLICK_TILE` usage in the repo.

- [ ] **Step 2: Commit**

```bash
git add -u && git commit -m "feat(agility): walkToNearestStartTile — closest startTile click"
```

---

## Phase 5 — Recovery handlers

### Task 15: `handleFallOrInvalidPosition`

- [ ] **Step 1: Implement**

```java
private boolean handleFallOrInvalidPosition(long now)
{
    WorldPoint p = client.getLocalPlayer().getWorldLocation();

    if (course.fallTiles.contains(p)) {
        status.set("Fell — recovering to start");
        log.info("[rooftop-agility] fell at {} — recovering", p);
        walkToNearestStartTile();
        long delay = 1_200L + (long) (Math.random() * 1_300L);
        nextActionAt = now + delay;
        return true;
    }

    if (!course.validTiles.contains(p) && !course.startTiles.contains(p)) {
        status.set("Outside course — recovering to start");
        log.info("[rooftop-agility] off-route at {} — recovering", p);
        walkToNearestStartTile();
        long delay = 1_200L + (long) (Math.random() * 1_300L);
        nextActionAt = now + delay;
        return true;
    }

    return false;
}
```

- [ ] **Step 2: Commit**

```bash
git add -u && git commit -m "feat(agility): handleFallOrInvalidPosition — fall/off-route recovery"
```

### Task 16: `handleUnmappedValidTile`

- [ ] **Step 1: Implement**

```java
private boolean handleUnmappedValidTile(long now)
{
    WorldPoint p = client.getLocalPlayer().getWorldLocation();
    if (course.stageByTile.containsKey(p)) {
        unknownStageTile = null;
        return false;
    }
    // We're on a validTile (fall/invalid already handled) but no stage match.
    if (!p.equals(unknownStageTile)) {
        unknownStageTile  = p;
        unknownStageSince = now;
    }
    if (now - unknownStageSince > UNMAPPED_TILE_TIMEOUT_MS) {
        status.set("Unmapped valid course tile: " + p);
        log.warn("[rooftop-agility] unmapped valid tile {} for >8s — stopping for data fix", p);
        running.set(false);
        state.set(State.IDLE);
        return true;
    }
    status.set("Waiting for known stage (at " + p + ")");
    nextActionAt = now + 600;
    return true;
}
```

- [ ] **Step 2: Commit**

```bash
git add -u && git commit -m "feat(agility): handleUnmappedValidTile — 8s timeout w/ exact-tile stop message"
```

---

## Phase 6 — Marks of grace

### Task 17: `tryPickupReachableMark`

- [ ] **Step 1: Implement**

```java
private static final int ITEM_MARK_OF_GRACE = net.runelite.api.gameval.ItemID.GRACE;

private boolean tryPickupReachableMark(long now)
{
    if (!pickupMarks) return false;
    int stage = detectCurrentStage();
    if (stage == UNKNOWN) return false;

    RooftopNode node = course.nodes.get(stage);
    WorldPoint player = client.getLocalPlayer().getWorldLocation();

    net.runelite.api.Scene scene = client.getScene();
    net.runelite.api.Tile[][][] tiles = scene.getTiles();
    int plane = client.getPlane();
    int baseX = client.getBaseX();
    int baseY = client.getBaseY();

    for (WorldPoint mt : node.reachableMarkTiles) {
        if (mt.getPlane() != player.getPlane()) continue;
        if (mt.getPlane() != plane)             continue;
        int sx = mt.getX() - baseX;
        int sy = mt.getY() - baseY;
        if (sx < 0 || sy < 0 || sx >= 104 || sy >= 104) continue;
        net.runelite.api.Tile t = tiles[plane][sx][sy];
        if (t == null) continue;
        java.util.List<net.runelite.api.TileItem> items = t.getGroundItems();
        if (items == null) continue;
        for (net.runelite.api.TileItem ti : items) {
            if (ti == null || ti.getId() != ITEM_MARK_OF_GRACE) continue;

            dispatcher.dispatch(ActionRequest.builder()
                .kind(ActionRequest.Kind.CLICK_GROUND_ITEM)
                .channel(ActionRequest.Channel.MOUSE)
                .worldPoint(mt)
                .itemId(ITEM_MARK_OF_GRACE)
                .menuOption("Take")
                .build());

            state.set(State.PICKING_MARK);
            markTileClicked = mt;
            markClickAt     = now;
            markCountBefore = inventoryCount(ITEM_MARK_OF_GRACE);
            long delay = 600L + (long) (Math.random() * 400L);   // 600–1000
            nextActionAt = now + delay;
            status.set("Picking up mark at " + mt);
            log.info("[rooftop-agility] picking mark at {} from stage {}", mt, stage);
            return true;
        }
    }
    return false;
}

private int inventoryCount(int itemId)
{
    net.runelite.api.ItemContainer inv = client.getItemContainer(
        net.runelite.api.gameval.InventoryID.INV);
    if (inv == null) return 0;
    int n = 0;
    for (net.runelite.api.Item it : inv.getItems()) {
        if (it != null && it.getId() == itemId) n += it.getQuantity();
    }
    return n;
}
```

`CLICK_GROUND_ITEM` builder setters — confirm names against
`ActionRequest.Builder`. The point is `worldPoint`, `itemId`, and
`menuOption="Take"`.

- [ ] **Step 2: Commit**

```bash
git add -u && git commit -m "feat(agility): tryPickupReachableMark — plane + reachableMarkTiles filtered"
```

### Task 18: `handlePickingMark`

- [ ] **Step 1: Implement**

```java
private void handlePickingMark(long now)
{
    boolean markGone = !markStillOnTile(markTileClicked);
    int     nowCount = inventoryCount(ITEM_MARK_OF_GRACE);

    if (nowCount > markCountBefore) {
        marksPicked++;
        state.set(State.RUNNING);
        markTileClicked = null;
        log.info("[rooftop-agility] mark picked — total {}", marksPicked);
        return;
    }
    if (markGone) {
        state.set(State.RUNNING);
        markTileClicked = null;
        log.info("[rooftop-agility] mark gone from tile (despawn or scene change)");
        return;
    }
    if (now - markClickAt > MARK_PICKUP_TIMEOUT_MS) {
        state.set(State.RUNNING);
        markTileClicked = null;
        log.info("[rooftop-agility] mark pickup timeout — resyncing");
        return;
    }
}

private boolean markStillOnTile(WorldPoint mt)
{
    if (mt == null) return false;
    int plane = client.getPlane();
    int sx = mt.getX() - client.getBaseX();
    int sy = mt.getY() - client.getBaseY();
    if (sx < 0 || sy < 0 || sx >= 104 || sy >= 104) return false;
    net.runelite.api.Tile t = client.getScene().getTiles()[plane][sx][sy];
    if (t == null) return false;
    java.util.List<net.runelite.api.TileItem> items = t.getGroundItems();
    if (items == null) return false;
    for (net.runelite.api.TileItem ti : items) {
        if (ti != null && ti.getId() == ITEM_MARK_OF_GRACE) return true;
    }
    return false;
}
```

- [ ] **Step 2: Commit**

```bash
git add -u && git commit -m "feat(agility): handlePickingMark — three exit conditions w/ marks counter"
```

---

## Phase 7 — Run energy + lifecycle

### Task 19: `maybeEnableRun`

- [ ] **Step 1: Inspect the run-orb widget**

```bash
grep -rnE 'RUN_ORB|MinimapOrb|RUN.*WIDGET|runOrb' \
  runelite-client/src/main/java/net/runelite/client/plugins/recorder/ \
  runelite-client/src/main/java/net/runelite/client/sequence/ 2>/dev/null | head
```

If no existing run-toggle helper, dispatch a `CLICK_WIDGET` against the
run-orb widget id (use `InterfaceID` from `runelite-api`).

- [ ] **Step 2: Implement**

```java
private boolean maybeEnableRun(long now)
{
    if (client.getVarpValue(173) == 1) return false;        // VarPlayer 173 == run enabled
    if (now < nextRunToggleAt)         return false;

    int energyPercent = client.getEnergy() / 100;           // 0..10000 raw
    if (energyPercent < runOnAtLeast)  return false;

    // Run-orb widget id; confirm during inspection
    int runOrbWidget = net.runelite.api.gameval.InterfaceID.OrbsResizableModern.RUN;
    dispatcher.dispatch(ActionRequest.builder()
        .kind(ActionRequest.Kind.CLICK_WIDGET)
        .channel(ActionRequest.Channel.MOUSE)
        .widgetId(runOrbWidget)
        .build());
    nextRunToggleAt = now + RUN_TOGGLE_THROTTLE_MS;
    log.info("[rooftop-agility] enabling run (energy {}% >= threshold {}%)",
        energyPercent, runOnAtLeast);
    return true;
}
```

The exact `InterfaceID` constant path varies between RuneLite versions —
if `OrbsResizableModern.RUN` doesn't exist, use the right group (search
the api package). Confirm there's no separate fixed-vs-resizable
distinction we need to handle for v1.

Reseed `runOnAtLeast` in `start()` and whenever run becomes disabled
(latter: in Task 20).

- [ ] **Step 3: Commit**

```bash
git add -u && git commit -m "feat(agility): maybeEnableRun — one ActionRequest per tick, throttled"
```

### Task 20: Implement `start()` + run-threshold reseed

- [ ] **Step 1: Replace the no-op `start()`**

```java
public void start()
{
    if (running.get()) return;
    if (client.getGameState() != GameState.LOGGED_IN) {
        status.set("Not logged in");
        return;
    }
    course = COURSES.get(selectedCourse);
    if (course == null) {
        status.set("No course profile for " + selectedCourse);
        return;
    }
    try { validateCourse(course); } catch (RuntimeException ex) {
        status.set("Course validation failed: " + ex.getMessage());
        log.error("[rooftop-agility] validateCourse failed", ex);
        return;
    }
    if (targetLevel <= client.getRealSkillLevel(Skill.AGILITY)) {
        status.set("Target level " + targetLevel + " already reached");
        return;
    }
    WorldPoint p = client.getLocalPlayer().getWorldLocation();
    boolean recoverable =
           course.validTiles.contains(p)
        || course.startTiles.contains(p)
        || course.fallTiles.contains(p)
        || course.lapEndTiles.contains(p);
    if (!recoverable) {
        status.set("Player not on a recoverable Draynor tile (at " + p + ")");
        return;
    }

    startedAt           = System.currentTimeMillis();
    nextActionAt        = 0L;
    nextRunToggleAt     = 0L;
    runOnAtLeast        = 20 + (int) (Math.random() * 21);   // 20..40
    lastClickedNode     = null;
    lastClickedStage    = UNKNOWN;
    lastObstacleClickAt = 0L;
    markTileClicked     = null;
    unknownStageTile    = null;
    lapsCompleted       = 0;
    marksPicked         = 0;
    state.set(State.RUNNING);
    status.set("Running — " + course.label);
    running.set(true);
    log.info("[rooftop-agility] started — course={}, target={}, eatAtHp={}, pickupMarks={}",
        course.label, targetLevel, eatAtHp, pickupMarks);
}
```

- [ ] **Step 2: Commit**

```bash
git add -u && git commit -m "feat(agility): start() — pre-flight validation + state reset"
```

---

## Phase 8 — Plugin + Panel wiring

### Task 21: Construct + clean up in `RecorderPlugin`

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPlugin.java`

- [ ] **Step 1: Inspect existing fletching wiring**

Read lines around 437 (construction) and 907 (cleanup). Mirror that
shape for the agility script.

- [ ] **Step 2: Add the field**

Near the other script fields (search for `private FletchingScript`),
add:

```java
private net.runelite.client.plugins.recorder.scripts.RooftopAgilityScript rooftopAgilityScript;
```

- [ ] **Step 3: Construct + register** (near line 437)

```java
rooftopAgilityScript = new net.runelite.client.plugins.recorder.scripts.RooftopAgilityScript(
    client, clientThread, dispatcher);
eventBus.register(rooftopAgilityScript);
// Pass to panel (Task 22 adds the setter)
panel.setRooftopAgilityScript(rooftopAgilityScript);
```

- [ ] **Step 4: Cleanup** (near line 907)

```java
if (rooftopAgilityScript != null) {
    rooftopAgilityScript.stop();
    eventBus.unregister(rooftopAgilityScript);
    rooftopAgilityScript = null;
}
```

- [ ] **Step 5: Commit**

```bash
git add -u && git commit -m "feat(agility): wire RooftopAgilityScript into RecorderPlugin"
```

### Task 22: Panel tab + isRunning poll

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPanel.java`

- [ ] **Step 1: Add the script field + setter** (near line 348)

```java
// Rooftop Agility script.
private net.runelite.client.plugins.recorder.scripts.RooftopAgilityScript rooftopAgilityScript;

public void setRooftopAgilityScript(
    net.runelite.client.plugins.recorder.scripts.RooftopAgilityScript s)
{
    this.rooftopAgilityScript = s;
}
```

- [ ] **Step 2: Register the tab** (near line 423)

```java
tabs.addTab("Agility", tabScroll(buildAgilityTab()));
```

- [ ] **Step 3: Build the tab**

Mirror the fletching tab builder (line 2853 onward). Replicate the
layout described in spec §16 with these controls:

- `JComboBox<RooftopCourseId>` (Draynor only for v1).
- `JSpinner` for target level (default 20, range 1..99).
- `JCheckBox` for pickup marks (default true).
- `JSpinner` for `eatAtHp` (default 8, range 1..99).
- Start / Stop buttons.
- `JLabel` for status that polls `script.status()` + lap/mark/runtime.

On Start click, push panel values into the script via `setSelectedCourse`,
`setTargetLevel`, `setPickupMarks`, `setEatAtHp`, then call
`script.start()` from a `Thread.start()` to avoid blocking the EDT (per
CLAUDE.md §"Threading model" worker-thread rule for panel handlers).

On Stop click, call `script.stop()`. Disable Start while running, Stop
while idle.

```java
private JPanel buildAgilityTab()
{
    JPanel p = new JPanel();
    p.setBorder(BorderFactory.createTitledBorder("Rooftop Agility"));
    // ... lay out the controls described above; mirror buildFletchingTab()
    return p;
}
```

- [ ] **Step 4: Register the isRunning poll** (near line 3817)

```java
registerRunningPoll(
    () -> rooftopAgilityScript != null && rooftopAgilityScript.isRunning());
```

(Match the actual helper name from the surrounding poll registrations.)

- [ ] **Step 5: Commit**

```bash
git add -u && git commit -m "feat(agility): panel tab + isRunning poll"
```

### Task 23: Panel status formatter (runtime + laps + marks)

- [ ] **Step 1: Add the formatter**

Inside the agility tab's status label updater (Swing timer that polls
the script), render:

```java
String runtime = "—";
if (rooftopAgilityScript.isRunning()) {
    long ms = System.currentTimeMillis() - rooftopAgilityScript.startedAt();
    long sec = ms / 1000;
    runtime = String.format("%d:%02d:%02d", sec / 3600, (sec / 60) % 60, sec % 60);
}
statusLabel.setText(String.format("%s — %d laps — %d marks — %s",
    rooftopAgilityScript.status(),
    rooftopAgilityScript.lapsCompleted(),
    rooftopAgilityScript.marksPicked(),
    runtime));
```

- [ ] **Step 2: Commit**

```bash
git add -u && git commit -m "feat(agility): panel status — laps/marks/runtime line"
```

---

## Phase 9 — User-initiated acceptance (USER GATE)

**STOP HERE. Hand back to the user.** The user runs gradle + the client
themselves, exercises the manual acceptance plan from spec §21, and
reports failures. Subagents do not run the build or the client.

### Task 24: User runs the build

- [ ] **Step 1: User confirms readiness** — once the user is ready to
  start in-game testing, they say so. The assistant may then run:

```bash
JBIN=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home/bin/java
JAVA_HOME=$(dirname $JBIN)/.. ./gradlew :client:shadowJar
```

(Use the exact commands from `CLAUDE.md` §"Build & run".)

- [ ] **Step 2: Fix any compile errors** — re-read `compileJava` output,
  patch import paths / builder setter names, commit each fix as a
  separate `fix(agility): …` commit.

### Task 25: User executes acceptance items 1–16 from spec §21

- [ ] **Step 1: User performs each item** — cold start, resume mid-
  course, forced fall, off-route walk, mark on/off rooftop, obstacle
  timeout, low HP with/without food, target reached, click throttle,
  stuck menu, unmapped valid tile, validation failure, tightrope
  disambiguation, one-ActionRequest-per-tick.

- [ ] **Step 2: For each failure** — user reports the failing item; we
  open a `fix(agility): …` commit, surgical change, re-run the failing
  item only. No mass rewrites.

- [ ] **Step 3: When all 16 pass** — commit a `docs(agility): mark v1
  spec accepted` flipping the spec header status to `ACCEPTED YYYY-MM-DD`.

---

## Plan self-review

**Spec coverage** (skim against
`docs/superpowers/specs/2026-05-21-rooftop-agility-design.md` sections):

| Spec § | Plan task |
|--------|-----------|
| §1 Goal | implicit (whole plan) |
| §2 Non-goals | implicit (no banking, no inter-region, no auto-progress; nothing in plan touches those) |
| §3 File layout | Task 1 + Task 21 + Task 22 |
| §4 Threading | header + Task 6 (event subscriber) |
| §5 State machine | Task 1 (enum) |
| §6 Data model | Task 2 |
| §6a validateCourse | Task 3 |
| §7 Throttling | Task 6 (gate) + each handler sets `nextActionAt` |
| §8 Tick loop order | Task 6 (full ordering matches spec §8) |
| §9 detectCurrentStage | Task 11 |
| §10 Fall/invalid | Task 15 |
| §10a Unmapped valid tile | Task 16 |
| §11 Click + timeout | Task 12 + Task 13 |
| §12 Marks of grace | Task 17 + Task 18 |
| §13 HP / run | Task 9 + Task 19 |
| §14 Target level | Task 7 + DEFAULT_TARGET_LEVEL in Task 1 |
| §15 Lap tracking + startedAt | Task 13 (lap inc) + Task 20 (startedAt) |
| §16 Panel UI | Task 22 + Task 23 |
| §17 Capture workflow | Task 0 (user-owned) |
| §18 Object IDs / verbs | Task 4 + Task 5 (per-capture data) |
| §19 Logging | every handler `log.info(...)` with `[rooftop-agility]` |
| §20 Failure-mode register | covered by handlers in Tasks 13, 15, 16, 18 |
| §21 Manual acceptance | Phase 9 |

**Placeholder scan:** No `TBD` / `TODO` / "fill in" / "similar to Task N"
strings. Every code step contains the actual code. Two known
inspect-before-write callouts in Task 8 (key-dispatch helper), Task 9
(ActionRequest setter names), Task 12 (scene-lookup helper), Task 14
(CLICK_TILE setter), Task 17 (CLICK_GROUND_ITEM setter), Task 19 (run-
orb widget id). These are not placeholders — they're explicit
"inspect-the-existing-code-before-guessing" instructions because the
ActionRequest builder surface evolves, and the memory rule says
"describe by behavior, don't … guess existing APIs ('inspect first')".

**Type consistency:**
- `RooftopCourseId` (enum) — used consistently in `selectedCourse`,
  `COURSES`, `RooftopCourse.id`.
- `RooftopNode` — used in `RooftopCourse.nodes`, `lastClickedNode`,
  `clickObstacle(int, RooftopNode, long)`.
- `State.PICKING_MARK` — set in Task 17, cleared in Task 18.
- `nextActionAt`, `nextRunToggleAt` — both `long`, both reset in `start()`.
- `lastClickedStage` — `int`, used in `expectedSuccessTiles(stage)`.
- `eatAtHp` field name consistent: Task 1 declares, Task 9 reads, Task
  22 panel writes.
- `targetLevel` field name consistent across Task 1, 7, 20, 22.
- `pickupMarks` field name consistent across Task 1, 17, 22.
- `inventoryCount(int)` defined in Task 17, called in Tasks 17 + 18.
- `walkToNearestStartTile()` declared as stub in Task 13, implemented in
  Task 14.
- `clearLastObstacle()` defined in Task 13, called in Tasks 13 (×3).

No mismatches found.

---

## Execution handoff

Plan saved to `docs/superpowers/plans/2026-05-21-rooftop-agility.md`.
Two execution options:

1. **Subagent-Driven (recommended)** — fresh subagent per task, review
   between tasks. Pairs naturally with the user-gate phases (capture
   delivery, acceptance) where the loop pauses for human input anyway.
2. **Inline Execution** — execute tasks in this session using
   executing-plans, batch with checkpoints.

The user already asked for subagent-driven. Awaiting "go" before
dispatching the first subagent (which will begin at Task 1, since Task 0
is a user-owned capture gate).
