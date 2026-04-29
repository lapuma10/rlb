# Sequence-Engine GE Proof — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task.

**Spec (READ FIRST):** `docs/superpowers/specs/2026-04-29-sequence-engine-ge-proof-design.md`

**Companion proof (sister branch):** `docs/superpowers/specs/2026-04-29-sequence-engine-banking-proof-design.md` and its plan.

**Goal:** Build a GE Core buy/sell proof on the sequence engine — self-sufficient from `master`. No banking dependency required. User is already at the GE with coins/items in inventory.

**Approach:** Phase A (now) implements the engine foundation subset, GE Core types, step library, factory, script, and UI in one phase. Phase B (after banking lands) adds bank-prep composition.

---

## Build / test

- Compile: `./gradlew :client:compileJava`
- Sequence-engine tests: `./gradlew :client:test --tests "net.runelite.client.sequence.*"`
- GE-only: `./gradlew :client:test --tests "net.runelite.client.sequence.activities.ge.*"`
- Full suite: `./gradlew :client:test`
- Pipe to `tail` only with `set -o pipefail` (otherwise build failures get hidden).

## Conventions

- No BSD copyright headers on new files (private fork).
- TDD: failing test → minimal impl → green → commit. Group steps into focused commits.
- **Never** edit files in spec §4 no-touch list (`CookingScript.java`,
  `sequence/activities/banking/**`, `RecorderConfig.java`).

## "Inspect first" — APIs the plan does NOT guess

Read these before coding the relevant task:

- `runelite-api/.../GrandExchangeOffer.java` — `getQuantitySold()`, `getItemId()`, `getTotalQuantity()`, `getPrice()`, `getSpent()`, `getState()`.
- `runelite-api/.../GrandExchangeOfferState.java` — enum cases.
- `runelite-api/.../Client.java` — `getGrandExchangeOffers()` returns `GrandExchangeOffer[]` (8 slots).
- `runelite-api/.../gameval/InterfaceID.java` — nested classes `GeOffers`, `GeOfferSetup`, `GeCollect`, etc. Verify exact root field name (probably `UNIVERSE`; read the file).
- `plugins/grandexchange/GrandExchangePlugin.java` — working examples of GE state reads.
- `plugins/grandexchange/GrandExchangeInputListener.java` — search-input typing; informs `SelectItemStep`.
- `plugins/grandexchange/GrandExchangeSearchPanel.java` — result-row widget structure.
- `recorder/farm/BankInteraction.java` — production impl pattern for actions classes; `GeInteraction` mirrors this style.
- `sequence/dispatch/HumanizedInputDispatcher.java` — typed-quantity / chatbox helper (verify method names).
- `recorder/RecorderPanel.java` — how existing tabs are added to `JTabbedPane`.
- `recorder/RecorderPlugin.java` — Guice injection pattern for scripts.
- `sequence/` package — read existing `SequenceEngine`, `SequenceManager`, `StateDrivenEngine`, `Completion`, `Failure`, `WorldSnapshot`, `ClientObserver` before editing. Do not guess current shapes.

---

## Phase A — now (self-sufficient from master)

**Precondition:** clean compile from `master` with `./gradlew :client:compileJava`.

### Task 1. `DiagnosticReason` + `BlockReason` + `GeBlockReason` sealed types

**Files:**
- New: `sequence/affordance/DiagnosticReason.java`
- New: `sequence/affordance/BlockReason.java` (GE subset: `PinKeypadUp`, `WorldInteractionBlocked(BlockingInterface)`, `NotAtLocation(WorldArea)`)
- New: `sequence/affordance/BlockingInterface.java`
- New: `sequence/affordance/GeBlockReason.java` (all GE-domain records from spec §7)

**Behavior:**
- `DiagnosticReason` is engine-generic sealed parent. Initial `permits` clause includes `BlockReason`, `GeBlockReason`, plus engine-generic cases (`Loading`, `ActionTimedOut`, `Unknown`).
- `BlockReason` is a shared sealed sub-interface. GE declares only the three records it needs here (`PinKeypadUp`, `WorldInteractionBlocked`, `NotAtLocation`). Banking will add bank-domain records when it rebases.
- `GeBlockReason` sealed sub-interface per spec §7.
- `InsufficientCoins` and `InsufficientSellItems` are records of `GeBlockReason` — primary guards in GE Core (no upstream WithdrawItem to catch them).

**Tests:**
- `GeBlockReasonTest` — sealed hierarchy holds; each record `instanceof DiagnosticReason`.
- `BlockReasonTest` — `PinKeypadUp / WorldInteractionBlocked / NotAtLocation` compile and are pattern-matchable.
- `DiagnosticReasonPermitsTest` — `GeBlockReason` and `BlockReason` are pattern-matchable as `DiagnosticReason`.

**Notes:** `GeBlockReason extends DiagnosticReason` requires `DiagnosticReason` to list `GeBlockReason` in its `permits` clause — both new files in this task, so no ordering issue.

### Task 2. Engine plumbing — `Completion.failed`, `Failure.diagnostic`

**Files:**
- Modify: `sequence/Completion.java`
- Modify: `sequence/Failure.java`

**Behavior:**
- Inspect current `Completion.java` and `Failure.java` before editing.
- `Completion.Failed` gains an `@Nullable DiagnosticReason diagnostic` field.
- `Completion.failed(DiagnosticReason)` factory method.
- `Failure` gains `@Nullable DiagnosticReason diagnostic` field + `Failure.fromDiagnostic(DiagnosticReason, int ticks)` factory.

**Tests:**
- `FoundationCompletionFailureTest` (F1) — `Completion.failed(reason).diagnostic() == reason`; `Failure.fromDiagnostic(reason, 3).diagnostic() == reason`; pre-existing no-diagnostic paths still compile.

### Task 3. Engine lifecycle fixes

**Files:**
- Modify: `sequence/internal/StateDrivenEngine.java`
- Modify: `sequence/internal/PriorityPlanner.java`

**Behavior — inspect current source first, then apply:**
- `canStart` gate: if `step.canStart(snapshot) == false`, do not call `onStart` this tick. Record `LAST_BLOCK_REASON` from blackboard for telemetry.
- `Retry(N)` cumulative: engine tracks total attempts per step-execution. Once `attempts > N`, abort regardless of `onFailure` result.
- STEP-scope ordering: `bb.clear(BlackboardScope.STEP)` fires AFTER terminal transition (after `onFailure`), NOT between `onStart` and same-tick `check`.
- Diagnostic passthrough: when a step fails, populate `Failure.diagnostic` from the step's `K_PRECONDITION_FAILURE` key if present.
- `PriorityPlanner` telemetry-on-reject: when the `canStart` gate fires, emit `(stepName, reason)` to `RingBufferTelemetry`.

**Tests:**
- `FoundationRetryBoundTest` (F2) — step whose `onFailure` always returns `Retry(1)` still terminates after 2 total attempts.
- `FoundationStepScopeOrderTest` (F3) — STEP-scoped key set in `onStart` is still readable in same-tick `check`.
- `FoundationCanStartGateTest` (F4) — step with `canStart=false` never has `onStart` called; `canStart=true` unblocks normally.

### Task 4. `SequenceManager` extensions + `InputOwnership` + blackboard keys

**Files:**
- Modify: `sequence/SequenceEngine.java` (add `clearReactives()`, `registerReactive(Step, int)`)
- Modify: `sequence/SequenceManager.java` (scheduler-marshalled passthroughs + `setInputOwnership`)
- New: `sequence/dispatch/InputOwnership.java`
- New: `sequence/blackboard/SequenceBlackboardKeys.java` (`LAST_BLOCK_REASON`)

**Behavior:**
- Inspect current `SequenceEngine.java` and `SequenceManager.java` before editing.
- `InputOwnership` provides `tryAcquire(ownerToken)`, `release(ownerToken)`, `currentOwner()`.
- `clearReactives()` removes all registered reactives from the engine.
- `registerReactive(Step, int priority)` registers a step as a reactive with the given priority.
- `setInputOwnership(InputOwnership, String ownerToken)` wires the ownership check into the manager's start logic (refuse to start if another owner holds the lease).

**Tests:**
- `InputOwnershipTest` — acquire / double-acquire / release semantics.
- `SequenceManagerReactivesTest` — clear + register round-trip; reactives survive a run.

### Task 5. `WorldSnapshot` view scaffolding + new views + observers

**Files:**
- Modify: `sequence/WorldSnapshot.java`
- New: `sequence/views/InventoryView.java`
- New: `sequence/views/InteractionView.java`
- New: `sequence/views/InteractionMode.java`
- New: `sequence/views/ItemStack.java`
- New: `sequence/internal/InventoryObserver.java`
- New: `sequence/internal/InteractionObserver.java`
- Inspect `sequence/internal/ClientObserver.java` — modify to compose the two new observers; extend snapshot builder.
- Inspect `sequence/internal/ImmutableWorldSnapshot.java` (may exist) — create if absent.

**Behavior:**
- `WorldSnapshot` gains three `default` methods: `inventory()` returning `InventoryView.empty()`, `interaction()` returning `InteractionView.empty()`, `grandExchange()` returning `GrandExchangeView.empty()`.
- All existing `WorldSnapshot` implementations compile without changes (default methods).
- `InventoryView`: `count(itemId)`, `freeSlots()`, `items()`. Empty impl returns 0 / 28 / empty list.
- `InteractionView`: `mode()`, `blockingInterface()`. Empty impl returns `FREE` / empty Optional.
- `InventoryObserver` reads `ItemContainer(InventoryID.INVENTORY)` on client thread.
- `InteractionObserver` reads widget tree to detect blocking interfaces.

**Note:** `GrandExchangeView` and its observer are in Task 6. The `default grandExchange()` default method is added here so the type compiles before Task 6 adds the real observer.

**Tests:**
- `WorldSnapshotDefaultViewsTest` (F5) — a `WorldSnapshot` impl not overriding `inventory()` returns `InventoryView.empty()` with `count(*) == 0`, `freeSlots() == 28`. Same for `interaction()` and `grandExchange()`.

### Task 6. `GrandExchangeView` + `GrandExchangeOfferView` + enums + observer

**Files:**
- New: `sequence/views/{GrandExchangeView, GrandExchangeOfferView, OfferSide, OfferStatus}.java`
- New: `sequence/internal/GrandExchangeObserver.java`
- Modify: `sequence/internal/ClientObserver.java` — compose `GrandExchangeObserver` into the snapshot pipeline.

**Behavior:**
- Per spec §6. `GrandExchangeView` interface with `open()`, `offerSetupOpen()`, `collectOpen()`, `offers()`, `firstEmptySlot()`, `emptySlotCount()`, `offersFor(itemId, side)`, `empty()` null-object.
- `GrandExchangeOfferView` record per spec §6; `isEmpty / isComplete / isActive / isCancelled` predicates.
- `GrandExchangeObserver.read(tick)` per spec §6.2. Inspect InterfaceID field names at task time.
- `ClientObserver` compose: `snapshot.grandExchange()` now returns the observer's output (not the default empty).

**Tests:**
- `GrandExchangeOfferViewTest` — predicate truth tables for each status.
- `GrandExchangeViewEmptyTest` — `empty()` → `firstEmptySlot() == OptionalInt.of(0)`; `emptySlotCount() == 8`; `offersFor(*) == []`.
- `GrandExchangeOfferStateMappingTest` — covers spec §6.1 mapping for all API states.

### Task 7. Test fixtures: `GeSnapBuilder`, `RecordingGeActions`, `GeEngineHarness`

**Files (test only):**
- `sequence/activities/ge/{GeSnapBuilder, RecordingGeActions, GeEngineHarness}.java`

**Behavior:**
- `GeSnapBuilder`: fluent builder. Produces a `WorldSnapshot` impl backed by the now-existing `InventoryView`, `InteractionView`, `GrandExchangeView` (from Tasks 5–6). Exposes `withInventory(...)`, `withInteraction(...)`, `withGrandExchange(...)`.
- `RecordingGeActions implements GeActions` — in-memory call list as serialized strings.
- `GeEngineHarness` — wraps `SequenceManager.withDefaults()` with a fixture observer over a queued snapshot list and a `MockInputDispatcher`. Provides `queue(...)`, `run(rootStep)`, `advance(n)`, `state()`.

**Tests:**
- `GeFixturesSelfTest` — snap builder wires the GE view; `RecordingGeActions` tracks calls in order.

### Task 8. Domain types

**Files:**
- New: `sequence/activities/ge/{BuyItemIntent, SellItemIntent, PricePolicy, OfferWaitPolicy}.java`
- New: `sequence/activities/ge/GeBlackboardKeys.java`
- New: `sequence/activities/ge/GeActions.java` interface

**Behavior:**
- Records with compact constructors validating positive values.
- `PricePolicy` sealed, only `Exact(coinsEach)` implemented.
- `OfferWaitPolicy` factory methods `until / untilOrPartial / noWait`.
- `GeBlackboardKeys.K_GE_OFFER_SLOT` at scope SEQUENCE.
- `GeActions` interface per spec §10.

**Tests:**
- `BuyItemIntentTest` — quantity must be positive.
- `PricePolicyTest` — `Exact(0)` rejected; `Exact(100).coinsEach() == 100`.
- `OfferWaitPolicyTest` — factory methods produce expected fields.

### Task 9. Guard steps and `EnsureNoBlockingInterfaceStep`

**Files:**
- New: `sequence/activities/EnsureNoBlockingInterfaceStep.java` (shared, NOT under `ge/` or `banking/`)
- New: `sequence/activities/ge/EnsureAtGrandExchangeStep.java`
- New: `sequence/activities/ge/OpenGrandExchangeStep.java`
- New: `sequence/activities/ge/EnsureNoConflictingOfferStep.java`

**Behavior:**

`EnsureNoBlockingInterfaceStep`:
- Reactive step. `canStart=false` (blocks) when a non-allowed blocking interface is present (`interaction().mode() != FREE` and `interaction().blockingInterface()` root id not in `allowList`). `canStart=true` otherwise.
- `onStart`: dispatch close action on the blocking interface.
- `check`: `Succeeded` when `interaction().mode() == FREE` or interface is in allowList; `Failed(WorldInteractionBlocked)` after timeout.
- `allowList` is a constructor parameter (`Set<Integer>` of root widget ids).

`EnsureAtGrandExchangeStep`:
- canStart=true; fatal `NotAtGrandExchange(area)` in onStart if player outside `geArea`.
- Recovery: Abort.
- **Tests:** in-area succeeds; out-of-area aborts with diagnostic; `MockInputDispatcher.dispatchCount() == 0`.

`OpenGrandExchangeStep`:
- Already-satisfied: `ge.open()`.
- canStart=false waitable: `WorldInteractionBlocked(by)` where `by.canBeClosed()`.
- Fatal preconditions: non-closable blocking interface, `PinKeypadUp`.
- Dispatches `geActions.openGrandExchange()` in onStart.
- check: `Succeeded` when `ge.open()`; `Failed(GeNotOpen)` after timeout.
- Recovery: Retry(3) then Abort.
- **Tests:** already-open; non-closable blocker fatal; closable blocker waits then opens; timeout aborts.

`EnsureNoConflictingOfferStep`:
- canStart=true. Pure verifier — no dispatch.
- Fatal: `GeExistingOfferConflict` if any non-EMPTY slot for `(itemId, side)`.
- **Tests:** no offers → succeeds; ACTIVE BUY for itemId → fatal; COMPLETE BUY → fatal; ACTIVE SELL for same itemId → succeeds (different side).

### Task 10. `Create*OfferStep` sub-steps

Sub-steps per spec §9.3. Each a separate file.

**Files:**
- New: `sequence/activities/ge/StartOfferStep.java`
- New: `sequence/activities/ge/SelectItemStep.java`
- New: `sequence/activities/ge/SetQuantityStep.java`
- New: `sequence/activities/ge/SetPriceStep.java`
- New: `sequence/activities/ge/ConfirmOfferStep.java`

**StartOfferStep(side)**
- onStart: dispatch `geActions.clickOfferSlotButton(slot = ge.firstEmptySlot().orElseThrow(), side)`.
- Fatal: `GeNotOpen` (no dispatch), `GeSlotsFull` (firstEmptySlot() empty).
- check: `Succeeded` when `ge.offerSetupOpen()`; timeout → `Failed(GeOfferSetupNotOpen)`.
- Recovery: Retry(2) for `GeOfferSetupNotOpen`; Abort otherwise.
- **Tests:** happy path; slots-full fatal; ge-not-open fatal; setup-never-opens → retry then abort.

**SelectItemStep(itemId, displayName)**
- onStart: dispatch `geActions.selectItem(itemId, displayName)`.
- check: `Succeeded` when setup widget shows target itemId; `Failed(GeOfferItemMismatch)` for wrong item; timeout → `Failed(GeOfferRejected("item-not-selected"))`.
- Inspect widget field name for selected-item id at task time.
- Recovery: Retry(2) for `GeOfferRejected`; Abort for mismatch.
- **Tests:** correct item; wrong item; timeout.

**SetQuantityStep(qty)** and **SetPriceStep(priceEach)**
- onStart: one humanized-dispatch chain (click *X widget + type value).
- check: `Succeeded` when widget shows requested value; mismatch → typed failure; timeout → `Failed(GeOfferRejected)`.
- Recovery: Retry(2) for `GeOfferRejected`; Abort for mismatch.
- **Tests:** happy path each; mismatch each; timeout each.

**ConfirmOfferStep(itemId, side, qty, priceEach)**
- onStart: dispatch `geActions.confirmOffer()`. Fatal: `GeNotOpen`.
- check: look up `ge.offers()` for slot matching `(itemId, side, qty, priceEach)`:
  - Match → write `K_GE_OFFER_SLOT = slot` to SEQUENCE scope; return `Succeeded`.
  - Mismatched single field → `Failed(GeOffer{Quantity/Item/Price}Mismatch)`.
  - Not found within `timeoutTicks` → `Failed(GeOfferRejected("not-surfaced"))`.
- Recovery: Retry(2) for `GeOfferRejected`; Abort for mismatch.
- **Tests:** happy path → `K_GE_OFFER_SLOT` in SEQUENCE scope after success; mismatch per field; not-surfaced timeout. Mirrors `WithdrawItemStep.check` pattern (verification + blackboard inline).

### Task 11. `WaitForOfferStep` + `CollectOfferStep`

**Files:**
- New: `sequence/activities/ge/WaitForOfferStep.java`
- New: `sequence/activities/ge/CollectOfferStep.java`

**WaitForOfferStep(waitPolicy)**
- canStart=true. Pure passive wait.
- onStart: records step-private `K_START_TICK`. Reads slot from `K_GE_OFFER_SLOT` (SEQUENCE).
- check:
  - `slotView.isComplete()` → `Succeeded`.
  - Timeout with `acceptPartialOnTimeout=true` and `completedQuantity > 0` → `Succeeded`.
  - Timeout with partial progress → `Failed(GeOfferIncomplete(slot, completed, requested))`.
  - Timeout with no progress → `Failed(GeOfferTimeout(slot, ticks))`.
  - Else → `Running`.
- Recovery: Abort.
- **Tests:** complete arrives; partial+accepted; zero-progress+timeout+!acceptPartial → `Failed(GeOfferTimeout)` (assert no cancel-offer dispatch — spec scenario 14/15); partial+timeout+!acceptPartial → `Failed(GeOfferIncomplete)`.

**CollectOfferStep**
- canStart=true. Reads slot from `K_GE_OFFER_SLOT` (SEQUENCE scope).
- Already-satisfied: `slotView.isEmpty()` (treat as collected externally, no dispatch).
- onStart: read slot/side from snapshot; compute `expectedDeltaItemId` (BUY → `slotView.itemId()`; SELL → `ItemID.COINS`); persist `K_EXPECTED_DELTA_ITEM_ID`, `K_EXPECTED_DELTA_START = inv.count(expectedDeltaItemId)`, `K_START_TICK` to STEP scope; fatal `GeNotOpen`; else dispatch `geActions.collect(slot)`.
- check: slot EMPTY AND inv delta > 0 → `Succeeded`; timeout → `Failed(GeCollectFailed(slot, expectedDeltaItemId, observedDelta))`.
- Recovery: Retry(2) for `GeCollectFailed`; Abort for `GeNotOpen`.
- **Tests:** already-empty satisfied (no dispatch); BUY collect → `Succeeded` with item delta; SELL collect → `Succeeded` with coin delta; slot-empties-but-no-inv-delta → `Failed(GeCollectFailed)` (spec scenario 17); GE not open → fatal; slot-never-empties → `Failed(GeCollectFailed)`.

### Task 12. `GrandExchangeSequenceFactory` + `GrandExchangeSequencePlan`

**Files:**
- New: `sequence/activities/ge/GrandExchangeSequenceFactory.java`
- New: `sequence/activities/ge/GrandExchangeSequencePlan.java`

**Behavior:**
- `GrandExchangeSequencePlan = record(Step root, List<Step> reactiveSteps)`.
- `buyCore(BuyItemIntent, WorldArea geArea, GeActions ge)`:
  - Validate `totalCost = Math.multiplyExact(qty, priceEach)` — overflow → fail-fast.
  - Build linear per spec §9.1 (7 steps).
  - Reactives: single `EnsureNoBlockingInterfaceStep(allowList(GE_ROOTS))`.
  - No `bankBoothIds`, no `BankActions` params.
- `sellCore(SellItemIntent, WorldArea geArea, GeActions ge)`: mirror.
- Private `buildCreateBuyOfferStep(intent, ge)`: returns `LinearSequence("CreateBuyOffer", [StartOffer(BUY), SelectItem, SetQuantity, SetPrice, ConfirmOffer])`.
- Private `buildCreateSellOfferStep(intent, ge)`: mirror with SELL.
- **Phase B note:** `buyWithBankPrep` / `sellWithBankPrep` are NOT implemented now. Add a
  class-level javadoc note: "Phase B will add buyWithBankPrep / sellWithBankPrep once the
  banking step library lands. They compose banking steps (OpenBank → WaitForBankReady →
  WithdrawItem → CloseBank) before the GE Core sequence."

**Tests (E2E):**
- `planExposesRootAndReactives` — `plan.reactiveSteps()` has exactly one `EnsureNoBlockingInterfaceStep`; allow-list contains `GE_ROOTS` only (no `BANK_ROOTS`).
- `buyCorePlanHasNoBankActions` — no `OpenBankStep` or bank-related step in the linear root's child list.
- `buyCoreHappyPath` (spec scenario 1) — tick-by-tick fixture drives buy to IDLE; `RecordingGeActions` shows expected click sequence; no bank actions recorded.
- `sellCoreHappyPath` (spec scenario 2) — mirror.
- `reactivePreemptsOnDialogMidFlow` (spec scenario 18) — mid-snapshot blocker, reactive preempts `WaitForOfferStep`, dismiss verified, linear resumes.
- `geWidgetDoesNotPreemptCreate` (spec scenario 19) — GE open during `CreateBuyOffer` does NOT trigger reactive (allow-list check).

### Task 13. `GeInteraction` (production `GeActions` impl)

**Files:**
- New: `sequence/activities/ge/GeInteraction.java`

**Behavior:**
- Per spec §10.1. Constructor: `Client`, `ClientThread`, `HumanizedInputDispatcher`.
- Each method resolves widget bounds via `PixelResolver` at click time (fresh resolution per click — CLAUDE.md §7).
- `selectItem`: choose between typing-into-search-then-click-result and direct varc-set at impl time; document in class javadoc.
- Fail-silent if target widget hidden.

**Tests:**
- None directly. `RecordingGeActions` (Task 7) is the test surface.

### Task 14. `GrandExchangeScript`

**Files:**
- New: `recorder/scripts/GrandExchangeScript.java`

**Behavior:**
- Per spec §12.1.
- Constructor: `Client`, `ClientThread`, `HumanizedInputDispatcher`, `GeInteraction`, `InputOwnership`, `WorldArea geArea`.
- No `BankInteraction`, no `bankBoothIds` in Phase A.
- `OWNER_TOKEN = "ge-script"` (distinct from banking's `"cooking-banking"`).
- `startBuy(BuyItemIntent)`, `startSell(SellItemIntent)`, `stop()`, `state()`, `status()`.
- `onGameTick()` entry point; advances engine tick when intent in flight.
- Race-guarded via `intentStartRequested` AtomicBoolean.
- `geManager.run(...)` try/catch-guarded (release lease + reset flag on exception).
- `tickInFlight` AtomicBoolean prevents queue-stacking.

**Tests:**
- `GrandExchangeScriptStateMachineTest` — start while idle succeeds; start while running fails; stop releases lease; race-guard prevents double-start.

### Task 15. `RecorderPanel` GE tab + `RecorderPlugin` wiring

**Files:**
- Modify: `recorder/RecorderPanel.java` — add new tab to existing `JTabbedPane`.
- Modify: `recorder/RecorderPlugin.java` — construct `GrandExchangeScript`, expose to panel.

**RecorderPanel behavior:**
- Tab label: **"GE Core Mode"**.
- Pre-flight note displayed in tab: "Requires you to already be at GE and have coins/items in inventory."
- Item-id field (load-bearing) + item-name field (decorative).
- Quantity `JSpinner` (≥1), Price `JSpinner` (≥1).
- Wait-policy selector (until-complete / partial-ok).
- Buy / Sell / Stop buttons; disable Buy/Sell while task in flight.
- Status `JLabel` + telemetry `JTextArea` (last 8 `RingBufferTelemetry` records). Polled via Swing `Timer` once per second.
- "Prepare from bank first" checkbox — **visible but disabled**; tooltip: "Available after sequence banking proof lands".
- Stop calls `script.stop()`.

**RecorderPlugin behavior:**
- Inspect current `RecorderPlugin.java` before editing. Mirror how `CookingScript` is injected.
- Construct `GeInteraction` with existing `HumanizedInputDispatcher`.
- Construct `GrandExchangeScript` with `InputOwnership` (construct a new singleton for Phase A; banking will share it at rebase time).
- Pass `WorldArea geArea` for the Varrock GE area (inspect correct constants at task time).
- `@Subscribe public void onGameTick(GameTick e) { grandExchangeScript.onGameTick(); }`.

**Tests:**
- None (Swing UI tests not part of proof). Manual verification is the acceptance gate.

---

## Phase B — after banking lands (documented, not implemented now)

**Precondition:** banking's step library (`OpenBankStep`, `WaitForBankReadyStep`,
`WithdrawItemStep`, `CloseBankStep`, `BankActions`, `WithdrawQuantity`) and banking's
`BlockReason` domain records (`BankNotOpen`, etc.) are on a rebase target.

### Task B1. `GrandExchangeSequenceFactory` bank-prep methods

Add `buyWithBankPrep(BuyItemIntent, WorldArea, List<Integer> bankBoothIds, BankActions, GeActions)` and `sellWithBankPrep(...)`. These prepend `OpenBank → WaitForBankReady → WithdrawItem → CloseBank` before the GE Core sequence.

Update allow-list in bank-prep factory to `allowList(BANK_ROOTS, GE_ROOTS)`.

### Task B2. `GrandExchangeScript` bank-prep methods

Add `startBuyWithPrep(BuyItemIntent)` and `startSellWithPrep(SellItemIntent)` that call the bank-prep factory variants. Add constructor params for `BankInteraction` / `bankBoothIds`.

### Task B3. `RecorderPanel` bank-prep checkbox

Enable the "Prepare from bank first" checkbox. When checked, `Buy` / `Sell` buttons call `startBuyWithPrep` / `startSellWithPrep` instead of `startBuy` / `startSell`. Remove the disabled-tooltip.

---

## Scenario coverage map (spec §13 → tests)

| # | Scenario | Test class · method | Task |
|---|---|---|---|
| 1 | BuyCore happy path | `GrandExchangeSequenceFactoryTest.buyCoreHappyPath` | 12 |
| 2 | SellCore happy path | `GrandExchangeSequenceFactoryTest.sellCoreHappyPath` | 12 |
| 3 | Not at GE | `EnsureAtGrandExchangeStepTest.notAtArea` | 9 |
| 4 | Insufficient coins | `CreateBuyOfferStepTest.insufficientCoinsFatal` (or `StartOfferStepTest` — whichever step absorbs the check; see spec §9.2) | 10 |
| 5 | Insufficient sell items | `CreateSellOfferStepTest.insufficientItemsFatal` | 10 |
| 6 | Existing active BUY conflict | `EnsureNoConflictingOfferStepTest.existingActiveBuyConflicts` | 9 |
| 7 | All slots full | `StartOfferStepTest.slotsFullFatal` | 10 |
| 8 | GE not open during create | `StartOfferStepTest.geNotOpenFatal` | 10 |
| 9 | Offer creation rejected | `ConfirmOfferStepTest.notSurfacedTimeout` | 10 |
| 10 | Quantity mismatch | `ConfirmOfferStepTest.quantityMismatch` | 10 |
| 11 | Item mismatch | `ConfirmOfferStepTest.itemMismatch` | 10 |
| 12 | Price mismatch | `ConfirmOfferStepTest.priceMismatch` | 10 |
| 13 | Buy partial accepted | `WaitForOfferStepTest.partialAccepted` | 11 |
| 14 | Buy timeout no partial | `WaitForOfferStepTest.timeoutNoPartial` | 11 |
| 15 | Sell partial accepted | `WaitForOfferStepTest.partialAcceptedSell` (parameterized variant) | 11 |
| 16 | Sell timeout no partial | `WaitForOfferStepTest.timeoutNoPartialSell` | 11 |
| 17 | Collect slot-empty but no delta | `CollectOfferStepTest.collectEmptiesSlotButItemDeltaMissing` | 11 |
| 18 | Reactive preempts blocking dialog | `GrandExchangeSequenceFactoryTest.reactivePreemptsOnDialogMidFlow` | 12 |
| 19 | GE widget does not preempt | `GrandExchangeSequenceFactoryTest.geWidgetDoesNotPreemptCreate` | 12 |
| F1 | Completion/Failure diagnostic plumbing | `FoundationCompletionFailureTest` | 2 |
| F2 | Retry cumulativity | `FoundationRetryBoundTest` | 3 |
| F3 | STEP scope ordering | `FoundationStepScopeOrderTest` | 3 |
| F4 | canStart gate | `FoundationCanStartGateTest` | 3 |
| F5 | WorldSnapshot default views | `WorldSnapshotDefaultViewsTest` | 5 |

Plus regression: all existing engine tests (LoginRunner, etc.) and any existing sequence tests must continue to pass.

---

## Acceptance checklist

Verify spec §15 in full after Task 15 lands. Spot checks:

- `./gradlew :client:compileJava` passes from a fresh checkout against `master`. No banking foundation needed.
- No file in the §4 no-touch list has been edited.
- `GrandExchangeSequenceFactory.buyCore(...)` and `.sellCore(...)` take no `bankBoothIds` or `BankActions` params.
- `CreateBuyOfferStep.onStart` checks `inv.count(COINS) >= qty*priceEach`; fatal `InsufficientCoins` if not.
- `CreateSellOfferStep.onStart` checks `inv.count(itemId) >= qty`; fatal `InsufficientSellItems` if not.
- `WaitForOfferStep` typed timeouts fire from `check()` BEFORE the engine's generic `Failure.timeout`. No auto-abort dispatch (assert `MockInputDispatcher` shows no cancel-offer call).
- `CreateBuy/SellOfferStep` is a `LinearSequence` of five sub-steps, each dispatching at most one mutating action in `onStart`.
- `K_GE_OFFER_SLOT` is SEQUENCE-scoped; survives between `ConfirmOfferStep` (writer), `WaitForOfferStep`, `CollectOfferStep` (readers).
- No GE step holds mutable instance state.
- No GE step imports `net.runelite.api.widgets.Widget`.
- No GE step calls `dispatcher.dispatch(...)` directly.
- `RecorderPanel` tab label is "GE Core Mode". Pre-flight note displayed. "Prepare from bank first" checkbox visible but disabled with correct tooltip.
- `GrandExchangeScript.OWNER_TOKEN = "ge-script"`.
- `geManager.run(plan.root())` is try/catch-guarded.
- `Retry(N)` cumulativity test (F2) passes.
- STEP scope ordering test (F3) passes.
- `git diff master..HEAD --stat` shows only files in spec §4 touch list (no `CookingScript.java`, no `sequence/activities/banking/**`, no `RecorderConfig.java`).
