# Artemis Phase 2 — Cow Killer Pilot (v1.0) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **Do NOT begin implementation until the operator approves this plan.**

**Goal:** Prove a real automation script can run a real loop using the Artemis v1.0 surface only — no engine bypass, no allow-list row. The pilot is "kill cows until the session budget says stop, then logout." Inventory-full termination is enabled only if optional loot pickup is included and `take(...)` is IRL-verified first; pure kill-only never fills the inventory and terminates on session-end. It is **not** "build the best cow killer."

**Architecture:** Single new script `CowKillerScript` under `recorder/scripts/`, composed via `Artemis.plan(...)` + existing engine composites (`LinearSequence` / `RepeatStep` / `Selector`) + the new `DynamicStep` primitive landing in Phase 1C. State transitions live in plan structure, not a script-side enum FSM. ArtemisImpl is constructed by `RecorderPlugin` (one-time wiring slice) so the pilot can actually run.

**Prerequisite:** Phase 1C `DynamicStep` read-to-action bridge must land first (`docs/superpowers/plans/2026-05-24-artemis-dynamic-step-bridge.md`). Without it, the body Step that does `findNpc(...) → click(NpcRef, ...)` has no engine-clean composition path — verified by reading `StateDrivenEngine.invokeOrchestration` (`:410-427`), which hardcodes the composite dispatch to `LinearSequence` / `Selector` / `RepeatStep`.

**Tech Stack:** Java 17, Artemis v1.0 (`sequence/artemis/*`), existing sequence engine + Phase 1C `DynamicStep`, `RecorderPlugin` panel button wiring. No new low-level primitives beyond `DynamicStep`.

**Scope (in):** LUMBRIDGE_COW_FIELD tile population, ArtemisImpl construction wiring (precondition), `CowKillerScript` pilot using `DynamicStep` + existing v1.0 Artemis surface, Tier 1 structural acceptance, test-profile run protocol, allow-list discipline (zero new rows).

**Scope (out):** DynamicStep itself (separate Phase 1C plan). Phase 3 PixelResolver rewrite. Click heatmap / click diversity acceptance (Tier 2). Bank cycle. GE. Production migration of `ChickenFarmV2Script` / `ChickenFarmV3Script`. Broad refactor. Anti-detection or evasion framing. `sampleNearCentroid` work. New low-level dispatcher access. Allow-list entry for the pilot. `Artemis.attack(NpcQuery)` / similar convenience wrappers (Option β was rejected in favor of DynamicStep).

---

## 1. Phase 2 goal

Build one small pilot script that proves Artemis v1.0 can run a useful real loop without reaching around the engine.

The pilot:

- walks to `NamedZone.LUMBRIDGE_COW_FIELD`
- inside a `DynamicStep` body, finds a cow via `Artemis.findNpc(NpcQuery.byName("Cow").within(N).rotation(ClosestWithSlack(2)))`
- attacks via `Artemis.click(NpcRef, "Attack")` — **base click, no `OutcomeCheck`.** `OutcomeCheck.interactingWithMe()` and `TargetAnimChanged` are unsupported in v1 — `OutcomeChecks.java:87-91` returns `OUTCOME_NOT_SUPPORTED_V1` for both. Base verb-only success per spec §11 is sufficient for Tier 1: menu verb verified pre-press + dispatcher reported completion. Tier 1 proves Artemis integration, not perfect engagement-state semantics.
- (optional) takes one cowhide / bones via `Artemis.findItem(...)` + `Artemis.take(...)` if §5's gating rule deems it ready
- re-enters the loop body each iteration via `RepeatStep(times=0)` — terminates only when the body Fails
- logs out via `Artemis.logout()` when the loop ends
- composition uses `Artemis.plan("cow-killer-v1")` + existing engine composites + `DynamicStep`
- passes the Phase 1B grep gate with **zero** new allow-list rows

The pilot proves *integration*, not *click quality*. Tier 2 (click heatmap, identity diversity, ≥30 % stdev) is deferred to post-Phase-3.

---

## 2. LUMBRIDGE_COW_FIELD tile population

**First Phase 2 task** (Slice 2A — see §9).

### Chosen bounds (proposed, conservative, marked test-profile adjustable)

East-of-Lumbridge cow field, F2P, plane 0. Rectangle:

```
x ∈ [3253, 3262]   (10 tiles wide)
y ∈ [3253, 3274]   (22 tiles tall)
plane = 0
→ 220 tiles total
```

Why this bound (single sentence): a rectangle that fully covers the fenced cow field east of the Lumbridge–Al-Kharid road; conservative on the south edge so it doesn't intrude into the chicken pen at ~y=3294 (different zone) and conservative on the east edge so it doesn't bleed into the Al-Kharid path.

**Cross-reference:** `HotColdLocation.LUMBRIDGE_COW_FIELD` is at `(3174, 3336, 0)` — the *north* Lumbridge field. The pilot uses the larger *east* field because it has ~15+ cows and is the conventional F2P training spot. The HotCold reference is unrelated; do not reuse those coords.

**Source:** in-game observation. **Not yet confirmed by a recorded trail or marker.** Slice 2A's task list includes a "verify in-game with a brief test-profile login" pre-flight before populating; see §9.

### Implementation shape

Mirrors `LUMBRIDGE_CASTLE_GROUND_FLOOR` exactly (`NamedZone.java:33-44` + `:76-89`):

- Override `tiles()` from the enum constant body.
- Static `private static final List<WorldPoint> LUMBRIDGE_COW_FIELD_TILES = buildRect(3253, 3262, 3253, 3274, 0);`
- `buildRect(...)` already exists; reuse it.

### Tests (in `NamedZoneTest.java`)

Extend the existing test class — **shared infra carve-out per `feedback_no_tests_for_bot_scripts`**, NamedZone is shared infra, not a script.

1. `lumbridgeCowFieldIsPopulatedInPhase2()` — asserts `LUMBRIDGE_COW_FIELD.tiles().size() == 220`.
2. Update `onlyLumbridgeCastleGroundFloorIsPopulatedInPhase1A4d()` → rename to `onlyExpectedZonesArePopulated()` and broaden the populated set to {castle ground floor, cow field}. All other v1.0 zones still empty.
3. `lumbridgeCowFieldTilesArePlane0AndInsideBounds()` — every tile has plane 0 and `x ∈ [3253, 3262]` and `y ∈ [3253, 3274]`.

Run: `./gradlew :client:test --tests NamedZoneTest`. All four existing tests + three new ones pass.

### Proof that `walkTo(NamedZone.LUMBRIDGE_COW_FIELD)` no longer fails `EMPTY_ZONE`

`WalkToZoneStep.REASON_EMPTY_ZONE` (`WalkToZoneStep.java:46`) is raised in `doPreFlight` when `zone.tiles().isEmpty()`. After Slice 2A:

- A targeted unit test in `WalkToZoneStepTest` (if it exists; otherwise the existing smoke harness) constructs a `WalkToZoneStep` for `LUMBRIDGE_COW_FIELD` and asserts `doPreFlight` does *not* short-circuit with `EMPTY_ZONE`.
- If no `WalkToZoneStepTest` exists yet, add one minimal assertion — this is shared-infra coverage, not script coverage.

Do **not** populate any other zone. `LUMBRIDGE_BANK`, `LUMBRIDGE_BANK_P2`, `LUMBRIDGE_CHICKEN_PEN`, `GRAND_EXCHANGE` stay empty placeholders — they land with the script migration that needs them (Phase 5+).

---

## 3. CowKillerScript shape (allowed/banned imports)

**Path:** `runelite-client/src/main/java/net/runelite/client/plugins/recorder/scripts/CowKillerScript.java`

**Allowed imports** (must be a strict subset of these — anything else is a plan violation):

- `net.runelite.client.sequence.artemis.Artemis`
- `net.runelite.client.sequence.artemis.query.NpcQuery`
- `net.runelite.client.sequence.artemis.query.ItemQuery` (only if loot included per §5)
- `net.runelite.client.sequence.artemis.query.RotationPolicy` (rotation tweak only)
- `net.runelite.client.sequence.artemis.view.NpcRef`, `view.GroundItemRef`, `view.PlayerState`, `view.InventoryView`
- `net.runelite.client.sequence.artemis.outcome.OutcomeCheck`
- `net.runelite.client.sequence.artemis.session.IdlePolicy`
- `net.runelite.client.sequence.artemis.zones.NamedZone`
- `net.runelite.client.sequence.Step`
- `net.runelite.client.sequence.composite.SequencePlanBuilder`
- `net.runelite.client.sequence.composite.RepeatStep`, `composite.Selector` (for the kill-loop body)
- `net.runelite.client.sequence.composite.DynamicStep` (Phase 1C — read-to-action bridge for the `findNpc → click` body; without it the pilot has no engine-clean composition path)
- `net.runelite.client.sequence.composite.FailStep` (Phase 1C — definitive-fail leaf used by the `DynamicStep` factory when `findNpc` returns empty)
- `net.runelite.client.plugins.recorder.session.SessionShape` (read-only — `Artemis.session()` returns this)
- `net.runelite.api.coords.WorldPoint` (only if a WorldPoint constant is needed; usually unnecessary if walking via `NamedZone`)
- `net.runelite.api.ItemID` (for `COWHIDE = 1739`, `BONES = 526`; `gameval.ItemID` does **not** contain these constants — verified via grep). Per spec §14, id constant classes are allowed.
- `java.util.*` basics
- `lombok.extern.slf4j.Slf4j` (optional — logging)

**Banned imports** (would fail Phase 1B grep gate; pilot must have **zero** allow-list rows):

- `HumanizedInputDispatcher`, `ActionRequest`, `PixelResolver`, `WindMouse`, `PressTiming`, `InputOwnership` (any `sequence.dispatch.*`)
- `SceneScanner`, `NpcSelector`, `WidgetActions`
- `BankInteraction`, `GeInteraction`, `sequence.activities.banking.*`, `sequence.activities.ge.*`
- `Navigator`, `TrailWalker`, `UniversalWalker`, anything in `recorder.walker.*` / `nav.*` / `trail.*` / `transport.*`
- Raw `net.runelite.api.NPC`, `GameObject`, `widgets.Widget`
- `clickCanvas`, `sampleNearCentroid`, `dispatcher.dispatch(`, `ActionRequest.builder(`, `new ActionRequest(`, `new NpcSelector(`
- `java.awt.Robot`, `java.awt.event.MouseEvent`
- `net.runelite.client.callback.ClientThread` (Artemis marshals internally)

**Construction surface**

Pilot takes `Artemis` (and only `Artemis`) as a constructor dep — no `Client`, no `ClientThread`, no `RecorderManager`, no engine pieces. Pattern:

```
public CowKillerScript(Artemis artemis) { this.artemis = artemis; }
public Step plan() { … returns root of composed plan … }
```

A separate panel/RecorderPlugin button calls `sequenceManager.run(script.plan())`. The plumbing for that button lives in `RecorderPlugin` / `RecorderPanel` (non-script code, not gated).

---

## 4. Pilot loop design

State machine (conceptual — implemented as plan composition, **no script-side enum FSM**):

```
START
  └─► WALK_TO_COW_FIELD            (Artemis.walkTo(NamedZone.LUMBRIDGE_COW_FIELD))
       └─► Selector(                 # so combatLoop's Failed still reaches LOGOUT
             RepeatStep("cow-killer-loop", body, times=0)
                body = DynamicStep.of("find-and-attack-cow", supplier)
                  supplier =
                    Optional<NpcRef> cow = artemis.findNpc(cowQuery);
                    if (cow.isEmpty()) return FailStep.of("NO_COW_FOUND");
                    return artemis.click(cow.get(), "Attack");
             ,
             LogoutStep                # Artemis.logout()
           )
DONE
```

(Optional-loot variant adds a second `DynamicStep` inside the `RepeatStep` body via a `LinearSequence(findAndAttack, optionalTakeLoot)` — see "OPTIONAL_TAKE_LOOT" below.)

**Why `DynamicStep` is required, not a script-local Step.** A naïve `LinearSequence(findNpc, click)` doesn't work — `findNpc(...)` returns `Optional<NpcRef>`, not a `Step`. A script-local `extends CompositeStep` doesn't work either — `StateDrivenEngine.invokeOrchestration` (`:410-427`) hardcodes the composite dispatch to `LinearSequence` / `Selector` / `RepeatStep`; unknown composites get `FinishWithFailure("unknown composite type ...")`. `DynamicStep` is the v1 primitive that bridges read-result → fresh Step at execution time. Lands in the Phase 1C prerequisite slice.

**Termination — engine-driven, not predicate-driven.** `RepeatStep` repeats infinitely while the body succeeds and terminates the moment the body returns `Completion.Failed` (`RepeatStep.java:51`). There is **no `until(...)` predicate API** in v1.0. The "until inventory.isFull() OR !session.shouldContinue()" framing is realised through the engine's natural failure paths:

| Termination trigger | How the body Fails | Active in v1 pilot? |
|---|---|---|
| `session.shouldContinue() == false` | next gameplay Step (the click inside the DynamicStep child) fails its session-gate check at `onStart` (spec §3) | **Yes — primary terminator for kill-only pilot** |
| `inventory.isFull() == true` | `take(GroundItemRef)` returns `Recovery.Abort("inventory full")` per spec §12.6 | **Only with optional loot included AND `take(...)` IRL-verified** (§5 gating) |
| `findNpc` returns empty repeatedly | `FailStep("NO_COW_FOUND")` returned by the DynamicStep factory propagates as `Recovery.Abort` per its leaf-step contract | Yes — fires only if field truly empty for an extended window |

**Pure kill-only termination is session-only.** If loot is not included (or if `take(...)` verification defers it to slice 2C.1), the pilot will continue killing until the session budget gates the next click. That's the intended Tier 1 path. Inventory-full termination is **not promised** unless the loot variant lands.

The `Selector(RepeatStep, LogoutStep)` wrap is required because `LinearSequence` short-circuits on Failed per spec §12.5 — see "Plan composition note" below.

**Plan composition note.** `LinearSequence.then(...)` short-circuits on Failed (verified `LinearSequence.onChildPopped` per spec §12.5). The kill loop ALWAYS ends with Failed (it can only terminate by failing). So the outer composition wraps `RepeatStep + LogoutStep` in a `Selector`: `Selector` tries children in order, succeeding on the first that succeeds. `RepeatStep` Failed → Selector tries `LogoutStep` → `logout()` runs and succeeds. This is the canonical v1.0 pattern.

**FIND-AND-ATTACK behavior** — the DynamicStep factory does the read **and** materializes the click Step in one closure. Each iteration of `RepeatStep`, the engine constructs a fresh `DynamicStepFrame`, re-invokes the supplier, and pushes the resulting Step as child (Phase 1C contract). If `findNpc` returns empty, the factory returns `FailStep.of("NO_COW_FOUND")` — DynamicStep propagates Failed, which fails the RepeatStep body, which closes the iteration. Next iteration the supplier is called again — engine pattern, no script-side retry logic.

**Stale-ref handling** — when `findNpc` returns a `NpcRef` and the supplier hands it to `artemis.click(...)`, the engine re-resolves at `ClickNpcStep.onStart` (spec §8). A despawned cow between find and click triggers `DiagnosticReason.STALE_REF` → `Recovery.Retry(3)` → DynamicStep frame fails, RepeatStep starts a new iteration, fresh supplier call, fresh `findNpc`. The retry budget lives at the Artemis Step level; the script does not implement its own.

**OPTIONAL_TAKE_LOOT** — see §5. If included: the DynamicStep body becomes a `LinearSequence(findAndAttackDynamicStep, optionalTakeDynamicStep)`. The `optionalTakeDynamicStep` wraps `findItem + take` similarly: `Optional<GroundItemRef> hide = artemis.findItem(...); return hide.map(artemis::take).orElseGet(() -> FailStep.of("NO_LOOT"));`. Wrapped in a `Selector(optionalTakeDynamicStep, succeedAnyway)` so missing loot doesn't fail the kill loop — but `succeedAnyway` is not yet a v1 utility (no current need for `SucceedStep`); the simpler shape is to omit the Selector and accept that "no loot this iteration" Fails the body, ending the loop. This trade-off is decided at §5's gating rule: include loot only if `take(...)` is IRL-verified AND the operator accepts session-only termination if no loot is visible on a given iteration.

**Failure handling** — every constituent Step uses Artemis defaults (§12.6). The pilot does not override `Recovery.*` from the script side; if the defaults are wrong for the cow-killer case, that's a feedback signal for Artemis (not the pilot).

---

## 5. Outcome checks

| Step | OutcomeCheck | Budget | Notes |
|---|---|---|---|
| WALK_TO_COW_FIELD | (none — `walkTo` has its own success criterion: player inside zone tiles for 2 consecutive ticks) | 60 ticks | per spec §11 |
| ATTACK_COW (`click(NpcRef, "Attack")` — base overload, **no `OutcomeCheck`**) | (none — base success: menu verb verified pre-press + dispatcher reported completion per spec §11) | 8-tick `click` timeout | `OutcomeCheck.interactingWithMe()` and `TargetAnimChanged` are unsupported in v1 — `OutcomeChecks.java:87-91` returns `OUTCOME_NOT_SUPPORTED_V1`; do not use them. The next loop iteration's `findNpc + click` is the natural feedback signal: if engagement is still in progress the next click on the same cow is either a no-op (cow still interacting) or transitions to a different cow (engagement ended, RotationPolicy picks again). |
| OPTIONAL_TAKE_LOOT (only if §5 gating includes it) | (none — `take(GroundItemRef)` succeeds when inventory gained ≥1 of `item.itemId` within 4 ticks per spec §11) | 6-tick `take` timeout (spec §11) | see §4 for the trade-off if loot is empty on a given iteration |
| LOGOUT | (none — `logout()` succeeds when `GameState == LOGIN_SCREEN` within 8 ticks) | 8 ticks | per spec §11 |

**Why base verb-only instead of `PlayerAnimChanged`** — `PlayerAnimChanged(4)` is supported (`OutcomeChecks.java:75-78`) but the cow attack-swing animation depends on the player's weapon + level, and on a one-hit kill the swing animation may not register before the cow despawns. The base verb-verified contract is more reliable for Tier 1 — it guarantees the engine accepted the click, which is all the pilot needs to prove integration. If the operator wants tighter post-click verification, `PlayerAnimChanged(4)` is a verified-supported drop-in for the next variant (slice 2C.1 or later).

**Why not `Custom(predicate)`** — supported (`OutcomeChecks.java:83-86`) but adds engine-snapshot reads inside the predicate. Tier 1 doesn't need this; defer to v1.x when a script genuinely needs a predicate-driven outcome check.

**No `OutcomeCheck.interactingWithMe()` anywhere in the pilot.** That sentinel evaluates to `OUTCOME_NOT_SUPPORTED_V1` after waiting out the budget — using it would silently introduce a 4-tick (default) stall before every attack with no engagement-started signal. Wait for v1.x to widen the Artemis read surface (per `OutcomeChecks.java:42-51` Javadoc) before reaching for it.

### Loot inclusion gating

`Artemis.take(GroundItemRef)` is wired (`ArtemisImpl.java:460`, `TakeGroundItemStep.java:39`). **Decision rule for Slice 2C:** if a one-Step smoke test (`findItem(cowhide).flatMap(Artemis::take)`) completes successfully on the test-profile session before the pilot's body is committed, include `OPTIONAL_TAKE_LOOT` in the body. Otherwise commit a kill-only pilot in 2C and add loot in a follow-up commit 2C.1 once `take` is verified. The pilot is not the venue for shaking out `take` bugs — kill-only is still a valid pilot.

---

## 6. Tier 1 acceptance criteria

Tier 1 is the structural floor. Every item below must pass before Phase 2 is declared in-flight-done. **Tier 1 can land before Phase 3.**

1. `:client:compileJava` succeeds with no warnings introduced by Phase 2 files.
2. `:client:test --tests "net.runelite.client.sequence.artemis.zones.NamedZoneTest"` passes (incl. the three new assertions from §2).
3. The pilot's plan composition passes a synthetic structural test (slice 2C, §9) — asserts `script.plan()` returns the `LinearSequence(WalkToZoneStep, Selector(RepeatStep(body=DynamicStep), LogoutStep))` tree in the expected shape **without invoking any I/O**. The `DynamicStep`'s factory is verifiable by a separate unit assertion (invoke factory with a mocked Artemis returning an empty `findNpc`, assert it returns a `FailStep` with reason `"NO_COW_FOUND"`; invoke with a present `NpcRef`, assert it returns a `ClickNpcStep` with verb `"Attack"`). Proves state-machine wiring + factory branch logic, not gameplay.
4. `./scripts/check-no-direct-engine-reaches.sh` exits 0 with **zero** allow-list rows added for `CowKillerScript.java`. Adding any of the §3 banned imports to the pilot file makes the gate exit non-zero.
5. The pilot's import list, verified by `grep -c '^import ' CowKillerScript.java`, contains only entries from §3's allowed list.
6. The pilot does not invoke any constructor of `ActionRequest`, `HumanizedInputDispatcher`, `Navigator`, `NpcSelector`, `SceneScanner`, `BankInteraction`, `GeInteraction`, `WidgetActions`, `clickCanvas`, `sampleNearCentroid` — verified by the same grep gate.
7. `Artemis.walkTo(NamedZone.LUMBRIDGE_COW_FIELD)` no longer fails with `REASON_EMPTY_ZONE`.
8. `Artemis.logout()` is the script's terminal Step. Session-gate exemption holds: when `session.shouldContinue() == false` causes the next click to fail at its onStart session check, the `RepeatStep` body's `DynamicStep` fails, the `RepeatStep` returns Failed, the wrapping `Selector` tries the next branch which is `LogoutStep`, and `Artemis.logout()` runs to completion because logout is a maintenance Step per spec §3, §12 table.
9. Stale-ref handling is exercised. A synthetic test (slice 2C) builds a deliberately-stale `NpcRef` (`observedTick = -1000`), feeds it to a click Step, asserts the Step's `check()` returns `Completion.Failed(STALE_REF)` and `onFailure(...)` returns `Recovery.Retry(3)`. This is shared-infra coverage of Artemis re-resolution, not script test.
10. Test-profile session run (§8) completes its first 30-minute window without:
    - retry storms (≥3 consecutive same-target failures on one cow)
    - stuck-state intervals ≥5 min (engine ticks without a Step transition while the script should be active)
    - any `dispatcher.dispatch` lines in the pilot's log scope (verifies the gate's runtime promise)

All ten criteria are observable signals — none are subjective. Per `feedback_api_spec_review_checklist`, "test-profile session ran for 2h" is *the platform*, not an acceptance signal.

---

## 7. Tier 2 acceptance criteria — explicitly deferred

**Tier 2 cannot land in Phase 2 because it requires Phase 3.** It does not block Phase 2 sign-off.

Deferred items, each pinned to its real phase:

| Item | Why deferred | Phase that enables it |
|---|---|---|
| Click heatmap on a single cow over 100+ cycles | Heatmap needs Phase 3's `sampleInsideShape` to be meaningful; under current `sampleNearCentroid` the heatmap will always be the 12-px centroid cluster | Phase 3 (PixelResolver §10 rewrite) |
| Click-pixel stdev ≥ 30 % of model's larger dimension | Same — pre-Phase-3 baseline is the ~5 % centroid cluster, by construction | Phase 3 |
| Cow identity histogram across the run (distinct NPC indices ≈ field population) | Needs both `RotationPolicy.ClosestWithSlack(2)` working *and* full-shape sampling so different cows aren't filtered out by occlusion | Phase 3 + dashboard (Phase 7) |
| Shape-inside click sampling validation | Lives inside `PixelResolver` — Artemis is transparent to it | Phase 3 |
| Real behaviour analytics dashboard | Consumer of `StepEvent` stream; Phase 2 emits, Phase 7 reads | Phase 7 |
| Bank-booth and NamedZone-tile rotation observable across cycles | v1.0 has no bank surface; rotation is observable for the cow field walk-to once Phase 7 dashboard reads it | Phase 5 (bank in v1.1) + Phase 7 (dashboard) |

**Phasing summary, plain text:**

```
Phase 2 Tier 1     → proves Artemis integration is usable
Phase 3            → fixes click resolution (sampleInsideShape)
Phase 2 Tier 2 /  → proves click-quality metrics post-Phase-3
  later
```

Phase 2 is **complete only when both tiers pass** per spec §19, but Tier 2 sign-off is administratively deferred until Phase 3 lands. Tier 1 sign-off is its own milestone.

---

## 8. Test-profile session protocol

This is a **controlled validation**, not a production migration. The pilot runs only against `.runelite-test/` (test-profile dir per Phase 0B), never against a live account.

### Run length target

30 minutes for the first acceptance run. 2 hours for the v1.0-lock signal per spec §18.

### Pre-flight

1. Confirm test profile in use: `RecorderPanel` shows the test-profile indicator (Phase 0B wiring).
2. `git status` clean and on the Phase 2 commit chain.
3. Pilot account standing in or near Lumbridge (test profile only; do not piggyback on production accounts per `project_f2p_two_account_strategy`).
4. Inventory pre-state: empty.

### What to inspect

| Source | What to look for | Pass criterion |
|---|---|---|
| `~/.runelite/logs/client.log` (test-profile redirect) tag `[artemis]`, `[cow-killer-v1]`, `[walk-to-zone]` | Step start / check-completion events, retry counts | Step state transitions every <5 min while active; retry storms zero |
| Test-profile session JSON (per spec §13 / Phase 0A.3 `StepEvent`) | One `start` + one `check-completion` event per dispatched Step; payload includes target ref + verb + click pixel | Every dispatched Step emits both events; zero unwired Steps |
| Grep `client.log` for `DiagnosticReason.STALE_REF` | Non-zero count is healthy (re-resolution is working); a sudden zero usually means re-resolution isn't running | Non-zero count, single-digit per 30 min |
| Grep for `dispatcher.dispatch` / `clickCanvas` / `sampleNearCentroid` in script-scope log lines | Should be empty — the pilot must not reach these directly | Zero hits |

### What stops the test

- Player dies (unlikely; cows are level 2). If it happens, capture the log and stop.
- `client.log` shows ≥3 consecutive `Recovery.Abort` from one Step. Investigation needed — not a quiet retry.
- Stuck-state ≥5 min while pilot should be active. Investigation needed.
- Session budget exhausted and `logout()` did **not** fire — terminal Step missing or `Selector(RepeatStep, LogoutStep)` wrapping broken.
- Any `dispatcher.dispatch` log line attributable to the pilot's scope. Hard fail.

### What success looks like

- Pilot started, walked to cow field, killed cows in a continuous loop until the session budget gated the next click (or, if loot included, until inventory filled), then logged out cleanly.
- `client.getGameState() == LOGIN_SCREEN` at the end of the run.
- All ten Tier 1 criteria checked off.

### Known limitations acceptable pre-Phase-3

- Click pixels cluster near the cow model centroid (~5 % stdev). This is the pre-Phase-3 baseline; Tier 2 measures the improvement after Phase 3.
- Cow identity skew (one or two cow indices dominate the pick distribution) is possible if `ClosestWithSlack(2)` doesn't spread enough on a sparse field. Not a Phase 2 blocker.
- The pilot does not handle aggressive PvP, other players hooking cows mid-attack, or bank-stuff cycles (v1.1+ scope).

---

## 9. Implementation slicing

Four small commits **after** the Phase 1C `DynamicStep` slice lands. Each compiles and tests pass before the next starts. Per `feedback_one_at_a_time_irl_test`, no big-bang.

### Prerequisite — Phase 1C `DynamicStep` slice (separate plan)

Do **not** start Slice 2A until Phase 1C is on master and green:
- `docs/superpowers/plans/2026-05-24-artemis-dynamic-step-bridge.md` is approved and implemented.
- `DynamicStep`, `DynamicStepFrame`, `FailStep` exist under `sequence/composite/`.
- `StateDrivenEngine` has the three new branches (makeFrame, pushFirstChildIfComposite, invokeOrchestration).
- Full `:client:test` suite green.
- Grep gate green.

Phase 2 references DynamicStep + FailStep as if they exist; without 1C, slice 2C will not compile.

### Slice 2A — LUMBRIDGE_COW_FIELD zone tiles + tests

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/sequence/artemis/zones/NamedZone.java`
- Modify: `runelite-client/src/test/java/net/runelite/client/sequence/artemis/zones/NamedZoneTest.java`

Steps:

- [ ] **Step 1: Verify bounds with a brief in-game test-profile check** (operator-driven; 5 min). Walk into the field, sample 3-4 corner tiles via the click-inspector, confirm `x ∈ [3253, 3262]` and `y ∈ [3253, 3274]` covers the visible cow population.
- [ ] **Step 2: Override `tiles()` on `LUMBRIDGE_COW_FIELD`** in `NamedZone.java` to return `LUMBRIDGE_COW_FIELD_TILES`. Add the static field below `LUMBRIDGE_CASTLE_GROUND_FLOOR_TILES`, populated via `buildRect(3253, 3262, 3253, 3274, 0)`. **Do not touch any other enum constant.**
- [ ] **Step 3: Update class Javadoc** — move `LUMBRIDGE_COW_FIELD` from "Pending populations" to "populated", set the descriptor to "220-tile 10×22 rectangle covering the east-of-Lumbridge cow field; Phase 2 pilot target."
- [ ] **Step 4: Update / extend `NamedZoneTest`** with the three new assertions in §2 (populated count, plane+bounds, broaden the populated-set test).
- [ ] **Step 5: Add the WalkToZoneStep `EMPTY_ZONE` non-firing assertion** as described in §2.
- [ ] **Step 6: Run** `./gradlew :client:compileJava` then `:client:test --tests NamedZoneTest`. Both pass.
- [ ] **Step 7: Run the grep gate** `./scripts/check-no-direct-engine-reaches.sh`. Exits 0 (unchanged — NamedZone is engine code, not script code).
- [ ] **Step 8: Commit.** Message: `feat(artemis): Phase 2A — LUMBRIDGE_COW_FIELD tile population (220-tile 10×22 rectangle)`

### Slice 2B — ArtemisImpl construction wiring + CowKillerScript skeleton + grep-gate proof

ArtemisImpl is constructed by `RecorderPlugin` for the first time in this slice. The skeleton is **empty** of behavior — its purpose is to prove the script file compiles and passes the grep gate before any logic lands. Per `feedback_api_spec_review_checklist`, "enforcement lands with the first thing it protects."

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPlugin.java` — instantiate `ArtemisImpl` once via `ArtemisDeps` (deps already exist as a record) and expose it for scripts.
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPanel.java` — add a "Cow Killer (test)" button gated to test-profile.
- Create: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/scripts/CowKillerScript.java` — class skeleton: constructor takes `Artemis`, `plan()` method returns `null` or a trivial throw-stub.

Steps:

- [ ] **Step 1: Build `ArtemisDeps`** in `RecorderPlugin.startUp()` from existing fields (`client`, `clientThread`, `itemManager`, `RecorderManager`, `AccountRng`, `SessionShape`, `Navigator`, `LogoutAction`). Instantiate `ArtemisImpl artemis = new ArtemisImpl(deps);`. Hold it as a private field.
- [ ] **Step 2: Add a panel button** wired to `new CowKillerScript(artemis).plan()` → `sequenceManager.run(...)`. Button only enabled in test-profile mode.
- [ ] **Step 3: Write the skeleton** — minimal class with `Artemis` field + `Step plan()` that throws `UnsupportedOperationException("Phase 2C wires the loop")`. Import only `Artemis` and `Step` from §3's allowed list.
- [ ] **Step 4: Run** `:client:compileJava`. Passes.
- [ ] **Step 5: Run** `./scripts/check-no-direct-engine-reaches.sh`. Exits 0; no allow-list row added for `CowKillerScript.java`.
- [ ] **Step 6: Deliberately add a banned import** (e.g., `import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;`) to `CowKillerScript.java`. Re-run the gate. Exits 1 with the FAIL hint line. **Remove the deliberate import before committing.**
- [ ] **Step 7: Commit.** Message: `feat(artemis): Phase 2B — ArtemisImpl wired in RecorderPlugin; CowKillerScript skeleton; grep gate green with zero allow-list rows`

### Slice 2C — Pilot loop using Artemis only + structural test

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/scripts/CowKillerScript.java` — implement `plan()` per §4.
- Create: `runelite-client/src/test/java/net/runelite/client/plugins/recorder/scripts/CowKillerScriptPlanShapeTest.java` — synthetic structural test (shared-infra carve-out — it tests Artemis integration shape, not gameplay).

Steps:

- [ ] **Step 1: Implement `plan()`** as `LinearSequence(walkTo, Selector(RepeatStep(body=DynamicStep, times=0), Logout))` per §4. The `DynamicStep` factory closes over `artemis`, calls `findNpc(cowQuery)`, returns `FailStep.of("NO_COW_FOUND")` on empty or `artemis.click(cow.get(), "Attack")` (base verb-only — **no `OutcomeCheck`**) on present. Honor the loot-inclusion gating rule in §5 — kill-only pilot if `take` is not yet IRL-verified.
- [ ] **Step 2: Write the structural test.** Build a `CowKillerScript` against a minimal `Artemis` test fake. Call `script.plan()`. Walk the returned tree and assert: top-level is `LinearSequence`, child 0 is a `WalkToZoneStep` with `zone == LUMBRIDGE_COW_FIELD`, child 1 is a `Selector` whose children are (`RepeatStep` with `times == 0`, `LogoutStep`). The `RepeatStep`'s body is a `DynamicStep` with `name == "find-and-attack-cow"`. **Factory branch test (separate assertion):** call the DynamicStep's factory directly with a mock Artemis (a) returning `Optional.empty()` from `findNpc` — assert factory returns a `FailStep` with reason `"NO_COW_FOUND"`; (b) returning a present `NpcRef` — assert factory returns a `ClickNpcStep` whose verb is `"Attack"` and whose OutcomeCheck field is null/absent (base overload). No I/O. No game state.
- [ ] **Step 3: Write the stale-ref test** per §6 criterion 9.
- [ ] **Step 4: Run** `:client:compileJava` then `:client:test --tests CowKillerScriptPlanShapeTest`. Both pass.
- [ ] **Step 5: Run** `./scripts/check-no-direct-engine-reaches.sh`. Exits 0, zero allow-list rows.
- [ ] **Step 6: Re-grep** `CowKillerScript.java` imports against §3's allowed list. Every import is in the list.
- [ ] **Step 7: Commit.** Message: `feat(artemis): Phase 2C — CowKillerScript pilot loop (Artemis-only, kill-until-full-then-logout)`

### Slice 2D — Test-profile session protocol notes + acceptance checklist

**Files:**
- Create: `docs/learnings/2026-05-24-artemis-phase-2-pilot-session-protocol.md` — operator-facing playbook mirroring §8 with concrete log greps and the Tier 1 acceptance checklist as a markdown checkbox list.

Steps:

- [ ] **Step 1: Write the playbook** with: pre-flight checklist, log greps (verbatim shell commands), Tier 1 acceptance checkbox list, stop-the-test triggers, the §7 deferred-to-Phase-3 note.
- [ ] **Step 2: Cross-link** from the Phase 2 plan (this doc) + spec §19 Phase 2 section.
- [ ] **Step 3: Commit.** Message: `docs(artemis): Phase 2D — test-profile session protocol for cow killer pilot`
- [ ] **Step 4: Operator runs the 30-min test-profile session** per §8. Records results in the playbook (one paragraph per Tier 1 criterion).
- [ ] **Step 5: Sign-off.** If all 10 Tier 1 criteria pass, Phase 2 Tier 1 is closed. Tier 2 awaits Phase 3.

---

## 10. Final output (proposed files, classes, state machine, methods, tests, criteria, risks, deferred items, commits)

### Proposed files

**Create:**
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/scripts/CowKillerScript.java`
- `runelite-client/src/test/java/net/runelite/client/plugins/recorder/scripts/CowKillerScriptPlanShapeTest.java`
- `docs/learnings/2026-05-24-artemis-phase-2-pilot-session-protocol.md`

**Modify:**
- `runelite-client/src/main/java/net/runelite/client/sequence/artemis/zones/NamedZone.java`
- `runelite-client/src/test/java/net/runelite/client/sequence/artemis/zones/NamedZoneTest.java`
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPlugin.java` (one-time ArtemisImpl wiring)
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPanel.java` (test-profile-gated button)

No edits to existing scripts. No edits to `Artemis.java`, `ArtemisImpl.java`, query records, view records, or Step subclasses — the pilot is a *consumer*, not an extension.

### Exact script class name

`net.runelite.client.plugins.recorder.scripts.CowKillerScript`

### State machine (mapped to plan composition)

```
LinearSequence("cow-killer-v1")
 ├─ WalkToZoneStep(LUMBRIDGE_COW_FIELD)              # WALK_TO_COW_FIELD
 └─ Selector(                                        # so combatLoop Failed still runs LOGOUT
     ├─ RepeatStep("cow-killer-loop", body, times=0) # combatLoop — fails when body Fails
     │    body = DynamicStep.of("find-and-attack-cow", () -> {
     │             Optional<NpcRef> cow = artemis.findNpc(cowQuery);
     │             if (cow.isEmpty()) return FailStep.of("NO_COW_FOUND");
     │             return artemis.click(cow.get(), "Attack"); // base click — no OutcomeCheck
     │           })
     ├─ LogoutStep                                   # runs when combatLoop returns Failed
   )
```

(Loot variant — only if §5 gating includes it — replaces `body = DynamicStep.of(...)` with `body = LinearSequence(findAndAttackDynamicStep, optionalTakeDynamicStep)`.)

### Artemis methods used

`findNpc`, `findItem` (gated), `session`, `walkTo(NamedZone)`, `click(NpcRef, "Attack")` (base overload — no OutcomeCheck), `take(GroundItemRef)` (gated), `logout()`, `plan(String)`.

Methods **not** used in this pilot: `findObject`, `findWidget`, `walkTo(WorldPoint)`, `click(GameObjRef, ...)`, `click(WidgetRef, ...)`, `click(*, OutcomeCheck)` overloads (skipped — `interactingWithMe` unsupported per `OutcomeChecks.java:87-91`; `PlayerAnimChanged` deferred to a later variant), `useOn(...)`, `idle(IdlePolicy)`, `inventory()` (only relevant if loot gated in), `player()` (no script-side need in kill-only). They land with later pilots / migrations.

### Tests

1. `NamedZoneTest.lumbridgeCowFieldIsPopulatedInPhase2()`
2. `NamedZoneTest.onlyExpectedZonesArePopulated()` (renamed + broadened)
3. `NamedZoneTest.lumbridgeCowFieldTilesArePlane0AndInsideBounds()`
4. `WalkToZoneStepTest` (or smoke): `LUMBRIDGE_COW_FIELD` does not trigger `REASON_EMPTY_ZONE`.
5. `CowKillerScriptPlanShapeTest` — structural assertion of `plan()` tree shape.
6. Stale-ref test per §6 criterion 9 (lives in an existing Artemis test class or a new `ArtemisStaleRefRetryTest`).

**No unit tests for the script's gameplay logic** — per `feedback_no_tests_for_bot_scripts`, scripts get manual in-game verification only. Shared-infra coverage (NamedZone, plan-shape, stale-ref retry) is the carve-out.

### Acceptance criteria

See §6 (Tier 1, 10 items) and §7 (Tier 2, deferred to Phase 3 + Phase 7).

### Risks

| Risk | Probability | Mitigation |
|---|---|---|
| Phase 1C `DynamicStep` slice slips or lands with a bug | Medium | Phase 2 is explicitly gated on Phase 1C landing green; Slice 2C will fail to compile without it. Plan-doc dependency is explicit (§9 prerequisite section). |
| Proposed cow field bounds wrong on one edge | Medium | Slice 2A includes an operator in-game spot-check before the rectangle is committed; bounds are easy to adjust pre-commit |
| `Artemis.take(GroundItemRef)` has a latent wiring bug not yet hit | Low (Step exists since Phase 1A.3) | Loot is gated per §5; if `take` misbehaves, ship kill-only pilot, defer loot to 2C.1 |
| `RecorderPlugin` ArtemisImpl wiring breaks a non-pilot path | Low | Wiring adds an instance + a panel button; no existing field is modified |
| Cows respawn slowly and the supplier returns `FailStep` repeatedly | Low (east field has 15+) | Each Failed iteration is a fresh RepeatStep iteration — the supplier is invoked again next tick; the natural recovery loop. No script-side retry needed. |
| Base click overload "silently succeeds" when engine accepted the click but no actual engagement followed (e.g., cow ran out of range) | Medium | Next iteration's `findNpc` either picks a different cow or the same one — no stuck state. Tier 1 criterion 10 catches retry storms / stuck states. Promoting to `PlayerAnimChanged(4)` is a follow-up if observed. |
| Test-profile session bumps into the daily-budget cap (Phase 0B) before 30 min | Low | First run is well under any sane budget; if budget too tight, raise via test-profile config (not real-account) |
| Click resolution still uses `sampleNearCentroid` (banned for production per CLAUDE.md §10) under the hood | Certain | This is the Tier 2 deferred concern, called out in §7; Tier 1 does not gate on click diversity |
| Operator confuses east and north Lumbridge cow fields | Low | §2 explicitly cites both coordinates and picks east; HotColdLocation cross-ref noted |

### What is explicitly deferred

- Phase 3 PixelResolver §10 rewrite (`sampleInsideShape`, UI occlusion mask, kill `sampleNearCentroid`)
- Click heatmap acceptance (Tier 2)
- Cow identity histogram dashboard
- Bank cycle (v1.1 + Phase 5)
- GE (v1.2)
- `ChickenFarmV2Script` / `ChickenFarmV3Script` deletion (Phase 5, ChickenFarmV4)
- All other legacy script migrations (Phase 6)
- Phase 4 CI / pre-commit hardening of the grep gate
- Phase 7 diversity dashboard consuming `StepEvent`
- `IdlePolicy.NATURAL_BREAK` / `MEAL` with logout-and-re-login semantics (v1.3)
- `confirmMakeAll`, `obstacleKeyConfirm` typed Steps (v1.1, v1.x)
- Anti-detection / mimicry / evasion framing — pilot's framing is route robustness + recovery from uncertain input per `feedback_no_evasion_framing`
- `Artemis.attack(NpcQuery)` / `findAndClick` / similar convenience wrappers (Option β from review — rejected in favor of generic `DynamicStep` primitive)
- `OutcomeCheck.PlayerAnimChanged` use in the kill loop (deferred — `PlayerAnimChanged(4)` is supported but adds variable behavior on one-hit cow deaths; revisit if base-click "silently succeeds" risk in §10 risks table fires during the 30-min run)

### Commit slicing (recap from §9)

**Prerequisite:** Phase 1C `DynamicStep` slice (separate plan + separate commits). Must land on master green before any Phase 2 slice.

1. **2A** — `feat(artemis): Phase 2A — LUMBRIDGE_COW_FIELD tile population (220-tile 10×22 rectangle)`
2. **2B** — `feat(artemis): Phase 2B — ArtemisImpl wired in RecorderPlugin; CowKillerScript skeleton; grep gate green with zero allow-list rows`
3. **2C** — `feat(artemis): Phase 2C — CowKillerScript pilot loop (Artemis + DynamicStep, kill-only base-click)`
4. **2D** — `docs(artemis): Phase 2D — test-profile session protocol for cow killer pilot`

(Optional follow-up — only if §5 gating defers loot:)
- **2C.1** — `feat(artemis): Phase 2C.1 — cow killer pilot adds loot pickup (Artemis.take, gated on take verification)`

---

## Stop and wait for approval

This plan is **review-only**. Do not begin Slice 2A until the operator green-lights it.

The biggest rule for Phase 2, restated for the implementer:

```
The pilot is not "make the best cow killer."
The pilot is "prove a real script can use Artemis without bypassing it."
```

If during implementation any temptation arises to import a banned target — direct `SceneScanner` access, `dispatcher.dispatch(...)` for a one-off, a `clickCanvas` shortcut — **stop immediately**. The right answer is to extend Artemis in a later phase, not to grant the pilot an exemption. The pilot landing with **zero** new allow-list rows is the whole point.

---

## Self-review checklist (against `feedback_api_spec_review_checklist` memory)

- [x] **Internal contradictions** — none. Tier 1 explicitly does not require click diversity; Tier 2 explicitly does and is deferred to Phase 3.
- [x] **v1 scope respected** — only v1.0 Artemis surface used; no `openBank`, `openGe`, `confirmMakeAll`, `obstacleKeyConfirm`, `idle(NATURAL_BREAK)`.
- [x] **Java package = file path** — `net.runelite.client.plugins.recorder.scripts.CowKillerScript` lives at `recorder/scripts/CowKillerScript.java`.
- [x] **No generic success contract** — every Step's success criterion is named and tied to spec §11.
- [x] **Session-gate exemptions** — `logout()` runs even when `session.shouldContinue() == false`, per spec §3 / §12 maintenance carve-out. Stated in §6 criterion 8.
- [x] **No stale references in plan** — every file path verified before writing; `WalkToZoneStep.REASON_EMPTY_ZONE` checked at `WalkToZoneStep.java:46`; `OutcomeCheck.interactingWithMe()` factory checked at `OutcomeCheck.java:43-46` AND verified unsupported in v1 at `OutcomeChecks.java:87-91` — pilot does NOT use it. `RepeatStep` API verified at `RepeatStep.java:35` (no `until(...)` predicate); engine composite dispatch verified hardcoded at `StateDrivenEngine.java:410-427` (motivates the Phase 1C `DynamicStep` prerequisite).
- [x] **No untyped generics** — all types named.
- [x] **No new composite actions** — pilot uses existing `LinearSequence`, `RepeatStep`, `Selector`, plus the Phase 1C `DynamicStep`. `DynamicStep` is the prerequisite-slice primitive, not a Phase 2 invention.
- [x] **Login deferred** — pilot ends with `logout()`; no re-login.
- [x] **Import allow-list** — §3 enumerates allowed and banned; Tier 1 criterion 4 requires zero allow-list rows.
- [x] **Audit before code** — the plan reads the existing files (NamedZone, NpcQuery, OutcomeCheck, IdlePolicy, ArtemisImpl, WalkToZoneStep, allowlist.tsv) before proposing changes.
- [x] **Embed roadmap, not link** — Tier 2 deferral table embedded inline in §7.
- [x] **Split prereqs from polish** — Slice 2A (zone tiles) and 2B (wiring + skeleton) land before 2C (loop); 2D (docs) lands last.
- [x] **Enforcement lands with the first thing it protects** — Phase 1B grep gate is already live; Slice 2B exercises it deliberately with the planted-banned-import test before removal.
- [x] **Observable signals, not durations** — Tier 1 criteria are signal-based (zero allow-list rows, zero retry storms, Step state transitions every <5 min, `GameState == LOGIN_SCREEN` at end); 30-min run length is the *platform*, not a signal.
- [x] **Apply existing memories at write time** — applied: `feedback_compact_plans` (~660 lines), `feedback_no_tests_for_bot_scripts` (script gets no unit tests; shared infra does), `feedback_no_evasion_framing`, `feedback_one_at_a_time_irl_test` (slice 2A → 2B → 2C → 2D), `feedback_verify_trail_filenames` (no trail files referenced; bounds derived from in-game observation + cross-ref), `feedback_dont_build_client_unprompted` (plan does not request a build; operator runs `:client:compileJava` per slice), `feedback_no_convenience_in_architecture` (no script-side shortcuts), `feedback_api_spec_review_checklist` (this checklist).
- [x] **Compact** — within `feedback_compact_plans` 300-700 budget.
