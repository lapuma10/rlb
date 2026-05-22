# Agility Course Recorder Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a standalone in-game capture mode that watches the user run a rooftop agility course and emits a fully-populated JSON consumable by the existing `RooftopCourseLoader`, eliminating the manual tile-marking workflow used for Draynor.

**Architecture:** A new `recorder/agility/` subpackage with five files: a session class with three RuneLite `@Subscribe` handlers (MenuOptionClicked, StatChanged, GameTick), per-lap accumulators, a side-panel UI, an in-canvas tile overlay, and a JSON writer. All client-thread observers, no input dispatch. Output is JSON in `~/.runelite/recorder/rooftops/<id>.json` that the existing loader picks up at startup.

**Tech Stack:** Java 17, RuneLite plugin API, Swing for panel/overlay, Gson for JSON.

**Spec reference:** `docs/superpowers/specs/2026-05-22-agility-course-recorder-design.md` (locked 2026-05-22). For any behavior in this plan that is described tersely, the spec is canonical.

**Per-task verification convention.** Per project memory (`feedback_no_tests_for_bot_scripts`), gameplay-adjacent tools skip unit tests and are validated by manual in-game runs. Each implementation task ends with a **compile check**:

```
JBIN=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home/bin/java
JAVA_HOME=$(dirname $JBIN)/.. ./gradlew :client:compileJava
```

Expected: `BUILD SUCCESSFUL`. Manual in-game verification lives in the final three tasks (15–17).

**Inspection-first targets** referenced repeatedly:

- `recorder/capture/ClickResolver.java:43-110` — canonical pattern for handling `MenuOptionClicked` + `MenuAction.GAME_OBJECT_*` filtering + `entry.getIdentifier()` for objectId. **Mirror this pattern** rather than re-discovering it.
- `recorder/transport/RouteOverlay.java` and `recorder/combat/ChickenOverlay.java` — canonical patterns for `extends Overlay` + `Perspective.getCanvasTilePoly` rendering.
- `recorder/scripts/RooftopAgilityScript.java` — runtime consumer of the JSON; never modified by this plan, but a reference for the data shape we emit.
- `recorder/scripts/RooftopCourseLoader.java` — JSON schema target; never modified by v1 of this plan.
- `recorder/RecorderPlugin.java:107-711` — panel + overlay + subscriber registration patterns.

---

## File Structure

```
runelite-client/src/main/java/net/runelite/client/plugins/recorder/agility/

  ClickOutcome.java               enum
  LapState.java                   enum
  ObstacleSignature.java          value type with equals/hashCode
  PendingClick.java               mutable per-click record
  ObstacleObservation.java        per-stage accumulator
  CaptureModel.java               session-wide state container

  AgilityCaptureSession.java      ~250 LOC: subscribers + state machine
  CourseJsonWriter.java           ~120 LOC: model → JSON file
  AgilityCaptureOverlay.java      ~120 LOC: in-canvas tile coloring
  AgilityCaptureTab.java          ~150 LOC: panel section

  RooftopCourseDefaults.java      static map: course id → label/level/count
```

Touches to existing files: `RecorderPlugin.java`, `RecorderPanel.java`. **No changes to `RooftopAgilityScript.java`, `RooftopCourseLoader.java`, or any runtime code.**

---

### Task 1: Enums, signature, and pending-click value types

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/agility/ClickOutcome.java`
- Create: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/agility/LapState.java`
- Create: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/agility/ObstacleSignature.java`
- Create: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/agility/PendingClick.java`

- [ ] **Step 1: Create the two enums.**

```java
// ClickOutcome.java
package net.runelite.client.plugins.recorder.agility;
public enum ClickOutcome { PENDING, SUCCESS, IGNORED, BROKEN_LAP, UNKNOWN }

// LapState.java
package net.runelite.client.plugins.recorder.agility;
public enum LapState { ARMED, IN_LAP, OFF_COURSE, LAP_COMPLETE }
```

- [ ] **Step 2: Create `ObstacleSignature` (value type with equals/hashCode).**

```java
package net.runelite.client.plugins.recorder.agility;

import java.util.Objects;
import net.runelite.api.coords.WorldPoint;

public final class ObstacleSignature
{
    public final int objectId;
    public final WorldPoint objectTile;
    public final String verb;

    public ObstacleSignature(int objectId, WorldPoint objectTile, String verb)
    {
        this.objectId   = objectId;
        this.objectTile = objectTile;
        this.verb       = verb;
    }

    @Override public boolean equals(Object o)
    {
        if (this == o) return true;
        if (!(o instanceof ObstacleSignature)) return false;
        ObstacleSignature s = (ObstacleSignature) o;
        return objectId == s.objectId
            && Objects.equals(objectTile, s.objectTile)
            && Objects.equals(verb, s.verb);
    }

    @Override public int hashCode() { return Objects.hash(objectId, objectTile, verb); }

    @Override public String toString() { return "Sig(" + objectId + "," + objectTile + ",'" + verb + "')"; }
}
```

- [ ] **Step 3: Create `PendingClick` (fields per spec §5; no methods).**

Fields: `int objectId`, `String verb`, `WorldPoint objectTile`, `WorldPoint sourceTile`, `long clickAtMs`, `long deadlineMs`, `long xpBefore`, `ClickOutcome outcome` (default `PENDING`). Constructor takes all but `outcome`.

- [ ] **Step 4: Run compile check.** Command above. Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit.**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/agility/
git commit -m "feat(agility-recorder): enums + ObstacleSignature + PendingClick"
```

---

### Task 2: `ObstacleObservation` + `CaptureModel`

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/agility/ObstacleObservation.java`
- Create: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/agility/CaptureModel.java`

- [ ] **Step 1: Create `ObstacleObservation`.** Fields per spec §5 ("ObstacleObservation"): `int orderIndex`, `Set<Integer> objectIds`, `Set<String> verbs`, `Set<String> objectLabels`, `Set<WorldPoint> stageTiles`, `Set<WorldPoint> objectTiles`, `Set<WorldPoint> successTiles`, `long maxClickToXpMs`, `int successCount`, `ObstacleSignature signature`. All sets `new HashSet<>()`, ints/longs `0`. Single no-arg constructor. Same class doubles as `PerLapObservation` — there's no separate type, per spec §5 note.

- [ ] **Step 2: Create `CaptureModel`.** Fields per spec §5 ("CaptureModel"). Pure container; no logic. Use `LinkedHashMap`/`ArrayList`/`HashSet` per spec. Initialise `state = LapState.ARMED`, `currentLapDirty = false`. Add a `reset()` method that re-initialises all collections + state — used by Cancel.

    Add one field not explicitly named in spec §5: `Deque<Sample> approachRing = new ArrayDeque<>()` where `Sample` is a private static nested type `{ long t; WorldPoint p; }`. This is the 10-second tile ring buffer referenced by spec §9.1 ("approachRingBuffer") — used by Task 5 to populate `approachTiles` on the first-ever SUCCESS.

- [ ] **Step 3: Compile + commit.**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/agility/
git commit -m "feat(agility-recorder): CaptureModel + ObstacleObservation"
```

---

### Task 3: `RooftopCourseDefaults`

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/agility/RooftopCourseDefaults.java`

- [ ] **Step 1: Create the static defaults map.** One row per course ID per spec §10 (verified counts):

```java
package net.runelite.client.plugins.recorder.agility;

import java.util.EnumMap;
import java.util.Map;
import net.runelite.client.plugins.recorder.scripts.RooftopAgilityScript.RooftopCourseId;

public final class RooftopCourseDefaults
{
    public static final class Row {
        public final String label;
        public final int level;
        public final int obstacleCount;
        public Row(String label, int level, int obstacleCount) {
            this.label = label; this.level = level; this.obstacleCount = obstacleCount;
        }
    }

    public static final Map<RooftopCourseId, Row> ROWS = new EnumMap<>(RooftopCourseId.class);
    static {
        ROWS.put(RooftopCourseId.DRAYNOR,      new Row("Draynor Village Rooftop", 1, 7));
        // ...remaining 8 rows per spec §10 table...
    }

    private RooftopCourseDefaults() {}
}
```

**Important:** Add the other 8 entries verbatim from spec §10. Any `RooftopCourseId` enum values that don't exist yet must be added to `RooftopCourseId` in `RooftopAgilityScript.java` — see step 2.

- [ ] **Step 2: Add missing enum entries.** Open `runelite-client/src/main/java/net/runelite/client/plugins/recorder/scripts/RooftopAgilityScript.java`. Find the `enum RooftopCourseId { DRAYNOR }` declaration. Extend to all nine: `{ DRAYNOR, AL_KHARID, VARROCK, CANIFIS, FALADOR, SEERS, POLLNIVNEACH, RELLEKKA, ARDOUGNE }`. This is the **only** edit to this file required by v1 — purely additive, no runtime behavior change.

- [ ] **Step 3: Compile + commit.**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/agility/RooftopCourseDefaults.java \
        runelite-client/src/main/java/net/runelite/client/plugins/recorder/scripts/RooftopAgilityScript.java
git commit -m "feat(agility-recorder): RooftopCourseDefaults + extend RooftopCourseId enum"
```

---

### Task 4: `AgilityCaptureSession` skeleton + subscriber stubs

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/agility/AgilityCaptureSession.java`

- [ ] **Step 1: Create the class shell.** `@Slf4j` annotated, logger tag `[agility-capture]`. Fields:
  - `private final Client client` (injected via constructor)
  - `private final EventBus eventBus`
  - `private final CaptureModel model = new CaptureModel(...)` — model is rebuilt by `start()`
  - `private boolean active = false`
  - approach-tile ring buffer (`Deque<Sample>` where `Sample` is `{ long t, WorldPoint p }`).
- [ ] **Step 2: Add `start(RooftopCourseId id, String label, int levelReq, int expectedObstacleCount)`** — initialises `model`, sets `active = true`, registers `this` on `eventBus`, sets `model.state = LapState.ARMED`. Add `stop()` that unregisters and clears active.
- [ ] **Step 3: Add empty `@Subscribe` handlers:**
  ```java
  @Subscribe public void onMenuOptionClicked(MenuOptionClicked e) { if (!active) return; /* Task 6 */ }
  @Subscribe public void onStatChanged(StatChanged e)             { if (!active) return; /* Task 7 */ }
  @Subscribe public void onGameTick(GameTick e)                   { if (!active) return; /* Task 5 */ }
  ```

  Use the same import + annotation style as `RecorderPlugin.java` line 1003+.
- [ ] **Step 4: Compile + commit.**

```bash
git commit -am "feat(agility-recorder): AgilityCaptureSession skeleton + subscriber stubs"
```

---

### Task 5: GameTick handler — player-tile sampling, approach ring buffer, deadline ticking

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/agility/AgilityCaptureSession.java`

- [ ] **Step 1: Sample player tile each tick.** In `onGameTick`:

```java
WorldPoint p = client.getLocalPlayer().getWorldLocation();
long now = System.currentTimeMillis();

// 1. Approach ring buffer — collected ONLY before any first SUCCESS, while
//    model.obstacles is empty (we haven't yet learned the course start).
if (model.obstacles.isEmpty()) {
    model.approachRing.add(new Sample(now, p));
    model.approachRing.removeIf(s -> now - s.t > 10_000);   // 10s window per spec §9.1
}

// 2. Per-lap tile buffer — accumulated only while we're IN_LAP.
if (model.state == LapState.IN_LAP) {
    model.currentLapTiles.add(p);
}

// 3. PENDING deadline check — see Task 8.
maybeExpirePendingClick(now, p);
```

`Sample` is a private static inner class: `{ long t; WorldPoint p; }`.

- [ ] **Step 2: Stub `maybeExpirePendingClick(now, p)`** as an empty method for now — wired in Task 8.

- [ ] **Step 3: Compile + commit.**

```bash
git commit -am "feat(agility-recorder): GameTick — player tile sampling + approach ring"
```

---

### Task 6: MenuOptionClicked handler — pending click creation + two-click race

**Files:**
- Modify: `AgilityCaptureSession.java`

- [ ] **Step 1: Filter for game-object actions.** Mirror `ClickResolver.java:102-106`:

```java
MenuEntry entry = e.getMenuEntry();
MenuAction type = entry.getType();
boolean isObjectClick =
       type == MenuAction.GAME_OBJECT_FIRST_OPTION
    || type == MenuAction.GAME_OBJECT_SECOND_OPTION
    || type == MenuAction.GAME_OBJECT_THIRD_OPTION
    || type == MenuAction.GAME_OBJECT_FOURTH_OPTION
    || type == MenuAction.GAME_OBJECT_FIFTH_OPTION;
if (!isObjectClick) return;
```

`EXAMINE_GAME_OBJECT` is intentionally excluded.

- [ ] **Step 2: Two-click race guard (spec §6.1).** If `model.pendingClick != null`, dirty the lap and return:

```java
if (model.pendingClick != null) {
    model.currentLapDirty = true;
    model.currentLapTiles.clear();
    model.currentLapObs.clear();
    model.pendingClick = null;
    model.state = LapState.OFF_COURSE;
    log.info("[agility-capture] two clicks queued — lap discarded");
    return;
}
```

- [ ] **Step 3: Resolve the GameObject's world tile.** The `MenuEntry` carries scene coords via `entry.getParam0() / getParam1()` (x, y of the clicked widget/scene cell); the object id is `entry.getIdentifier()`. **Inspect first:** read `ClickResolver.java:90-110` for the exact conversion to `WorldPoint`. For a GameObject menu click, the canonical conversion is:

```java
int sceneX = entry.getParam0();
int sceneY = entry.getParam1();
int plane  = client.getPlane();
WorldPoint objectTile = WorldPoint.fromScene(client, sceneX, sceneY, plane);
```

If `ClickResolver` uses a different pattern (e.g. `client.getScene().getTiles()` + `getGameObjects()`), prefer the existing pattern for consistency.

- [ ] **Step 4: Construct `PendingClick` and store.**

```java
int objectId        = entry.getIdentifier();
String verb         = entry.getOption();
WorldPoint sourceTile = client.getLocalPlayer().getWorldLocation();
long now            = System.currentTimeMillis();
long deadlineMs     = now + perObjectDeadline(objectId);     // Task 8 — for now return 12_000
long xpBefore       = client.getSkillExperience(Skill.AGILITY);

model.pendingClick = new PendingClick(objectId, verb, objectTile, sourceTile,
                                       now, deadlineMs, xpBefore);
```

- [ ] **Step 5: Stub `perObjectDeadline(int objectId)`** — returns `12_000L` for now. Full logic in Task 8.

- [ ] **Step 6: Compile + commit.**

```bash
git commit -am "feat(agility-recorder): MenuOptionClicked — PENDING + two-click race"
```

---

### Task 7: StatChanged handler — SUCCESS path + ARMED→IN_LAP + LAP_COMPLETE trigger

**Files:**
- Modify: `AgilityCaptureSession.java`

- [ ] **Step 1: Filter and validate.** In `onStatChanged`:

```java
if (e.getSkill() != Skill.AGILITY) return;
if (model.pendingClick == null)    return;
if (model.pendingClick.outcome != ClickOutcome.PENDING) return;
long now = System.currentTimeMillis();
if (now > model.pendingClick.deadlineMs) return;   // already expired; Task 8 handles
if (e.getXp() <= model.pendingClick.xpBefore) return;  // not an XP increase
```

- [ ] **Step 2: Build the `ObstacleObservation` for this success (spec §7.1).**

```java
WorldPoint successTile = client.getLocalPlayer().getWorldLocation();
WorldPoint sourceTile  = model.pendingClick.sourceTile;       // save before nulling

ObstacleObservation obs = new ObstacleObservation();
obs.orderIndex = model.currentLapObs.size();
obs.objectIds.add(model.pendingClick.objectId);
obs.verbs.add(model.pendingClick.verb);
obs.objectLabels.add(safeObjectLabel(client, model.pendingClick.objectId));
obs.stageTiles.add(sourceTile);
obs.objectTiles.add(model.pendingClick.objectTile);
obs.successTiles.add(successTile);
obs.maxClickToXpMs = now - model.pendingClick.clickAtMs;
obs.successCount   = 1;
obs.signature      = new ObstacleSignature(
                         model.pendingClick.objectId,
                         model.pendingClick.objectTile,
                         model.pendingClick.verb);
model.currentLapObs.add(obs);

// Ensure stage + success tiles in the lap's tile buffer for the
// containsAll save-gate (§14.7).
model.currentLapTiles.add(sourceTile);
model.currentLapTiles.add(successTile);

model.pendingClick.outcome = ClickOutcome.SUCCESS;
model.pendingClick = null;
```

`safeObjectLabel(client, id)` is a tiny helper that returns `client.getObjectDefinition(id).getName()` wrapped in a try/catch returning `""` on failure (used for debug-quality `objectLabels`).

- [ ] **Step 3: ARMED → IN_LAP transition.**

```java
if (model.state == LapState.ARMED) {
    model.state = LapState.IN_LAP;
    if (model.startTiles.isEmpty()) {                          // first-ever SUCCESS
        model.startTiles.add(sourceTile);
        for (Sample s : model.approachRing) model.approachTiles.add(s.p);
    }
}
```

- [ ] **Step 4: Lap completion trigger.**

```java
if (model.currentLapObs.size() == model.expectedObstacleCount) {
    handleLapComplete();    // implemented in Task 9
}
```

- [ ] **Step 5: Stub `handleLapComplete()`** as empty for now.

- [ ] **Step 6: Compile + commit.**

```bash
git commit -am "feat(agility-recorder): StatChanged — SUCCESS + ARMED→IN_LAP transition"
```

---

### Task 8: Deadline classifier — IGNORED / BROKEN_LAP / UNKNOWN + per-object deadline shrink

**Files:**
- Modify: `AgilityCaptureSession.java`

- [ ] **Step 1: Implement `maybeExpirePendingClick(now, p)`** (called from `onGameTick`, spec §7.2):

```java
private void maybeExpirePendingClick(long now, WorldPoint p)
{
    PendingClick pc = model.pendingClick;
    if (pc == null) return;
    if (now <= pc.deadlineMs) return;

    if (p.equals(pc.sourceTile)) {
        pc.outcome = ClickOutcome.IGNORED;
        model.pendingClick = null;
        return;
    }

    boolean onRoute =
           model.startTiles.contains(p)
        || model.approachTiles.contains(p)
        || anyKnownStageTile(p);

    pc.outcome = onRoute ? ClickOutcome.UNKNOWN : ClickOutcome.BROKEN_LAP;
    model.pendingClick = null;
    model.currentLapDirty = true;
    model.currentLapTiles.clear();
    model.currentLapObs.clear();
    model.state = LapState.OFF_COURSE;

    log.info("[agility-capture] lap broken ({}). Walk to course start.", pc.outcome);
}
```

`anyKnownStageTile(p)` scans `model.obstacles[*].stageTiles ∪ model.currentLapObs[*].stageTiles` for membership.

- [ ] **Step 2: Replace `perObjectDeadline` stub with the real formula (spec §7.3).**

```java
private long perObjectDeadline(int objectId)
{
    for (ObstacleObservation o : model.obstacles) {
        if (o.objectIds.contains(objectId) && o.maxClickToXpMs > 0) {
            long shrunk = Math.round(o.maxClickToXpMs * 1.5);
            return Math.max(8_000L, Math.min(12_000L, shrunk));
        }
    }
    return 12_000L;
}
```

- [ ] **Step 3: Compile + commit.**

```bash
git commit -am "feat(agility-recorder): deadline classifier + per-object deadline shrink"
```

---

### Task 9: Lap completion + signature comparison + merge

**Files:**
- Modify: `AgilityCaptureSession.java`

- [ ] **Step 1: Implement `handleLapComplete()` per spec §9.2.**

```java
private void handleLapComplete()
{
    List<ObstacleSignature> sequence = new ArrayList<>();
    for (ObstacleObservation o : model.currentLapObs) sequence.add(o.signature);

    if (model.canonicalSequence == null) {
        model.canonicalSequence = sequence;
        mergeObsIntoModel(model.currentLapObs);
        model.validTiles.addAll(model.currentLapTiles);
        model.cleanMatchingLaps = 1;
        model.lapEndTile = lastSuccessTile(model.currentLapObs);
        log.info("[agility-capture] canonical sequence established ({})", sequence.size());
    } else if (signaturesMatch(sequence, model.canonicalSequence)) {
        mergeObsIntoModel(model.currentLapObs);
        model.validTiles.addAll(model.currentLapTiles);
        model.cleanMatchingLaps++;
        log.info("[agility-capture] matching lap: {}", model.cleanMatchingLaps);
    } else {
        int diff = firstDiffIndex(sequence, model.canonicalSequence);
        log.warn("[agility-capture] lap sequence mismatch at index {} — discarded", diff);
    }

    model.currentLapTiles.clear();
    model.currentLapObs.clear();
    model.state = LapState.ARMED;
    model.currentLapDirty = false;
}
```

- [ ] **Step 2: Implement `signaturesMatch` and `firstDiffIndex` (spec §9.2 pseudocode).** Strict equality of `objectId`, `objectTile`, `verb` at every index. `firstDiffIndex` returns the first non-matching index, or `-1` if equal.

- [ ] **Step 3: Implement `mergeObsIntoModel(List<ObstacleObservation> lapObs)` per spec §9.3.** For each `o` in `lapObs`, find or create a matching `ObstacleObservation` in `model.obstacles` keyed by `orderIndex`, then `addAll` all sets, `max` the latency, and `successCount += 1`.

- [ ] **Step 4: `lastSuccessTile(lapObs)`** — returns the single tile in the last `ObstacleObservation`'s `successTiles` (there is exactly one within a single lap).

- [ ] **Step 5: Compile + commit.**

```bash
git commit -am "feat(agility-recorder): lap completion + canonical signature comparison"
```

---

### Task 10: Discard-Lap + Cancel + strict OFF_COURSE re-entry

**Files:**
- Modify: `AgilityCaptureSession.java`

- [ ] **Step 1: Add public methods callable from the panel:**

```java
public void discardCurrentLap()
{
    model.currentLapTiles.clear();
    model.currentLapObs.clear();
    model.pendingClick = null;
    model.currentLapDirty = true;
    model.state = LapState.OFF_COURSE;
    log.info("[agility-capture] current lap discarded");
}

public void cancel()
{
    model.reset();
    stop();        // unregister subscribers
}
```

- [ ] **Step 2: OFF_COURSE re-entry detection (spec §8 strict rule).** In `onGameTick`, after the tile-sampling block, before the deadline check:

```java
if (model.state == LapState.OFF_COURSE) {
    boolean canReenter;
    if (model.canonicalSequence == null) {
        // first-lap-broken soft reset: any next click is allowed.
        canReenter = true;
    } else {
        // strict: must return to start/approach.
        canReenter = (model.startTiles.contains(p) || model.approachTiles.contains(p))
                  && playerIdleForTicks(2);
    }
    if (canReenter) {
        model.state = LapState.ARMED;
        log.info("[agility-capture] re-armed for new lap");
    }
}
```

`playerIdleForTicks(n)` tracks idle ticks via the pose animation — implementer should follow the existing pattern in `RooftopAgilityScript.java` (`getPoseAnimation() == getIdlePoseAnimation()`) and a counter incremented in `onGameTick`.

- [ ] **Step 3: Compile + commit.**

```bash
git commit -am "feat(agility-recorder): Discard Lap / Cancel + OFF_COURSE strict re-entry"
```

---

### Task 11: `CourseJsonWriter` — model → JSON file

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/agility/CourseJsonWriter.java`

- [ ] **Step 1: Create the writer.** Schema must match `RooftopCourseLoader.java` exactly (re-read its docstring before writing). Field-by-field rules per spec §13.3 ("Per-obstacle write rules"). Key snippets:

```java
public final class CourseJsonWriter
{
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    /** Returns the absolute Path written. */
    public Path write(CaptureModel m) throws IOException
    {
        JsonObject root = new JsonObject();
        root.addProperty("id", m.targetId.name());
        root.addProperty("label", m.label);
        root.addProperty("agilityLevel", m.agilityLevelReq);
        root.addProperty("scanRadius", 14);
        root.add("approachTiles", tilesArray(m.approachTiles));
        root.add("startTiles",    tilesArray(m.startTiles));
        root.add("fallTiles",     new JsonArray());      // always empty in v1
        root.add("lapEndTiles",   tilesArray(List.of(m.lapEndTile)));
        root.add("validTiles",    tilesArray(m.validTiles));

        JsonArray obstacles = new JsonArray();
        for (int i = 0; i < m.obstacles.size(); i++) {
            obstacles.add(obstacleJson(m.obstacles.get(i), i == m.obstacles.size() - 1));
        }
        root.add("obstacles", obstacles);

        Path dir  = Paths.get(RuneLite.RUNELITE_DIR.getAbsolutePath(),
                              "recorder", "rooftops");
        Files.createDirectories(dir);
        Path tmp   = dir.resolve(m.targetId.name().toLowerCase() + ".json.tmp");
        Path final_ = dir.resolve(m.targetId.name().toLowerCase() + ".json");
        Files.writeString(tmp, gson.toJson(root));
        Files.move(tmp, final_, StandardCopyOption.REPLACE_EXISTING);
        return final_;
    }
}
```

- [ ] **Step 2: `obstacleJson` per spec §13.3 table.** `objectId` = most-frequent entry in `obstacle.objectIds` (compute via counting; on tie pick lowest int). Same approach for `action` from `verbs` and `label` from `objectLabels`. `timeoutMs = clamp(round(maxClickToXpMs * 1.5), 4000, 12000)`. `successTiles` emits `[]` if `isFinal`.

- [ ] **Step 3: `tilesArray(Iterable<WorldPoint>)`** — returns a `JsonArray` of 3-element int arrays `[x, y, plane]`, matching the loader's parser.

- [ ] **Step 4: Compile + commit.**

```bash
git commit -am "feat(agility-recorder): CourseJsonWriter — model → JSON file"
```

---

### Task 12: `AgilityCaptureTab` — side-panel UI

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/agility/AgilityCaptureTab.java`

- [ ] **Step 1: Build the form.** Mirror the layout style of the existing fletching tab in `RecorderPanel.java`. Components per spec §12:
  - Course `JComboBox<RooftopCourseId>` — pre-fills label / level / count fields on selection (uses `RooftopCourseDefaults`).
  - Label `JTextField`.
  - Expected count `JSpinner` (int).
  - Level `JSpinner` (int).
  - Buttons row: `Start`, `Discard Lap`, `Save`, `Cancel`.
  - State label, Lap progress label, Matching laps label, Save status label.
  - Obstacle table (`JTable` or a simple `JLabel[]` per row; the latter is sufficient).
  - Approach/Start/LapEnd/ValidTiles count labels.

- [ ] **Step 2: Wire button handlers.** All button handlers run on the EDT. For Start/Save/Cancel that may need to read state from the client thread, marshal via `clientThread.invoke(...)`. For the Save click (when enabled per checklist), spawn `new Thread(() -> { ... })` to do the disk write — never call `CourseJsonWriter.write` on the EDT.

- [ ] **Step 3: 5-second start prompt (spec §12).** On Start press, show an inline notice for 5 seconds: *"Stand at the course start. First successful obstacle click defines obstacle 0."* Use a `Timer` to clear it.

- [ ] **Step 4: Save-status driver.** A `refreshSaveStatus()` method that:
  - Runs the checklist from spec §14 in order.
  - On the first failing item, sets the Save button enabled = false and updates the status label with the human-readable reason.
  - On all pass, enables Save and sets status to "Ready".
  Called from the session's per-tick polling (Task 13).

- [ ] **Step 5: Field locking.** From `start()` until `save()`/`cancel()` completes, the four input fields are `setEnabled(false)`.

- [ ] **Step 6: Compile + commit.**

```bash
git commit -am "feat(agility-recorder): AgilityCaptureTab — panel UI"
```

---

### Task 13: `AgilityCaptureOverlay` — in-canvas tile coloring

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/agility/AgilityCaptureOverlay.java`

- [ ] **Step 1: Create the overlay.** `extends Overlay` (mirror `recorder/transport/RouteOverlay.java`). Position `OverlayPosition.DYNAMIC`, layer `OverlayLayer.ABOVE_SCENE`. Constructor takes `Client client`, `AgilityCaptureSession session`.

- [ ] **Step 2: `render(Graphics2D g)` (spec §11).** Take a defensive snapshot of `model.validTiles`, `model.currentLapTiles`, `obstacles[*].objectTiles`, `model.lapEndTile` under `synchronized(session.modelLock())`. Then for each tile in scene range, draw via `Perspective.getCanvasTilePoly(client, tile)`:

| Set | Colour | Fill |
|---|---|---|
| `validTiles` | `new Color(64, 128, 255, 80)` | yes |
| `currentLapTiles` | `new Color(255, 200, 0, 80)` | yes |
| obstacle `objectTiles` | `new Color(255, 80, 80, 200)` | outline |
| `lapEndTile` | `new Color(255, 200, 0, 220)` | outline |

Skip null polygons (off-screen tiles).

- [ ] **Step 3: Toggleable.** A `boolean enabled` field controlled by a checkbox on the panel; `render` returns `null` early if false.

- [ ] **Step 4: Compile + commit.**

```bash
git commit -am "feat(agility-recorder): AgilityCaptureOverlay — tile coloring"
```

---

### Task 14: Save Preview modal + atomic write + post-save round-trip validation

**Files:**
- Modify: `AgilityCaptureTab.java`
- Reference: `CourseJsonWriter.java` (from Task 11)

- [ ] **Step 1: On Save button click, gate on checklist** (spec §14). If any item fails, the click is a no-op and the status label shows the blocker — already handled by `refreshSaveStatus()`. If all pass, proceed to Step 2.

- [ ] **Step 2: Build the preview text (spec §13.2).** Compute timeoutMs range across obstacles, count warnings (e.g. obstacles with `objectIds.size() > 1`), check whether the target file exists.

- [ ] **Step 3: Show the modal.** Use `JOptionPane.showOptionDialog(...)` with the preview text. Buttons:
  - If target file does not exist: `[Save]` `[Cancel]`.
  - If exists: `[Overwrite]` `[Save as .new]` `[Cancel]`.

- [ ] **Step 4: On confirm, spawn a worker thread:**

```java
new Thread(() -> {
    try {
        Path written = writer.write(session.getModel());     // atomic write via temp+rename
        validateRoundTrip(written);                          // step 5
        SwingUtilities.invokeLater(() -> setStatus("Saved ✓"));
    } catch (Exception ex) {
        log.error("[agility-capture] save failed", ex);
        SwingUtilities.invokeLater(() -> setStatus("Save failed: " + ex.getMessage()));
    }
}, "agility-capture-save").start();
```

- [ ] **Step 5: `validateRoundTrip(Path written)` (spec §15).**

```java
private void validateRoundTrip(Path written) throws Exception
{
    // Reload the just-written file via the same loader the runtime uses.
    Map<RooftopCourseId, RooftopCourse> loaded = RooftopCourseLoader.loadAll();
    RooftopCourse loadedCourse = loaded.get(session.getModel().targetId);
    if (loadedCourse == null) {
        throw new IllegalStateException("Round-trip: file " + written + " did not appear in loader output");
    }
    // RooftopAgilityScript.validateCourse(loadedCourse) is package-private —
    // expose a public re-runnable validator OR call the loader's own
    // validation path. Inspect RooftopCourseLoader.loadAll() to confirm
    // which validation it already runs at parse-time.
}
```

**Note:** Inspect `RooftopCourseLoader.java` first — its `loadAll()` already runs `validateCourse` per its docstring ("Files that fail to parse or fail `validateCourse` are logged and skipped"). The round-trip check therefore reduces to: did the freshly-written course appear in the returned map? If not, validation failed.

- [ ] **Step 6: On round-trip failure**, swap the panel status to the exception message and surface an extra `[Discard saved file]` button on the modal.

- [ ] **Step 7: Compile + commit.**

```bash
git commit -am "feat(agility-recorder): Save Preview modal + atomic write + round-trip validate"
```

---

### Task 15: Wire into `RecorderPlugin` + `RecorderPanel`

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPlugin.java`
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPanel.java`

- [ ] **Step 1: Add a session field + overlay in `RecorderPlugin`.** Mirror existing patterns at lines 134-138, 107, 705 of `RecorderPlugin.java`:

```java
private AgilityCaptureSession agilityCaptureSession;
private AgilityCaptureOverlay agilityCaptureOverlay;
```

- [ ] **Step 2: Instantiate in `startUp()`.** After existing panel/overlay setup:

```java
agilityCaptureSession = new AgilityCaptureSession(client, eventBus);
agilityCaptureOverlay = new AgilityCaptureOverlay(client, agilityCaptureSession);
overlayManager.add(agilityCaptureOverlay);
```

The session does NOT register subscribers on `eventBus` at startup — it registers only when `start()` is called from the panel (subscribers fire only during capture sessions). Verify this matches the `start()`/`stop()` design from Task 4.

- [ ] **Step 3: Remove in `shutDown()`.**

```java
if (agilityCaptureOverlay != null) overlayManager.remove(agilityCaptureOverlay);
if (agilityCaptureSession != null) agilityCaptureSession.stop();
```

- [ ] **Step 4: Add the panel tab.** In `RecorderPanel`, find the `JTabbedPane` (it's already created per the annotator-redesign spec). Add a new tab:

```java
AgilityCaptureTab agilityTab = new AgilityCaptureTab(agilityCaptureSession, agilityCaptureOverlay, clientThread);
tabs.addTab("Agility", new JScrollPane(agilityTab));
```

The session reference is passed in by `RecorderPlugin.startUp()` when constructing the panel — extend `RecorderPanel`'s constructor to accept it.

- [ ] **Step 5: Full client build.**

```bash
JAVA_HOME=$(dirname $JBIN)/.. ./gradlew :client:shadowJar
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit.**

```bash
git commit -am "feat(agility-recorder): wire into RecorderPlugin + RecorderPanel"
```

---

## Manual Acceptance (Tasks 16–17)

No further code changes. Each task is an in-game session that exercises the spec's §17 acceptance plan. After each session, write a one-line PASS/FAIL note into a scratch file. If any item FAILs, halt acceptance, file an issue, and return to the implementation tasks.

For each in-game session, launch the client per `CLAUDE.md`:

```
JBIN=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home/bin/java
$JBIN -ea \
  --add-opens java.desktop/com.apple.eawt=ALL-UNNAMED \
  --add-opens java.desktop/com.apple.eawt.event=ALL-UNNAMED \
  --add-opens java.base/java.lang=ALL-UNNAMED \
  -jar runelite-client/build/libs/client-*.jar --developer-mode &
```

---

### Task 16: Acceptance — Draynor ground-truth + break/recover paths

Covers spec §17.1 – §17.7.

- [ ] **§17.1 Draynor re-capture.** Stand at Draynor start tile, press Start in the Agility tab, run 2 clean laps, press Save. Verify the JSON appears at `~/.runelite/recorder/rooftops/draynor.json`. Reload the client and confirm `RooftopAgilityScript` picks it up (the dropdown lists Draynor; running the bot uses the freshly captured course). Diff against the hand-authored course in code: same obstacle count (7), same objectIds, same verbs, ≥80% stageTile overlap.
- [ ] **§17.2 First-lap-broken recovery.** Press Start at the start tile, intentionally fail Tightrope-1. Confirm HUD shows "Lap broken. Walk to course start...", confirm walking back to start allows the lap to restart.
- [ ] **§17.3 Mid-course start (negative test).** Press Start standing on Tightrope-2's stage tile. Click obstacles in normal order. Observe the lap completes with rotated canonical — confirm `cleanMatchingLaps` does not produce a usable Save (next lap will mismatch).
- [ ] **§17.4 Forced fall mid-lap-2.** On lap 2, fail tightrope-1. Confirm `currentLapTiles` discarded, matching-lap counter unchanged. Lap 3 from start → counter reaches 2.
- [ ] **§17.5 Spam-click two obstacles.** Confirm dirty-lap fires with the correct HUD message.
- [ ] **§17.6 Mismatched sequence anomaly.** Click obstacles deliberately out of order. Lap completes with N successes but `cleanMatchingLaps` does not increment; HUD warns of mismatch.
- [ ] **§17.7 Discard Lap mid-flight.** Confirm provisional data cleared, OFF_COURSE state, already-committed data unaffected.
- [ ] **Commit acceptance log.**

```bash
echo "Draynor acceptance: ..." > docs/superpowers/acceptance/2026-05-22-agility-recorder-draynor.md
git add docs/superpowers/acceptance/
git commit -m "docs(agility-recorder): Draynor acceptance log"
```

---

### Task 17: Acceptance — overwrite/cancel + Al Kharid first-time capture (the real goal)

Covers spec §17.8 – §17.11.

- [ ] **§17.8 Overwrite modal.** Capture Draynor twice; second save shows the overwrite modal with `[Overwrite]` and `[Save as .new]`. Both options behave correctly.
- [ ] **§17.9 Cancel with 1 clean lap.** Confirmation modal appears; in-memory cleared; no file written.
- [ ] **§17.10 Round-trip validation.** Confirm a Save with passing checklist completes the `RooftopCourseLoader.loadAll()` reload without errors.
- [ ] **§17.11 Al Kharid first-time capture (the real goal).** Travel to Al Kharid, stand at the start tile, capture 2 clean laps, save. Restart client, select Al Kharid in the dropdown of `RecorderPanel`'s rooftop-agility section, press Start on the bot itself. The bot should run end-to-end successfully — this is the **acceptance gate** that confirms the recorder produced a useful course.
- [ ] **Commit acceptance log + tag the feature as v1.**

```bash
echo "Al Kharid acceptance: ..." >> docs/superpowers/acceptance/2026-05-22-agility-recorder-draynor.md
git add docs/superpowers/acceptance/
git commit -m "docs(agility-recorder): Al Kharid acceptance — v1 ships"
```

---

## Notes for the executing engineer

- **Threading discipline matters.** Re-read `CLAUDE.md` §"Threading model" before touching any subscriber. Specifically: the `@Subscribe` handlers run on the client thread (safe for `client.getLocalPlayer()`, varbit reads, ObjectComposition lookups). Panel button handlers run on EDT — wrap any disk IO in `new Thread(...)`. The overlay's `render(Graphics2D)` runs on the AWT EDT but takes a defensive snapshot of `model` under `synchronized(modelLock)`.

- **Don't try to be clever with subscriber registration.** `eventBus.register(session)` in `start()` and `eventBus.unregister(session)` in `stop()` is sufficient. Don't lazily register inside individual `@Subscribe` methods.

- **JSON writer is your one piece of pure logic that COULD have a unit test.** Per project memory we still skip it for v1 — `validateRoundTrip` in Task 14 plus Draynor acceptance in Task 16 are the equivalents.

- **If `RecorderPanel.java` does not yet have the `JTabbedPane` shell** (annotator-redesign was checked in months ago but worth verifying), Task 15 step 4 needs to handle that — add the tabbed pane before adding the Agility tab.

- **The `RooftopCourseId` enum extension in Task 3** is the only edit to runtime agility code in this entire plan. Keep it that way.

## Out of scope (do not implement)

The following are explicitly v1.5+ and must not be added in v1 even if tempting:

- Marks of grace capture (`ItemSpawned`, `ItemDespawned`, inventory deltas, `reachableMarkTiles` in JSON).
- Auto-detection of mid-course captures (plane-0 sanity check).
- Current-stage green outline on the overlay.
- Non-rooftop agility content.
- Editing JSON in-panel.
