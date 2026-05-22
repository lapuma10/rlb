# Centralized click guard — never click through the chat UI (and friends)

**Goal.** Every press in `HumanizedInputDispatcher` goes through one
primitive that knows the click's intent (world / UI / raw). The
primitive owns dead-zone checks, last-mile validation, and timing
profiles. Future guards (chatbox, side panels, custom RuneLite
overlays, "is a context menu open?") become one-line additions in
one file — never another scavenger hunt across 30 call sites.

The chatbox dead-zone is the **first consumer** of the new
primitive, not the design driver. The design driver is the missing
chokepoint itself.

---

## Why the obvious fix is the wrong fix

First instinct: add a `if (insideChatbox(cursor)) return;` guard at
the top of `clickPress(button)`. That's wrong because:

1. **`clickPress` is not the only press path.** Roughly 10 hot
   paths (NPC attack, ground-item take, GE menu-row click, inv-slot
   verbs in verified-action form) bypass it with direct
   `input.mousePress(BUTTON1); ... input.mouseRelease(BUTTON1);`
   pairs to get a tighter timing window. A guard in `clickPress`
   would silently miss every one of them.
2. **`clickPress` has no intent.** A guard there would block both
   "Attack chicken" (world click — should refuse if in chatbox)
   and "Click chat tab" (UI click — must be allowed). The
   information needed to make that distinction lives in the
   caller, not the primitive.

The fix is to introduce the primitive that does have intent, and
funnel everyone through it.

---

## The new primitive

```
boolean press(int button, ClickIntent intent, PressTiming timing)
```

Single chokepoint. Replaces both `clickPress(int)` and all direct
`input.mousePress/mouseRelease` pairs.

**`ClickIntent` enum** — declares why this press exists:

| Value         | Meaning                                                                 | Guards run                                |
| ------------- | ----------------------------------------------------------------------- | ----------------------------------------- |
| `WORLD`       | Targeting a world tile / NPC / object / ground item.                    | Dead-zone check (block); off-canvas check |
| `MINIMAP`     | Targeting the minimap disc (walk fallback path).                        | Off-canvas check only                     |
| `UI`          | Targeting a widget / bounds / inventory slot. May legitimately click inside chatbox/side panels/etc. | Off-canvas check only |
| `MENU_ROW`    | Clicking a right-click context-menu row that we just opened.            | Off-canvas check only                     |
| `RAW`         | Test hooks, `clickCanvas(x, y)`, low-level escape hatches.              | None                                      |

**`PressTiming` enum** — replaces the two-paths-for-timing problem:

| Value           | Pre-press dwell    | Button-down hold | Post-release hold |
| --------------- | ------------------ | ---------------- | ----------------- |
| `STANDARD`      | 180..500 ms        | 40..80 ms        | 100..350 ms       |
| `FAST_COMMIT`   | 0 ms (caller settled already) | 40..80 ms | 80..180 ms        |

Today's inline `input.mousePress/mouseRelease` pairs all correspond
to `FAST_COMMIT` (the caller did its own settle dwell already, e.g.
NPC-attack phases 4–6). Today's `clickPress(...)` calls all
correspond to `STANDARD`.

**Return value.** `true` = press fired. `false` = guard blocked the
press; `lastError` is set with a structured reason. Callers act on
the return:

- Walk fallback path checks the return and falls back to minimap.
- Interaction paths check the return and abort.
- Existing pre-checks (`isLeftClickWalk`, verb match) are
  unaffected — they still run before `press(...)` is called. The
  guard is the *last* gate, not the only gate.

---

## What lives inside the primitive

Pseudocode shape — actual implementation reads cursor from
`input.cursorX/cursorY`, the bounds from `UiDeadZones`, and uses
the existing `client.getCanvas()` for the off-canvas check:

```
boolean press(button, intent, timing):
    cx, cy = input.cursorX(), input.cursorY()

    // Off-canvas — never useful for any intent.
    if !onCanvas(cx, cy):
        lastError.set("press blocked: off-canvas at (cx, cy)")
        return false

    // Dead-zone — applies to WORLD only. UI, MENU_ROW, MINIMAP,
    // RAW bypass.
    if intent == WORLD:
        Rectangle hit = UiDeadZones.intersectsAt(cx, cy)
        if hit != null:
            lastError.set("press blocked: cursor in UI dead-zone " + hit)
            log.info("press blocked by dead-zone {} at ({},{}) intent=WORLD",
                hit, cx, cy)
            return false

    // Timing
    if timing == STANDARD:
        sleep(180..500)
    input.mousePress(button)
    sleep(40..80)
    input.mouseRelease(button)
    if timing == STANDARD: sleep(100..350)
    else:                  sleep(80..180)
    return true
```

That's the whole guard surface. Add a new dead-zone source? One
file. Add a new intent rule? Inside this method. Add a new timing
profile? New enum value, one branch.

---

## UiDeadZones

```
package net.runelite.client.sequence.dispatch;

class UiDeadZones {
    // Call on the client thread (widget read).
    static List<Rectangle> current(Client client);
    // Convenience: returns the first matching dead-zone or null.
    static Rectangle intersectsAt(Client client, int x, int y);
}
```

v1 returns the bounds of `InterfaceID.Chatbox.UNIVERSE` when the
widget exists and isn't hidden. That's the entire screenshot
region the user saw painted by `ChatDebugOverlay` — message area,
scrollbar, tabs row.

Why `UNIVERSE` and not `CHATAREA`: the tabs row and scrollbar are
inside `UNIVERSE` but outside `CHATAREA`. Clicking the scrollbar
mid-walk still drifts the player. Cover the whole `UNIVERSE`
rectangle.

A `// TODO` comment lists candidates for later (`MES_LAYER`
numeric-prompt overlay, side panels, the XP tracker overlay) but
**they do not go in v1**. Add only when a real regression demands
it. v1 is the chatbox alone.

Returns defensive `Rectangle` copies. Tolerates the chatbox widget
not being loaded (login screen, world hop) by returning an empty
list.

---

## Migration of the existing call sites

The bulk of this spec is a mechanical refactor. ~30 call sites in
`HumanizedInputDispatcher.java`. Group by intent so the diff is
auditable:

**WORLD** — walks and world-target interactions:
```
walkClick           clickPress(BUTTON1)        → press(B1, WORLD, STANDARD)
                    [the post-fallback minimap press is MINIMAP]
npcClick   phase 8  input.mousePress/Release   → press(B1, WORLD, FAST_COMMIT)
groundItemClick     input.mousePress/Release   → press(B1, WORLD, FAST_COMMIT)
gameObjectClick     clickPress(BUTTON1)        → press(B1, WORLD, STANDARD)
```

**MENU_ROW** — clicking a right-click menu entry after we opened one:
```
npcClick   phase 9  clickPress(BUTTON3)        → press(B3, WORLD, STANDARD)   [opens menu]
                    moveCursorTo(row); ...press → press(B1, MENU_ROW, FAST_COMMIT)
gameObjectClick + groundItemClick: same shape (right-click WORLD, row-click MENU_ROW)
boundsClick / invSlotClick verified-action variants: row click is MENU_ROW
```

**UI** — every widget/bounds/inv-slot dispatch:
```
widgetClick         clickPress(BUTTON1)        → press(B1, UI, STANDARD)
widgetVerbClick     clickPress(BUTTON1/3)      → press(B*, UI, STANDARD)
boundsClick         clickPress(B1/3)           → press(B*, UI, STANDARD)
invSlotClick        clickPress(B1/3)           → press(B*, UI, STANDARD)
```

**MINIMAP** — the walk-fallback path:
```
walkClick fallback  clickPress(BUTTON1)        → press(B1, MINIMAP, STANDARD)
```

**RAW** — escape hatches:
```
clickCanvas(x, y)                             → press(B1, RAW, STANDARD)
clickCanvas(xProp, yProp)                     → press(B1, RAW, STANDARD)
```

After the refactor, `clickPress(int)` and every direct
`input.mousePress/mouseRelease` pair are deleted. There is exactly
one method that ever calls `input.mousePress` / `input.mouseRelease`:
the new `press(...)` primitive.

---

## PixelResolver — optional efficiency layer

Once the primitive is in place, the dead-zone guarantee no longer
*requires* PixelResolver changes. But without them, every blocked
press still costs a humanized cursor move (~150–400 ms) before the
guard fires.

So PixelResolver gets a lighter touch — its existing rejection-
sampling loops (in `tryCleanMainViewPixel`, `resolveNpc`,
`resolveGameObject`, `resolveGroundItemPixel`, `resolveTilePixel`,
`resolveWallObject`) extend their `intersectsAny(...)` reject list
to include `UiDeadZones.current(client)`. Same shape as the
existing actor-hull rejection. Same 24-attempt budget.

If every candidate inside a polygon hits a dead-zone, return
`null` — the caller's existing null-handling kicks in (walk falls
through to minimap, interactions return early with `lastError`).
This is a "fail fast and let the script decide" path, not a
recovery path.

Worth doing: yes. Required for correctness: no. The primitive is
the correctness gate.

---

## Files to touch

```
NEW   runelite-client/.../sequence/dispatch/UiDeadZones.java
NEW   runelite-client/.../sequence/dispatch/ClickIntent.java
NEW   runelite-client/.../sequence/dispatch/PressTiming.java
EDIT  runelite-client/.../sequence/dispatch/HumanizedInputDispatcher.java
        - introduce `press(button, intent, timing)` primitive
        - migrate every clickPress/mousePress call site (~30)
        - delete clickPress(int) and the inlined press/release pairs
        - new method returns boolean; update walkClick to use the
          return value for minimap fallback
EDIT  runelite-client/.../sequence/dispatch/PixelResolver.java
        - extend rejection-sampling loops with dead-zone reject
        - 5 resolve* methods touched; same pattern in each
NEW   runelite-client/.../sequence/dispatch/PixelResolverDeadZoneTest.java
        - one narrow unit test (see below)
```

No `RecorderConfig` change. The guard is unconditional — there is
no toggle to disable it, because "never, ever, ever" is the
requirement.

---

## Migration plan — how to unify without breaking what works

The unification is mechanical (~30 call sites) but the risk surface
is real: timing drift on the inlined press/release pairs, and
silently swallowed failures if a caller forgets to check the new
boolean return. Five phases, each a separate commit, each
revertable independently.

### Phase 0 — parity audit (no code change)

Read every `clickPress(...)` and inlined `mousePress/mouseRelease`
call site. Capture in a table (commit as a scratch file under
`docs/superpowers/scratch/` or paste into the PR description):

| Line | Caller method | Button | Pre-dwell ms | Hold ms | Post-dwell ms | Intent | Timing label |
| ---- | ------------- | ------ | ------------ | ------- | ------------- | ------ | ------------ |
| 675  | walkClick     | B1     | 180..500     | 40..80  | 100..350      | WORLD  | STANDARD     |
| 888  | npcClick ph8  | B1     | 0            | 40..80  | 100..350      | WORLD  | FAST_COMMIT  |
| ...  | ...           | ...    | ...          | ...     | ...           | ...    | ...          |

This table is the source of truth for the migration. The
`PressTiming` enum values must produce the exact same `sleep(...)`
ranges as the original site. If the audit reveals a third timing
profile (some path has unique pre/post values), add a third enum
value rather than rounding to STANDARD/FAST_COMMIT — drifting
timings to "close enough" is exactly the kind of subtle behavior
change that bites two weeks later.

### Phase 1 — introduce primitive, zero migration

- Add `UiDeadZones`, `ClickIntent`, `PressTiming`.
- Add the new `press(int, ClickIntent, PressTiming) -> boolean`
  method to `HumanizedInputDispatcher`. Implemented but **not yet
  called from anywhere**.
- Keep the old `clickPress(int)` and every inlined press/release
  pair exactly as-is.
- Add the PixelResolver dead-zone test + extend the
  rejection-sampling loops (this part can ship behind the
  primitive, since the resolver returning `null` is already a
  well-handled state).
- Build green. Zero functional change. PR is reviewable as pure
  additions.

### Phase 2 — migrate guard-bypass intents (UI, MENU_ROW, MINIMAP, RAW)

These intents bypass dead-zone checks. **Behavior is identical**
post-migration; the only thing that changes is the call shape.
Migrate one caller method at a time:

```
widgetClick / widgetVerbClick / widgetAnyVerbClick    → UI
boundsClick / boundsAnyVerbClick / boundsClickOnWorker → UI
invSlotClick / *VerifiedAction variants              → UI for the
                                                       press, MENU_ROW
                                                       for the row click
walkClick post-fallback minimap press                 → MINIMAP
clickCanvas(x,y) / clickCanvas(xProp,yProp)           → RAW
All right-click row-clicks (post BUTTON3 menu open)   → MENU_ROW
```

After this phase, ~60% of call sites are on the primitive. The
remaining `clickPress(int)` and inlined pairs are all WORLD
intent.

Manual sanity check: open the bank, open the GE, open the chat
filter tabs, open inventory. Anything that was a UI click before
is still a UI click. Nothing broke because nothing changed
behaviorally.

### Phase 3 — migrate WORLD intent, one kind at a time

This is where behavior actively changes: world clicks now refuse
to fire when the cursor sits in a dead-zone. Do it one kind per
commit, with manual in-game acceptance between commits:

1. **WALK / CLICK_TILE.** Migrate `walkClick`. Update the post-press
   callers to check the boolean — non-strict walks fall back to
   `resolveMinimapOnly` (the existing path), strict walks abort.
   Acceptance: chicken-farm walk leg through a chatbox-occluded
   tile. Confirm: efficiency-layer skips main-view, guard never
   fires because the resolver already returned a minimap pixel.
2. **CLICK_NPC.** Migrate `npcClick`. Both the BUTTON3 (right-click)
   and BUTTON1 (left-click) presses become WORLD intent. Caller
   aborts on `false`. Acceptance: combat session, induce the
   chatbox-occluded chicken case.
3. **CLICK_GAME_OBJECT.** Migrate `gameObjectClick`. Acceptance:
   bank booth interaction; stairs interaction; gate open. All
   typical Trail-Walker flows.
4. **CLICK_GROUND_ITEM.** Migrate `groundItemClick`. Acceptance:
   loot pickup during a kill session. (Per the resolver's
   ground-item clickbox path, the loot tile is typically right
   under the player and chatbox-occluded loot tiles are common in
   fixed mode.)

Each step is a clean revert if a regression appears: revert the
commit, leave the primitive in place, the rest of the migration
still works.

### Phase 4 — delete the old code

- Delete `clickPress(int)`.
- Final grep: `input.mousePress` and `input.mouseRelease` should
  appear in exactly one method (the `press(...)` primitive). If
  any other call site remains, audit it before deletion.
- Final grep: every `clickPress(` reference is gone.

### Risks and mitigations

| Risk                                                          | Mitigation                                                                                                                  |
| ------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------- |
| Timing drift collapses two distinct profiles into one         | Phase-0 audit table is the source of truth. Add a third (or fourth) `PressTiming` value rather than rounding.                |
| Caller forgets to check the `boolean` and continues as if pressed | Every Phase-3 migration is one kind per commit. Manual in-game acceptance gate between commits surfaces the swallowed press. |
| Inlined press/release sits inside per-phase interaction logic | The migration replaces the two-line pair with one `press(...)` call **in place**. Surrounding "phase 5 re-aim" and "phase 8 claimed-by-other" checks are not moved. |
| The new guard exposes a pre-existing latent bug (a path that "worked" by accident) | Acceptable. Treat it as a discovery — document the bug, fix on the same branch, mention in PR description.                  |
| Scope creep — adding more dead-zones in this spec              | Locked: v1 is `Chatbox.UNIVERSE` only. Other zones are TODO comments, not features.                                          |

### Reversibility summary

- Phase 1 commit: pure-additive, never breaks anything. Always
  safe to keep even if Phases 2–4 get reverted.
- Phase 2 commit: behaviorally identical to pre-migration.
  Reverting it is purely a stylistic change.
- Phase 3 commits (×4, one per kind): each independently
  revertable. The four WORLD-intent paths share no state with each
  other.
- Phase 4 commit: only the deletion. If a forgotten call site is
  later discovered, re-introducing `clickPress(int)` as
  `press(b, RAW, STANDARD)` is trivial.

---

## Tests

One unit test, narrow, covers the resolver layer:

```
PixelResolverDeadZoneTest:
    - fixture: stub Client whose Chatbox.UNIVERSE bounds = lower-
      left quadrant of a 800x600 canvas
    - resolveWalkTarget(target projecting into that quadrant)
      returns a pixel on the minimap (not in the quadrant)
    - resolveNpc(npc whose hull is fully inside that quadrant)
      returns null
    - resolveNpc(npc straddling the boundary) returns a pixel
      that is inside the hull AND outside the quadrant
```

The `press(...)` primitive itself is verified by manual in-game
acceptance (next section). Per the no-tests-for-scripts memory,
dispatcher behavior is best validated against the live engine —
the unit-test surface for input timing / cursor movement is too
narrow to be worth the maintenance burden.

---

## Manual acceptance

Use the `ChatDebugOverlay` we just landed as the visual aid.

1. **Chicken farm with chatbox-occluded targets.** Resize the
   client so the chatbox covers part of the chicken pen. Watch
   the logs:
   - `resolver: candidate rejected by dead-zone` — efficiency
     layer firing
   - `press blocked by dead-zone Rectangle[...] at (x,y)
     intent=WORLD` — guard firing
   - Script keeps progressing (camera rotates, retries pick a
     different chicken).
2. **Walk with chatbox over the destination.** Use the
   recorder-panel walk-test to walk to a tile that's currently
   under the chatbox in main view. Expect:
   - `resolveWalkTarget` returns a minimap pixel directly
     (efficiency layer caught it).
   - Player walks fine.
3. **Right-click flow into chatbox.** Force-misposition the
   cursor (script-side) so a right-click would open over the
   chatbox. Expect:
   - `press blocked` on the BUTTON3 attempt
   - No stuck menu (per the stuck-menu memory).
4. **Negative test — chat tab click still works.** Open the chat
   filter tabs (All / Game / Public / etc.) via a UI click —
   that's a `CLICK_WIDGET` so intent=UI, guard bypassed. Tab
   switches correctly.
5. **Negative test — bank deposit with chatbox visible.** Bank
   widget is far above chatbox; no occlusion. Confirm no
   false-positive blocking. (CLICK_WIDGET = UI intent, guard
   bypassed anyway.)

If a script ends up "stuck rotating forever because every angle
has the target occluded" — that's a script-side recovery
problem, not a dispatcher problem. Out of scope for this spec.

---

## Open questions before code

1. **Strict-walk + dead-zone**: today strict-walk aborts on a
   failed `isLeftClickWalk`. Should strict-walk also abort on a
   dead-zone hit (vs. allowing minimap fallback)? Proposal:
   strict-walk treats dead-zone as a hard abort. Confirming.
2. **MENU_ROW intent + chatbox**: if a right-click menu we opened
   somehow ends up extending into chatbox bounds (long menu near
   the bottom), should we still allow the row click? Proposal:
   yes — MENU_ROW bypasses dead-zone because the menu itself is
   already drawn on top of everything by the engine. The cursor
   being "inside chatbox bounds" while clicking a menu row is
   fine because the engine's click resolution sees the menu, not
   the chatbox. Confirming.
3. **Refactor scope**: this touches ~30 call sites in one file.
   Worth doing as one PR (clean primitive + migrate + add
   dead-zone in one shot) or split (primitive + migration first,
   dead-zone consumer second)? Proposal: one PR. The primitive
   without a real consumer is dead weight; the consumer without
   the primitive is the wrong design. Confirming.
