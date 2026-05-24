# Sequence Engine — Architecture

## 1. What the engine is

The sequence engine (`net.runelite.client.sequence`) is a tick-driven, step-based
execution framework for gameplay scripts. It replaces the pattern of enum-FSM
`tickLoop` methods (still used in `recorder/scripts/`) with composable `Step`
objects that declare their preconditions, dispatch actions, and report completion
through read-only `WorldSnapshot` views. The engine handles timeout, retry,
preemption, and telemetry uniformly, so individual steps contain only domain
logic. A script becomes a tree of steps assembled by a factory; the engine
drives it one game tick at a time.

## 2. Engine map

```
sequence/
  SequenceEngine.java          interface: start/pause/resume/stop/advanceTick
  SequenceManager.java         assembles engine + subsystems; plugin entry point
  Step.java                    the one interface every step implements
  WorldSnapshot.java           read-only game state passed to canStart/check/onFailure
  Completion.java              sealed: Running | Succeeded | Failed
  Recovery.java                sealed: Retry | Skip | Abort | JumpToAnchor
  Failure.java                 record: reason, ticksElapsed, cause, diagnostic
  PreemptionPolicy.java        enum: NEVER | AFTER_CURRENT_TICK | WHEN_SAFE | ALWAYS
  StepContext.java             mutable context passed to onStart/tick/onEvent

  affordance/
    ActionKind.java            enum of dispatchable action categories
    BlockReason.java           sealed bank-domain typed diagnostic reasons
    DiagnosticReason.java      root interface for typed failure reasons
    AffordanceReport.java      which ActionKinds are currently allowed
    Affordance.java            one entry: kind + allowed flag
    BlockingInterface.java     record: rootWidgetId + label

  blackboard/
    Blackboard.java            put/get/remove by typed key, scoped
    BlackboardKey.java         typed key record
    BlackboardScope.java       enum: GLOBAL | RUN | SEQUENCE | STEP
    ScopedBlackboard.java      production implementation

  composite/
    LinearSequence.java        run children in order; fail-fast
    Selector.java              try children until one succeeds
    RepeatStep.java            run body N times (or until failure)
    DynamicStep.java           materialize child Step at execution time
    FailStep.java              leaf step: check() always returns Failed(reason)

  views/
    InventoryView.java         size, freeSlots, count(itemId)
    BankView.java              open, ready, pinUp, availability(itemId)
    WidgetView.java            isVisible, isHidden, visibleRootIds
    InteractionView.java       mode, worldInteractionAvailable, affordances
    EventFacts.java            lastInventoryChangeTick, lastBankContainerChangeTick, …

  telemetry/
    Telemetry.java             record(TelemetryRecord), tail(n), subscribe/unsubscribe
    RingBufferTelemetry.java   fixed-capacity circular buffer implementation
    TelemetryRecord.java       record: tick, frameDepth, stepName, Event, payload

  activities/
    WalkStep.java              walk to a WorldPoint with arrival radius
    banking/                   all banking steps (see §11)

  login/
    LoginRunner.java           login FSM — NOT using Step/SequenceEngine (see §10)

  internal/                    StateDrivenEngine, PriorityPlanner, Observer, Planner
  dispatch/                    InputDispatcher, InputOwnership
```

## 3. Core abstractions

**`SequenceEngine`** (`SequenceEngine.java`) drives execution. `start(Step)` begins
a run; `advanceTick()` drives one game tick. `registerReactive(Step)` adds a
always-present step that the planner considers each tick alongside the root
chain. Tests drive the engine synchronously via `advanceTick()`.

**`Step`** (`Step.java`) is the one interface everything implements:
`canStart` / `onStart` / `tick` / `onEvent` / `check` / `onFailure`. Steps are
stateless instances; working state goes in `bb.scope(BlackboardScope.STEP)`.

**`WorldSnapshot`** (`WorldSnapshot.java`) is the read-only game-state facade
passed to `canStart`, `check`, and `onFailure`. It exposes five typed views
(see §6). Steps MUST NOT read `Client` directly; they read `WorldSnapshot`.

**`Completion`** (`Completion.java`) is a sealed interface returned by `check`:
`Running` (keep ticking), `Succeeded(reason)`, or `Failed(reason, diagnostic)`.
`Completion.RUNNING` is a singleton constant; use it instead of `new Running()`.

**`Recovery`** (`Recovery.java`) is a sealed interface returned by `onFailure`:
`Retry(maxAttempts)`, `Skip(reason)`, `Abort(reason)`, or
`JumpToAnchor(anchorName)` which restarts a `LinearSequence` at a named anchor.

**`Failure`** (`Failure.java`) carries the reason, elapsed ticks, optional
throwable, and optional `DiagnosticReason` delivered to `onFailure`.

**`PreemptionPolicy`** (`PreemptionPolicy.java`) declares how a running step may
be interrupted by a higher-priority reactive: `NEVER`, `AFTER_CURRENT_TICK`,
`WHEN_SAFE` (when `isSafeToPause` returns true), or `ALWAYS`.

**`Blackboard`** (`blackboard/`) is a typed key-value store partitioned by scope.
`GLOBAL` persists across runs; `RUN` lives for one `start`/`stop` cycle;
`SEQUENCE` for one composite subtree; `STEP` is cleared when the step exits.
Steps declare their keys as `static final BlackboardKey<T>` constants.

## 4. Step lifecycle

```
canStart(snapshot, bb)
    ↓ true
onStart(ctx)          ← dispatch action OR record ALREADY_SATISFIED
    ↓
[tick(ctx) / onEvent(event, ctx)]   ← called each tick while running
    ↓
check(snapshot, bb)   ← Running | Succeeded | Failed
    ↓ Failed
onFailure(failure, snapshot, bb)  → Recovery
```

`canStart` is polled each tick by the planner until it returns true. Once true,
`onStart` is called once; then `tick` and `onEvent` are called each tick until
`check` returns non-Running. If `check` returns `Failed` and the step's
`timeoutTicks` expires first, `onFailure` is also called with a timeout failure.

Steps must not block. `onStart` dispatches an action and returns; `check`
inspects the resulting snapshot. A step that dispatches `depositAll(itemId)` in
`onStart` polls `inv.count(itemId) == 0` in `check`.

## 5. Already-satisfied semantics

A step MUST check whether its goal is already achieved before dispatching any
action. If satisfied on entry, `onStart` records the outcome in the step-scoped
blackboard and returns without dispatching; `check` detects the flag and
immediately returns `Succeeded`.

This means `canStart` returning true does NOT imply work needs to be done. The
engine will push the step, call `onStart`, and the step will exit in one tick.
Composite steps like `LinearSequence` benefit: the whole sequence completes
without dispatcher involvement when each child finds itself already satisfied.

Example — `DepositItemStep` (`activities/banking/DepositItemStep.java:41-44`):

```
if (s.inventory().count(itemId) == 0) {
    step.put(K_OUTCOME, Outcome.ALREADY_SATISFIED);
    return;   // no bank.depositAll call
}
```

`check` at line 72 reads `K_OUTCOME` and returns `Succeeded("item N not in inventory")`.

The same pattern applies to `WithdrawItemStep`, `EnsureAtBankStep`,
`EnsureInventoryMatchesLoadoutStep`, and `CloseBankStep`.

## 6. WorldSnapshot views

`WorldSnapshot` composes five view interfaces. Steps access them via the snapshot
passed to `canStart`, `check`, and `onFailure` (never via `Client` directly).

**`InventoryView`** — `size()`, `freeSlots()`, `isFull()`, `items()`,
`contains(itemId)`, `count(itemId)`. Observer reads `client.getItemContainer(InventoryID.INVENTORY)`.

**`BankView`** — `open()` (bank widget visible), `ready()` (container loaded),
`pinUp()` (PIN keypad active), `availability(itemId)` (PRESENT/ABSENT/UNKNOWN).
Observer reads `Bankmain.UNIVERSE` visibility and bank item container.

**`WidgetView`** — `isVisible(id)`, `isHidden(id)`, `visibleRootIds()`. Observer
walks the widget tree from each top-level root. Steps use this to detect blocking
dialogs by checking which root widget IDs are currently rendered.

**`InteractionView`** — `mode()` (WORLD/CUTSCENE/…), `worldInteractionAvailable()`,
`movementAvailable()`, `blockingInterface()`, `affordances()`. The `affordances()`
accessor returns an `AffordanceReport` (see §7).

**`EventFacts`** — `lastInventoryChangeTick()`, `lastBankContainerChangeTick()`,
`lastBlockingInterfaceChangeTick()`, `lastPlayerAnimationChangeTick()`. Observer
writes these on the corresponding RuneLite events. Steps use them to detect
completion without polling item counts every tick.

Default empty implementations (`InventoryView.empty()`, `BankView.empty()`, etc.)
are safe to use in tests that don't need a particular view.

## 7. Affordance layer

`ActionKind` (`affordance/ActionKind.java`) enumerates classes of dispatch:
`WALK`, `INTERACT_WORLD`, `INTERACT_INVENTORY`, `OPEN_BANK_BOOTH`,
`USE_BANK_WIDGET`, `CLOSE_BLOCKING_INTERFACE`, `DISMISS_DIALOG`, and others.

`BlockReason` (`affordance/BlockReason.java`) is a sealed set of typed
diagnostic reasons used by banking steps: `BankNotOpen`, `BankNotReady`,
`PinKeypadUp`, `LoadoutMismatch`, `NotAtLocation`, `WithdrawNoOp`, etc. Steps
return these from `check(Completion.failed(reason))` and test for them in
`onFailure` to choose `Retry` vs `Abort`.

`AffordanceReport` (`affordance/AffordanceReport.java`) bundles one `Affordance`
entry per `ActionKind`. `AffordanceReport.allAllowed()` is the proof-of-concept
stub; production wiring computes each entry from the current game state. Steps
can query `interaction.affordances().isAllowed(ActionKind.WALK)` to gate dispatch.

Note: affordance computation is scaffolded for the banking proof. The full
per-ActionKind gating (e.g. blocking `WALK` while a dialog is open) is deferred
to the next iteration.

## 8. Composite steps

**`LinearSequence`** (`composite/LinearSequence.java`) runs children in order.
If any child returns `Failed`, the sequence fails immediately. Constructed via
`new LinearSequence(name, List.of(child1, child2, ...))` or fluently with
`.then(step)`. Named anchors (`anchor(name)`) allow `Recovery.JumpToAnchor` to
restart mid-sequence rather than from the top.

**`Selector`** (`composite/Selector.java`) tries children in priority order until
one succeeds. On a child failure it calls `canStart` on the next candidate;
skips candidates where `canStart` is false. Succeeds when any child succeeds;
fails when all candidates are exhausted. Used for "try approach A, fall back to
approach B" patterns.

**`RepeatStep`** (`composite/RepeatStep.java`) runs a body step N times
(`times > 0`) or until the body fails (`times == 0`, infinite). Fails
immediately if the body returns `Failed`. Used to wrap a banking sequence in a
cooking loop: `new RepeatStep("CookLoop", bankSequence, 0)`.

**`DynamicStep`** (`composite/DynamicStep.java`) materializes its child Step
at execution time via a caller-supplied `Supplier<Step>`. The bridge between
synchronous Artemis read methods (which return `Optional<...Ref>`, not Step)
and Artemis action methods (which need a pre-resolved ref). Engine evaluates
the factory in `pushFirstChildIfComposite`; a `null` factory result fails the
frame with `DYNAMIC_FACTORY_RETURNED_NULL`, a thrown exception fails with
`DYNAMIC_FACTORY_THREW:<class>:<message>`. Per-execution state lives in
`DynamicStepFrame`, so the same `DynamicStep` instance inside a `RepeatStep`
gets a fresh child every iteration. Example:
`DynamicStep.of("find-and-attack", () -> { Optional<NpcRef> n = artemis.findNpc(q);
return n.isPresent() ? artemis.click(n.get(), "Attack") : FailStep.of("NO_TARGET"); })`.

**`FailStep`** (`composite/FailStep.java`) is a leaf step whose `check()`
returns `Completion.Failed(reason)` immediately and whose `onFailure(...)`
returns `Recovery.Abort(reason)`. Definitive failure utility — used by
`DynamicStep` factories to express "no target found" without reaching into
engine internals.

## 9. Telemetry

`RingBufferTelemetry` (`telemetry/RingBufferTelemetry.java`) holds up to
`capacity` records in a circular `ArrayDeque`. Each `TelemetryRecord` carries:
`tick`, `frameDepth`, `stepName`, an `Event` enum value
(`SELECTED | STARTED | CHECK | SUCCEEDED | FAILED | RECOVERY | PREEMPTED |
RESUMED | EVENT`), and a `payload` string.

The engine writes a record at every state transition. Callers read recent records
via `telemetry.tail(n)` and subscribe to live records via
`manager.subscribe(Consumer<TelemetryRecord>)` / `manager.unsubscribe(...)`.
Unsubscribe when the subscriber is torn down; the list is `CopyOnWriteArrayList`
so iteration is safe but leaks persist across stop/start cycles.

## 10. Case study: `LoginRunner`

`LoginRunner` (`login/LoginRunner.java`) drives the login flow. It is an
enum-FSM (`LoginState`) implemented before the `Step`/`SequenceEngine`
abstractions existed. It does NOT use `SequenceEngine`; it loops directly over a
`EnumMap<LoginState, Function<LoginContext, StateResult>>` handler table in a
tight `while(true)` on a dedicated worker thread.

`StateResult` is a sealed type: `Continue(nextState)`, `Done`, or
`Failure(LoginError)`. `LoginRunner.runWithHandlers` is the test entry point —
callers supply the handler map to inject fakes. Production calls `run(ctx)` which
wires in the real `LoginStates` methods.

The runner implements its own retry loop: one `LoginError.recoverable()` failure
triggers `LoginErrorRecovery.apply(err, ctx)`, increments a retry counter, and
restarts from `WAIT_FOR_LOGIN_SCREEN`. This mirrors what `Recovery.Retry` does
in the engine, but independently.

**Takeaway for new scripts:** LoginRunner's enum-FSM is a peer pattern for
one-shot multi-step flows that run off-tick on a worker thread. For
on-tick gameplay loops (banking, cooking, combat) use `SequenceEngine` with
`Step` trees.

## 11. Case study: `BankingSequenceFactory`

`BankingSequenceFactory.prepareCookingLoadout(location, food, needsTinderbox, bank)`
(`activities/banking/BankingSequenceFactory.java`) assembles the full cooking
bank run as a `BankingSequencePlan`.

The plan's root is a `LinearSequence("BankForCooking[<label>]")` with ten
children in order (see the factory's inline comments at lines 93-125):

1. `EnsureAtBankStep` — `canStart=true`, fails if player outside `location.bankArea()`
2. `EnsureNoBlockingInterfaceStep` — dismisses any non-allow-listed dialog
3. `OpenBankStep` — clicks bank booth, waits for `BankView.open()`
4. `WaitForBankReadyStep` — waits for `BankView.ready()` (container loaded)
5. `DepositItemStep(food.cookedId)` — already-satisfied if count==0
6. `DepositItemStep(food.burntId)` — already-satisfied if count==0
7. `WithdrawItemStep(TINDERBOX, AtLeast(1))` — optional, only if `needsTinderbox`
8. `WithdrawItemStep(food.rawId, FillRemainingInventory)` — fills to 27 or 28 slots
9. `EnsureInventoryMatchesLoadoutStep` — verifies loadout; fails with `LoadoutMismatch`
10. `CloseBankStep` — already-satisfied if bank is not open

The plan also holds a `reactiveSteps` list with one `EnsureNoBlockingInterfaceStep`
registered at high priority. The caller (`CookingScript`) registers it via
`engine.registerReactive(reactive)` so it can preempt any in-flight linear step
when a blocking dialog appears mid-sequence.

`BankActions` (`activities/banking/BankActions.java`) is the interface steps call
for actual dispatch (`openBankBooth()`, `depositAll(itemId)`, `withdrawItem(itemId,
qty)`, `closeBank()`). Production implementation delegates to
`HumanizedInputDispatcher`; the test double records calls.

## 12. How to write a new step

- **`canStart` correctness** — return false until all hard prerequisites are met
  (correct area, required UI open, etc.). Return true even if the goal is already
  satisfied; `onStart` handles the already-satisfied case.
- **Already-satisfied check first** — the first thing `onStart` does is check
  whether the goal is already met. Record the outcome in `bb.scope(STEP)`;
  `check` reads it back. Never dispatch if already satisfied.
- **Snapshot-only verification** — `check` and `onFailure` MUST only read
  `WorldSnapshot`; never call `Client` methods inside them.
- **No instance state** — steps are stateless between engine runs. All working
  memory goes in `bb.scope(BlackboardScope.STEP)` with typed `BlackboardKey`
  constants. Instance fields are config only (item IDs, timeouts, `BankActions`).
- **Declare typed `BlockReason`s** — if your step can fail for a classifiable
  reason (e.g. `BankNotOpen`, `NotAtLocation`), return a `Completion.failed(reason)`
  and match on it in `onFailure` to choose `Retry` vs `Abort`.
- **Set `preemptionPolicy` honestly** — `WHEN_SAFE` requires a correct
  `isSafeToPause` implementation. Use `NEVER` only for steps that cannot be
  interrupted mid-dispatch.

## 13. Known gaps / next-after-proof

These are scaffolded or deferred; do not implement without reading the spec first.

- **Affordance computation** — `AffordanceReport.allAllowed()` is always returned.
  Full gating by `InteractionView.mode()` and blocking-interface detection is
  the next planned task.
- **`WalkStep` affordance gating** (scenario 2 in the proof spec) — `WalkStep`
  exists but does not yet gate on `affordances().isAllowed(WALK)`.
- **Scene / object views** — no `SceneView`; steps that need game objects must
  use `BankActions` or similar injected adapters, not a raw scene scan.
- **Ground-item, NPC, varbit views** — not present; new scripts needing these
  must add a view to `WorldSnapshot` and wire its observer.
- **Transport-aware walking** — `WalkStep` uses `HumanizedInputDispatcher` directly;
  the `TrailWalker` transport logic is not integrated with the engine.
- **Full `RepeatStep` + `Selector` integration test** — composite steps have unit
  tests but no end-to-end scenario.
- **LoginRunner migration** — `LoginRunner` could be rewritten as a `Step` tree
  but that is low priority; the existing runner works and is independently tested.
