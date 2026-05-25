# Tier 1 Run 01 — 2026-05-24

> Filled per Phase 2D §9
> (`docs/superpowers/plans/2026-05-24-artemis-phase-2d-operator-session-protocol.md`).
> First supervised run; **Tier 1 NOT PASSED — launch path validated, loop
> reliability blockers found.**
> Captures two attempts in one session (operator Stop + restart between
> them): Attempt 1.A (script-initiated logout) and Attempt 1.B
> (post-restart; cows killed but with click-miss storm).

---

## Identification

```
Runtime commit (binary):    0fce87fac  (Phase 2D HEAD; jar built 22:35:23)
Protocol/doc commit:        0fce87fac  (Phase 2D)
Branch:                     master
Date:                       2026-05-24
```

## Environment

```
Account:                 test account (operator-confirmed)
World:                   not recorded
Start location:          adjacent to Lumbridge cow field
                         (logs: player at (3256,3260,0) / (3259,3260,0)
                         on each walk start)
Inventory at start:      not recorded
Gear at start:           not recorded
Recording session:       not enabled (no RECORDING entries from
                         RecorderManager in log window) — Step-tree
                         counts below are operator-observed +
                         log-derived estimates, not authoritative
                         session JSON.
```

## Timing

```
Attempt 1.A (script-initiated logout):
  Start time:            22:39:15  (operator clicked Start)
  Logout fired:          22:39:22  (LogoutStep dispatched widget 0xa40022)
  Operator Stop:         between 22:39:22 and 22:40:58
  Attempt duration:      ~7s of script activity + operator cleanup
  Stop trigger:          §6 "Behavior diverges from planned Step tree" —
                         scripted logout fired before any attack landed;
                         operator correctly halted the run.

Attempt 1.B (post-restart):
  Start time:            22:40:58  (operator clicked Start again)
  Last logged activity:  22:44:13  (4th cow click-miss in third walk
                         cycle)
  Operator Stop:         after 22:44:13 (exact timestamp not in shared
                         log excerpt)
  Attempt duration:      ~3-5 minutes of script activity
  Stop trigger:          operator-normal — consistent click-miss pattern
                         across 3 walk-arrival → attack cycles and 6+
                         distinct cow targets; operator decided to halt
                         before §6 thresholds escalated.
```

## UI state transitions observed

```
- Start clicked at 22:39:15 (attempt 1.A) → status "running" (Δ not
  captured)
- Operator Stop after 22:39:22 → status returned to idle (Δ not captured)
- Start clicked at 22:40:58 (attempt 1.B) → status "running"
- Operator Stop after 22:44:13 → status returned to idle (Δ not captured)
```

Panel state UI was responsive throughout (no freeze reported).

## Step tree behavior

```
Source: operator-observed + client.log derived; recorder session NOT
        active, no Events.Step entries persisted, counts approximate.

- walkTo dispatches:                 3 (22:39:15, 22:40:58, 22:43:50)
  - all targeting WorldPoint(x=3260, y=3258, plane=0)
    (LUMBRIDGE_COW_FIELD)
  - all completed with reachedGoal=true; 0 failures observed
- Cow targets selected:              6+ distinct NPC IDs (2806, 2798,
                                     2785, 2786 directly in logs; plus
                                     1+ unlogged in attempt 1.A and the
                                     cows that were killed successfully
                                     in attempt 1.B)
- Attacks dispatched:                ≥10 ActionRequest enqueues; most
                                     aborted at hover/menu check; a
                                     minority landed successfully in
                                     attempt 1.B (cows died per operator)
- Busy-gate skip count:              ≥3 (one per walk arrival, every
                                     cycle). Plus operator-observed
                                     "waits till theyre dead" during
                                     combat — busy gate working during
                                     kills.
- No-cow idle/retry occurrences:     Not directly logged; possibly
                                     during the 18s gap between 22:41:20
                                     and 22:41:38 (cow respawn / rotation
                                     yield) — not confirmable from log
                                     alone.
```

## Exceptions / log anomalies

```
- Zero new exception stacktraces from PID 45144's session in client.log.
- The 135 "Unsuccessful ping" entries in client.log are pre-existing
  Jagex-server noise from older sessions in the un-rotated log file —
  zero from the 22:36+ window of this Tier 1 session.
- No NavigatorRetryStorm; no exception storm of any kind.
```

## Failures observed

```
F1 (CRITICAL, attempt 1.A, 22:39:22) —
    Scripted logout via Selector fallback.
    CowKillerScript.plan() (CowKillerScript.java:122-124) wires
        Selector("cow-killer-after-walk")
          .option(RepeatStep("cow-killer-loop", tick, 0))
          .option(artemis.logout())
    The RepeatStep returned FAILED on its first iteration — most likely
    because the busy-gate-dropped CLICK_NPC at 22:39:16 propagated as a
    ClickNpcStep failure up through RepeatStep, causing the Selector to
    advance to the next option (artemis.logout()). Logout fired cleanly
    via LogoutStep + RecorderLogoutAction + Artemis. Widget 0xa40022
    decodes to group 164 / child 34 (the STONE10 logout side-panel tab
    that RecorderLogoutAction.nextLogoutWidgetId() returns).
    Operator-facing protocol (Phase 2D §1, §5, §8) framed logout as
    never firing outside of operator action — that framing was
    incomplete; the script's OWN plan can log out without operator
    action when an ordinary loop failure propagates up.

F2 (HIGH, attempt 1.B, 22:41:00–22:44:13) —
    Click-miss storm against moving cows. NPCs 2785, 2786, 2798, 2806
    each required 2-4 attack attempts to land combat. Pattern per
    attempt:
      1. PixelResolver snapshots NPC hull pixel on dispatcher worker
      2. Humanized cursor takes 200-500ms to arrive
      3. Cow has paced; cursor lands on adjacent tile
      4. "not hover-default for 'Attack' — right-click flow"
      5. Right-click menu (against the wrong-tile target) doesn't
         contain 'Attack' — dispatcher aborts cleanly (no stuck-menu
         leak — feedback_stuck_rightclick_menu discipline holds)
      6. Step retries next iteration; eventually one attempt catches
         the cow standing still and combat starts
    ActionRequest.liveTracked exists for CLICK_GAME_OBJECT
    (ActionRequest.java:124) but NOT for CLICK_NPC. That gap is the
    underlying engine-level cause; script-level retry tolerance is
    the short-term path.

F3 (MEDIUM, all 3 walk-arrival cycles, 22:39:16 / 22:40:59 / 22:43:51) —
    First CLICK_NPC after walk arrival dropped by busy gate. The walk's
    dispatcher chain has not released its busy flag when the next
    Step.check() tries to enqueue the attack. ~600 ms wasted per loop
    iteration. Minor on its own — but in attempt 1.A appears to be the
    triggering failure that propagated up to the Selector → logout
    fallback (F1).
```

## Stop outcome

```
Stop worked cleanly?     Yes, both times. Stop button responsive within
                         expected window; status label returned to idle.
Manual logout completed? Attempt 1.A — unintentional: the SCRIPT logged
                         out before operator-Stop, so manual logout was
                         not the operator's action. Attempt 1.B — not
                         specifically confirmed in the run report
                         (likely performed manually after Stop per
                         protocol).
```

## Screenshots

None captured for this run.

## client.log excerpt

```
Path: ~/.runelite/logs/client.log
Window: 2026-05-24 22:36:09 (PID 45144 boot) through 22:44:13 (last
        logged attack attempt). Relevant excerpts covered in
        §Failures observed.
```

## Operator notes

```
- First-time launch via Phase 2C.1 UI worked: Recorder panel rendered
  the Cow Killer pilot (test) section; config toggle ON enabled Start;
  click Start launched the pilot.
- Attempt 1.A: logged out unexpectedly — operator stopped, restarted.
- Attempt 1.B: confirmed cows die; busy gate works during combat; but
  4-5 attack attempts per cow before any cow took damage.
- No crashes, no UI freeze, no exception storm.
- Grep gate unchanged; no allow-list changes required.
- Engine integration: confirmed end-to-end.
- Loop reliability: NOT acceptable for unattended or extended
  supervised runs in current state.
```

## Verdict

```
Tier 1 NOT PASSED — launch path validated, loop reliability blockers
found.

CONFIRMED WORKING:
  ✅ UI Start (Phase 2C.1 wiring)
  ✅ WalkToZone(LUMBRIDGE_COW_FIELD) (Phase 2A zone + Phase 2B.x wiring)
  ✅ NpcQuery.byName("Cow") target selection (Phase 2C)
  ✅ Combat busy gate during kills (Phase 2C DynamicStep Branch 1)
  ✅ UI Stop (Phase 2C.1 wiring)
  ✅ Grep gate green (Phase 1B)
  ✅ No runtime crash / exception storm

BLOCKING FOR TIER 1 PASS:
  ❌ F1: Selector → artemis.logout() fallback fires on ordinary loop
         failure (transient click-miss / dispatcher-busy drop), not
         only on terminal SESSION_EXHAUSTED.
         Per operator directive: ordinary failures MUST be non-terminal;
         only true terminal conditions should reach logout.
  ❌ F2: Stale-NPC-position causing 4-5 click attempts per cow.
         Borderline §6 "Consistent click miss" — combat does start, so
         technically not a blocker by §6 thresholds, but operationally
         too noisy.
  ❌ F3: First-attack-after-walk dropped by dispatcher busy
         (~600 ms wasted per loop iteration; suspected trigger for F1
         in attempt 1.A).

ROUTE PER PHASE 2D §10:
  - F1 → Phase 2C.x (failure isolation in the DynamicStep attack branch;
                     keep logout reserved for terminal conditions only)
  - F2 → short-term Phase 2C.x (script-side fresh-refetch + idle/retry);
         long-term Phase 3 (liveTracked NPC mode in dispatcher;
         DEFERRED)
  - F3 → Phase 2C.x (settle gate between walk arrival and first attack
                     enqueue, or dispatcher.isBusy() poll in
                     ClickNpcStep.onStart)

NEXT: Phase 2C.x loop-tolerance fix planned and implemented —
see docs/superpowers/plans/2026-05-24-artemis-phase-2cx-loop-tolerance.md.
After 2C.x lands + rebuild + restart, re-run Tier 1 as Run 02.
```
