# Sequence-engine adoption proof: cooking-script banking

**Date:** 2026-04-29
**Status:** Design approved (brainstorming complete); revised 2026-04-29 per spec-review #4 — awaiting user re-review before plan
**Owner:** mantas
**Worktree:** to be created (`worktree-sequence-banking-proof`)

---

## 0. Revision history

**2026-04-29 #4:** Eliminated last `canStart=false` waitable from `WithdrawItemStep`. `BankContentsUnknown` (`availability(itemId) == UNKNOWN`) is now a fatal precondition detected in `onStart` → `K_PRECONDITION_FAILURE=BankContentsUnknown` → `Failed(BankContentsUnknown)` → `Abort`. Rationale: `WaitForBankReadyStep` already guarantees `bank.ready() == true` before `WithdrawItemStep` starts; `ready()` means the bank container is loaded; `UNKNOWN` after that indicates an observer bug or snapshot inconsistency, not a transient state the engine should wait through. No banking step now uses `canStart=false` except `OpenBankStep` (which retains `WorldInteractionBlocked(by)` as waitable — the reactive `EnsureNoBlockingInterfaceStep` can clear it). Updated §7 table, §9.2, §9.3 canStart/onStart, commentary, and acceptance criteria accordingly.

**2026-04-29 #3:** Fixed `canStart=false` waitable-timeout hole — a step that never starts never accrues ticks, so a waitable blocker can stall forever. Resolution: `WaitForBankReadyStep` converted to `canStart=true`; owns its wait and typed timeout (`Failed(BankNotReady)`) in `check()`, no longer uses canStart-blocking. `PinKeypadUp` made fatal (not waitable) in `OpenBankStep` and `WithdrawItemStep` — no PIN handler is in scope, so stalling forever is wrong; sequences abort with `Failed(PinKeypadUp)`. `BankNotOpen` in `DepositItemStep` and `WithdrawItemStep` made a fatal precondition (not waitable) — after `OpenBank` has already succeeded, a closed bank means unexpected closure or misclick; abort and let the script recover at the script level. `WithdrawItemStep` waitable blockers now limited to `BankContentsUnknown` only. Removed `Exact(q)` from `WithdrawQuantity` sealed type (it had identical resolution semantics to `AtLeast(q)`, misleading name); all usages updated to `AtLeast`. Fixed `BlockReason.Unknown` → `DiagnosticReason.Unknown` in `InputOwnership` mid-sequence failure. Added `clearReactives()` call before re-registration on each banking run (reactive steps accumulated across trips). Added try/catch guard around `bankingManager.run(plan.root())` to reset `bankingStartRequested` and release `InputOwnership` on exception. Added `EnsureNoBlockingInterfaceStep.canStart` precision note (only when blocking interface IS present AND NOT in allow-list). Added test #16 (bank interface must not trigger reactive preemption during `WithdrawItem`). Added `STEP` scope ordering constraint (cleared after terminal transition, not between `onStart` and same-tick `check`). Added 8 acceptance criteria. Added open question for Lumbridge logs-on-ground wait (fire-from-logs flow).

**2026-04-29 #2:** Resolved `canStart` wait-vs-fatal-vs-already-satisfied semantics (fatal preconditions now encode as `canStart=true` + `onStart` records `K_PRECONDITION_FAILURE` + first `check` returns `Failed(BlockReason)`). Fixed `WithdrawQuantity` semantics — quantities mean *final desired inventory count*; step computes `delta = target - currentCount`; `FillRemainingInventory` already-satisfied iff `freeSlots == 0`. Stated partial-final-trip policy explicitly (allowed). Wired typed timeout reasons via `check()` returning `Failed(WithdrawNoOp(...))` deterministically before the engine's generic timeout. Expanded `BankingSequenceFactory` signature to take full domain context (`CookingLocation`, `CookingFood.Entry`); narrowed deposit cleanup to current-food cooked/burnt. Introduced `BankingSequencePlan { root, reactiveSteps }` so callers register reactive steps explicitly. Centralized `LAST_BLOCK_REASON` in a shared `SequenceBlackboardKeys` class. Renamed engine-layer typed reason from `BlockReason` to `DiagnosticReason` (`BlockReason` becomes a bank-domain sub-interface). Added `WorldSnapshot` default-method note for implementers. Guarded async `bankingManager.run(root)` start race. Rephrased test #6 as snapshot-derived reactive preemption. Added 9 acceptance criteria.

**2026-04-29 #1:** Initial design.

---

## 1. Problem

The repo has a substantial sequence engine under `runelite-client/src/main/java/net/runelite/client/sequence/` (≈25 production files + ≈25 tests, landed in commit `564de7dde sequence-engine: squash 88 commits from worktree-sequence-engine`). It implements exactly the architecture you'd want for a robust bot:
`SequenceEngine`, `Step` (with `canStart` / `onStart` / `onEvent` / `tick` / `check` / `onFailure`), `Completion`, `Recovery`, `Failure`, `PreemptionPolicy`, `WorldSnapshot`, `PriorityPlanner`, `Executor`, `ActionBudget`, `FrameStack`, `LinearSequence` / `Selector` / `RepeatStep`, scoped `Blackboard`, and `RingBufferTelemetry`. It is wired as a RuneLite plugin (`SequencerPlugin`) which ticks the engine on `GameTick` and forwards game events. A full vertical slice — `LoginRunner` — runs on top of it.

But none of the gameplay scripts in `runelite-client/.../plugins/recorder/` use the engine. `CookingScript` (1158 lines), `ChickenFarmV3Script` (530 lines), and `LumbridgeBankPenScript` are hand-rolled enum FSMs with daemon `tickLoop`s that reinvent throttles, retry budgets, stuck detection, and verification by hand. Each new script repays this tax. Two reasons:

1. **`WorldSnapshot` is impoverished.** Today it exposes `tick()` + `PlayerView` only — no inventory, no widget state, no bank state, no interaction mode. A `Step.check()` has nothing to verify gameplay against, so the engine cannot model a banking flow.
2. **The engine is undocumented in `CLAUDE.md`/file-map.** Future agents read the legacy enum-FSM scripts and copy the wrong pattern.

## 2. Goal

Make the existing engine usable for one real gameplay vertical — the banking/inventory-prep slice of `CookingScript` — by:

- Extending `WorldSnapshot` with the minimum domain views needed for banking + inventory verification.
- Adding `InteractionMode` / `BlockingInterface` / a typed `BlockReason` model.
- Adding an affordance layer that reports allowed/blocked actions with diagnostic reasons.
- Adding a small banking step library.
- Porting only the banking phase of `CookingScript` to the engine, behind a feature flag, with the legacy path preserved.
- Writing tests for the failure modes that matter (missed clicks, container not ready, item disappears, blocking dialog mid-flow, timeout).
- Documenting the engine so future agents adopt it instead of copying enum-FSM scripts.

## 3. Scope

### In scope
- `WorldSnapshot` expansion: `InventoryView`, `BankView`, `WidgetView`, `InteractionView`, `EventFacts`.
- `affordance/` package: `ActionKind`, `DiagnosticReason` (engine-generic sealed parent), `BlockReason` (bank-domain sealed sub-interface of `DiagnosticReason`), `BlockingInterface`, `Affordance`, `AffordanceReport`.
- `Presence` tri-state for bank items; `BankItemAvailability` carrying `Presence` + `OptionalInt knownCount` + `boolean visible`.
- New observers: `InventoryObserver`, `BankObserver`, `WidgetObserver`, `InteractionObserver`, composed by an extended `ClientObserver`.
- Banking step library: `EnsureAtBankStep`, `EnsureNoBlockingInterfaceStep`, `OpenBankStep`, `WaitForBankReadyStep`, `DepositItemStep` (one per item), `WithdrawItemStep`, `EnsureInventoryMatchesLoadoutStep`, `CloseBankStep`.
- `WithdrawQuantity` sealed type: `AtLeast(qty)`, `FillRemainingInventory()`. (`Exact` removed — see §8.)
- `BankingSequenceFactory.prepareCookingLoadout(location, food, needsTinderbox, bank)` → `BankingSequencePlan` (linear `root` + explicit `reactiveSteps` list including `EnsureNoBlockingInterfaceStep`). Caller `bankingManager.registerReactive(...)`s the reactive list before `bankingManager.run(root)`.
- `InputOwnership` singleton + lease checks at sequence-start (not per-tick).
- `CookingScript` integration: new `BANKING_VIA_ENGINE` state; legacy `tickBankingLegacy()` retained behind `RecorderConfig.useEngineBanking()`.
- Tests for the 17 scenarios in §13.
- `runelite-client/.../sequence/ARCHITECTURE.md` + a short `CLAUDE.md` pointer.
- Renaming the engine-layer typed reason from `BlockReason` to `DiagnosticReason`; `BlockReason` becomes a bank-domain sealed sub-interface of `DiagnosticReason`. Extending `Completion.Failed` and `Failure` to optionally carry a typed `DiagnosticReason`.
- Centralizing the typed-block key in `SequenceBlackboardKeys.LAST_BLOCK_REASON` (no private per-step keys); planner records `(stepName, reason)` to telemetry at the reject site.

### Out of scope
- Migrating `ChickenFarmV3Script`, combat (`ChickenCombatLoop`, `TrainingSession`, `CombatStyleSwitcher`), or `LumbridgeBankPenScript`.
- Walking inside the engine. `EnsureAtBankStep` is a guard, not a walker. `CookingScript` keeps its existing `WALK_TO_BANK` state and only hands off once the player is in the bank area.
- Pushing RuneLite events into `CookingScript`'s engine. Verification in this proof is snapshot-based only; `Step.onEvent` is a no-op for banking steps. Wiring `@Subscribe` event forwarding into the recorder plugin's manager is deferred.
- Shared-singleton `SequenceManager` across plugins. `CookingScript` constructs its own; `SequencerPlugin` keeps its own.
- A wider OSRS world model (NPCs, ground items, scene objects, varbits, skills xp).
- Deletion of `tickBankingLegacy()` body.

## 4. Existing engine inventory (read-only summary)

```
runelite-client/src/main/java/net/runelite/client/sequence/
  SequenceEngine.java             ✅ start/pause/resume/stop/registerReactive/advanceTick/offerEvent
  SequenceManager.java            ✅ scheduler-marshalled run/pause/resume/stop, withDefaults()
  SequenceState.java              ✅ IDLE / RUNNING / PAUSED / COMPLETED / FAILED
  Step.java                       ✅ canStart/onStart/tick/onEvent/check/onFailure (+ priority/timeout/preemption/isSafeToPause)
  Completion.java                 ✅ sealed: Running / Succeeded(reason) / Failed(reason)
                                     ⚠️ extend Failed to carry @Nullable DiagnosticReason
  Recovery.java                   ✅ sealed: Retry(maxAttempts) / Skip(reason) / Abort(reason) / JumpToAnchor(name)
                                     ⚑ `Retry(maxAttempts)` is cumulative: the engine tracks total attempts for the current step execution and aborts the step once attempt count > maxAttempts, regardless of what onFailure returns on the next call. Steps that return Retry(1) are therefore bounded at 1 retry (2 total attempts). Implementation MUST enforce this; if onFailure can return Retry(N) on every call the step would loop indefinitely.
  Failure.java                    ✅ record (reason, ticksElapsed, cause)
                                     ⚠️ extend with @Nullable DiagnosticReason + factory fromDiagnostic(DiagnosticReason, int)
  StepContext.java                ✅ actions(), bb(), snapshot(), currentTick(), inputMode(), log(msg)
  WorldSnapshot.java              ⚠️ expose only tick() + PlayerView — extend with new views (§5)
  PlayerView.java                 ✅ kept as-is
  PreemptionPolicy.java           ✅ NEVER / WHEN_SAFE / ALWAYS

  internal/StateDrivenEngine.java ✅ tick loop (drain → snapshot → planner → execute → check → recover)
  internal/PriorityPlanner.java   ✅ reactives + frame stack, preemption rules
  internal/FrameStack.java        ✅ LIFO frame stack
  internal/Executor.java          ✅ at most one mutating action per tick (ActionBudget)
  internal/ActionBudget.java      ✅ caps interactions/tick
  internal/Observer.java          ✅ interface
  internal/ClientObserver.java    ⚠️ extend to compose Inventory/Bank/Widget/Interaction observers
  internal/Actions.java           ✅ semantic action API for steps (walkTo etc.)

  composite/LinearSequence.java   ✅ runs children in order, fails-fast or skips per child onFailure
  composite/Selector.java         ✅ first-of-N
  composite/RepeatStep.java       ✅ N-times / until-condition

  blackboard/Blackboard.java      ✅ put/get/remove/scope/clear
  blackboard/ScopedBlackboard.java ✅ GLOBAL / RUN / SEQUENCE / STEP scopes
                                     ⚠️ engine must clear STEP scope between step transitions; verify in StateDrivenEngine

  telemetry/RingBufferTelemetry.java ✅ 2048-record ring buffer + subscribers

  activities/WalkStep.java        ✅ same-plane waypoint walker, NOT transport-aware (don't reuse for cooking-bank)
  activities/StepRegistry.java    ✅ factories registry
  activities/StepFactory.java     ✅ interface

  dispatch/HumanizedInputDispatcher.java ✅ isBusy, awaitIdle, runExclusive — primitives for InputOwnership

  login/LoginRunner.java          ✅ full vertical proof — engine-driven login + world picker
plugins/sequencer/SequencerPlugin.java ✅ GameTick → advanceTick; forwards 5 event types
```

Legend: ✅ kept as-is; ⚠️ touched by this proof.

## 5. WorldSnapshot expansion

All new views are interfaces (mirroring the existing pattern so `FixtureObserver` can hand-roll fakes). Concrete implementations live under `sequence/internal/`. `WorldSnapshot` itself stays an interface; existing callers (`LoginRunner`, `WalkStep`) only see new methods if they choose to.

```java
public interface WorldSnapshot {
    int tick();                          // existing
    @Nullable PlayerView player();       // existing
    InventoryView inventory();           // NEW
    BankView bank();                     // NEW
    WidgetView widgets();                // NEW
    InteractionView interaction();       // NEW
    EventFacts events();                 // NEW
}
```

> **Implementer impact (additive for callers, breaking for implementers).** Adding methods to `WorldSnapshot` is additive for *callers* (existing `LoginRunner` / `WalkStep` call sites compile unchanged) but breaking for *implementers* — every existing fixture, fake, or alternate snapshot must implement the new methods. To avoid churn in the existing ≈25 sequence-engine tests, the new view methods are declared as `default` returning empty/null-object views (`InventoryView.empty()`, `BankView.empty()`, `WidgetView.empty()`, `InteractionView.world()`, `EventFacts.none()`). Production observers override these with real readers; existing test fixtures keep compiling without modification; banking-step tests provide the views explicitly.

### 5.1 InventoryView

```java
public interface InventoryView {
    int size();                              // typically 28
    int freeSlots();
    boolean isFull();
    List<ItemStack> items();
    boolean contains(int itemId);
    int count(int itemId);
}

public record ItemStack(int slot, int itemId, int quantity) {}
```

### 5.2 BankView (with tri-state availability)

```java
public enum Presence { PRESENT, ABSENT, UNKNOWN }

public record BankItemAvailability(
    Presence presence,
    OptionalInt knownCount,    // present iff presence == PRESENT
    boolean visible             // currently scrolled into view
) {
    public static BankItemAvailability unknown()         { return new BankItemAvailability(Presence.UNKNOWN, OptionalInt.empty(), false); }
    public static BankItemAvailability absent()          { return new BankItemAvailability(Presence.ABSENT,  OptionalInt.empty(), false); }
    public static BankItemAvailability present(int n, boolean visible) {
        return new BankItemAvailability(Presence.PRESENT, OptionalInt.of(n), visible);
    }
}

public interface BankView {
    boolean open();                                  // bankmain widget visible
    boolean ready();                                 // InventoryID.BANK container loaded
    boolean pinUp();                                 // pin keypad visible
    BankItemAvailability availability(int itemId);   // tri-state + count + visible
}
```

`availability()` returns `UNKNOWN` for every id when `ready() == false` — the container has not loaded yet. `WaitForBankReadyStep` owns the wait-for-ready phase; once it succeeds, `ready() == true` is guaranteed. Callers that run after `WaitForBankReadyStep` (i.e., `DepositItemStep`, `WithdrawItemStep`) MUST treat `UNKNOWN` as a fatal observer inconsistency — not a signal to wait — because `UNKNOWN` after `ready()` means the observer failed to read the container, not that the container is still loading. `ABSENT` always means the item is not in the bank and should abort.

### 5.3 WidgetView (no raw `Widget` exposure)

```java
public interface WidgetView {
    boolean isVisible(int widgetId);                 // walks parent chain
    boolean isHidden(int widgetId);                  // explicit complement
    Set<Integer> visibleRootIds();                   // for affordance evaluation
}
```

Steps that need pixel geometry (e.g., to click a withdraw button) do not consult `WidgetView` — that work is inside `BankInteraction` (which already resolves widget bounds at click time via `PixelResolver`). The snapshot answers "is X visible?" not "give me the Widget."

### 5.4 InteractionView + affordance layer

```java
public enum InteractionMode {
    WORLD,           // default — can walk, click world, use inventory on world
    BANKING,         // bank widget open
    DIALOG,          // chat/level-up/skillmulti dialog
    SHOP,
    MENU_OPEN,       // right-click menu hovering
    LOADING,         // game state != LOGGED_IN
    INVENTORY_ONLY,  // some interface that allows inventory but not world
    UNKNOWN
}

public record BlockingInterface(
    String name,
    int rootWidgetId,
    boolean blocksWorld,
    boolean canBeClosed
) {}

public interface InteractionView {
    InteractionMode mode();
    boolean worldInteractionAvailable();
    boolean movementAvailable();
    Optional<BlockingInterface> blockingInterface();
    AffordanceReport affordances();
}
```

### 5.5 EventFacts (snapshot-derived, no event push)

For this proof, event push is out of scope. `EventFacts` is computed by the observer by diffing the previous tick's snapshot against the current one — no `Subscribe`-fed event stream into `CookingScript`'s engine.

```java
public interface EventFacts {
    int lastInventoryChangeTick();                   // -1 if no change observed
    int lastBankContainerChangeTick();
    int lastBlockingInterfaceChangeTick();
    int lastPlayerAnimationChangeTick();
}
```

`InventoryObserver` and `BankObserver` keep one-tick memos to compute these. No `Object` event objects are exposed — steps verify state from the snapshot, period.

## 6. Affordance layer

```java
public enum ActionKind {
    WALK,
    INTERACT_WORLD,
    INTERACT_INVENTORY,
    OPEN_BANK_BOOTH,
    USE_BANK_WIDGET,
    USE_INVENTORY_ON_OBJECT,
    USE_INVENTORY_ON_INVENTORY,
    CLOSE_BLOCKING_INTERFACE,
    DISMISS_DIALOG
}

// Engine-generic typed reason. Lives in sequence/affordance/. No bank/domain knowledge — the engine
// (Completion / Failure / Executor / telemetry) imports DiagnosticReason, never BlockReason.
public sealed interface DiagnosticReason permits
        BlockReason,
        DiagnosticReason.Loading,
        DiagnosticReason.ActionTimedOut,
        DiagnosticReason.Unknown {
    record Loading()                                                                      implements DiagnosticReason {}
    record ActionTimedOut(String stepName, int ticks)                                     implements DiagnosticReason {}
    record Unknown(String detail)                                                         implements DiagnosticReason {}
}

// Bank-domain typed reason. Sub-interface of DiagnosticReason. Banking steps + the affordance
// report use BlockReason; engine plumbing (Completion.Failed.diagnostic, Failure.diagnostic,
// SequenceBlackboardKeys.LAST_BLOCK_REASON) accept the broader DiagnosticReason.
public sealed interface BlockReason extends DiagnosticReason {
    record BankNotOpen()                                                                  implements BlockReason {}
    record BankNotReady()                                                                 implements BlockReason {}
    record BankContentsUnknown()                                                          implements BlockReason {}
    record BankMissingItem(int itemId, String name, int requiredQty)                      implements BlockReason {}
    record InventoryFull(int neededFreeSlots)                                             implements BlockReason {}
    record LoadoutMismatch(List<ItemDiff> diff)                                           implements BlockReason {}
    record DialogOpen(int rootWidgetId, String label)                                     implements BlockReason {}
    record MenuOpen()                                                                     implements BlockReason {}
    record PinKeypadUp()                                                                  implements BlockReason {}
    record NotAtLocation(WorldArea required)                                              implements BlockReason {}
    record WorldInteractionBlocked(BlockingInterface by)                                  implements BlockReason {}
    record WithdrawNoOp(int itemId, int ticks)                                            implements BlockReason {}
}

public record ItemDiff(int itemId, String name, int have, int want) {}

public record Affordance(
    ActionKind kind,
    Optional<DiagnosticReason> reason,
    List<ActionKind> suggestedRecoveries
) {
    public static Affordance allowed(ActionKind k) { return new Affordance(k, Optional.empty(), List.of()); }
    public static Affordance blocked(ActionKind k, DiagnosticReason r, ActionKind... recovers) {
        return new Affordance(k, Optional.of(r), List.of(recovers));
    }
    public boolean isAllowed() { return reason.isEmpty(); }
}

public record AffordanceReport(List<Affordance> entries) {
    public boolean isAllowed(ActionKind k)              { /* lookup */ }
    public Optional<Affordance> entry(ActionKind k)     { /* lookup */ }
    public List<Affordance> blocked()                   { /* filter !isAllowed */ }
}
```

### Engine-level diagnostic surfacing

To surface typed reasons end-to-end, two existing engine types are extended (additive — existing callers unaffected). The engine plumbing imports only `DiagnosticReason`; bank-domain `BlockReason` flows through transparently as a sub-interface.

```java
public sealed interface Completion {
    record Running() implements Completion {}
    record Succeeded(String reason) implements Completion {}
    record Failed(String reason, @Nullable DiagnosticReason diagnostic) implements Completion {
        public Failed(String reason) { this(reason, null); }
    }
    Running RUNNING = new Running();
    static Failed failed(DiagnosticReason r) { return new Failed(r.toString(), r); }
}

public record Failure(
    String reason,
    int ticksElapsed,
    @Nullable Throwable cause,
    @Nullable DiagnosticReason diagnostic
) {
    public static Failure timeout(int ticksElapsed)                                    { return new Failure("timeout", ticksElapsed, null, null); }
    public static Failure fromCheck(String reason, int ticksElapsed)                   { return new Failure(reason, ticksElapsed, null, null); }
    public static Failure fromException(Throwable t, int ticksElapsed)                 { return new Failure(t.getClass().getSimpleName() + ": " + t.getMessage(), ticksElapsed, t, null); }
    public static Failure fromDiagnostic(DiagnosticReason r, int ticksElapsed)         { return new Failure(r.toString(), ticksElapsed, null, r); }
}
```

The planner records typed reasons via the shared `SequenceBlackboardKeys.LAST_BLOCK_REASON` (a `BlackboardKey<DiagnosticReason>`; see §9.4) on every canStart-rejection, AND logs `<stepName>: blocked: <reason>` to telemetry immediately at the reject site — the planner does not rely solely on the mutable last-reason key (which would race when multiple candidate steps are evaluated). When a step transitions to FAILED, the executor records both the human-readable string and the typed `DiagnosticReason` to the ring buffer.

## 7. Step contract — three outcomes mapped onto two-valued `canStart`

The existing `Step` interface stays unchanged. The proof distinguishes **three** step outcomes — *waitable*, *fatal precondition failure*, *already satisfied* — using the existing two-valued `canStart`. The split lets `LinearSequence` distinguish "wait one more tick" from "abort the sequence" without changing `Step`.

| outcome | `canStart` | `onStart` action | first `check` returns |
|---|---|---|---|
| **Fresh work** | `true` | dispatch action; record `K_TARGET` / `K_START_TICK` etc. to step blackboard | `Running` (then `Succeeded` on a later tick) |
| **Already satisfied** | `true` | no-op (no dispatch, no work-implying state) | `Succeeded` on the first call |
| **Fatal precondition failure** | `true` | record `K_PRECONDITION_FAILURE = BlockReason` (no dispatch) | `Failed(BlockReason)` on the first call |
| **Waitable blocker** | `false` (writes typed `BlockReason` to `SequenceBlackboardKeys.LAST_BLOCK_REASON`) | not invoked | not invoked (planner re-evaluates next tick) |

Examples per outcome:

| outcome | `WithdrawItemStep` | `EnsureAtBankStep` | `EnsureInventoryMatchesLoadoutStep` |
|---|---|---|---|
| fresh work | bank PRESENT, freeSlots > 0, currentCount < target | (always fresh — pure verifier) | (always fresh — pure verifier) |
| already satisfied | currentCount ≥ minQty (AtLeast) **or** freeSlots == 0 (Fill) | player is in `bankArea` | inventory matches `loadout` |
| fatal precondition | `BankNotOpen`, `PinKeypadUp`, `BankContentsUnknown` (UNKNOWN after ready = observer bug), `BankMissingItem`, `InventoryFull` | `NotAtLocation(area)` | `LoadoutMismatch(diff)` |
| waitable | (none — `WithdrawItemStep` has no `canStart=false` conditions) | (none — guard never blocks) | (none — verifier never blocks) |

`WaitForBankReadyStep` is a passive-wait step with a different shape: `canStart=true` always; `onStart` no-op; `check` returns `Succeeded` when `bank.ready()`, `Failed(BankNotOpen)` if bank closes before becoming ready, or `Failed(BankNotReady)` after `timeoutTicks()` — the step owns its wait and its typed timeout entirely in `check()`.

`BankContentsUnknown` as a fatal precondition in `WithdrawItemStep`: once `WaitForBankReadyStep` has succeeded, `bank.ready() == true`, which means the bank container is loaded and `availability(itemId)` must resolve to `PRESENT` or `ABSENT`. `UNKNOWN` at this point indicates an observer inconsistency or snapshot bug, not a transient delay. The engine should not wait through a broken state; surfacing `Failed(BankContentsUnknown)` immediately makes the bug visible rather than masking it with indefinite retry.

### Why this split?

The unchanged `Step` interface only signals "ready vs not-ready" via `canStart`, so the engine alone cannot tell "wait a tick" from "abort the sequence". By moving fatal preconditions out of `canStart=false` and into `onStart` + `check Failed`, the engine receives a deterministic abort signal and `LinearSequence` aborts. Waitable blockers stay on `canStart=false` so the planner retries next tick. Reactive steps (e.g., `EnsureNoBlockingInterfaceStep`, §9.5) sit on top and preempt linear progress when their `canStart` flips to `true` mid-flow.

Without this split: a `canStart=false` for `BankMissingItem` would mean the planner waits forever for an item that will never appear, with no abort signal. With the split: `WithdrawItemStep.canStart` returns `true`, `onStart` records `K_PRECONDITION_FAILURE`, first `check` returns `Failed(BankMissingItem)`, `onFailure` returns `Abort`, sequence aborts cleanly.

### Rules

- `canStart=false` means "currently blocked, please retry next tick". **Critical constraint:** a `canStart=false` step that never starts also never accrues ticks — `onStart` and `check` are never called, so `timeoutTicks()` never fires. Therefore `canStart=false` is restricted to **blockers that a reactive step can realistically clear within a bounded number of ticks**. In this proof, only `OpenBankStep` uses `canStart=false`, and only for `WorldInteractionBlocked(by)` where the blocker is closable and in the allow-list (so `EnsureNoBlockingInterfaceStep` reactive will dismiss it). A `WorldInteractionBlocked` by a non-closable or unknown modal is a fatal precondition (`canStart=true`, `onStart` records it) — the reactive cannot clear it and waiting would stall forever.
- **Passive-wait steps** (e.g., `WaitForBankReadyStep`) use `canStart=true` always and own their wait + typed timeout entirely in `check()`. The step starts immediately, checks the condition each tick, and returns `Failed(BankNotReady)` after `timeoutTicks()` — guaranteeing bounded waiting without relying on the engine's generic `timeoutTicks()` path.
- `PinKeypadUp` is a fatal precondition everywhere in this proof (no PIN handler is in scope). Steps detect it in `onStart` → `K_PRECONDITION_FAILURE=PinKeypadUp`; first `check` returns `Failed(PinKeypadUp)` → `Abort`. Never `canStart=false`.
- `BankNotOpen` after `OpenBankStep` has already succeeded is a fatal precondition in `DepositItemStep` and `WithdrawItemStep` (unexpected closure = abort, not indefinite wait). These steps detect it in `onStart` → `K_PRECONDITION_FAILURE=BankNotOpen`. Never `canStart=false` for this condition.
- Fatal preconditions discriminated in `onStart` via `K_PRECONDITION_FAILURE` (a step-scoped `BlackboardKey<BlockReason>`); `check` reads it first and returns `Failed(BlockReason)` deterministically before any verification.
- Already-satisfied discriminated in `onStart` via `K_OUTCOME = Outcome.ALREADY_SATISFIED`; `check` returns `Succeeded` first call, no dispatch was sent.
- Steps that exist to *clear* a condition (`EnsureAtBankStep`, `EnsureNoBlockingInterfaceStep`, `EnsureInventoryMatchesLoadoutStep`, `CloseBankStep`) NEVER return `canStart=false`. They always start; the work is in `check`. This avoids "the step says it can't start, but no one else can fix the problem either" deadlocks.
- **STEP scope ordering:** `bb.clear(BlackboardScope.STEP)` must happen *after* the terminal transition is processed (i.e., after `check()` returns `Succeeded`/`Failed` and `onFailure` has been called), never between `onStart` and the same-tick `check()`. The immediate same-tick `check()` reads `K_OUTCOME` / `K_PRECONDITION_FAILURE` written by `onStart`; clearing before that drops these keys and breaks the already-satisfied and fatal paths.

## 8. WithdrawQuantity sealed type

`WithdrawQuantity` describes the *desired final inventory count* of an item (or a fill-target derived at `onStart`). The step computes a concrete `target` and `delta = target - currentCount` at `onStart` and writes both to step-scoped blackboard so `check` reads a deterministic target. No `Integer.MAX_VALUE` sentinels; no "withdraw exactly q" when "have q already" is already satisfied.

```java
public sealed interface WithdrawQuantity {
    /** Final inventory count of itemId should be at least q. Already satisfied if currentCount >= q. */
    record AtLeast(int qty)              implements WithdrawQuantity {}
    /** Withdraw enough to fill remaining inventory slots; resolved at onStart against bank knownCount and freeSlots. */
    record FillRemainingInventory()      implements WithdrawQuantity {}
}
```

> **Note:** `Exact(q)` has been removed. Its resolution semantics (`Math.max(q, currentCount)`, already-satisfied iff `currentCount >= q`) were identical to `AtLeast(q)`. "Exact" was misleading — it did not enforce `currentCount == q`, only `currentCount >= q`. For this proof only `AtLeast` and `FillRemainingInventory` are needed.

### 8.1 Resolution rules (applied at `onStart`)

| desired | target final count | delta to withdraw | already satisfied iff |
|---|---|---|---|
| `AtLeast(q)` | `Math.max(q, currentCount)` | `target - currentCount` | `currentCount >= q` |
| `FillRemainingInventory()` | `currentCount + Math.min(freeSlots, knownBankCount)` | `target - currentCount` | `freeSlots == 0` |

`FillRemainingInventory` is **never** "already satisfied" just because `currentCount >= 1`. It is satisfied only when the inventory has no room left to fill (`freeSlots == 0`). This was a bug in the prior draft (`minQty()` returned `1` for the Fill case, so a player with 1 raw food and 26 free slots would skip the withdraw).

### 8.2 Partial-final-trip policy (`FillRemainingInventory` only)

The proof allows partial final trips: if the bank has fewer of the item than the inventory has free slots, the step withdraws what the bank has (`min(freeSlots, knownBankCount)`) and proceeds. The full inventory is not a hard requirement; the only failure mode is *zero* of the item in the bank, which `WithdrawItemStep`'s `onStart` records as `BankMissingItem` (fatal — see §7). `EnsureInventoryMatchesLoadoutStep`'s loadout policy must therefore allow `rawFood count >= 1` (not `count == freeSlotsAtStart`) for cooking flows that may end on a partial trip.

If you want full trips only, use a separate `FillInventoryOrAbort` quantity (not in this proof) that requires `knownBankCount >= freeSlots`. Deferred.

### 8.3 What `bank.withdraw…` is called with

The step does NOT pass the original `WithdrawQuantity` value to `BankInteraction`. It passes the computed `delta`:

```java
if (delta == 0)                       /* no dispatch — already-satisfied path; check returns Succeeded */;
else if (delta == 1)                  bank.withdrawOne(itemId);
else if (delta == knownBankCount)     bank.withdrawAll(itemId);
else                                  bank.withdrawX(itemId, delta);
```

Worked examples:

| inventory currentCount | freeSlots | bank knownCount | desired | target | delta | dispatch |
|---|---|---|---|---|---|---|
| 3 | 25 | 100 | `AtLeast(5)` | 5 | 2 | `withdrawX(2)` (NOT `withdrawX(5)`) |
| 0 | 28 | 5 | `AtLeast(1)` (tinderbox) | 1 | 1 | `withdrawOne()` |
| 1 | 27 | 5 | `AtLeast(1)` (tinderbox) | 1 | 0 | none — already-satisfied |
| 1 | 27 | 100 | `FillRemainingInventory()` | 1 + min(27,100) = 28 | 27 | `withdrawAll()` |
| 1 | 27 | 12 | `FillRemainingInventory()` (partial final trip) | 1 + min(27,12) = 13 | 12 | `withdrawX(12)` (cooking continues with smaller batch) |
| 0 | 28 | 0 | `FillRemainingInventory()` | (n/a — fatal) | (n/a) | none — `onStart` records `BankMissingItem`; `check` returns `Failed(BankMissingItem)`; sequence aborts |

## 9. Banking step library

All steps live in `runelite-client/src/main/java/net/runelite/client/sequence/activities/banking/`. Each step is **stateless**: working memory goes to `ctx.bb().scope(BlackboardScope.STEP)`, not to instance fields. The engine (`StateDrivenEngine`) clears `STEP` scope on step transitions; this proof verifies that and adds it if missing.

### 9.1 Sequence

```
LinearSequence("PrepareCookingInventory"):
  EnsureAtBankStep(bankArea)
  EnsureNoBlockingInterfaceStep(allowList = [BANK])
  OpenBankStep(boothObjectIds, bankArea)
  WaitForBankReadyStep
  DepositItemStep(cookedId)
  DepositItemStep(burntId)
  WithdrawItemStep(tinderboxId, AtLeast(1))
  WithdrawItemStep(rawFoodId, FillRemainingInventory())
  EnsureInventoryMatchesLoadoutStep(loadout)
  CloseBankStep
```

### 9.2 Step contracts

Per §7, every row has THREE disjoint paths beyond fresh-work, mapped onto the unchanged two-valued `canStart`:
- **Waitable**: `canStart=false` writes a typed `BlockReason` to `SequenceBlackboardKeys.LAST_BLOCK_REASON`; planner retries next tick.
- **Already satisfied**: `canStart=true`, `onStart` no-op, first `check` returns `Succeeded`.
- **Fatal precondition failure**: `canStart=true`, `onStart` records `K_PRECONDITION_FAILURE = BlockReason` (no dispatch), first `check` returns `Failed(BlockReason)` → `LinearSequence` aborts.

| Step | `canStart=false` → waitable `BlockReason` | Already-satisfied detection | `onStart` action (incl. fatal-precondition recording) | `check` outcomes | `onFailure` |
|---|---|---|---|---|---|
| **EnsureAtBank**(area) | (never blocks — pure guard) | `s.player().worldLocation()` ∈ `area` → `Succeeded` first `check` | (no dispatch); fatal: if not in area, record `NotAtLocation(area)` (still no dispatch) | `Failed(NotAtLocation(area))` from `K_PRECONDITION_FAILURE` if recorded; else `Succeeded` | `Abort` |
| **EnsureNoBlockingInterface**(allowedRoots) | (never blocks — runs to clear blockers) | no blocker visible (or visible blocker is in `allowedRoots`) → `Succeeded` on first `check` | dispatch dismiss (Space for level-up dialog, Esc for menu, …) | `Succeeded` when blocker cleared; `Failed(ActionTimedOut)` if not dismissed in `timeoutTicks` | `Retry(2)` then `Abort` |
| **OpenBank**(boothIds, area) | `WorldInteractionBlocked(by)` where `by.canBeClosed() && by.rootWidgetId()` is in the allow-list (closable non-bank interface — `EnsureNoBlockingInterfaceStep` reactive will clear it within bounded ticks) | `bank.open()` already true → `Succeeded` first `check` | **fatal preconditions**: if `bank.pinUp()` → `K_PRECONDITION_FAILURE=PinKeypadUp`; if `blockingInterface` is present but `!by.canBeClosed()` or is not in the allow-list → `K_PRECONDITION_FAILURE=WorldInteractionBlocked(by)` (non-closable/unknown modal — reactive cannot clear it; abort); else `bank.clickBankBoothRandom()` | `Failed(PinKeypadUp)` or `Failed(WorldInteractionBlocked(by))` from `K_PRECONDITION_FAILURE`; `Succeeded` when `bank.open()` | `Retry(3)` then `Abort` |
| **WaitForBankReady** | **(NONE — `canStart=true` always; never `canStart=false`)** | `bank.ready()` already true → `Succeeded` first `check` | none (no-op; records `K_START_TICK`) | `Succeeded` when `bank.ready()`; `Failed(BankNotOpen)` if bank closes before becoming ready; `Failed(BankNotReady)` when `currentTick - K_START_TICK >= timeoutTicks()` (typed timeout owned by `check()`, not engine generic) | `Abort` |
| **DepositItem**(itemId) | **(NONE)** | `inv.count(itemId) == 0` → `Succeeded` first `check` | **fatal precondition**: if `!bank.open()` records `K_PRECONDITION_FAILURE=BankNotOpen` (no dispatch); else `bank.depositAll(itemId)` | `Failed(BankNotOpen)` from `K_PRECONDITION_FAILURE` or if bank closes mid-step; `Succeeded` when `inv.count(itemId) == 0` | `Retry(3)` then `Abort` |
| **WithdrawItem**(itemId, qty) | **(NONE — `canStart=true` always)** | `currentCount >= q` (AtLeast) **or** `freeSlots == 0` (FillRemainingInventory) → onStart no-op, check `Succeeded` first | resolves `target` and `delta = target - currentCount` (§8); **fatal preconditions**: records `K_PRECONDITION_FAILURE` for `BankNotOpen` (`!bank.open()`), `PinKeypadUp` (`bank.pinUp()`), `BankContentsUnknown` (availability==UNKNOWN — observer bug after `bank.ready()` was true), `BankMissingItem` (availability==ABSENT), or `InventoryFull` (delta > freeSlots) and skips dispatch; else dispatches `bank.withdrawOne` / `withdrawAll` / `withdrawX(delta)` (the **delta**, never the original `q`) | `Failed(BlockReason)` from `K_PRECONDITION_FAILURE` if recorded; `Succeeded` when `inv.count(itemId) ≥ target`; `Failed(BankMissingItem)` if availability becomes ABSENT mid-step; `Failed(BankNotOpen)` if bank closes mid-step; `Failed(WithdrawNoOp(itemId, timeoutTicks))` deterministically when `currentTick - K_START_TICK >= timeoutTicks()` and inventory unchanged from `K_INV_START` (overrides engine's generic timeout — §10.6) | `Retry(1)` for `WithdrawNoOp`; `Abort` for all other `BlockReason`s |
| **EnsureInventoryMatchesLoadout**(loadout) | (never blocks — pure verification) | inventory matches `loadout` → `Succeeded` first `check` | (no dispatch); fatal: if mismatch, record `LoadoutMismatch(diff)` (still no dispatch) | `Failed(LoadoutMismatch(diff))` from `K_PRECONDITION_FAILURE` if recorded; else `Succeeded` | `Abort` |
| **CloseBank** | (never blocks — runs to clear) | `!bank.open()` → `Succeeded` first `check` | `bank.closeBank()` | `Succeeded` when `!bank.open() && interaction().worldInteractionAvailable()`; engine generic timeout (`timeoutTicks=4`) → `Failure.timeout` | `Retry(3)` then `Abort` |

In this proof only `OpenBankStep` uses `canStart=false` — for `WorldInteractionBlocked(by)` only, which the reactive `EnsureNoBlockingInterfaceStep` can clear. Every other banking step has `canStart=true` always. All bad precondition states (`BankNotOpen`, `PinKeypadUp`, `BankContentsUnknown`, `BankMissingItem`, `InventoryFull`) are detected as fatal preconditions in `onStart` and surface as `Failed(BlockReason)` on the first `check()`, giving the engine a deterministic abort signal with zero risk of infinite stall.

`EnsureNoBlockingInterfaceStep.canStart` must check both conditions: a blocking interface IS present AND its root widget id is NOT in `allowedRoots`. If only the first check is performed, the reactive step will close the bank itself during `WithdrawItem` (since the bank widget is a blocking interface by definition). The allow-list must correctly exclude `BANK_ROOTS`. Test #16 verifies this.

### 9.3 Canonical full Java — `WithdrawItemStep`

```java
public final class WithdrawItemStep implements Step {
    private final int itemId;
    private final WithdrawQuantity desired;
    private final BankInteraction bank;
    private final String displayName;   // resolved once at construction for telemetry only

    public WithdrawItemStep(int itemId, WithdrawQuantity desired, BankInteraction bank) {
        this.itemId = itemId;
        this.desired = desired;
        this.bank = bank;
        this.displayName = ItemNames.of(itemId);
    }

    @Override public String name()                                  { return "WithdrawItem(" + displayName + ", " + desired + ")"; }
    @Override public int priority()                                 { return 100; }
    @Override public int timeoutTicks()                             { return 6; }
    @Override public PreemptionPolicy preemptionPolicy()            { return PreemptionPolicy.WHEN_SAFE; }
    @Override public boolean isSafeToPause(WorldSnapshot s, Blackboard bb) { return true; }

    /**
     * canStart is always true. WithdrawItemStep has no waitable canStart=false conditions (per §7).
     * All bad states are either fatal preconditions (detected in onStart) or already-satisfied
     * (detected in onStart). BankContentsUnknown is fatal, not waitable — bank.ready()==true was
     * already guaranteed by WaitForBankReadyStep; UNKNOWN after that is an observer bug.
     */
    @Override public boolean canStart(WorldSnapshot s, Blackboard bb) {
        return true;
    }

    /**
     * onStart picks one of three paths and persists the choice to step blackboard so check() is deterministic:
     *   - already-satisfied → K_OUTCOME = ALREADY_SATISFIED, no dispatch
     *   - fatal precondition → K_PRECONDITION_FAILURE = BlockReason, no dispatch
     *   - fresh work → K_TARGET / K_INV_START / K_START_TICK; dispatch withdraw with computed delta
     *
     * Fatal preconditions checked in order: BankNotOpen → PinKeypadUp → BankContentsUnknown →
     * already-satisfied → BankMissingItem → InventoryFull → fresh work + dispatch.
     * BankContentsUnknown is fatal because bank.ready()==true (WaitForBankReadyStep guarantee)
     * implies the container is loaded; UNKNOWN at this point is an inconsistent observer state.
     */
    @Override public void onStart(StepContext ctx) {
        WorldSnapshot s = ctx.snapshot();
        Blackboard step = ctx.bb().scope(BlackboardScope.STEP);
        BankView b = s.bank();
        InventoryView i = s.inventory();
        int currentCount = i.count(itemId);

        // 1) Fatal preconditions from unexpected bank states — record and bail without dispatching.
        if (!b.open()) {
            step.put(K_PRECONDITION_FAILURE, new BlockReason.BankNotOpen());
            return;
        }
        if (b.pinUp()) {
            step.put(K_PRECONDITION_FAILURE, new BlockReason.PinKeypadUp());
            return;
        }
        if (b.availability(itemId).presence() == Presence.UNKNOWN) {
            step.put(K_PRECONDITION_FAILURE, new BlockReason.BankContentsUnknown());
            return;
        }

        // 2) Already satisfied — no dispatch.
        if (alreadySatisfied(currentCount, i.freeSlots())) {
            step.put(K_OUTCOME, Outcome.ALREADY_SATISFIED);
            return;
        }

        // 3) Fatal preconditions from item state — record and bail without dispatching.
        BankItemAvailability a = b.availability(itemId);
        if (a.presence() == Presence.ABSENT) {
            step.put(K_PRECONDITION_FAILURE, new BlockReason.BankMissingItem(itemId, displayName, minQty()));
            return;
        }
        int knownBankCount = a.knownCount().orElse(Integer.MAX_VALUE);
        int target = resolveTargetCount(currentCount, i.freeSlots(), knownBankCount);
        int delta = target - currentCount;
        if (delta > i.freeSlots()) {
            step.put(K_PRECONDITION_FAILURE, new BlockReason.InventoryFull(delta - i.freeSlots()));
            return;
        }

        // 3) Fresh work — persist target/startTick and dispatch with the delta (NOT the original q).
        step.put(K_OUTCOME, Outcome.FRESH);
        step.put(K_INV_START, currentCount);
        step.put(K_TARGET, target);
        step.put(K_START_TICK, s.tick());

        if (delta == 0)                              return;   // defensive belt-and-braces; alreadySatisfied should have caught this
        if (delta == 1)                              bank.withdrawOne(itemId);
        else if (delta == knownBankCount)            bank.withdrawAll(itemId);
        else                                          bank.withdrawX(itemId, delta);
    }

    @Override public void onEvent(Object e, StepContext ctx) { /* no-op — proof relies on snapshot verification */ }
    @Override public void tick(StepContext ctx)              { /* no per-tick action */ }

    @Override public Completion check(WorldSnapshot s, Blackboard bb) {
        Blackboard step = bb.scope(BlackboardScope.STEP);

        // Fatal precondition recorded in onStart → first check returns Failed deterministically.
        Optional<BlockReason> pre = step.get(K_PRECONDITION_FAILURE);
        if (pre.isPresent()) return Completion.failed(pre.get());

        // Already-satisfied path → first check returns Succeeded.
        if (step.get(K_OUTCOME).orElse(Outcome.FRESH) == Outcome.ALREADY_SATISFIED) {
            return new Completion.Succeeded(displayName + " already in inventory");
        }

        int target = step.get(K_TARGET).orElseThrow(() -> new IllegalStateException("K_TARGET missing"));
        int invStart = step.get(K_INV_START).orElse(s.inventory().count(itemId));
        int now = s.inventory().count(itemId);

        if (now >= target) return new Completion.Succeeded("withdrew " + displayName + " (delta=" + (now - invStart) + ")");

        // Bank closed mid-step.
        if (!s.bank().open()) return Completion.failed(new BlockReason.BankNotOpen());

        // Item disappeared from bank mid-step (rare but possible — someone moved it, bank was reset).
        if (s.bank().availability(itemId).presence() == Presence.ABSENT) {
            return Completion.failed(new BlockReason.BankMissingItem(itemId, displayName, target));
        }

        // Typed timeout — deterministic, fires before the engine's generic timeoutTicks() (§10.6).
        // Engine post-processes check() result before applying timeoutTicks(), so returning Failed here
        // means Failure.diagnostic carries WithdrawNoOp, not the engine's untyped Failure.timeout.
        int startTick = step.get(K_START_TICK).orElse(s.tick());
        if (s.tick() - startTick >= timeoutTicks() && now == invStart) {
            return Completion.failed(new BlockReason.WithdrawNoOp(itemId, timeoutTicks()));
        }
        return Completion.RUNNING;
    }

    @Override public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard bb) {
        DiagnosticReason r = f.diagnostic();
        // Retry(1) is bounded at 1 retry (2 total attempts) because the engine tracks the attempt
        // count cumulatively and aborts once attempt count > maxAttempts — it does NOT call
        // onFailure indefinitely. If bank is still non-responsive after the retry, the re-dispatch
        // produces another WithdrawNoOp, onFailure is called again but the engine will see
        // attempt==2 > maxAttempts==1 and abort the step regardless of what we return here.
        if (r instanceof BlockReason.WithdrawNoOp)        return new Recovery.Retry(1);
        // All other typed reasons are unrecoverable by this step alone — abort.
        return new Recovery.Abort(f.reason());
    }

    // ─── helpers ─────────────────────────────────────────────────────────
    // No rejectWaitable helper — canStart() always returns true; no canStart=false conditions exist.

    private boolean alreadySatisfied(int currentCount, int freeSlots) {
        return switch (desired) {
            case WithdrawQuantity.AtLeast(int q)            -> currentCount >= q;
            // Fill is never "already satisfied" just because count >= 1 — only when no room remains.
            case WithdrawQuantity.FillRemainingInventory() -> freeSlots == 0;
        };
    }
    private int minQty() {
        return switch (desired) {
            case WithdrawQuantity.AtLeast(int q)            -> q;
            case WithdrawQuantity.FillRemainingInventory() -> 1;   // for telemetry / BankMissingItem.requiredQty only
        };
    }
    private int resolveTargetCount(int currentCount, int freeSlots, int knownBankCount) {
        return switch (desired) {
            case WithdrawQuantity.AtLeast(int q)            -> Math.max(q, currentCount);
            // Allow partial final trip: cap by knownBankCount (§8.2).
            case WithdrawQuantity.FillRemainingInventory() -> currentCount + Math.min(freeSlots, knownBankCount);
        };
    }
    private enum Outcome { FRESH, ALREADY_SATISFIED }

    // Step-private working memory. The cross-step typed key (LAST_BLOCK_REASON) lives in
    // SequenceBlackboardKeys (§9.4) — never duplicated here.
    private static final BlackboardKey<Integer>     K_TARGET                = BlackboardKey.of("withdraw.target",                Integer.class);
    private static final BlackboardKey<Integer>     K_INV_START             = BlackboardKey.of("withdraw.invStart",              Integer.class);
    private static final BlackboardKey<Integer>     K_START_TICK            = BlackboardKey.of("withdraw.startTick",             Integer.class);
    private static final BlackboardKey<Outcome>     K_OUTCOME               = BlackboardKey.of("withdraw.outcome",               Outcome.class);
    private static final BlackboardKey<BlockReason> K_PRECONDITION_FAILURE  = BlackboardKey.of("withdraw.preconditionFailure",   BlockReason.class);
}
```

Five things worth flagging:

- All working memory is in the step blackboard scope. The `WithdrawItemStep` instance has only constructor-finals.
- `canStart` always returns `true`. All bad states — `BankNotOpen`, `PinKeypadUp`, `BankContentsUnknown`, `BankMissingItem`, `InventoryFull` — are detected as fatal preconditions in `onStart` (§7). There are no `canStart=false` waitable conditions; no infinite stall is possible.
- `onStart` checks fatal preconditions *before* the already-satisfied check, so an unexpectedly closed bank aborts cleanly even when `currentCount` would otherwise pass the already-satisfied test. `onStart` then resolves the target final count and computes `delta = target - currentCount`, dispatching withdraw with the *delta* — never the original `q` — so `AtLeast(5)` with `currentCount=3` withdraws 2, not 5 (§8).
- `check()` differentiates "bank closed", "item disappeared", "deterministic timeout (`WithdrawNoOp`)", and "still in flight" — four distinct outcomes from one method. The typed `WithdrawNoOp` fires *before* the engine's generic `timeoutTicks()` so the `Failure` carries the correct `DiagnosticReason` (§10.6).
- `onFailure` decides recovery from the typed `DiagnosticReason`, not a stringly-typed message.

### 9.4 Shared blackboard keys: `SequenceBlackboardKeys`

The typed-block key is centralized — `LAST_BLOCK_REASON` is read by the planner/executor for telemetry, so step-private keys would silo it. Per-step working memory (e.g., `WithdrawItemStep.K_TARGET`) stays step-private; only cross-step / engine-visible keys live here.

```java
package net.runelite.client.sequence.blackboard;

import javax.annotation.Nullable;
import net.runelite.client.sequence.affordance.DiagnosticReason;

public final class SequenceBlackboardKeys {
    private SequenceBlackboardKeys() {}

    /**
     * Step-scoped: the typed reason a step's last canStart-rejection cited. In this proof only
     * OpenBankStep writes this key (for WorldInteractionBlocked). Read by the planner immediately
     * at the rejection site (logged with the step's name) and by telemetry. Never relied on as the
     * sole surfacing path — the planner also pushes a (stepName, reason) tuple to the ring buffer
     * at reject time, because the key is mutable and would race when multiple candidate steps are
     * evaluated in the same tick.
     */
    public static final BlackboardKey<DiagnosticReason> LAST_BLOCK_REASON =
        BlackboardKey.of("step.lastBlockReason", DiagnosticReason.class);
}
```

The planner (in `PriorityPlanner.evaluateCandidates(...)`) wraps every `canStart` call as:

```java
boolean ok = step.canStart(snapshot, bb);
if (!ok) {
    DiagnosticReason r = bb.scope(BlackboardScope.STEP).get(SequenceBlackboardKeys.LAST_BLOCK_REASON).orElse(null);
    telemetry.record(new BlockEvent(step.name(), r, snapshot.tick()));   // (stepName, reason, tick)
}
```

Telemetry is the *primary* surfacing path; the blackboard key is the secondary path (used by callers like `CookingScript.readLastTelemetry` to surface why the engine is currently parked). Reading the key alone is unsafe when multiple candidates are evaluated per tick because the last write wins; the (stepName, reason) tuple in the ring buffer is unambiguous per attempt.

### 9.5 Reactive step registration

`EnsureNoBlockingInterfaceStep` is registered as a *reactive* step on the engine, not just placed at position 2 in the linear sequence. The factory returns both:

```java
public record BankingSequencePlan(Step root, List<Step> reactiveSteps) {}
```

`CookingScript.tickBankingViaEngine()` consumes both:

```java
BankingSequencePlan plan = BankingSequenceFactory.prepareCookingLoadout(location, food, needsTinderbox, bank);
bankingManager.clearReactives();   // must clear before re-registering — cooking does repeated bank trips
for (Step reactive : plan.reactiveSteps()) bankingManager.registerReactive(reactive, /* priority= */ 200);
bankingManager.run(plan.root());
```

`clearReactives()` is called unconditionally before each banking run. Without it, reactive steps accumulate across repeated trips (the `CookingScript` loop calls `tickBankingViaEngine` hundreds of times). The `SequenceManager` (or `SequenceEngine`) must expose `clearReactives()` — add it if not present.

Without explicit reactive registration, a blocking dialog appearing *after* `OpenBank` succeeded would not preempt the in-flight `WithdrawItem` — the linear sequence has already passed `EnsureNoBlockingInterfaceStep`. With reactive registration, the planner re-evaluates the reactive step every tick and preempts the linear frame as soon as the dialog appears (snapshot-detected, no event push — see test #6 in §13).

## 10. Engine-side support changes

### 10.1 Observer composition (extending `ClientObserver`)

```java
public final class ClientObserver implements Observer {
    private final Client client;
    private final InventoryObserver inv;
    private final BankObserver bank;
    private final WidgetObserver widgets;
    private final InteractionObserver interaction;

    public ClientObserver(Client client) {
        this.client       = client;
        this.inv          = new InventoryObserver(client);
        this.bank         = new BankObserver(client);
        this.widgets      = new WidgetObserver(client);
        this.interaction  = new InteractionObserver(client, bank, widgets);
    }

    @Override public WorldSnapshot snapshot(int tick) {
        PlayerView pv = readPlayer();
        InventoryView iv = inv.read(tick);
        BankView bv = bank.read(tick);
        WidgetView wv = widgets.read(tick);
        InteractionView interactionView = interaction.read(tick, bv, wv);
        EventFacts ef = computeFactsFromMemos(tick);
        return new ImmutableWorldSnapshot(tick, pv, iv, bv, wv, interactionView, ef);
    }
}
```

`InventoryObserver` and `BankObserver` keep one-tick memos (`prevInventoryHash`, `prevBankHash`) to populate `EventFacts.lastInventoryChangeTick` etc. without subscribing to RuneLite events.

### 10.2 STEP-scope clearing

Verify `StateDrivenEngine` clears `bb.clear(BlackboardScope.STEP)` whenever the active step transitions. If it does not today, add it as part of this proof — a single line in the executor's transition path. Tests in §11 cover this.

### 10.3 canStart vs. check ordering for "already satisfied"

The engine currently calls `canStart` → `onStart` → (loop: `tick`/`onEvent`, then `check`). For the already-satisfied path to fire on `WithdrawItemStep` etc., `check` must run at least once before any tick advances state. Verify `StateDrivenEngine.advanceTick` calls `check` immediately after `onStart` on the same tick (or, equivalently, calls `check` before `onStart` for the no-op detection). If neither holds today, this proof adds a single tick `check()` call immediately following `onStart()`. Tests cover both ordering branches.

### 10.4 InvokeLater queue guard

`CookingScript`'s daemon hops to client thread via `clientThread.invokeLater(engine::advanceTick)`. To prevent multiple ticks queueing if the client thread lags:

```java
private final AtomicBoolean tickInFlight = new AtomicBoolean();

private void scheduleEngineTick() {
    if (!tickInFlight.compareAndSet(false, true)) return;   // previous tick still pending
    clientThread.invokeLater(() -> {
        try { bankingManager.getEngine().advanceTick(); }
        finally { tickInFlight.set(false); }
    });
}
```

### 10.5 Async start guard — preventing IDLE-vs-pending race

`SequenceManager.run(root)` is scheduler-marshalled (`clientThread::invoke`) — it submits a task that flips engine state from IDLE → RUNNING on the client thread. If the cooking-script daemon thread reads `bankingManager.state()` between `bankingManager.run(...)` returning and the marshalled task executing, it sees `IDLE` while a start is pending. Without a guard the daemon would re-enter the IDLE branch next tick, attempt to `tryAcquire` again (already holds it — returns false), and report `bank: input owned by …` even though it owns the lease. Confusing telemetry, no real corruption.

`CookingScript` tracks pending starts locally:

```java
private final AtomicBoolean bankingStartRequested = new AtomicBoolean();
```

`tickBankingViaEngine` flow:

```java
case IDLE -> {
    if (bankingStartRequested.get()) return;   // start was requested last tick; wait for marshalled task to flip state
    if (!inputOwnership.tryAcquire(OWNER_TOKEN)) { /* unchanged */ return; }

    BankingSequencePlan plan = BankingSequenceFactory.prepareCookingLoadout(location.get(), food.get(), needsTinderbox, bank);
    bankingManager.clearReactives();
    for (Step r : plan.reactiveSteps()) bankingManager.registerReactive(r, 200);

    bankingStartRequested.set(true);
    bankingManager.run(plan.root());
    status.set("bank: engine starting");
}
case RUNNING -> {
    bankingStartRequested.set(false);   // engine confirmed RUNNING; clear pending flag
    scheduleEngineTick();
    status.set("bank: " + readLastTelemetry(bankingManager));
}
case COMPLETED, FAILED -> {
    bankingStartRequested.set(false);   // also clear on terminal — covers race-then-failure
    /* unchanged terminal handling */
}
```

(Alternative: add a `SequenceState.STARTING` to `SequenceManager` and have it transition synchronously before scheduling the marshalled task. Cleaner long-term but out of scope for this proof — the local `AtomicBoolean` is sufficient.)

### 10.6 Typed-timeout-vs-engine-timeout ordering

The engine's tick path is:

```text
1. drain events
2. snapshot()
3. planner.advance() → executor selects step
4. step.tick(ctx)
5. step.check(snapshot, bb) → Completion
6. if Completion is Failed, run onFailure → Recovery
7. if Completion is Running and step has been running >= timeoutTicks(), engine wraps as Failure.timeout(elapsed)  ← generic, untyped
```

For `WithdrawNoOp` (or any other step-specific typed timeout) to surface correctly, the step's `check()` MUST detect the timeout and return `Failed(BlockReason.WithdrawNoOp(...))` **before** the engine reaches step 7. Concretely: `WithdrawItemStep.check()` reads `K_START_TICK`, computes `elapsed = currentTick - startTick`, and returns `Failed(WithdrawNoOp(...))` when `elapsed >= timeoutTicks()` AND no inventory change is observed. Because step 5 runs before step 7, the engine sees the typed `Failed` first and the generic timeout fallback is never reached for this scenario.

Steps that own their typed timeout in `check()` (e.g., `WithdrawItemStep` via `WithdrawNoOp`, `WaitForBankReadyStep` via `BankNotReady`) never reach the engine's step 7. `CloseBankStep` does not override timeout detection and therefore falls through to the engine's generic `Failure.timeout(elapsed)` (null `diagnostic`) — that is acceptable for this proof.

Test #3 in §13 asserts: scenario "withdraw clicked, inventory unchanged for 6 ticks" produces `Failure.diagnostic instanceof BlockReason.WithdrawNoOp`, NOT `Failure.timeout(6)` with a null diagnostic.

## 11. CookingScript integration

### 11.1 New state and feature flag

```java
public enum State {
    IDLE,
    BANKING_VIA_ENGINE,    // ← new
    BANKING_LEGACY,        // ← renamed from BANKING; still used when flag off
    WALK_TO_COOK,
    LIGHTING_FIRE,
    COOKING,
    WALK_TO_BANK,
    ABORTED
}
```

```java
@ConfigItem(
    keyName = "useEngineBanking",
    name = "Use engine-driven banking (experimental)",
    description = "Routes cooking-bot banking through the sequence engine."
)
default boolean useEngineBanking() { return false; }
```

`tickLoop` chooses between `tickBankingLegacy()` (the existing 200-line body, renamed and `@Deprecated` with a comment pointing at the engine path) and `tickBankingViaEngine()`.

### 11.2 `tickBankingViaEngine`

`CookingScript` gains three new instance fields (constructor-injected from `RecorderPlugin`):

```java
private final InputOwnership inputOwnership;     // Guice singleton — shared with SequencerPlugin
private SequenceManager bankingManager;          // lazy-built on first BANKING_VIA_ENGINE entry
private final AtomicBoolean tickInFlight = new AtomicBoolean();
private final AtomicBoolean bankingStartRequested = new AtomicBoolean();   // §10.5 async-start race guard
// `bank` (BankInteraction), `food` (Supplier<CookingFood.Entry>), `client`/`clientThread` are existing fields.
```

```java
private void tickBankingViaEngine() throws InterruptedException {
    CookingLocation l = location.get();
    if (!playerInArea(l.bankArea())) {
        setState(State.WALK_TO_BANK);   // walking stays in legacy land
        return;
    }
    if (bankingManager == null) bankingManager = buildBankingManager();

    SequenceState es = bankingManager.state();
    switch (es) {
        case IDLE -> {
            if (bankingStartRequested.get()) return;   // §10.5 — start pending; wait for marshalled task
            if (!inputOwnership.tryAcquire(OWNER_TOKEN)) {
                status.set("bank: input owned by " + inputOwnership.currentOwner().orElse("?"));
                return;
            }
            BankingSequencePlan plan = BankingSequenceFactory.prepareCookingLoadout(
                l,
                food.get(),                                                       // CookingFood.Entry
                l.kind() == CookingLocation.SourceKind.FIRE_FROM_LOGS,
                bank);
            bankingManager.clearReactives();
            for (Step r : plan.reactiveSteps()) bankingManager.registerReactive(r, /* priority= */ 200);
            bankingStartRequested.set(true);
            try {
                bankingManager.run(plan.root());
            } catch (Exception e) {
                bankingStartRequested.set(false);
                inputOwnership.release(OWNER_TOKEN);
                throw e;
            }
            status.set("bank: engine starting");
        }
        case RUNNING -> {
            bankingStartRequested.set(false);
            scheduleEngineTick();
            status.set("bank: " + readLastTelemetry(bankingManager));
        }
        case COMPLETED -> {
            bankingStartRequested.set(false);
            inputOwnership.release(OWNER_TOKEN);
            snapshotTripBaseline();
            setState(State.WALK_TO_COOK);
        }
        case FAILED -> {
            bankingStartRequested.set(false);
            String reason = lastFailureReason(bankingManager);
            inputOwnership.release(OWNER_TOKEN);   // no-op if engine already released on mid-sequence failure
            abortWithStatus("bank-engine: " + reason);
        }
        case PAUSED -> { /* not used in this proof */ }
    }
}

private static final String OWNER_TOKEN = "cooking-banking";

private SequenceManager buildBankingManager() {
    SequenceManager m = SequenceManager.withDefaults();
    m.setObserver(new ClientObserver(client));
    m.setDispatcher(dispatcher);                  // existing HumanizedInputDispatcher reused
    m.setScheduler(clientThread::invoke);
    m.setInputOwnership(inputOwnership, OWNER_TOKEN);   // exposes token so executor can call isOwner(token) defensively
    return m;
}
```

### 11.3 InputOwnership

```java
public final class InputOwnership {
    private final AtomicReference<String> owner = new AtomicReference<>();
    public boolean tryAcquire(String name) { return owner.compareAndSet(null, name); }
    public boolean isOwner(String name)    { return name.equals(owner.get()); }
    public Optional<String> currentOwner() { return Optional.ofNullable(owner.get()); }
    public boolean release(String name)    { return owner.compareAndSet(name, null); }
}
```

Bound as a Guice `@Singleton`. `SequencerPlugin`'s manager and `CookingScript` both reference the same instance.

`SequenceManager.setInputOwnership(InputOwnership ownership, String ownerToken)` stores both. The executor (or `StateDrivenEngine`) accesses the stored pair for its defensive mid-sequence check: `ownership.isOwner(ownerToken)`. Without the stored token, the executor could only check `ownership.currentOwner().isPresent()` (which fails to distinguish "held by me" from "held by someone else"). The owner token is also used in the executor's cleanup path (`ownership.release(ownerToken)`) if the engine itself needs to release mid-sequence. `CookingScript`'s `COMPLETED`/`FAILED` handlers also call `release(OWNER_TOKEN)` — the second call is a safe no-op (`compareAndSet(token, null)` returns false when already null).

**Lease semantics** (per refinement #6): the lease is acquired at sequence-start and released at sequence-end (or on `FAILED`). The Executor does NOT silently no-op per-tick when the lease is held by another owner. Instead:

- If `CookingScript.tickBankingViaEngine` cannot acquire the lease, it does not start the engine. The script reports `bank: input owned by …` and yields. No engine ticks elapse, no timeouts accrue.
- If the lease is somehow lost mid-sequence (it isn't, since only the owner releases — but defensively): the executor calls `ownership.isOwner(ownerToken)`, logs a critical warning, records `DiagnosticReason.Unknown("input ownership lost mid-sequence")` to telemetry, calls `ownership.release(ownerToken)`, and the engine returns `FAILED`. The `CookingScript` FAILED handler's subsequent `release(OWNER_TOKEN)` call is a harmless no-op.

### 11.4 Threading summary

- Daemon thread (`cooking-script`): 600 ms sleep cadence, drives `tickLoop` switch.
- Daemon hops to client thread via `clientThread.invokeLater(engine::advanceTick)`, gated by `tickInFlight` AtomicBoolean.
- `bankingManager.run(root)` and `.stop()` are scheduler-marshalled (`clientThread::invoke`).
- Snapshot is per-tick immutable, built once at the top of `engine.advanceTick`, passed to all `canStart`/`check` calls in that tick.
- `InputOwnership` is `AtomicReference`-based.
- `HumanizedInputDispatcher.dispatch()` is safe to call from any thread (verified — internal worker submission).

## 12. BankingSequenceFactory

The factory takes full domain context — the cooking location (resolves `bankArea` and booth object ids) and the food entry (resolves `rawFoodId`, `cookedFoodId`, `burntFoodId`) — so the linear sequence can be assembled deterministically with no placeholder resolution. It returns a `BankingSequencePlan` carrying both the linear `root` and the explicit `reactiveSteps` list (§9.5).

```java
public record BankingSequencePlan(Step root, List<Step> reactiveSteps) {}

public final class BankingSequenceFactory {
    private BankingSequenceFactory() {}

    public static BankingSequencePlan prepareCookingLoadout(
            CookingLocation location,
            CookingFood.Entry food,
            boolean needsTinderbox,
            BankInteraction bank) {

        WorldArea bankArea = location.bankArea();
        List<Integer> boothIds = location.bankBoothIds();

        List<Step> linear = new ArrayList<>(8);
        linear.add(new EnsureAtBankStep(bankArea));
        linear.add(new EnsureNoBlockingInterfaceStep(allowList(BANK_ROOTS)));   // also registered reactively below
        linear.add(new OpenBankStep(boothIds, bankArea, bank));
        linear.add(new WaitForBankReadyStep(bank));

        // Narrow deposit cleanup: only the current food's cooked + burnt ids, not an all-foods sweep.
        // EnsureInventoryMatchesLoadoutStep catches any other unwanted items and aborts with
        // LoadoutMismatch — clearer failure than a slow auto-cleanup. Broader cross-food cleanup
        // is a follow-up if the legacy script's behavior actually requires it.
        linear.add(new DepositItemStep(food.cookedId(), bank));
        linear.add(new DepositItemStep(food.burntId(),  bank));

        if (needsTinderbox) linear.add(new WithdrawItemStep(ItemID.TINDERBOX, new WithdrawQuantity.AtLeast(1), bank));
        linear.add(new WithdrawItemStep(food.rawId(), new WithdrawQuantity.FillRemainingInventory(), bank));

        linear.add(new EnsureInventoryMatchesLoadoutStep(loadoutFor(food, needsTinderbox)));
        linear.add(new CloseBankStep(bank));

        Step root = new LinearSequence("PrepareCookingInventory", linear);

        // Reactive — preempts the linear frame whenever a blocking interface appears mid-flow (§9.5).
        // Reactive registration ensures dialogs appearing AFTER OpenBank still get dismissed.
        List<Step> reactives = List.of(new EnsureNoBlockingInterfaceStep(allowList(BANK_ROOTS)));

        return new BankingSequencePlan(root, reactives);
    }
}
```

## 13. Tests (17 scenarios)

All new tests live under `runelite-client/src/test/java/net/runelite/client/sequence/activities/banking/`. They use the existing `FixtureObserver` + `MockInputDispatcher` patterns. Each scenario is a single `@Test`.

| # | Scenario | Verifies |
|---|---|---|
| 1 | Bank already open at sequence start | `OpenBankStep` already-satisfied path: canStart=true, onStart no-op, check `Succeeded` first call |
| 2 | World action rejected while bank open | `WalkStep.canStart` rejects with `WorldInteractionBlocked(BANK)`; `AffordanceReport.entry(WALK)` blocked |
| 3 | Withdraw clicked but inventory unchanged for 6 ticks | `WithdrawItemStep` returns `RUNNING` for 5 ticks then `Failed` with `BlockReason.WithdrawNoOp(itemId, 6)` from `check()` *before* the engine's generic `Failure.timeout`. Assert `Failure.diagnostic instanceof BlockReason.WithdrawNoOp` AND `Failure.diagnostic` is non-null (NOT `Failure.timeout` with null diagnostic, NOT `BankMissingItem`) |
| 4 | Bank closes unexpectedly mid-withdraw | `WithdrawItemStep.check` returns `Failed(BankNotOpen)`; `onFailure` returns `Abort` |
| 5 | Required item already in inventory | `WithdrawItemStep` already-satisfied: canStart=true, onStart writes K_OUTCOME=ALREADY_SATISFIED with no dispatch, first check `Succeeded`. Assert `MockInputDispatcher.dispatchCount() == priorCount` |
| 6 | Snapshot changes to show blocking dialog mid-flow | Reactive `EnsureNoBlockingInterfaceStep` (explicitly registered via `bankingManager.registerReactive(...)`, see §9.5) becomes eligible per snapshot diff and preempts the in-flight linear step (e.g., `WithdrawItem`). No event push: the reactive step's `canStart` flips to `true` solely because `interaction().blockingInterface()` changed in the new snapshot. After dismiss, the linear frame resumes |
| 7 | `CloseBank` only succeeds when world interaction is available | check returns `RUNNING` for one tick after `bank.closeBank()` until `interaction.worldInteractionAvailable()` is true |
| 8 | `WaitForBankReadyStep` owns the wait and typed timeout | (a) Fixture: bank open but `ready()=false`. `WaitForBankReadyStep.canStart=true`; `onStart` no-op, records K_START_TICK; `check` returns `RUNNING`. Next tick `ready()=true` → `Succeeded`. (b) Fixture: bank closes mid-wait; `check` returns `Failed(BankNotOpen)`. (c) Fixture: bank never becomes ready in `timeoutTicks` ticks; `check` returns `Failed(BankNotReady)` (typed timeout, NOT the engine's generic `Failure.timeout`). Assert `Failure.diagnostic instanceof BlockReason.BankNotReady` in case (c). |
| 8b | `BankContentsUnknown` after `bank.ready()=true` is fatal | Fixture: `bank.ready()=true` but `availability(itemId).presence() == UNKNOWN` (simulates observer bug). `WithdrawItemStep.canStart=true`; `onStart` records `K_PRECONDITION_FAILURE=BankContentsUnknown` without dispatching; first `check` returns `Failed(BankContentsUnknown)` → sequence aborts. Assert `MockInputDispatcher.dispatchCount() == priorCount` (no withdraw click sent). This test guards against the step silently waiting forever on a broken observer. |
| 9 | Tinderbox absent in bank | `WithdrawItemStep.canStart=true` (per §7 fatal-precondition path); `onStart` records `K_PRECONDITION_FAILURE = BankMissingItem(tinderbox)` without dispatching; first `check` returns `Failed(BankMissingItem)`; sequence aborts; `EnsureInventoryMatchesLoadoutStep` never reached. Assert `MockInputDispatcher.dispatchCount() == priorCount` (no withdraw click was sent) |
| 10 | InputOwnership: second engine refuses to start | `CookingScript`-style caller fails `tryAcquire`; `bankingManager.run` is never called; `MockInputDispatcher.dispatchCount()` from second engine stays 0 |
| 11 | End-to-end happy path | `LinearSequence` completes; `MockInputDispatcher` shows: 1 booth click, 1 deposit-cooked, 1 deposit-burnt (or skips if absent), 1 withdraw-tinderbox, 1 withdraw-raw, 1 close. `RingBufferTelemetry` contains `started/succeeded` per step in order |
| 12 | Loadout mismatch caught before close | Inventory missing tinderbox at `EnsureInventoryMatchesLoadout`; first `check` returns `Failed(LoadoutMismatch(diff = [(tinderbox, have=0, want=1)]))` from `K_PRECONDITION_FAILURE`; sequence aborts; `CloseBank` is NOT executed |
| 13 | `AtLeast(5)` with `currentCount=3` | `WithdrawItemStep.onStart` resolves `target=5`, `delta=2`; `MockInputDispatcher` records exactly one `bank.withdrawX(itemId, 2)` — NOT `withdrawX(itemId, 5)` |
| 14 | `FillRemainingInventory` not satisfied just because count≥1 | currentCount=1, freeSlots=27, knownBankCount=100, desired=`FillRemainingInventory()`. Step is NOT already-satisfied (freeSlots > 0); `target = 1 + min(27,100) = 28`, `delta = 27`; dispatches `bank.withdrawAll(itemId)` |
| 15 | `FillRemainingInventory` partial final trip | currentCount=0, freeSlots=28, knownBankCount=12, desired=`FillRemainingInventory()`. `target = 0 + min(28,12) = 12`, `delta = 12`; dispatches `bank.withdrawX(itemId, 12)`; check succeeds when inventory grows by 12. Sequence proceeds to cooking with a 12-item batch (not aborted) |
| 16 | Bank interface open during `WithdrawItem` — reactive does NOT preempt | Fixture: bank widget open (`BANKING` mode), `WithdrawItem` step RUNNING. Reactive `EnsureNoBlockingInterfaceStep` has `allowList=[BANK_ROOTS]`. Assert: `reactiveStep.canStart(snapshot, bb) == false` (bank is present BUT is allowed → no preemption). `WithdrawItem` continues uninterrupted. This test guards against the allow-list logic being wrong, which would cause the reactive step to close the bank mid-withdraw |

Plus regression: all existing ≈25 sequence-engine tests must continue to pass.

### TDD discipline

Implementation follows `superpowers:test-driven-development`: each step gets a failing unit test first, then minimal Java to make it pass. The full `BankingSequenceFactory` integration test is added last.

## 14. Documentation plan

### 14.1 `runelite-client/src/main/java/net/runelite/client/sequence/ARCHITECTURE.md` (new)

≈300 lines. Sections:

1. **What the engine is** — one-paragraph overview, what problem it solves, why enum FSMs are not the pattern for new gameplay flows.
2. **Engine map** — package layout (this proof-spec's §4 trimmed).
3. **Core abstractions** — `SequenceEngine`, `Step`, `WorldSnapshot`, `Completion`, `Recovery`, `Failure`, `PreemptionPolicy`, `Blackboard` scopes.
4. **Step lifecycle** — diagram + prose of `canStart → onStart → tick/onEvent → check → onFailure`.
5. **Already-satisfied semantics** — explicit subsection on the `canStart=true → onStart no-op → check Succeeded` pattern.
6. **WorldSnapshot views** — list of every view, what it provides, where the observer reads it from.
7. **Affordance layer** — `ActionKind`, `BlockReason`, how `AffordanceReport` is built.
8. **Composite steps** — `LinearSequence`, `Selector`, `RepeatStep`.
9. **Telemetry** — what `RingBufferTelemetry` records, how to subscribe.
10. **Case study: LoginRunner** — how login is built on the engine.
11. **Case study: BankingSequenceFactory** — how cooking banking is built on the engine (this proof, post-implementation).
12. **How to write a new step** — checklist (canStart correctness, snapshot-only verification, no instance state, declare typed BlockReasons).
13. **Known gaps / next-after-proof** — combat, scene/object views, ground items, NPCs, varbits, transport-aware walking.

### 14.2 `CLAUDE.md` (modified)

Insert a short pointer at the top of "File map":

```markdown
## Sequence engine — read FIRST for any NEW gameplay scripting

The repo has a state-driven sequence engine under
`runelite-client/.../sequence/`. New gameplay scripts MUST use it, not
the enum-FSM `tickLoop` pattern in `recorder/scripts/`.

See `runelite-client/src/main/java/net/runelite/client/sequence/ARCHITECTURE.md`
for the engine map, step lifecycle, and the cooking-banking case study.

The legacy enum-FSM scripts in `recorder/scripts/` (CookingScript banking-legacy
path, ChickenFarmV3, LumbridgeBankPenScript) predate engine adoption. Read them
for domain knowledge (widget IDs, dispatch semantics) but DO NOT copy their FSM
shape for new flows.
```

### 14.3 Inline javadoc

Every new step has a class-level doc that lists:
- Pre-conditions (`canStart` rules)
- The single `onStart` action
- Success criteria (`check`)
- Possible `BlockReason`s
- Recovery policy

## 15. Acceptance criteria

A reviewer should verify:

- [ ] All ≈25 existing sequence-engine tests continue to pass.
- [ ] All 15 new banking-step tests pass.
- [ ] `WorldSnapshot` exposes `inventory()`, `bank()`, `widgets()`, `interaction()`, `events()` as `default` methods returning empty/null-object views (so existing test fixtures keep compiling). No raw `Widget` references on the public surface.
- [ ] `BankView.availability(itemId)` returns `BankItemAvailability` with `Presence` + optional `knownCount` + `visible` flag. UNKNOWN is distinct from ABSENT.
- [ ] `DiagnosticReason` is the engine-layer typed reason (sealed interface, lives in `sequence/affordance/`); `BlockReason` is a sealed sub-interface holding bank-domain records.
- [ ] `Completion.Failed` and `Failure` carry an optional typed `DiagnosticReason` (renamed from `BlockReason`); `Failure.fromDiagnostic(...)` is the typed factory.
- [ ] No banking step holds mutable instance state. Working memory lives in `bb.scope(BlackboardScope.STEP)`. The engine clears `STEP` scope between step transitions.
- [ ] `WithdrawQuantity` is a sealed type with `AtLeast / FillRemainingInventory` (no `Exact`). No `Integer.MAX_VALUE` sentinels.
- [ ] `WithdrawNoOp(itemId, ticks)` is a distinct `BlockReason`. The "withdraw clicked but inventory did not change" path NEVER reports `BankMissingItem` unless `BankView.availability` returns ABSENT.
- [ ] `EnsureInventoryMatchesLoadoutStep` runs before `CloseBankStep` and aborts with `LoadoutMismatch(diff)` if not met.
- [ ] `InputOwnership` lease is checked at sequence-start in `CookingScript`. When held by another, the script does not start the banking engine — no per-tick executor no-ops, no silent timeouts.
- [ ] `tickInFlight` AtomicBoolean prevents multiple `clientThread.invokeLater(engine::advanceTick)` calls queueing.
- [ ] `tickBankingLegacy()` exists, is `@Deprecated`, is selected only when `RecorderConfig.useEngineBanking() == false`.
- [ ] When `useEngineBanking() == true`, end-to-end CookingScript banking → walk-to-cook → cook → walk-to-bank → banking-via-engine → … runs through at least one full trip in dev session without manual intervention.
- [ ] `runelite-client/.../sequence/ARCHITECTURE.md` exists with all 13 sections from §14.1.
- [ ] `CLAUDE.md` has the engine pointer from §14.2.
- [ ] No new file imports `net.runelite.api.widgets.Widget` from a `Step`. (Steps consult `WidgetView`.)
- [ ] No banking step calls `dispatcher.dispatch(...)` directly with a raw `ActionRequest`. (Dispatch is via `BankInteraction` / `Actions`.)
- [ ] `LinearSequence` distinguishes waitable (`canStart=false`) from fatal precondition (`canStart=true` + first `check` returning `Failed`). Tests #1, #5, #9 cover the three branches; no silent stall on a fatal `BlockReason`.
- [ ] `WithdrawQuantity.FillRemainingInventory` is "already satisfied" iff `freeSlots == 0`, NOT when `currentCount >= 1`. Test #14 covers this.
- [ ] `WithdrawItemStep` computes `target = resolveTargetCount(currentCount, freeSlots, knownBankCount)` and `delta = target - currentCount` at `onStart`, then dispatches the *delta* (e.g., `withdrawX(itemId, 2)` for `AtLeast(5)` with `currentCount=3`). Test #13 covers this.
- [ ] `BankingSequenceFactory.prepareCookingLoadout` takes `(CookingLocation, CookingFood.Entry, boolean, BankInteraction)` and returns `BankingSequencePlan { root, reactiveSteps }`. No placeholder location/booth resolution; deposit cleanup is narrow (current food's cooked + burnt ids only).
- [ ] `EnsureNoBlockingInterfaceStep` is registered as a reactive step via `bankingManager.registerReactive(plan.reactiveSteps())`, not solely placed at position 2 in the linear sequence. Test #6 covers reactive preemption mid-flow.
- [ ] `SequenceBlackboardKeys.LAST_BLOCK_REASON` is the single typed-block key; no banking step has a private `LAST_BLOCK_REASON` constant. The planner records `(stepName, reason)` to telemetry at the reject site (not solely via the mutable key).
- [ ] `WithdrawNoOp(itemId, ticks)` is produced deterministically by `WithdrawItemStep.check()` (before the engine's generic `Failure.timeout`). Test #3 asserts `Failure.diagnostic instanceof BlockReason.WithdrawNoOp` and not `Failure.timeout` with a null diagnostic.
- [ ] `CookingScript.tickBankingViaEngine` guards `bankingManager.run(plan.root())` against IDLE-vs-pending race via `AtomicBoolean bankingStartRequested` (§10.5).
- [ ] `FillRemainingInventory` partial-final-trip policy is implemented: `target = currentCount + Math.min(freeSlots, knownBankCount)`. Test #15 covers this.
- [ ] `canStart=false` is used ONLY in `OpenBankStep` and ONLY when the blocking interface is both closable (`by.canBeClosed()==true`) and in the allow-list. A non-closable or unknown blocking interface triggers a fatal precondition in `onStart` (`K_PRECONDITION_FAILURE=WorldInteractionBlocked(by)`) — it is never a `canStart=false` waitable. Every other banking step has `canStart=true` always. `BankNotOpen`, `PinKeypadUp`, `BankContentsUnknown`, `BankMissingItem`, and `InventoryFull` are all fatal preconditions detected in `onStart`, not waitable `canStart=false` blockers. No infinite stall is possible from any step in this sequence.
- [ ] `WaitForBankReadyStep.canStart` is always `true`. The step owns its wait and its typed timeout in `check()`: `Failed(BankNotReady)` fires after `timeoutTicks()`. The engine's generic `Failure.timeout` is not relied upon for this step.
- [ ] `PinKeypadUp` in `OpenBankStep` and `WithdrawItemStep` is detected as a fatal precondition (in `onStart` or mid-step in `check()`), resulting in `Failed(PinKeypadUp)` → `Abort`. It is never `canStart=false` waitable.
- [ ] `BankNotOpen` in `DepositItemStep` and `WithdrawItemStep` is a fatal precondition detected in `onStart` → `K_PRECONDITION_FAILURE=BankNotOpen`, first `check` returns `Failed(BankNotOpen)` → `Abort`. It is never `canStart=false` waitable.
- [ ] `EnsureNoBlockingInterfaceStep.canStart` returns `true` only when (a) a blocking interface IS present AND (b) its root widget id is NOT in `allowedRoots`. Test #16 verifies the bank interface during `WithdrawItem` does not trigger reactive preemption.
- [ ] `bankingManager.clearReactives()` is called before re-registering reactive steps on each new banking run. Reactive steps do not accumulate across banking trips.
- [ ] `bankingManager.run(plan.root())` is guarded with try/catch: on exception, `bankingStartRequested` is reset to `false` and `InputOwnership` is released before re-throwing.
- [ ] `WithdrawQuantity` has no `Exact` case. All usages use `AtLeast` or `FillRemainingInventory`. Tinderbox is specified as `AtLeast(1)`.
- [ ] `bb.clear(BlackboardScope.STEP)` occurs AFTER terminal transition is processed (after `onFailure` has been called), never between `onStart` and the same-tick `check()`. Tests #1 and #5 implicitly verify this by requiring `K_OUTCOME` written in `onStart` to be readable in the immediately-following `check()`.
- [ ] `Recovery.Retry(maxAttempts)` is enforced cumulatively by the engine: the engine tracks total attempt count for the current step run and aborts the step once `attemptCount > maxAttempts`, regardless of what `onFailure` returns on a subsequent call. `WithdrawItemStep`'s `Retry(1)` is therefore bounded at 2 total attempts. Implementation must verify the engine enforces this invariant — if `onFailure` can return `Retry(1)` on every call without the engine aborting, the step loops indefinitely.
- [ ] `SequenceManager.setInputOwnership(InputOwnership, String ownerToken)` is implemented. The `ownerToken` is stored by the manager and accessible to the executor for the defensive `isOwner(ownerToken)` check. The owner token `"cooking-banking"` appears only as `CookingScript.OWNER_TOKEN` — never as a bare string literal in the call sites.

## 16. Non-goals (re-emphasized)

- No combat / chicken-farm / Lumbridge-walker migration.
- No transport-aware walking inside the engine. `EnsureAtBankStep` is a guard, not a walker.
- No RuneLite event push into `CookingScript`'s engine. Verification is snapshot-based; `Step.onEvent` is a no-op for banking steps in this proof.
- No shared `SequenceManager` Guice singleton across plugins — two managers coexist.
- No deletion of the legacy banking body. Renamed and flagged; deletion is a follow-up after a green soak.
- No new dispatcher. The proof uses the existing `HumanizedInputDispatcher` (which `BankInteraction` already wraps). `DirectInputDispatcher` is reserved for tests via `MockInputDispatcher`.

## 17. Open questions / next-after-proof

| # | Question | Recommended next step |
|---|---|---|
| 1 | Two engines vs. shared singleton | After proof, Guice-bind a single `SequenceManager` shared across plugins. Defer. |
| 2 | `tickBankingLegacy()` retention | After ≈5 green dev sessions on the engine path, delete the legacy body in a follow-up PR. |
| 3 | Transport-aware walking step | `WalkToBankStep` wrapping `UniversalWalker` — opens up porting `WALK_TO_COOK` next. |
| 4 | RuneLite event push | Have `RecorderPlugin` `@Subscribe` `ItemContainerChanged` etc. and call `cookingBankingManager.offerEvent(e)`. Cheap, but requires reasoning about ordering with snapshot-derived `EventFacts`. |
| 5 | GameTick subscription for CookingScript | Replace daemon `Thread.sleep(600)` with `@Subscribe onGameTick` for the engine-banking phase only. Removes one thread, simplifies tick cadence. |
| 6 | Telemetry surfacing in panel | Add a `BankingTelemetryPanel` to `RecorderPanel` showing the last N records of the active sequence — debugging aid. |
| 7 | Full cooking migration | After banking proof works for ≈2 weeks, migrate `LIGHTING_FIRE`/`COOKING`/`WALK_TO_COOK`/`WALK_TO_BANK` to engine-driven steps. |
| 8 | Lumbridge logs-on-ground wait (fire-from-logs flow) | The Lumbridge bank area has logs on the ground that the bot picks up to light a fire. Another player can take all the logs before the bot reaches them (rare). When this happens the bot must wait for the logs to respawn rather than aborting or looping instantly. This is handled in the `LIGHTING_FIRE` phase of `CookingScript` (not the banking phase), but it needs a timed-wait step or a polling loop capped at respawn time (≈60s). Implement as a `WaitForGroundItemStep(logsId, tile, timeoutTicks=100)` with `canStart=true`; `check()` polls `GroundItemView.itemAt(tile, itemId)` and returns `Failed(ItemNotPresent)` after timeout. Requires `GroundItemView` added to `WorldSnapshot` (currently out of scope). Deferred to the full cooking migration (item #7). |

## 18. Worktree + commit hygiene

- Worktree: `worktree-sequence-banking-proof`, base off `master`.
- New files: skip BSD copyright headers (per project convention — see `~/.claude/.../memory/feedback_no_bsd_headers.md`).
- Existing files touched (e.g., `Completion.java`, `Failure.java`, `WorldSnapshot.java`, `ClientObserver.java`, `CookingScript.java`, `RecorderConfig.java`, `CLAUDE.md`): keep upstream BSD headers intact on already-headered files.
- Commit cadence: small, reviewable commits per step (e.g., `sequence: add InventoryView + observer`, `sequence: add BankView with tri-state availability`, …).
- Build verification: `./gradlew :client:compileJava` after each commit (per `feedback_rlb_gradle_compile.md`).

## 19. Appendix — type quick reference

```java
// Engine extensions (touched, not new)
sequence/Completion.java     +Failed(reason, @Nullable DiagnosticReason)
sequence/Failure.java        +diagnostic field, +fromDiagnostic factory (renamed from fromBlock)
sequence/WorldSnapshot.java  +inventory(), bank(), widgets(), interaction(), events() — default null-object methods to keep existing test fixtures compiling

// Shared blackboard keys (NEW — §9.4)
sequence/blackboard/SequenceBlackboardKeys.java  (LAST_BLOCK_REASON: BlackboardKey<DiagnosticReason>)

// New views
sequence/views/InventoryView.java
sequence/views/BankView.java
sequence/views/WidgetView.java
sequence/views/InteractionView.java
sequence/views/EventFacts.java
sequence/views/ItemStack.java                  (record)
sequence/views/Presence.java                    (enum)
sequence/views/BankItemAvailability.java        (record)
sequence/views/InteractionMode.java             (enum)

// Affordance layer
sequence/affordance/ActionKind.java             (enum)
sequence/affordance/DiagnosticReason.java       (sealed interface + Loading/ActionTimedOut/Unknown records — engine-generic)
sequence/affordance/BlockReason.java            (sealed sub-interface of DiagnosticReason + bank-domain records)
sequence/affordance/BlockingInterface.java      (record)
sequence/affordance/Affordance.java             (record, reason: Optional<DiagnosticReason>)
sequence/affordance/AffordanceReport.java       (record)
sequence/affordance/ItemDiff.java               (record)

// Withdraw quantity
sequence/activities/banking/WithdrawQuantity.java   (sealed: AtLeast / FillRemainingInventory — Exact removed)

// Banking steps
sequence/activities/banking/EnsureAtBankStep.java
sequence/activities/banking/EnsureNoBlockingInterfaceStep.java
sequence/activities/banking/OpenBankStep.java
sequence/activities/banking/WaitForBankReadyStep.java
sequence/activities/banking/DepositItemStep.java
sequence/activities/banking/WithdrawItemStep.java
sequence/activities/banking/EnsureInventoryMatchesLoadoutStep.java
sequence/activities/banking/CloseBankStep.java
sequence/activities/banking/BankingSequenceFactory.java       (signature: (CookingLocation, CookingFood.Entry, boolean, BankInteraction))
sequence/activities/banking/BankingSequencePlan.java           (record: root, reactiveSteps — §9.5)
sequence/activities/banking/Loadout.java        (record: List<ItemRequirement>)
sequence/activities/banking/ItemRequirement.java (record: itemId, name, minQty)

// Observers
sequence/internal/InventoryObserver.java
sequence/internal/BankObserver.java
sequence/internal/WidgetObserver.java
sequence/internal/InteractionObserver.java
sequence/internal/ClientObserver.java           (extended — composes the above)

// Input ownership
sequence/dispatch/InputOwnership.java

// CookingScript integration
plugins/recorder/scripts/CookingScript.java     (extended — new state, new tick path)
plugins/recorder/RecorderConfig.java            (extended — useEngineBanking flag)

// Documentation
runelite-client/src/main/java/net/runelite/client/sequence/ARCHITECTURE.md
CLAUDE.md                                        (modified)
```
