# Tier 1 Run 04 — Phase 1A.4d.1 + Phase 2C.x.1 verification

> **PRELIMINARY — awaiting close-range continuation.**
> Filled per Phase 2D §9
> (`docs/superpowers/plans/2026-05-24-artemis-phase-2d-operator-session-protocol.md`).
> First attempt was started from ~21 tiles away from LUMBRIDGE_COW_FIELD;
> the long walk uncovered a NEW PixelResolver minimap-projection bug
> that prevents player movement. **Phase 1A.4d.1 is verified working
> regardless** — walk now Aborts cleanly without IllegalStateException.
> Operator will continue Run 04 from close range (≤10 tiles) so the
> canvas-walk path is used and Phase 2C.x.1 can finally be evaluated
> at a meaningful sample size.
> Do NOT commit until the close-range continuation is captured.

---

## Purpose

Verify Phase 1A.4d.1 (walk retry + premature-STUCK fix, commit
`d489543d0`) and re-evaluate Phase 2C.x.1 (dead-NPC filter, commit
`6395736dc`) which Run 03 could not exercise.

---

## Identification

```
Runtime commit (binary):    d489543d0   (Phase 1A.4d.1; jar built 11:30:04)
Protocol/doc commit:        0fce87fac   (Phase 2D operator protocol)
Previous runs:              Run 01 (run-log 56863742f, runtime 0fce87fac)
                            Run 02 (run-log 3a28f6d76, runtime f3b75298e)
                            Run 03 (run-log c99a9af16, runtime 6395736dc)
Branch:                     master
Date:                       2026-05-25
```

## Environment

```
Account:                 onenonly2 (test account, per
                         "Session account name late-resolved" boot log)
World:                   not recorded
Start location:          ~21 tiles north of LUMBRIDGE_COW_FIELD
                         (player at (3240, 3265, 0) at walk start;
                         goal area centred near (3260, 3258, 0))
                         — UNEXPECTED for Tier 1 protocol which assumes
                         start near the field; long-walk path triggered
                         the F-E1 PixelResolver bug.
Inventory at start:      not recorded
Gear at start:           not recorded
Recording session:       not enabled
```

## Timing

```
Session window:          2026-05-25 11:30:37 (PID 93387 boot) – ongoing

Attempt 4.A (long walk, exposed F-E1):
  Walk start:            11:31:11  (21-tile walk from (3240, 3265))
  Walk minimap clicks:   11:31:14 / 11:31:15 / 11:31:16 / 11:31:17
                         (4 clicks, ALL at screen (1157, 84) = disc
                         centre = player's own tile = no-op)
  STUCK declared:        11:31:17  (correct semantics — player did
                         not move; gate did not fire prematurely)
  Walk Aborted cleanly:  11:31:17  (NO IllegalStateException;
                         1A.4d.1 fix VERIFIED)
  nav-v21 cancel:        11:31:42

Attempt 4.B (short walk + brief cow-loop sample):
  Walk start:            11:31:44  (8-tile walk from (3258, 3266))
  Walk dispatch:         11:31:44  (screen (992, 229) — CANVAS pixel,
                         not minimap; canvas-walk path eligible
                         because dist <= 10)
  Walk arrived:          11:31:44  (instant — reachedGoal in same
                         second)
  First attack attempt:  11:32:36  (npc 2806 — Attack not in menu)
  Second attack attempt: 11:32:39  (npc 2908 — DIFFERENT scene index,
                         Attack not in menu)
  park cursor:           11:32:42
  Operator Stop / nav-v21 cancel: 11:32:47

Run-running time so far: ~2 minutes across 2 attempts in 1 supervised
session. Continuation pending from close-range start.
```

## UI state transitions observed

```
- Pilot Start clicked sometime between 11:30:58 (worldmap-bootstrap
  complete) and 11:31:11 (first walk start). Status went to running.
- Stop responsive at 11:32:47 (nav-v21 cancel logged).
- No UI freeze.
```

## Step tree behavior (so far)

```
Source: client.log derived; recorder session NOT active.

- walkTo dispatches:                 2 (11:31:11 long, 11:31:44 short)
  - long walk (21-tile): STUCK + Aborted (no movement due to F-E1)
  - short walk (8-tile): arrived in 1s
- "dispatcher busy, dropping
  CLICK_NPC" (F3 indicator):         0 — F3 fix still verified
- "widgetVerbClick: ... 'Logout'"
  (F1 indicator):                    0 — F1 fix still verified
- IllegalStateException: WalkStep
  is single-use (F-D1 indicator):    0 — 1A.4d.1 fix VERIFIED
- Cow targets selected (distinct
  scene indices, Attempt 4.B only):  2 (2806, 2908) — different
                                     indices, no same-index repeat
- "right-click menu did not          2 (in the ~6s cow-loop window)
  contain 'Attack'":
- attack-or-retry idle fallthroughs: 1+ park cursor event observed
- F2b indicator — same scene index   0 — early positive signal but
  retried 2+ times in <30s window:    sample too small to fully
                                      verify
- F-E1 indicator — minimap walk
  click landed at disc centre
  (1157, 84):                        4 in Attempt 4.A
```

## Exceptions / log anomalies

```
- ZERO new exception stacktraces from PID 93387's session.
- Specifically: ZERO occurrences of
  "WalkStep is single-use — construct a fresh Step per walk".
  Phase 1A.4d.1 retry contradiction fix VERIFIED.
- 1 benign "Unsuccessful ping" Jagex-server retry (11:32:03).
  Pre-existing noise; unrelated to bot loop.
- No NavigatorRetryStorm; no exception storm of any kind.
```

## Failures observed

```
F-E1 (NEW Tier 1 BLOCKER for long walks) — PixelResolver minimap
projection collapses to disc-centre when target is beyond minimap
radius.

  Concrete evidence (Attempt 4.A, 21-tile walk):
    11:31:14  PixelResolver - minimap disc: widget bounds=(1081,8 152x152) center=(1157,84) r=72
    11:31:14  HumanizedInputDispatcher - walk → world (3259,3259,0) via screen (1157,84)
    11:31:15  HumanizedInputDispatcher - minimap click → clicking without menu pre-check
    11:31:15  walk → world (3259,3259,0) via screen (1157,84)
    11:31:16  walk → world (3257,3261,0) via screen (1157,84)
    11:31:17  walk → world (3259,3259,0) via screen (1157,84)
    11:31:17  walk: STUCK
    11:31:17  park cursor → (1067, 845) [edge=2]

  Source path (PixelResolver.resolveWalkTarget, line 125-167):
    - dist > 10 → considerMainView=false → minimap path forced
    - Perspective.localToMinimap(client, scene) returns null for
      targets beyond ~17-tile radius
    - line 142: `if (minimap == null) return null;`
    - BUT the dispatcher saw a non-null pixel (it logged the walk
      dispatch and the minimap click) — so somewhere along the
      chain the projection collapsed to disc-centre instead of
      returning null. Likely interaction between
      clampToMinimapDisc and ringJitter on an out-of-disc pixel.

  Server-side effect: clicking the dead-centre of the minimap walks
  the player to the tile they ALREADY stand on — a no-op. Game
  acknowledges 4 clicks; player doesn't move. WalkStepBase correctly
  declares STUCK after 6 ticks of no movement (the gate released
  appropriately — workerEverRunning was true because
  Navigator.tick was returning RUNNING).

  Safety guard not present: HumanizedInputDispatcher.walkClick
  checks "target == player.worldLocation()" pre-resolve (line
  612-619), but does NOT check "resolved minimap pixel ==
  disc-centre" post-resolve. Either fix point is viable for
  a later slice.

  This is a pre-existing PixelResolver bug; NOT introduced by
  Phase 1A.4d.1. The 1A.4d.1 fix actually exposes it cleanly
  (Abort instead of the prior IllegalStateException crash loop).

NOT observed in this run (regression status preserved):
  - F1 (scripted logout)          : 0 occurrences (still fixed)
  - F2b (dying-cow retargeting)   : 0 same-index repeats observed
                                    in the brief cow-loop sample
                                    (early positive signal; sample
                                    too small for full verification)
  - F3 (busy-drop after walk)     : 0 drops (still fixed)
  - F-D1 (WalkStep retry crash)   : 0 occurrences (1A.4d.1 fix
                                    VERIFIED)

Deferred (not Tier 1 blocker by itself):
  - F-D2 off-canvas park lands on chat-area UI on tall canvases.
    Park positions in Attempt 4.A / 4.B: (1067, 845), (1257, 539).
    Cursor parked, not clicked.
```

## Per-failure regression checks (vs prior runs)

```
F1 (scripted logout):
  Status:                NOT OBSERVED — fix STILL VERIFIED.

F2 (general click misses):
  Status:                INCONCLUSIVE — only 2 attempts in Attempt 4.B
                         before operator Stop. Sample too small.

F2b (dying-cow retargeting, Phase 2C.x.1 target):
  Status:                EARLY POSITIVE SIGNAL — NOT FULLY VERIFIED.
                         The 2 attack attempts in the cow-loop window
                         (11:32:36-40) used distinct scene indices
                         (2806, 2908), no same-index repeat. But
                         sample too small. Close-range continuation
                         needed to bank verification.

F3 (busy-drop after walk arrival):
  Status:                NOT OBSERVED — fix STILL VERIFIED.

F-D1 (WalkStep retry + premature STUCK, Phase 1A.4d.1 target):
  Status:                VERIFIED FIXED.
  Evidence:              Long walk in Attempt 4.A hit STUCK at
                         tick ~6, but onFailure returned Abort cleanly
                         — ZERO IllegalStateException occurrences
                         (vs Run 03's 3). The retry contradiction is
                         gone. Premature-STUCK gate worked as
                         intended: stuck only counted while
                         workerEverRunning=true, and player legit
                         did not move (F-E1 cause).
```

## Stop outcome

```
Stop worked cleanly?     Yes — operator Stop responsive at 11:32:47
                         (nav-v21 cancel logged immediately).
Manual logout completed? Not confirmed.
```

## Screenshots

None captured for this run yet.

## client.log excerpt

```
Path: ~/.runelite/logs/client.log
Window so far: 2026-05-25 11:30:37 (PID 93387 boot) – 11:32:47
        (Attempt 4.B nav-v21 cancel). Relevant excerpts inline in
        §Failures observed.
Continuation pending.
```

## Operator notes

```
- 1A.4d.1 retry-contradiction fix CONFIRMED — no
  IllegalStateException, walks Abort cleanly when stuck.
- Pre-existing PixelResolver bug found: long-walk minimap
  projection lands on disc-centre (= player's current tile) →
  4 minimap clicks dispatched, 0 movement. Tier 1 BLOCKER for
  long walks specifically.
- Short walks (≤10 tiles, canvas-walk path) work correctly —
  Attempt 4.B's 8-tile walk arrived instantly.
- Operator hypothesis "bot is banned from the minimap" —
  ruled out: the dispatcher DID dispatch 4 minimap clicks; the
  game accepted them; they just resolve to "walk to self" which
  is a server no-op.

Decision per operator: continue Run 04 from close range
(≤10 tiles to LUMBRIDGE_COW_FIELD) to evaluate the cow-loop /
2C.x.1 dead-NPC filter at a meaningful sample size. The
PixelResolver long-walk fix becomes the next navigation slice
(Phase 1A.4d.2) AFTER the cow-loop evaluation lands.
```

## Verdict (PRELIMINARY)

```
PRELIMINARY — Tier 1 verdict pending close-range continuation.

CONFIRMED IN THIS WINDOW:
  ✅ Phase 1A.4d.1 — walk retry + premature-STUCK fix VERIFIED.
     Zero IllegalStateException; walks Abort cleanly on stuck;
     premature-STUCK gate (workerEverRunning) released
     correctly only when the navigator was actually running.
  ✅ F1 (logout) — 0 occurrences (still verified)
  ✅ F3 (busy-drop) — 0 occurrences (still verified)

NEW TIER 1 BLOCKER:
  ❌ F-E1 — PixelResolver long-walk minimap projection
     collapses to disc-centre (player's own tile). 4 minimap
     clicks in Attempt 4.A all landed at screen (1157, 84) =
     disc-centre; player did not move; walk correctly STUCK +
     Aborted. Pre-existing PixelResolver bug; not introduced
     by 1A.4d.1.

PHASE 2C.x.1 (dead-NPC filter):
  ↗ Early positive signal — 2 attack attempts in Attempt 4.B
     used distinct scene indices, no same-index repeat. NOT
     fully verified — sample too small.

NEXT (per operator):
  1. Operator manually places character ≤10 tiles from
     LUMBRIDGE_COW_FIELD.
  2. Operator clicks Start again (same runtime commit
     d489543d0).
  3. Run for 3-5 min if stable.
  4. Update this run-log with close-range evidence.
  5. THEN commit (single doc-only commit for Run 04).
  6. THEN decide:
     - If cow loop passes close-range → mark 2C.x.1 verified;
       plan Phase 1A.4d.2 (PixelResolver long-walk fix) next.
     - If cow loop still fails → triage cow-loop issue first.

ROUTE PER PHASE 2D §10 (preliminary):
  - F-E1 → Phase 1A.4d.2 (PixelResolver disc-edge projection
           + angular fallback). Plan + implementation:
           docs/superpowers/plans/2026-05-25-artemis-phase-1a4d2-pixelresolver-minimap-disc-edge.md.
  - F-D1 → ADDRESSED by 1A.4d.1.
  - F-D2 (chat-area park) → DEFERRED.
  - F2  (stale moving cow) → Phase 3 still DEFERRED.
  - F2b (dying-cow filter) → AWAITING close-range continuation
                              for full verification.
```
