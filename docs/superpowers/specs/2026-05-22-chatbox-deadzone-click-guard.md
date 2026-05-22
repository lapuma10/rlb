# Centralized click guard — never click through the chat UI (and friends)

**Goal.** Every press in `HumanizedInputDispatcher` goes through one
primitive that knows the click's intent (world / UI / raw). The
primitive owns dead-zone checks, last-mile validation, and timing
profiles. New dead-zone sources become one-line additions in
`UiDeadZones`; the dispatcher and resolver don't change.

The chatbox is the **first dead-zone consumer**, not the design
driver. The design driver is the missing chokepoint itself.

---

## Why the obvious fix is the wrong fix

First instinct: add `if (insideChatbox(cursor)) return;` at the top of
`clickPress(button)`. That's wrong because:

1. **`clickPress` is not the only press path.** ~10 hot paths (NPC
   attack phase 8, ground-item Take, GE menu-row click, inv-slot
   verbs in verified-action form) bypass it with direct
   `input.mousePress(BUTTON1); ... input.mouseRelease(BUTTON1);` to
   get a tighter timing window. A guard in `clickPress` silently
   misses every one of them.
2. **`clickPress` has no intent.** A guard there would block both
   "Attack chicken" (world click — refuse when in chatbox) and
   "Click chat tab" (UI click — must be allowed). The information
   needed to make that distinction lives in the caller.

Fix: introduce the primitive that does have intent, and funnel
everyone through it.

---

## The new primitive

```
boolean press(int button, ClickIntent intent, PressTiming timing)
```

Single chokepoint. Replaces both `clickPress(int)` and every direct
`input.mousePress/mouseRelease` pair.

**`ClickIntent` enum** — declares why this press exists:

| Value      | Meaning                                                                 | Guards run                          |
| ---------- | ----------------------------------------------------------------------- | ----------------------------------- |
| `WORLD`    | Targeting a world tile / NPC / object / ground item.                    | UI dead-zone (block); off-canvas    |
| `MINIMAP`  | Targeting the minimap disc (walk fallback).                             | Off-canvas only                     |
| `UI`       | Targeting a widget / bounds / inventory slot. May legitimately click inside chatbox/side panels/etc. | Off-canvas only |
| `MENU_ROW` | Clicking a right-click context-menu row we just opened.                 | Off-canvas only                     |
| `RAW`      | Test hooks, `clickCanvas(x, y)`, low-level escape hatches.              | None                                |

**`PressTiming` enum** — **three values**, one per profile present in
the source. The migration table maps every call site to exactly
one value; ms ranges are read from the source, not inferred.

| Value             | Pre-press dwell | Button-down hold | Post-release hold | Where it's used                                      |
| ----------------- | --------------- | ---------------- | ----------------- | ---------------------------------------------------- |
| `STANDARD`        | 180..500 ms     | 40..80 ms        | 100..350 ms       | Every `clickPress(BUTTON1)` / `clickPress(BUTTON3)` site (~33 today). |
| `FAST_COMMIT`     | 0 ms            | 40..80 ms        | 100..350 ms       | Post-verify commit press. 2 sites: npcClick phase 8 left-click, groundItemClick phase 5 Take. |
| `MENU_SELECTION`  | 0 ms            | 40..80 ms        | 80..260 ms        | Right-click menu-row selection. 8 sites (npc row, ground-item row, widget row, bounds row, etc.). |

**Audit correction (Phase 1 follow-up, 2026-05-22).** The earlier
four-profile proposal split phase 8 by attackVerb into `TIGHT_COMMIT`
(20..40 pre) and `LIGHT_COMMIT` (40..80 pre). On re-reading the
source, those values belong to phase 6's "settle before verify"
sleep at npcClick:949
(`SequenceSleep.sleep(client, attackVerb ? 20..40 : 40..80)`), which
is followed by two state reads (`isNpcClaimedByOtherPlayer`,
`isTopVerbOnNpc`) before the press. Folding phase 6 into the press
primitive would change ordering — the verify reads would happen
after the dwell instead of before. Phase 6 stays in `npcClick`; the
press primitive's pre-dwell at phase 8 is 0 ms regardless of
attackVerb. No `pickAttackTiming(verb)` helper needed — phase 6
already encodes the distinction in its own ternary.

**Return value.** `true` = press fired. `false` = guard blocked the
press; `lastError` is set with a structured reason. Callers act on
the return:

- `walkClick`: false on WORLD dead-zone hit → fall through to the
  existing minimap-fallback branch (same recovery shape it already
  uses when `isLeftClickWalk` reports a non-walk top verb).
- `npcClick` / `gameObjectClick` / `groundItemClick`: false →
  same "set lastError, return early" branch they already have for
  pre-flight failures (cross-plane, hull off-canvas, menu mismatch).
- Existing pre-checks (`isLeftClickWalk`, verb-at-top) still run
  before `press(...)`. The guard is the *last* gate, not the only
  one.

---

## Inside the primitive

```
boolean press(button, intent, timing):
    cx, cy = input.cursorX(), input.cursorY()

    if !onCanvas(cx, cy):
        lastError.set("press blocked: off-canvas (cx, cy)")
        return false

    if intent == WORLD:
        Rectangle hit = UiDeadZones.intersectsAt(client, cx, cy)
        if hit != null:
            lastError.set("press blocked: cursor in UI dead-zone " + hit)
            log.info("press blocked dead-zone {} at ({},{}) intent=WORLD",
                     hit, cx, cy)
            return false

    sleep(timing.preDwell())
    input.mousePress(button)
    sleep(timing.holdMs())
    input.mouseRelease(button)
    sleep(timing.postDwell())
    return true
```

That's the whole guard surface. New dead-zone source? Add to
`UiDeadZones`. New intent rule? Inside this method. New timing
profile? New enum value + one ms range. No call-site churn.

---

## UiDeadZones — v1 coverage

```
package net.runelite.client.sequence.dispatch;

class UiDeadZones {
    // Must be called on the client thread.
    static List<Rectangle> current(Client client);
    static Rectangle intersectsAt(Client client, int x, int y);
}
```

v1 returns the live `getBounds()` of these widgets when present and
not hidden (visibility check walks the ancestor chain). Each entry
covers all four UI layouts; `null` / hidden widgets are silently
skipped so the lookup is cheap and self-pruning regardless of which
layout is active.

| Source                | Widget id(s)                                                                 | Notes                                                                                              |
| --------------------- | ---------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------- |
| Chatbox               | `InterfaceID.Chatbox.UNIVERSE`                                               | Whole region the ChatDebugOverlay paints. Tabs and scrollbar matter, not just `CHATAREA`.          |
| Inventory / sidebar   | `Toplevel.SIDE_PANELS`, `ToplevelOsrsStretch.SIDE_PANELS`, `ToplevelPreEoc.SIDE_PANELS`, `ToplevelOsm.SIDE_PANELS` | Whichever sidebar tab is open (inventory / combat / skills / …) — single root per layout.          |
| Compass               | `Toplevel.COMPASSCLICK` (and the three resizable equivalents)                | Steals world clicks near the top-left orb cluster in resizable modern.                              |
| Minimap orb cluster   | `Orbs.ORB_HEALTH/PRAYER/RUNENERGY/SPECENERGY/STORE/WORLDMAP/WIKI` + the `OrbsNomap`, `OrbsOsm`, `OrbsOsmNomap` equivalents | Each orb has its own click bounds that overlap the minimap inscribed circle. ORB_WORLDMAP is the world-map-opener offender from the lap-end → Rough Wall regression. |
| Minimap itself (WORLD only) | `Orbs.MINIMAP` + `OrbsNomap/OsmNomap/OsrsStretch.MAP_MINIMAP` variants | WORLD-intent presses inside minimap bounds are blocked: minimap clicks are walks, and walks go through `MINIMAP` intent. This is what makes "minimap is walk-only" hold. |

Returns defensive `Rectangle` copies. Tolerates any widget not
being loaded (login screen, world hop, fixed layout where stretch
widgets are absent) by returning an empty list.

A `// TODO` comment lists later candidates (`MES_LAYER` numeric
prompt overlay, XP tracker overlay, custom RuneLite plugin
overlays). They do not go in v1. Add only when a real regression
demands it.

---

## PixelResolver — bypass fix + dead-zone reject (first-class concern)

**Latent bug discovered in audit.** Five sites today can return a
fallback pixel **without going through the rejection-sampling
loop**, defeating the actor-hull rejection that's already there and
defeating any new dead-zone reject too:

| Site                          | Line(s)        | Bypass shape                                                            |
| ----------------------------- | -------------- | ----------------------------------------------------------------------- |
| `resolveTilePixel`            | 230–231        | "bbox centre fallback" returns the centre without checking it           |
| `resolveGroundItemPixel`      | 293–294 (S1)   | ItemLayer clickbox `bb` centre fallback                                 |
| `resolveGroundItemPixel`      | 316     (S2)   | projected-tile-centre fallback                                          |
| `sampleInsidePolygon`         | 849            | "bare centroid" last-resort return                                      |
| `sampleNearCentroid`          | 887–888        | centroid-then-fallback chain                                            |

Today these silently return a pixel inside the chatbox even when
the rejection loop ran and filtered every other candidate. **This
is not a chatbox-spec invention — it's an existing latent bug in
actor-hull rejection too.** Ship the fix on this branch.

**Restructure.** Each fallback gets the same guard as the loop:
verify the candidate is on-canvas, doesn't conflict with recent
clicks, and **isn't inside any current dead-zone**. If the
fallback's only candidate fails the guards, the method returns
`null` and the caller's existing null-handling kicks in:

- `walkClick`: null pixel → already logs "not resolvable" and
  returns. Tile-poly path was a quiet drift; minimap fallback now
  fires correctly via the existing branch.
- `gameObjectClick` / `groundItemClick` / `npcClick`: null → log
  + lastError + return, exactly what the off-screen path already
  does.

**Dead-zone reject in the loops.** Standard one-line add per
sampler — `intersectsAny(deadZones, x, y)` joins the existing
`intersectsAny(actorHulls, x, y)` rejection at the same point in
each of:

```
tryCleanMainViewPixel    resolveTilePixel
resolveGroundItemPixel   resolveNpc
resolveGameObject        resolveWallObject
sampleInsideShape        (used by objectClickCandidates)
```

This is the efficiency layer the spec called "optional" before.
With the bypass fix in, the loops are the only candidate path —
the layer is no longer optional, it's the resolver's correctness
guarantee.

**Camera-rotate-when-occluded is OUT OF SCOPE** for this spec.
When `objectClickCandidates` returns empty because every
candidate is occluded, the dispatcher logs "no candidates
resolvable" and the script's existing retry next tick takes
over. Adding a dispatcher-side rotate-and-retry is a separate
follow-up because it introduces new dispatcher control flow and
new state (rotation budgets). The agility-script spam guard (see
follow-ups) is what stops a re-dispatch storm in the meantime.

---

## Files to touch

```
NEW   sequence/dispatch/UiDeadZones.java
NEW   sequence/dispatch/ClickIntent.java
NEW   sequence/dispatch/PressTiming.java
EDIT  sequence/dispatch/HumanizedInputDispatcher.java
        - introduce press(button, intent, timing) primitive
        - migrate every clickPress / mousePress call site (~30)
        - delete clickPress(int) and inlined press/release pairs
        - walkClick uses the boolean return to drive minimap fallback
EDIT  sequence/dispatch/PixelResolver.java
        - restructure 5 fallback sites to honour rejection guards
        - extend rejection-sampling loops with dead-zone reject
        - 7 resolve* / sample* methods touched; same pattern in each
NEW   sequence/dispatch/PixelResolverDeadZoneTest.java
        - narrow unit test (see Tests)
NEW   sequence/dispatch/UiDeadZonesTest.java
        - the layout-swap unit test (see Tests)
```

No `RecorderConfig` change. The guard is unconditional — there is
no toggle to disable it.

---

## Migration plan

Three phases, three commits, one PR.

### Phase 0 — audit table (committed as a scratch doc)

The audit has already been done by the three reviewer agents.
Capture the result as `docs/superpowers/scratch/2026-05-22-press-audit.md`
(or paste into the PR description) so Phase 1's enum values are
backed by a written record, not by memory:

| Site                          | Button | Pre ms     | Hold ms | Post ms    | Intent   | Timing label    |
| ----------------------------- | ------ | ---------- | ------- | ---------- | -------- | --------------- |
| `clickPress` body (33 callers)| B1/B3  | 180..500   | 40..80  | 100..350   | varies   | STANDARD        |
| npcClick:979 phase 8          | B1     | 0          | 40..80  | 100..350   | WORLD    | FAST_COMMIT     |
| groundItemClick:1192 phase 5  | B1     | 0          | 40..80  | 100..350   | WORLD    | FAST_COMMIT     |
| 8 menu-row sites (1024, 1226, 1775, 2058, 2126, 2561, 2639, 2923) | B1 | 0 | 40..80 | 80..260 | MENU_ROW | MENU_SELECTION |

Pre-press settle sleeps (`SequenceSleep.sleep(...)` immediately
before mousePress) at the menu-row sites and at phase 6 of
npcClick stay at the call site — they precede state-reading logic
that cannot move. The press primitive's pre-dwell ranges in the
table above are the *actual* delay between the last call-site
operation and the mousePress, which is 0 for every inlined site.

Phase 1's enum ms ranges must match this table exactly. Drift = a
behavior change none of us asked for.

### Phase 1 — primitive + resolver work, zero call-site migration

- Add `UiDeadZones`, `ClickIntent`, `PressTiming` (3 values).
- Add `press(int, ClickIntent, PressTiming) -> boolean` to
  `HumanizedInputDispatcher`. Built but **not yet called**.
- Restructure PixelResolver's 5 bypass sites + extend rejection
  loops with dead-zone reject. Callers whose pixels happen to be
  off the dead-zone see no change; callers whose pixels were inside
  the dead-zone or any other rejection rule now get `null` and
  fall through their existing null-handling. The "bug" they
  exposed is the bug we wanted to fix.
- Build green. **Zero dispatcher call-site migration.** Resolver
  behavior changes only for pixels that previously violated
  rejection rules — that's the intended fix, not a side effect.
- Land tests for `UiDeadZones` (layout swap) and
  `PixelResolverDeadZoneTest` (rejection behaviour). No tests
  on the primitive itself — manual acceptance covers it.

### Phase 2 — mechanical migration of all ~30 call sites, single commit

Reviewer-Agent 2 verified single-commit is safe: all four WORLD
methods share the same "set lastError, return early" shape; the
press returning false plugs into their existing branches with no
restructure. UI / MENU_ROW / MINIMAP / RAW intents have no
behavior change at all (their guards are off-canvas only, which
was already implicit).

Migration mapping:

```
WORLD intent
  walkClick           clickPress(BUTTON1)        → press(B1, WORLD,   STANDARD)
  npcClick     ph 8   input.mousePress/Release   → press(B1, WORLD,   FAST_COMMIT)
  groundItemClick ph5 input.mousePress/Release   → press(B1, WORLD,   FAST_COMMIT)
  gameObjectClick     clickPress(BUTTON1)        → press(B1, WORLD,   STANDARD)
  npcClick   ph 9     clickPress(BUTTON3)        → press(B3, WORLD,   STANDARD)
  gameObjectClick     right-click open           → press(B3, WORLD,   STANDARD)
  groundItemClick     right-click open           → press(B3, WORLD,   STANDARD)

MENU_ROW intent (8 sites: npc row, ground-item row, widget row,
                 bounds row, plus repeated invSlotClick variants)
  every menu-row site moveCursorTo+settle; press → press(B1, MENU_ROW, MENU_SELECTION)
                      (settle sleep stays at call site — see audit
                       correction above)

UI intent
  widgetClick         clickPress(B1)             → press(B1, UI, STANDARD)
  widgetVerbClick     clickPress(B1/3)           → press(B*, UI, STANDARD)
  boundsClick         clickPress(B1/3)           → press(B*, UI, STANDARD)
  invSlotClick        clickPress(B1/3)           → press(B*, UI, STANDARD)

MINIMAP intent
  walkClick fallback  clickPress(BUTTON1)        → press(B1, MINIMAP, STANDARD)

RAW intent
  clickCanvas(x, y)                              → press(B1, RAW, STANDARD)
  clickCanvas(xProp, yProp)                      → press(B1, RAW, STANDARD)
```

After this commit, `clickPress(int)` and every inlined
`input.mousePress / input.mouseRelease` pair are deleted. Final
grep gate: `input.mousePress` and `input.mouseRelease` appear in
exactly one method — `press(...)`. Any other survivor needs a
ticket before merge.

### Phase 2.5 — RooftopAgilityScript spam-click guard, same PR, separate commit

One-file change in `RooftopAgilityScript.onGameTick`. Skip
re-dispatching the same obstacle when:

- `lastClickedNode != null` AND
- `now - lastObstacleClickAt < node.timeoutMs` AND
- no progress signal since the click (no animation, no component
  change, no player-tile change).

Today these checks are scattered across `detectCurrentStage()` and
`handleObstacleTimeout()`, leaving a window where the script
re-dispatches every 600–1200 ms even though the prior click is
still in flight or just failed silently. With the dispatcher's
dead-zone guard active, a failed press costs ~0 ms (blocked at the
last gate) so the cadence becomes "fire as fast as the throttle
allows" — visibly the "stage 0 spam until it climbs" the user
reported.

Kept in the same PR because it closes the symptom that triggered
this work; kept in a **separate commit** so the dispatcher work
and the script guard revert independently. No tests
(per the no-tests-for-scripts memory); validated by the same
agility acceptance run in Phase 3.

### Phase 3 — manual acceptance (no code)

Single in-game session covering both halves of the change:

1. **Chicken farm with chatbox-occluded targets.** Resize so
   chatbox covers part of the pen. Logs to expect:
   - `resolver: candidate rejected by dead-zone …` (efficiency
     layer)
   - `press blocked dead-zone Rectangle[...] at (x,y) intent=WORLD`
     (last-gate guard, fires only if a fallback would have leaked
     before the bypass fix)
   - Script keeps progressing; combat continues on a different
     chicken / after the next camera nudge.
2. **Walk to a chatbox-occluded tile.** Recorder-panel walk-test.
   Resolver returns a minimap pixel directly; player walks fine.
3. **Lap-end → Rough Wall recovery walk (agility).** The
   regression that triggered this spec. Run Draynor agility, let
   the lap complete, watch the recovery walk: no world map opens.
   Logs show the minimap pixel landed clear of `ORB_WORLDMAP`
   bounds.
4. **Right-click flow into chatbox (force-misposition).** Script-
   side cursor parking on the chatbox + a queued right-click.
   `press blocked` on BUTTON3, no stuck menu (`Escape` fallback
   from the stuck-menu memory still works).
5. **Negative — chat tab click still works.** UI intent, guard
   bypassed.
6. **Negative — bank deposit with chatbox visible.** UI intent.
   No false-positive blocking.

If a script ends up "stuck rotating forever because every angle
has the target occluded" → script-side recovery problem, out of
scope here (see follow-ups).

### Reversibility

- Phase 1: pure additions. Safe to keep even if Phase 2 reverts.
- Phase 2: single commit. Revert = back to today's behavior with
  the primitive sitting unused. No half-state.
- Phase 3: no code, nothing to revert.

---

## Tests

**`UiDeadZonesTest`** — exercises layout-swap robustness:
```
- fixture: stub Client that toggles between fixed / resizable
  modern / resizable classic / OSM layouts via the relevant
  varbits
- assert UiDeadZones.current(client) returns the right widget
  bounds set per layout
- assert null / hidden widgets are skipped without throwing
```

**`PixelResolverDeadZoneTest`** — verifies rejection + bypass fix:
```
- fixture: stub Client whose Chatbox.UNIVERSE = lower-left
  quadrant of an 800x600 canvas
- resolveWalkTarget(target projecting into that quadrant) returns
  a pixel on the minimap (efficiency layer)
- resolveNpc(npc whose hull is fully inside that quadrant)
  returns null (bypass fix — old code would have returned the
  centroid)
- resolveNpc(npc straddling the boundary) returns a pixel that's
  inside the hull AND outside the quadrant
- resolveGroundItemPixel falls through all three strategies and
  returns null when every strategy lands inside the quadrant
```

No unit test on `press(...)` itself — manual acceptance covers
it, per the no-tests-for-scripts memory.

---

## Out of scope — follow-ups

These were on the table this session; they're intentionally
separate from this spec to keep its scope tight.

1. **Camera-rotate-when-occluded.** Dispatcher-side
   "rotate-and-retry once if all candidates are dead-zone-rejected."
   Needs a rotation budget, a re-resolve step after rotation, and a
   way to plumb the signal back from the resolver. Separate spec.
2. **Additional dead-zone sources.** MES_LAYER (numeric prompts),
   XP tracker, custom plugin overlays. Add via one-line
   contributions to `UiDeadZones` when a real regression
   demonstrates need.

---

## Decided

1. **Strict-walk + dead-zone**: **hard abort.** Strict-walk's
   contract is "use the chosen tile or fail" — falling back to
   minimap would silently violate it. Dead-zone hit produces the
   same `lastError + return` shape as a `isLeftClickWalk` mismatch.
2. **MENU_ROW + chatbox overlap**: **allowed.** A right-click menu
   we just opened is drawn on top of the chatbox by the engine,
   so the row click resolves to the menu, not the chatbox.
   MENU_ROW intent bypasses the dead-zone guard.
3. ~~**`pickAttackTiming` location**: next to `npcClick`.~~
   **Superseded** by the Phase 1 audit correction — the attackVerb
   timing distinction lives in phase 6's settle sleep, which is
   not a press-primitive concern. No helper is needed; phase 6's
   existing ternary stays as-is.
