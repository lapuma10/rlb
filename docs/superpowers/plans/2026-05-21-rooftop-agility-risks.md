# Rooftop Agility v1 — Known Risks (pre-acceptance)

**Status:** captured 2026-05-21 after QC pass 2. No further fixes pending.
Code in `RooftopAgilityScript.java` + plugin/panel wiring; spec at
`docs/superpowers/specs/2026-05-21-rooftop-agility-design.md`.

## Operational gates

- **Build not verified.** Gradle never run during implementation. One
  compile error caught + fixed in QC pass 1 (`client.getMenu().isOpen()`
  → `client.isMenuOpen()`); secondary errors possible from
  `lastRunOn`, `lapEndTileSeen`, `lapEndSince`, `clientThread.invokeLater`,
  `InterfaceID.Orbs.RUNBUTTON`, or `InterfaceID.OrbsNomap.RUNBUTTON`. First
  acceptance step is `./gradlew :client:compileJava`.

- **`COURSES` map is empty.** Draynor capture data (plan Tasks 4–5;
  spec §17) is the user-owned blocker. `start()` refuses with
  `"No course profile for DRAYNOR"` until populated.

- **Per-node `timeoutMs` is a placeholder.** Set to 4000 ms (short hops)
  / 7000 ms (tightropes) on first paste; tune from observed lap times.

## Runtime risks (compile + start OK, but watch during acceptance)

- **Run-orb dispatch has no `verb`.** `maybeEnableRun` issues
  `CLICK_WIDGET` against the run-orb without a `.verb(...)`. Engine
  default left-click should toggle run; if it resolves to something else
  on a particular camera/layout combo, run never turns on. Mitigation:
  add `.verb("Toggle Run")` if observed dead.

- **`expectedSuccessTiles` for intermediate stages defaults to next
  node's `stageTiles`.** If the actual landing tile after a successful
  obstacle traversal is mid-rooftop (not in the next stage's
  `stageTiles`), timeout fires on every otherwise-successful traversal.
  The capture workflow tells you to mark transit tiles into `validTiles`,
  but if any are missed `handleUnmappedValidTile` surfaces the exact
  missed coords after 8 s — paste them into the relevant
  `stage.N` group and restart.

- **Dual lap-increment is by-convention.** Both `handleObstacleTimeout`
  (timeout backstop) and `handleLapEnd` (normal-success) increment
  `lapsCompleted`. Single-counts today because `clearLastObstacle()`
  runs before `handleLapEnd`'s `finalNodeClicked` check. Invariant is
  documented inline in both methods. Any refactor that reorders or
  removes the clear silently double-counts — re-verify if you touch
  either site.

- **Mark on a stage tile.** If a mark of grace spawns on the player's
  current stage tile, `tryPickupReachableMark` runs first, then the
  next tick clicks the obstacle. Adds one tick of pacing; no bug.

- **Cold-start on `lapEndTile`.** Preflight allows starting on a lapEnd
  (recoverable tile). `handleLapEnd` walks toward start each throttle
  window. If `walkToNearestStartTile` repeatedly fails for any reason,
  residency cap kicks in at 8 s and stops with
  `"Stuck on lapEndTile <x,y,p> — walk-to-start failing"`.

## What is NOT a risk (verified clean across two QC passes)

- Mark filtering (plane + `reachableMarkTiles`), PICKING_MARK lifecycle,
  `markStillOnTile` re-scan.
- `handleLowHp` throttle gate + no-food stop path.
- `maybeEnableRun` falling-edge reseed of `runOnAtLeast` + 2 s toggle
  throttle.
- `handleBlockingDialog` Escape via KEY/KEYBOARD.
- `handleTargetLevel` using real (not boosted) level.
- EDT-to-client-thread marshaling of `start()`.
- `dispatcher.isBusy()` gating all downstream dispatch branches.
- One-`ActionRequest`-per-tick invariant.
- Wiring: `RecorderPlugin` construct/register/unregister/cleanup,
  `RecorderPanel` tab + Start/Stop handlers + status poll + session
  tracker.
- `validateCourse` invariants (spec §6a).

## Acceptance plan

Spec §21 has 16 manual in-game items, ordered. First three items
(cold start, resume mid-course, forced fall) catch the most common
failure modes; items 11 (click throttle), 13 (unmapped tile),
15 (tightrope disambiguation), and 16 (one-ActionRequest-per-tick)
are the ones most likely to surface a subtle bug.
