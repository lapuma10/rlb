# Phase 2C.x — Cow-Pilot Loop Tolerance

**Status:** approved 2026-05-24 — implementation slice.
**Sibling docs:**
- Run 01: `docs/learnings/2026-05-24-artemis-tier1-run-01.md`
- Phase 2C: `docs/superpowers/plans/2026-05-24-artemis-phase-2c1-ui-wiring.md`
- Phase 2D operator protocol: `docs/superpowers/plans/2026-05-24-artemis-phase-2d-operator-session-protocol.md`

---

## 1. Problem

Tier 1 Run 01 exposed three reliability issues in the Phase 2C
cow-killer Step tree:

- **F1 (CRITICAL)** — `Selector(RepeatStep, logout)` fell through to
  `artemis.logout()` after a transient `ClickNpcStep` failure. The
  outer Selector treats any `RepeatStep` Failed as terminal.
- **F2 (HIGH)** — 4-5 click attempts per cow because the cow moves
  between `PixelResolver` hull snapshot and humanized cursor arrival.
- **F3 (MEDIUM)** — first `CLICK_NPC` after every walk arrival
  dropped by the dispatcher busy flag (walk's residual chain not
  yet released).

F1 and F3 are tightly coupled: the busy-drop forces `ClickNpcStep`
into its 8-tick (`TIMEOUT_TICKS`) `dispatcherIdle()` poll loop, which
either eventually Succeeds blindly (if the walk's chain clears in
time) or fails with `ActionTimedOut`. In Run 01 attempt 1.A the
timeout fired → `DynamicStep` Failed → `RepeatStep` Failed → outer
`Selector` advanced to `logout`. Either preventing the drop OR
catching the timeout fixes F1.

## 2. Engine semantics — verified before designing

Each invariant was checked in source before the fix shape was
finalized. Trusting these as a contract:

| Component | Verified behavior | Source |
|---|---|---|
| `Selector.onChildPopped` on child `Failed` | tries next eligible option | `Selector.java:49-61` |
| `Selector.onChildPopped` on child `Succeeded` | finishes with success | `Selector.java:51` |
| `RepeatStep.onChildPopped` on child `Failed` | `FinishWithFailure(reason)` (propagates up) | `RepeatStep.java:51` |
| `RepeatStep.onChildPopped` on child `Succeeded` | increments count, pushes body again | `RepeatStep.java:52-56` |
| `DynamicStep.onChildPopped` | transparent — propagates child status as-is | `DynamicStep.java:105-122` |
| `FailStep.check()` | always `Completion.Failed(reason)` | `FailStep.java:104-108` |
| `ClickNpcStep` hard-fail modes | `STALE_REF` (age / identity mismatch), `TARGET_NOT_FOUND` (re-resolve), `ActionTimedOut` (>8 ticks) | `ClickNpcStep.java:82-111`, `:131-145` |
| `ClickNpcStep` on dispatcher idle | Succeeded (does NOT see dispatcher-internal menu-miss aborts) | `ClickNpcStep.java:142-148` |
| `IdleStep` | pure tick-counter; no dispatch, no worker sleep, no client-thread block; maintenance Step (bypasses session gate) | `IdleStep.java:108-125` |

The approved fix `Selector(click, idle)` works because (a) Selector
falls through on child Failed and (b) IdleStep deterministically
Succeeds after its sampled tick window.

## 3. Approved tree shape

```
LinearSequence("cow-killer-v1")
 ├─ artemis.walkTo(NamedZone.LUMBRIDGE_COW_FIELD)
 ├─ artemis.idle(SHORT_IDLE)                      ← NEW (2C.x F3)
 └─ Selector("cow-killer-after-walk")
     ├─ RepeatStep("cow-killer-loop", DynamicStep, 0)
     │     DynamicStep("cow-killer-tick") supplier:
     │       Branch 0  !session.shouldContinue()  → FailStep("SESSION_EXHAUSTED")
     │                                             [MUST NOT be wrapped]
     │       Branch 1  player==null || !idle()    → artemis.idle(SHORT_IDLE)
     │       Branch 2  cow Optional empty         → artemis.idle(SHORT_IDLE)
     │       Branch 3  cow found                  → Selector("attack-or-retry")
     │                                                ├─ artemis.click(cow, "Attack")
     │                                                └─ artemis.idle(SHORT_IDLE)
     │                                             [NEW 2C.x — F1 + F2]
     └─ artemis.logout()
```

Reuses the existing `SHORT_IDLE = IdlePolicy(600, 1200, false)` at
`CowKillerScript:77`. No new policy constant, no new method on
`Artemis`, no new Step type.

## 4. F1 / F2 / F3 mapping

**F1 — Selector → logout on ordinary loop failure.** Inner
`Selector("attack-or-retry")` catches every `ClickNpcStep` Failed
case. By verified Selector semantics: on Failed → next option = idle
→ Succeeded → `DynamicStep` Succeeded → `RepeatStep` continues
iterating. Logout NEVER reached on transient failures.

**SESSION_EXHAUSTED safety.** Branch 0 returns `FailStep` directly,
NOT wrapped. The Failed status propagates intact through `DynamicStep`
+ `RepeatStep`, the outer `Selector` falls through to its `logout()`
option, and the run terminates as designed. Pinned by Branch 0
regression test.

**F2 — stale-NPC click-miss storm.** Inner Selector handles both
hard failures (re-resolve `STALE_REF`, `TARGET_NOT_FOUND`) and the
existing "blindly Succeeded on dispatcher idle even when right-click
menu didn't contain Attack" pattern via loop-iteration retry. Each
retry calls `artemis.findNpc(query)` fresh, so the next attempt
uses an up-to-date `NpcRef`. Does NOT eliminate the underlying
stale position — that's Phase 3 `liveTracked` `CLICK_NPC` (see
`ActionRequest.java:124`), explicitly DEFERRED.

**F3 — first-attack-after-walk dropped by dispatcher busy.**
`artemis.idle(SHORT_IDLE)` between `walkTo` and the loop `Selector`
gives the walk's residual dispatcher chain 600-1200 ms to drain.
First attack iteration enqueues against an idle dispatcher. If the
chain takes longer than `SHORT_IDLE.maxMs`, the inner Selector
still catches the resulting `ActionTimedOut` (defense in depth).

## 5. Tests

`runelite-client/src/test/java/.../recorder/scripts/CowKillerScriptPlanShapeTest.java`

**Structural — updated:**
- `planRootIsLinearSequenceCowKillerV1` — `LinearSequence` children
  count is now **3** (walk + post-walk-settle + selector), was 2.
- Helper `extractRepeatStep()` walks `getChildren().get(2)`, was `.get(1)`.

**Structural — renamed (was `child1IsSelectorWithRepeatThenLogout`):**
- `child2IsSelectorWithRepeatThenLogout` — reads
  `getChildren().get(2)`. Same downstream assertions about the
  Selector's options.

**Structural — new:**
- `child1IsPostWalkSettleIdleStep` — asserts `getChildren().get(1)`
  is the `artemis.idle(SHORT_IDLE)` Step (returned as `mockIdleStep`
  by the existing Mockito stub).

**Branch 0 — regression-guard comment added:**
- `supplierBranch0_sessionExhausted_returnsFailStepAndShortCircuits`
  unchanged in assertions; new Phase 2C.x comment makes the
  "do not wrap" invariant explicit so a future refactor that wraps
  Branch 0 trips the existing `instanceof FailStep` check.

**Branch 3 — renamed + amended (was
`supplierBranch3_sessionOkAndIdleAndCowPresent_returnsBaseClickStep`):**
- `supplierBranch3_cowFound_returnsAttackOrRetrySelectorWithClickThenIdle`
  asserts:
  - result is a `Selector` instance (not the click Step directly)
  - `Selector.name()` is `"attack-or-retry"`
  - 2 options
  - option 0 is `mockClickStep` (from stubbed `artemis.click(cow, "Attack")`)
  - option 1 is `mockIdleStep` (from stubbed `artemis.idle(any)`)
  - `verify(artemis).click(cow, "Attack")` (base 2-arg overload)
  - `verify(artemis, never()).click(any, anyString, OutcomeCheck.class)` (no 3-arg)
  - `verify(artemis).idle(any(IdlePolicy.class))` (idle IS called now)

**Other branch tests (player-busy, player-null, no-cow) unchanged** —
their cases don't touch the modified tree shape.

**Mockito limitation accepted:** the existing
`when(artemis.idle(any(IdlePolicy.class))).thenReturn(mockIdleStep)`
stub returns the SAME `mockIdleStep` for the post-walk settle, the
inner-Selector idle, the Branch 1 idle, and the Branch 2 idle. Tests
cannot distinguish "which idle" by identity. Structural shape is
pinned by which-child-where assertions; this is fine.

## 6. Scope

**Modified:**
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/scripts/CowKillerScript.java`
- `runelite-client/src/test/java/net/runelite/client/plugins/recorder/scripts/CowKillerScriptPlanShapeTest.java`

**Added:**
- `docs/superpowers/plans/2026-05-24-artemis-phase-2cx-loop-tolerance.md` (this doc)

**Optional:**
- Single cross-reference line in `docs/learnings/2026-05-24-artemis-tier1-run-01.md`
  pointing to this plan doc.

**NOT touched (per scope constraints):**
- `PixelResolver`
- `HumanizedInputDispatcher`
- `ActionRequest` (no `liveTracked` for `CLICK_NPC` in this slice)
- `Artemis` interface
- `ArtemisImpl`
- `WalkStepBase`
- `RecorderPanel`, `RecorderConfig`, any UI
- Phase 1B grep gate allow-list (no new rows)

## 7. Risks (accepted for v1)

| Risk | Severity | Mitigation |
|---|---|---|
| Inner Selector hides genuine bugs (cow permanently unreachable → infinite retry) | Low — operator-supervised; bot looks busy but makes no kill progress; visible failure mode | Acceptable for v1. Phase 3 `liveTracked` reduces this directly. Could add a kill-rate floor monitor later (out of 2C.x scope). |
| Post-walk `SHORT_IDLE` insufficient when walk's dispatcher chain is unusually long | Low | Inner Selector catches the resulting `ActionTimedOut` → loop continues. Defense in depth. |
| Mockito stub returns same `mockIdleStep` for all `idle(...)` calls | None — structural pinning by position, not identity | Test design above. |
| Changing `LinearSequence` children count breaks unrelated callers | Very low | `CowKillerScript.plan()` consumed only by `RecorderPlugin.launchCowKillerPilot()` as opaque `Step`. |
| `SESSION_EXHAUSTED` accidentally wrapped in a future refactor | Medium | Branch 0 test still asserts `instanceof FailStep` directly; in-code comment at Branch 0 + Branch 3 reaffirms the do-not-wrap invariant. |

## 8. Out of scope (DEFERRED)

- Phase 3 `liveTracked` for `CLICK_NPC` in `ActionRequest`
- Phase 3 `PixelResolver` per-NPC re-projection
- Phase 1D-or-3 — Step seeing dispatcher-internal menu-miss aborts
- Phase 0B — real session budget; v1.0 lock signoff requires this
- Loot pickup, inventory-full termination, bank/GE — separate slices
- UI changes — none needed
- Grep gate allow-list — no new rows

## 9. Outcome / next

After this slice lands and is operator-approved:

1. Rebuild `:client:shadowJar` (operator-gated per
   `feedback_dont_build_client_unprompted`).
2. Kill stale RuneLite + relaunch from new jar (per Phase 2D
   rebuild-restart pattern).
3. Run Tier 1 Run 02 per Phase 2D operator protocol.
4. Fill `docs/learnings/2026-05-24-artemis-tier1-run-02.md`.

If Run 02 passes Phase 2D §7, mark Tier 1 PASSED and route per
Phase 2D §10. If F2 remains operationally noisy (still multiple
clicks per cow despite the inner Selector), evaluate Phase 3
`liveTracked` priority.
