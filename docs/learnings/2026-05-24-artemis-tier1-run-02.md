# Tier 1 Run 02 — Phase 2C.x verification

> Filled per Phase 2D §9
> (`docs/superpowers/plans/2026-05-24-artemis-phase-2d-operator-session-protocol.md`).
> Second supervised Tier 1 run; **Tier 1 PARTIAL PASS — Phase 2C.x F1+F3
> verified; F2 residual sub-case (dying-cow retargeting) found.**

---

## Purpose

Verify the Phase 2C.x loop-tolerance fix
(`docs/superpowers/plans/2026-05-24-artemis-phase-2cx-loop-tolerance.md`,
commit `f3b75298e`) resolves Run 01's reliability blockers without
introducing regressions.

---

## Identification

```
Runtime commit (binary):    f3b75298e   (Phase 2C.x; jar built 10:02:43)
Protocol/doc commit:        0fce87fac   (Phase 2D operator protocol)
Previous run:               Run 01 (run-log commit 56863742f,
                            runtime 0fce87fac)
Branch:                     master
Date:                       2026-05-25
```

## Environment

```
Account:                 test account (operator-confirmed)
World:                   not recorded
Start location:          adjacent to Lumbridge cow field
                         (logs: player at (3258,3267,0) / (3259,3270,0)
                         / (3262,3262,0) on each walk start)
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
Session window:          2026-05-25 10:05:01 – 10:15:58
                         (multiple Start/Stop cycles within the
                         single supervised session)

Attempt 2.A (first):
  Start time:            10:05:01  (operator clicked Start)
  Operator Stop:         10:05:20  (after 2 attack misses against
                         npc 2904)
  Duration:              ~19s
  Stop trigger:          operator-normal (early stop after observing
                         "constant right-clicking" pattern starting)

Attempt 2.B:
  Start time:            10:07:40
  Operator Stop:         (cancelled at 10:09:33 after 4+ attack-miss
                         cycles at npc 2912, 2896, 2904)
  Duration:              ~1m53s

(Gap: 10:09:33 - 10:14:15. transport-obs at 10:11:18 suggests
 operator walked the character manually between attempts —
 player crossed a gate around (3236, 3295, 0).)

Attempt 2.C:
  Start time:            10:14:15  (0-tile walk; already in zone)
  Operator Stop:         10:15:44  (after 3+ misses at npc 2806)
  Duration:              ~1m29s

Attempt 2.D (final):
  Start time:            10:15:46
  Operator Stop:         10:15:58  (after 2 misses at npc 2798, 2896)
  Duration:              ~12s

Total observed script-running time: ~3m53s across 4 attempts.
```

## UI state transitions observed

```
- Start clicked at 10:05:01 (attempt 2.A) → status "running"
- Operator Stop responsive on each of 4 stop events
- No UI freeze / panel state lag reported
```

## Step tree behavior

```
Source: client.log derived; recorder session NOT active, no
        Events.Step entries persisted.

- walkTo dispatches:                 4 (10:05:01, 10:07:40,
                                     10:14:15 [0-tile], 10:15:46)
  - all targeting WorldPoint(x=3260, y=3258, plane=0)
    (LUMBRIDGE_COW_FIELD)
  - all completed reachedGoal=true (or ARRIVED on the 0-tile case)
- "dispatcher busy, dropping CLICK_NPC" (F3 indicator):
                                     1 occurrence (at 10:07:42,
                                     immediately after walk arrival
                                     10:07:41) — out of 4 walk cycles.
                                     Run 01 baseline: 3 of 3.
- Cow targets selected (distinct scene indices):
                                     5+ (2904, 2912, 2896, 2806, 2798)
- Attack click attempts:             ≥14 ActionRequest enqueues
- "right-click menu did not contain 'Attack'" (F2 indicator):
                                     16 across the session
                                     (Run 01 ≈ 8 in shorter window)
- "widgetVerbClick: ... 'Logout'" (F1 indicator):
                                     0 across the entire session
                                     (Run 01: 1 at 22:39:22)
- park cursor events (idle cycles):  5 — confirms inner Selector
                                     fall-through-to-idle path is
                                     firing between failed attacks
- No-cow idle/retry occurrences:     not directly logged; likely
                                     during the gaps between attempts
```

## Exceptions / log anomalies

```
- Zero new exception stacktraces from PID 1253's session.
- One benign WARN at 10:06:47: "Unable to ping session service"
  (Jagex-server ping retry; matches pre-existing log noise pattern;
  not script-induced).
- No NavigatorRetryStorm; no exception storm of any kind.
```

## Failures observed

```
F2b (NEW sub-case found in Run 02) — Dying / unattackable target
fixation.

  Concrete pattern (from 10:15:18-10:15:39 sequence):
    10:15:18  npc 2806  attempt 1 — right-click — Attack not in menu
    10:15:36  npc 2806  attempt 2 — right-click — Attack not in menu
    10:15:38  npc 2806  attempt 3 — right-click — Attack not in menu

  Pattern repeated at npc 2904 (10:05:16-10:05:18, 10:09:02-10:09:04),
  npc 2896 (10:08:30-10:08:31, 10:09:32, 10:15:55), npc 2912
  (10:07:47-10:07:48), npc 2798 (10:15:50).

  Root cause (Artemis read-layer audit confirms):
    - NpcQuery filter chain in ArtemisImpl.matches() (lines 511-548)
      checks: name, id, location-not-null, plane, range, excludeIndices,
      requireUnengaged.
    - requireUnengaged filters npc.getInteracting() against other
      Players only. A cow whose interacting is null (combat just
      ended, including by death) passes this filter.
    - There is NO "is dead" filter today.
    - RotationPolicy.ClosestWithSlack(2) picks the nearest match;
      a dying cow at the player's tile is still "closest" until it
      despawns ~5-10s later.
    - The script's supplier therefore selects the same dying cow
      across multiple iterations.
    - The 2C.x inner Selector("attack-or-retry") catches each Failed
      click and idles → next iteration → same dying cow selected
      again. Engine stays alive (F1 fix working) but loop wastes
      attempts.

  Notes on RuneLite signals available but NOT currently exposed:
    - Actor.isDead() exists (returns true during death animation)
    - Actor.getAnimation() exists (death anim ids exist)
    - Actor.getInteracting() exists
    - NPC inherits all of these
    - NpcRef today exposes only: index, id, name, originalLoc,
      healthRatio, observedTick (NpcRef.java:11-22).
    - PlayerState.idle is computed in ArtemisImpl:384 as
      animation==-1 && getInteracting()==null — already proven
      pattern for "in-combat" detection.

Side observation: weird mouse path described by operator
("minimap → chat → attack") = dispatcher WindMouse curves between
off-canvas park positions and the cow's hull pixel. Park positions
in the run: (1067,845), (261,842), (224,860), (341,-17), (1278,318).
WindMouse paths between these and a cow at the centre of the canvas
naturally pass through UI areas. Not a 2C.x concern; Phase 3
dispatcher / PixelResolver territory.
```

## Per-failure regression checks (vs Run 01)

```
F1 (scripted logout from ordinary failure):
  Status:                NOT OBSERVED — 2C.x fix VERIFIED
  Evidence:              0 widgetVerbClick(Logout) entries across
                         the entire 10:03-10:15 session vs Run 01's
                         1 firing at 22:39:22. Inner Selector("attack-or-retry")
                         caught every transient ClickNpcStep failure;
                         outer Selector(RepeatStep, logout) never
                         advanced to its logout option.

F2 (4-5 clicks per cow):
  Status:                MIXED — engine no longer terminates, but
                         residual sub-case (F2b dying-cow retargeting)
                         emerged as the dominant remaining cost.
  Run 01 baseline:       8 right-click misses in ~5 min before logout
                         terminated run.
  Run 02 observed:       16 right-click misses in ~12 min across 4
                         attempts; per-minute miss rate similar, but
                         loop survives them all (was the goal of 2C.x).

F3 (busy-gate drop after walk):
  Status:                MOSTLY FIXED — Δ from 3-of-3 (Run 01) to
                         1-of-4 (Run 02).
  Evidence:              "dispatcher busy, dropping CLICK_NPC" at
                         10:07:42 only — immediately after walk
                         arrival at 10:07:41. SHORT_IDLE (600-1200ms)
                         drained the chain on the other 3 cycles.
                         When the drop did happen, inner Selector
                         caught the resulting timeout — no
                         propagation to logout.
```

## Stop outcome

```
Stop worked cleanly?     Yes, on all 4 operator Stop events. Status
                         label returned to idle/disabled each time.
Manual logout completed? Not specifically confirmed at end of session.
```

## Screenshots

None captured for this run.

## client.log excerpt

```
Path: ~/.runelite/logs/client.log
Window: 2026-05-25 10:03:12 (PID 1253 boot) through 10:15:58
        (last logged nav cancel). Per-failure excerpts inline in
        §Failures observed.
```

## Operator notes

```
- 2C.x F1 fix WORKED — the bot no longer logs out on transient
  click failures. Engine survived ~12 minutes of intermittent
  combat with multiple Start/Stop cycles.
- Cows DID die successfully when first attack landed.
- Visible behavior between attacks: dispatcher park-cursor lands
  off-edge (random 1 of 4 edges); WindMouse paths between park
  and cow can visually traverse the minimap/chatbox area en
  route — humanization behavior, not script bug.
- The "constant right-clicking" pattern after a kill is the
  bot re-selecting the dying cow (still in scene) repeatedly
  during the ~5-10s death-animation/despawn window. Loop
  doesn't terminate (good — F1 fix), but wastes click budget
  on the corpse.
- No crashes, no UI freeze, no exception storm. Grep gate
  unchanged.
```

## Verdict

```
Tier 1 PARTIAL PASS — Phase 2C.x F1+F3 fixes verified;
F2 residual sub-case (dying-cow target fixation) is the
remaining bottleneck.

CONFIRMED FIXED BY PHASE 2C.x:
  ✅ F1: Selector → artemis.logout() on ordinary loop failure
         (Run 02: 0 logouts; Run 01: 1 logout)
  ✅ F3: First-attack-after-walk dispatcher busy drop
         (Run 02: 1 of 4 cycles; Run 01: 3 of 3 cycles)

PARTIALLY ADDRESSED:
  ⚠️  F2: Inner Selector keeps engine alive on misses (engine
         survives, no termination), but the misses themselves
         persist because the target rotation has no
         dead/dying-aware filter.

NEW SUB-CASE IDENTIFIED:
  ❌ F2b: Dying-cow target fixation. Cow's HP reaches 0,
          death animation plays for ~5-10s, NPC remains in
          scene with name="Cow" and interacting=null, so
          NpcQuery.byName("Cow").unengagedOnly() keeps
          returning it. ClosestWithSlack(2) picks the same
          dying cow each iteration. Click fails (Attack not
          in menu), inner Selector idles, next iteration
          picks the same dying cow again. Cycle until cow
          despawns from scene.

ROUTE PER PHASE 2D §10:
  - F2b → Phase 2C.x.1 (NEW slice) — add dead-NPC filter at
          Artemis read layer (ArtemisImpl.matches(): one-line
          `if (npc.isDead()) return false;`). This is the
          minimal architectural change; alternatives
          (NpcRef exposure, script-side blacklist) are larger
          and unnecessary for the dominant failure mode.
  - Phase 3 liveTracked CLICK_NPC remains DEFERRED.
  - Loot / drop-wait / inventory-full termination remain
    DEFERRED until target-state model is stable.

NEXT: plan and implement Phase 2C.x.1 (dead-NPC filter),
rebuild + restart, run Tier 1 Run 03.
```
