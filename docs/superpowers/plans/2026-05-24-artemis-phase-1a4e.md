# Artemis Phase 1A.4e — `SequencePlanBuilderImpl` + `ArtemisImpl.plan(String)`

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **One final commit at the end** matches the existing Phase 1A.4 sub-slice convention (1A.4a/b/c/d each landed as a single commit); do not commit per-task.

**Goal:** Land the only remaining 1A.4 stub — the composition wrapper that turns `artemis.plan("name").then(stepA).then(stepB).root()` into a runnable `LinearSequence`.

**Architecture:** A small mutable builder under `sequence/composite/` that owns an `ArrayList<Step>`, validates inputs at the boundary, and produces a fresh `LinearSequence` snapshot on every `root()` call. `ArtemisImpl.plan(name)` returns one of these. No threading. No Navigator. No worker. No StepEvent emission from the builder itself — the composed Steps already emit their own lifecycle events.

**Tech Stack:** Java 17, JUnit 4 + Mockito (existing test infra), Lombok (already in use by `LinearSequence`).

**Out of scope (mirrors operator's boundary):** scripts, migration, CowKiller pilot, smoke test execution, PixelResolver, bank/GE, grep gate (Phase 1B), dashboard, StepEvent changes, walk/idle/logout changes, NamedZone tile changes.

---

## Pre-implementation findings (LinearSequence audit)

Already verified against `runelite-client/src/main/java/net/runelite/client/sequence/composite/LinearSequence.java`:

| Question | Answer |
|---|---|
| Does it accept `List<Step>`? | Yes — `LinearSequence(String name, List<Step> steps)` at `LinearSequence.java:46-50`. |
| Does it copy the list defensively? | Yes — `if (steps != null) children.addAll(steps);` (`:49`). The constructor's internal `children` field is a private `ArrayList<>`. The caller's list reference is not retained. |
| Does it allow empty children? | Yes. `onChildPopped(...)` returns `FinishWithSuccess("all children done")` when `currentChildIndex >= children.size()` (`:73-75`); engine sees the sequence as immediately done. `priority()` returns 0 in that case (`:59`). `LinearSequenceListConstructorTest.emptyListYieldsNoChildren` covers it. |
| Preserves insertion order? | Yes — `ArrayList` + `addAll(steps)` preserves iteration order; `LinearSequenceListConstructorTest.listConstructorPreservesOrderAndContents` proves it. |
| Failure semantics match spec §12.5? | Yes — `onChildPopped` returns `FinishWithFailure` on `Completion.Failed` (`:72`). No implicit retry; engine drives `Recovery` per §12.6. |
| Exposes child list for tests? | Yes — Lombok `@Getter` on the class exposes `getChildren()` returning the live `List<Step>` field (`:36`). Tests already use this. |

**Implication:** The builder can construct a `LinearSequence` via the list-constructor and rely on it for the defensive snapshot — but we will *also* defensive-copy on our side (pass `new ArrayList<>(internalList)`) so the contract is explicit at the builder layer and doesn't depend on `LinearSequence`'s internal copy behaviour.

---

## Design decisions (lock these before coding)

1. **Package:** `net.runelite.client.sequence.composite` — sibling to `LinearSequence`, matches the existing `SequencePlanBuilder` interface location.
2. **Mutability:** mutable builder. `then(...)` returns `this`. Each `root()` call returns a fresh `LinearSequence` snapshot of current state — multiple calls are independent, and post-root `then(...)` does not mutate already-returned sequences.
3. **`then(Step)` null policy:** `Objects.requireNonNull(step, "step")`. Throws `NullPointerException` with a clear message. No `IllegalArgumentException` — `requireNonNull` is the conventional Java idiom and produces a precise message.
4. **Constructor null/blank-name policy:** `Objects.requireNonNull(name, "name")` for null. `IllegalArgumentException("plan name must not be blank")` for `name.trim().isEmpty()`. Fail at construction, not lazily at `root()` — caller finds out immediately.
5. **`root()` snapshot policy:** every call constructs a new `LinearSequence(name, new ArrayList<>(steps))`. The builder's own list is untouched. Tests verify mutation after `root()` doesn't leak.
6. **Recording:** none from the builder. The composed `LinearSequence` and its child Steps record on their own. Builder is pure composition.
7. **No retry / no recovery:** builder layer does nothing custom. Engine's `LinearSequence.onChildPopped` handles failure propagation per §12.5.
8. **Return type of `root()`:** declared `Step` (matching interface), concrete type `LinearSequence` (tests downcast for assertions). Document that the concrete type is `LinearSequence` so callers know what `SequenceManager.run(...)` will see.

---

## File Structure

**Create:**
- `runelite-client/src/main/java/net/runelite/client/sequence/composite/SequencePlanBuilderImpl.java` (~60 lines, no BSD header per `feedback_no_bsd_headers`)
- `runelite-client/src/test/java/net/runelite/client/sequence/composite/SequencePlanBuilderImplTest.java` (~150 lines)

**Modify:**
- `runelite-client/src/main/java/net/runelite/client/sequence/artemis/ArtemisImpl.java`
  - Replace `plan(String name)` body (`:500-508`) with `return new SequencePlanBuilderImpl(name);`
  - Delete `notInPhase1A4dBuilder` helper (`:510-516`)
  - Update Javadoc at `:66-83` to drop the "Only `plan(...)` still throws" wording
- `runelite-client/src/main/java/net/runelite/client/sequence/artemis/Artemis.java`
  - Update interface Javadoc at `:33-36` to drop "plan(...) lands in 1A.4e" — it's landed now
- `runelite-client/src/test/java/net/runelite/client/sequence/artemis/ArtemisImplReadsTest.java` (or a new tiny test file) — add ONE wiring test that `artemis.plan("x")` returns a `SequencePlanBuilderImpl` instance and that the returned builder's `.then(stub).root()` is a `LinearSequence` named `"x"` with one child

---

## Task 1: Build `SequencePlanBuilderImpl` happy path

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/sequence/composite/SequencePlanBuilderImpl.java`
- Test: `runelite-client/src/test/java/net/runelite/client/sequence/composite/SequencePlanBuilderImplTest.java`

- [ ] **Step 1: Write the failing happy-path tests**

```java
// runelite-client/src/test/java/net/runelite/client/sequence/composite/SequencePlanBuilderImplTest.java
package net.runelite.client.sequence.composite;

import net.runelite.client.sequence.*;
import net.runelite.client.sequence.blackboard.Blackboard;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class SequencePlanBuilderImplTest
{
    @Test
    public void thenAppendsInInsertionOrder()
    {
        Step a = stubStep("A");
        Step b = stubStep("B");
        Step c = stubStep("C");

        SequencePlanBuilderImpl builder = new SequencePlanBuilderImpl("plan-x");
        builder.then(a).then(b).then(c);

        LinearSequence root = (LinearSequence) builder.root();
        List<Step> children = root.getChildren();
        assertEquals(3, children.size());
        assertSame(a, children.get(0));
        assertSame(b, children.get(1));
        assertSame(c, children.get(2));
    }

    @Test
    public void thenReturnsSameBuilderForFluentChaining()
    {
        SequencePlanBuilderImpl builder = new SequencePlanBuilderImpl("plan-x");
        SequencePlanBuilder afterThen = builder.then(stubStep("A"));
        assertSame(builder, afterThen);
    }

    @Test
    public void rootReturnsLinearSequenceWithPreservedName()
    {
        SequencePlanBuilderImpl builder = new SequencePlanBuilderImpl("my-plan");
        builder.then(stubStep("only"));

        Step root = builder.root();
        assertTrue("root() must return a LinearSequence", root instanceof LinearSequence);
        assertEquals("my-plan", ((LinearSequence) root).name());
    }

    @Test
    public void zeroStepPlanReturnsEmptyLinearSequence()
    {
        SequencePlanBuilderImpl builder = new SequencePlanBuilderImpl("empty-plan");

        LinearSequence root = (LinearSequence) builder.root();
        assertEquals("empty-plan", root.name());
        assertTrue("empty builder must produce an empty LinearSequence", root.getChildren().isEmpty());
    }

    private static Step stubStep(String name)
    {
        return new Step()
        {
            public String name() { return name; }
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
        };
    }
}
```

- [ ] **Step 2: Run tests to verify they fail (class does not exist)**

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :client:test --tests \
  net.runelite.client.sequence.composite.SequencePlanBuilderImplTest
```

Expected: compile error / `cannot find symbol class SequencePlanBuilderImpl`.

- [ ] **Step 3: Write minimal `SequencePlanBuilderImpl` to pass these tests**

```java
// runelite-client/src/main/java/net/runelite/client/sequence/composite/SequencePlanBuilderImpl.java
// NO BSD header — new file in our private fork (per feedback_no_bsd_headers).
package net.runelite.client.sequence.composite;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.runelite.client.sequence.Step;

/**
 * Mutable builder for {@code Artemis.plan(name).then(...).root()} chains
 * (Phase 1A.4e). Owns an internal {@link ArrayList} of {@link Step};
 * each {@link #root()} call snapshots the current state into a fresh
 * {@link LinearSequence} so subsequent {@link #then(Step)} calls cannot
 * leak into an already-returned sequence.
 *
 * <p>Validation is at the boundary: {@code null}/blank plan name fails
 * at construction; {@code null} Step in {@code then(...)} throws
 * immediately. The engine's {@link LinearSequence} contract is unchanged
 * — failure short-circuits per spec §12.5, retries are explicit per
 * §12.6, all driven by the engine, not this builder.
 */
public final class SequencePlanBuilderImpl implements SequencePlanBuilder
{
    private final String name;
    private final List<Step> steps = new ArrayList<>();

    public SequencePlanBuilderImpl(String name)
    {
        // Validation lands in Task 3 — leave both checks for now so
        // happy-path tests can pass without dragging the validation
        // tests in early.
        this.name = name;
    }

    @Override
    public SequencePlanBuilder then(Step step)
    {
        // Null check lands in Task 2.
        steps.add(step);
        return this;
    }

    @Override
    public Step root()
    {
        // Snapshot via fresh ArrayList — subsequent then(...) calls
        // mutate the builder's list, not the returned sequence's list.
        return new LinearSequence(name, new ArrayList<>(steps));
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :client:test --tests \
  net.runelite.client.sequence.composite.SequencePlanBuilderImplTest
```

Expected: 4 tests pass (`thenAppendsInInsertionOrder`, `thenReturnsSameBuilderForFluentChaining`, `rootReturnsLinearSequenceWithPreservedName`, `zeroStepPlanReturnsEmptyLinearSequence`).

---

## Task 2: Null-Step validation

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/sequence/composite/SequencePlanBuilderImpl.java`
- Test: `runelite-client/src/test/java/net/runelite/client/sequence/composite/SequencePlanBuilderImplTest.java`

- [ ] **Step 1: Append failing test**

```java
@Test(expected = NullPointerException.class)
public void thenNullStepFailsLoud()
{
    new SequencePlanBuilderImpl("plan-x").then(null);
}

@Test
public void thenNullStepMessageMentionsStep()
{
    try
    {
        new SequencePlanBuilderImpl("plan-x").then(null);
        fail("expected NullPointerException");
    }
    catch (NullPointerException e)
    {
        assertNotNull("NPE must carry a message", e.getMessage());
        assertTrue("message must mention 'step', got: " + e.getMessage(),
            e.getMessage().toLowerCase().contains("step"));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Expected: the first one passes (null `add` on `ArrayList` accepts null silently → no NPE → test fails as `Did not throw`). Actually `ArrayList.add(null)` does NOT throw — so both tests fail until we add the guard.

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :client:test --tests \
  net.runelite.client.sequence.composite.SequencePlanBuilderImplTest
```

Expected output: 2 failures (`thenNullStepFailsLoud`, `thenNullStepMessageMentionsStep`).

- [ ] **Step 3: Add `requireNonNull` guard in `then(...)`**

```java
@Override
public SequencePlanBuilder then(Step step)
{
    Objects.requireNonNull(step, "step");
    steps.add(step);
    return this;
}
```

- [ ] **Step 4: Run tests to verify they pass**

Expected: all 6 builder tests now pass.

---

## Task 3: Null/blank-name validation

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/sequence/composite/SequencePlanBuilderImpl.java`
- Test: `runelite-client/src/test/java/net/runelite/client/sequence/composite/SequencePlanBuilderImplTest.java`

- [ ] **Step 1: Append failing tests**

```java
@Test(expected = NullPointerException.class)
public void nullNameFailsLoud()
{
    new SequencePlanBuilderImpl(null);
}

@Test
public void blankNameFailsLoud()
{
    String[] blanks = { "", "   ", "\t", "\n" };
    for (String blank : blanks)
    {
        try
        {
            new SequencePlanBuilderImpl(blank);
            fail("expected IllegalArgumentException for blank name: [" + blank + "]");
        }
        catch (IllegalArgumentException expected)
        {
            assertTrue("message must mention 'name', got: " + expected.getMessage(),
                expected.getMessage().toLowerCase().contains("name"));
        }
    }
}

@Test
public void nameIsPreservedExactlyAsGiven()
{
    SequencePlanBuilderImpl builder = new SequencePlanBuilderImpl("  edges  ");
    // Builder preserves the caller's name verbatim — no trim, no rewrite.
    // Validation is "is the name blank after trim", not "rewrite the name".
    LinearSequence root = (LinearSequence) builder.root();
    assertEquals("  edges  ", root.name());
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :client:test --tests \
  net.runelite.client.sequence.composite.SequencePlanBuilderImplTest
```

Expected: `nullNameFailsLoud` fails (no NPE thrown — current ctor stores null silently); `blankNameFailsLoud` fails (no exception thrown for blank). `nameIsPreservedExactlyAsGiven` passes (current ctor preserves verbatim).

- [ ] **Step 3: Add name validation in constructor**

```java
public SequencePlanBuilderImpl(String name)
{
    Objects.requireNonNull(name, "name");
    if (name.trim().isEmpty())
    {
        throw new IllegalArgumentException("plan name must not be blank");
    }
    this.name = name;
}
```

- [ ] **Step 4: Run tests to verify they pass**

Expected: all 9 builder tests pass.

---

## Task 4: Defensive-snapshot guarantee for `root()`

**Files:**
- Test only: `runelite-client/src/test/java/net/runelite/client/sequence/composite/SequencePlanBuilderImplTest.java`
- No production change expected — Task 1's implementation already does `new ArrayList<>(steps)`. Task 4 *proves* it via tests.

- [ ] **Step 1: Append failing-or-passing snapshot tests**

```java
@Test
public void rootSnapshotsDoesNotLeakLaterThenCalls()
{
    Step a = stubStep("A");
    Step b = stubStep("B");

    SequencePlanBuilderImpl builder = new SequencePlanBuilderImpl("plan-x");
    builder.then(a);
    LinearSequence root1 = (LinearSequence) builder.root();

    // Mutate the builder after root1 was taken.
    builder.then(b);

    // root1 must NOT see stepB.
    assertEquals("root1 children size", 1, root1.getChildren().size());
    assertSame(a, root1.getChildren().get(0));

    // A fresh root2 SHOULD include both steps.
    LinearSequence root2 = (LinearSequence) builder.root();
    assertEquals(2, root2.getChildren().size());
    assertSame(a, root2.getChildren().get(0));
    assertSame(b, root2.getChildren().get(1));
}

@Test
public void multipleRootCallsReturnIndependentInstances()
{
    SequencePlanBuilderImpl builder = new SequencePlanBuilderImpl("plan-x");
    builder.then(stubStep("A"));

    Step r1 = builder.root();
    Step r2 = builder.root();

    assertNotSame("each root() call must produce a fresh LinearSequence instance", r1, r2);
}
```

- [ ] **Step 2: Run tests**

Expected: both pass — Task 1's `new ArrayList<>(steps)` in `root()` already gives this guarantee. If a future hand modifies `root()` to share the list, these tests fail loud.

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :client:test --tests \
  net.runelite.client.sequence.composite.SequencePlanBuilderImplTest
```

- [ ] **Step 3: No production change** — verify the snapshot guarantee was wired correctly in Task 1.

If either test fails, fix `root()` to use `new ArrayList<>(steps)` and re-run.

---

## Task 5: Wire `ArtemisImpl.plan(String)` + update Javadoc

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/sequence/artemis/ArtemisImpl.java`
- Modify: `runelite-client/src/main/java/net/runelite/client/sequence/artemis/Artemis.java`
- Test: `runelite-client/src/test/java/net/runelite/client/sequence/artemis/ArtemisImplReadsTest.java` (add ONE wiring test)

- [ ] **Step 1: Add wiring test to existing `ArtemisImplReadsTest`**

Append to `ArtemisImplReadsTest`:

```java
// ── plan(...) wiring (Phase 1A.4e) ──────────────────────────────

@Test
public void planReturnsBuilderThatProducesNamedLinearSequence()
{
    SequencePlanBuilder builder = artemis.plan("smoke-test-plan");
    assertNotNull(builder);
    assertTrue("plan(...) must return SequencePlanBuilderImpl",
        builder instanceof SequencePlanBuilderImpl);

    Step root = builder.root();
    assertTrue("root() must produce a LinearSequence", root instanceof LinearSequence);
    assertEquals("smoke-test-plan", ((LinearSequence) root).name());
}
```

Imports to add at the top of `ArtemisImplReadsTest`:
```java
import net.runelite.client.sequence.Step;
import net.runelite.client.sequence.composite.LinearSequence;
import net.runelite.client.sequence.composite.SequencePlanBuilder;
import net.runelite.client.sequence.composite.SequencePlanBuilderImpl;
import static org.junit.Assert.assertTrue;
```
(some may already be imported — only add what's missing).

- [ ] **Step 2: Run test to verify it fails**

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :client:test --tests \
  net.runelite.client.sequence.artemis.ArtemisImplReadsTest.planReturnsBuilderThatProducesNamedLinearSequence
```

Expected: fail with `UnsupportedOperationException: Artemis.plan(\"smoke-test-plan\") composition wires up in Phase 1A.4e...` — the current stub.

- [ ] **Step 3: Replace `ArtemisImpl.plan(...)` body and delete the stub helper**

Edit `ArtemisImpl.java`:

Replace lines `:500-508` (current `plan(...)` body):

```java
@Override
public SequencePlanBuilder plan(String name)
{
    return new SequencePlanBuilderImpl(name);
}
```

Delete the `notInPhase1A4dBuilder` helper at lines `:510-516`:

```java
private static SequencePlanBuilder notInPhase1A4dBuilder(String surface)
{
    throw new UnsupportedOperationException(
        "Artemis." + surface + " composition wires up in Phase 1A.4e — "
            + "Phase 1A.4d implemented walkTo(NamedZone). plan(...) is the only "
            + "remaining stub.");
}
```

Add import to `ArtemisImpl.java`:

```java
import net.runelite.client.sequence.composite.SequencePlanBuilderImpl;
```

Update the class Javadoc at `ArtemisImpl.java:66-83`. Replace:

```
 * Phase 1A.3 / 1A.4a / 1A.4b / 1A.4c / 1A.4d implementation of {@link Artemis}.
 * ...
 * Only {@code plan(...)} still throws {@link UnsupportedOperationException} — it lands in 1A.4e.
```

with:

```
 * Phase 1A.3 / 1A.4a / 1A.4b / 1A.4c / 1A.4d / 1A.4e implementation of {@link Artemis}.
 * ...
 * {@code plan(...)} returns {@link SequencePlanBuilderImpl} (Phase 1A.4e).
```

(keep the rest of the Javadoc — the description of read methods, Step subclasses, walk semantics, etc. is still accurate.)

- [ ] **Step 4: Update `Artemis.java` interface Javadoc**

Edit lines `:33-36`:

Replace:
```
 * <p>Phase 1A.1 — interface only. {@code ArtemisImpl} lands in Phase
 * 1A.2; Step subclasses in Phase 1A.3; walk/idle/logout wiring in
 * Phase 1A.4; grep gate in Phase 1B.
```

with:
```
 * <p>Phase 1A.1 — interface only. {@code ArtemisImpl} lands in Phase
 * 1A.2; Step subclasses in Phase 1A.3; walk/idle/logout wiring in
 * Phase 1A.4a-d; composition wrapper in Phase 1A.4e; grep gate in
 * Phase 1B.
```

- [ ] **Step 5: Run wiring test + full artemis test suite**

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :client:test --tests \
  "net.runelite.client.sequence.artemis.*" --tests \
  "net.runelite.client.sequence.composite.*" --tests \
  "net.runelite.client.sequence.activities.script.*"
```

Expected: all pass. The activities/script suite (87 tests from 1A.4d baseline) must still be green — Task 5's surface change only touches `plan(...)` and Javadoc, so the Step-subclass tests should be unaffected. Watch for unintentional import-order / formatting changes flagged by checkstyle if it runs.

---

## Task 6: Build + full-suite verification + single commit

- [ ] **Step 1: Run full client build**

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :client:compileJava
```

Expected: clean build.

- [ ] **Step 2: Run the full client test suite (or the narrowed subset that covers everything 1A.4e can break)**

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :client:test --tests \
  "net.runelite.client.sequence.*"
```

Expected: 141 prior tests + ~10 new builder tests + 1 new wiring test ≈ 152 passing, 0 failing. Exact count will differ slightly depending on test method counts; the absolute requirement is **0 failures, 0 errors**.

If anything outside `sequence/composite` or `sequence/artemis` breaks, stop — the change should not reach those packages. Diagnose before continuing.

- [ ] **Step 3: Verify git status — only the expected files changed**

```bash
git status
```

Expected modified/new:
- `runelite-client/src/main/java/net/runelite/client/sequence/composite/SequencePlanBuilderImpl.java` (new)
- `runelite-client/src/main/java/net/runelite/client/sequence/artemis/ArtemisImpl.java` (modified)
- `runelite-client/src/main/java/net/runelite/client/sequence/artemis/Artemis.java` (modified — Javadoc only)
- `runelite-client/src/test/java/net/runelite/client/sequence/composite/SequencePlanBuilderImplTest.java` (new)
- `runelite-client/src/test/java/net/runelite/client/sequence/artemis/ArtemisImplReadsTest.java` (modified — one wiring test added)

If anything else appears, stop and investigate before committing.

- [ ] **Step 4: Single commit matching the 1A.4 sub-slice convention**

```bash
git add \
  runelite-client/src/main/java/net/runelite/client/sequence/composite/SequencePlanBuilderImpl.java \
  runelite-client/src/main/java/net/runelite/client/sequence/artemis/ArtemisImpl.java \
  runelite-client/src/main/java/net/runelite/client/sequence/artemis/Artemis.java \
  runelite-client/src/test/java/net/runelite/client/sequence/composite/SequencePlanBuilderImplTest.java \
  runelite-client/src/test/java/net/runelite/client/sequence/artemis/ArtemisImplReadsTest.java

git commit -m "$(cat <<'EOF'
feat(artemis): Phase 1A.4e — SequencePlanBuilderImpl + plan(String) wiring

Lands the composition wrapper:
- new SequencePlanBuilderImpl under sequence/composite/ (mutable
  builder, then(Step) appends + returns this, root() snapshots into
  a fresh LinearSequence per call)
- ArtemisImpl.plan(name) returns SequencePlanBuilderImpl; stub
  helper notInPhase1A4dBuilder deleted
- null Step in then(...) throws NPE; null/blank plan name throws
  NPE/IAE at construction
- multiple root() calls produce independent LinearSequence snapshots
  (mutating the builder post-root does not leak into already-returned
  sequences)

Tests: 10 builder unit tests + 1 ArtemisImpl wiring test. All prior
sequence-engine + activities/script tests still pass.

Closes Phase 1A.4. Phase 1A.4e is the last 1A.4 sub-slice; pilot
script work (Phase 2) and grep gate (Phase 1B) follow per
docs/superpowers/specs/2026-05-23-artemis-design.md §19.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"

git status
git log -1 --format='%H%n%s'
```

Expected: clean commit, working tree clean, branch ahead of origin/master by 6 commits.

---

## Self-review checklist (run before stopping)

- [ ] Every spec/boundary requirement has a task — yes: builder design (Task 1), null Step (Task 2), null/blank name (Task 3), defensive snapshot (Task 4), Artemis wiring (Task 5), build/test/commit (Task 6).
- [ ] No placeholders ("TBD", "implement later", "add validation") — all steps include concrete code.
- [ ] Type / method names consistent — `SequencePlanBuilderImpl`, `then`, `root`, `name`, `getChildren` used identically across tasks.
- [ ] No out-of-scope files touched — only `SequencePlanBuilderImpl.java` (new), `ArtemisImpl.java`, `Artemis.java`, `SequencePlanBuilderImplTest.java` (new), `ArtemisImplReadsTest.java`. No Steps, no scripts, no NamedZone, no PixelResolver, no grep gate, no dashboard.
- [ ] Memory-constraints respected — no BSD header on new files (`feedback_no_bsd_headers`); no bot/evasion framing (`feedback_no_evasion_framing`); compact plan (~500 lines, within `feedback_compact_plans` budget); inline implementation + post-merge subagent QC if requested (`feedback_inline_impl_subagent_qc`).
- [ ] Operator's snapshot watchpoint covered — Task 4 has two tests that specifically prove the post-root-then issue and the multiple-root-instance issue.
- [ ] One final commit per the existing 1A.4 sub-slice convention — Task 6 step 4.

---

## Out-of-scope (carried forward, not addressed here)

- `workerFailureDetail` not yet reaching `StepEvent.detail` (CF-2 from 1A.4c → 1A.4d). Still pending. Phase 7 dashboard work is the consumer; defer until then.
- Smoke test of the 3-step plan (`walkTo(NamedZone.LUMBRIDGE_CASTLE_GROUND_FLOOR)` → `findNpc(...)` → `logout()`) per spec §19 Phase 1A exit criteria. Belongs in a separate slice after 1A.4e lands — IRL test on test-profile session, not unit-testable here.
- CowKiller pilot (Phase 2) — separate spec, separate plan.
- Grep gate (Phase 1B) — separate slice; lands before Phase 2.
