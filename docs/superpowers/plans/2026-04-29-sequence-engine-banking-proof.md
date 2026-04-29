# Sequence-Engine Banking Proof — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task.

**Spec (READ FIRST):** `docs/superpowers/specs/2026-04-29-sequence-engine-banking-proof-design.md`

**Goal:** Port the banking phase of `CookingScript` to the existing sequence engine, behind a feature flag, with the legacy path preserved.

**Approach:** Inspect real APIs in the repo before writing each task's code. This plan describes WHAT to build and what to verify; concrete Java comes from reading the current files at implementation time.

---

## Build / test

- Compile: `./gradlew :client:compileJava`
- Sequence-engine tests: `./gradlew :client:test --tests "net.runelite.client.sequence.*"`
- Banking-only: `./gradlew :client:test --tests "net.runelite.client.sequence.activities.banking.*"`
- Full suite: `./gradlew :client:test`
- Pipe to `tail` only with `set -o pipefail` (otherwise build failures get hidden — see memory).

## Conventions

- No BSD copyright headers on new files (private fork).
- TDD: failing test → minimal impl → green → commit. Group steps into focused commits, but you don't need a separate commit per checkbox.

## "Inspect first" — APIs the plan does NOT guess

For every task that touches these, read the current source before coding:

- `PlayerView` member methods.
- `InterfaceID.Bankmain.*`, `InterfaceID.BankpinKeypad.*`, `InventoryID.INV` / `INV.BANK` exact constant names in `runelite-api/src/main/java/net/runelite/api/gameval/`.
- `BankInteraction` current public methods (`bankReady`, `withdrawAll`, `withdrawOne`, `depositAll`, `closeBank` exist; `withdrawX` does NOT yet).
- `HumanizedInputDispatcher` typed-quantity / chatbox helper (verify the exact method name before calling from `BankInteraction.withdrawX`).
- `SequenceManager` current API surface (engine getter, telemetry getter, scheduler, run/pause/etc.).
- `PriorityPlanner` constructor and how `SequenceManager` wires it.
- `TelemetryRecord` fields / event enum values for the planner-rejection record.
- `CookingFood.Entry` field-vs-accessor shape (`food.rawId` vs `food.rawId()`).
- `CookingLocation` — does `bankBoothIds()` exist? builder-pattern shape?
- `ClientObserver.readPlayer()` body — must be preserved verbatim when the class is extended.
- `StateDrivenEngine.runPendingOnStarts` and `applyRecovery` current shapes (Retry off-by-one + canStart-gate edits patch them).
- `LinearSequence` current constructor surface.
- `RecorderPlugin` — how `CookingScript` is constructed today; where to inject `InputOwnership`.

---

## Task Order

### 1. Engine diagnostic foundation

**Files:** `sequence/affordance/{DiagnosticReason, BlockReason, BlockingInterface, ItemDiff, ActionKind, Affordance, AffordanceReport}.java`; modify `sequence/Completion.java`, `sequence/Failure.java`.

**Behavior:**
- `DiagnosticReason` is an engine-generic sealed interface with built-in cases (`Loading`, `ActionTimedOut`, `Unknown`).
- `BlockReason` is a sealed sub-interface with the bank-domain records listed in spec §6.
- `Completion.Failed` gains an optional `DiagnosticReason diagnostic`; add `Completion.failed(DiagnosticReason)` factory.
- `Failure` gains an optional `DiagnosticReason diagnostic`; add `Failure.fromDiagnostic(DiagnosticReason, int)` factory.
- `Affordance` and `AffordanceReport` records with `allowed` / `blocked` factories and `allAllowed()`.
- **Scope note — affordance layer is scaffolded only.** This proof introduces the typed surface (`ActionKind`, `Affordance`, `AffordanceReport`) but does NOT compute real affordances at observer time: `InteractionObserver.affordances()` returns `AffordanceReport.allAllowed()` (see Task 10). Banking steps consult `interaction().blockingInterface()` and `interaction().worldInteractionAvailable()` directly. Full per-action affordance computation is deferred.

**Tests:**
- `BlockReasonTest` — sealed hierarchy holds; `BlockReason.BankNotOpen instanceof DiagnosticReason`.
- `CompletionFailureDiagnosticTest` — `Completion.failed(r).diagnostic()==r`; legacy `new Completion.Failed("x")` has `diagnostic()==null`; `Failure.fromDiagnostic` round-trips; legacy factories produce null diagnostic.
- `AffordanceReportTest` — `allAllowed().blocked().isEmpty()`; `Affordance.blocked` carries reason + recoveries.

**Notes:**
- Sealed `permits` requires both `DiagnosticReason` and `BlockReason` to compile in the same source pass — declare them together.
- `BlockReason.NotAtLocation` references `net.runelite.api.coords.WorldArea`.

### 2. Engine lifecycle fixes

**Files:** `sequence/internal/StateDrivenEngine.java`.

**Behavior:**
- **canStart gate:** in `runPendingOnStarts`, do NOT call `onStart` on a leaf frame whose `step.canStart(snap, bb)` returns `false`. The frame stays un-started; the engine re-evaluates next tick. Composite frames bypass the gate (their leaves get evaluated independently).
- **Retry cumulativity:** in `applyRecovery`, change the abort threshold from `retryCount >= maxAttempts` to `retryCount > maxAttempts`. So `Retry(N)` permits N retries / N+1 total attempts.
- **Diagnostic passthrough:** when a leaf returns `Completion.Failed` with a non-null `diagnostic`, build the `Failure` via `Failure.fromDiagnostic(d, elapsed)` instead of `Failure.fromCheck(reason, elapsed)`.
- **STEP-scope timing (already correct):** `popAndOrchestrate` clears `BlackboardScope.STEP` only after a terminal transition. Do not change this — add a regression test.
- **Same-tick check (already correct):** the existing tick path runs `runPendingOnStarts` immediately followed by the leaf-`check` loop. Do not change this — add a regression test.

**Tests:**
- `StateDrivenEngineCanStartGateTest` — a `GatedStep` with `canStart=(tick>=3)` is not started until tick 3.
- `StateDrivenEngineRetryCumulativityTest` — `Retry(1)` produces exactly 2 `onStart` calls then `FAILED`; `Retry(3)` produces 4.
- `StateDrivenEngineDiagnosticPassthroughTest` — a step returning `Completion.failed(WithdrawNoOp(...))` shows up in `onFailure` as `Failure.diagnostic instanceof WithdrawNoOp`.
- `StateDrivenEngineStepScopeLifecycleTest` — onStart-written STEP key is visible in same-tick `check()`; key is cleared between two sequential children.
- `StateDrivenEngineSameTickCheckTest` — an already-satisfied step (onStart writes K_OUTCOME, check reads it) reaches IDLE in 1 tick.

### 3. SequenceEngine / SequenceManager extensions + InputOwnership

**Files:**
- New: `sequence/dispatch/InputOwnership.java`, `sequence/blackboard/SequenceBlackboardKeys.java`.
- Modify: `sequence/SequenceEngine.java`, `sequence/SequenceManager.java`, `sequence/internal/StateDrivenEngine.java`, `sequence/internal/PriorityPlanner.java`.

**Behavior:**
- `InputOwnership`: `AtomicReference<String>`-backed single-holder lease (`tryAcquire`, `isOwner`, `currentOwner`, `release`); only the current owner's release succeeds; double-release is a harmless no-op. **Lives in `dispatch/`** — it governs input.
- `SequenceEngine.clearReactives()`: remove all registered reactive steps (idempotent).
- `SequenceEngine.registerReactive(Step, int priority)`: convenience overload — priority parameter documents intent at the call site; selection still uses `step.priority()`.
- `SequenceManager.clearReactives()`: scheduler-marshalled passthrough.
- `SequenceManager.registerReactive(Step, int)`: scheduler-marshalled passthrough.
- `SequenceManager.setInputOwnership(InputOwnership, String ownerToken)`: store both. Wire the pair into `Executor` (or `StateDrivenEngine`'s pre-dispatch path) so that before draining a mutating action through the dispatcher, the engine verifies `inputOwnership.isOwner(ownerToken)`. If false (lease was lost / stolen mid-sequence), the engine records a telemetry record with `DiagnosticReason.Unknown("input ownership lost mid-sequence")`, calls `inputOwnership.release(ownerToken)` defensively, and fails the current frame with that diagnostic — the engine transitions to `FAILED`. Per spec §11.3.
- `SequenceBlackboardKeys.LAST_BLOCK_REASON`: `BlackboardKey<DiagnosticReason>` at `step.lastBlockReason`.
- `PriorityPlanner`: when `step.canStart` returns false, record a `TelemetryRecord` with the step name + `LAST_BLOCK_REASON` (read from STEP scope) + tick. `SequenceManager.rebuildEngineIfReady` injects telemetry into the planner if it's a `PriorityPlanner`.

**Tests:**
- `InputOwnershipTest` (in `sequence/dispatch/`) — first acquire wins; second acquire while held fails; only owner can release; double-release is no-op.
- `StateDrivenEngineClearReactivesTest` — `clearReactives()` wipes registrations; in a separate test, simulate three sequential banking-style runs (each registers one reactive then `clearReactives`s) and verify reactives don't accumulate.
- `PriorityPlannerTelemetryTest` — a stub step that writes `LAST_BLOCK_REASON` and returns false from `canStart` produces a CHECK telemetry record containing the reason, AND the key is readable from STEP scope after.
- `StateDrivenEngineInputOwnershipTest` — engine configured with an `InputOwnership` + token; lease is cleared externally before a tick; the next mutating action does NOT reach the dispatcher (`MockInputDispatcher.requests` empty), engine reaches `FAILED`, the diagnostic mentions "input ownership lost".

### 4. WorldSnapshot views

**Files:** `sequence/views/{InventoryView, BankView, Presence, BankItemAvailability, WidgetView, InteractionView, InteractionMode, EventFacts, ImmutableWorldSnapshot}.java`; modify `sequence/WorldSnapshot.java`.

**Behavior:**
- Each view interface provides an EMPTY/null-object factory.
- `WorldSnapshot` adds `default` methods returning the empty views, so existing `WorldSnapshot` implementers (FixtureObserver, etc.) keep compiling.
- `BankView.availability(int)` returns `BankItemAvailability(Presence, OptionalInt knownCount, boolean visible)`. Always `UNKNOWN` if `ready()==false`.
- `ImmutableWorldSnapshot` is a record + builder used by tests and (later) by production `ClientObserver`.

**Tests:**
- `BankItemAvailabilityTest` — three factory methods produce distinct presences; `present(n,v).knownCount().getAsInt()==n`.
- `ImmutableWorldSnapshotTest` — builder overrides individual views; defaults applied for the rest.
- Smoke: existing engine tests must still pass (the new methods are `default`).

### 5. Banking test fixtures

**Files (test only):** `sequence/activities/banking/{BankSnapBuilder, RecordingBankActions, BankingEngineHarness}.java` plus a `BankingFixturesSelfTest`.

**Behavior:**
- `BankSnapBuilder`: fluent builder for `WorldSnapshot` fixtures (`tick`, `player`, `invItem`, `freeSlots`, `bankOpen/Ready/PinUp`, `bankItem/bankItemAbsent/bankItemUnknown`, `mode`, `worldAvailable`, `blocker`, `lastInvChangeTick`). Internally constructs anonymous-class views and builds an `ImmutableWorldSnapshot`.
- `RecordingBankActions implements BankActions`: in-memory list of every call serialized as `"depositAll(123)"`, `"withdrawX(456,12)"`, etc.; configurable return values per method.
- `BankingEngineHarness`: wraps `SequenceManager.withDefaults()` with a `FixtureObserver` (the existing test helper) over a queued snapshot list and a `MockInputDispatcher`. Provides `queue(...)`, `run(rootStep)`, `advance(n)`, `state()`.

**Tests:** `BankingFixturesSelfTest` — smoke check that the snap builder wires all views and `RecordingBankActions` tracks calls.

### 6. Banking adapters

**Files:**
- New: `sequence/activities/banking/BankActions.java` — interface with the methods banking steps dispatch through (`clickBankBoothRandom`, `depositAll(int)`, `withdrawOne(int)`, `withdrawAll(int)`, `withdrawX(int,int)`, `closeBank`).
- Modify: `recorder/farm/BankInteraction.java` — implement `BankActions`; add `withdrawX(itemId, qty)` (uses the existing `withdrawWithVerb(...)` plumbing for "Withdraw-X" + the dispatcher's typed-quantity helper — **inspect dispatcher first** for the actual helper name).
- Modify: `recorder/cook/CookingLocation.java` + `CookingLocations.java` — add `bankBoothIds()` accessor + builder setter; populate Lumbridge with the bank-booth `ObjectID` constants (verify exact names in `runelite-api/.../gameval/ObjectID.java`).
- Modify: `sequence/composite/LinearSequence.java` — add `LinearSequence(String name, List<Step> children)` constructor.

**Tests:** `LinearSequenceListConstructorTest` — list-constructor populates children in order. The remaining adapter changes are exercised via downstream step tests (no dedicated tests).

### 7. `WithdrawQuantity` sealed type

**Files:** `sequence/activities/banking/WithdrawQuantity.java`.

**Behavior:** sealed interface with `AtLeast(int qty)` and `FillRemainingInventory()` only. **No `Exact` variant** (had identical semantics to `AtLeast`; spec removed it).

**Tests:** `WithdrawQuantityTest` — sealed hierarchy supports exhaustive `switch`; `AtLeast.qty()` accessor works.

### 8. Banking steps

One step per task. All steps are stateless — working memory in `bb.scope(BlackboardScope.STEP)`. All step contracts per spec §7 / §9.2.

#### 8a. `EnsureAtBankStep`
- canStart=true; fatal `NotAtLocation(area)` in onStart if player not in bankArea; Recovery=Abort.
- **Tests:** in-area succeeds; out-of-area aborts with `NotAtLocation` diagnostic.

#### 8b. `EnsureNoBlockingInterfaceStep` (reactive)
- canStart=true ONLY when (a) blocker present AND (b) `blocker.rootWidgetId` NOT in `allowedRoots`.
- onStart records the blocker AND dispatches a semantic dismiss action via the engine's dispatcher: `Escape` for closable modals (level-up dialog, etc.), `Space` for chat-continue prompts. If `HumanizedInputDispatcher` lacks a suitable single-key helper, add one minimal method (e.g., `pressEscape()` / `pressSpace()`) — production cannot be a no-op or the dialog stays forever.
- check verifies blocker cleared via snapshot. Recovery=Retry(2) then Abort.
- Tests can stay snapshot-driven (post-onStart, the next snapshot shows blocker cleared); the dispatch call is verified through `MockInputDispatcher` recording, not via real keystroke effects.
- **Tests (covers spec scenario 16):** no-blocker → canStart=false; blocker IN allow-list → canStart=false (bank itself must NOT trigger preemption); blocker OUT of allow-list → canStart=true.

#### 8c. `OpenBankStep` (covers spec scenario 1)
- already-satisfied: `bank.open()` → no booth click.
- fatals (recorded in onStart): `PinKeypadUp`; `WorldInteractionBlocked(by)` when `!by.canBeClosed()` OR root not in OpenBank's `closableAllowList`.
- waitable canStart=false: only when `WorldInteractionBlocked(by)` AND `by.canBeClosed()` AND root IS in the allow-list (reactive `EnsureNoBlocker` will clear it).
- onStart: `bank.clickBankBoothRandom()`. Recovery=Retry(3) except for fatal pin/blocker which Abort.
- **Tests:** already-open; pin-keypad fatal; non-closable blocker fatal; closable+allowed blocker waits then opens.

#### 8d. `WaitForBankReadyStep` (covers spec scenario 8)
- canStart=true always. onStart records `K_START_TICK`.
- check: `Succeeded` when `bank.ready()`; `Failed(BankNotOpen)` if bank closes; `Failed(BankNotReady)` (typed timeout owned by check) when `tick - K_START_TICK >= timeoutTicks()`. Recovery=Abort.
- **Tests:** ready arrives; bank closes mid-wait; never-ready typed timeout (assert `Failure.diagnostic instanceof BankNotReady`, NOT engine generic `Failure.timeout`).

#### 8e. `DepositItemStep`
- already-satisfied: `inv.count(itemId)==0`.
- fatal: `BankNotOpen`. onStart dispatches `bank.depositAll(itemId)`.
- check succeeds when `inv.count(itemId)==0`. Recovery=Retry(3) except `BankNotOpen` Abort.
- **Tests:** zero-count satisfied (no dispatch); deposit dispatches and succeeds; bank-closed → fatal.

#### 8f. `WithdrawItemStep` (covers spec scenarios 3, 4, 5, 8b, 9, 13, 14, 15)

Most complex step. canStart=true always — every bad state is either fatal or already-satisfied.

- onStart precondition order: `BankNotOpen` → `PinKeypadUp` → `BankContentsUnknown` (UNKNOWN after `bank.ready()` is observer bug — fatal) → already-satisfied check → `BankMissingItem` (presence==ABSENT) → resolve target/delta → `InventoryFull` (delta > freeSlots) → dispatch.
- already-satisfied: `AtLeast.qty <= currentCount` OR `Fill.freeSlots == 0`.
- fresh-work dispatch is the **delta**, not the original quantity:
  - `delta == 1` → `withdrawOne`
  - `delta == knownBankCount` → `withdrawAll`
  - else → `withdrawX(itemId, delta)`
  - `delta == 0` → no dispatch.
- check returns: `Failed(precondition)` if onStart recorded one; `Succeeded` when `inv.count >= target`; `Failed(BankNotOpen)` mid-step; `Failed(BankMissingItem)` if availability becomes ABSENT mid-step; `Failed(WithdrawNoOp(itemId, timeoutTicks))` deterministically when `tick - K_START_TICK >= timeoutTicks() && now == K_INV_START`.
- onFailure: `Retry(1)` for `WithdrawNoOp` only; `Abort` otherwise.

**Tests (8 cases, named after spec §13 numbers):**
- 13: `AtLeast(5)` + count=3 → exactly `withdrawX(itemId, 2)` (NOT `withdrawX(..., 5)`).
- 5: count already at quantity → no dispatch, succeeds.
- 14: `Fill` + count=1, freeSlots=27, knownBankCount=100 → not satisfied; `withdrawAll`.
- 15: `Fill` + count=0, freeSlots=28, knownBankCount=12 → `withdrawX(..., 12)` (partial trip allowed).
- 9: presence=ABSENT → `BankMissingItem` fatal, no dispatch.
- 8b: `bank.ready()=true` + presence=UNKNOWN → `BankContentsUnknown` fatal, no dispatch.
- 4: bank closes mid-step → `BankNotOpen`.
- 3: 6+ ticks with no inventory change → typed `WithdrawNoOp` BEFORE the engine's generic `Failure.timeout`. Assert `Failure.diagnostic instanceof WithdrawNoOp` AND it's non-null.

#### 8g. `EnsureInventoryMatchesLoadoutStep` (covers spec scenario 12)
- Pure verifier. Adds `activities/banking/Loadout.java` with `Slot(itemId, qty, exact)`.
- fatal: `LoadoutMismatch(diff)` if any required slot fails (`exact==count` OR `>=1`). Recovery=Abort.
- **Tests:** matching loadout succeeds; missing tinderbox produces `LoadoutMismatch` diagnostic.

#### 8h. `CloseBankStep` (covers spec scenario 7)
- already-satisfied: `!bank.open()`.
- onStart: `bank.closeBank()`.
- check succeeds when `!bank.open() && interaction.worldInteractionAvailable()`. Recovery=Retry(3).
- **Tests:** already-closed satisfied (no dispatch); waits for world available before succeeding.

### 9. `BankingSequenceFactory` + `BankingSequencePlan`

**Files:** `sequence/activities/banking/{BankingSequencePlan, BankingSequenceFactory}.java`.

**Behavior:**
- `BankingSequencePlan` = `record (Step root, List<Step> reactiveSteps)`.
- `prepareCookingLoadout(CookingLocation, CookingFood.Entry, boolean needsTinderbox, BankActions) → BankingSequencePlan`.
- Linear root: `EnsureAtBank(area)` → `EnsureNoBlockingInterface(BANK_ROOTS)` → `OpenBank(boothIds, closableAllowList=Set.of())` → `WaitForBankReady` → `DepositItem(cookedId)` → `DepositItem(burntId)` → `[WithdrawItem(tinderbox, AtLeast(1))]?` → `WithdrawItem(rawId, FillRemainingInventory())` → `EnsureInventoryMatchesLoadout(loadout)` → `CloseBank`.
- Reactives: `[EnsureNoBlockingInterfaceStep(BANK_ROOTS)]`.
- `BANK_ROOTS = Set.of(InterfaceID.Bankmain.UNIVERSE)` — verify exact constant name.
- `OPEN_BANK_CLOSABLE_ALLOWLIST = Set.of()` for the proof (no closable modals are waitable; production wiring identifies which roots are safe).
- Loadout: tinderbox `exact=true`; raw food `exact=false` (>=1, allowing partial-final-trip per spec §8.2).

**Tests (E2E — covers spec scenarios 6, 10, 11):**
- `planExposesRootAndReactives` — `plan.reactiveSteps()` has one `EnsureNoBlockingInterfaceStep`.
- `endToEndHappyPath` (scenario 11) — tick-by-tick fixture drives the sequence to IDLE; `RecordingBankActions` shows expected calls in order.
- `reactivePreemptsOnDialogMidFlow` (scenario 6) — mid-snapshot blocker appears, reactive preempts in-flight `WithdrawItem`, dismiss verified, linear frame resumes.
- Scenario 10 is verified at the `InputOwnership` layer (Task 3 test).

### 10. Production observers

**Dependency:** Task 11 (`CookingScript` integration) must NOT start until this is done. Without real observers, `BankView.open()` always returns false and the script will sit forever.

**Files:**
- New: `sequence/internal/{InventoryObserver, BankObserver, WidgetObserver, InteractionObserver}.java`.
- Modify: `sequence/internal/ClientObserver.java`.

**Behavior:**
- All client reads happen on the client thread (callers — `SequencerPlugin`, `CookingScript.scheduleEngineTick` — already marshal via `clientThread::invoke`).
- `InventoryObserver` / `BankObserver`: maintain `prevHash` + `lastChangeTick` for `EventFacts`.
- `BankObserver`: reads `Bankmain.UNIVERSE` widget for `open()`; `BankpinKeypad.UNIVERSE` for `pinUp()`; `InventoryID.BANK` container for `ready()` + `availability()` (visible-tracking deferred — every present item reports `visible=true`).
- `InteractionObserver`: combines bank + widgets + `Client.getMenu().isOpen()` to compute `mode()` and `blockingInterface()`. For the proof, only the bank itself is detected as a blocker; level-up dialog detection is deferred.
- `ClientObserver` composes the four into an `ImmutableWorldSnapshot`. **Preserve the existing `readPlayer()` body verbatim** (LoginRunner depends on it) — read the current file, copy the existing player-reading logic into the new method.

**Tests:** none required at this layer — covered by step tests via `BankSnapBuilder`. Smoke: existing engine + login tests must still pass.

### 11. `CookingScript` integration (gated on Task 10)

**Files:**
- Modify: `recorder/RecorderConfig.java` — add `default boolean useEngineBanking() { return false; }` (default MUST be false).
- Modify: `recorder/RecorderPlugin.java` — construct one `InputOwnership` field, pass it to `CookingScript`.
- Modify: `recorder/scripts/CookingScript.java`:
  - Rename `State.BANKING` → `State.BANKING_LEGACY`; add `State.BANKING_VIA_ENGINE`. Update every `State.BANKING` reference (grep first).
  - Rename `tickBanking()` → `tickBankingLegacy()`, mark `@Deprecated`.
  - State router (in `tickWalk` etc.): `setState(config.useEngineBanking() ? BANKING_VIA_ENGINE : BANKING_LEGACY)`.
  - Add fields: `inputOwnership`, `config`, lazy `bankingManager`, `tickInFlight = new AtomicBoolean()`, `bankingStartRequested = new AtomicBoolean()`, `static final String OWNER_TOKEN = "cooking-banking"`.
  - Add `tickBankingViaEngine()`:
    - If `!playerInArea(bankArea)` → `setState(WALK_TO_BANK)`.
    - Lazy-build `bankingManager`.
    - Switch on `bankingManager.state()`:
      - `IDLE`: respect `bankingStartRequested`; tryAcquire `OWNER_TOKEN`; build plan; `clearReactives()`; register reactives; `bankingStartRequested.set(true)`; try-catch `bankingManager.run(plan.root())` with cleanup (release lease + reset flag) on exception.
      - `RUNNING`: `bankingStartRequested.set(false)`; `scheduleEngineTick()`; status from telemetry.
      - `COMPLETED`: `bankingStartRequested.set(false)`; release lease; `setState(WALK_TO_COOK)`.
      - `FAILED`: `bankingStartRequested.set(false)`; release lease; `abortWithStatus`.
  - Add `buildBankingManager()`: `withDefaults()`, `setObserver(new ClientObserver(client))`, `setDispatcher(dispatcher)`, `setScheduler(clientThread::invoke)`, `setInputOwnership(inputOwnership, OWNER_TOKEN)`.
  - Add `scheduleEngineTick()`: gated by `tickInFlight` AtomicBoolean; calls `clientThread.invokeLater(engine::advanceTick)`.
  - Add helpers `readLastTelemetry(SequenceManager)` and `lastFailureReason(SequenceManager)` — read the ring buffer.

**Tests:** `CookingScriptStateRouterTest` — enum has both `BANKING_LEGACY` and `BANKING_VIA_ENGINE`. End-to-end behavior is covered by `BankingSequenceFactoryTest`.

### 12. Documentation

**Files:**
- New: `sequence/ARCHITECTURE.md` — ~300 lines, the 13 sections from spec §14.1 (engine map, lifecycle, already-satisfied semantics, views, affordance layer, composites, telemetry, LoginRunner case study, BankingSequenceFactory case study, how to write a new step, known gaps).
- Modify: project `CLAUDE.md` — add the "Sequence engine — read FIRST for any NEW gameplay scripting" pointer block (spec §14.2).

---

## Scenario coverage map (spec §13 → tests)

| # | Scenario | Test class · method | Task |
|---|---|---|---|
| 1 | Bank already open | `OpenBankStepTest` already-open | 8c |
| 2 | World action rejected while bank open | _Deferred — WalkStep affordance gating is post-proof_ | — |
| 3 | Withdraw 6-tick no-op → typed `WithdrawNoOp` | `WithdrawItemStepTest` no-op timeout | 8f |
| 4 | Bank closes mid-withdraw | `WithdrawItemStepTest` mid-step closure | 8f |
| 5 | Already in inventory — no dispatch | `WithdrawItemStepTest` already-satisfied | 8f |
| 6 | Reactive preempts on mid-flow dialog | `BankingSequenceFactoryTest` reactive-preemption | 9 |
| 7 | `CloseBank` waits for world available | `CloseBankStepTest` | 8h |
| 8 | `WaitForBankReadyStep` — 3 sub-cases | `WaitForBankReadyStepTest` (3 tests) | 8d |
| 8b | `BankContentsUnknown` after ready=true is fatal | `WithdrawItemStepTest` unknown-after-ready | 8f |
| 9 | Tinderbox absent → `BankMissingItem` fatal | `WithdrawItemStepTest` absent-fatal | 8f |
| 10 | InputOwnership refuses second acquire | `InputOwnershipTest` | 3 |
| 11 | End-to-end happy path | `BankingSequenceFactoryTest` happy-path | 9 |
| 12 | Loadout mismatch caught before close | `EnsureInventoryMatchesLoadoutStepTest` | 8g |
| 13 | `AtLeast(5)` + count=3 → `withdrawX(2)` | `WithdrawItemStepTest` delta-not-q | 8f |
| 14 | `Fill` not satisfied just because count≥1 | `WithdrawItemStepTest` fill-not-satisfied | 8f |
| 15 | `Fill` partial final trip | `WithdrawItemStepTest` partial-fill | 8f |
| 16 | Bank widget during `WithdrawItem` does NOT preempt | `EnsureNoBlockingInterfaceStepTest` allow-list | 8b |
| 17 | Retry cumulativity bounds attempts | `StateDrivenEngineRetryCumulativityTest` | 2 |

> **Scenario 2 deferred:** the proof does not modify `WalkStep.canStart` or wire `AffordanceReport.isAllowed(WALK)` to bank state. That work belongs to the next pass (full affordance computation). The banking steps in this proof consult `interaction().blockingInterface()` and `worldInteractionAvailable()` directly.

Plus regression: all existing ~25 sequence-engine tests must continue to pass.

---

## Acceptance checklist

Verify spec §15 in full after Task 12 lands. Spot checks:

- All 17 scenario tests pass; all existing engine + login tests still green.
- `WithdrawQuantity` has only `AtLeast` and `FillRemainingInventory` (no `Exact`).
- `WithdrawItemStep` dispatches the **delta**, never the original `q`.
- `canStart=false` is used ONLY in `OpenBankStep`, ONLY for closable+allowed `WorldInteractionBlocked`. Every other banking step has `canStart=true` always.
- `WaitForBankReadyStep` owns its typed timeout in `check()` (not engine generic).
- `Retry(N)` is cumulative — `Retry(1)` bounded at 2 total attempts.
- `useEngineBanking()` defaults to `false`. Legacy `tickBankingLegacy()` is the production default; engine path is opt-in.
- No banking step holds mutable instance state.
- No banking step imports `net.runelite.api.widgets.Widget`.
- No banking step calls `dispatcher.dispatch(...)` directly.
- `bankingManager.run(plan.root())` is try/catch-guarded (releases lease + clears `bankingStartRequested` on exception).
- `InputOwnership` lives at `sequence/dispatch/InputOwnership.java`.
- Views live at `sequence/views/`.
- `OWNER_TOKEN = "cooking-banking"` appears as `CookingScript.OWNER_TOKEN`, never as a bare string literal at call sites.

---

## Self-review (before handoff)

- Every spec section has at least one task here.
- No unresolved placeholders inside in-scope tasks. Explicitly out-of-scope / deferred items (visible-bank-slot tracking, level-up dialog detection, full affordance computation, scenario 2 / `WalkStep` gating) are allowed when named as such.
- Tests are described by behavior; full source is the implementer's job, drawn from the actual repo APIs at code time.
- "Inspect first" notes flag every place where the plan refuses to guess existing code.
