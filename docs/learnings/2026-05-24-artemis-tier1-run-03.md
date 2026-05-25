# Tier 1 Run 03 — Phase 2C.x.1 verification (BLOCKED)

> Filled per Phase 2D §9
> (`docs/superpowers/plans/2026-05-24-artemis-phase-2d-operator-session-protocol.md`).
> Third supervised Tier 1 run; **Tier 1 NOT PASSED — WalkStep retry +
> premature-STUCK bug blocks the cow loop before 2C.x.1 can be fully
> evaluated.**

---

## Purpose

Intended: verify the Phase 2C.x.1 dead-NPC filter
(`docs/superpowers/plans/2026-05-25-artemis-phase-2cx1-dead-npc-filter.md`,
commit `6395736dc`) resolves Run 02 F2b.

Actual: exposed a deeper bug in `WalkStepBase` (Phase 1A.4d) that
predates this Tier 1 series and prevents most walks from completing.

---

## Identification

```
Runtime commit (binary):    6395736dc   (Phase 2C.x.1; jar built 10:42:40)
Protocol/doc commit:        0fce87fac   (Phase 2D operator protocol)
Previous runs:              Run 01 (run-log 56863742f, runtime 0fce87fac)
                            Run 02 (run-log 3a28f6d76, runtime f3b75298e)
Branch:                     master
Date:                       2026-05-25
```

## Environment

```
Account:                 test account "onenonly2"
                         (boot log "Session account name late-resolved")
World:                   not recorded
Start location:          varied per attempt — see Timing
Inventory at start:      not recorded
Gear at start:           not recorded
Recording session:       not enabled
```

## Timing

```
Session window:          2026-05-25 10:44:20 – 10:52:14
                         (multiple Start/Stop cycles within one
                         supervised session)

Attempt 3.A (first — SHORT walk):
  Walk start:            10:44:20  (5-tile walk from (3255,3261)
                                    to LUMBRIDGE_COW_FIELD)
  Walk arrived:          10:44:20  (reachedGoal=true)
  Attack misses:         npc 2806 at 10:44:22, npc 2787 at 10:44:25
                         (DIFFERENT scene indices — 2C.x.1 working)
  Operator Stop:         10:44:58
  Duration:              ~38s
  Outcome:               walk OK; small attack-loop sample; cow killed?
                         Not confirmed visually.

Attempt 3.B (LONG walk, BUG fires):
  Walk start:            10:48:03  (20-tile walk from (3262,3278)
                                    to LUMBRIDGE_COW_FIELD)
  Walk click:            10:48:06  (3s later — humanized cursor + click)
  STUCK declared:        10:48:06  (SAME second as the click landed)
  Single-use exception:  10:48:06  WARN ArtemisActionStep:
                         WalkToZone onStart threw
                         IllegalStateException: WalkStep is single-use
  Outcome:               walk failed; engine attempted retry on same
                         Step instance; threw immediately.

Attempt 3.C (same bug, same cause):
  Walk start:            10:48:23  (20-tile walk, same target)
  STUCK declared:        10:48:26  (3s after start)
  Single-use exception:  10:48:26  WARN — same IllegalStateException

Attempt 3.D (same bug, same cause):
  Walk start:            10:52:04  (20-tile walk, same target)
  STUCK declared:        10:52:07  (3s after start)
  Single-use exception:  10:52:07  WARN — same IllegalStateException

Total observed script-running time: ~5 min wall. Only ~38 s of
that was an attempt where the script even reached the cow loop;
the other 3 attempts died at walk-start.
```

## UI state transitions observed

```
- Start clicked on each of 4 attempts → status "running"
- Stop responsive on each operator Stop
- No UI freeze
```

## Step tree behavior

```
Source: client.log derived; recorder session NOT active.

- walkTo dispatches:                 4 (10:44:20, 10:48:03, 10:48:23,
                                     10:52:04). 1 reached goal; 3
                                     declared STUCK + threw single-use
                                     IllegalStateException.
- Cow targets selected (Attempt 3.A only):
                                     2 distinct (2806, 2787) —
                                     2C.x.1 working, no same-index repeat.
- Attack click attempts (3.A only):  2
- "right-click menu did not          2 (both in 3.A; sample too small
  contain 'Attack'":                 to draw F2 conclusions)
- "widgetVerbClick: ... 'Logout'"
  (F1 indicator):                    0 — F1 fix STILL verified
- "dispatcher busy, dropping
  CLICK_NPC" (F3 indicator):         0 — F3 fix STILL verified
- IllegalStateException: WalkStep
  is single-use:                     3 (10:48:06, 10:48:26, 10:52:07)
- park cursor events:                3 — all bottom edge (224, 860)
                                     [edge=2]
```

## Exceptions / log anomalies

```
NEW critical log pattern (Tier 1 blocker):

  2026-05-25 10:48:06 WARN n.r.c.s.a.script.ArtemisActionStep -
    WalkToZone(LUMBRIDGE_COW_FIELD) onStart threw:
    java.lang.IllegalStateException: WalkStep is single-use —
    construct a fresh Step per walk (WalkToZone(LUMBRIDGE_COW_FIELD))

Same pattern at 10:48:26 and 10:52:07.

The exception is THROWN ON RETRY, not on first start. Source:
WalkStepBase.java:195-200 (single-use guard) + 388-403 (onFailure
returns Recovery.Retry(2) for STUCK/TIMEOUT/NAVIGATOR_FAILED) +
StateDrivenEngine.java:443-444 (Retry resets the FRAME's started
flag and re-fires step.onStart on the SAME Step instance).

Benign pre-existing noise unchanged:
- "Unsuccessful ping" Jagex-server retries; 1 occurrence in window
  (10:45:22), unrelated to bot loop.
```

## Failures observed

```
F-D1 (TIER 1 BLOCKER, NEW) — WalkStep retry/premature-STUCK bug.

  Root cause (verified in source, multiple sites):

  Engine path (StateDrivenEngine.java:435-445):
    On Recovery.Retry, engine increments frame.retryCount, sets
    frame.setStarted(false), and re-fires f.getStep().onStart(...)
    on the SAME Step instance at next tick.

  Step path (WalkStepBase.java):
    doStart (line 193-200) checks instance-level `started` flag;
    if true, throws IllegalStateException("WalkStep is single-use").
    onFailure (line 388-403) returns Recovery.Retry(2) for
    REASON_STUCK / REASON_TIMEOUT / REASON_NAVIGATOR_FAILED.

  Coupled bug — premature STUCK detection:
    STUCK_THRESHOLD_TICKS=6 (line 94, ~3.6s) starts counting from
    doStart, with no gate for "has the worker actually started
    navigating yet". For walks where dispatcher cursor traversal
    + minimap click takes ≥3s (long-distance walks, off-canvas
    park previous), STUCK fires before the player has physically
    moved. This is what triggers the Retry, which trips the guard.

  10:48 evidence: walk start 10:48:03 → click dispatched
  10:48:06 (3 seconds, ~5 ticks of "no movement") → STUCK declared
  same second.

F-D2 (DEFERRED, lower priority) — Off-canvas park lands on UI
  surfaces (chat panel / Private/Friends tabs). Cursor PARKED only,
  not clicking. The (224, 860) park position the dispatcher chose
  for [edge=2] (bottom edge) lands over the chat tab area on this
  canvas resolution. RuneLite's click-inspector overlay shows
  "l-click: Cancel [CANCEL]" — that's an inspector hint, not a
  performed click. Still flagged as a soft concern (hover
  tooltips / scroll capture risk); deferred to a future
  dispatcher-safety slice.

NOT in this run (regression status preserved):
  - F1 (scripted logout)           : 0 occurrences (still fixed)
  - F2b (dying-cow retargeting)    : 0 same-index repeats (still fixed)
  - F3 (busy-drop after walk)      : 0 drops (still fixed)
```

## Per-failure regression checks (vs prior runs)

```
F1 (scripted logout):
  Status:                NOT OBSERVED — fix STILL VERIFIED
  Evidence:              0 widgetVerbClick(Logout) entries across
                         the entire Run 03 window.

F2 (stale-moving-cow click misses):
  Status:                INCONCLUSIVE — only 2 attempts in Attempt 3.A
                         before operator Stop. Sample too small.

F2b (dying-cow retargeting, Phase 2C.x.1 target):
  Status:                CANNOT BE FULLY EVALUATED — the walk bug
                         blocks 3 of 4 attempts from reaching the
                         attack loop. The 1 attempt that DID reach
                         the loop showed two DIFFERENT scene indices
                         tried with no same-index repeat — early
                         positive signal that 2C.x.1 works as designed,
                         but not a full verification.

F3 (busy-drop after walk arrival):
  Status:                NOT OBSERVED — fix STILL VERIFIED
  Evidence:              0 "dispatcher busy, dropping CLICK_NPC"
                         entries across the entire Run 03 window.
```

## Stop outcome

```
Stop worked cleanly?     Yes — operator Stop responsive on all
                         4 attempts.
Manual logout completed? Not specifically confirmed.
```

## Screenshots

Operator-supplied annotated screenshot during Attempt 3.B or later
(post-walk-bug-cycle) shows:
- Red arrows from player area → minimap (top-right) and from player
  area → bottom-left chat tabs (Private/Friends).
- HUD overlay: `mouse: 224,860  tile: 3265,3273 p=0  l-click: Cancel
  [CANCEL]`.
- (224, 860) matches dispatcher's `park cursor → (224,860) [edge=2]`
  log line.
- `l-click: Cancel [CANCEL]` is RuneLite's click-inspector hint
  ("if you left-clicked here, this menu action would fire") — NOT
  a record of an actual click. The bot did not click Cancel.

## client.log excerpt

```
Path: ~/.runelite/logs/client.log
Window: 2026-05-25 10:43:09 (PID 43805 boot) – 10:52:14 (last
        nav cancel). Relevant excerpts inline in §Failures observed.
```

## Operator notes

```
- 2C.x.1 dead-NPC filter early signal positive but not fully
  evaluated; walk bug blocks 3 of 4 attempts.
- Operator concerned about input-surface behavior (mouse going to
  minimap area and chat area). Verified as parking, not clicking.
- The walk bug is independent of any 2C.x / 2C.x.1 change; it
  predates this Tier 1 series. Verified in source — WalkStepBase
  retry contradiction is structural, not introduced by recent work.
- F1 (logout) and F3 (busy-drop) remain fixed.
- No crashes, no UI freeze, no exception storm beyond the
  Walk-IllegalStateException pattern.
```

## Verdict

```
Tier 1 NOT PASSED — Phase 2C.x.1 dead-NPC filter could not be fully
evaluated because a WalkStep retry/premature-STUCK bug in
WalkStepBase (Phase 1A.4d) blocks the cow loop on long walks.

CONFIRMED STILL FIXED:
  ✅ F1: Selector → artemis.logout() on ordinary loop failure
  ✅ F3: first-attack-after-walk dispatcher-busy drop

EARLY POSITIVE SIGNAL (NOT a full pass):
  ↗  F2b: Phase 2C.x.1 dead-NPC filter — the one successful walk
       attempt showed two DIFFERENT scene indices tried, no
       same-index repeat. Consistent with the filter working.

NEW TIER 1 BLOCKER:
  ❌ F-D1: WalkStep retry + premature-STUCK bug.
     - WalkStepBase.onFailure returns Recovery.Retry(2) for
       STUCK / TIMEOUT / NAVIGATOR_FAILED.
     - StateDrivenEngine retries by re-firing onStart on the same
       Step instance.
     - WalkStepBase's instance-level single-use guard
       (this.started) throws IllegalStateException.
     - Triggered by the STUCK detector firing before the
       dispatcher has finished its humanized cursor + minimap
       click chain (3-second observed vs 3.6s threshold).

DEFERRED (NOT a Tier 1 blocker by itself):
  ⏸  F-D2: Off-canvas park lands over chat-area UI on tall
     canvases. Cursor parked only, not clicked. Soft concern
     for a later dispatcher-safety slice.

ROUTE PER PHASE 2D §10:
  - F-D1 → Phase 1A.4d.1 (NEW slice) — WalkStep retry/premature-STUCK
           fix. Coupled two-line change in WalkStepBase:
             (a) gate stuck counter on
                 `volatile workerEverRunning == true`
                 (worker writes; doCheck reads)
             (b) change onFailure switch default to Recovery.Abort
                 for all reasons (drop the broken Retry contract;
                 callers can rebuild walk via parent composite if
                 retry is wanted)
  - F-D2 → DEFERRED to a future dispatcher-safety slice.
  - F2 (stale-moving-cow) → Phase 3 liveTracked CLICK_NPC (still
                            deferred).
  - F2b (dying-cow) → 2C.x.1 still expected to be verified after
                      1A.4d.1 lands.

NEXT: plan and implement Phase 1A.4d.1 (WalkStep retry +
premature-STUCK fix). After it lands + rebuild + restart, run
Tier 1 Run 04. Run 04 will be the first run that can fully
evaluate 2C.x.1.
```
