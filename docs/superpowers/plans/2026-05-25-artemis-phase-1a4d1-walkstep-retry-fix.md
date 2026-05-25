# Phase 1A.4d.1 — WalkStep retry + premature-STUCK fix

**Status:** approved 2026-05-25 — implementation slice.
**Sibling docs:**
- Run 03 (motivating evidence): `docs/learnings/2026-05-24-artemis-tier1-run-03.md`
- Parent slice: Phase 1A.4d (WalkStepBase introduction)

---

## 1. Problem

Tier 1 Run 03 surfaced a structural bug in `WalkStepBase` (Phase 1A.4d)
that predates the cow-killer Tier 1 series and prevents long-distance
walks from completing.

Three consecutive 20-tile walks (10:48:03 / 10:48:23 / 10:52:04) were
declared `STUCK` ~3 seconds after walk start — **before the player
had physically moved at all**. Each STUCK triggered
`WalkStepBase.onFailure` to return `Recovery.Retry(2)`, which the
engine's `applyRecovery` path (`StateDrivenEngine.java:443-444`)
handled by setting `frame.setStarted(false)` and re-firing
`step.onStart(...)` on the **same Step instance** at the next tick.
`WalkStepBase.doStart`'s instance-level single-use guard threw:

```
WARN ArtemisActionStep - WalkToZone(LUMBRIDGE_COW_FIELD) onStart threw:
java.lang.IllegalStateException: WalkStep is single-use —
construct a fresh Step per walk (WalkToZone(LUMBRIDGE_COW_FIELD))
```

Run 03 verdict: **Tier 1 NOT PASSED**; Phase 2C.x.1 dead-NPC filter
could not be fully evaluated because the walk bug blocks 3 of 4
attempts before the cow loop runs.

## 2. Two coupled root causes (both in `WalkStepBase.java`)

### 2a. Retry contradiction (`onFailure`)

| Site | Behavior |
|---|---|
| `WalkStepBase.java:195-200` (`doStart`) | `if (started) throw new IllegalStateException("WalkStep is single-use…")` |
| `WalkStepBase.java:387-403` (`onFailure`, pre-fix) | Returns `Recovery.Retry(2)` for `REASON_STUCK / REASON_TIMEOUT / REASON_NAVIGATOR_FAILED` |
| `StateDrivenEngine.java:435-445` | On `Retry`, resets `frame.setStarted(false)` and re-fires `step.onStart(...)` on the SAME Step instance |

The Step declares itself single-use AND offers Retry. Both can't be
true unless retry creates a fresh instance — which the engine doesn't.

### 2b. Premature `STUCK` (`doCheck`)

`STUCK_THRESHOLD_TICKS = 6` (~3.6 s, `WalkStepBase.java:94`) starts
counting from `doStart` with no gate for "has the navigator actually
started running yet". For a long walk:

- Worker spawns at tick 0.
- Worker calls `navigator.tick()`; navigator plans, asks dispatcher
  to click the minimap.
- Dispatcher humanized cursor traversal + minimap click takes 2-4
  seconds, especially when the cursor was parked off-canvas from a
  prior action.
- Player's `WorldPoint` doesn't change for 5+ ticks during this
  pre-walk window.
- `stuckTicks` hits 6 → `STUCK` fires → retry → re-onStart →
  `IllegalStateException`.

Concrete Run 03 evidence:
```
10:48:03  walk: start (worker spawned)
10:48:06  walk → world via screen (1157,84)        ← click only just dispatched
10:48:06  walk: stop signal — reason: stuck         ← SAME second
10:48:06  WARN: WalkStep is single-use IllegalStateException
```

## 3. Approved fix (Option A — both changes required)

Single file changed in production: `WalkStepBase.java`.

### 3a. Gate the stuck counter on `volatile workerEverRunning`

Rename the existing `workerHadRunning` field and add `volatile`. The
worker thread already sets this when it observes `NavStatus.RUNNING`
(used internally to distinguish `NO_ROUTE` vs `NAVIGATOR_FAILED`).
Promote it to a cross-thread publish; `doCheck` reads it on the
client thread to gate the stuck counter.

The 6-tick threshold (~3.6 s) **stays unchanged**. The semantics of
the constant's docstring — "location unchanged while walking" — only
become true after the gate releases.

### 3b. Drop `Recovery.Retry` from `WalkStepBase.onFailure`

`onFailure` now returns `Recovery.Abort(reason)` for every failure
reason. Engine Retry was incompatible with the single-use guard;
removing the conflict fixes the contradiction cleanly.

Walk retries become caller responsibility. The cow-killer's current
`LinearSequence` will see walk-Abort, propagate up, and stop the
plan; operator must restart. Acceptable for v1. If a future script
needs walk retries, it builds them via a parent composite that
constructs a fresh `artemis.walkTo(...)` Step per attempt — not by
reusing this Step instance.

The single-use guard at `doStart` (line 195-200) is **kept** — it
defends against any direct caller mistake.

## 4. Tests

`WalkToZoneStepTest` + `WalkToWorldPointStepTest` updates.

### Updated existing tests

- **`WalkToZoneStepTest.onFailureMapsAllDiagnosticsCorrectly`** —
  all 7 reasons (`STUCK`, `TIMEOUT`, `NAVIGATOR_FAILED`,
  `NAVIGATOR_MISSING`, `NO_ROUTE`, `NAVIGATOR_EXCEPTION`,
  `EMPTY_ZONE`) now assert `Recovery.Abort`. Added regression guard:
  `assertFalse(rec instanceof Recovery.Retry)` per reason.
- **`WalkToWorldPointStepTest.onFailureMapsDiagnosticsCorrectly`** —
  same pattern, 6 reasons (no `EMPTY_ZONE`).
- **`WalkToWorldPointStepTest.stuckDetectionAfter6Ticks`** — added
  `awaitCondition(step::workerEverRunningForTesting, 1000, …)` after
  `respondWith(RUNNING)` so the race between worker publish and
  test thread read is deterministically resolved before counting
  ticks. Test still asserts `STUCK` fires at exactly tick 6.

### New test

- **`WalkToWorldPointStepTest.stuckCounter_doesNotIncrement_beforeWorkerEverRunning`** —
  the gate-negative test. Spawn worker, respond `NavStatus.IDLE`
  only (never `RUNNING`), advance 20 ticks with location frozen,
  assert `step.check(...)` stays `RUNNING` and no STUCK fires.
  Explicitly verifies `workerEverRunningForTesting()` is false.

### Regression guards (unchanged, must still pass)

- `singleUseGuardThrowsOnSecondDoStart` — direct double-doStart
  still throws. Guard stays; only the engine's Retry trigger is
  removed.
- `noRouteWhenFirstNavTickFails` — NO_ROUTE path uses
  `workerEverRunning == false` as before (now `volatile`,
  semantics preserved).
- `navigatorExceptionFromTick` — NAVIGATOR_EXCEPTION path
  unaffected.
- `succeedsAfterArrivalDebounce` / `succeedsAfterTwoConsecutiveTicksInZone`
  — happy path, unchanged.

## 5. Files touched

```
M  runelite-client/src/main/java/.../sequence/activities/script/WalkStepBase.java
M  runelite-client/src/test/java/.../sequence/activities/script/WalkToZoneStepTest.java
M  runelite-client/src/test/java/.../sequence/activities/script/WalkToWorldPointStepTest.java
A  docs/superpowers/plans/2026-05-25-artemis-phase-1a4d1-walkstep-retry-fix.md
M  docs/learnings/2026-05-24-artemis-tier1-run-03.md  (one-line cross-ref to this plan)
```

**Not touched** (per scope constraints):
- `StateDrivenEngine.java`, `StepFrame.java` — engine assumption is
  unchanged; the contract simply moves from "Step may opt into
  engine Retry" to "Step Aborts and the caller decides."
- `CowKillerScript`, `Artemis`, `ArtemisImpl`, `NpcQuery`, `NpcRef`
- `PixelResolver`, `HumanizedInputDispatcher`, `ActionRequest`
- `RecorderPanel`, `RecorderConfig`
- Phase 1B grep gate allow-list

## 6. Risks (small, accepted)

| Risk | Severity | Mitigation |
|---|---|---|
| A walk that genuinely stalls mid-route now Aborts without auto-retry — pilot stops, operator must restart | Acceptable for v1 | Matches the user's explicit directive. Better than the current crash-on-retry. Future plan-level retry slice can wrap `walkTo` in a parent composite. |
| Volatile publish race: client thread checks before worker has written | Very low | Stuck counter staying false for one extra tick is benign — the worker sets the flag on its next loop. Worst case: STUCK fires at tick 7 instead of 6. Test uses `awaitCondition` to remove the race in the test fixture. |
| Walks that never observe RUNNING (navigator stuck in IDLE forever) skip stuck detection entirely | Low | The 60-tick `TIMEOUT_TICKS` safety net (`WalkStepBase.TIMEOUT_TICKS`) still catches truly-hung walks. NO_ROUTE / NAVIGATOR_FAILED via worker also still terminate. |
| Existing tests pinning the broken Retry contract will fail until updated | Expected | Updated in this slice. |

## 7. Out of scope (deferred)

- **F-D2** off-canvas park lands on chat-area UI on tall canvases.
  Cursor parked, not clicking. Separate dispatcher-safety slice.
- **F2** stale-moving-cow click misses — Phase 3 `liveTracked`
  `CLICK_NPC` still DEFERRED.
- **F2b** dying-cow filter (Phase 2C.x.1) — awaiting a Run 04 that
  can complete a long walk to fully verify.
- Plan-level walk retry (parent composite that wraps `walkTo` in
  a Selector with a fresh attempt) — separate slice if/when a
  script needs it.
- Engine-level "Retry creates fresh instance via factory" refactor
  — Option C from the triage; deferred unless a use case appears.

## 8. Outcome / next

After this slice lands:

1. Rebuild `:client:shadowJar` (operator-gated per
   `feedback_dont_build_client_unprompted`).
2. Kill stale RuneLite (PID 43805, still holding the pre-1A.4d.1
   jar) + relaunch from new jar per Phase 2D rebuild-restart
   pattern.
3. Run Tier 1 Run 04 per Phase 2D operator protocol.
4. Run 04 is the first run that can complete long walks → first
   chance to fully evaluate Phase 2C.x.1 (dead-NPC filter) at the
   intended sample size.
5. Fill `docs/learnings/2026-05-25-artemis-tier1-run-04.md` (or
   `2026-05-24-` per the existing series prefix convention; choose
   at template-creation time).
