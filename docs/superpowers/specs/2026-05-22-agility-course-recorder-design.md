# Agility Course Recorder — v1 Design

**Status:** LOCKED 2026-05-22. Implementation-ready.
**Scope:** A dedicated in-game capture mode that watches the user run a rooftop
agility course manually and emits a fully-populated `<course>.json` consumable
by the existing `RooftopCourseLoader`. Target: capture a new rooftop course
in 5–10 minutes of normal play with zero hand-pasting of tile lists.

This spec is the companion to the existing [`2026-05-21-rooftop-agility-design.md`](2026-05-21-rooftop-agility-design.md)
(the runtime script that *consumes* the JSON). The runtime is unchanged; this
spec defines the *authoring tool* that produces its data.

## 1. Goal

Eliminate the manual labor that produced Draynor's `RooftopCourse` data in
`RooftopAgilityScript.java`: hand-marking tile groups for stage tiles, object
tiles, start, lap-end, and approach; reading object IDs out of the
ClickInspector log; cross-referencing TransportObserver entries for plane
information; pasting it all into Java initializer blocks.

After this tool ships, the workflow to add a new rooftop is:

1. Open RecorderPanel → Agility tab.
2. Pick the course from the dropdown (e.g. `AL_KHARID`).
3. Stand at the actual course start tile.
4. Press **Start**.
5. Run 2 clean full laps (the HUD shows live coverage).
6. Press **Save**.
7. JSON written to `~/.runelite/recorder/rooftops/al_kharid.json`.
8. Restart the client — the RooftopCourseLoader picks it up automatically.

## 2. Non-goals (v1)

- **Non-rooftop agility content**: Gnome Stronghold, Werewolf, Pyramid,
  Brimhaven, Hallowed Sepulchre. Those need a different data model.
- **Mid-course start support for capture sessions**: the user must stand at
  the actual course start before pressing Start in the recorder. Auto-
  detecting a mid-course start during *capture* (which can produce a
  rotated canonical obstacle sequence) is explicitly deferred — v1 trusts
  user positioning. **This restriction applies only to the recorder.**
  The runtime `RooftopAgilityScript` may still start mid-course if the
  player is already on a known stage tile; it infers the current stage
  from `stageByTile` and continues. If the player is outside
  `validTiles`, the script's existing off-route recovery walks them back
  to start (unchanged from the runtime's existing behavior).
- **Marks of grace capture**: deferred to v1.5. The JSON written by v1 has
  no `reachableMarkTiles` entries (or empty arrays); the runtime falls back
  to the no-marks code path until v1.5 lands.
- **Auto-detection of which course the player is on**: the user selects from
  a dropdown.
- **Capturing the travel-to-course trail**: use the existing route annotator.
- **Editing a previously-captured JSON in-panel**: edit the file directly.
- **Inter-lap stats** (XP/h, laps/h, time-per-obstacle).
- **Unit tests**: per project memory (`feedback_no_tests_for_bot_scripts`),
  gameplay-adjacent tools are validated by in-game runs. Manual acceptance
  criteria are listed in §17.

## 3. File layout

A new subpackage under the existing recorder plugin:

```
runelite-client/src/main/java/net/runelite/client/plugins/recorder/agility/
  AgilityCaptureSession.java      ~250 LOC   state + event handlers + accumulator
  AgilityCaptureOverlay.java      ~120 LOC   in-canvas Swing Overlay (tile colouring)
  AgilityCaptureTab.java          ~150 LOC   side-panel UI section
  ObstacleObservation.java        ~40 LOC    per-obstacle accumulator (data class)
  CourseJsonWriter.java           ~80 LOC    in-memory model → JSON file (atomic)
```

~640 LOC across 5 files. Wired into existing `RecorderPlugin` and
`RecorderPanel` as a new "Agility" tab. **Zero changes to runtime
agility behavior or the JSON loader schema.** Small integration edits
to `RecorderPlugin` and `RecorderPanel` are expected (new subscriber
registrations + a new tab section).

## 4. Threading

Per `CLAUDE.md` §"Threading model":

| Operation | Thread |
|-----------|--------|
| `@Subscribe` event handlers | client thread; read tile/varbits/inventory inline |
| Accumulator mutation | client thread; `synchronized(model)` only at copy-out for overlay reads |
| Overlay paint | EDT; takes a defensive snapshot under the lock; never iterates inside `paintComponent` |
| Panel button handlers | EDT; spawn `Thread.start()` for the JSON write so EDT doesn't touch disk |

The tool dispatches no clicks (it is a pure observer) — no
`HumanizedInputDispatcher`, no worker threads beyond the one disposable
thread that does the JSON write on Save.

## 5. Data model

```
CaptureModel
  targetId             RooftopCourseId       chosen in dropdown before Start
  label                String                pre-filled from defaults, editable
  agilityLevelReq      int                   pre-filled from defaults, editable
  expectedObstacleCount int                  pre-filled from defaults, editable

  obstacles            List<ObstacleObservation>   grows monotonically; order = first-success order
  canonicalSequence    List<ObstacleSignature>     signature-per-stage from first clean full lap
  cleanMatchingLaps    int                   includes the canonical lap itself

  approachTiles        Set<WorldPoint>       last 10s of player tiles before first-ever click
  startTiles           Set<WorldPoint>       player tile at moment of first-obstacle click
  lapEndTile           WorldPoint            player tile at final-obstacle SUCCESS

  validTiles           Set<WorldPoint>       COMMITTED only — see §9

  currentLapTiles      Set<WorldPoint>       in-flight lap buffer; not yet committed
  currentLapObs        List<PerLapObservation>  in-flight per-obstacle data; not yet merged

  pendingClick         PendingClick          single-slot; null when no click is awaiting outcome
  currentLapDirty      boolean               set when this lap can no longer count toward matching
  state                LapState              ARMED / IN_LAP / OFF_COURSE / LAP_COMPLETE
  capturedAtMs         long
```

```
ObstacleObservation
  orderIndex           int                   assigned on first SUCCESS
  objectIds            Set<Integer>          evidence; usually 1 entry, but engine swaps can produce more
  verbs                Set<String>           usually 1 entry ("Climb", "Cross", …)
  objectLabels         Set<String>           from ObjectComposition (debug-quality)
  stageTiles           Set<WorldPoint>       player tile at click time (across all matching laps)
  objectTiles          Set<WorldPoint>       click-target tile
  successTiles         Set<WorldPoint>       player tile at follow-up XP grant
  maxClickToXpMs       long                  used to compute timeoutMs
  successCount         int                   number of clean matching laps this obstacle has been seen in
```

`PerLapObservation` is structurally identical to `ObstacleObservation` (same
fields, same semantics) — the rename is purely to signal that the values
inside have not yet been merged into the saved model. In Java, the same
class is used; the difference is which collection holds it.

```
ObstacleSignature
  objectId             int                   stable per-stage engine id
  objectTile           WorldPoint            obstacle's world tile (fixed)
  verb                 String                action verb on the menu entry

  // Equality: all three fields match. Used to detect mismatched/rotated laps
  // before merging them into the saved model. Computed at the moment of
  // SUCCESS — see §7.1.
```

```
PendingClick
  objectId             int
  verb                 String
  objectTile           WorldPoint
  sourceTile           WorldPoint
  clickAtMs            long
  deadlineMs           long
  xpBefore             long                  total Agility XP at click time
  outcome              ClickOutcome          PENDING / SUCCESS / IGNORED / BROKEN_LAP / UNKNOWN
```

**Why `List<ObstacleObservation>` not `Map<int objectId, _>`.** Stage order is
the structure; object IDs are evidence collected per stage. A future course
might have two obstacles with the same engine ID after a model swap, or
different IDs across capture sessions for the same obstacle. Keying by
order index avoids accidentally merging them.

**Why `currentLapObs` is separate from `obstacles`.** Per-obstacle
observations are accumulated PER LAP and only merged into the main model
when the lap completes cleanly AND its sequence establishes or matches
canonicalSequence (see §9). This prevents a broken/mismatched lap's
data from polluting the saved JSON.

## 6. Event subscribers (all on client thread)

```
@Subscribe void onMenuOptionClicked(MenuOptionClicked e)
@Subscribe void onStatChanged(StatChanged e)        // filter Skill.AGILITY
@Subscribe void onGameTick(GameTick e)
```

Three subscribers. `ItemSpawned` / `ItemDespawned` /
`ItemContainerChanged` are NOT added in v1 (marks → v1.5).

### 6.1 `onMenuOptionClicked`

Filter for game-object actions:
`GAME_OBJECT_FIRST_OPTION..GAME_OBJECT_FIFTH_OPTION`. `EXAMINE_GAME_OBJECT`
and everything else are ignored.

```
if pendingClick != null:
    // Two clicks queued — capture quality compromised.
    currentLapDirty = true
    currentLapTiles.clear()
    currentLapObs.clear()
    pendingClick = null
    state = OFF_COURSE
    hud.warn("Two clicks queued — lap discarded. Walk to start and try again.")
    return

pendingClick = new PendingClick(
    objectId    = entry.getIdentifier(),
    verb        = entry.getOption(),
    objectTile  = entry.getTarget()->worldTile,
    sourceTile  = client.getLocalPlayer().getWorldLocation(),
    clickAtMs   = now,
    deadlineMs  = now + perObjectDeadline(objectId),   // see §7.3
    xpBefore    = client.getSkillExperience(AGILITY),
    outcome     = PENDING)
```

### 6.2 `onStatChanged`

Filter `e.getSkill() == AGILITY`. If `pendingClick != null` and
`e.getXp() > pendingClick.xpBefore`, the click resolves as SUCCESS — see
§7.1.

### 6.3 `onGameTick`

Drives:
- Player-tile sampling (§9 — accumulates into `currentLapTiles` while
  `state == IN_LAP`).
- PENDING click deadline checks (§7).
- Approach-tile ring buffer (last 10s of player tiles before any click).

## 7. Per-click outcome classifier

### 7.1 SUCCESS

`onStatChanged(AGILITY)` fires AND `now <= pendingClick.deadlineMs` AND
`pendingClick.outcome == PENDING`:

```
successTile = client.getLocalPlayer().getWorldLocation()

// Each successful click within a lap creates a fresh PerLapObservation
// plus the matching ObstacleSignature used for sequence comparison.
// Within a single rooftop lap each obstacle is clicked exactly once, so
// we always append; merging across laps happens in §9.3.
obs = new PerLapObservation()
obs.orderIndex = currentLapObs.size()         // 0-indexed position in this lap
obs.objectIds.add(pendingClick.objectId)
obs.verbs.add(pendingClick.verb)
obs.objectLabels.add(objectComposition(pendingClick.objectId).getName())
obs.stageTiles.add(pendingClick.sourceTile)
obs.objectTiles.add(pendingClick.objectTile)
obs.successTiles.add(successTile)
obs.maxClickToXpMs = now - pendingClick.clickAtMs
obs.successCount   = 1
obs.signature      = new ObstacleSignature(
                         pendingClick.objectId,
                         pendingClick.objectTile,
                         pendingClick.verb)
currentLapObs.append(obs)

// Capture sourceTile before nulling pendingClick — used in state transition below.
sourceTile = pendingClick.sourceTile

// Ensure the stage tile and success tile are in this lap's tile buffer,
// even if state was ARMED (no tick-sampling) at the moment of the click.
// Required for the containsAll(allStageTiles) save-gate (§14 #7).
currentLapTiles.add(sourceTile)
currentLapTiles.add(successTile)

pendingClick.outcome = SUCCESS
pendingClick = null

// State transition: first SUCCESS of a lap moves ARMED → IN_LAP.
if state == ARMED:
    state = IN_LAP
    if startTiles.isEmpty():                  // first SUCCESS ever this session
        startTiles.add(sourceTile)
        approachTiles = approachRingBuffer.tiles()

// Lap completion check (§8):
if currentLapObs.size() == expectedObstacleCount:
    transitionToLapComplete()
```

### 7.2 IGNORED, BROKEN_LAP, UNKNOWN (deadline expiry)

Checked each tick when `pendingClick != null` and `now > pendingClick.deadlineMs`:

```
playerTile = client.getLocalPlayer().getWorldLocation()

if playerTile == pendingClick.sourceTile:
    outcome = IGNORED            // engine dropped the click; harmless
    pendingClick = null
    return

if playerTile NOT IN (knownStageTiles ∪ startTiles ∪ approachTiles):
    outcome = BROKEN_LAP         // off-route — fall, misclick, walked off
else:
    outcome = UNKNOWN            // movement happened but couldn't classify

// Both BROKEN_LAP and UNKNOWN dirty the lap:
currentLapDirty = true
currentLapTiles.clear()
currentLapObs.clear()
pendingClick = null
state = OFF_COURSE
hud.warn("Lap broken. Walk to course start and click first obstacle again.")
```

`knownStageTiles` is `model.obstacles[*].stageTiles ∪ currentLapObs[*].stageTiles`
— anything we've seen across this session and within the current lap.

### 7.3 Deadline computation

```
perObjectDeadline(objectId):
    if obs = model.obstacles.find(o -> o.objectIds.contains(objectId)):
        return clamp(round(obs.maxClickToXpMs * 1.5), 8_000, 12_000)
    return 12_000
```

First-ever click of an obstacle: 12s. After observation, deadline shrinks
based on observed latency, floored at 8s, ceiling at 12s.

## 8. Lap state machine

```
State transitions:

ARMED
  └─ first SUCCESS of the lap → IN_LAP
      └─ each subsequent SUCCESS → IN_LAP (accumulate)
      └─ BROKEN_LAP / UNKNOWN / Discard-Lap button → OFF_COURSE
      └─ SUCCESS that reaches expectedObstacleCount → LAP_COMPLETE

OFF_COURSE
  └─ canonicalSequence is null (no clean lap yet):
        next agility click becomes ARMED → IN_LAP (soft reset)
  └─ canonicalSequence is set:
        player tile lands in startTiles ∪ approachTiles AND idle pose ≥ 2 ticks → ARMED
  └─ Discard-Lap or Cancel → ARMED

LAP_COMPLETE
  └─ atomic merge (§9.2) → ARMED
```

**Why strict re-entry once canonical exists.** Allowing re-entry from
any `knownStageTiles` would conflict with the "no mid-course start" v1
rule — a broken lap recovered mid-course would produce a rotated
sequence. Requiring `startTiles ∪ approachTiles` enforces the same
discipline as a fresh capture: the next lap restarts from the actual
course start. Aligned with §16's mid-course-start handling.

**First-lap-broken case** (`canonicalSequence == null` AND
`state == OFF_COURSE`): there are no canonical stage tiles to re-enter
through, and `startTiles` may also be empty if the BROKEN_LAP happened
on the first click. HUD displays: *"Lap broken. Walk to course start
and click first obstacle again."* Re-entry detection: the next agility
click becomes the new ARMED → IN_LAP transition (the soft reset).

**Two-click race**: handled in §6.1 — second click dirties the lap.

## 9. Tile commit rule (the heart of capture quality)

The invariant: **nothing from `currentLapObs` or `currentLapTiles` is merged
into the main model until a lap completes AND that lap either establishes
canonicalSequence (first clean lap) or matches it.** Mismatched complete
laps are discarded entirely.

### 9.1 During capture

```
on GameTick while state == IN_LAP:
    currentLapTiles.add(client.getLocalPlayer().getWorldLocation())

on GameTick while state == ARMED AND obstacles.isEmpty():
    // Approach tile ring buffer (last 10s before any click ever).
    approachRingBuffer.add(now, playerTile)
    approachRingBuffer.evictOlderThan(now - 10_000)

on first-ever first SUCCESS of the session:
    approachTiles = approachRingBuffer.tiles()
    startTiles.add(pendingClick.sourceTile)
```

### 9.2 On LAP_COMPLETE

```
// Build the signature list for THIS lap. Each entry is the
// ObstacleSignature stored on the PerLapObservation at §7.1.
sequence = currentLapObs.map(obs -> obs.signature)

if canonicalSequence == null:
    // Establishing case — this lap is the canonical reference.
    canonicalSequence = sequence
    mergeObsIntoModel(currentLapObs)              // obstacle data → model.obstacles
    model.validTiles.addAll(currentLapTiles)
    cleanMatchingLaps = 1
    lapEndTile = currentLapObs.last().successTiles.last()
    hud.info("Canonical sequence established (" + sequence.size() + " obstacles).")

else if signaturesMatch(sequence, canonicalSequence):
    // Confirming case — merge.
    mergeObsIntoModel(currentLapObs)
    model.validTiles.addAll(currentLapTiles)
    cleanMatchingLaps++
    hud.info("Matching clean lap: " + cleanMatchingLaps)

else:
    // Anomaly — discard everything from this lap, including its tiles.
    hud.warn("Lap sequence mismatch — discarded. " +
              "First differing index: " + firstDiffIndex(sequence, canonicalSequence))

currentLapTiles.clear()
currentLapObs.clear()
state = ARMED
```

```
signaturesMatch(a, b):
    if a.size() != b.size(): return false
    for i in 0..a.size():
        if a[i].objectId   != b[i].objectId:   return false
        if a[i].objectTile != b[i].objectTile: return false
        if a[i].verb       != b[i].verb:       return false
    return true
```

The comparison is strict: every stage's `(objectId, objectTile, verb)`
triple must match. This catches both **rotated** laps (mid-course start
producing `[B, C, D, A]` vs canonical `[A, B, C, D]`) and **disordered**
laps (manual click-out-of-order producing `[A, C, B, D]`). With the
previous `[0, 1, 2, 3]`-index comparison, neither would be caught.

### 9.3 `mergeObsIntoModel`

For each PerLapObservation `o` in `currentLapObs`:

- `existing = model.obstacles.findByOrderIndex(o.orderIndex)` (or create if first).
- `existing.objectIds.addAll(o.objectIds)`, same for `verbs`, `objectLabels`.
- `existing.stageTiles.addAll(o.stageTiles)`, same for `objectTiles`, `successTiles`.
- `existing.maxClickToXpMs = max(existing.maxClickToXpMs, o.maxClickToXpMs)`.
- `existing.successCount++`.

## 10. Course defaults

Hardcoded `RooftopCourseDefaults` map. Used to pre-fill panel fields when the
user picks a course from the dropdown; every field is editable before Start
(locked from Start onward).

| Course ID | Label | Level | Obstacles |
|---|---|---|---|
| DRAYNOR | Draynor Village Rooftop | 1 | 7 |
| AL_KHARID | Al Kharid Rooftop | 20 | 8 |
| VARROCK | Varrock Rooftop | 30 | 9 |
| CANIFIS | Canifis Rooftop | 40 | 8 |
| FALADOR | Falador Rooftop | 50 | 13 |
| SEERS | Seers' Village Rooftop | 60 | 6 |
| POLLNIVNEACH | Pollnivneach Rooftop | 70 | 9 |
| RELLEKKA | Rellekka Rooftop | 80 | 7 |
| ARDOUGNE | Ardougne Rooftop | 90 | 7 |

Verified against the OSRS Wiki summary table at
`https://oldschool.runescape.wiki/w/Rooftop_Agility_Courses` on
2026-05-22. The user can still override in-panel before pressing Start
if the wiki entry changes. Falador is the notable outlier at 13
obstacles per lap — confirmed on the wiki, not a typo.

## 11. HUD overlay (in-canvas Swing Overlay)

Lightweight per-frame overlay drawing tile poly outlines via
`Perspective.getCanvasTilePoly`. Four tile types, four colours; toggleable
via panel checkbox (default on during IN_LAP):

| Tile type | Colour | Filled? |
|---|---|---|
| Committed `validTiles` | translucent blue | yes |
| `currentLapTiles` (in-flight) | translucent yellow | yes |
| `obstacles[*].objectTiles` | red outline | no |
| `lapEndTile` | gold outline | no |

Marks-of-grace highlighting and current-stage green outline are deferred
to v1.5. The side panel's text table covers per-obstacle progress.

## 12. Side panel UI

```
┌─ Agility Capture ────────────────────────────────────────┐
│ Course:           [ Al Kharid Rooftop ▾ ]                │
│ Label:            [ Al Kharid Rooftop ........... ]      │
│ Expected count:   [ 8 ]    Agility level req: [ 20 ]     │
│                                                          │
│ [ Start ]  [ Discard Lap ]  [ Save ]  [ Cancel ]         │
│                                                          │
│ State:   IN_LAP      Lap progress: 4/8                   │
│ Matching laps: 0/2                                       │
│ Save status: need 2 matching clean laps                  │
│                                                          │
│ obstacle 0  Climb-up   11633   stage:✓(3) obj:✓ succ:✓   │
│ obstacle 1  Cross      11634   stage:✓(2) obj:✓ succ:✓   │
│ obstacle 2  Jump       11635   stage:✓(1) obj:✓ succ:✓   │
│ obstacle 3  *          ?       stage:- obj:- succ:-      │ ← PENDING
│ obstacle 4..7  (not yet observed)                        │
│                                                          │
│ Approach tiles:  ✓ (5)                                   │
│ Start tiles:     ✓ (1)                                   │
│ Lap-end tile:    not yet                                 │
│ Valid tiles:     0 committed (27 in current lap)         │
└──────────────────────────────────────────────────────────┘
```

**Field locking.** From Start until Save / Cancel, the Course / Label /
Expected count / Agility level fields are read-only. Discard Lap and the
overlay toggle remain interactive.

**Start prompt.** On Start, an inline notice displays for 5 seconds:
*"Stand at the course start. First successful obstacle click defines
obstacle 0."* This is the v1 mid-course-start mitigation.

**Save status line.** Shows the first failing checklist blocker (§14) so
the user knows exactly what to do next, e.g. `"Save: need 1 more matching
clean lap"`, `"Save: obstacle 5 missing successTiles"`.

## 13. Save preview + JSON output

### 13.1 Save flow

1. User presses `[Save]`.
2. Checklist (§14) runs; if any blocker fails, panel updates `Save status`
   and the click is a no-op.
3. If passes: Save Preview modal opens with summary (§13.2).
4. User confirms in the modal.
5. EDT spawns a worker `Thread.start()`:
   a. `CourseJsonWriter` serialises model → JSON.
   b. Atomic write: temp file in target dir, then `Files.move(..., REPLACE_EXISTING)`.
   c. If pre-existing file detected before write, modal swaps `[Save]` for
      `[Overwrite]` and offers `[Save as .new.json]` to side-by-side.
   d. After write, immediately attempt `RooftopCourseLoader.loadAll()` in a
      sandbox and run `validateCourse` against the freshly-loaded course.
   e. On validation pass: panel status "Saved ✓".
   f. On validation fail: panel status shows the `IllegalStateException`
      message; modal offers `[Discard saved file]` or `[Keep + investigate]`.

### 13.2 Save Preview modal

```
─ Ready to save: Al Kharid Rooftop (AL_KHARID) ──

  8 obstacles, all observed in 2 matching clean laps
  42 validTiles
  1 startTile, 5 approachTiles
  1 lapEndTile at (3304, 3162, plane 3)
  timeoutMs range: 4500–7500 ms

  0 warnings

  Target: ~/.runelite/recorder/rooftops/al_kharid.json

  [ Save ]   [ Cancel ]
```

If any per-obstacle warning exists (e.g. `objectIds.size() > 1`,
non-whitelisted verb observed), a `[ ⚠ N warnings — show ]` link expands
the modal to list them.

### 13.3 JSON output schema

Matches the existing `RooftopCourseLoader` schema exactly. No schema
extension in v1.

```json
{
  "id": "AL_KHARID",
  "label": "Al-Kharid Rooftop",
  "agilityLevel": 20,
  "scanRadius": 14,
  "approachTiles": [[3273, 3195, 0]],
  "startTiles":    [[3273, 3195, 0]],
  "fallTiles":     [],
  "lapEndTiles":   [[3304, 3162, 3]],
  "validTiles":    [[3273, 3195, 0], [3272, 3195, 3]],
  "obstacles": [
    {
      "label": "Rough wall",
      "objectId": 11633,
      "action": "Climb-up",
      "objectTiles":  [[3272, 3195, 0]],
      "stageTiles":   [[3272, 3195, 0], [3273, 3195, 0]],
      "successTiles": [[3271, 3195, 3]],
      "timeoutMs": 4500
    }
  ]
}
```

**Per-obstacle write rules:**

| JSON field | From model |
|---|---|
| `objectId` | most-frequent entry in `objectIds`; warns if size > 1 |
| `action` | most-frequent entry in `verbs` |
| `label` | most-frequent entry in `objectLabels` (debug-quality) |
| `objectTiles` | full set |
| `stageTiles` | full set |
| `successTiles` | full set; emitted as `[]` for the final obstacle (lapEndTile covers landing) |
| `timeoutMs` | `clamp(round(maxClickToXpMs * 1.5), 4000, 12000)` |

**`fallTiles`** is always `[]` in v1: the runtime's off-route branch
(`!validTiles.contains(player) → recover`) handles every fall, so no
fall-tile capture is needed.

**`reachableMarkTiles`** is omitted entirely in v1. v1.5 will add it
after confirming `RooftopCourseLoader` ignores unknown JSON fields
cleanly (one grep — listed in §19).

## 14. Save-quality checklist

`[Save]` button stays disabled until ALL of these pass. The panel's `Save
status` line shows the first failing blocker by name.

1. `cleanMatchingLaps >= 2`.
2. `obstacles.size() == expectedObstacleCount`.
3. `lapEndTile != null`.
4. `startTiles.size() >= 1`.
5. `approachTiles.size() >= 1`.
6. For every obstacle:
   - `objectIds.size() >= 1`,
   - `verbs.size() >= 1`,
   - `stageTiles.size() >= 1`,
   - `objectTiles.size() >= 1`,
   - `successTiles.size() >= 1` (except final obstacle — exempt),
   - `successCount >= 2`.
7. `model.validTiles.containsAll(unionOf(obstacles[*].stageTiles))` —
   precise containment, not size comparison.
8. `pendingClick == null` — no click awaiting outcome.

If all pass, the button enables and clicking it opens the Save Preview
modal (§13.2).

## 15. Post-save validation round-trip

After the file is written, the writer immediately:

1. Calls `RooftopCourseLoader.loadAll()` (which scans the directory and
   parses every file).
2. Locates the freshly-written course in the returned map.
3. Re-runs `RooftopAgilityScript.validateCourse` against the loaded
   `RooftopCourse`.

This is cheap (millisecond) and catches any case where our internal
checklist passed but a downstream invariant would fail. Failure surfaces
in the panel as the exception message; the user is offered
`[Discard saved file]` to delete it cleanly.

## 16. Edge cases

| Scenario | Handling |
|---|---|
| User starts a **capture session** mid-course | Not supported in v1. The 5-second Start prompt instructs the user to stand at the course start. If ignored, the first SUCCESS defines obstacle 0 — a rotated canonical sequence is the user's responsibility to catch. Future enhancement: plane-0 sanity check (see §19). Note: this constraint applies to **recording new JSON**, not to running an already-captured course — see §2. |
| Two pending clicks (user spammed) | §6.1: lap dirtied, OFF_COURSE, HUD warns. |
| Player attacked mid-capture (NPC) | No XP fires → BROKEN_LAP via deadline. Lap discarded. |
| User alt-tabs mid-lap | No movement, no XP, deadline expires → UNKNOWN → dirty lap. |
| Wrong course in dropdown | Lap can never reach `expectedObstacleCount` (off by 1+). HUD shows progress indefinitely (e.g. `"5/8 obstacles"`). User notices, hits Cancel. |
| Lag spike — XP delayed beyond 12s | UNKNOWN classification → dirty lap. User retries. Per-obstacle deadline grows on subsequent observations, capped at 12s. |
| Engine swaps objectId mid-session | `objectIds` set on the observation grows. JSON writes the most-frequent. HUD warns if `objectIds.size() > 1`. |
| Player dies during capture | Tile goes off-route → BROKEN_LAP. Walk back to start to retry. |
| Player levels up Agility mid-lap | `StatChanged` carries the XP delta; the level-up doesn't affect our delta check. |
| Captured JSON already exists on disk | Save Preview modal swaps `[Save]` for `[Overwrite]` + offers `[Save as .new.json]` for side-by-side comparison. |
| Captured JSON fails round-trip validation | Panel surfaces the exception; user can `[Discard saved file]`. |
| User hits Discard Lap | `currentLapTiles` and `currentLapObs` cleared. Already-committed data (previous matching laps' validTiles + obstacles) is preserved. |
| User hits Cancel with `cleanMatchingLaps > 0` | Confirmation modal: *"Discard captured data? %d matching lap(s) and %d valid tiles will be lost."*  |
| User restarts client mid-capture | In-memory model is lost. Tool ships with no persistence between sessions. |

## 17. Manual acceptance plan

Exercised on a low-level alt by the user, in order. Each item has a
single-line pass criterion. Failure halts the plan; we fix and rerun.

1. **Draynor re-capture (ground truth)** — 2 clean laps from the start tile,
   Save. JSON reloads via `RooftopCourseLoader` cleanly. Diff against the
   hand-authored Draynor's structural fields: obstacle count = 7,
   objectIds match `[11404, 11405, 11406, 11430, 11630, 11631, 11632]`,
   verbs match `["Climb", "Cross", "Cross", "Balance", "Jump-up", "Jump", "Climb-down"]`.
2. **First-lap-broken recovery** — Press Start at the start tile, fail the
   first tightrope. Verify lap dirties, HUD shows the recovery prompt,
   walking back to start and re-clicking obstacle 0 restarts the lap.
3. **Mid-course start (negative test)** — Press Start standing on Tightrope-2's
   stageTile. Click obstacles in their normal order. Observe that the lap
   will eventually reach 7 successes in a rotated sequence — note the bad
   sequence in the HUD output. (Acceptance: behavior matches §16's "not
   supported" line, no auto-correction expected in v1.)
4. **Forced fall mid-lap-2** — On lap 2, fail tightrope-1. Verify
   `currentLapTiles` discarded, lap counter unchanged, recovery prompt
   shown. Lap 3 from the start tile succeeds → matching lap counter
   increments to 2.
5. **Spam-click two obstacles** — Press first agility click; before
   resolution, click a second obstacle. Verify dirty-lap path fires with
   correct HUD message.
6. **Mismatched sequence anomaly** — Manually click obstacles out of order
   (e.g. obstacle 3 before obstacle 2). Verify the lap completes with 7
   successes but `cleanMatchingLaps` does not increment. HUD warns of
   sequence mismatch.
7. **Discard Lap mid-flight** — Mid-lap, hit Discard Lap. Verify provisional
   data cleared, state → OFF_COURSE, already-committed data unaffected.
8. **Cancel with 1 clean lap captured** — Confirmation modal, in-memory
   cleared, no file written.
9. **Overwrite modal** — Capture Draynor cleanly, save. Capture again, save
   → overwrite modal appears, both options work.
10. **Round-trip validation** — After Save, verify the freshly-written file
    parses and passes `validateCourse`.
11. **Al Kharid first-time capture (the real goal)** — Travel to Al Kharid,
    capture cleanly. Save → `al_kharid.json` written. Restart client,
    select Al Kharid in `RecorderPanel`'s rooftop-agility dropdown, run
    the bot end-to-end successfully.

## 18. Out of scope (named explicitly to prevent scope creep)

- Marks of grace capture (v1.5; adds `ItemSpawned`/`ItemDespawned`/inventory-delta subscribers, JSON `reachableMarkTiles` write, and loader-support verification).
- Non-rooftop agility content (Gnome Stronghold, Pyramid, Brimhaven, Sepulchre).
- Auto-detection of mid-course starts (deferred — see §19).
- Auto-detection of which course the player is on.
- Capturing the travel-to-course trail.
- In-panel editing of previously-captured JSON.
- Inter-lap statistics (XP/h, laps/h, per-obstacle timing histograms).
- Persistence of in-progress captures across client restarts.
- Multi-character session merging.
- Capturing across plane/region transitions (only single-region rooftops in v1).

## 19. Open items to verify before implementation

1. **Loader unknown-field tolerance.** Verify `RooftopCourseLoader` ignores
   unknown JSON fields cleanly (so v1.5's `reachableMarkTiles` write
   doesn't break v1 loads). One grep against the loader's parse code.
2. **Mid-course start sanity check (v1.5).** A plane-0 check on the first
   SUCCESS would catch the most common mid-course start error (all 9
   rooftop courses begin at plane 0). Out of v1 scope but worth flagging.
3. **Penguin / Hefin / Werewolf rooftops.** Confirm structural shape before
   adding their `RooftopCourseId` enum entries (separate spec item).
4. **`MenuOptionClicked.getTarget()`** API shape — verify the world tile
   of a clicked GameObject is reachable from the event without extra
   lookups. (Pure API confirmation; not blocking.)

## 20. Summary of locked decisions

- **Scope**: rooftop agility only, 9 standard courses.
- **Capture mode**: dedicated standalone subpackage; live in-game HUD; not coupled to the behavior Recorder.
- **Code site**: `runelite-client/.../plugins/recorder/agility/`, ~640 LOC across 5 files.
- **Detection**: any GameObject click + Agility XP within 8–12s deadline.
- **Outcome classifier**: `PENDING → SUCCESS / IGNORED / BROKEN_LAP / UNKNOWN`.
- **Lap state machine**: `ARMED → IN_LAP → (LAP_COMPLETE | OFF_COURSE)`, with first-lap-broken soft reset.
- **Tile commit rule**: per-lap buffer, committed only on a clean lap that establishes or matches `canonicalSequence`.
- **Save gate**: 2 matching clean full laps + per-obstacle invariants + `validTiles.containsAll(allStageTiles)` + post-save `validateCourse` round-trip.
- **`fallTiles`**: empty in JSON. Runtime off-route branch handles falls.
- **Marks**: deferred to v1.5.
- **Mid-course start**: not supported in v1 *for capture sessions* — HUD prompt instructs the user to stand at the course start. Runtime `RooftopAgilityScript` is unaffected and may still start mid-course on a known stage tile.
