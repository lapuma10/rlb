# Sequence Engine Design

**Date:** 2026-04-25
**Status:** Approved (design phase)
**Project:** rlb (RuneLite fork)

## 1. Purpose

A state-driven sequence engine for orchestrating multi-step workflows in OSRS via the RuneLite client. The engine sits **above** RuneLite's plugin system as a separate subsystem so that upstream RuneLite updates can be merged without touching engine code. It provides:

- A library-grade engine where every major component is an interface (rip-and-replace at every layer).
- A composable `Step` abstraction that supports linear sequences, parallel groups, selectors, and repetition.
- Tick-driven execution with passive verification and explicit recovery semantics.
- A single insertion point (`InputDispatcher`) for future humanization (active / tired / afk play styles).
- A sidebar UI panel for building, running, and observing sequences.

## 2. Scope

### In scope

- The engine core: `SequenceEngine`, `Step`, `StepContext`, `Planner`, `Verifier`, `Observer`, `Blackboard`, `Telemetry`, `InputDispatcher`.
- Composite steps: `LinearSequence`, `ParallelGroup`, `Selector`, `RepeatStep`.
- A first concrete activity: `WalkStep` (replaces the standalone Walker plugin's logic).
- A thin RuneLite plugin (`SequencerPlugin`) that owns the sidebar panel and wires the engine into the client.
- A factory-based step registry so the panel can render parameter forms for any activity.

### Deferred (designed-for, not built)

- `HumanizedInputDispatcher` (timing variance, mouse curves, jitter, idle injection).
- Concrete play-style profiles (`ActiveProfile`, `TiredProfile`, `AfkProfile`).
- Persistence (saving sequences to disk, loading on startup).
- Additional activities beyond `WalkStep`.

## 3. Architectural principles

Two rules govern the whole system:

1. **No concrete class crosses a module boundary.** Modules communicate through interfaces only. If a constructor or method signature anywhere in the engine references a concrete implementation class from another module, that is a bug.
2. **`SequenceManager` is the only concrete facade.** It owns the wiring of all subsystems and exposes a stable API to the UI panel and any plugin that wants to drive a sequence. Everything below it is pluggable.

These two rules give the engine the rip-and-replace property at every layer.

## 4. Package layout

```
net.runelite.client.sequence/                  ← engine (no plugin coupling)
  SequenceManager.java                          ← public concrete facade
  SequenceEngine.java                           ← interface
  Step.java                                     ← interface
  StepContext.java                              ← interface
  Completion.java                               ← sealed: Running/Succeeded/Failed
  Recovery.java                                 ← sealed: Retry/Skip/Abort/JumpToAnchor
  PreemptionPolicy.java                         ← enum
  Failure.java                                  ← record
  SequenceState.java                            ← enum: IDLE/RUNNING/PAUSED/FAILED
  WorldSnapshot.java                            ← interface

  internal/
    StateDrivenEngine.java                      ← default SequenceEngine
    Planner.java                                ← interface
    PriorityPlanner.java                        ← default
    Observer.java                               ← interface
    ClientObserver.java                         ← default
    Executor.java                               ← engine-internal; not a swap point
    FrameStack.java
    StepFrame.java
    ActionBudget.java
    ActionRequest.java
    Actions.java                                ← interface (the only mutation API for steps)
    DirectActions.java                          ← default

  blackboard/
    Blackboard.java                             ← interface
    BlackboardKey.java                          ← typed key
    BlackboardScope.java                        ← enum: GLOBAL/RUN/SEQUENCE/STEP
    ScopedBlackboard.java                       ← default

  dispatch/
    InputDispatcher.java                        ← interface (the humanization seam)
    DirectInputDispatcher.java                  ← default

  telemetry/
    Telemetry.java                              ← interface
    RingBufferTelemetry.java                    ← default
    TelemetryRecord.java

  composite/
    LinearSequence.java
    ParallelGroup.java
    Selector.java
    RepeatStep.java

  activities/
    WalkStep.java                               ← first concrete activity
    StepFactory.java                            ← interface
    StepParam.java                              ← param descriptor for UI
    WalkStepFactory.java

net.runelite.client.plugins.sequencer/          ← thin UI plugin
  SequencerPlugin.java                          ← Plugin: wires SequenceManager, registers panel
  SequencerPanel.java                           ← PluginPanel: status, step list, log tail
  SequencerNavButton.java                       ← sidebar button
```

The `sequence/` package never imports from `plugins.sequencer/`. The plugin imports from `sequence/`, never the reverse. This keeps the engine reusable and isolated from upstream merge surface.

## 5. The Step contract

`Step` is the universal unit of work. Composite steps and primitive activities both implement it.

```java
public interface Step {
    String name();
    int priority();
    int timeoutTicks();
    PreemptionPolicy preemptionPolicy();

    boolean isSafeToPause(WorldSnapshot state, Blackboard bb);
    boolean canStart(WorldSnapshot state, Blackboard bb);

    void onStart(StepContext ctx);
    void onEvent(GameEvent event, StepContext ctx);
    void tick(StepContext ctx);
    Completion check(WorldSnapshot state, Blackboard bb);   // PASSIVE — read only
    Recovery onFailure(Failure failure, WorldSnapshot state, Blackboard bb);
}
```

### Method contracts

- `canStart(state, bb)` — pure predicate. Does this step's entry condition hold? No side effects.
- `onStart(ctx)` — called exactly once when the engine activates the step. May queue actions via `ctx.actions()`.
- `onEvent(event, ctx)` — called when a routed RuneLite event arrives while this step is active. May queue actions.
- `tick(ctx)` — called every game tick while the step is active. May queue actions.
- `check(state, bb)` — **passive only**. Returns `Running`, `Succeeded(reason)`, or `Failed(reason)`. Must not mutate anything, must not call `ctx.actions()`. The single source of truth for completion.
- `onFailure(failure, state, bb)` — invoked when `check` returns `Failed` or the step times out. Returns the desired `Recovery`.
- `priority()` — used by the planner for selection and preemption.
- `preemptionPolicy()` — `NEVER` / `AFTER_CURRENT_TICK` / `WHEN_SAFE` / `ALWAYS`. The structural rule.
- `isSafeToPause(state, bb)` — runtime override. Even with `WHEN_SAFE`, the step decides moment-to-moment.

### The mutation rule

The single architectural rule that prevents the design from becoming messy:

> Steps must not call `Client` methods, `MouseManager`, or any RuneLite mutation API directly. All game-state changes go through `ctx.actions()`. The engine is then free to enforce action budgets, route through `InputDispatcher`, and apply humanization without ever touching step code.

`StepContext` does not expose `Client`. It exposes the following:

```java
public interface StepContext {
    Actions actions();          // budget-enforced action queue
    Blackboard bb();
    WorldSnapshot snapshot();   // current tick's snapshot
    int currentTick();
    String inputMode();         // dispatcher self-reports: "direct", "active", ...
    void log(String msg);
}
```

Passive reads in `canStart` and `check` use the `WorldSnapshot` argument those methods receive directly. `onStart`, `tick`, and `onEvent` use `ctx.snapshot()` if they need to inspect state before queueing actions.

### The Actions API

Steps queue intents through `Actions`; the executor drains the queue under `ActionBudget` and forwards each request to `InputDispatcher`. Initial method set:

```java
public interface Actions {
    void walkTo(WorldPoint tile);
    void clickTile(WorldPoint tile);
    void clickNpc(int npcIndex, String option);
    void clickGameObject(int objectId, WorldPoint at, String option);
    void clickGroundItem(int itemId, WorldPoint at, String option);
    void clickWidget(int widgetId, int childIndex, String option);
    void clickInventoryItem(int slot, String option);
    void sendKey(int keyCode);
    void cancelPending();           // clear this step's queued requests
}
```

Adding a new action verb means adding a method here and implementing it in `DirectActions`. Steps stay decoupled from `Client`.

## 6. Sealed result types

```java
public sealed interface Completion {
    record Running()                  implements Completion {}
    record Succeeded(String reason)   implements Completion {}
    record Failed(String reason)      implements Completion {}
}

public sealed interface Recovery {
    record Retry(int maxAttempts)     implements Recovery {}
    record Skip(String reason)        implements Recovery {}
    record Abort(String reason)       implements Recovery {}
    record JumpToAnchor(String name)  implements Recovery {}
}

public enum PreemptionPolicy {
    NEVER, AFTER_CURRENT_TICK, WHEN_SAFE, ALWAYS
}

public enum SequenceState {
    IDLE, RUNNING, PAUSED, FAILED
}

public record Failure(
    String reason,        // free-form, mirrors Completion.Failed.reason
    int ticksElapsed,     // ticks the step ran before failing
    Throwable cause       // null if not exception-driven (e.g. timeout)
) {}
```

Sealed interfaces with reason-carrying records are used (not bare enums) so that telemetry and recovery have human-readable context. The engine constructs a `Failure` when `check()` returns `Failed` (cause = null) or when `timeoutTicks` is exceeded (cause = null, reason = `"timeout"`); steps that throw inside `tick()` / `onStart()` / `onEvent()` produce a `Failure` with the throwable as `cause`.

### Recovery semantics

How each `Recovery` is applied depends on the failed frame's position in the stack:

| Recovery | Frame is root | Frame is child of `LinearSequence` | Frame is child of `ParallelGroup` | Frame is child of `Selector` |
|---|---|---|---|---|
| `Retry(n)` | re-run frame; abort if attempts exhausted | re-run frame; on exhaustion, propagate `Failed` to parent | re-run; on exhaustion, child reports `Failed` to parent's policy | re-run; on exhaustion, parent tries next sibling |
| `Skip(reason)` | engine stops with `FAILED` | parent advances to next sibling | child treated as `Succeeded` for policy purposes | parent tries next sibling |
| `Abort(reason)` | engine stops with `FAILED` (always) | engine stops with `FAILED` (propagates to root) | engine stops with `FAILED` | engine stops with `FAILED` |
| `JumpToAnchor(name)` | engine stops with `FAILED` (no enclosing composite) | nearest enclosing `LinearSequence` with that anchor restarts from it; `FAILED` if no match found | not applicable — fails fast with `FAILED` | not applicable — fails fast with `FAILED` |

`JumpToAnchor` resolution walks **outward** through the frame stack looking for an enclosing `LinearSequence` that declared the named anchor. The first match wins. If none is found the engine stops with `FAILED`.

## 7. The engine loop

`StateDrivenEngine` subscribes to `GameTick`. Each tick:

```
snapshot = observer.snapshot(client)
routeQueuedEvents(frameStack)            // top of stack (innermost) first,
                                          // then outward to root

for each frame in frameStack (top-to-bottom):
    frame.step.tick(ctx)                  // queues action requests

executor.drainActionQueue(budget)         // arbitration + budget enforcement
                                          // delegates each request to InputDispatcher

if frameStack.empty():
    next = planner.select(snapshot, bb, registeredSteps)
    if next != null:
        push frame; next.onStart(ctx); telemetry.started(next)

else:
    activeFrame = frameStack.top()

    if reactiveStep eligible
       AND activeFrame.step.preemptionPolicy() != NEVER
       AND activeFrame.step.isSafeToPause(snapshot, bb):
        preempt(activeFrame); pick reactiveStep

    result = activeFrame.step.check(snapshot, bb)   // passive
    switch result:
        case Succeeded(r) -> telemetry.succeeded(activeFrame, r); pop()
        case Failed(r)    -> apply(activeFrame.step.onFailure(...))
        case Running()    -> if activeFrame.timedOut(): apply(onFailure(...))
```

### Key invariants

- `check()` is the **only** completion path. Timeouts are translated into a synthetic `Failed` via `onFailure`.
- `tick()`, `onStart()`, `onEvent()` are the **only** mutation paths. They go through `ctx.actions()`.
- The planner runs only when the frame stack is empty or preemption is permitted.
- Events route across the entire frame stack so composites can observe their children.

## 8. Composite steps

```
LinearSequence  — ordered children; child N's canStart gates on N-1 success.
                  Carries optional anchors as metadata for JumpToAnchor recovery.
ParallelGroup   — children active concurrently; ParallelGroup.Policy controls
                  completion. Action arbitration: priority desc, then frame
                  depth, then insertion order. ActionBudget caps per-tick
                  mutations.
Selector        — first child whose canStart returns true is chosen.
RepeatStep      — wraps a child; repeats N times or until a condition.
```

```java
public final class ParallelGroup implements Step {
    public enum Policy {
        ALL_SUCCEED,   // group succeeds when every child has Succeeded
        ANY_SUCCEEDS,  // group succeeds as soon as one child Succeeds
        FIRST_DONE     // group resolves on first terminal child (Succeeded or Failed)
    }
    // ...
}
```

Composite steps implement `Step` themselves and are nestable.

### Anchors

Anchors are metadata on `LinearSequence`, not blackboard keys:

```java
new LinearSequence("Banking")
    .anchor("START")
    .then(new WalkToBankStep(...))
    .anchor("BANK_OPEN")
    .then(new WithdrawStep(...));
```

`Recovery.JumpToAnchor("START")` is resolved by the enclosing `LinearSequence`. The blackboard is not used for anchor identity.

## 9. Frames vs definitions

A `Step` is a **reusable definition**. A `StepFrame` is **runtime state for one execution**:

```java
class StepFrame {
    Step step;
    int startedTick;
    int retryCount;
    int currentChildIndex;     // composites only
    Completion status;
    BlackboardScope stepScope; // cleared when frame pops
}
```

This separation lets the same `Step` instance appear multiple times in a sequence without state collisions. The `FrameStack` is the runtime tree of currently-active frames.

## 10. Blackboard

```java
public interface Blackboard {
    <T> void put(BlackboardKey<T> key, T value);
    <T> Optional<T> get(BlackboardKey<T> key);
    Blackboard scope(BlackboardScope scope);   // returns scoped view
}

public final class BlackboardKey<T> {
    public static <T> BlackboardKey<T> of(String name, Class<T> type);
    public String name();
    public Class<T> type();
}

public enum BlackboardScope {
    GLOBAL,    // persists across runs
    RUN,       // cleared when engine stops
    SEQUENCE,  // cleared when root sequence finishes
    STEP       // cleared when frame pops
}
```

Typed keys eliminate stringly-typed lookups. Scopes prevent leakage across runs.

## 11. Action budget and arbitration

```java
public final class ActionBudget {
    public int maxClientActionsPerTick   = 1;
    public int maxMouseActionsPerTick    = 1;
    public int maxKeyboardActionsPerTick = 1;
    public boolean passiveChecksUnlimited = true;
}
```

The Executor drains each tick's queued `ActionRequest`s under the budget. When multiple parallel children request actions in the same tick, requests are sorted by:

1. `priority` (descending)
2. `frameDepth` (deeper first — more specific scopes win)
3. `insertionOrder` (ascending — earlier-queued wins ties)

Unfulfilled requests are dropped (not carried over) — the next tick will produce fresh requests from `tick()` calls.

## 12. The InputDispatcher seam

```java
public interface InputDispatcher {
    void dispatch(ActionRequest req);
    void cancel(ActionRequest req);
    boolean isBusy();
}
```

`InputDispatcher` is the **only** boundary between the engine and the game. The engine never knows about `Client.menuAction`, `MouseManager`, `Robot`, or any other RuneLite input mechanism.

### Default implementation

`DirectInputDispatcher` calls `client.menuAction(...)` inline. Zero humanization. Single file. ~80 lines.

### Future implementation

`HumanizedInputDispatcher` composes:
- An `InputProfile` (timing parameters, click jitter, idle injection probability, mouse path style)
- A seedable RNG
- Mouse pathing utilities
- Optionally `DirectInputDispatcher` underneath as a fallback

When humanization lands, swapping is one line:

```java
manager.setInputDispatcher(new HumanizedInputDispatcher(client, profile, rng));
```

The engine, steps, planner, verifier, blackboard, telemetry, and panel all remain unchanged.

### Adaptive steps

`StepContext.inputMode()` returns a string the dispatcher self-reports (e.g. `"direct"`, `"active"`, `"tired"`, `"afk"`). Steps that want to adapt may branch on it; most ignore it. There is no type coupling between steps and humanization classes.

## 13. Engine subsystem interfaces (defaults shipped)

| Interface | Default implementation | Future replacements |
|---|---|---|
| `SequenceEngine` | `StateDrivenEngine` | `LinearEngine` (tests), `RecordingEngine` |
| `Planner` | `PriorityPlanner` | `RoundRobinPlanner`, `WeightedRandomPlanner` |
| `Observer` | `ClientObserver` | `FixtureObserver` (tests) |
| `Blackboard` | `ScopedBlackboard` | `PersistentBlackboard` |
| `Telemetry` | `RingBufferTelemetry` | `JsonFileTelemetry`, `NoopTelemetry` |
| `InputDispatcher` | `DirectInputDispatcher` | `HumanizedInputDispatcher` |
| `Step` | `WalkStep`, composites | any further activities |

The `Executor` (action queue drain + arbitration + budget enforcement) and the completion-detection logic that calls `step.check()` live **inside** `StateDrivenEngine` as engine-internal collaborators. They are not separately swappable — to change either, you swap the engine.

## 14. Telemetry

A ring buffer of structured records:

```java
public record TelemetryRecord(
    int tick,
    int frameDepth,
    String stepName,
    Event event,
    String payload          // free-form context
) {
    public enum Event {
        SELECTED,    // planner chose this step
        STARTED,     // onStart() was called
        CHECK,       // check() ran (payload includes Running/Succeeded/Failed reason)
        SUCCEEDED,   // frame popped after Succeeded
        FAILED,      // frame entered onFailure()
        RECOVERY,    // a Recovery was applied (payload = recovery type + detail)
        PREEMPTED,   // frame paused so a higher-priority step could run
        RESUMED,     // frame resumed after preemption
        EVENT        // routed RuneLite event delivered to step.onEvent()
    }
}
```

The default size is 2048 records. The panel subscribes to the telemetry stream and tails the last N to its log view. Sample output:

```
[1438] CHECK      OpenBank      RUNNING
[1437] STARTED    OpenBank
[1437] SELECTED   OpenBank      priority=60
[1436] SUCCEEDED  WalkToBank    durationTicks=13
[1431] CHECK      WalkToBank    RUNNING distance=4
[1425] STARTED    WalkToBank    target=(3208,3219)
```

## 15. SequenceManager (the public facade)

```java
public final class SequenceManager {
    public void setEngine(SequenceEngine engine);
    public void setDispatcher(InputDispatcher dispatcher);
    public void setTelemetry(Telemetry telemetry);
    public void setObserver(Observer observer);
    public void setPlanner(Planner planner);
    public void setBlackboard(Blackboard blackboard);

    public void register(Step reactiveStep);   // always-on, priority-eligible
    public void unregister(Step reactiveStep);

    public void run(Step rootSequence);
    public void pause();
    public void resume();
    public void stop();

    public SequenceState state();
    public void subscribe(Consumer<TelemetryRecord> listener);
}
```

Every setter is a one-line swap. The default wiring (`StateDrivenEngine`, `PriorityPlanner`, `ClientObserver`, `ScopedBlackboard`, `RingBufferTelemetry`, `DirectInputDispatcher`) is established by `SequencerPlugin.startUp()`.

Reactive steps registered via `register(...)` are evaluated each tick by the planner: a step is **eligible** when it is registered, its `canStart(snapshot, bb)` returns `true`, and it is not already on the frame stack. The planner picks the highest-`priority()` eligible step. If a non-reactive frame is currently active, the eligible reactive step preempts it only when the active frame's `preemptionPolicy()` and `isSafeToPause(...)` both permit it.

## 16. The first activity: WalkStep

```java
public final class WalkStep implements Step {
    private final WorldPoint target;
    private final int arrivalRadius;       // tiles, default 1

    public String name()                       { return "WalkTo " + target; }
    public int priority()                      { return 50; }
    public int timeoutTicks()                  { return 200; }
    public PreemptionPolicy preemptionPolicy() { return PreemptionPolicy.WHEN_SAFE; }

    public boolean isSafeToPause(WorldSnapshot s, Blackboard bb) { return true; }

    public boolean canStart(WorldSnapshot s, Blackboard bb) {
        return s.player() != null;
    }

    public void onStart(StepContext ctx) {
        clickNextWaypoint(ctx);
    }

    public void tick(StepContext ctx) {
        WorldSnapshot s = ctx.snapshot();
        if (s.player().isIdle() && distanceTo(target, s) > arrivalRadius) {
            clickNextWaypoint(ctx);
        }
    }

    public Completion check(WorldSnapshot s, Blackboard bb) {
        WorldPoint pos = s.player().worldLocation();
        if (pos.distanceTo(target) <= arrivalRadius) {
            return new Completion.Succeeded("arrived at " + target);
        }
        return new Completion.Running();
    }

    public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard bb) {
        return new Recovery.Retry(2);
    }

    public void onEvent(GameEvent e, StepContext ctx) { /* unused */ }

    private void clickNextWaypoint(StepContext ctx) {
        WorldPoint next = pickReachableWaypointToward(target, ctx.snapshot());
        ctx.actions().walkTo(next);   // budgeted; routed through InputDispatcher
    }
}
```

**`pickReachableWaypointToward` — v1 implementation:** pick the loaded scene tile closest to `target` that lies inside the current scene bounds (via `WorldPoint.isInScene` / `LocalPoint.fromWorld`). No collision-aware pathfinding; the game's own click-to-walk handles in-scene routing. Cross-map walks work because successive scene loads make new tiles reachable, and `tick()` re-clicks each time the player goes idle. Full pathfinding (collision-aware, multi-region) is out of scope and listed in §20.

`WalkStep` handles cross-map walks naturally: as scene loads bring more tiles into range, `tick()` re-clicks toward the target whenever the player has gone idle and is not yet within the arrival radius.

The existing `WalkerPlugin` becomes a thin wrapper that, on hotkey, builds a one-step `LinearSequence(new WalkStep(...))` and calls `manager.run(...)` — same UX, routed through the proper engine.

## 17. The UI panel

`SequencerPanel` extends `PluginPanel`. Five regions:

1. **Status header** — current state (`IDLE` / `RUNNING` / `PAUSED` / `FAILED`), current tick, name and depth of the active frame.
2. **Sequence list** — the steps in the running sequence, with check / arrow / pending markers; "Add step" button at the bottom; per-step parameter editors and reorder controls.
3. **Reactive list** — always-on registered steps with their entry conditions ("EatFood: hp < 30%"); separate add button.
4. **Controls** — Run, Pause, Stop buttons.
5. **Live log** — scrollable tail of `Telemetry`.

The panel talks only to `SequenceManager`. It never imports concrete engine internals.

### Step factories

```java
public interface StepFactory {
    String typeId();                          // "walk_to"
    String displayName();                     // "Walk To"
    List<StepParam> params();                 // [{name, type, default}]
    Step build(Map<String, Object> args);
}

public record StepParam(String name, ParamType type, Object defaultValue) {}

public enum ParamType {
    INT, STRING, WORLD_POINT, ITEM_ID, BOOLEAN, ENUM
}
```

`WalkStepFactory` declares one `WORLD_POINT` parameter (`"target"`). The panel renders three int fields (X, Y, plane) automatically. Adding new activities later requires writing the `Step` and a `StepFactory` — the panel form generation is for free.

## 18. Lifecycle and threading

- `SequencerPlugin.startUp()` instantiates `SequenceManager`, wires defaults, registers the panel and nav button.
- `SequencerPlugin.shutDown()` calls `manager.stop()` and tears down the panel.
- All engine work happens on the RuneLite client thread. `GameTick` is delivered there; `tick()`, `check()`, `onStart()`, `onEvent()` all run there.
- The panel UI lives on the EDT and posts engine commands via `ClientThread.invoke()`.
- Telemetry subscriptions are dispatched on the client thread; the panel marshals to the EDT for display.

## 19. Testing strategy

Each interface has a test double:

- `LinearEngine` — minimal engine for unit-testing step composition. Exposes `advanceTicks(int)` for synchronous test driving (not present on the production `StateDrivenEngine`).
- `FixtureObserver` — feeds canned `WorldSnapshot`s.
- `MockInputDispatcher` — captures `ActionRequest`s for assertions; never hits `Client`.

A typical step test:

```java
WalkStep step = new WalkStep(new WorldPoint(3208, 3219, 0), 1);
FixtureObserver obs = new FixtureObserver(snapshotsAlongPath);
MockInputDispatcher disp = new MockInputDispatcher();
LinearEngine engine = new LinearEngine();   // synchronous, advanceTicks-driven

manager.setEngine(engine);
manager.setObserver(obs);
manager.setDispatcher(disp);
manager.run(new LinearSequence("test").then(step));

engine.advanceTicks(20);
assertThat(disp.requests()).contains(walkTo(...));
```

## 20. Future work (out of scope for this spec)

- Concrete play-style profiles (`ActiveProfile`, `TiredProfile`, `AfkProfile`).
- Mouse path generation (bezier curves, overshoot, hover delays).
- Persistence of sequences to disk; loading on startup.
- A library of additional activities (`OpenBankStep`, `WithdrawStep`, `EatFoodStep`, `ChatCommandStep`, ...).
- Conditional / branching composites beyond `Selector`.
- Visual sequence editor (drag-and-drop) — the current panel is form-based.

## 21. Acceptance criteria

This design is complete when the following are true:

1. The `sequence/` package compiles and contains all interfaces listed in §4 with at least their default implementations.
2. `SequencerPlugin` registers, opens a panel, and is discoverable in the plugin list.
3. A user can build a single-step `LinearSequence(WalkStep)` from the panel, hit Run, and the player walks to the configured tile via the engine.
4. The telemetry ring buffer shows the expected `SELECTED → STARTED → CHECK → SUCCEEDED` sequence in the panel log.
5. Swapping `manager.setDispatcher(new MockInputDispatcher())` makes the run capture requests rather than mutate the game — verifying the seam works end-to-end.
