# Sequence Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a state-driven sequence engine for OSRS via RuneLite, with a `WalkStep` activity and a sidebar panel that can build, run, and observe sequences.

**Architecture:** Engine lives under `net.runelite.client.sequence`, isolated from the UI plugin under `net.runelite.client.plugins.sequencer`. Every major subsystem (engine, planner, observer, blackboard, telemetry, dispatcher) is an interface with a default implementation. Steps queue intents through a budgeted `Actions` API; the engine routes those to an `InputDispatcher` that's the only thing aware of `Client.menuAction`.

**Tech Stack:** Java 17 (records, sealed interfaces), JUnit 4, Mockito, Lombok, Google Guice, RuneLite plugin SDK.

**Spec:** `docs/superpowers/specs/2026-04-25-sequence-engine-design.md`

---

## Conventions

- **Source root:** `runelite-client/src/main/java/`
- **Test root:** `runelite-client/src/test/java/`
- **Package roots:** `net.runelite.client.sequence` (engine) and `net.runelite.client.plugins.sequencer` (UI plugin)
- **License header:** every new `.java` file gets the standard 2-clause BSD header used by RuneLite (copy from any neighbouring file). Headers are omitted from code blocks below for brevity — copy from a sibling file when creating new files.
- **Imports:** the explicit `import` lines are omitted from most code blocks. Add them as needed.
- **Lombok:** prefer `@Value`, `@RequiredArgsConstructor`, `@Getter`, `@Slf4j` to match codebase style. Records are used **only** for the small spec-defined data carriers (`Failure`, `ActionRequest`, `TelemetryRecord`).
- **Tests:** JUnit 4 (`org.junit.Test`, `org.junit.Assert.*`). Mockito where mocking is needed.
- **Run all tests for a package:** `./gradlew :runelite-client:test --tests 'net.runelite.client.sequence.*'`

---

## Phase 1 — Foundation Types

### Task 1: Create package skeleton + foundation enums

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/sequence/PreemptionPolicy.java`
- Create: `runelite-client/src/main/java/net/runelite/client/sequence/SequenceState.java`
- Create: `runelite-client/src/main/java/net/runelite/client/sequence/InputMode.java`
- Create: `runelite-client/src/main/java/net/runelite/client/sequence/blackboard/BlackboardScope.java`

- [ ] **Step 1: Create the four enum files**

`PreemptionPolicy.java`:
```java
package net.runelite.client.sequence;

public enum PreemptionPolicy {
    NEVER,
    AFTER_CURRENT_TICK,
    WHEN_SAFE,
    ALWAYS
}
```

`SequenceState.java`:
```java
package net.runelite.client.sequence;

public enum SequenceState {
    IDLE,
    RUNNING,
    PAUSED,
    FAILED
}
```

`InputMode.java`:
```java
package net.runelite.client.sequence;

public enum InputMode {
    DIRECT,
    HUMANIZED,
    RECORDING,
    MOCK
}
```

`blackboard/BlackboardScope.java`:
```java
package net.runelite.client.sequence.blackboard;

public enum BlackboardScope {
    GLOBAL,
    RUN,
    SEQUENCE,
    STEP
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :runelite-client:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/sequence/
git commit -m "sequence: add foundation enums"
```

---

### Task 2: Sealed result types — Completion, Recovery, Failure

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/sequence/Completion.java`
- Create: `runelite-client/src/main/java/net/runelite/client/sequence/Recovery.java`
- Create: `runelite-client/src/main/java/net/runelite/client/sequence/Failure.java`

- [ ] **Step 1: Create Completion**

```java
package net.runelite.client.sequence;

public sealed interface Completion {
    record Running() implements Completion {}
    record Succeeded(String reason) implements Completion {}
    record Failed(String reason) implements Completion {}

    Running RUNNING = new Running();
}
```

- [ ] **Step 2: Create Recovery**

```java
package net.runelite.client.sequence;

public sealed interface Recovery {
    record Retry(int maxAttempts) implements Recovery {}
    record Skip(String reason) implements Recovery {}
    record Abort(String reason) implements Recovery {}
    record JumpToAnchor(String anchorName) implements Recovery {}
}
```

- [ ] **Step 3: Create Failure**

```java
package net.runelite.client.sequence;

import javax.annotation.Nullable;

public record Failure(
    String reason,
    int ticksElapsed,
    @Nullable Throwable cause
) {
    public static Failure timeout(int ticksElapsed) {
        return new Failure("timeout", ticksElapsed, null);
    }

    public static Failure fromCheck(String reason, int ticksElapsed) {
        return new Failure(reason, ticksElapsed, null);
    }

    public static Failure fromException(Throwable t, int ticksElapsed) {
        return new Failure(t.getClass().getSimpleName() + ": " + t.getMessage(), ticksElapsed, t);
    }
}
```

- [ ] **Step 4: Verify compilation**

Run: `./gradlew :runelite-client:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/sequence/{Completion,Recovery,Failure}.java
git commit -m "sequence: add sealed result types (Completion, Recovery, Failure)"
```

---

### Task 3: Blackboard — typed key, interface, scoped impl

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/sequence/blackboard/BlackboardKey.java`
- Create: `runelite-client/src/main/java/net/runelite/client/sequence/blackboard/Blackboard.java`
- Create: `runelite-client/src/main/java/net/runelite/client/sequence/blackboard/ScopedBlackboard.java`
- Test: `runelite-client/src/test/java/net/runelite/client/sequence/blackboard/ScopedBlackboardTest.java`

- [ ] **Step 1: Create BlackboardKey**

```java
package net.runelite.client.sequence.blackboard;

import lombok.EqualsAndHashCode;
import lombok.Getter;

@Getter
@EqualsAndHashCode
public final class BlackboardKey<T> {
    private final String name;
    private final Class<T> type;

    private BlackboardKey(String name, Class<T> type) {
        this.name = name;
        this.type = type;
    }

    public static <T> BlackboardKey<T> of(String name, Class<T> type) {
        return new BlackboardKey<>(name, type);
    }
}
```

- [ ] **Step 2: Create Blackboard interface**

```java
package net.runelite.client.sequence.blackboard;

import java.util.Optional;

public interface Blackboard {
    <T> void put(BlackboardKey<T> key, T value);
    <T> Optional<T> get(BlackboardKey<T> key);
    <T> void remove(BlackboardKey<T> key);
    Blackboard scope(BlackboardScope scope);
    void clear(BlackboardScope scope);
}
```

- [ ] **Step 3: Write the failing test**

```java
package net.runelite.client.sequence.blackboard;

import org.junit.Test;
import static org.junit.Assert.*;

public class ScopedBlackboardTest {
    private static final BlackboardKey<String> NAME = BlackboardKey.of("name", String.class);
    private static final BlackboardKey<Integer> COUNT = BlackboardKey.of("count", Integer.class);

    @Test
    public void putThenGet_returnsValue() {
        ScopedBlackboard bb = new ScopedBlackboard();
        bb.scope(BlackboardScope.RUN).put(NAME, "alice");
        assertEquals("alice", bb.scope(BlackboardScope.RUN).get(NAME).orElse(null));
    }

    @Test
    public void valuesAreScopedSeparately() {
        ScopedBlackboard bb = new ScopedBlackboard();
        bb.scope(BlackboardScope.RUN).put(COUNT, 1);
        bb.scope(BlackboardScope.STEP).put(COUNT, 2);
        assertEquals(Integer.valueOf(1), bb.scope(BlackboardScope.RUN).get(COUNT).orElse(null));
        assertEquals(Integer.valueOf(2), bb.scope(BlackboardScope.STEP).get(COUNT).orElse(null));
    }

    @Test
    public void clear_removesOnlyThatScope() {
        ScopedBlackboard bb = new ScopedBlackboard();
        bb.scope(BlackboardScope.RUN).put(COUNT, 1);
        bb.scope(BlackboardScope.STEP).put(COUNT, 2);
        bb.clear(BlackboardScope.STEP);
        assertEquals(Integer.valueOf(1), bb.scope(BlackboardScope.RUN).get(COUNT).orElse(null));
        assertFalse(bb.scope(BlackboardScope.STEP).get(COUNT).isPresent());
    }

    @Test
    public void getMissingKey_returnsEmpty() {
        ScopedBlackboard bb = new ScopedBlackboard();
        assertFalse(bb.scope(BlackboardScope.RUN).get(NAME).isPresent());
    }
}
```

- [ ] **Step 4: Run test to verify it fails**

Run: `./gradlew :runelite-client:test --tests 'net.runelite.client.sequence.blackboard.ScopedBlackboardTest'`
Expected: FAIL — `ScopedBlackboard` cannot be resolved.

- [ ] **Step 5: Implement ScopedBlackboard**

```java
package net.runelite.client.sequence.blackboard;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class ScopedBlackboard implements Blackboard {
    private final EnumMap<BlackboardScope, Map<BlackboardKey<?>, Object>> stores =
        new EnumMap<>(BlackboardScope.class);
    private final BlackboardScope viewScope;

    public ScopedBlackboard() {
        this(BlackboardScope.RUN);
        for (BlackboardScope s : BlackboardScope.values()) {
            stores.put(s, new HashMap<>());
        }
    }

    private ScopedBlackboard(ScopedBlackboard parent, BlackboardScope viewScope) {
        this.stores.putAll(parent.stores);
        this.viewScope = viewScope;
    }

    private ScopedBlackboard(BlackboardScope viewScope) {
        this.viewScope = viewScope;
    }

    @Override
    public <T> void put(BlackboardKey<T> key, T value) {
        stores.get(viewScope).put(key, value);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> get(BlackboardKey<T> key) {
        Object v = stores.get(viewScope).get(key);
        return v == null ? Optional.empty() : Optional.of((T) v);
    }

    @Override
    public <T> void remove(BlackboardKey<T> key) {
        stores.get(viewScope).remove(key);
    }

    @Override
    public Blackboard scope(BlackboardScope scope) {
        return new ScopedBlackboard(this, scope);
    }

    @Override
    public void clear(BlackboardScope scope) {
        stores.get(scope).clear();
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew :runelite-client:test --tests 'net.runelite.client.sequence.blackboard.ScopedBlackboardTest'`
Expected: PASS — 4 tests.

- [ ] **Step 7: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/sequence/blackboard/ runelite-client/src/test/java/net/runelite/client/sequence/blackboard/
git commit -m "sequence: add typed Blackboard with scoped views"
```

---

### Task 4: WorldSnapshot interface

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/sequence/WorldSnapshot.java`
- Create: `runelite-client/src/main/java/net/runelite/client/sequence/PlayerView.java`

- [ ] **Step 1: Create PlayerView interface**

```java
package net.runelite.client.sequence;

import net.runelite.api.coords.WorldPoint;

public interface PlayerView {
    WorldPoint worldLocation();
    int animation();
    boolean isIdle();
    int health();
    int maxHealth();
}
```

- [ ] **Step 2: Create WorldSnapshot interface**

```java
package net.runelite.client.sequence;

import javax.annotation.Nullable;

public interface WorldSnapshot {
    int tick();
    @Nullable PlayerView player();
}
```

- [ ] **Step 3: Verify compilation**

Run: `./gradlew :runelite-client:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/sequence/{WorldSnapshot,PlayerView}.java
git commit -m "sequence: add WorldSnapshot and PlayerView interfaces"
```

---

## Phase 2 — Action Layer

### Task 5: ActionRequest + ActionBudget

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/sequence/internal/ActionRequest.java`
- Create: `runelite-client/src/main/java/net/runelite/client/sequence/internal/ActionBudget.java`

- [ ] **Step 1: Create ActionRequest**

```java
package net.runelite.client.sequence.internal;

import lombok.Value;
import net.runelite.api.coords.WorldPoint;
import javax.annotation.Nullable;

@Value
public class ActionRequest {
    public enum Kind { WALK, CLICK_TILE, CLICK_NPC, CLICK_GAME_OBJECT, CLICK_GROUND_ITEM, CLICK_WIDGET, CLICK_INV_ITEM, KEY }
    public enum Channel { CLIENT, MOUSE, KEYBOARD }

    Kind kind;
    Channel channel;
    int ownerFrameId;     // which frame requested it (for arbitration)
    int frameDepth;       // for arbitration — deeper wins ties
    int priority;         // copied from owning Step at queue time
    long insertionOrder;  // monotonic — earlier wins ties

    // payload — only the fields relevant to `kind` are non-null
    @Nullable WorldPoint tile;
    int npcIndex;
    int objectId;
    int itemId;
    int widgetId;
    int childIndex;
    int slot;
    int keyCode;
    @Nullable String option;
}
```

- [ ] **Step 2: Create ActionBudget**

```java
package net.runelite.client.sequence.internal;

public final class ActionBudget {
    public int maxClientActionsPerTick = 1;
    public int maxMouseActionsPerTick = 1;
    public int maxKeyboardActionsPerTick = 1;
    public boolean passiveChecksUnlimited = true;

    public int limitFor(ActionRequest.Channel ch) {
        return switch (ch) {
            case CLIENT -> maxClientActionsPerTick;
            case MOUSE -> maxMouseActionsPerTick;
            case KEYBOARD -> maxKeyboardActionsPerTick;
        };
    }
}
```

- [ ] **Step 3: Verify compilation**

Run: `./gradlew :runelite-client:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/sequence/internal/
git commit -m "sequence: add ActionRequest and ActionBudget"
```

---

### Task 6: Actions interface + DirectActions queue

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/sequence/internal/Actions.java`
- Create: `runelite-client/src/main/java/net/runelite/client/sequence/internal/DirectActions.java`
- Test: `runelite-client/src/test/java/net/runelite/client/sequence/internal/DirectActionsTest.java`

- [ ] **Step 1: Create Actions interface**

```java
package net.runelite.client.sequence.internal;

import net.runelite.api.coords.WorldPoint;

public interface Actions {
    void walkTo(WorldPoint tile);
    void clickTile(WorldPoint tile);
    void clickNpc(int npcIndex, String option);
    void clickGameObject(int objectId, WorldPoint at, String option);
    void clickGroundItem(int itemId, WorldPoint at, String option);
    void clickWidget(int widgetId, int childIndex, String option);
    void clickInventoryItem(int slot, String option);
    void sendKey(int keyCode);
    void cancelPending();
}
```

- [ ] **Step 2: Write the failing test**

```java
package net.runelite.client.sequence.internal;

import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class DirectActionsTest {

    @Test
    public void walkTo_queuesWalkRequest() {
        DirectActions.Sink sink = new DirectActions.Sink();
        DirectActions a = new DirectActions(sink, /* frameId */ 7, /* frameDepth */ 2, /* priority */ 50);
        a.walkTo(new WorldPoint(3208, 3219, 0));
        List<ActionRequest> queued = sink.drain();
        assertEquals(1, queued.size());
        ActionRequest r = queued.get(0);
        assertEquals(ActionRequest.Kind.WALK, r.getKind());
        assertEquals(7, r.getOwnerFrameId());
        assertEquals(50, r.getPriority());
        assertEquals(new WorldPoint(3208, 3219, 0), r.getTile());
    }

    @Test
    public void cancelPending_clearsThisOwnersRequests() {
        DirectActions.Sink sink = new DirectActions.Sink();
        DirectActions a = new DirectActions(sink, 7, 2, 50);
        DirectActions b = new DirectActions(sink, 8, 2, 50);
        a.walkTo(new WorldPoint(1, 2, 0));
        b.walkTo(new WorldPoint(3, 4, 0));
        a.cancelPending();
        List<ActionRequest> remaining = sink.drain();
        assertEquals(1, remaining.size());
        assertEquals(8, remaining.get(0).getOwnerFrameId());
    }

    @Test
    public void insertionOrder_isMonotonic() {
        DirectActions.Sink sink = new DirectActions.Sink();
        DirectActions a = new DirectActions(sink, 7, 2, 50);
        a.walkTo(new WorldPoint(1, 2, 0));
        a.walkTo(new WorldPoint(3, 4, 0));
        List<ActionRequest> queued = sink.drain();
        assertTrue(queued.get(0).getInsertionOrder() < queued.get(1).getInsertionOrder());
    }
}
```

- [ ] **Step 3: Run test — verify failure**

Run: `./gradlew :runelite-client:test --tests 'net.runelite.client.sequence.internal.DirectActionsTest'`
Expected: FAIL — `DirectActions` does not exist.

- [ ] **Step 4: Implement DirectActions**

```java
package net.runelite.client.sequence.internal;

import net.runelite.api.coords.WorldPoint;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public final class DirectActions implements Actions {

    /** Per-engine queue. One Sink shared by all DirectActions instances. */
    public static final class Sink {
        private final List<ActionRequest> queue = new ArrayList<>();
        private final AtomicLong counter = new AtomicLong();

        synchronized void enqueue(ActionRequest r) { queue.add(r); }

        public synchronized List<ActionRequest> drain() {
            List<ActionRequest> out = new ArrayList<>(queue);
            queue.clear();
            return out;
        }

        synchronized void cancelByOwner(int ownerFrameId) {
            queue.removeIf(r -> r.getOwnerFrameId() == ownerFrameId);
        }

        long nextInsertionOrder() { return counter.getAndIncrement(); }
    }

    private final Sink sink;
    private final int ownerFrameId;
    private final int frameDepth;
    private final int priority;

    public DirectActions(Sink sink, int ownerFrameId, int frameDepth, int priority) {
        this.sink = sink;
        this.ownerFrameId = ownerFrameId;
        this.frameDepth = frameDepth;
        this.priority = priority;
    }

    private ActionRequest base(ActionRequest.Kind k, ActionRequest.Channel ch) {
        return ActionRequest.builder()
            .kind(k).channel(ch)
            .ownerFrameId(ownerFrameId).frameDepth(frameDepth).priority(priority)
            .insertionOrder(sink.nextInsertionOrder())
            .build();
    }

    @Override
    public void walkTo(WorldPoint tile) {
        sink.enqueue(base(ActionRequest.Kind.WALK, ActionRequest.Channel.CLIENT).toBuilder().tile(tile).build());
    }

    @Override
    public void clickTile(WorldPoint tile) {
        sink.enqueue(base(ActionRequest.Kind.CLICK_TILE, ActionRequest.Channel.CLIENT).toBuilder().tile(tile).build());
    }

    @Override
    public void clickNpc(int npcIndex, String option) {
        sink.enqueue(base(ActionRequest.Kind.CLICK_NPC, ActionRequest.Channel.CLIENT).toBuilder()
            .npcIndex(npcIndex).option(option).build());
    }

    @Override
    public void clickGameObject(int objectId, WorldPoint at, String option) {
        sink.enqueue(base(ActionRequest.Kind.CLICK_GAME_OBJECT, ActionRequest.Channel.CLIENT).toBuilder()
            .objectId(objectId).tile(at).option(option).build());
    }

    @Override
    public void clickGroundItem(int itemId, WorldPoint at, String option) {
        sink.enqueue(base(ActionRequest.Kind.CLICK_GROUND_ITEM, ActionRequest.Channel.CLIENT).toBuilder()
            .itemId(itemId).tile(at).option(option).build());
    }

    @Override
    public void clickWidget(int widgetId, int childIndex, String option) {
        sink.enqueue(base(ActionRequest.Kind.CLICK_WIDGET, ActionRequest.Channel.CLIENT).toBuilder()
            .widgetId(widgetId).childIndex(childIndex).option(option).build());
    }

    @Override
    public void clickInventoryItem(int slot, String option) {
        sink.enqueue(base(ActionRequest.Kind.CLICK_INV_ITEM, ActionRequest.Channel.CLIENT).toBuilder()
            .slot(slot).option(option).build());
    }

    @Override
    public void sendKey(int keyCode) {
        sink.enqueue(base(ActionRequest.Kind.KEY, ActionRequest.Channel.KEYBOARD).toBuilder().keyCode(keyCode).build());
    }

    @Override
    public void cancelPending() {
        sink.cancelByOwner(ownerFrameId);
    }
}
```

- [ ] **Step 5: Add `@Builder(toBuilder = true)` to ActionRequest**

Edit `ActionRequest.java`: add `@lombok.Builder(toBuilder = true)` annotation above `@Value`. The builder is needed by `DirectActions`.

```java
@Value
@lombok.Builder(toBuilder = true)
public class ActionRequest {
    // ... unchanged body
}
```

- [ ] **Step 6: Run tests**

Run: `./gradlew :runelite-client:test --tests 'net.runelite.client.sequence.internal.DirectActionsTest'`
Expected: PASS — 3 tests.

- [ ] **Step 7: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/sequence/internal/{Actions,DirectActions,ActionRequest}.java runelite-client/src/test/java/net/runelite/client/sequence/internal/
git commit -m "sequence: add Actions interface and DirectActions queue"
```

---

### Task 7: InputDispatcher interface + DirectInputDispatcher

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/sequence/dispatch/InputDispatcher.java`
- Create: `runelite-client/src/main/java/net/runelite/client/sequence/dispatch/DirectInputDispatcher.java`

- [ ] **Step 1: Create InputDispatcher interface**

```java
package net.runelite.client.sequence.dispatch;

import net.runelite.client.sequence.InputMode;
import net.runelite.client.sequence.internal.ActionRequest;

public interface InputDispatcher {
    void dispatch(ActionRequest req);
    void cancel(ActionRequest req);
    boolean isBusy();
    InputMode mode();
}
```

- [ ] **Step 2: Create DirectInputDispatcher**

```java
package net.runelite.client.sequence.dispatch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.sequence.InputMode;
import net.runelite.client.sequence.internal.ActionRequest;

@Slf4j
@RequiredArgsConstructor
public final class DirectInputDispatcher implements InputDispatcher {
    private final Client client;

    @Override
    public void dispatch(ActionRequest req) {
        switch (req.getKind()) {
            case WALK -> walk(req.getTile());
            case CLICK_TILE -> walk(req.getTile()); // walking IS a tile click for now
            case CLICK_NPC -> npcOption(req.getNpcIndex(), req.getOption());
            case CLICK_GAME_OBJECT, CLICK_GROUND_ITEM, CLICK_WIDGET, CLICK_INV_ITEM, KEY ->
                log.debug("dispatch kind {} not yet implemented", req.getKind());
        }
    }

    private void walk(WorldPoint target) {
        if (target == null) return;
        LocalPoint local = LocalPoint.fromWorld(client.getTopLevelWorldView(), target);
        if (local == null) {
            log.debug("walk target {} not in current scene", target);
            return;
        }
        client.menuAction(local.getSceneX(), local.getSceneY(),
            MenuAction.WALK, 0, 0, "Walk here", "");
    }

    private void npcOption(int npcIndex, String option) {
        // resolution helper kept stub for v1; future tasks fill this out
        log.debug("npcOption({}, {}) not yet implemented", npcIndex, option);
    }

    @Override public void cancel(ActionRequest req) { /* in-flight cancellation N/A for direct */ }
    @Override public boolean isBusy() { return false; }
    @Override public InputMode mode() { return InputMode.DIRECT; }
}
```

- [ ] **Step 3: Verify compilation**

Run: `./gradlew :runelite-client:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/sequence/dispatch/
git commit -m "sequence: add InputDispatcher and direct (menuAction) implementation"
```

---

### Task 8: MockInputDispatcher (test double)

**Files:**
- Create: `runelite-client/src/test/java/net/runelite/client/sequence/dispatch/MockInputDispatcher.java`

- [ ] **Step 1: Create MockInputDispatcher**

```java
package net.runelite.client.sequence.dispatch;

import lombok.Getter;
import net.runelite.client.sequence.InputMode;
import net.runelite.client.sequence.internal.ActionRequest;
import java.util.ArrayList;
import java.util.List;

public final class MockInputDispatcher implements InputDispatcher {
    @Getter private final List<ActionRequest> requests = new ArrayList<>();

    @Override public void dispatch(ActionRequest req) { requests.add(req); }
    @Override public void cancel(ActionRequest req) { requests.remove(req); }
    @Override public boolean isBusy() { return false; }
    @Override public InputMode mode() { return InputMode.MOCK; }

    public void clear() { requests.clear(); }
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :runelite-client:compileTestJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add runelite-client/src/test/java/net/runelite/client/sequence/dispatch/
git commit -m "sequence: add MockInputDispatcher test double"
```

---

## Phase 3 — Step Abstractions

### Task 9: Step + StepContext interfaces

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/sequence/Step.java`
- Create: `runelite-client/src/main/java/net/runelite/client/sequence/StepContext.java`

- [ ] **Step 1: Create StepContext**

```java
package net.runelite.client.sequence;

import net.runelite.client.sequence.blackboard.Blackboard;
import net.runelite.client.sequence.internal.Actions;

public interface StepContext {
    Actions actions();
    Blackboard bb();
    WorldSnapshot snapshot();
    int currentTick();
    InputMode inputMode();
    void log(String msg);
}
```

- [ ] **Step 2: Create Step**

```java
package net.runelite.client.sequence;

import net.runelite.api.events.GameEvent;
import net.runelite.client.sequence.blackboard.Blackboard;

public interface Step {
    String name();
    int priority();
    int timeoutTicks();
    PreemptionPolicy preemptionPolicy();

    boolean isSafeToPause(WorldSnapshot state, Blackboard bb);
    boolean canStart(WorldSnapshot state, Blackboard bb);

    void onStart(StepContext ctx);
    void onEvent(Object event, StepContext ctx);
    void tick(StepContext ctx);
    Completion check(WorldSnapshot state, Blackboard bb);
    Recovery onFailure(Failure failure, WorldSnapshot state, Blackboard bb);
}
```

Note: `onEvent` takes `Object` — RuneLite events are not type-tagged from a common base. Steps narrow with `instanceof`.

- [ ] **Step 3: Verify compilation**

Run: `./gradlew :runelite-client:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/sequence/{Step,StepContext}.java
git commit -m "sequence: add Step and StepContext interfaces"
```

---

### Task 10: StepFrame + composite frame subclasses + FrameStack

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/sequence/internal/StepFrame.java`
- Create: `runelite-client/src/main/java/net/runelite/client/sequence/internal/FrameStack.java`
- Test: `runelite-client/src/test/java/net/runelite/client/sequence/internal/FrameStackTest.java`

- [ ] **Step 1: Create StepFrame**

Lombok note: `@Setter` cannot be applied at class level when any field is `final`. We mark only the mutable fields with `@Setter` and use plain `@Getter` for everything.

```java
package net.runelite.client.sequence.internal;

import lombok.Getter;
import lombok.Setter;
import net.runelite.client.sequence.Completion;
import net.runelite.client.sequence.Step;

@Getter
public class StepFrame {
    private static final java.util.concurrent.atomic.AtomicInteger ID = new java.util.concurrent.atomic.AtomicInteger();

    private final int id = ID.incrementAndGet();
    private final Step step;
    private final int depth;       // distance from root frame; 0 = root
    @Setter private int startedTick;
    @Setter private int retryCount;
    @Setter private Completion status = new Completion.Running();
    @Setter private boolean started;   // false until onStart() has run

    public StepFrame(Step step, int depth) {
        this.step = step;
        this.depth = depth;
    }

    public boolean timedOut(int currentTick) {
        return started && currentTick - startedTick >= step.timeoutTicks();
    }
}
```

- [ ] **Step 2: Write the failing FrameStack test**

```java
package net.runelite.client.sequence.internal;

import net.runelite.client.sequence.*;
import net.runelite.client.sequence.blackboard.Blackboard;
import org.junit.Test;
import static org.junit.Assert.*;

public class FrameStackTest {
    @Test
    public void pushPop_isLifo() {
        FrameStack s = new FrameStack();
        StepFrame a = new StepFrame(stub("A"), 0);
        StepFrame b = new StepFrame(stub("B"), 1);
        s.push(a);
        s.push(b);
        assertSame(b, s.top());
        s.pop();
        assertSame(a, s.top());
    }

    @Test
    public void empty_returnsTrueWhenNoFrames() {
        FrameStack s = new FrameStack();
        assertTrue(s.isEmpty());
        s.push(new StepFrame(stub("A"), 0));
        assertFalse(s.isEmpty());
    }

    @Test
    public void leaves_returnsTopFrame_forLinearStack() {
        FrameStack s = new FrameStack();
        StepFrame a = new StepFrame(stub("A"), 0);
        StepFrame b = new StepFrame(stub("B"), 1);
        s.push(a); s.push(b);
        assertEquals(java.util.List.of(b), s.leaves());
    }

    private static Step stub(String n) {
        return new Step() {
            public String name() { return n; }
            public int priority() { return 0; }
            public int timeoutTicks() { return 100; }
            public PreemptionPolicy preemptionPolicy() { return PreemptionPolicy.ALWAYS; }
            public boolean isSafeToPause(WorldSnapshot s, Blackboard b) { return true; }
            public boolean canStart(WorldSnapshot s, Blackboard b) { return true; }
            public void onStart(StepContext c) {}
            public void onEvent(Object e, StepContext c) {}
            public void tick(StepContext c) {}
            public Completion check(WorldSnapshot s, Blackboard b) { return Completion.RUNNING; }
            public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard b) { return new Recovery.Abort("none"); }
        };
    }
}
```

- [ ] **Step 3: Run test — verify failure**

Run: `./gradlew :runelite-client:test --tests 'net.runelite.client.sequence.internal.FrameStackTest'`
Expected: FAIL — `FrameStack` does not exist.

- [ ] **Step 4: Implement FrameStack**

Note: for v1 we model the stack as a simple ArrayList (linear case). ParallelGroup support is added when ParallelGroup itself is built — we extend `leaves()` then.

```java
package net.runelite.client.sequence.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class FrameStack {
    private final List<StepFrame> frames = new ArrayList<>();

    public void push(StepFrame f) { frames.add(f); }

    public StepFrame pop() {
        if (frames.isEmpty()) throw new IllegalStateException("frame stack empty");
        return frames.remove(frames.size() - 1);
    }

    public StepFrame top() {
        if (frames.isEmpty()) throw new IllegalStateException("frame stack empty");
        return frames.get(frames.size() - 1);
    }

    public boolean isEmpty() { return frames.isEmpty(); }
    public int size() { return frames.size(); }

    public List<StepFrame> all() { return Collections.unmodifiableList(frames); }

    /** Leaf frames — the primitive frames that should receive tick() calls.
     *  In linear stacks this is just the top. Composites override by holding
     *  multiple active children (ParallelGroup). For v1 we return the top. */
    public List<StepFrame> leaves() {
        return frames.isEmpty() ? List.of() : List.of(frames.get(frames.size() - 1));
    }
}
```

- [ ] **Step 5: Run tests**

Run: `./gradlew :runelite-client:test --tests 'net.runelite.client.sequence.internal.FrameStackTest'`
Expected: PASS — 3 tests.

- [ ] **Step 6: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/sequence/internal/{StepFrame,FrameStack}.java runelite-client/src/test/java/net/runelite/client/sequence/internal/FrameStackTest.java
git commit -m "sequence: add StepFrame and FrameStack"
```

---

### Task 11: Telemetry — interface, record, ring buffer

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/sequence/telemetry/TelemetryRecord.java`
- Create: `runelite-client/src/main/java/net/runelite/client/sequence/telemetry/Telemetry.java`
- Create: `runelite-client/src/main/java/net/runelite/client/sequence/telemetry/RingBufferTelemetry.java`
- Test: `runelite-client/src/test/java/net/runelite/client/sequence/telemetry/RingBufferTelemetryTest.java`

- [ ] **Step 1: Create TelemetryRecord**

```java
package net.runelite.client.sequence.telemetry;

public record TelemetryRecord(
    int tick, int frameDepth, String stepName, Event event, String payload
) {
    public enum Event {
        SELECTED, STARTED, CHECK, SUCCEEDED, FAILED, RECOVERY, PREEMPTED, RESUMED, EVENT
    }
}
```

- [ ] **Step 2: Create Telemetry interface**

```java
package net.runelite.client.sequence.telemetry;

import java.util.List;
import java.util.function.Consumer;

public interface Telemetry {
    void record(TelemetryRecord r);
    List<TelemetryRecord> tail(int n);
    void subscribe(Consumer<TelemetryRecord> listener);
    void unsubscribe(Consumer<TelemetryRecord> listener);
}
```

- [ ] **Step 3: Write the failing test**

```java
package net.runelite.client.sequence.telemetry;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class RingBufferTelemetryTest {
    @Test
    public void record_storesAndTails() {
        RingBufferTelemetry t = new RingBufferTelemetry(4);
        for (int i = 0; i < 3; i++) {
            t.record(new TelemetryRecord(i, 0, "Step" + i, TelemetryRecord.Event.STARTED, ""));
        }
        List<TelemetryRecord> tail = t.tail(10);
        assertEquals(3, tail.size());
        assertEquals(0, tail.get(0).tick());
    }

    @Test
    public void overflow_dropsOldest() {
        RingBufferTelemetry t = new RingBufferTelemetry(2);
        for (int i = 0; i < 5; i++) {
            t.record(new TelemetryRecord(i, 0, "Step" + i, TelemetryRecord.Event.STARTED, ""));
        }
        List<TelemetryRecord> tail = t.tail(10);
        assertEquals(2, tail.size());
        assertEquals(3, tail.get(0).tick());
        assertEquals(4, tail.get(1).tick());
    }

    @Test
    public void subscriber_receivesRecords() {
        RingBufferTelemetry t = new RingBufferTelemetry(4);
        List<TelemetryRecord> seen = new ArrayList<>();
        t.subscribe(seen::add);
        t.record(new TelemetryRecord(1, 0, "X", TelemetryRecord.Event.STARTED, ""));
        assertEquals(1, seen.size());
    }
}
```

- [ ] **Step 4: Run test — verify failure**

Run: `./gradlew :runelite-client:test --tests 'net.runelite.client.sequence.telemetry.RingBufferTelemetryTest'`
Expected: FAIL — `RingBufferTelemetry` does not exist.

- [ ] **Step 5: Implement RingBufferTelemetry**

```java
package net.runelite.client.sequence.telemetry;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public final class RingBufferTelemetry implements Telemetry {
    private final int capacity;
    private final Deque<TelemetryRecord> buffer = new ArrayDeque<>();
    private final List<Consumer<TelemetryRecord>> listeners = new CopyOnWriteArrayList<>();

    public RingBufferTelemetry(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
        this.capacity = capacity;
    }

    @Override
    public synchronized void record(TelemetryRecord r) {
        if (buffer.size() == capacity) buffer.removeFirst();
        buffer.addLast(r);
        for (Consumer<TelemetryRecord> l : listeners) l.accept(r);
    }

    @Override
    public synchronized List<TelemetryRecord> tail(int n) {
        int take = Math.min(n, buffer.size());
        List<TelemetryRecord> out = new ArrayList<>(take);
        int skip = buffer.size() - take;
        int i = 0;
        for (TelemetryRecord r : buffer) {
            if (i++ >= skip) out.add(r);
        }
        return out;
    }

    @Override public void subscribe(Consumer<TelemetryRecord> l) { listeners.add(l); }
    @Override public void unsubscribe(Consumer<TelemetryRecord> l) { listeners.remove(l); }
}
```

- [ ] **Step 6: Run tests**

Run: `./gradlew :runelite-client:test --tests 'net.runelite.client.sequence.telemetry.RingBufferTelemetryTest'`
Expected: PASS — 3 tests.

- [ ] **Step 7: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/sequence/telemetry/ runelite-client/src/test/java/net/runelite/client/sequence/telemetry/
git commit -m "sequence: add Telemetry with ring-buffer implementation"
```

---

### Task 12: Observer interface + ClientObserver + FixtureObserver

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/sequence/internal/Observer.java`
- Create: `runelite-client/src/main/java/net/runelite/client/sequence/internal/ClientObserver.java`
- Create: `runelite-client/src/test/java/net/runelite/client/sequence/internal/FixtureObserver.java`

- [ ] **Step 1: Create Observer interface**

```java
package net.runelite.client.sequence.internal;

import net.runelite.client.sequence.WorldSnapshot;

public interface Observer {
    WorldSnapshot snapshot(int currentTick);
}
```

- [ ] **Step 2: Create ClientObserver**

```java
package net.runelite.client.sequence.internal;

import lombok.RequiredArgsConstructor;
import lombok.Value;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.sequence.PlayerView;
import net.runelite.client.sequence.WorldSnapshot;

@RequiredArgsConstructor
public final class ClientObserver implements Observer {
    private final Client client;

    @Override
    public WorldSnapshot snapshot(int currentTick) {
        Player p = client.getLocalPlayer();
        PlayerView pv = (p == null) ? null : new ClientPlayerView(
            p.getWorldLocation(), p.getAnimation(),
            client.getBoostedSkillLevel(net.runelite.api.Skill.HITPOINTS),
            client.getRealSkillLevel(net.runelite.api.Skill.HITPOINTS));
        return new ClientWorldSnapshot(currentTick, pv);
    }

    @Value
    private static class ClientPlayerView implements PlayerView {
        WorldPoint worldLocation;
        int animation;
        int health;
        int maxHealth;
        @Override public boolean isIdle() { return animation == -1; }
    }

    @Value
    private static class ClientWorldSnapshot implements WorldSnapshot {
        int tick;
        PlayerView player;
    }
}
```

- [ ] **Step 3: Create FixtureObserver (test double)**

```java
package net.runelite.client.sequence.internal;

import net.runelite.client.sequence.WorldSnapshot;
import java.util.ArrayList;
import java.util.List;

public final class FixtureObserver implements Observer {
    private final List<WorldSnapshot> snapshots = new ArrayList<>();
    private int idx = 0;

    public FixtureObserver(List<WorldSnapshot> snapshots) {
        this.snapshots.addAll(snapshots);
    }

    @Override
    public WorldSnapshot snapshot(int currentTick) {
        WorldSnapshot s = snapshots.get(Math.min(idx, snapshots.size() - 1));
        if (idx < snapshots.size() - 1) idx++;
        return s;
    }
}
```

- [ ] **Step 4: Verify compilation**

Run: `./gradlew :runelite-client:compileJava :runelite-client:compileTestJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/sequence/internal/{Observer,ClientObserver}.java runelite-client/src/test/java/net/runelite/client/sequence/internal/FixtureObserver.java
git commit -m "sequence: add Observer, ClientObserver, and FixtureObserver"
```

---

## Phase 4 — Engine Subsystems

### Task 13: Planner interface + PriorityPlanner

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/sequence/internal/Planner.java`
- Create: `runelite-client/src/main/java/net/runelite/client/sequence/internal/PriorityPlanner.java`
- Test: `runelite-client/src/test/java/net/runelite/client/sequence/internal/PriorityPlannerTest.java`

- [ ] **Step 1: Create Planner interface**

```java
package net.runelite.client.sequence.internal;

import net.runelite.client.sequence.Step;
import net.runelite.client.sequence.WorldSnapshot;
import net.runelite.client.sequence.blackboard.Blackboard;
import javax.annotation.Nullable;
import java.util.Collection;

public interface Planner {
    @Nullable Step select(WorldSnapshot state, Blackboard bb, Collection<Step> candidates);
}
```

- [ ] **Step 2: Write failing test**

```java
package net.runelite.client.sequence.internal;

import net.runelite.client.sequence.*;
import net.runelite.client.sequence.blackboard.Blackboard;
import net.runelite.client.sequence.blackboard.ScopedBlackboard;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class PriorityPlannerTest {
    @Test
    public void picksHighestPriorityEligibleStep() {
        Step a = step("A", 10, true);
        Step b = step("B", 50, true);
        Step c = step("C", 100, false); // not eligible
        PriorityPlanner p = new PriorityPlanner();
        Step chosen = p.select(snapshot(), new ScopedBlackboard(), List.of(a, b, c));
        assertSame(b, chosen);
    }

    @Test
    public void returnsNullWhenNoneEligible() {
        Step a = step("A", 10, false);
        PriorityPlanner p = new PriorityPlanner();
        assertNull(p.select(snapshot(), new ScopedBlackboard(), List.of(a)));
    }

    private static WorldSnapshot snapshot() {
        return new WorldSnapshot() {
            public int tick() { return 0; }
            public PlayerView player() { return null; }
        };
    }

    private static Step step(String n, int prio, boolean eligible) {
        return new Step() {
            public String name() { return n; }
            public int priority() { return prio; }
            public int timeoutTicks() { return 100; }
            public PreemptionPolicy preemptionPolicy() { return PreemptionPolicy.ALWAYS; }
            public boolean isSafeToPause(WorldSnapshot s, Blackboard b) { return true; }
            public boolean canStart(WorldSnapshot s, Blackboard b) { return eligible; }
            public void onStart(StepContext c) {}
            public void onEvent(Object e, StepContext c) {}
            public void tick(StepContext c) {}
            public Completion check(WorldSnapshot s, Blackboard b) { return Completion.RUNNING; }
            public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard b) { return new Recovery.Abort(""); }
        };
    }
}
```

- [ ] **Step 3: Run test — verify failure**

Run: `./gradlew :runelite-client:test --tests 'net.runelite.client.sequence.internal.PriorityPlannerTest'`
Expected: FAIL — `PriorityPlanner` does not exist.

- [ ] **Step 4: Implement PriorityPlanner**

```java
package net.runelite.client.sequence.internal;

import net.runelite.client.sequence.Step;
import net.runelite.client.sequence.WorldSnapshot;
import net.runelite.client.sequence.blackboard.Blackboard;
import javax.annotation.Nullable;
import java.util.Collection;

public final class PriorityPlanner implements Planner {
    @Override @Nullable
    public Step select(WorldSnapshot state, Blackboard bb, Collection<Step> candidates) {
        Step best = null;
        for (Step s : candidates) {
            if (!s.canStart(state, bb)) continue;
            if (best == null || s.priority() > best.priority()) best = s;
        }
        return best;
    }
}
```

- [ ] **Step 5: Run tests**

Run: `./gradlew :runelite-client:test --tests 'net.runelite.client.sequence.internal.PriorityPlannerTest'`
Expected: PASS — 2 tests.

- [ ] **Step 6: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/sequence/internal/{Planner,PriorityPlanner}.java runelite-client/src/test/java/net/runelite/client/sequence/internal/PriorityPlannerTest.java
git commit -m "sequence: add Planner with priority-based default"
```

---

### Task 14: Executor — drain queue under ActionBudget

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/sequence/internal/Executor.java`
- Test: `runelite-client/src/test/java/net/runelite/client/sequence/internal/ExecutorTest.java`

- [ ] **Step 1: Write failing test**

```java
package net.runelite.client.sequence.internal;

import net.runelite.api.coords.WorldPoint;
import net.runelite.client.sequence.dispatch.MockInputDispatcher;
import org.junit.Test;
import static org.junit.Assert.*;

public class ExecutorTest {

    @Test
    public void drain_dispatchesUpToBudget() {
        DirectActions.Sink sink = new DirectActions.Sink();
        DirectActions a = new DirectActions(sink, 1, 1, 50);
        a.walkTo(new WorldPoint(1, 2, 0));
        a.walkTo(new WorldPoint(3, 4, 0));    // 2nd CLIENT-channel request

        MockInputDispatcher disp = new MockInputDispatcher();
        ActionBudget budget = new ActionBudget(); // maxClientActionsPerTick = 1
        Executor exec = new Executor(disp, budget);

        exec.drain(sink);
        assertEquals(1, disp.getRequests().size());
    }

    @Test
    public void drain_arbitratesByPriorityFrameDepthInsertionOrder() {
        DirectActions.Sink sink = new DirectActions.Sink();
        // Lower-priority but earlier-queued
        new DirectActions(sink, 1, 0, 10).walkTo(new WorldPoint(1, 1, 0));
        // Higher-priority — should win
        new DirectActions(sink, 2, 0, 90).walkTo(new WorldPoint(2, 2, 0));

        MockInputDispatcher disp = new MockInputDispatcher();
        Executor exec = new Executor(disp, new ActionBudget());

        exec.drain(sink);
        assertEquals(1, disp.getRequests().size());
        assertEquals(90, disp.getRequests().get(0).getPriority());
    }
}
```

- [ ] **Step 2: Run test — verify failure**

Run: `./gradlew :runelite-client:test --tests 'net.runelite.client.sequence.internal.ExecutorTest'`
Expected: FAIL — `Executor` does not exist.

- [ ] **Step 3: Implement Executor**

```java
package net.runelite.client.sequence.internal;

import lombok.RequiredArgsConstructor;
import net.runelite.client.sequence.dispatch.InputDispatcher;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;

@RequiredArgsConstructor
public final class Executor {
    private final InputDispatcher dispatcher;
    private final ActionBudget budget;

    /** Comparator: priority desc, frame depth desc (deeper first), insertion order asc. */
    private static final Comparator<ActionRequest> ORDER =
        Comparator.<ActionRequest>comparingInt(ActionRequest::getPriority).reversed()
            .thenComparing(Comparator.<ActionRequest>comparingInt(ActionRequest::getFrameDepth).reversed())
            .thenComparingLong(ActionRequest::getInsertionOrder);

    public void drain(DirectActions.Sink sink) {
        List<ActionRequest> all = sink.drain();
        all.sort(ORDER);

        EnumMap<ActionRequest.Channel, Integer> spent = new EnumMap<>(ActionRequest.Channel.class);
        for (ActionRequest.Channel ch : ActionRequest.Channel.values()) spent.put(ch, 0);

        for (ActionRequest r : all) {
            int s = spent.get(r.getChannel());
            if (s >= budget.limitFor(r.getChannel())) continue;
            dispatcher.dispatch(r);
            spent.put(r.getChannel(), s + 1);
        }
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :runelite-client:test --tests 'net.runelite.client.sequence.internal.ExecutorTest'`
Expected: PASS — 2 tests.

- [ ] **Step 5: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/sequence/internal/Executor.java runelite-client/src/test/java/net/runelite/client/sequence/internal/ExecutorTest.java
git commit -m "sequence: add Executor with action budget arbitration"
```

---

### Task 15: SequenceEngine interface

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/sequence/SequenceEngine.java`

- [ ] **Step 1: Create SequenceEngine interface**

```java
package net.runelite.client.sequence;

public interface SequenceEngine {
    /** Start a new run with the given root step. Calling twice before stop() throws. */
    void start(Step rootStep);

    /** Pause execution; no ticks consumed until resume(). */
    void pause();

    /** Resume after pause. */
    void resume();

    /** Stop the run; clears all frames. Safe to call when idle. */
    void stop();

    /** Register an always-on reactive step considered each tick by the planner. */
    void registerReactive(Step reactive);
    void unregisterReactive(Step reactive);

    /** Drive one tick of the loop. Production: invoked by SequencerPlugin.onGameTick.
     *  Tests may call this directly to drive the engine synchronously. */
    void advanceTick();

    /** Convenience: drive {@code n} ticks back-to-back. Used by tests in lieu of
     *  a separate LinearEngine class — the spec mentions one but in this codebase
     *  StateDrivenEngine itself is synchronous-friendly. */
    default void advanceTicks(int n) { for (int i = 0; i < n; i++) advanceTick(); }

    /** Offer a RuneLite event to be routed to active frames on the next tick.
     *  Plugins call this from their @Subscribe handlers. */
    void offerEvent(Object event);

    SequenceState state();
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :runelite-client:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/sequence/SequenceEngine.java
git commit -m "sequence: add SequenceEngine interface"
```

---

### Task 16: StateDrivenEngine — production engine

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/sequence/composite/CompositeStep.java`
- Create: `runelite-client/src/main/java/net/runelite/client/sequence/internal/StateDrivenEngine.java`
- Create: `runelite-client/src/main/java/net/runelite/client/sequence/internal/DefaultStepContext.java`
- Test: `runelite-client/src/test/java/net/runelite/client/sequence/internal/StateDrivenEngineTest.java`

The engine needs `CompositeStep` (the abstract base) to exist so it can recognize composite frames. The concrete composites (`LinearSequence`, `Selector`, `RepeatStep`) are added in Tasks 17–19, which extend the engine's per-composite branches.

- [ ] **Step 1a: Create CompositeStep base**

```java
package net.runelite.client.sequence.composite;

import net.runelite.client.sequence.*;
import net.runelite.client.sequence.blackboard.Blackboard;

/** Composite steps wrap children and orchestrate them through the engine.
 *  They never tick or queue actions themselves — leaf primitives do that. */
public abstract class CompositeStep implements Step {

    /** Engine calls a frame-aware overload on each concrete composite. The
     *  abstract overload here exists so the engine can fall back if a
     *  composite type is registered but no engine branch handles it. */
    public abstract NextAction onChildPopped(Step child, Completion status,
                                             WorldSnapshot state, Blackboard bb);

    public sealed interface NextAction permits PushChild, FinishWithSuccess, FinishWithFailure {}
    public record PushChild(Step child) implements NextAction {}
    public record FinishWithSuccess(String reason) implements NextAction {}
    public record FinishWithFailure(String reason) implements NextAction {}

    // Composites never tick themselves and never queue actions
    @Override public final void tick(StepContext ctx) { /* no-op */ }
    @Override public void onEvent(Object e, StepContext ctx) { /* default */ }
    @Override public int timeoutTicks() { return Integer.MAX_VALUE; }
    @Override public PreemptionPolicy preemptionPolicy() { return PreemptionPolicy.WHEN_SAFE; }
    @Override public boolean isSafeToPause(WorldSnapshot s, Blackboard b) { return true; }
    @Override public boolean canStart(WorldSnapshot s, Blackboard b) { return true; }
    @Override public void onStart(StepContext ctx) { /* default */ }

    /** Composites stay RUNNING until the engine pops them via orchestration. */
    @Override public Completion check(WorldSnapshot s, Blackboard b) { return new Completion.Running(); }

    @Override public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard b) {
        return new Recovery.Abort(f.reason());
    }
}
```

- [ ] **Step 1b: Create DefaultStepContext**

```java
package net.runelite.client.sequence.internal;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.sequence.InputMode;
import net.runelite.client.sequence.StepContext;
import net.runelite.client.sequence.WorldSnapshot;
import net.runelite.client.sequence.blackboard.Blackboard;

@Slf4j
@RequiredArgsConstructor
public final class DefaultStepContext implements StepContext {
    private final Actions actions;
    private final Blackboard bb;
    private final WorldSnapshot snapshot;
    private final int currentTick;
    private final InputMode mode;

    @Override public Actions actions() { return actions; }
    @Override public Blackboard bb() { return bb; }
    @Override public WorldSnapshot snapshot() { return snapshot; }
    @Override public int currentTick() { return currentTick; }
    @Override public InputMode inputMode() { return mode; }
    @Override public void log(String msg) { log.info(msg); }
}
```

- [ ] **Step 2: Write failing test (smallest end-to-end behavior)**

```java
package net.runelite.client.sequence.internal;

import net.runelite.api.coords.WorldPoint;
import net.runelite.client.sequence.*;
import net.runelite.client.sequence.blackboard.Blackboard;
import net.runelite.client.sequence.blackboard.ScopedBlackboard;
import net.runelite.client.sequence.dispatch.MockInputDispatcher;
import net.runelite.client.sequence.telemetry.RingBufferTelemetry;
import net.runelite.client.sequence.telemetry.TelemetryRecord;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class StateDrivenEngineTest {

    @Test
    public void runsSingleStep_emitsExpectedTelemetry() {
        // Snapshot list — initially not at target, then arrives on tick 2
        List<WorldSnapshot> snaps = List.of(
            snap(0, new WorldPoint(0, 0, 0)),
            snap(1, new WorldPoint(0, 0, 0)),
            snap(2, new WorldPoint(5, 5, 0))
        );

        FixtureObserver obs = new FixtureObserver(snaps);
        MockInputDispatcher disp = new MockInputDispatcher();
        RingBufferTelemetry tel = new RingBufferTelemetry(64);
        ScopedBlackboard bb = new ScopedBlackboard();

        StateDrivenEngine engine = new StateDrivenEngine(obs, new PriorityPlanner(), disp, tel, bb);
        engine.start(new TestArrivedStep(new WorldPoint(5, 5, 0)));

        engine.advanceTick();   // pick + onStart, no completion yet
        engine.advanceTick();   // still RUNNING
        engine.advanceTick();   // arrives — SUCCEEDED

        List<TelemetryRecord.Event> events = tel.tail(64).stream().map(TelemetryRecord::event).toList();
        assertTrue(events.contains(TelemetryRecord.Event.SELECTED));
        assertTrue(events.contains(TelemetryRecord.Event.STARTED));
        assertTrue(events.contains(TelemetryRecord.Event.SUCCEEDED));
        assertEquals(SequenceState.IDLE, engine.state()); // popped, nothing else queued
    }

    private static WorldSnapshot snap(int tick, WorldPoint at) {
        return new WorldSnapshot() {
            public int tick() { return tick; }
            public PlayerView player() { return new PlayerView() {
                public WorldPoint worldLocation() { return at; }
                public int animation() { return -1; }
                public boolean isIdle() { return true; }
                public int health() { return 99; }
                public int maxHealth() { return 99; }
            }; }
        };
    }

    private static class TestArrivedStep implements Step {
        private final WorldPoint target;
        TestArrivedStep(WorldPoint t) { this.target = t; }
        public String name() { return "TestArrived"; }
        public int priority() { return 50; }
        public int timeoutTicks() { return 50; }
        public PreemptionPolicy preemptionPolicy() { return PreemptionPolicy.ALWAYS; }
        public boolean isSafeToPause(WorldSnapshot s, Blackboard b) { return true; }
        public boolean canStart(WorldSnapshot s, Blackboard b) { return true; }
        public void onStart(StepContext c) {}
        public void onEvent(Object e, StepContext c) {}
        public void tick(StepContext c) {}
        public Completion check(WorldSnapshot s, Blackboard b) {
            if (s.player().worldLocation().equals(target)) return new Completion.Succeeded("arrived");
            return Completion.RUNNING;
        }
        public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard b) { return new Recovery.Abort(""); }
    }
}
```

- [ ] **Step 3: Run test — verify failure**

Run: `./gradlew :runelite-client:test --tests 'net.runelite.client.sequence.internal.StateDrivenEngineTest'`
Expected: FAIL — `StateDrivenEngine` does not exist.

- [ ] **Step 4: Implement StateDrivenEngine**

The engine implements:
- The 5-step tick loop from spec §7 (events → check/pop → orchestrate → planner+preempt → tick → drain).
- Event routing — events are queued via `offerEvent()` and delivered to all active frames at tick-start.
- Preemption with suspension — when a higher-priority reactive becomes eligible and the active frame allows preemption, the current frame chain is suspended; on the reactive's completion, the suspended chain is restored.
- Recovery propagation — `Skip` / `Retry`-exhaustion / `Failed` propagates to the parent composite via the same orchestration callback used for `Succeeded`. Roots without a parent fail the run.
- `JumpToAnchor` — resolved by walking outward through the frame stack to find an enclosing `LinearSequence` with the named anchor; restarts from that index.
- Scope cleanup — `STEP` scope is cleared on every frame pop; `SEQUENCE` scope is cleared when the root frame finishes; `RUN` is cleared on `stop()`.
- Defensive try/catch — exceptions from `tick()`, `onStart()`, `onEvent()` produce a synthetic `Failed`, never propagate out of the engine.

```java
package net.runelite.client.sequence.internal;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.sequence.*;
import net.runelite.client.sequence.blackboard.Blackboard;
import net.runelite.client.sequence.blackboard.BlackboardScope;
import net.runelite.client.sequence.composite.CompositeStep;
import net.runelite.client.sequence.dispatch.InputDispatcher;
import net.runelite.client.sequence.telemetry.Telemetry;
import net.runelite.client.sequence.telemetry.TelemetryRecord;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Slf4j
public final class StateDrivenEngine implements SequenceEngine {

    private final Observer observer;
    private final Planner planner;
    private final InputDispatcher dispatcher;
    private final Telemetry telemetry;
    private final Blackboard blackboard;

    private final FrameStack frames = new FrameStack();
    /** Stack of suspended frame chains. Topmost is restored when current chain empties. */
    private final Deque<List<StepFrame>> suspended = new ArrayDeque<>();
    private final Set<Step> reactives = new LinkedHashSet<>();
    private final DirectActions.Sink sink = new DirectActions.Sink();
    private final Executor executor;
    private final Deque<Object> eventQueue = new ArrayDeque<>();

    private SequenceState state = SequenceState.IDLE;
    private int currentTick = 0;

    public StateDrivenEngine(Observer observer, Planner planner, InputDispatcher dispatcher,
                             Telemetry telemetry, Blackboard blackboard) {
        this.observer = observer;
        this.planner = planner;
        this.dispatcher = dispatcher;
        this.telemetry = telemetry;
        this.blackboard = blackboard;
        this.executor = new Executor(dispatcher, new ActionBudget());
    }

    @Override
    public synchronized void start(Step rootStep) {
        if (state != SequenceState.IDLE) throw new IllegalStateException("engine not idle");
        StepFrame frame = makeFrame(rootStep, 0);
        frames.push(frame);
        state = SequenceState.RUNNING;
        // onStart deferred to first advanceTick (so a real snapshot is available)
        telemetry.record(rec(frame, TelemetryRecord.Event.SELECTED, "priority=" + rootStep.priority()));
        // Composites: also push first child top-down so the leaf is correct on first tick.
        // pushFirstChildIfComposite doesn't use the snapshot — composite first-child push
        // is unconditional (children's canStart is checked when they're activated as leaves).
        pushFirstChildIfComposite(frame, null);
    }

    @Override public synchronized void pause()  { if (state == SequenceState.RUNNING) state = SequenceState.PAUSED; }
    @Override public synchronized void resume() { if (state == SequenceState.PAUSED)  state = SequenceState.RUNNING; }

    @Override
    public synchronized void stop() {
        while (!frames.isEmpty()) frames.pop();
        suspended.clear();
        eventQueue.clear();
        blackboard.clear(BlackboardScope.RUN);
        blackboard.clear(BlackboardScope.SEQUENCE);
        blackboard.clear(BlackboardScope.STEP);
        state = SequenceState.IDLE;
    }

    @Override public synchronized void registerReactive(Step r)   { reactives.add(r); }
    @Override public synchronized void unregisterReactive(Step r) { reactives.remove(r); }
    @Override public synchronized SequenceState state() { return state; }
    @Override public synchronized void offerEvent(Object event)   { eventQueue.add(event); }

    @Override
    public synchronized void advanceTick() {
        if (state != SequenceState.RUNNING) return;
        currentTick++;
        WorldSnapshot snap = observer.snapshot(currentTick);

        // Drain pending events to all active frames
        drainEventsTo(frames.all(), snap);

        // Run pending onStarts (deferred from start() / push)
        for (StepFrame f : new ArrayList<>(frames.all())) {
            if (!f.isStarted()) {
                f.setStartedTick(currentTick);
                f.setStarted(true);
                guarded(f, () -> f.getStep().onStart(makeCtx(snap, f)));
                telemetry.record(rec(f, TelemetryRecord.Event.STARTED, ""));
            }
        }

        // 1. Verify and pop completed/failed leaves (orchestration interleaves via popAndOrchestrate)
        for (StepFrame leaf : new ArrayList<>(frames.leaves())) {
            if (!frames.all().contains(leaf)) continue;   // popped by recursion already
            Completion c;
            try {
                c = leaf.getStep().check(snap, blackboard);
            } catch (Throwable t) {
                c = new Completion.Failed(t.getClass().getSimpleName() + ": " + t.getMessage());
            }
            telemetry.record(rec(leaf, TelemetryRecord.Event.CHECK, completionDesc(c)));
            switch (c) {
                case Completion.Succeeded s -> {
                    leaf.setStatus(s);
                    telemetry.record(rec(leaf, TelemetryRecord.Event.SUCCEEDED, s.reason()));
                    popAndOrchestrate(leaf, s, snap);
                }
                case Completion.Failed f -> {
                    int elapsed = currentTick - leaf.getStartedTick();
                    Recovery r;
                    try {
                        r = leaf.getStep().onFailure(Failure.fromCheck(f.reason(), elapsed), snap, blackboard);
                    } catch (Throwable t) {
                        r = new Recovery.Abort("onFailure threw: " + t.getMessage());
                    }
                    applyRecovery(leaf, r, snap);
                }
                case Completion.Running r -> {
                    if (leaf.timedOut(currentTick)) {
                        int elapsed = currentTick - leaf.getStartedTick();
                        Recovery rec;
                        try {
                            rec = leaf.getStep().onFailure(Failure.timeout(elapsed), snap, blackboard);
                        } catch (Throwable t) {
                            rec = new Recovery.Abort("onFailure threw: " + t.getMessage());
                        }
                        applyRecovery(leaf, rec, snap);
                    }
                }
            }
        }

        // 3. Planner & preemption
        if (frames.isEmpty()) {
            // Try to resume a suspended chain first
            if (!suspended.isEmpty()) {
                List<StepFrame> resumed = suspended.pop();
                for (StepFrame f : resumed) frames.push(f);
                telemetry.record(rec(frames.top(), TelemetryRecord.Event.RESUMED, ""));
            } else {
                Step next = planner.select(snap, blackboard, eligibleReactives());
                if (next != null) {
                    StepFrame f = makeFrame(next, 0);
                    frames.push(f);
                    telemetry.record(rec(f, TelemetryRecord.Event.SELECTED, "priority=" + next.priority()));
                    pushFirstChildIfComposite(f, snap);
                } else {
                    state = SequenceState.IDLE;
                    blackboard.clear(BlackboardScope.RUN);
                    blackboard.clear(BlackboardScope.SEQUENCE);
                }
            }
        } else {
            tryPreempt(snap);
        }

        // 4. Tick leaves
        for (StepFrame leaf : frames.leaves()) {
            try {
                leaf.getStep().tick(makeCtx(snap, leaf));
            } catch (Throwable t) {
                Recovery r = new Recovery.Abort("tick threw: " + t.getMessage());
                applyRecovery(leaf, r, snap);
            }
        }

        // 5. Drain action queue
        executor.drain(sink);
    }

    // ---- helpers ----

    private void drainEventsTo(List<StepFrame> targets, WorldSnapshot snap) {
        if (eventQueue.isEmpty() || targets.isEmpty()) { eventQueue.clear(); return; }
        // Innermost first per spec §7
        List<StepFrame> reversed = new ArrayList<>(targets);
        java.util.Collections.reverse(reversed);
        Object ev;
        while ((ev = eventQueue.poll()) != null) {
            for (StepFrame f : reversed) {
                final Object evf = ev;
                guarded(f, () -> f.getStep().onEvent(evf, makeCtx(snap, f)));
                telemetry.record(rec(f, TelemetryRecord.Event.EVENT, ev.getClass().getSimpleName()));
            }
        }
    }

    private Set<Step> eligibleReactives() {
        Set<Step> onStack = new HashSet<>();
        for (StepFrame f : frames.all()) onStack.add(f.getStep());
        Set<Step> out = new LinkedHashSet<>(reactives);
        out.removeAll(onStack);
        return out;
    }

    private void tryPreempt(WorldSnapshot snap) {
        StepFrame top = frames.top();
        if (top.getStep().preemptionPolicy() == PreemptionPolicy.NEVER) return;
        if (!top.getStep().isSafeToPause(snap, blackboard)) return;
        Step candidate = planner.select(snap, blackboard, eligibleReactives());
        if (candidate == null || candidate.priority() <= top.getStep().priority()) return;

        // Suspend whole chain, push reactive at depth 0
        List<StepFrame> chain = new ArrayList<>(frames.all());
        suspended.push(chain);
        while (!frames.isEmpty()) frames.pop();
        StepFrame f = makeFrame(candidate, 0);
        frames.push(f);
        telemetry.record(rec(top, TelemetryRecord.Event.PREEMPTED, "by " + candidate.name()));
        telemetry.record(rec(f, TelemetryRecord.Event.SELECTED, "preempting"));
        pushFirstChildIfComposite(f, snap);
    }

    private void popAndOrchestrate(StepFrame popped, Completion status, WorldSnapshot snap) {
        frames.pop();
        blackboard.clear(BlackboardScope.STEP);

        if (frames.isEmpty()) {
            // Root finished. Suspended-chain resumption happens in advanceTick step 3.
            if (status instanceof Completion.Failed) {
                failRun();
            } else {
                blackboard.clear(BlackboardScope.SEQUENCE);
            }
            return;
        }

        StepFrame parent = frames.top();
        if (!(parent.getStep() instanceof CompositeStep)) return;   // shouldn't happen
        CompositeStep.NextAction next = invokeOrchestration(parent, popped, status, snap);

        switch (next) {
            case CompositeStep.PushChild pc -> {
                StepFrame child = makeFrame(pc.child(), parent.getDepth() + 1);
                frames.push(child);
                telemetry.record(rec(child, TelemetryRecord.Event.SELECTED, "child of " + parent.getStep().name()));
                pushFirstChildIfComposite(child, snap);
            }
            case CompositeStep.FinishWithSuccess fs -> {
                Completion.Succeeded ps = new Completion.Succeeded(fs.reason());
                parent.setStatus(ps);
                telemetry.record(rec(parent, TelemetryRecord.Event.SUCCEEDED, fs.reason()));
                popAndOrchestrate(parent, ps, snap);
            }
            case CompositeStep.FinishWithFailure ff -> {
                int elapsed = currentTick - parent.getStartedTick();
                Recovery r;
                try {
                    r = parent.getStep().onFailure(Failure.fromCheck(ff.reason(), elapsed), snap, blackboard);
                } catch (Throwable t) {
                    r = new Recovery.Abort("onFailure threw: " + t.getMessage());
                }
                applyRecovery(parent, r, snap);
            }
        }
    }

    /** Type-dispatch onto the composite's frame-aware orchestration overload.
     *  Tasks 17–19 add per-composite branches via incremental edits. */
    private CompositeStep.NextAction invokeOrchestration(
        StepFrame parent, StepFrame popped, Completion status, WorldSnapshot snap) {
        // [LinearSequence branch added in Task 17]
        // [Selector branch added in Task 18]
        // [RepeatStep branch added in Task 19]
        return new CompositeStep.FinishWithFailure(
            "unknown composite type " + parent.getStep().getClass().getSimpleName());
    }

    private void applyRecovery(StepFrame frame, Recovery r, WorldSnapshot snap) {
        telemetry.record(rec(frame, TelemetryRecord.Event.RECOVERY, r.toString()));
        switch (r) {
            case Recovery.Retry retry -> {
                frame.setRetryCount(frame.getRetryCount() + 1);
                if (frame.getRetryCount() >= retry.maxAttempts()) {
                    // Exhausted — propagate Failed to parent (or fail run if root)
                    Completion.Failed propagated = new Completion.Failed("retry exhausted");
                    frame.setStatus(propagated);
                    popAndOrchestrate(frame, propagated, snap);
                } else {
                    frame.setStartedTick(currentTick);
                    frame.setStarted(false);   // onStart will re-fire at tick start of next advance
                }
            }
            case Recovery.Skip s -> {
                Completion.Succeeded synthetic = new Completion.Succeeded("skipped: " + s.reason());
                if (frames.size() == 1) {
                    // Skip at root = fail run
                    frames.pop();
                    failRun();
                } else {
                    popAndOrchestrate(frame, synthetic, snap);
                }
            }
            case Recovery.Abort a -> {
                while (!frames.isEmpty()) frames.pop();
                suspended.clear();
                failRun();
            }
            case Recovery.JumpToAnchor j -> resolveJumpToAnchor(frame, j, snap);
        }
    }

    /** Walks outward through the frame stack to find an enclosing composite that
     *  declared the named anchor. The body is filled in by Task 17 (LinearSequence
     *  is the only composite that supports anchors). */
    private void resolveJumpToAnchor(StepFrame failed, Recovery.JumpToAnchor j, WorldSnapshot snap) {
        // [LinearSequence anchor walk added in Task 17]
        applyRecovery(failed, new Recovery.Abort("anchor '" + j.anchorName() + "' not resolvable"), snap);
    }

    /** When a composite frame is pushed, we push its first eligible child too,
     *  recursing in case the child is itself a composite. Per-composite branches
     *  are added in Tasks 17–19. */
    private void pushFirstChildIfComposite(StepFrame composite, WorldSnapshot snap) {
        // [LinearSequence first-child push added in Task 17]
        // [Selector first-child push added in Task 18]
        // [RepeatStep first-child push added in Task 19]
    }

    /** Choose the right StepFrame subclass for a Step. Tasks 17–19 add their
     *  per-composite branches. */
    private StepFrame makeFrame(Step s, int depth) {
        // [LinearSequence -> LinearSequenceFrame added in Task 17]
        // [Selector -> SelectorFrame added in Task 18]
        // [RepeatStep -> RepeatStepFrame added in Task 19]
        return new StepFrame(s, depth);
    }

    private void guarded(StepFrame frame, Runnable r) {
        try { r.run(); }
        catch (Throwable t) {
            log.warn("step {} threw: {}", frame.getStep().name(), t.toString());
            applyRecovery(frame, new Recovery.Abort(t.getClass().getSimpleName() + ": " + t.getMessage()),
                observer.snapshot(currentTick));
        }
    }

    /** Centralized terminal-failure cleanup. Used by Skip-at-root, Abort, and
     *  any other path that ends a run with FAILED. Clears all scopes so the
     *  engine is fully reset before sitting in FAILED state. */
    private void failRun() {
        suspended.clear();
        blackboard.clear(BlackboardScope.STEP);
        blackboard.clear(BlackboardScope.SEQUENCE);
        blackboard.clear(BlackboardScope.RUN);
        state = SequenceState.FAILED;
    }

    private DefaultStepContext makeCtx(WorldSnapshot snap, StepFrame frame) {
        DirectActions actions = new DirectActions(sink, frame.getId(), frame.getDepth(), frame.getStep().priority());
        return new DefaultStepContext(actions, blackboard, snap, currentTick, dispatcher.mode());
    }

    private TelemetryRecord rec(StepFrame frame, TelemetryRecord.Event ev, String payload) {
        return new TelemetryRecord(currentTick, frame.getDepth(), frame.getStep().name(), ev, payload);
    }

    private static String completionDesc(Completion c) {
        return switch (c) {
            case Completion.Running r -> "RUNNING";
            case Completion.Succeeded s -> "SUCCEEDED " + s.reason();
            case Completion.Failed f -> "FAILED " + f.reason();
        };
    }
}
```

This file is large (~250 lines). To stay buildable as a single commit, follow this implementation order:
1. Skeleton with fields + start/stop/pause/resume/state
2. `advanceTick` with leaf check/pop only (no composites)
3. `popAndOrchestrate` + `invokeOrchestration` (composites land in later tasks)
4. `applyRecovery` (Retry/Skip/Abort branches)
5. `resolveJumpToAnchor`
6. `tryPreempt` + `eligibleReactives`
7. `drainEventsTo`
8. `guarded`, `makeCtx`, telemetry helpers

- [ ] **Step 5: Run tests**

Run: `./gradlew :runelite-client:test --tests 'net.runelite.client.sequence.internal.StateDrivenEngineTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/sequence/internal/{StateDrivenEngine,DefaultStepContext}.java runelite-client/src/test/java/net/runelite/client/sequence/internal/StateDrivenEngineTest.java
git commit -m "sequence: add StateDrivenEngine with primitive-step execution"
```

---

## Phase 5 — Composite Steps

### Task 17: LinearSequence + LinearSequenceFrame + engine wiring

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/sequence/composite/LinearSequence.java`
- Create: `runelite-client/src/main/java/net/runelite/client/sequence/composite/LinearSequenceFrame.java`
- Modify: `runelite-client/src/main/java/net/runelite/client/sequence/internal/StateDrivenEngine.java` — add LinearSequence branches to the four extension points (`makeFrame`, `pushFirstChildIfComposite`, `invokeOrchestration`, `resolveJumpToAnchor`)
- Test: `runelite-client/src/test/java/net/runelite/client/sequence/composite/LinearSequenceTest.java`

- [ ] **Step 1: Create LinearSequence + LinearSequenceFrame**

```java
package net.runelite.client.sequence.composite;

import lombok.Getter;
import net.runelite.client.sequence.*;
import net.runelite.client.sequence.blackboard.Blackboard;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Getter
public final class LinearSequence extends CompositeStep {
    private final String name;
    private final List<Step> children = new ArrayList<>();
    private final Map<String, Integer> anchors = new HashMap<>();   // name -> child index

    public LinearSequence(String name) { this.name = name; }

    public LinearSequence then(Step child) { children.add(child); return this; }
    public LinearSequence anchor(String anchorName) {
        anchors.put(anchorName, children.size());
        return this;
    }

    @Override public String name() { return name; }
    @Override public int priority() { return children.isEmpty() ? 0 : children.get(0).priority(); }

    @Override
    public NextAction onChildPopped(Step child, Completion status, WorldSnapshot state, Blackboard bb) {
        // The frame's own state (currentChildIndex) is held by the engine via LinearSequenceFrame.
        // We answer based on what the engine passes us — but the engine calls this *with* the frame so
        // we read currentChildIndex from there. To keep onChildPopped pure, the engine increments the
        // index BEFORE invoking us, then we just say "push child at that index" or "done".
        throw new UnsupportedOperationException("Engine drives index — use onChildPopped(frame, ...)");
    }

    /** Engine-friendly entry point. */
    public NextAction onChildPopped(LinearSequenceFrame frame, Completion status) {
        if (status instanceof Completion.Failed f) return new FinishWithFailure(f.reason());
        frame.setCurrentChildIndex(frame.getCurrentChildIndex() + 1);
        if (frame.getCurrentChildIndex() >= children.size()) {
            return new FinishWithSuccess("all children done");
        }
        return new PushChild(children.get(frame.getCurrentChildIndex()));
    }

    public int anchorIndex(String anchorName) {
        return anchors.getOrDefault(anchorName, -1);
    }
}
```

```java
package net.runelite.client.sequence.composite;

import lombok.Getter;
import lombok.Setter;
import net.runelite.client.sequence.internal.StepFrame;

public final class LinearSequenceFrame extends StepFrame {
    @Getter @Setter private int currentChildIndex = 0;
    public LinearSequenceFrame(LinearSequence step, int depth) { super(step, depth); }
}
```

- [ ] **Step 2: Wire LinearSequence into StateDrivenEngine extension points**

Edit `StateDrivenEngine.java` to add LinearSequence-specific branches at the four extension points marked with `[LinearSequence ... added in Task 17]`. Final state of each method:

`makeFrame`:
```java
private StepFrame makeFrame(Step s, int depth) {
    if (s instanceof net.runelite.client.sequence.composite.LinearSequence ls) {
        return new net.runelite.client.sequence.composite.LinearSequenceFrame(ls, depth);
    }
    // [Selector branch added in Task 18]
    // [RepeatStep branch added in Task 19]
    return new StepFrame(s, depth);
}
```

`pushFirstChildIfComposite`:
```java
private void pushFirstChildIfComposite(StepFrame composite, WorldSnapshot snap) {
    Step s = composite.getStep();
    if (s instanceof net.runelite.client.sequence.composite.LinearSequence ls
        && !ls.getChildren().isEmpty()) {
        StepFrame child = makeFrame(ls.getChildren().get(0), composite.getDepth() + 1);
        frames.push(child);
        telemetry.record(rec(child, TelemetryRecord.Event.SELECTED, "child #0"));
        pushFirstChildIfComposite(child, snap);
        return;
    }
    // [Selector branch added in Task 18]
    // [RepeatStep branch added in Task 19]
}
```

`invokeOrchestration`:
```java
private CompositeStep.NextAction invokeOrchestration(
    StepFrame parent, StepFrame popped, Completion status, WorldSnapshot snap) {
    Step parentStep = parent.getStep();
    if (parentStep instanceof net.runelite.client.sequence.composite.LinearSequence ls
        && parent instanceof net.runelite.client.sequence.composite.LinearSequenceFrame lsf) {
        return ls.onChildPopped(lsf, status);
    }
    // [Selector branch added in Task 18]
    // [RepeatStep branch added in Task 19]
    return new CompositeStep.FinishWithFailure(
        "unknown composite type " + parentStep.getClass().getSimpleName());
}
```

`resolveJumpToAnchor`:
```java
private void resolveJumpToAnchor(StepFrame failed, Recovery.JumpToAnchor j, WorldSnapshot snap) {
    List<StepFrame> all = new ArrayList<>(frames.all());
    for (int i = all.size() - 1; i >= 0; i--) {
        StepFrame f = all.get(i);
        if (f.getStep() instanceof net.runelite.client.sequence.composite.LinearSequence ls
            && f instanceof net.runelite.client.sequence.composite.LinearSequenceFrame lsf) {
            int idx = ls.anchorIndex(j.anchorName());
            if (idx >= 0) {
                while (frames.size() > i + 1) frames.pop();
                blackboard.clear(net.runelite.client.sequence.blackboard.BlackboardScope.STEP);
                lsf.setCurrentChildIndex(idx);
                Step child = ls.getChildren().get(idx);
                StepFrame childFrame = makeFrame(child, lsf.getDepth() + 1);
                frames.push(childFrame);
                telemetry.record(rec(childFrame, TelemetryRecord.Event.SELECTED,
                    "jump to " + j.anchorName()));
                pushFirstChildIfComposite(childFrame, snap);
                return;
            }
        }
    }
    applyRecovery(failed, new Recovery.Abort("anchor '" + j.anchorName() + "' not found"), snap);
}
```

- [ ] **Step 3: Write LinearSequence test**

```java
package net.runelite.client.sequence.composite;

import net.runelite.api.coords.WorldPoint;
import net.runelite.client.sequence.*;
import net.runelite.client.sequence.blackboard.Blackboard;
import net.runelite.client.sequence.blackboard.ScopedBlackboard;
import net.runelite.client.sequence.dispatch.MockInputDispatcher;
import net.runelite.client.sequence.internal.*;
import net.runelite.client.sequence.telemetry.RingBufferTelemetry;
import net.runelite.client.sequence.telemetry.TelemetryRecord;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class LinearSequenceTest {

    @Test
    public void runsTwoChildrenInOrder() {
        WorldSnapshot fixed = new WorldSnapshot() {
            public int tick() { return 0; }
            public PlayerView player() { return null; }
        };
        FixtureObserver obs = new FixtureObserver(List.of(fixed, fixed, fixed, fixed, fixed));
        RingBufferTelemetry tel = new RingBufferTelemetry(64);

        ImmediateStep a = new ImmediateStep("A");
        ImmediateStep b = new ImmediateStep("B");

        StateDrivenEngine engine = new StateDrivenEngine(
            obs, new PriorityPlanner(), new MockInputDispatcher(), tel, new ScopedBlackboard());

        engine.start(new LinearSequence("seq").then(a).then(b));
        engine.advanceTick();   // A's check returns Succeeded
        engine.advanceTick();   // B's check returns Succeeded
        engine.advanceTick();   // sequence done, engine idle

        List<String> seen = tel.tail(64).stream()
            .filter(r -> r.event() == TelemetryRecord.Event.SUCCEEDED)
            .map(TelemetryRecord::stepName).toList();
        assertEquals(List.of("A", "B", "seq"), seen);
    }

    private static class ImmediateStep implements Step {
        private final String n;
        ImmediateStep(String n) { this.n = n; }
        public String name() { return n; }
        public int priority() { return 50; }
        public int timeoutTicks() { return 50; }
        public PreemptionPolicy preemptionPolicy() { return PreemptionPolicy.ALWAYS; }
        public boolean isSafeToPause(WorldSnapshot s, Blackboard b) { return true; }
        public boolean canStart(WorldSnapshot s, Blackboard b) { return true; }
        public void onStart(StepContext c) {}
        public void onEvent(Object e, StepContext c) {}
        public void tick(StepContext c) {}
        public Completion check(WorldSnapshot s, Blackboard b) { return new Completion.Succeeded("done"); }
        public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard b) { return new Recovery.Abort(""); }
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :runelite-client:test --tests 'net.runelite.client.sequence.composite.LinearSequenceTest' --tests 'net.runelite.client.sequence.internal.StateDrivenEngineTest'`
Expected: PASS — engine test still green; new linear test green.

- [ ] **Step 5: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/sequence/composite/ runelite-client/src/test/java/net/runelite/client/sequence/composite/ runelite-client/src/main/java/net/runelite/client/sequence/internal/StateDrivenEngine.java
git commit -m "sequence: add LinearSequence composite with engine orchestration"
```

---

### Task 18: Selector composite

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/sequence/composite/Selector.java`
- Create: `runelite-client/src/main/java/net/runelite/client/sequence/composite/SelectorFrame.java`
- Modify: `runelite-client/src/main/java/net/runelite/client/sequence/internal/StateDrivenEngine.java` — add Selector orchestration branch
- Test: `runelite-client/src/test/java/net/runelite/client/sequence/composite/SelectorTest.java`

- [ ] **Step 1: Create Selector**

```java
package net.runelite.client.sequence.composite;

import net.runelite.client.sequence.*;
import net.runelite.client.sequence.blackboard.Blackboard;
import java.util.ArrayList;
import java.util.List;

public final class Selector extends CompositeStep {
    private final String name;
    private final List<Step> children = new ArrayList<>();

    public Selector(String name) { this.name = name; }
    public Selector option(Step s) { children.add(s); return this; }

    public List<Step> children() { return children; }

    @Override public String name() { return name; }
    @Override public int priority() { return 50; }

    @Override
    public NextAction onChildPopped(Step child, Completion status, WorldSnapshot s, Blackboard b) {
        throw new UnsupportedOperationException("use frame-aware overload");
    }

    public NextAction onChildPopped(SelectorFrame frame, Completion status,
                                    WorldSnapshot s, Blackboard b) {
        if (status instanceof Completion.Succeeded sc) return new FinishWithSuccess(sc.reason());
        // Failed -> try next eligible
        for (int i = frame.getNextChildIndex(); i < children.size(); i++) {
            Step candidate = children.get(i);
            if (candidate.canStart(s, b)) {
                frame.setNextChildIndex(i + 1);
                return new PushChild(candidate);
            }
        }
        return new FinishWithFailure("no selector child succeeded");
    }
}
```

- [ ] **Step 2: Create SelectorFrame**

```java
package net.runelite.client.sequence.composite;

import lombok.Getter;
import lombok.Setter;
import net.runelite.client.sequence.internal.StepFrame;

public final class SelectorFrame extends StepFrame {
    @Getter @Setter private int nextChildIndex = 1;  // 0 was pushed at start
    public SelectorFrame(Selector s, int depth) { super(s, depth); }
}
```

- [ ] **Step 3: Add Selector branches to engine extension points**

Edit `StateDrivenEngine.java`. Add to `makeFrame`:

```java
if (s instanceof net.runelite.client.sequence.composite.Selector sel) {
    return new net.runelite.client.sequence.composite.SelectorFrame(sel, depth);
}
```

Add to `pushFirstChildIfComposite`:

```java
if (s instanceof net.runelite.client.sequence.composite.Selector sel && !sel.children().isEmpty()) {
    StepFrame child = makeFrame(sel.children().get(0), composite.getDepth() + 1);
    frames.push(child);
    telemetry.record(rec(child, TelemetryRecord.Event.SELECTED, "selector option #0"));
    pushFirstChildIfComposite(child, snap);
    return;
}
```

Add to `invokeOrchestration`:

```java
if (parentStep instanceof net.runelite.client.sequence.composite.Selector sel
    && parent instanceof net.runelite.client.sequence.composite.SelectorFrame sf) {
    return sel.onChildPopped(sf, status, snap, blackboard);
}
```

- [ ] **Step 4: Write Selector test**

```java
package net.runelite.client.sequence.composite;

import net.runelite.client.sequence.*;
import net.runelite.client.sequence.blackboard.Blackboard;
import net.runelite.client.sequence.blackboard.ScopedBlackboard;
import net.runelite.client.sequence.dispatch.MockInputDispatcher;
import net.runelite.client.sequence.internal.*;
import net.runelite.client.sequence.telemetry.RingBufferTelemetry;
import net.runelite.client.sequence.telemetry.TelemetryRecord;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class SelectorTest {
    @Test
    public void firstChildSucceeds_secondChildSkipped() {
        var snap = new WorldSnapshot() {
            public int tick() { return 0; }
            public PlayerView player() { return null; }
        };
        FixtureObserver obs = new FixtureObserver(List.of(snap, snap, snap));
        RingBufferTelemetry tel = new RingBufferTelemetry(32);
        StateDrivenEngine eng = new StateDrivenEngine(
            obs, new PriorityPlanner(), new MockInputDispatcher(), tel, new ScopedBlackboard());

        eng.start(new Selector("sel")
            .option(new ImmediateSucceed("A"))
            .option(new ImmediateSucceed("B")));

        eng.advanceTick();   // A succeeds, selector finishes
        eng.advanceTick();   // engine settles
        List<String> succ = tel.tail(32).stream()
            .filter(r -> r.event() == TelemetryRecord.Event.SUCCEEDED)
            .map(TelemetryRecord::stepName).toList();
        assertTrue(succ.contains("A"));
        assertFalse(succ.contains("B"));
    }

    private static class ImmediateSucceed implements Step {
        private final String n;
        ImmediateSucceed(String n) { this.n = n; }
        public String name() { return n; }
        public int priority() { return 50; }
        public int timeoutTicks() { return 50; }
        public PreemptionPolicy preemptionPolicy() { return PreemptionPolicy.ALWAYS; }
        public boolean isSafeToPause(WorldSnapshot s, Blackboard b) { return true; }
        public boolean canStart(WorldSnapshot s, Blackboard b) { return true; }
        public void onStart(StepContext c) {}
        public void onEvent(Object e, StepContext c) {}
        public void tick(StepContext c) {}
        public Completion check(WorldSnapshot s, Blackboard b) { return new Completion.Succeeded("ok"); }
        public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard b) { return new Recovery.Abort(""); }
    }
}
```

- [ ] **Step 5: Run tests**

Run: `./gradlew :runelite-client:test --tests 'net.runelite.client.sequence.composite.*'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/sequence/composite/{Selector,SelectorFrame}.java runelite-client/src/main/java/net/runelite/client/sequence/internal/StateDrivenEngine.java runelite-client/src/test/java/net/runelite/client/sequence/composite/SelectorTest.java
git commit -m "sequence: add Selector composite"
```

---

### Task 19: RepeatStep composite

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/sequence/composite/RepeatStep.java`
- Create: `runelite-client/src/main/java/net/runelite/client/sequence/composite/RepeatStepFrame.java`
- Modify: `StateDrivenEngine.java` — Repeat orchestration branch
- Test: `runelite-client/src/test/java/net/runelite/client/sequence/composite/RepeatStepTest.java`

- [ ] **Step 1: Create RepeatStep**

```java
package net.runelite.client.sequence.composite;

import net.runelite.client.sequence.*;
import net.runelite.client.sequence.blackboard.Blackboard;

public final class RepeatStep extends CompositeStep {
    private final String name;
    private final Step body;
    private final int times;       // 0 = infinite (until child Failed)

    public RepeatStep(String name, Step body, int times) {
        this.name = name; this.body = body; this.times = times;
    }

    public Step body() { return body; }
    public int times() { return times; }

    @Override public String name() { return name; }
    @Override public int priority() { return body.priority(); }

    @Override
    public NextAction onChildPopped(Step child, Completion status, WorldSnapshot s, Blackboard b) {
        throw new UnsupportedOperationException();
    }

    public NextAction onChildPopped(RepeatStepFrame frame, Completion status) {
        if (status instanceof Completion.Failed f) return new FinishWithFailure(f.reason());
        frame.setIterationsCompleted(frame.getIterationsCompleted() + 1);
        if (times > 0 && frame.getIterationsCompleted() >= times) {
            return new FinishWithSuccess("repeat done " + times);
        }
        return new PushChild(body);
    }
}
```

- [ ] **Step 2: Create RepeatStepFrame**

```java
package net.runelite.client.sequence.composite;

import lombok.Getter;
import lombok.Setter;
import net.runelite.client.sequence.internal.StepFrame;

public final class RepeatStepFrame extends StepFrame {
    @Getter @Setter private int iterationsCompleted = 0;
    public RepeatStepFrame(RepeatStep s, int depth) { super(s, depth); }
}
```

- [ ] **Step 3: Add RepeatStep branches to engine extension points**

Edit `StateDrivenEngine.java`. Add to `makeFrame`:

```java
if (s instanceof net.runelite.client.sequence.composite.RepeatStep rs) {
    return new net.runelite.client.sequence.composite.RepeatStepFrame(rs, depth);
}
```

Add to `pushFirstChildIfComposite`:

```java
if (s instanceof net.runelite.client.sequence.composite.RepeatStep rs) {
    StepFrame child = makeFrame(rs.body(), composite.getDepth() + 1);
    frames.push(child);
    telemetry.record(rec(child, TelemetryRecord.Event.SELECTED, "repeat body"));
    pushFirstChildIfComposite(child, snap);
    return;
}
```

Add to `invokeOrchestration`:

```java
if (parentStep instanceof net.runelite.client.sequence.composite.RepeatStep rs
    && parent instanceof net.runelite.client.sequence.composite.RepeatStepFrame rsf) {
    return rs.onChildPopped(rsf, status);
}
```

- [ ] **Step 4: Write RepeatStep test**

```java
package net.runelite.client.sequence.composite;

import net.runelite.client.sequence.*;
import net.runelite.client.sequence.blackboard.Blackboard;
import net.runelite.client.sequence.blackboard.ScopedBlackboard;
import net.runelite.client.sequence.dispatch.MockInputDispatcher;
import net.runelite.client.sequence.internal.*;
import net.runelite.client.sequence.telemetry.RingBufferTelemetry;
import net.runelite.client.sequence.telemetry.TelemetryRecord;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class RepeatStepTest {
    @Test
    public void runsBodyExactlyNTimes() {
        WorldSnapshot snap = new WorldSnapshot() {
            public int tick() { return 0; }
            public PlayerView player() { return null; }
        };
        FixtureObserver obs = new FixtureObserver(List.of(snap, snap, snap, snap, snap));
        RingBufferTelemetry tel = new RingBufferTelemetry(64);
        StateDrivenEngine eng = new StateDrivenEngine(
            obs, new PriorityPlanner(), new MockInputDispatcher(), tel, new ScopedBlackboard());

        Counter body = new Counter("BODY");
        eng.start(new RepeatStep("rep", body, 3));
        for (int i = 0; i < 6; i++) eng.advanceTick();

        long doneCount = tel.tail(64).stream()
            .filter(r -> r.event() == TelemetryRecord.Event.SUCCEEDED && r.stepName().equals("BODY"))
            .count();
        assertEquals(3, doneCount);
    }

    private static class Counter implements Step {
        final String n; int count = 0;
        Counter(String n) { this.n = n; }
        public String name() { return n; }
        public int priority() { return 50; }
        public int timeoutTicks() { return 10; }
        public PreemptionPolicy preemptionPolicy() { return PreemptionPolicy.ALWAYS; }
        public boolean isSafeToPause(WorldSnapshot s, Blackboard b) { return true; }
        public boolean canStart(WorldSnapshot s, Blackboard b) { return true; }
        public void onStart(StepContext c) {}
        public void onEvent(Object e, StepContext c) {}
        public void tick(StepContext c) {}
        public Completion check(WorldSnapshot s, Blackboard b) { return new Completion.Succeeded("done"); }
        public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard b) { return new Recovery.Abort(""); }
    }
}
```

- [ ] **Step 5: Run tests**

Run: `./gradlew :runelite-client:test --tests 'net.runelite.client.sequence.composite.*'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/sequence/composite/{RepeatStep,RepeatStepFrame}.java runelite-client/src/main/java/net/runelite/client/sequence/internal/StateDrivenEngine.java runelite-client/src/test/java/net/runelite/client/sequence/composite/RepeatStepTest.java
git commit -m "sequence: add RepeatStep composite"
```

---

### Task 20: ParallelGroup (deferred to v2)

**Note:** ParallelGroup is in §2 of the spec, but for v1 acceptance criteria (single-step `LinearSequence(WalkStep)` from panel) it is not strictly required. To keep this plan tractable, ParallelGroup is left as a follow-up. The `CompositeStep` base, `FrameStack.leaves()`, and engine orchestration hook all permit adding it without breaking changes.

If you want it now, the shape is:
- `ParallelGroup` extends `CompositeStep`, holds `List<Step> children` and a `Policy` enum.
- `ParallelGroupFrame` holds `Set<StepFrame> activeChildren` and `Map<StepFrame, Completion> resolved`.
- Engine `makeFrame` adds a branch.
- `FrameStack.leaves()` returns all frames in `activeChildren` when the top is a `ParallelGroupFrame`.
- Engine `start(...)` and `popAndOrchestrate` push all children at once and check the Policy each pop.

Skip for now and record a follow-up task.

---

## Phase 6 — First Activity & Registry

### Task 21: StepParam, ParamType, StepFactory, StepRegistry

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/sequence/activities/ParamType.java`
- Create: `runelite-client/src/main/java/net/runelite/client/sequence/activities/StepParam.java`
- Create: `runelite-client/src/main/java/net/runelite/client/sequence/activities/StepFactory.java`
- Create: `runelite-client/src/main/java/net/runelite/client/sequence/activities/StepRegistry.java`
- Test: `runelite-client/src/test/java/net/runelite/client/sequence/activities/StepRegistryTest.java`

- [ ] **Step 1: Create ParamType + StepParam**

```java
package net.runelite.client.sequence.activities;

public enum ParamType {
    INT, STRING, WORLD_POINT, ITEM_ID, BOOLEAN, ENUM
}
```

```java
package net.runelite.client.sequence.activities;

public record StepParam(String name, ParamType type, Object defaultValue) {}
```

- [ ] **Step 2: Create StepFactory interface**

```java
package net.runelite.client.sequence.activities;

import net.runelite.client.sequence.Step;
import java.util.List;
import java.util.Map;

public interface StepFactory {
    String typeId();
    String displayName();
    List<StepParam> params();
    Step build(Map<String, Object> args);
}
```

- [ ] **Step 3: Write failing test**

```java
package net.runelite.client.sequence.activities;

import org.junit.Test;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public class StepRegistryTest {
    @Test
    public void registerAndLookup() {
        StepRegistry reg = new StepRegistry();
        StepFactory f = stub("walk_to", "Walk To");
        reg.register(f);
        assertEquals(1, reg.all().size());
        assertSame(f, reg.byTypeId("walk_to"));
    }

    @Test
    public void duplicateTypeId_throws() {
        StepRegistry reg = new StepRegistry();
        reg.register(stub("walk_to", "Walk To"));
        try {
            reg.register(stub("walk_to", "Other"));
            fail("expected IllegalStateException");
        } catch (IllegalStateException ok) {}
    }

    @Test
    public void unregister_removes() {
        StepRegistry reg = new StepRegistry();
        reg.register(stub("walk_to", "Walk To"));
        reg.unregister("walk_to");
        assertEquals(0, reg.all().size());
        assertNull(reg.byTypeId("walk_to"));
    }

    private static StepFactory stub(String id, String name) {
        return new StepFactory() {
            public String typeId() { return id; }
            public String displayName() { return name; }
            public List<StepParam> params() { return List.of(); }
            public net.runelite.client.sequence.Step build(Map<String, Object> args) { return null; }
        };
    }
}
```

- [ ] **Step 4: Run test — verify failure**

Run: `./gradlew :runelite-client:test --tests 'net.runelite.client.sequence.activities.StepRegistryTest'`
Expected: FAIL — `StepRegistry` does not exist.

- [ ] **Step 5: Implement StepRegistry**

```java
package net.runelite.client.sequence.activities;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class StepRegistry {
    private final Map<String, StepFactory> byTypeId = new LinkedHashMap<>();

    public synchronized void register(StepFactory factory) {
        if (byTypeId.containsKey(factory.typeId())) {
            throw new IllegalStateException("typeId already registered: " + factory.typeId());
        }
        byTypeId.put(factory.typeId(), factory);
    }

    public synchronized void unregister(String typeId) { byTypeId.remove(typeId); }

    public synchronized List<StepFactory> all() { return new ArrayList<>(byTypeId.values()); }

    @Nullable
    public synchronized StepFactory byTypeId(String typeId) { return byTypeId.get(typeId); }
}
```

- [ ] **Step 6: Run tests**

Run: `./gradlew :runelite-client:test --tests 'net.runelite.client.sequence.activities.StepRegistryTest'`
Expected: PASS — 3 tests.

- [ ] **Step 7: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/sequence/activities/{ParamType,StepParam,StepFactory,StepRegistry}.java runelite-client/src/test/java/net/runelite/client/sequence/activities/
git commit -m "sequence: add StepFactory and StepRegistry"
```

---

### Task 22: WalkStep + WalkStepFactory

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/sequence/activities/WalkStep.java`
- Create: `runelite-client/src/main/java/net/runelite/client/sequence/activities/WalkStepFactory.java`
- Test: `runelite-client/src/test/java/net/runelite/client/sequence/activities/WalkStepTest.java`

- [ ] **Step 1: Write failing test**

```java
package net.runelite.client.sequence.activities;

import net.runelite.api.coords.WorldPoint;
import net.runelite.client.sequence.*;
import net.runelite.client.sequence.blackboard.Blackboard;
import net.runelite.client.sequence.blackboard.ScopedBlackboard;
import net.runelite.client.sequence.dispatch.MockInputDispatcher;
import net.runelite.client.sequence.internal.*;
import net.runelite.client.sequence.telemetry.RingBufferTelemetry;
import net.runelite.client.sequence.telemetry.TelemetryRecord;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class WalkStepTest {

    @Test
    public void arrivedWithinRadius_returnsSucceeded() {
        WorldPoint target = new WorldPoint(3208, 3219, 0);
        WalkStep step = new WalkStep(target, 1);
        WorldSnapshot at = snapshotAt(new WorldPoint(3208, 3219, 0));
        Completion c = step.check(at, new ScopedBlackboard());
        assertTrue(c instanceof Completion.Succeeded);
    }

    @Test
    public void notArrived_returnsRunning() {
        WorldPoint target = new WorldPoint(3208, 3219, 0);
        WalkStep step = new WalkStep(target, 1);
        WorldSnapshot far = snapshotAt(new WorldPoint(3000, 3000, 0));
        assertTrue(step.check(far, new ScopedBlackboard()) instanceof Completion.Running);
    }

    @Test
    public void factoryBuildsStepFromArgs() {
        WalkStepFactory f = new WalkStepFactory();
        WorldPoint target = new WorldPoint(3208, 3219, 0);
        Step s = f.build(Map.of("target", target, "arrivalRadius", 2));
        assertTrue(s instanceof WalkStep);
        assertEquals(target, ((WalkStep) s).target());
        assertEquals(2, ((WalkStep) s).arrivalRadius());
    }

    private static WorldSnapshot snapshotAt(WorldPoint p) {
        return new WorldSnapshot() {
            public int tick() { return 0; }
            public PlayerView player() {
                return new PlayerView() {
                    public WorldPoint worldLocation() { return p; }
                    public int animation() { return -1; }
                    public boolean isIdle() { return true; }
                    public int health() { return 99; }
                    public int maxHealth() { return 99; }
                };
            }
        };
    }
}
```

- [ ] **Step 2: Run test — verify failure**

Run: `./gradlew :runelite-client:test --tests 'net.runelite.client.sequence.activities.WalkStepTest'`
Expected: FAIL — `WalkStep` does not exist.

- [ ] **Step 3: Implement WalkStep**

```java
package net.runelite.client.sequence.activities;

import net.runelite.api.coords.WorldPoint;
import net.runelite.client.sequence.*;
import net.runelite.client.sequence.blackboard.Blackboard;

public final class WalkStep implements Step {
    private final WorldPoint target;
    private final int arrivalRadius;

    public WalkStep(WorldPoint target) { this(target, 1); }
    public WalkStep(WorldPoint target, int arrivalRadius) {
        this.target = target;
        this.arrivalRadius = arrivalRadius;
    }

    public WorldPoint target() { return target; }
    public int arrivalRadius() { return arrivalRadius; }

    @Override public String name() { return "WalkTo " + target; }
    @Override public int priority() { return 50; }
    @Override public int timeoutTicks() { return 200; }
    @Override public PreemptionPolicy preemptionPolicy() { return PreemptionPolicy.WHEN_SAFE; }

    @Override public boolean isSafeToPause(WorldSnapshot s, Blackboard b) { return true; }

    @Override public boolean canStart(WorldSnapshot s, Blackboard b) {
        return s.player() != null;
    }

    @Override public void onStart(StepContext ctx) {
        clickToward(ctx);
    }

    @Override public void tick(StepContext ctx) {
        WorldSnapshot s = ctx.snapshot();
        if (s.player() == null) return;
        if (s.player().isIdle() && s.player().worldLocation().distanceTo(target) > arrivalRadius) {
            clickToward(ctx);
        }
    }

    @Override public Completion check(WorldSnapshot s, Blackboard b) {
        if (s.player() == null) return Completion.RUNNING;
        if (s.player().worldLocation().distanceTo(target) <= arrivalRadius) {
            return new Completion.Succeeded("arrived at " + target);
        }
        return Completion.RUNNING;
    }

    @Override public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard b) {
        return new Recovery.Retry(2);
    }

    @Override public void onEvent(Object e, StepContext ctx) { /* unused */ }

    private void clickToward(StepContext ctx) {
        WorldPoint waypoint = pickReachableWaypointToward(target, ctx.snapshot());
        if (waypoint != null) ctx.actions().walkTo(waypoint);
    }

    /**
     * Spec §16: pick the loaded scene tile closest to {@code target} that is
     * inside the current scene. The dispatcher only converts via
     * {@code LocalPoint.fromWorld}, which returns null for off-scene targets —
     * so we bound the click to a tile we know is in-scene. Successive scene
     * loads make new tiles reachable; tick() re-clicks each idle frame.
     */
    static WorldPoint pickReachableWaypointToward(WorldPoint target, WorldSnapshot snap) {
        if (snap.player() == null) return null;
        WorldPoint pos = snap.player().worldLocation();
        if (pos == null) return null;

        // If target is in-scene already, click it directly. We approximate
        // "in-scene" by distance-to-player; full check requires Client.
        int dist = pos.distanceTo(target);
        if (dist <= 50) return target;   // well inside the 104-tile scene

        // Otherwise pick the point along the straight line toward target that
        // is ~50 tiles from the player, clamped to scene plane.
        int dx = Math.signum((float) (target.getX() - pos.getX())) > 0 ? 50 : -50;
        int dy = Math.signum((float) (target.getY() - pos.getY())) > 0 ? 50 : -50;
        // Stay on the same plane; cross-plane walks are not supported in v1.
        return new WorldPoint(pos.getX() + dx, pos.getY() + dy, pos.getPlane());
    }
}
```

- [ ] **Step 4: Implement WalkStepFactory**

```java
package net.runelite.client.sequence.activities;

import net.runelite.api.coords.WorldPoint;
import net.runelite.client.sequence.Step;
import java.util.List;
import java.util.Map;

public final class WalkStepFactory implements StepFactory {
    @Override public String typeId() { return "walk_to"; }
    @Override public String displayName() { return "Walk To"; }
    @Override public List<StepParam> params() {
        return List.of(
            new StepParam("target", ParamType.WORLD_POINT, new WorldPoint(0, 0, 0)),
            new StepParam("arrivalRadius", ParamType.INT, 1)
        );
    }
    @Override public Step build(Map<String, Object> args) {
        WorldPoint t = (WorldPoint) args.get("target");
        int r = ((Number) args.getOrDefault("arrivalRadius", 1)).intValue();
        return new WalkStep(t, r);
    }
}
```

- [ ] **Step 5: Run tests**

Run: `./gradlew :runelite-client:test --tests 'net.runelite.client.sequence.activities.WalkStepTest'`
Expected: PASS — 3 tests.

- [ ] **Step 6: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/sequence/activities/{WalkStep,WalkStepFactory}.java runelite-client/src/test/java/net/runelite/client/sequence/activities/WalkStepTest.java
git commit -m "sequence: add WalkStep activity and WalkStepFactory"
```

---

## Phase 7 — Manager Facade

### Task 23: SequenceManager

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/sequence/SequenceManager.java`
- Test: `runelite-client/src/test/java/net/runelite/client/sequence/SequenceManagerTest.java`

- [ ] **Step 1: Write failing test**

```java
package net.runelite.client.sequence;

import net.runelite.api.coords.WorldPoint;
import net.runelite.client.sequence.activities.WalkStep;
import net.runelite.client.sequence.dispatch.MockInputDispatcher;
import net.runelite.client.sequence.internal.FixtureObserver;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class SequenceManagerTest {

    @Test
    public void runsAStep_andSwapsDispatcher() {
        WorldPoint target = new WorldPoint(5, 5, 0);
        FixtureObserver obs = new FixtureObserver(List.of(
            snap(new WorldPoint(0, 0, 0)),
            snap(new WorldPoint(0, 0, 0)),
            snap(target)));

        MockInputDispatcher mock = new MockInputDispatcher();
        SequenceManager mgr = SequenceManager.withDefaults();
        mgr.setObserver(obs);
        mgr.setDispatcher(mock);

        mgr.run(new WalkStep(target));
        // tick 1: onStart deferred → fires on first advance, queues walk, drained → mock receives ≥1 request
        // tick 2: still walking
        // tick 3: arrived → SUCCEEDED, frame popped, engine becomes IDLE
        mgr.getEngine().advanceTicks(3);

        assertFalse(mock.getRequests().isEmpty());
        assertEquals(SequenceState.IDLE, mgr.state());
    }

    private static WorldSnapshot snap(WorldPoint p) {
        return new WorldSnapshot() {
            public int tick() { return 0; }
            public PlayerView player() {
                return new PlayerView() {
                    public WorldPoint worldLocation() { return p; }
                    public int animation() { return -1; }
                    public boolean isIdle() { return true; }
                    public int health() { return 99; }
                    public int maxHealth() { return 99; }
                };
            }
        };
    }
}
```

- [ ] **Step 2: Run test — verify failure**

Run: `./gradlew :runelite-client:test --tests 'net.runelite.client.sequence.SequenceManagerTest'`
Expected: FAIL — `SequenceManager` does not exist.

- [ ] **Step 3: Implement SequenceManager**

`SequenceManager` accepts and exposes `SequenceEngine` via the interface — never the concrete `StateDrivenEngine`. This honors spec §3 principle 1 ("public APIs accept interfaces") and lets a test swap in `LinearEngine` (Task 16b) without changes.

```java
package net.runelite.client.sequence;

import lombok.Getter;
import net.runelite.client.sequence.activities.StepRegistry;
import net.runelite.client.sequence.blackboard.Blackboard;
import net.runelite.client.sequence.blackboard.ScopedBlackboard;
import net.runelite.client.sequence.dispatch.InputDispatcher;
import net.runelite.client.sequence.internal.*;
import net.runelite.client.sequence.telemetry.RingBufferTelemetry;
import net.runelite.client.sequence.telemetry.Telemetry;
import net.runelite.client.sequence.telemetry.TelemetryRecord;

import java.util.function.Consumer;

public final class SequenceManager {
    @Getter private SequenceEngine engine;
    @Getter private InputDispatcher dispatcher;
    @Getter private Telemetry telemetry;
    @Getter private Observer observer;
    @Getter private Planner planner;
    @Getter private Blackboard blackboard;
    @Getter private final StepRegistry registry = new StepRegistry();

    private SequenceManager() {}

    /** Build with all defaults except dispatcher and observer (no Client available in test). */
    public static SequenceManager withDefaults() {
        SequenceManager m = new SequenceManager();
        m.telemetry = new RingBufferTelemetry(2048);
        m.planner = new PriorityPlanner();
        m.blackboard = new ScopedBlackboard();
        // observer + dispatcher set by caller (Client-dependent)
        return m;
    }

    /** Override the engine entirely (e.g. tests use LinearEngine). Otherwise
     *  defaults wire up StateDrivenEngine when all subsystems are set. */
    public void setEngine(SequenceEngine e) { this.engine = e; }

    public void setDispatcher(InputDispatcher d) { this.dispatcher = d; rebuildEngineIfReady(); }
    public void setTelemetry(Telemetry t) { this.telemetry = t; rebuildEngineIfReady(); }
    public void setObserver(Observer o) { this.observer = o; rebuildEngineIfReady(); }
    public void setPlanner(Planner p) { this.planner = p; rebuildEngineIfReady(); }
    public void setBlackboard(Blackboard b) { this.blackboard = b; rebuildEngineIfReady(); }

    private void rebuildEngineIfReady() {
        if (engine != null) return;   // explicit setEngine takes precedence
        if (observer != null && dispatcher != null && planner != null
            && telemetry != null && blackboard != null) {
            engine = new StateDrivenEngine(observer, planner, dispatcher, telemetry, blackboard);
        }
    }

    public void run(Step root) { engine.start(root); }
    public void pause()  { engine.pause(); }
    public void resume() { engine.resume(); }
    public void stop()   { engine.stop(); }
    public SequenceState state() { return engine == null ? SequenceState.IDLE : engine.state(); }

    public void register(Step reactive)   { engine.registerReactive(reactive); }
    public void unregister(Step reactive) { engine.unregisterReactive(reactive); }

    /** Plugins forward RuneLite events here; the engine routes them on the next tick. */
    public void offerEvent(Object event) { if (engine != null) engine.offerEvent(event); }

    public void subscribe(Consumer<TelemetryRecord> listener)   { telemetry.subscribe(listener); }
    public void unsubscribe(Consumer<TelemetryRecord> listener) { telemetry.unsubscribe(listener); }
}
```

- [ ] **Step 4: Run test**

Run: `./gradlew :runelite-client:test --tests 'net.runelite.client.sequence.SequenceManagerTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/sequence/SequenceManager.java runelite-client/src/test/java/net/runelite/client/sequence/SequenceManagerTest.java
git commit -m "sequence: add SequenceManager facade"
```

---

## Phase 8 — UI Plugin

### Task 24: SequencerPlugin shell — registers and starts up

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/plugins/sequencer/SequencerPlugin.java`
- Create: `runelite-client/src/main/java/net/runelite/client/plugins/sequencer/SequencerConfig.java`

- [ ] **Step 1: Create SequencerConfig**

```java
package net.runelite.client.plugins.sequencer;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;

@ConfigGroup("sequencer")
public interface SequencerConfig extends Config {
    // intentionally empty — sequence definition is panel-driven, not config-driven
}
```

- [ ] **Step 2: Create SequencerPlugin shell (no panel yet)**

```java
package net.runelite.client.plugins.sequencer;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.events.GameTick;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.sequence.SequenceManager;
import net.runelite.client.sequence.activities.WalkStepFactory;
import net.runelite.client.sequence.dispatch.DirectInputDispatcher;
import net.runelite.client.sequence.internal.ClientObserver;

@Slf4j
@PluginDescriptor(
    name = "Sequencer",
    description = "State-driven sequence engine for OSRS workflows",
    tags = {"sequence", "automation", "engine"}
)
public class SequencerPlugin extends Plugin {

    @Inject private Client client;
    @Inject private ClientThread clientThread;
    @Inject private SequencerConfig config;

    private SequenceManager manager;

    @Provides SequencerConfig provideConfig(ConfigManager cm) { return cm.getConfig(SequencerConfig.class); }

    @Override
    protected void startUp() {
        manager = SequenceManager.withDefaults();
        manager.setObserver(new ClientObserver(client));
        manager.setDispatcher(new DirectInputDispatcher(client));
        manager.getRegistry().register(new WalkStepFactory());
        log.info("Sequencer plugin started");
    }

    @Override
    protected void shutDown() {
        if (manager != null) manager.stop();
        manager = null;
    }

    @Subscribe
    public void onGameTick(GameTick event) {
        if (manager != null && manager.getEngine() != null) {
            manager.getEngine().advanceTick();
        }
    }

    // Forward a curated set of RuneLite events to the engine. Steps that care
    // about specific event types narrow via instanceof in their onEvent.

    @Subscribe public void onItemContainerChanged(net.runelite.api.events.ItemContainerChanged e) { offer(e); }
    @Subscribe public void onAnimationChanged(net.runelite.api.events.AnimationChanged e)         { offer(e); }
    @Subscribe public void onChatMessage(net.runelite.api.events.ChatMessage e)                   { offer(e); }
    @Subscribe public void onMenuOptionClicked(net.runelite.api.events.MenuOptionClicked e)       { offer(e); }
    @Subscribe public void onGameStateChanged(net.runelite.api.events.GameStateChanged e)         { offer(e); }

    private void offer(Object event) { if (manager != null) manager.offerEvent(event); }

    public SequenceManager manager() { return manager; }
}
```

- [ ] **Step 3: Verify compile + plugin discovery**

Run: `./gradlew :runelite-client:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/sequencer/{SequencerPlugin,SequencerConfig}.java
git commit -m "sequencer: add plugin shell that wires SequenceManager on startup"
```

---

### Task 25: SequencerPanel — status header, controls, sequence list

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/plugins/sequencer/SequencerPanel.java`
- Modify: `SequencerPlugin.java` — register the panel as a NavigationButton

- [ ] **Step 1: Create the panel**

```java
package net.runelite.client.plugins.sequencer;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.sequence.SequenceManager;
import net.runelite.client.sequence.SequenceState;
import net.runelite.client.sequence.Step;
import net.runelite.client.sequence.activities.StepFactory;
import net.runelite.client.sequence.activities.StepParam;
import net.runelite.client.sequence.composite.LinearSequence;
import net.runelite.client.sequence.telemetry.TelemetryRecord;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public final class SequencerPanel extends PluginPanel {

    private final SequenceManager manager;

    private final JLabel stateLabel = new JLabel("State: IDLE");
    private final JLabel activeLabel = new JLabel("Active: —");
    private final DefaultListModel<StepRow> stepListModel = new DefaultListModel<>();
    private final JList<StepRow> stepList = new JList<>(stepListModel);
    private final DefaultListModel<String> logModel = new DefaultListModel<>();
    private final JList<String> logList = new JList<>(logModel);

    private final DefaultListModel<StepRow> reactiveListModel = new DefaultListModel<>();
    private final JList<StepRow> reactiveList = new JList<>(reactiveListModel);

    private final JButton runBtn = new JButton("Run");
    private final JButton pauseBtn = new JButton("Pause");
    private final JButton stopBtn = new JButton("Stop");
    private final JButton addBtn = new JButton("+ Add step");
    private final JButton addReactiveBtn = new JButton("+ Add reactive");

    public SequencerPanel(SequenceManager manager) {
        super(false);
        this.manager = manager;
        setBackground(ColorScheme.DARK_GRAY_COLOR);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));

        add(buildStatusHeader());
        add(Box.createVerticalStrut(6));
        add(buildSequenceSection());
        add(Box.createVerticalStrut(6));
        add(buildReactiveSection());
        add(Box.createVerticalStrut(6));
        add(buildControls());
        add(Box.createVerticalStrut(6));
        add(buildLog());

        runBtn.addActionListener(e -> onRun());
        pauseBtn.addActionListener(e -> onPauseToggle());
        stopBtn.addActionListener(e -> onStop());
        addBtn.addActionListener(e -> onAddStep(stepListModel, false));
        addReactiveBtn.addActionListener(e -> onAddStep(reactiveListModel, true));

        manager.subscribe(this::onTelemetry);

        Timer t = new Timer(500, e -> refreshStatus());
        t.start();
    }

    private JComponent buildStatusHeader() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createTitledBorder("Status"));
        p.add(stateLabel);
        p.add(activeLabel);
        return p;
    }

    private JComponent buildSequenceSection() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createTitledBorder("Sequence"));
        stepList.setVisibleRowCount(6);
        p.add(new JScrollPane(stepList), BorderLayout.CENTER);
        p.add(addBtn, BorderLayout.SOUTH);
        return p;
    }

    private JComponent buildReactiveSection() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createTitledBorder("Reactive"));
        reactiveList.setVisibleRowCount(3);
        p.add(new JScrollPane(reactiveList), BorderLayout.CENTER);
        p.add(addReactiveBtn, BorderLayout.SOUTH);
        return p;
    }

    private JComponent buildControls() {
        JPanel p = new JPanel();
        p.add(runBtn);
        p.add(pauseBtn);
        p.add(stopBtn);
        return p;
    }

    private JComponent buildLog() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createTitledBorder("Telemetry"));
        logList.setVisibleRowCount(8);
        p.add(new JScrollPane(logList), BorderLayout.CENTER);
        return p;
    }

    private void onAddStep(DefaultListModel<StepRow> targetModel, boolean reactive) {
        List<StepFactory> factories = manager.getRegistry().all();
        if (factories.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No step factories registered.");
            return;
        }
        Object choice = JOptionPane.showInputDialog(
            this, "Pick a step type", "Add step",
            JOptionPane.PLAIN_MESSAGE, null,
            factories.stream().map(StepFactory::displayName).toArray(),
            factories.get(0).displayName());
        if (choice == null) return;
        StepFactory chosen = factories.stream()
            .filter(f -> f.displayName().equals(choice)).findFirst().orElseThrow();

        Map<String, Object> args = collectParamsViaForm(chosen);
        if (args == null) return;   // cancelled
        Step s = chosen.build(args);
        targetModel.addElement(new StepRow(s));
        if (reactive) manager.register(s);
    }

    /** Builds a single dialog with one input row per StepParam. Returns null on cancel. */
    private Map<String, Object> collectParamsViaForm(StepFactory factory) {
        List<StepParam> params = factory.params();
        if (params.isEmpty()) return new HashMap<>();
        JPanel form = new JPanel(new GridLayout(params.size(), 2, 4, 4));
        List<JTextField> fields = new java.util.ArrayList<>();
        for (StepParam p : params) {
            form.add(new JLabel(p.name() + " (" + p.type() + "):"));
            JTextField tf = new JTextField(String.valueOf(p.defaultValue()), 16);
            form.add(tf);
            fields.add(tf);
        }
        int result = JOptionPane.showConfirmDialog(this, form,
            "Configure " + factory.displayName(), JOptionPane.OK_CANCEL_OPTION);
        if (result != JOptionPane.OK_OPTION) return null;
        Map<String, Object> args = new HashMap<>();
        for (int i = 0; i < params.size(); i++) {
            args.put(params.get(i).name(), parseParam(params.get(i), fields.get(i).getText()));
        }
        return args;
    }

    private static Object parseParam(StepParam p, String raw) {
        return switch (p.type()) {
            case INT, ITEM_ID -> Integer.parseInt(raw.trim());
            case BOOLEAN -> Boolean.parseBoolean(raw.trim());
            case WORLD_POINT -> {
                String[] parts = raw.split(",");
                yield new net.runelite.api.coords.WorldPoint(
                    Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()),
                    parts.length > 2 ? Integer.parseInt(parts[2].trim()) : 0);
            }
            default -> raw;
        };
    }

    private void onRun() {
        if (stepListModel.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Add at least one step.");
            return;
        }
        LinearSequence seq = new LinearSequence("user");
        for (int i = 0; i < stepListModel.size(); i++) {
            seq.then(stepListModel.get(i).step());
        }
        manager.run(seq);
    }

    private void onPauseToggle() {
        if (manager.state() == SequenceState.PAUSED) {
            manager.resume();
            pauseBtn.setText("Pause");
        } else if (manager.state() == SequenceState.RUNNING) {
            manager.pause();
            pauseBtn.setText("Resume");
        }
    }

    private void onStop() { manager.stop(); pauseBtn.setText("Pause"); }

    private void refreshStatus() {
        SequenceState s = manager.state();
        stateLabel.setText("State: " + s);
    }

    private void onTelemetry(TelemetryRecord r) {
        SwingUtilities.invokeLater(() -> {
            logModel.add(0, String.format("[%d] %s %s %s", r.tick(), r.event(), r.stepName(), r.payload()));
            if (logModel.size() > 200) logModel.remove(logModel.size() - 1);
        });
    }

    private record StepRow(Step step) {
        @Override public String toString() { return step.name(); }
    }
}
```

- [ ] **Step 2: Wire panel into SequencerPlugin**

Edit `SequencerPlugin.java`:

Add fields:

```java
@Inject private net.runelite.client.ui.ClientToolbar clientToolbar;
private SequencerPanel panel;
private net.runelite.client.ui.NavigationButton navButton;
```

Append to `startUp()`:

```java
panel = new SequencerPanel(manager);
javax.swing.ImageIcon icon = new javax.swing.ImageIcon(
    net.runelite.client.util.ImageUtil.loadImageResource(getClass(), "/util/eye.png"));
navButton = net.runelite.client.ui.NavigationButton.builder()
    .tooltip("Sequencer")
    .icon(icon.getImage() instanceof java.awt.image.BufferedImage bi ? bi
          : net.runelite.client.util.ImageUtil.bufferedImageFromImage(icon.getImage()))
    .priority(7)
    .panel(panel)
    .build();
clientToolbar.addNavigation(navButton);
```

(If `eye.png` doesn't exist at that path, copy any 16x16 PNG from `runelite-client/src/main/resources/util/` and reference it. The tests don't require an icon — just the panel.)

Append to `shutDown()`:

```java
if (navButton != null) clientToolbar.removeNavigation(navButton);
panel = null;
navButton = null;
```

- [ ] **Step 3: Verify compile**

Run: `./gradlew :runelite-client:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/sequencer/
git commit -m "sequencer: add side panel with step picker, run/stop, and telemetry log"
```

---

## Phase 9 — Integration & Acceptance

### Task 26: Migrate WalkerPlugin to use SequenceManager

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/walker/WalkerPlugin.java`

- [ ] **Step 1: Add @PluginDependency and @Inject the SequencerPlugin**

Edit `WalkerPlugin.java`:

Add the dependency annotation directly above `@PluginDescriptor` (RuneLite's plugin loader populates dependencies via the Guice injector when this annotation is present):

```java
@PluginDependency(net.runelite.client.plugins.sequencer.SequencerPlugin.class)
@PluginDescriptor(
    name = "Walker",
    description = "Walk to configured world coordinates via hotkey",
    tags = {"walk", "coordinates", "movement"}
)
public class WalkerPlugin extends Plugin {
    // ...
    @Inject private net.runelite.client.plugins.sequencer.SequencerPlugin sequencerPlugin;
    // ...
}
```

- [ ] **Step 2: Replace direct menuAction call with engine call**

Replace the `walkToTarget()` method:

```java
private void walkToTarget() {
    if (client.getGameState() != GameState.LOGGED_IN) return;
    var manager = sequencerPlugin.manager();
    if (manager == null) return;
    if (manager.state() != net.runelite.client.sequence.SequenceState.IDLE) return;
    WorldPoint target = new WorldPoint(config.targetX(), config.targetY(), config.targetZ());
    manager.run(new net.runelite.client.sequence.composite.LinearSequence("walker-hotkey")
        .then(new net.runelite.client.sequence.activities.WalkStep(target)));
}
```

- [ ] **Step 3: Verify compile**

Run: `./gradlew :runelite-client:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/walker/WalkerPlugin.java
git commit -m "walker: route hotkey through SequenceManager instead of direct menuAction"
```

---

### Task 27: End-to-end acceptance test

**Files:**
- Create: `runelite-client/src/test/java/net/runelite/client/sequence/AcceptanceTest.java`

This test verifies §21 acceptance criteria 3, 4, and 5 in code (criterion 1 is "compiles" — covered; criterion 2 is plugin discovery — manual or RuneLite test runner).

- [ ] **Step 1: Write the test**

```java
package net.runelite.client.sequence;

import net.runelite.api.coords.WorldPoint;
import net.runelite.client.sequence.activities.WalkStep;
import net.runelite.client.sequence.composite.LinearSequence;
import net.runelite.client.sequence.dispatch.MockInputDispatcher;
import net.runelite.client.sequence.internal.FixtureObserver;
import net.runelite.client.sequence.telemetry.TelemetryRecord;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class AcceptanceTest {

    @Test
    public void buildAndRunLinearSequenceWalkStep_observesExpectedTelemetry() {
        WorldPoint target = new WorldPoint(3208, 3219, 0);
        FixtureObserver obs = new FixtureObserver(List.of(
            snap(new WorldPoint(3000, 3000, 0)),
            snap(new WorldPoint(3100, 3100, 0)),
            snap(target)));
        MockInputDispatcher mock = new MockInputDispatcher();

        SequenceManager mgr = SequenceManager.withDefaults();
        mgr.setObserver(obs);
        mgr.setDispatcher(mock);

        // Acceptance #3: build LinearSequence(WalkStep), run, walk happens
        mgr.run(new LinearSequence("user").then(new WalkStep(target)));

        // Acceptance #5: dispatcher swap captures requests
        mgr.getEngine().advanceTick();
        mgr.getEngine().advanceTick();
        mgr.getEngine().advanceTick();

        assertFalse("dispatcher should have received walk requests", mock.getRequests().isEmpty());

        // Acceptance #4: telemetry shows SELECTED -> STARTED -> CHECK -> SUCCEEDED
        List<TelemetryRecord.Event> events = mgr.getTelemetry().tail(64).stream()
            .map(TelemetryRecord::event).toList();
        int selected = events.indexOf(TelemetryRecord.Event.SELECTED);
        int started  = events.indexOf(TelemetryRecord.Event.STARTED);
        int check    = events.indexOf(TelemetryRecord.Event.CHECK);
        int succeed  = events.indexOf(TelemetryRecord.Event.SUCCEEDED);
        assertTrue("SELECTED present",  selected >= 0);
        assertTrue("STARTED after SELECTED", started > selected);
        assertTrue("CHECK after STARTED", check > started);
        assertTrue("SUCCEEDED after CHECK", succeed > check);
    }

    private static WorldSnapshot snap(WorldPoint p) {
        return new WorldSnapshot() {
            public int tick() { return 0; }
            public PlayerView player() {
                return new PlayerView() {
                    public WorldPoint worldLocation() { return p; }
                    public int animation() { return -1; }
                    public boolean isIdle() { return true; }
                    public int health() { return 99; }
                    public int maxHealth() { return 99; }
                };
            }
        };
    }
}
```

- [ ] **Step 2: Run all sequence tests**

Run: `./gradlew :runelite-client:test --tests 'net.runelite.client.sequence.*'`
Expected: PASS — all sequence tests green.

- [ ] **Step 3: Run full test suite to confirm no regressions**

Run: `./gradlew :runelite-client:test`
Expected: BUILD SUCCESSFUL — all existing tests still pass.

- [ ] **Step 4: Commit**

```bash
git add runelite-client/src/test/java/net/runelite/client/sequence/AcceptanceTest.java
git commit -m "sequence: add end-to-end acceptance test for §21 criteria 3-5"
```

---

## Notes for the implementer

- **Java 17 syntax check.** This plan uses records, sealed interfaces, and pattern-matching switches. If `./gradlew :runelite-client:compileJava` fails on a sealed/record line, check that `org.gradle.jvm.toolchain` resolves to JDK 17 — this should already be true in the rlb fork. If not, fall back to Lombok `@Value` classes with a private constructor (sealed → abstract class with package-private subclasses).
- **PMD failures.** RuneLite enforces PMD; you may see warnings about unused imports or empty methods. Fix as flagged or disable per-file with `// NOPMD`.
- **License headers.** Every new `.java` file needs the standard 2-clause BSD header. Copy from any existing file in the same package.
- **Testing pace.** Each task is roughly 10–25 minutes of work including TDD. Phase 1–4 should fly. Phase 5 (composites) is where engine wiring gets fiddly — review carefully.
- **ParallelGroup.** Skipped for v1 by Task 20's note. Add as a follow-up plan once the rest is shipped.

## Self-review

Spec coverage:

| Spec § | Plan task(s) |
|---|---|
| §1 Purpose | overall structure |
| §2 Scope | `Verifier` absent ✓; ParallelGroup deferred (Task 20 note) |
| §3 Principles (interfaces, facade) | Task 23 setEngine accepts `SequenceEngine` interface |
| §4 Package layout | Tasks 1–22 build it |
| §5 Step contract + Actions API + StepContext | Tasks 6, 9 |
| §6 Sealed types + Recovery semantics table | Task 2 + Task 16 (`applyRecovery` + `popAndOrchestrate`) |
| §7 Engine loop ordering | Task 16 — events → check/pop → orchestrate → planner+preempt → tick → drain |
| §8 Composites (LinearSequence/Selector/RepeatStep) | Tasks 17–19; ParallelGroup deferred |
| §9 Frames + composite-specific frame subclasses | Task 10 + Tasks 17–19 |
| §10 Blackboard + scope cleanup on pop | Task 3 + Task 16 (`popAndOrchestrate` clears STEP, root-pop clears SEQUENCE, stop clears RUN) |
| §11 Action budget + arbitration | Tasks 5, 14 |
| §12 InputDispatcher seam + InputMode | Tasks 7, 8 |
| §13 Subsystem swap surface | Task 23 (Manager wiring; `Verifier` correctly absent) |
| §14 Telemetry + EVENT/PREEMPTED/RESUMED records | Tasks 11 + Task 16 (engine emits all event types) |
| §15 SequenceManager (full API) | Task 23 — `setEngine` takes interface, `offerEvent` passthrough, reactive register/unregister |
| §16 WalkStep + waypoint picker | Task 22 — `pickReachableWaypointToward` implemented |
| §17 Panel + StepRegistry + reactive list + pause | Tasks 21, 24, 25 |
| §18 Lifecycle/threading | Task 24 (plugin forwards GameTick + curated events) |
| §19 Testing (`LinearEngine` + `MockInputDispatcher` + `FixtureObserver`) | Tasks 8, 12 + `SequenceEngine.advanceTicks` default method (no separate LinearEngine class) |
| §20 Future work | explicitly out of scope |
| §21 Acceptance criteria | Task 27 covers 3, 4, 5 |

Engine loop semantics (§5 + §7):
- `tick()` only on leaves — `CompositeStep.tick()` is `final` no-op; engine iterates `frames.leaves()`
- `check()` is passive — only reads state; mutation goes through `ctx.actions()`
- `onStart()` deferred to first `advanceTick` so a real snapshot is available
- Step exceptions caught in `tick`/`onStart`/`onEvent`/`check` — produce synthetic `Failed`/`Abort`
- Preemption with suspension/resumption (PREEMPTED/RESUMED telemetry)
- Recovery propagation: `Skip`/`Retry`-exhausted invoke `popAndOrchestrate` so parent composites advance correctly
- `JumpToAnchor` resolved by walking outward through frames (Task 17)
- Reactive eligibility excludes steps already on the frame stack (`eligibleReactives()`)

Lombok safety:
- `StepFrame` and composite-frame subclasses have `@Setter` only on mutable fields, never at class level
- `@Value` on `ActionRequest` + `@Builder(toBuilder = true)` for ergonomic construction

Type consistency confirmed: `Completion`, `Recovery`, `Failure`, `StepFrame`, `Actions`, `SequenceEngine`, `CompositeStep.NextAction` signatures unchanged after their defining tasks.

No `TBD` placeholders. All code blocks contain working code or marked extension points (`[X branch added in Task Y]`).
