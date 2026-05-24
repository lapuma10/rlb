# Artemis Phase 1C — DynamicStep Read-to-Action Bridge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **Do NOT begin implementation until the operator approves this plan.**

**Goal:** Add **one** generic engine composite — `DynamicStep` — that materializes a child Step at execution time. Lets scripts compose `read(...) → produce a fresh Step` without manually driving Step lifecycle and without each script reinventing the bridge. Unblocks the Phase 2 cow pilot and every future read-then-act script.

**Architecture:** New composite alongside `LinearSequence` / `Selector` / `RepeatStep`. Engine learns three hardcoded branches for it (frame factory, first-child push, orchestration). Per-execution child state lives in `DynamicStepFrame`, **never** in the `DynamicStep` instance — same discipline `SequencePlanBuilderImpl` already enforces by rejecting same-instance Step re-adds (`SequencePlanBuilderImpl.java:49-56`).

**Tech Stack:** Java 17, existing sequence engine. No new dispatcher / Artemis surface changes. No script-facing import policy changes.

**Scope (in):** `DynamicStep`, `DynamicStepFrame`, `FailStep` (small leaf utility), three engine integration branches, complete test coverage.

**Scope (out):** `Function<WorldSnapshot, Step>` overload (Supplier-only in v1; rationale below). `SucceedStep` (YAGNI — no current caller). Snapshot-aware factory variants (defer until a real caller needs the snapshot inside the factory). Cow pilot itself (separate Phase 2 plan). Artemis surface extensions like `Artemis.attack(NpcQuery)` — Option β rejected upstream.

---

## 1. Design rules (from review)

These are the contract DynamicStep MUST satisfy. Every implementation decision in §3-§5 traces back to one of these:

1. **DynamicStep is an engine composite, not a leaf Step driving an inner Step.** No manual `child.onStart()` / `child.check()` calls. The engine owns child lifecycle.
2. **`StateDrivenEngine` gets an explicit DynamicStep orchestration case.** Mirrors the existing `LinearSequence` / `Selector` / `RepeatStep` branches in `invokeOrchestration` (`StateDrivenEngine.java:410-427`), `pushFirstChildIfComposite` (`:495-538`), and `makeFrame` (`:542+`).
3. **DynamicStep does not call child.onStart / check / onFailure manually.** Engine routes all lifecycle.
4. **Generated child state lives in `DynamicStepFrame`**, not in the `DynamicStep` instance. The composite instance is reusable; the frame is per-execution.
5. **Safe inside `RepeatStep`.** Each `RepeatStep` iteration re-pushes the DynamicStep, engine constructs a fresh `DynamicStepFrame`, the factory is re-invoked → fresh child per iteration.
6. **Factory creates a fresh child Step per execution.** Caller's responsibility; the engine enforces by calling the supplier each time `pushFirstChildIfComposite` runs for a new frame.
7. **If the factory cannot produce a child Step, DynamicStep fails with a clear diagnostic.** Specific reason codes per failure mode (§5).
8. **DynamicStep does not expose low-level engine primitives to scripts.** Only `Step` + supplier in/out. Same script-side import surface as `RepeatStep` / `Selector`.
9. **DynamicStep does not dispatch anything itself.** No `ActionRequest`, no dispatcher. The materialized child does that via its own `onStart`.
10. **Tiny and fully tested before any consumer uses it.** Phase 2 cow pilot starts only after this slice lands green.

---

## 2. API decision — `Supplier<Step>`, not `Function<WorldSnapshot, Step>`

**Investigation:** `StateDrivenEngine.pushFirstChildIfComposite(...)` is called with `snap = null` at engine startup (`StateDrivenEngine.java:111-113`), but with a non-null snapshot when a composite pushes a child mid-orchestration (`:386-389`) or via the planner (`:355-359`).

**Tradeoff:**

| API | Pros | Cons |
|---|---|---|
| `Supplier<Step>` | Always works. Factory closes over `Artemis` for state reads — Artemis itself marshals the read to client thread per spec §12. Same shape callers already use. | Factory doesn't see the engine snapshot; if it wants snapshot-only data not exposed via Artemis, can't get it directly. |
| `Function<WorldSnapshot, Step>` | Factory can short-circuit on snapshot fields. | Requires either rejecting DynamicStep as root (constraint) OR passing null at root (factory must handle null). Increases caller cognitive load. |

**Decision:** **`Supplier<Step>`** for v1. The Phase 2 pilot and every Artemis-using script reads state via Artemis (which returns its own snapshot-equivalents like `NpcRef`, `InventoryView`), so the supplier-only form is sufficient. If a future caller genuinely needs the raw engine `WorldSnapshot` inside the factory, a v1.x overload `DynamicStep.of(name, Function<WorldSnapshot, Step>)` can be added without changing the Supplier form. Additive, not breaking.

**Public surface:**

```
DynamicStep.of(String name, Supplier<Step> factory)
```

Static factory matches the existing `OutcomeCheck.playerAnimChanged(...)` ergonomic-factory pattern. The constructor is also `public` so direct construction `new DynamicStep(name, factory)` works for callers that prefer it.

---

## 3. File structure

**Create (engine + utility):**

| File | Responsibility | Size estimate |
|---|---|---|
| `runelite-client/src/main/java/net/runelite/client/sequence/composite/DynamicStep.java` | The composite. `extends CompositeStep`. Holds `name` + `factory`; **no mutable state**. | ~60 LOC |
| `runelite-client/src/main/java/net/runelite/client/sequence/composite/DynamicStepFrame.java` | Per-execution frame. Holds `factoryEvaluated` boolean + materialized child reference (telemetry only — engine has its own child frame). | ~25 LOC |
| `runelite-client/src/main/java/net/runelite/client/sequence/composite/FailStep.java` | Leaf Step that returns `Completion.Failed(reason)` from its first `check(...)`. Used by factories that need to express "no target found." | ~50 LOC |

**Modify (engine):**

- `runelite-client/src/main/java/net/runelite/client/sequence/internal/StateDrivenEngine.java` — three new branches:
  - `makeFrame(...)`: case `instanceof DynamicStep ds → new DynamicStepFrame(ds, depth)`
  - `pushFirstChildIfComposite(...)`: case for DynamicStep — evaluate the supplier once, validate the result, push it as child OR set Failed status + `popAndOrchestrate` (mirrors Selector's "no eligible child" path at `:518-521`)
  - `invokeOrchestration(...)`: case → call `ds.onChildPopped(dsf, status, snap, blackboard)` (the DynamicStep contract: propagate child status as own status)

**Create (tests):**

| File | Coverage |
|---|---|
| `runelite-client/src/test/java/net/runelite/client/sequence/composite/DynamicStepTest.java` | Unit-level: factory invocation, factory-throws, factory-returns-null, frame isolation. Uses minimal fakes for child Steps. |
| `runelite-client/src/test/java/net/runelite/client/sequence/composite/DynamicStepEngineIntegrationTest.java` | Engine-level: runs DynamicStep through `StateDrivenEngine`, asserts: child push, status propagation, RepeatStep-fresh-child-per-iteration, no "unknown composite type" failure. |
| `runelite-client/src/test/java/net/runelite/client/sequence/composite/FailStepTest.java` | FailStep returns `Completion.Failed` with given reason on first `check`. |

No CLAUDE.md edits. No grep-gate edits (no banned-import changes). No Artemis edits.

---

## 4. DynamicStep behavior contract

**Composite traversal:**

```
Engine pushes DynamicStep frame
   └─► makeFrame creates DynamicStepFrame(ds, depth)
   └─► pushFirstChildIfComposite for DynamicStep:
         ├─► supplier.get()
         │     ├─► returns Step s → frame.setMaterializedChild(s); push child frame; mark frame.factoryEvaluated = true
         │     ├─► returns null    → setStatus(Failed("DYNAMIC_FACTORY_RETURNED_NULL")); popAndOrchestrate
         │     └─► throws ex       → setStatus(Failed("DYNAMIC_FACTORY_THREW:" + ex.getMessage())); popAndOrchestrate
   
Engine ticks child Step normally.

Child pops with Completion C:
   └─► invokeOrchestration → DynamicStep.onChildPopped(dsf, C, snap, bb):
         ├─► if C instanceof Completion.Succeeded s → FinishWithSuccess(s.reason())
         ├─► if C instanceof Completion.Failed f    → FinishWithFailure(f.reason())
         └─► (no other completion types exist)
```

**Per spec §12.5 / §12.6:** DynamicStep is transparent — it does not implement its own retry budget. Retry happens at the materialized child's `onFailure → Recovery.Retry(N)` per spec §12.6. After the child's retries exhaust, the Failed propagates up through DynamicStep into the surrounding composite.

**Snapshot freshness:** Each `pushFirstChildIfComposite` call invokes the supplier. When DynamicStep is the body of a `RepeatStep`, each iteration the engine re-creates the DynamicStepFrame and re-invokes the supplier — fresh child per iteration. This is **structural**, not a contract DynamicStep enforces — the engine's "new frame per push" pattern already provides it.

**Identity rule:** DynamicStep does NOT detect "supplier returned the same Step instance twice" — that's the caller's discipline. The Step's `then(...)` in `SequencePlanBuilderImpl.java:49-56` already rejects same-instance re-adds for explicit plan composition; for DynamicStep the equivalent discipline is "the supplier must produce a fresh Step on each call." The factory pattern (lambda closing over Artemis) makes this natural: every `artemis.click(ref, verb)` call returns a fresh Step (verified by reading `ArtemisImpl`'s click methods).

---

## 5. Failure modes — exact reason codes

| Failure mode | Reason code | When |
|---|---|---|
| Supplier returned `null` | `DYNAMIC_FACTORY_RETURNED_NULL` | `pushFirstChildIfComposite` after `supplier.get()` returns null |
| Supplier threw `RuntimeException` | `DYNAMIC_FACTORY_THREW:<exception class simple name>:<exception message>` | `pushFirstChildIfComposite` catches the exception. Class name kept in the reason because postmortem dashboards otherwise lose the type information (a `NullPointerException` with `null` message would be indistinguishable from any other `RuntimeException`). `Error` (AssertionError, OutOfMemoryError) intentionally NOT caught — propagates per JVM convention. |
| Child Step Failed | (propagated child reason) | `invokeOrchestration` returns `FinishWithFailure(child.reason)` |
| Child Step Succeeded | (propagated child reason) | `invokeOrchestration` returns `FinishWithSuccess(child.reason)` |

These reason strings appear in telemetry / `StepEvent` payloads. Distinct from existing engine codes so dashboards (Phase 7) can disambiguate.

---

## 6. Test matrix

The 8 tests the review required, mapped to test files:

| # | Test | File | Asserts |
|---|---|---|---|
| 1 | Factory called exactly once per frame | `DynamicStepTest` | Counter-increment mock supplier; engine pushes one frame; counter = 1 |
| 2 | Child success propagates | `DynamicStepTest` | Mock child returns `Completion.Succeeded("ok")`; DynamicStepFrame ends with `FinishWithSuccess("ok")` |
| 3 | Child failure propagates | `DynamicStepTest` | Mock child returns `Completion.Failed("bad")`; DynamicStepFrame ends with `FinishWithFailure("bad")` |
| 4 | Factory exception becomes failure | `DynamicStepTest` | Mock supplier throws `IllegalStateException("nope")`; DynamicStepFrame ends with `Failed("DYNAMIC_FACTORY_THREW:nope")`; no child frame pushed |
| 5 | Factory returns null → fail loud | `DynamicStepTest` | Mock supplier returns `null`; DynamicStepFrame ends with `Failed("DYNAMIC_FACTORY_RETURNED_NULL")`; no child frame pushed |
| 6 | DynamicStep inside RepeatStep — fresh child each iteration | `DynamicStepEngineIntegrationTest` | RepeatStep(times=3) with body=DynamicStep; supplier increments a counter and returns a one-tick-success child each call; engine drives 3 iterations; assert supplier counter == 3 |
| 7 | DynamicStep does not reuse child state across iterations | `DynamicStepEngineIntegrationTest` | Same as #6, but the supplier's returned children are tracked by identity; assert all three are distinct instances (`a != b != c`) |
| 8 | Engine recognises DynamicStep; no "unknown composite type" failure | `DynamicStepEngineIntegrationTest` | Run plain DynamicStep through engine; assert orchestration does NOT return `FinishWithFailure("unknown composite type ...")`; child success/failure routes through DynamicStep.onChildPopped |

Plus `FailStepTest`:
- Returns `Completion.Failed("MY_REASON")` on first `check(...)` regardless of state.
- Reason matches the constructor argument.

---

## 7. Implementation slicing

Five small commits. Each lands green before the next starts. Per `feedback_one_at_a_time_irl_test` — no big-bang.

### Slice 1C.1 — FailStep utility + test

The smallest possible commit so the rest of the plan can use it.

- [ ] **Step 1:** Create `FailStep.java`. Leaf step (implements `Step` directly; not a composite). Holds `name` + `reason` final fields. `onStart` is no-op; `check(...)` returns `Completion.Failed(reason)` on first call. `onFailure` returns `Recovery.Abort(reason)` (no retry; this is a "definitive fail" utility). `tick`/`onEvent` no-op; `timeoutTicks = 1`; `preemptionPolicy = WHEN_SAFE`; `isSafeToPause = canStart = true`.
- [ ] **Step 2:** Create `FailStepTest.java` with two assertions: `Completion.Failed` on first check; reason equals constructor arg.
- [ ] **Step 3:** Run `./gradlew :client:compileJava` then `:client:test --tests FailStepTest`. Both pass.
- [ ] **Step 4:** Run grep gate `./scripts/check-no-direct-engine-reaches.sh`. Exits 0 (engine code, not script code; no allow-list change).
- [ ] **Step 5:** Commit. Message: `feat(sequence): Phase 1C.1 — FailStep leaf step (definitive-fail utility)`

### Slice 1C.2 — DynamicStep + DynamicStepFrame skeleton (no engine integration)

Lands the types without wiring. Compiles. Has unit tests for factory invocation that DON'T require engine integration (using the public `onChildPopped` overload directly).

- [ ] **Step 1:** Create `DynamicStep.java`. `final class DynamicStep extends CompositeStep`. Public constructor `(String name, Supplier<Step> factory)`. Static `of(...)` factory. Implements `name()` + `priority()` (default 0). Implements the abstract `onChildPopped(Step, Completion, WorldSnapshot, Blackboard)` from CompositeStep with `throw new UnsupportedOperationException()` (per `RepeatStep.java:46-48` pattern). Provides frame-aware `onChildPopped(DynamicStepFrame, Completion, WorldSnapshot, Blackboard)` returning `FinishWithSuccess` or `FinishWithFailure` per §4.
- [ ] **Step 2:** Create `DynamicStepFrame.java`. Extends `StepFrame`. Fields: `@Getter @Setter boolean factoryEvaluated = false`; `@Getter @Setter Step materializedChild`. Constructor `(DynamicStep step, int depth)`.
- [ ] **Step 3:** Create `DynamicStepTest.java` with tests #2, #3, #4, #5 from §6 — instantiate `DynamicStepFrame` directly, mock children, invoke `onChildPopped(frame, status, ...)` directly. (Tests #1, #6, #7, #8 need engine integration — slice 1C.3.)
- [ ] **Step 4:** Compile + tests pass.
- [ ] **Step 5:** Grep gate green.
- [ ] **Step 6:** Commit. Message: `feat(sequence): Phase 1C.2 — DynamicStep + DynamicStepFrame skeleton (no engine integration yet)`

### Slice 1C.3 — Engine integration (the three branches)

The load-bearing slice. Engine learns about DynamicStep.

- [ ] **Step 1:** Modify `StateDrivenEngine.makeFrame(Step, int)`. Add the branch: `if (s instanceof DynamicStep ds) return new DynamicStepFrame(ds, depth);` after the existing three composite cases. Keep the file's import + layout convention (fully-qualified class names per existing code).
- [ ] **Step 2:** Modify `StateDrivenEngine.pushFirstChildIfComposite(StepFrame, WorldSnapshot)`. Add the branch per §4 — invoke `ds.factory().get()`, validate, push or fail. On null: `composite.setStatus(new Completion.Failed("DYNAMIC_FACTORY_RETURNED_NULL")); popAndOrchestrate(composite, ..., snap)` — same pattern as Selector's no-eligible-child path at `:518-521`. On exception: same shape with `"DYNAMIC_FACTORY_THREW:" + ex.getMessage()`. Telemetry record on success: `TelemetryRecord.Event.SELECTED, "dynamic body"` mirroring RepeatStep's `"repeat body"` at `:534`.
- [ ] **Step 3:** Modify `StateDrivenEngine.invokeOrchestration(...)`. Add the branch: `if (parentStep instanceof DynamicStep ds && parent instanceof DynamicStepFrame dsf) return ds.onChildPopped(dsf, status, snap, blackboard);` before the final `unknown composite type` fallback.
- [ ] **Step 4:** Create `DynamicStepEngineIntegrationTest.java` with tests #1, #6, #7, #8 from §6.
- [ ] **Step 5:** Compile + all tests pass (`DynamicStepTest`, `DynamicStepEngineIntegrationTest`, `FailStepTest`, plus the existing engine tests must still pass).
- [ ] **Step 6:** Grep gate green.
- [ ] **Step 7:** Commit. Message: `feat(sequence): Phase 1C.3 — engine integration for DynamicStep (makeFrame + pushFirstChildIfComposite + invokeOrchestration branches)`

### Slice 1C.4 — Documentation update

- [ ] **Step 1:** Update `runelite-client/src/main/java/net/runelite/client/sequence/ARCHITECTURE.md` (if it exists; otherwise skip). Add a short paragraph naming DynamicStep alongside the other composites with the one-line use case: "Materializes a child Step at execution time. Use when the child depends on runtime state that can't be known at plan-construction time (e.g., `findNpc` results)."
- [ ] **Step 2:** No CLAUDE.md edits — DynamicStep is engine-side. Script-side allowed-import rules are unchanged.
- [ ] **Step 3:** Commit. Message: `docs(sequence): Phase 1C.4 — document DynamicStep alongside RepeatStep/Selector/LinearSequence`

### Slice 1C.5 — Sign-off + handoff to Phase 2

- [ ] **Step 1:** Run the full client test suite (`:client:test`) to verify no regressions. Engine touches are load-bearing; this is the safety net.
- [ ] **Step 2:** Run the grep gate one more time. Zero hits.
- [ ] **Step 3:** Operator-visible report:
  - 3 new engine files (`DynamicStep`, `DynamicStepFrame`, `FailStep`)
  - 1 modified engine file (`StateDrivenEngine.java`, ~30 LOC across 3 branches)
  - 3 new test files
  - 0 changes to Artemis interface, ArtemisImpl, RecorderPlugin, scripts/, or grep-gate config
- [ ] **Step 4:** STOP. Wait for operator approval before Phase 2A begins.

---

## 8. Risks

| Risk | Probability | Mitigation |
|---|---|---|
| Engine touches break an existing composite test | Low (new branches; no edits to existing branches) | Slice 1C.5 runs full `:client:test`; regression caught before sign-off |
| Supplier closure leaks state across iterations | Caller error, not DynamicStep's problem | Tests #6 and #7 catch the pattern at integration level; documented in DynamicStep Javadoc |
| Caller hands an Artemis-returned Step into both a static plan slot AND a DynamicStep supplier output | Low | `SequencePlanBuilderImpl.then(...)` rejects same-instance re-adds (`:49-56`); for DynamicStep, the supplier producing a fresh Step on each call is the documented contract |
| `pushFirstChildIfComposite` evaluates factory at `snap==null` and the factory expects state | N/A — Supplier-only API, no snap parameter | Decided in §2 |
| Adding DynamicStep to engine grows `invokeOrchestration` switch from 3 to 4 — pressure to virtualize | Visible | Acceptable for v1; if the switch hits 6+, refactor to virtual dispatch as a separate engine-cleanup spec |

---

## 9. What this does NOT do

- Add an `Artemis.findAndClick(NpcQuery, verb)` method. Rejected (Option β) — would bloat the Artemis surface with one wrapper per read-then-act pattern.
- Add `SucceedStep`. YAGNI — no current caller.
- Change spec §14 import policy. DynamicStep + FailStep are composite types, already allowed.
- Touch the script-side grep gate. Engine code, not script code.
- Touch CLAUDE.md numbered rules. Phase 4 owns the §11 import-policy text.
- Change OutcomeCheck or its v1 limitations. Phase 1A owns that surface.

---

## 10. Handoff to Phase 2

After Slice 1C.5 sign-off, Phase 2's revised cow pilot plan (`docs/superpowers/plans/2026-05-24-artemis-phase-2-cow-pilot.md`) uses DynamicStep as the find-and-attack body:

```
DynamicStep.of("find-and-attack-cow", () -> {
    Optional<NpcRef> cow = artemis.findNpc(cowQuery);
    if (cow.isEmpty()) {
        return FailStep.of("NO_COW_FOUND");
    }
    return artemis.click(cow.get(), "Attack");  // base click — interactingWithMe() unsupported per OutcomeChecks.java:87-91
})
```

Phase 2's plan revision lands separately. This slice's only handoff is "DynamicStep is available."

---

## Self-review (compact checklist)

- [x] Plan size within `feedback_compact_plans` budget (target 300-700; this is ~370).
- [x] No full Java sources pasted — describes behavior, references file:line for existing patterns.
- [x] APIs verified before referencing: `RepeatStep.java:46-48` for the abstract-stub pattern; `StateDrivenEngine.java:410-427` for the orchestration switch; `:495-538` for first-child push; `:542+` for makeFrame; `SelectorFrame.java` for the frame size shape; `SequencePlanBuilderImpl.java:49-56` for the same-instance-Step rejection precedent.
- [x] Engine extensibility constraints explicit (3 branches; closed-set dispatch in v1; refactor pressure noted as risk only).
- [x] Supplier-vs-Function decision documented with rationale, not asserted.
- [x] Failure modes have specific reason codes.
- [x] Tests map 1-to-1 with the review's 8 required cases.
- [x] No "convenience tier" — DynamicStep is the architectural primitive, not a wrapper (`feedback_no_convenience_in_architecture`).
- [x] No anti-detection / evasion framing (`feedback_no_evasion_framing`).
- [x] Slicing is one-at-a-time (`feedback_one_at_a_time_irl_test`): utility → types → engine → docs → sign-off.
- [x] No build commands beyond what the operator runs explicitly (`feedback_dont_build_client_unprompted`).

---

## Stop and wait

This plan is review-only. Do not begin Slice 1C.1 until the operator green-lights it.
