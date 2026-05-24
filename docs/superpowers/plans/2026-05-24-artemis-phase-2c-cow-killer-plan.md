# Artemis Phase 2C — Cow Killer Plan Implementation (Pre-flight)

> **For agentic workers:** This is a decision-and-design plan only. **Do not begin implementation until the operator approves it.** Implementation, when approved, follows `superpowers:executing-plans` with the TDD discipline locked in §6.

**Goal:** Implement `CowKillerScript.plan()` so it returns a real composed `Step` tree using Artemis + `DynamicStep` + `FailStep` only. Zero allow-list rows. The pilot becomes runnable end-to-end through the launch path 2B.1.b landed — operator clicks Start (once 2C.1 adds the button), engine ticks, cows die.

**Architecture:** Replace `CowKillerScript.plan()`'s `UnsupportedOperationException` with `LinearSequence("cow-killer-v1") → [walkTo(LUMBRIDGE_COW_FIELD), Selector(RepeatStep(body=DynamicStep, times=0), logout())]`. The `DynamicStep` factory has **four branches**, in order: (0) if `session.shouldContinue()` is false → return `FailStep.of("SESSION_EXHAUSTED")` — the body's one true terminal failure, propagates to RepeatStep → Selector → logout; (1) if `player.idle()` is false → return `artemis.idle(short IdlePolicy)` so the body yields while combat is in progress; (2) if `findNpc` returns empty → return `artemis.idle(short IdlePolicy)` so the body retries without terminating (cows respawn); (3) otherwise return `artemis.click(npc, "Attack")` (base verb-only — no OutcomeCheck per spec §11 + `OutcomeChecks.java:87-91`). **Branch 0 must run first**: `IdleStep` is a maintenance Step that bypasses the session gate (`IdleStep.java:23-25`), so without the explicit session check the loop would idle forever after Phase 0B lands real budgets. The busy-state gate (branch 1) is the spam-prevention fix: without it, `RepeatStep(times=0)` would spam-click while combat is in progress, since the base click Step Succeeds as soon as the dispatcher reports completion (spec §11), not when the cow dies. Structural test verifies the four-branch factory + tree shape via existing composite getters.

**Tech Stack:** Java 17, Artemis v1.0 (incl. `IdleStep` via `artemis.idle(IdlePolicy)`), `DynamicStep` + `FailStep` (Phase 1C — `FailStep` re-used for the SESSION_EXHAUSTED terminator), `LinearSequence` + `Selector` + `RepeatStep` (existing composites). No new types, no new packages, no new tests beyond a single structural test.

**Scope (in):** `CowKillerScript.plan()` body + a structural test under `recorder/scripts/` (shared-infra carve-out per `feedback_no_tests_for_bot_scripts` — the test asserts Artemis integration shape, not gameplay).

**Scope (out):** UI button (2C.1). `RecorderConfig` flag (2C.1). Loot (Phase 2C.x optional follow-up once `take(...)` IRL-verified). Bank cycle (v1.1). Real session budget (Phase 0B). Anything touching the launch path (2B.1.b landed it; 2C does not modify it).

---

## 1. Plan shape — exact tree

```
LinearSequence("cow-killer-v1")
 ├─ child 0: WalkToZoneStep (via artemis.walkTo(NamedZone.LUMBRIDGE_COW_FIELD))
 └─ child 1: Selector("cow-killer-after-walk")
     ├─ option 0: RepeatStep("cow-killer-loop", body=DynamicStep, times=0)
     │      body: DynamicStep.of("cow-killer-tick", () -> {
     │                // Branch 0 — session terminator. MUST run FIRST.
     │                // IdleStep bypasses the session gate (it's a
     │                // maintenance Step at IdleStep.java:23-25), so
     │                // branches 1 and 2 would idle forever after a
     │                // real budget lands in Phase 0B. The explicit
     │                // check here is the only path that lets the body
     │                // Fail terminally and reach the Selector's
     │                // logout branch.
     │                if (!artemis.session().shouldContinue()) {
     │                    return FailStep.of("SESSION_EXHAUSTED");
     │                }
     │                // Branch 1 — busy gate. PlayerState.idle() is the
     │                // Artemis "standing still + no interacting target"
     │                // approximation (PlayerState.java:19-24). While the
     │                // player is mid-attack, mid-walk, mid-anything,
     │                // yield without dispatching a fresh click.
     │                PlayerState player = artemis.player();
     │                if (player == null || !player.idle()) {
     │                    return artemis.idle(SHORT_IDLE);
     │                }
     │                // Branch 2 — no cow available. Don't terminate;
     │                // cows respawn, other players may be holding ours,
     │                // a tick-timing read may miss. Yield + retry.
     │                Optional<NpcRef> cow = artemis.findNpc(cowQuery);
     │                if (cow.isEmpty()) {
     │                    return artemis.idle(SHORT_IDLE);
     │                }
     │                // Branch 3 — engaged-free + cow found. Attack.
     │                return artemis.click(cow.get(), "Attack");  // BASE click
     │              })
     └─ option 1: LogoutStep (via artemis.logout())
```

`SHORT_IDLE` is a `static final IdlePolicy(600, 1200, false)` — 1 to 2 game ticks (engine `TICK_MS = 600` per `IdleStep.java:49`). Long enough that `RepeatStep` doesn't burn CPU between iterations; short enough that combat-end detection is timely.

Construction via `artemis.plan("cow-killer-v1").then(walkStep).then(selector).root()` — uses `Artemis.plan(String)` (Phase 1A.4e), so the outer `LinearSequence` is built by `SequencePlanBuilderImpl` and the rejection of same-instance Step re-adds (`SequencePlanBuilderImpl.java:49-56`) applies for free.

The inner `Selector` is constructed directly (`new Selector(name).option(repeat).option(logout)`) since `SequencePlanBuilder` doesn't expose a Selector builder. **No new builder helper.**

---

## 2. Repeat / Selector / DynamicStep behavior — verified

All claims below grounded in code read during this pre-flight, file:line cited.

| Behavior | Verified at | Confirmed? |
|---|---|---|
| `RepeatStep(name, body, 0)` repeats infinitely **until body returns `Completion.Failed`** | `RepeatStep.java:51` — `if (status instanceof Completion.Failed f) return new FinishWithFailure(f.reason());` | ✅ |
| Selector returns `FinishWithSuccess` on first child that Succeeds | `Selector.java:51` — `if (status instanceof Completion.Succeeded sc) return new FinishWithSuccess(sc.reason());` | ✅ |
| Selector tries next child when previous Fails (with `canStart` gating) | `Selector.java:53-58` | ✅ |
| LinearSequence short-circuits on Failed | `LinearSequence.java:72` — `if (status instanceof Completion.Failed f) return new FinishWithFailure(f.reason());` | ✅ — but Selector's wrap absorbs the RepeatStep's Failed so the outer LinearSequence never sees one |
| `DynamicStep` propagates child Failed as `FinishWithFailure` | `DynamicStep.java:88-101` (Phase 1C.2) | ✅ |
| `FailStep.check()` returns `Completion.Failed(reason)` on first call; `name()` == reason | `FailStep.java:96-99, 55-61` (Phase 1C.1) | ✅ — used by Branch 0 SESSION_EXHAUSTED terminator |
| `artemis.session()` returns `SessionShape`; `SessionShape.shouldContinue()` exists | `Artemis.java:73` + `SessionShape.java:58-…` (Phase 0A.2) | ✅ |
| `IdleStep` accepts arbitrary durations (any `IdlePolicy(min, max, false)` with `0 <= min <= max`) | `IdleStep.java:74-79` | ✅ |
| `IdleStep` is a maintenance Step — bypasses session gate, doesn't dispatch input, doesn't block worker threads | `IdleStep.java:23-25` + `:19-21` | ⚠ — correct for "yield while busy" but **bypasses session gate**, which is exactly why Branch 0 must check `shouldContinue()` explicitly before falling into Branches 1-3. |
| Engine `TICK_MS == 600` — `IdlePolicy(600, 1200)` = 1-2 game ticks | `IdleStep.java:49` | ✅ |
| `PlayerState.idle()` is "no animation AND no interacting target" v1 approximation | `PlayerState.java:19-24` Javadoc | ✅ |
| `artemis.player()` returns `PlayerState` (may return `null` pre-login) | `Artemis.java:71` + `PlayerState.java:28` | ✅ |
| `artemis.idle(IdlePolicy)` returns a Step | `Artemis.java:116` + `ArtemisImpl.java:490` | ✅ |
| `NpcQuery` name match uses `equalsIgnoreCase` — does NOT catch "Cow calf" | `ArtemisImpl.java:514` — `q.name().equalsIgnoreCase(name)` | ✅ |
| `NpcQuery.requireUnengaged()` filters out cows another player is attacking | `ArtemisImpl.java:539-546` — checks `npc.getInteracting() instanceof Player` and != self | ✅ |
| `artemis.click(NpcRef, "Attack")` base overload returns Step (no OutcomeCheck) | `Artemis.java:84` + `ArtemisImpl.java:424` | ✅ |
| `artemis.logout()` returns a Step (`LogoutStep`) | Verified inline — Phase 1A.4b | ✅ |

**End-to-end trace** for the canonical Tier 1 flow (operator Stops to terminate):

```
1. plan() returns LinearSequence (root).
2. Engine pushes LinearSequence frame, pushes child 0 (WalkToZoneStep).
3. WalkToZoneStep arrives at LUMBRIDGE_COW_FIELD → Succeeded.
4. LinearSequence advances to child 1 (Selector), engine pushes its frame
   and the first eligible option (RepeatStep).
5. RepeatStep body fires: DynamicStep.supplier evaluates.
   - artemis.player() → PlayerState with idle == true (just arrived)
   - artemis.findNpc → Optional.of(cow)
   - returns artemis.click(cow, "Attack")
   - engine pushes ClickNpcStep → click dispatched, dispatcher
     reports completion → Succeeded (verb verified + dispatched).
6. DynamicStep child Succeeded → DynamicStep returns FinishWithSuccess →
   engine pops DynamicStep frame, RepeatStep starts next iteration.
7. Next iteration's supplier evaluates:
   - artemis.player() → PlayerState with idle == false (player is now
     mid-attack: walking toward cow, swinging, etc.)
   - returns artemis.idle(SHORT_IDLE)
   - engine pushes IdleStep → sleeps 1-2 ticks → Succeeded.
8. Loop iterates between idle-wait (when busy) and click (when idle).
   In practice the loop is mostly idle steps; the click fires only
   when combat ends and a new cow is needed.
9. Steps 5-8 repeat indefinitely with no natural termination in 2C
   (Long.MAX_VALUE session, no loot, idle on no-cow).
10. Operator triggers Stop → stopCowKillerPilot() → SequenceManager.stop()
    → engine halts mid-step. RepeatStep never returns Failed; Selector
    never reorchestrates; logout() does NOT fire. See §3 for the honest
    documentation of this gap.
```

**Critical correctness property:** the click Step is dispatched ONLY when `player.idle()` is true. The engine never sees back-to-back clicks while the player is still resolving the previous one. The base-click `Succeeded` (which fires on dispatcher complete, not on combat end) no longer causes spam because the next iteration's supplier sees `player.idle() == false` and yields.

---

## 3. Session behavior — honest 2C limitations

**The kill-only loop in 2C does NOT naturally terminate today, but the supplier's Branch 0 wires real termination for the moment Phase 0B lands.** Termination paths in 2C, documented so the Tier 1 playbook can be honest:

| Termination trigger | Active in 2C today? | Activates when… |
|---|---|---|
| **Branch 0: `session.shouldContinue() == false` → `FailStep("SESSION_EXHAUSTED")` → RepeatStep Fails → Selector runs logout** | **No today** — `SessionShape(Long.MAX_VALUE)` per 2B.1.b means `shouldContinue()` always returns true. | **Yes after Phase 0B** lands a real daily budget. The Branch 0 wiring in 2C means no further code change is needed — the moment `shouldContinue()` flips, the body Fails terminally and logout fires. |
| `inventory.isFull()` → `take(GroundItemRef)` returns `Recovery.Abort("inventory full")` | **No** — loot is not included in 2C. | Optional 2C.x slice adds it after `take(...)` IRL-verified. |
| Empty `findNpc` propagates as terminal failure | **No — deliberate design change.** Empty `findNpc` yields a short idle and retries. Cows respawn, other players hold ours, render timing causes one-tick misses — none of those should log the player out. | Future thresholded count (N consecutive empty reads) if observation shows it's needed. Not 2C. |
| Engine-side failure inside the body (dispatcher error, `Recovery.Abort` from a Step, etc.) | **Possible but rare** — natural engine paths still propagate Failed; Selector then runs logout. | Whenever the engine has a hard failure. Defensive, not user-facing. |

**What this means for Tier 1:**

- Operator clicks Start. Bot walks. Bot kills cows. Bot keeps killing cows.
- Operator clicks Stop (via the 2C.1 button, once it lands) → `SequenceManager.stop()` halts the engine mid-tick. **`logout()` does not fire.** The character stays logged in.
- For Tier 1 acceptance: this is acceptable; operator is supervising. The playbook must call out "manual logout via the in-game UI after Stop" as part of the protocol.
- For a 2-hour v1.0-lock run: this WILL block on Phase 0B's real session budget so the loop naturally exhausts and reaches the Selector's logout branch.

**The plan does NOT claim "kill until inventory full" in 2C.** That claim only re-enters when loot lands.

**Why Branch 0 + Selector + logout matter together:**

1. **Forward-compatibility with Phase 0B (real reason to keep the wrap):** when Phase 0B lands a real session budget, Branch 0's `!shouldContinue()` check trips, `FailStep("SESSION_EXHAUSTED")` propagates, RepeatStep Fails, Selector tries the next option (logout), and the player logs out cleanly. **No code change in CowKillerScript is needed at that point** — the path is wired today.
2. **Engine-level robustness:** any unexpected `Recovery.Abort` inside the body (dispatcher error, target gone after retry budget, etc.) propagates and the Selector logs the player out cleanly. Better than spinning silently.
3. **The shape was approved** in the revised cow-pilot plan §4; deferring the Selector + logout wrap to 2C.x would be re-litigating settled design.

**Operator-Stop behavior is unchanged:** `stopCowKillerPilot()` calls `SequenceManager.stop()` directly, halting the engine mid-step. The Branch 0 check never runs for that case; logout does NOT fire. Operator manually logs out in the in-game UI after Stop. Documented in 2D's playbook.

---

## 4. Cow query — exact NpcQuery

```java
NpcQuery cowQuery = NpcQuery.byName("Cow")
    .within(15)                                  // ~field-radius from arrival tile
    .onPlane(0)                                  // LUMBRIDGE_COW_FIELD.plane()
    .unengagedOnly()                             // skip cows mid-fight with other players
    .rotation(new RotationPolicy.ClosestWithSlack(2));   // explicit, matches NpcQuery default
```

**Per-field rationale:**

| Field | Value | Rationale |
|---|---|---|
| `name` | `"Cow"` | `ArtemisImpl.matches(...)` (`:514`) uses `equalsIgnoreCase` — exact match. Excludes "Cow calf" (a separate, non-killable NPC) without explicit filtering. |
| `within(15)` | 15 tiles | East cow field is 10 wide × 22 tall; 15-tile range from any arrival tile covers the field but doesn't reach into the chicken pen (~5 tiles south of cow field's southern edge) or the Al-Kharid road (~7 tiles east). Tight enough to avoid noise; loose enough to find cows from any zone tile. |
| `onPlane(0)` | 0 | LUMBRIDGE_COW_FIELD.plane() == 0. Defensive — Artemis defaults to `ANY_PLANE` (`NpcQuery.java:20`); pinning matches the zone. |
| `unengagedOnly()` | true | Skips cows currently being attacked by another player. The `requireUnengaged` check (`ArtemisImpl.java:539-546`) returns true only when `npc.getInteracting()` is a different Player; it does NOT filter out cows fighting US or cows not fighting at all. So this also doesn't break "re-engage on stale ref" — the cow's interacting becomes `self` once we attack, but `requireUnengaged` checks for *other* players, not ours. |
| `rotation(ClosestWithSlack(2))` | explicit ClosestWithSlack(2) | This is `NpcQuery`'s default per spec §7 + `NpcQuery.java:25`. **Explicit so a future change to the default doesn't silently regress the pilot.** Mild identity-rotation per `RotationPolicy` Javadoc — picks any cow within 2 tiles of the closest, with per-account RNG. |

**Why not `byId(cowNpcId)`:** the OSRS Cow NPC has multiple ids across variants (different graphic stages, calves). `byName("Cow")` matches the killable Cow regardless of id variant, more robust to game updates. The id-based approach is reserved for cases where the name itself is ambiguous.

---

## 5. Imports — strict allow-list compliance

**`CowKillerScript.java` final import list (after 2C):**

```java
import java.util.Objects;
import java.util.Optional;
import net.runelite.client.sequence.Step;
import net.runelite.client.sequence.artemis.Artemis;
import net.runelite.client.sequence.artemis.query.NpcQuery;
import net.runelite.client.sequence.artemis.query.RotationPolicy;
import net.runelite.client.sequence.artemis.session.IdlePolicy;
import net.runelite.client.sequence.artemis.view.NpcRef;
import net.runelite.client.sequence.artemis.view.PlayerState;
import net.runelite.client.sequence.artemis.zones.NamedZone;
import net.runelite.client.sequence.composite.DynamicStep;
import net.runelite.client.sequence.composite.FailStep;
import net.runelite.client.sequence.composite.RepeatStep;
import net.runelite.client.sequence.composite.Selector;
```

14 imports total. **All allowed by spec §14:**

**Changes from the prior draft of this plan:**
- **Added** `artemis.session.IdlePolicy` — for the `SHORT_IDLE` constant + busy/no-cow yield steps.
- **Added** `artemis.view.PlayerState` — for the `player.idle()` busy gate.
- **Re-added** `composite.FailStep` — used solely by Branch 0's `SESSION_EXHAUSTED` terminator. The "no cow found" branch deliberately does NOT use FailStep; only the session-terminal branch does.
- `sequence.Step` — explicitly allowed.
- `sequence.artemis.*` — Artemis surface.
- `sequence.composite.*` — composite types "when explicitly composing" per §14.
- `java.util.*` — basics.

**Imports NOT used (verified would-be-needed-or-not):**
- `SequencePlanBuilder` — used only inside `plan()` body via `artemis.plan(...)` call chain, doesn't need an explicit import unless we hold a `SequencePlanBuilder` variable (we don't — single-expression chain).
- `Completion`, `Recovery`, `WorldSnapshot`, etc. — script never sees these; engine handles them.
- `OutcomeCheck` — base click overload doesn't take one. Per `OutcomeChecks.java:87-91`, `InteractingWithMe` evaluates to `OUTCOME_NOT_SUPPORTED_V1`; deliberately not used. `PlayerAnimChanged` is supported but adds variable behavior on one-hit cow deaths (low-level account swing animation may be skipped); base click is more reliable for Tier 1.

**Grep-gate banned imports — confirmed absent:**
- No `sequence.dispatch.*` (HumanizedInputDispatcher, PixelResolver, WindMouse, ActionRequest, etc.)
- No `sequence.activities.*` (script-side ban; the Step subclasses ClickNpcStep/WalkToZoneStep/LogoutStep are constructed BY ArtemisImpl, never by the script)
- No `SceneScanner`, `NpcSelector`, `WidgetActions`, `BankInteraction`, `GeInteraction`
- No `recorder.walker.*`, `nav.*`, `trail.*`, `transport.*`
- No raw `api.NPC`, `api.GameObject`, `api.widgets.Widget`
- No `ClientThread`
- No `clickCanvas`, `sampleNearCentroid`
- No `Robot`, `MouseEvent`

**Expected gate result after 2C:** `OK: scanned 16 files, 107 allow-list entries applied`. Unchanged from current. **Zero allow-list rows for CowKillerScript.java.**

---

## 6. Tests — single structural test, TDD

**File:** `runelite-client/src/test/java/net/runelite/client/plugins/recorder/scripts/CowKillerScriptPlanShapeTest.java` (new)

**Carve-out reminder:** per `feedback_no_tests_for_bot_scripts`, scripts don't get unit tests — but the cow pilot's `plan()` is "Artemis integration shape," which is shared-infra coverage (same carve-out as `NamedZoneTest`). The test asserts the tree shape via the composite getters, not gameplay.

**Composite introspection — verified to exist:**

| Accessor | Verified at | Returns |
|---|---|---|
| `LinearSequence.getChildren()` | `LinearSequence.java:35` (`@Getter` on the class) | `List<Step>` |
| `Selector.children()` | `Selector.java:39` | `List<Step>` |
| `RepeatStep.body()` | `RepeatStep.java:39` | `Step` |
| `RepeatStep.times()` | `RepeatStep.java:40` | `int` |
| `DynamicStep.factory()` | `DynamicStep.java` (Phase 1C.2 — `public Supplier<Step> factory()`) | `Supplier<Step>` |

No "test seam" needs to be invented. The existing getters are enough.

**Test plan (TDD, write-test-first per `superpowers:test-driven-development`):**

```
Test 1: planRootIsLinearSequenceWithExpectedChildren
  - Construct a mock Artemis (Mockito) where artemis.walkTo(...) and
    artemis.logout() return distinguishable Step instances.
  - Call script.plan(). Assert:
    - result instanceof LinearSequence
    - name == "cow-killer-v1"
    - children.size() == 2

Test 2: walkStepIsFirstChild
  - First child of LinearSequence is the Step returned by artemis.walkTo(...).
  - Verify via reference equality with the mocked return.

Test 3: secondChildIsSelectorWithExpectedOptions
  - children[1] instanceof Selector
  - Selector.children().size() == 2
  - children[0] instanceof RepeatStep with body instanceof DynamicStep,
    times == 0
  - children[1] is the Step returned by artemis.logout()

Test 4: dynamicStepFactoryReturnsFailStepWhenSessionExhausted
  - DynamicStep ds = (DynamicStep) repeatStep.body();
  - Mock artemis.session() → SessionShape whose shouldContinue() returns
    false. SessionShape's two LongSupplier inputs + a 0L budget produce
    this naturally — no Mockito on SessionShape needed; construct with
    real suppliers (e.g., () -> 1L for tickSupplier, anything for last-
    break, 0L for budget so any tick past 0 exhausts immediately).
  - Step result = ds.factory().get();
  - assert result instanceof FailStep
  - assert ((FailStep) result).name() contains "SESSION_EXHAUSTED"
    (FailStep.name() returns the reason per Phase 1C.1 contract)
  - Verify artemis.player was NOT called (verifyNoInteractions on
    that method) — proves Branch 0 short-circuits before Branch 1.
  - Verify artemis.findNpc was NOT called.
  - Verify artemis.click was NOT called.
  - Verify artemis.idle was NOT called.

Test 5: dynamicStepFactoryYieldsIdleWhenPlayerBusy
  - Mock artemis.session().shouldContinue() → true (Branch 0 passes).
  - Mock artemis.player() to return a PlayerState where idle() == false.
    PlayerState is a record (PlayerState.java:26-35) — construct directly,
    no Mockito needed for the record itself.
  - Mock artemis.idle(any IdlePolicy) → distinguishable Step.
  - Step result = ds.factory().get();
  - assert result == the mocked idle Step (reference equality)
  - Verify artemis.findNpc was NOT called (Mockito verifyNoInteractions
    on that method) — proves the busy gate is fail-closed.
  - Verify artemis.click was NOT called.

Test 6: dynamicStepFactoryYieldsIdleWhenNoCowFound
  - Mock artemis.session().shouldContinue() → true.
  - Mock artemis.player() → idle == true.
  - Mock artemis.findNpc(any) → Optional.empty().
  - Mock artemis.idle(any IdlePolicy) → distinguishable Step.
  - Step result = ds.factory().get();
  - assert result == the mocked idle Step (NOT FailStep — the supplier
    no longer terminates on empty reads).
  - Verify artemis.click was NOT called.

Test 7: dynamicStepFactoryReturnsClickStepWhenSessionContinuesAndIdleAndNpcPresent
  - Mock artemis.session().shouldContinue() → true.
  - Mock artemis.player() → idle == true.
  - Mock artemis.findNpc(any) → Optional.of(realNpcRef).
    (NpcRef is a record — construct with minimal valid fields directly.)
  - Mock artemis.click(realNpcRef, "Attack") → distinguishable Step.
  - Step result = ds.factory().get();
  - assert result == the mocked click Step (reference equality)
  - Verify artemis.click was called with verb "Attack" via the 2-arg
    overload (NpcRef, String) — NOT the 3-arg version with OutcomeCheck.
    Mockito verify with explicit overload signature.
  - Verify artemis.idle was NOT called this iteration.

Test 8 (optional): cowKillerScriptImportsStayWithinAllowList
  - Read CowKillerScript.java source, grep '^import ' lines, assert each
    one is in the approved list from §5.
  - NOT a test of plan() behavior — a test of the import contract.
  - If too brittle for unit-test scope, drop and rely on the grep gate.
  - Defer decision to implementation.
```

**Critical properties tested by the suite:**

- **Branch 0 short-circuits Branches 1-3** (Test 4): session-exhausted returns FailStep without consulting player, findNpc, click, or idle. The terminal path is the only thing reachable when session is dead.
- **Supplier is fail-closed on the non-attack paths** (Tests 5 + 6): every non-Branch-3 path yields an idle Step. The only path that calls `artemis.click(...)` is `shouldContinue() == true AND player.idle() == true AND findNpc.isPresent()`. This is the spam-prevention guarantee surfaced in the first round of review.
- **Click overload is the base 2-arg, not the 3-arg OutcomeCheck** (Test 7): Mockito's `verify(artemis).click(npcRef, "Attack")` pins the overload signature.

**Test verification commands:**
```
:client:test --tests 'net.runelite.client.plugins.recorder.scripts.CowKillerScriptPlanShapeTest'
  → 5-6 tests, 0 failures expected
```

No engine-integration test for `CowKillerScript` — the engine integration is covered by Phase 1C.3's `DynamicStepEngineIntegrationTest` (DynamicStep + RepeatStep + LinearSequence end-to-end). 2C only needs to verify the tree shape; runtime correctness is observed in Tier 1.

**Stale-ref test (the cow-pilot plan §6 criterion 9):**

The revised cow-pilot plan calls for a stale-ref test proving `ClickNpcStep` rejects a deliberately-stale `NpcRef`. That test belongs in `runelite-client/src/test/java/net/runelite/client/sequence/activities/script/ClickNpcStepStaleRefRetryTest.java` (shared-infra test for the Artemis Step), NOT in `CowKillerScriptPlanShapeTest`. **Deferred to a separate slice (2C.x or 2D-prep)** because it's not about the script's plan shape — it's about engine behavior the script transitively depends on.

---

## 7. 2C acceptance criteria

| # | Criterion | Verifier |
|---|---|---|
| 1 | `:client:compileJava` BUILD SUCCESSFUL | gradle |
| 2 | `:client:test --tests 'CowKillerScriptPlanShapeTest'` passes (7 assertions + optional 8th, 0 failures) | gradle |
| 3 | `./scripts/check-no-direct-engine-reaches.sh` exits 0 with **zero new allow-list rows** for `CowKillerScript.java` | grep gate |
| 4 | `CowKillerScript.plan()` returns a non-throwing `Step` (no `UnsupportedOperationException`) | structural test |
| 5 | Top-level tree shape matches §1 exactly | structural test |
| 6 | DynamicStep factory's four branches are correct: **session exhausted → FailStep("SESSION_EXHAUSTED")**, **busy → idle Step**, **no cow → idle Step**, **session ok + idle + cow → base click Step**. Click is only reachable from the fourth branch — verified by Mockito `verifyNoInteractions` on subsequent-branch methods in each earlier-branch test. | structural test |
| 7 | `artemis.click(...)` is called via the **base 2-arg overload** (`click(NpcRef, String)`) — not the 3-arg OutcomeCheck overload | Mockito verify |
| 8 | No new files outside `recorder/scripts/CowKillerScript.java` (modified) + `CowKillerScriptPlanShapeTest.java` (new) | git status |
| 9 | Plugin enable/disable cycle clean (smoke — no new exception path) | manual |

**Out of Tier 1 acceptance (handled in 2D):**
- 30-min test-profile session run.
- Operator stop reaches logout naturally (it WON'T in 2C — see §3).
- Click-heatmap diversity (Tier 2, requires Phase 3).

---

## 8. Carry-forward to 2C.1

2C does not touch the UI. 2C.1's scope (unchanged from 2B.1's deferred items):

- `@ConfigItem boolean cowKillerPilotEnabled` on `RecorderConfig` (default false, labeled test-only).
- Start / Stop buttons + status label on `RecorderPanel`, gated by the config flag.
- Button actionListeners → `plugin::launchCowKillerPilot` / `plugin::stopCowKillerPilot`.
- Panel-side double-start guard (mirror of `launchCowKillerPilot()`'s own guard).

2C.1 lands after 2C green-light. No 2C changes block it.

---

## 9. Risks

| Risk | Probability | Mitigation |
|---|---|---|
| **Body spam-clicks while combat in progress** (round-1 review concern) | **Eliminated by Branch 1 (busy gate).** | Test 5 proves the supplier returns an idle Step when `player.idle() == false`, and verifies `artemis.click` was not called. If a future refactor breaks the gate, that test fails. |
| **Loop idles forever after Phase 0B session budget expires** (round-2 review concern) | **Eliminated by Branch 0 (session terminator).** | Test 4 proves the supplier returns `FailStep("SESSION_EXHAUSTED")` when `shouldContinue() == false`, and verifies no downstream Artemis method is consulted. Branch 0's `FailStep` is the only path that lets the body Fail terminally; IdleStep (used by Branches 1 + 2) is maintenance and bypasses the session gate per `IdleStep.java:23-25`. |
| Implementation quietly reintroduces script-side lifecycle control | Low — Phase 1C made this impossible via the engine's closed-set composite dispatch | Test asserts `DynamicStep` instance is the body — guarantees factory-supplier shape is being used. |
| `Mockito.when(artemis.findNpc(any))` can't return distinguishable refs without a complex builder | Low — `NpcRef` is a record, easy to construct in tests | Tests construct minimal `NpcRef` / `PlayerState` instances directly (no Mockito for the records). |
| The optional `import allowlist` test (§6 test 7) is brittle to formatting changes | Medium | Drop test 7; rely on the grep gate. The grep gate is the import-policy enforcement, not the test. |
| `unengagedOnly()` filters too aggressively on a popular world (every cow is being attacked) | Low — east cow field is large and cows respawn fast | Worst case: `findNpc` returns empty briefly; supplier yields a short idle; next iteration retries. No deadlock. Operator can remove `unengagedOnly()` if testing on a contested world. |
| Cow-name match catches a future OSRS NPC variant we don't want | Very low — `"Cow"` is a stable name; calves have a distinct name | If it happens, add an `excludeIndices(...)` to the query or switch to `byId(...)` — small follow-up. |
| Busy-state polling rate (`SHORT_IDLE = 600-1200ms`) is too coarse or too fine | Low — 1-2 game ticks matches engine's tick rate | If observation shows combat-end detection lags by ≥1 tick noticeably, halve `SHORT_IDLE.maxMs`. If we see needless re-checks, double. Tunable, in-file constant. |
| `PlayerState.idle()` is a v1 approximation (no animation + no interacting target) and misses some "busy" states | Medium — documented in `PlayerState.java:19-24` | The miss-case is "player has no animation but is mid-walk via a server-side step." `WalkToZoneStep` is the only path that produces this state in 2C, and it succeeds-before-loop so we're not in the cow-loop body during walk. If the click step's post-dispatch settle has a one-tick "no animation yet" window, the SHORT_IDLE wait absorbs it. Worst case: one extra click queued during settle — recovered by next iteration's gate. Not a Tier 1 blocker. |
| Field loops forever idling because cows never respawn | Low (east field has 15+ cows) | Operator can Stop. 2D playbook documents the supervised-run expectation. Future 2C.x can add a thresholded "N consecutive empty reads → terminate" if observation shows this is a real problem. |
| 2C's structural test passes but the actual Tier 1 run reveals a runtime bug | Medium — that's what Tier 1 IS for | 2D's playbook explicitly catches stuck-state intervals + retry storms + missing log signals. Not a 2C blocker. |
| Implementation widens the file list beyond CowKillerScript + its test | Will be caught by §7 criterion 8 (git status check) | Stop and report if any other file change is needed. |

---

## 10. Proposed file boundary

**Modify:**
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/scripts/CowKillerScript.java` — `plan()` body becomes a real composition. Imports grow from 3 → 12.

**Create:**
- `runelite-client/src/test/java/net/runelite/client/plugins/recorder/scripts/CowKillerScriptPlanShapeTest.java` — single test file, 5-6 assertions.

**Untouched in this slice:**
- `RecorderPlugin.java` (launch path was 2B.1.b).
- `RecorderConfig.java` (UI gate is 2C.1).
- `RecorderPanel.java` (UI is 2C.1).
- The grep gate config / allow-list.
- DynamicStep / FailStep / engine composites (Phase 1C).
- ArtemisImpl / Artemis interface (Phase 1A).
- NamedZone (Phase 2A).

---

## 11. Slicing — single commit

**Decision:** **One commit.** 2C is small (single Java file body + single test file) and the test depends on the body — splitting them would land a test that can't compile against the un-modified `plan()` stub. TDD discipline lands them together: test first (red → file doesn't compile because the body's expected shape isn't there), then implementation (green).

Commit message sketch:

```
feat(artemis): Phase 2C — CowKillerScript.plan() returns real Step tree (Artemis + DynamicStep, busy-gated)

Replaces the 2B.1 UnsupportedOperationException with a composed plan:
  LinearSequence("cow-killer-v1")
   ├─ walkTo(NamedZone.LUMBRIDGE_COW_FIELD)
   └─ Selector("cow-killer-after-walk")
       ├─ RepeatStep("cow-killer-loop", body=DynamicStep, times=0)
       │    body: DynamicStep.of("cow-killer-tick", () -> {
       │           // Branch 0 — session terminator. MUST be first.
       │           // IdleStep is maintenance + bypasses session gate, so
       │           // without this check Branches 1-2 would idle forever
       │           // after Phase 0B lands a real budget.
       │           if (!artemis.session().shouldContinue())
       │               return FailStep.of("SESSION_EXHAUSTED");
       │           // Branch 1 — busy gate.
       │           if (player == null || !player.idle())
       │               return artemis.idle(SHORT_IDLE);
       │           // Branch 2 — no cow yet.
       │           Optional<NpcRef> cow = artemis.findNpc(cowQuery);
       │           if (cow.isEmpty())
       │               return artemis.idle(SHORT_IDLE);
       │           // Branch 3 — engage.
       │           return artemis.click(cow.get(), "Attack");  // base; no OutcomeCheck
       │         })
       └─ artemis.logout()

SHORT_IDLE = new IdlePolicy(600, 1200, false) — 1 to 2 engine ticks.
IdleStep is a maintenance Step (IdleStep.java:23-25) — bypasses session
gate, doesn't dispatch input, doesn't sleep on worker threads.

The busy gate is the load-bearing fix: without it, RepeatStep(times=0)
would spam clicks while combat is in progress because the base click
Step Succeeds on dispatcher complete (spec §11), not on combat end.

NpcQuery: byName("Cow").within(15).onPlane(0).unengagedOnly()
  .rotation(ClosestWithSlack(2))
- byName("Cow") matches "Cow" exactly via equalsIgnoreCase
  (ArtemisImpl.java:514); excludes "Cow calf".
- unengagedOnly() skips cows another player is attacking.
- rotation(ClosestWithSlack(2)) explicit (= NpcQuery default) so a future
  default change doesn't silently regress.

Honest termination behavior in 2C:
- SessionShape(Long.MAX_VALUE) per 2B.1.b means shouldContinue() never trips.
- No loot in 2C, so inventory never fills.
- Empty findNpc no longer terminates — yields a short idle and retries.
  Cows respawn; other players hold cows; one-tick read misses happen —
  none of those should log the player out. A thresholded "N consecutive
  empty reads → terminate" can land later if observation shows this is
  a real problem.
- The Selector → logout branch fires only on engine-side failure inside
  the body (dispatcher error, etc.). Operator-triggered Stop calls
  SequenceManager.stop() directly — engine halts; logout does NOT fire.
  Operator manually logs out in the in-game UI after Stop. Documented
  in 2D's playbook (when it lands).

OutcomeCheck.interactingWithMe()/TargetAnimChanged still unsupported in v1
per OutcomeChecks.java:87-91 — base click is used + the supplier's busy
gate covers what interactingWithMe() would have provided.

Tests (CowKillerScriptPlanShapeTest, 7 structural assertions + 1
optional import-list check):
- Top-level is LinearSequence("cow-killer-v1") with 2 children
- Child 0 is the walkTo Step
- Child 1 is a Selector with [RepeatStep(times=0, body=DynamicStep), Logout]
- Branch 0: supplier returns FailStep("SESSION_EXHAUSTED") when
  shouldContinue() is false; player / findNpc / click / idle NOT called
- Branch 1: supplier yields idle Step when player busy; findNpc + click
  NOT called
- Branch 2: supplier yields idle Step when findNpc empty (NOT FailStep);
  click NOT called
- Branch 3: supplier yields base-click Step only when session ok AND
  player idle AND cow present (Mockito verifies the 2-arg click
  overload, not the 3-arg OutcomeCheck)

Files:
- runelite-client/src/main/java/net/runelite/client/plugins/recorder/scripts/CowKillerScript.java
- runelite-client/src/test/java/net/runelite/client/plugins/recorder/scripts/CowKillerScriptPlanShapeTest.java

Grep gate unchanged: 16 files, 107 allow-list entries, zero new rows
for CowKillerScript.java.

Plan: docs/superpowers/plans/2026-05-24-artemis-phase-2c-cow-killer-plan.md
Next: 2C.1 — RecorderConfig flag + RecorderPanel Start/Stop button.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
```

---

## Self-review

- [x] Plan size within `feedback_compact_plans` 300-700 budget (this is ~520 lines including tables).
- [x] No full Java sources pasted — pseudocode + behavioral specs only.
- [x] APIs verified before referencing: `LinearSequence.@Getter` + `then(Step)` at `:35-52`; `Selector.children()` at `:39`; `RepeatStep.body()` + `times()` at `:39-40`; `DynamicStep.factory()` from Phase 1C.2; `FailStep.name() == reason` from Phase 1C.1; `NpcQuery.byName` + `equalsIgnoreCase` at `ArtemisImpl.java:514`; `requireUnengaged` filter at `ArtemisImpl.java:539-546`; `OutcomeChecks.java:87-91` unsupported cases.
- [x] No "convenience tier" — script is composed from existing primitives; no wrapper classes invented.
- [x] No script-side lifecycle control — supplier closure produces a fresh Step per call; engine drives execution.
- [x] No banned imports referenced in the script.
- [x] Honest termination wording — kill-only doesn't naturally stop in 2C; documented in §3 and the commit message.
- [x] No UI changes — 2C.1 owns that scope.
- [x] No CLAUDE.md edits.
- [x] Carry-forwards explicit (loot, session budget, stale-ref test, UI).

---

## Stop and wait

This plan is review-only. Do not begin Phase 2C implementation until the operator green-lights it.
