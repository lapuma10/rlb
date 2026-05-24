# Artemis Phase 2B.1 — Pilot Launch + Runtime Dependency Wiring Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **This is a decision-and-design plan only — DO NOT begin implementation until the operator approves it.**

**Goal:** Resolve the five runtime wiring questions that block Phase 2C: `SequenceManager` ownership, pilot launch path, `Navigator` wiring, `LogoutAction` wiring, and `SessionShape` budget acceptability. Ship a launch path that can actually run a Tier 1 pilot session — without the cow loop yet — while keeping production scripts and the import policy unaffected.

**Architecture:** Move ArtemisImpl construction from plugin-startup (where Phase 2B placed it as a wire-up stub) to **launch-time construction inside a `launchCowKillerPilot()` method on `RecorderPlugin`**. The plugin owns one `SequenceManager` for the pilot's lifetime, builds a dedicated `V21Navigator` per pilot run via the existing `buildV21Navigator(...)` helper, and plugs in a new observation-only `RecorderLogoutAction`. **No UI surface lands in 2B.1** — the `launchCowKillerPilot()` method exists but is callable only from code (no panel button, no config flag). The config item + Start/Stop button land in **2C.1** after Phase 2C wires `CowKillerScript.plan()` to return a real Step. This avoids exposing a clickable Start whose expected behavior is throwing `UnsupportedOperationException`.

**Scope rationale (per operator review):** A panel button bound to a throwing `plan()` would be noisy and not useful; the only meaningful UI gate is one that lights up when `plan()` actually composes a Step. So 2B.1 ships the *launch path code surface* (method exists, dependencies wire correctly, lifecycle is clean), and 2C.1 adds the UI once there's something real to bind to.

**Tech Stack:** Java 17, existing sequence engine (`SequenceManager.run(Step)` / `.stop()`), Artemis v1.0 (`ArtemisImpl` / `ArtemisDeps`), existing `V21Navigator` factory in `RecorderPlugin`, RuneLite's `@ConfigItem` for the launch toggle, Swing panel button for the launch trigger.

**Scope (in):** `RecorderLogoutAction` (new), `RecorderPlugin.launchCowKillerPilot()` + `stopCowKillerPilot()` methods, removal of Phase 2B's startup-held `Artemis artemis` field, defensive shutdown.

**Scope (out):** `CowKillerScript.plan()` body (still throws `UnsupportedOperationException` after this slice — Phase 2C lands it). **`RecorderConfig` toggle + panel button — both deferred to 2C.1, after `plan()` returns a real Step.** Real `SessionShape` daily budget (Phase 0B). Phase 4 CI grep gate. Any change to existing scripts or the legacy `LogoutHelper`.

---

## 1. Decision: `SequenceManager` ownership

**Decision:** **`RecorderPlugin` owns one `SequenceManager` instance per pilot run** — constructed inside `launchCowKillerPilot()`, held as a `private @Nullable SequenceManager pilotSequenceManager;` field, nulled out when the run ends. **Not a plugin-startup field.** Not owned by `CowKillerScript`. Not owned by `RecorderPanel`.

**Rationale:**

- `SequenceManager` carries mutable engine state (`StateDrivenEngine` instance, blackboard, telemetry, observer, dispatcher) and is single-use per spec §12.5 + the `SequencePlanBuilderImpl.then(...)` precedent (rejects same-instance Step re-adds). One `SequenceManager` per logical run avoids the same trap at the engine level.
- `RecorderPlugin` already owns the cross-cutting state (Client, ClientThread, RecorderManager, V21Navigator factory, etc.) needed to wire a SequenceManager. Centralizing here matches the existing pattern for ChickenFarmV3 (the plugin constructs it, the panel just clicks `start()`).
- Putting it on `CowKillerScript` would couple the pilot to engine plumbing it should not see (spec §14 import-policy violation in spirit; the pilot is a *consumer* of Artemis, not of SequenceManager directly).
- Putting it on `RecorderPanel` would make Swing UI own engine lifecycle — unsafe for the same reason `LogoutHelper.tryLogout` is unsafe: panel methods run on EDT, engine methods need to marshal to ClientThread.

**Cancellation model:**

- `stopCowKillerPilot()` (new method on `RecorderPlugin`) calls `pilotSequenceManager.stop()` (existing — `SequenceManager.java:119`), then nulls the field.
- On `shutDown()` of the plugin, `stopCowKillerPilot()` is called defensively (no-op if no pilot is running). **This is the only `stopCowKillerPilot()` caller in 2B.1.**
- **Future 2C.1 scope:** the panel "Stop" button will bind to `plugin::stopCowKillerPilot`. Not in 2B.1 — no UI lands until 2C.1.
- `SequenceManager.setScheduler(clientThread::invoke)` is called at construction so mutating engine calls marshal to the client thread (`SequenceManager.java:114, 116, 119`).

**Construction shape (in `launchCowKillerPilot()` — pseudocode):**

```
SequenceManager mgr = SequenceManager.withDefaults();
mgr.setObserver(observer);          // plugin builds a tick-driven Observer
mgr.setDispatcher(dispatcher);      // plugin builds a HumanizedInputDispatcher for the pilot
mgr.setScheduler(clientThread::invoke);
// observer + dispatcher + telemetry + planner + blackboard → engine auto-built
// per SequenceManager.rebuildEngineIfReady() at :99-112
pilotSequenceManager = mgr;
mgr.run(cowKillerScript.plan());    // throws UnsupportedOperationException at 2B.1
                                    // (intentional — proves the wiring reaches
                                    // CowKillerScript.plan() before 2C swaps the
                                    // body); will return Step once 2C lands.
```

**Observer + Dispatcher concrete choice:** the existing `HumanizedInputDispatcher` instances the plugin builds for ChickenFarmV3 (`RecorderPlugin.java:644-665` area) are per-script. The pilot needs its own dispatcher + observer with non-conflicting `InputOwnership` token to avoid contending with running production scripts. Decision: build a **dedicated pilot-only dispatcher + observer pair** in `launchCowKillerPilot()` using the same helper patterns the V3 script uses.

---

## 2. Decision: pilot launch path

**Decision:** **In 2B.1, the launch path exists only as code** — a `launchCowKillerPilot()` method on `RecorderPlugin` that constructs the full per-run wiring (SequenceManager, Navigator, Dispatcher, ArtemisImpl, CowKillerScript, RecorderLogoutAction). **No UI button. No `@ConfigItem` toggle.** The method is callable from code only.

**The config item + Start/Stop button land in 2C.1**, after Phase 2C wires `CowKillerScript.plan()` to return a real Step. A button bound to a throwing `plan()` is noisy and not useful; the UI gate makes sense only when there's a real Step to run.

**Rationale:**

- A clickable Start whose expected behavior is `UnsupportedOperationException("Phase 2C wires the loop")` produces operator-visible exceptions for normal use — bad UX and misleading "is the pilot broken?" investigations.
- A *disabled* placeholder button is feasible but adds Swing state machinery (enabled-state binding, status label, refresh on config change) for a UI element that does nothing for one slice. Not worth landing twice.
- The `launchCowKillerPilot()` method existing **as code** is enough to verify the wiring is correct (compile + plugin lifecycle clean + the dependency record types line up). Manual invocation in 2B.1 — if needed — is via developer-mode IDE evaluation or a temporary test fixture; not via the user-facing UI.

**Verification of 2B.1's launch-method without UI:**

- `:client:compileJava` confirms signatures + dependency record types are correct.
- Plugin `startUp()` / `shutDown()` lifecycle clean (no exception thrown during plugin enable/disable).
- The `shutDown()` path calls `stopCowKillerPilot()` defensively (null-guard handles "no run in flight" — see §1).
- Optional: an operator can confirm reachability via an IDE breakpoint inside `launchCowKillerPilot()` while triggering it from a temporary test fixture or REPL. Not a CI-checked criterion.

**What lands in 2C.1 (deferred):**

- One new `@ConfigItem boolean cowKillerPilotEnabled` in `RecorderConfig` (default `false`).
- Start / Stop buttons + status label in `RecorderPanel`, enabled only when the config flag is `true`.
- Double-start guard (panel-side null-check of `pilotSequenceManager` before calling `launchCowKillerPilot()`; logger warn + no-op on contention).

**Failure-loud behavior in 2B.1 (no UI involved):**

- If anything else in the plugin (or a future caller) invokes `launchCowKillerPilot()` while `pilotSequenceManager != null`, the method logs a warn and returns without re-launching. The double-start guard is the method's own responsibility, not the panel's.

---

## 3. Decision: `Navigator` wiring

**Decision:** **Construct a dedicated `V21Navigator` instance for each pilot run** via the existing `buildV21Navigator(dispatcher)` helper (`RecorderPlugin.java:823`). Pass it into `new ArtemisImpl(...)` at launch time. **Do not** keep a startup-held shared Navigator. **Do not** add a setter to `ArtemisImpl` for late-Navigator injection. **Remove** the Phase 2B startup-held `Artemis artemis` field — it served only as a wiring proof and is now redundant.

**Rationale:**

- The existing `V21Navigator` factory (`buildV21Navigator(dispatcher)`) is the production builder. ChickenFarmV3 uses it via `buildV2Navigator` / `buildV21Navigator` per-script (`RecorderPlugin.java:795`). Sharing one Navigator across scripts contends busy-state — each script needs its own.
- The cow pilot inherits the same per-script-Navigator pattern. Building it at launch time scopes its lifetime to the pilot run — Pilot stops → Navigator's worker thread joins → no leaked resources.
- Adding a `setNavigator(...)` to `ArtemisImpl` would break the immutable-deps contract (`ArtemisDeps` is a record, `ArtemisImpl.navigator` is a `private final` field). Worse, it would let callers swap deps mid-Step — exactly the kind of stale-reference bug spec §8 was designed to prevent.
- The Phase 2B startup-held `Artemis artemis` field exists but is never read by anyone. Phase 2B's stated purpose was to *prove the wiring is reachable*. With the launch path landing in 2B.1, the field's reason-for-being is gone — keep the construction code, move it to `launchCowKillerPilot()`.

**Construction shape in `launchCowKillerPilot()`:**

```
HumanizedInputDispatcher dispatcher = /* build via existing per-script helper */;
V21Navigator nav = buildV21Navigator(dispatcher);
RecorderLogoutAction logoutAction = new RecorderLogoutAction(client);  // §4 — client-thread-only impl
SessionShape session = new SessionShape(
    () -> client.getTickCount(),
    () -> client.getTickCount(),
    Long.MAX_VALUE);  // §5 — acceptable for Tier 1 only
Artemis artemis = new ArtemisImpl(new ArtemisDeps(
    client, clientThread,
    new AccountRng(client),
    session,
    itemManager, manager,
    nav,                        // §3 — real Navigator
    logoutAction));             // §4 — real LogoutAction
CowKillerScript script = new CowKillerScript(artemis);
// ... SequenceManager construction per §1 ...
```

**Cleanup on stop:** `V21Navigator` exposes worker-stop methods (existing — used by V3 stop button). `stopCowKillerPilot()` calls them after `pilotSequenceManager.stop()` to release the navigator's worker thread.

---

## 4. Decision: `LogoutAction` wiring

**Decision:** **Create a new `RecorderLogoutAction` implementing `LogoutAction.nextLogoutWidgetId()`** at `runelite-client/src/main/java/net/runelite/client/plugins/recorder/session/RecorderLogoutAction.java`. Observation-only. Reuses the **widget-id constants** from `LogoutHelper` (`InterfaceID.Logout.LOGOUT`, the three `STONE10` candidates per layout) but **does not** reuse the blocking `tryLogout()` / `onClient(...)` pattern, which deadlocks per the existing `LogoutAction` interface Javadoc (`LogoutAction.java:14-26`).

**Rationale:**

- No `implements LogoutAction` exists in the tree today. The pilot needs one before its `Artemis.logout()` Step can succeed.
- The legacy `LogoutHelper.tryLogout()` (`LogoutHelper.java:40-93`) blocks on `clientThread.invokeLater + CountDownLatch.await()` (`LogoutHelper.java:95-107`). The `LogoutAction` interface Javadoc explicitly bans this pattern (re-quoted): *"It uses `clientThread.invokeLater + latch.await()`, which deadlocks when called from `Step.doStart` (which runs on the client thread)."*
- The widget-id detection logic in `LogoutHelper:43-78` is correct and safe — the unsafe part is the *threading wrapper*, not the *observation*. Copying just the IDs + the visibility-check shape into a clean observation-only class is correct YAGNI.

**`RecorderLogoutAction` contract (new file):**

| Aspect | Decision |
|---|---|
| Package | `net.runelite.client.plugins.recorder.session` (alongside `AccountRng`, `SessionShape`) |
| Constructor | `(Client client)` — `ClientThread` not needed under the client-thread-only contract |
| **Threading — CLIENT-THREAD ONLY** | `nextLogoutWidgetId()` MUST be invoked on the client thread. If invoked off the client thread, the method throws `IllegalStateException("RecorderLogoutAction.nextLogoutWidgetId must be called on the client thread")`. Caller is responsible for marshaling (e.g., `LogoutStep` is already in the Step lifecycle, whose `check()` runs on the client thread per `sequence/Step.java:29-43`). No `clientThread.invokeLater`. No `CountDownLatch`. No bounded-wait. No "non-blocking from any thread" — that would be a contradiction since the method returns an observed value. |
| Inline thread check | `if (!client.isClientThread()) throw new IllegalStateException(...)` — single guard at the top of `nextLogoutWidgetId()`. Mirrors the same fail-loud pattern the engine uses elsewhere when contract is violated. |
| `nextLogoutWidgetId()` body | Direct synchronous reads on the client thread: 1) check `InterfaceID.Logout.LOGOUT` — if visible (`widget != null && !widget.isHidden()`), return `OptionalInt.of(InterfaceID.Logout.LOGOUT)`. 2) Otherwise probe the three `STONE10` candidates (`Toplevel.STONE10`, `ToplevelOsm.STONE10`, `ToplevelPreEoc.STONE10`) in order, return `OptionalInt.of(first-visible-id)`. 3) Otherwise `OptionalInt.empty()`. |
| Banned | No `ActionRequest`. No `dispatcher.dispatch(...)`. No `tryLogout` loop. No `CountDownLatch`. No `invokeLater + await`. No worker thread. |
| Allowed | `client.getWidget(int)`. Widget id constants from `InterfaceID.*`. |

**Why client-thread-only (not the `readOnClient` marshal pattern):**

- `LogoutStep` is the only intended caller. Its `check()` runs on the client thread already (`sequence/Step.java:29-43`), so the marshal-and-block pattern that `ArtemisImpl.readOnClient` uses is unnecessary overhead here.
- The interface's Javadoc (`LogoutAction.java:34-40`) lets implementations marshal internally, but it does NOT require them to. Picking the simpler contract — fail loud off-thread — eliminates a class of bugs (off-thread reads racing the GameTick).
- The 2B.1 plan's prior draft claimed "non-blocking from any thread" — internally inconsistent because the method returns a value. Locked down to client-thread-only, fail-loud.

**`stopCowKillerPilot()` does NOT need to release the RecorderLogoutAction** — it holds no worker thread, no listeners, no native resources. It's a stateless observer.

---

## 5. Decision: `SessionShape` budget acceptability

**Decision:** **`SessionShape(Long.MAX_VALUE)` is acceptable for the first Tier 1 acceptance run (≤30 min, operator-supervised, manual stop).** **Phase 2C does NOT block on Phase 0B.** The 2-hour v1.0-lock signoff DOES block on Phase 0B landing real budget + test-profile wiring.

**Rationale:**

- The Tier 1 acceptance criteria in `2026-05-24-artemis-phase-2-cow-pilot.md` §6 deliberately don't require a real session budget. Termination is acceptable via session-only OR loot-only OR operator-stop.
- For an operator-supervised 30-minute test-profile run, infinite budget changes nothing — the operator stops the script when the criteria are observed, or when something goes wrong. The risk is a runaway script. Mitigated by:
  - The pilot's `RepeatStep(times=0)` body fails the moment a click fails its session-gate check — but with `Long.MAX_VALUE`, that never fires. Termination falls to the next failure mode (cow despawn beyond retry budget, dispatcher error, manual stop).
  - `stopCowKillerPilot()` is one button click away from the operator.
- A 2-hour v1.0-lock run, by contrast, is supposed to be an unattended demonstration that the session-shape gate works. That requires real budget — Phase 0B's job. Document this dependency in Phase 2D's playbook.

**Documentation requirement:** the Phase 2B.1 commit message and the Phase 2D playbook (when it lands) MUST state: *"Tier 1 30-min run is acceptable with `Long.MAX_VALUE` budget; v1.0-lock 2-hour signoff requires Phase 0B daily budget + test-profile dir isolation first."*

---

## 6. File structure (proposed)

**Create (1 file):**

| Path | Responsibility | Estimated size |
|---|---|---|
| `runelite-client/src/main/java/net/runelite/client/plugins/recorder/session/RecorderLogoutAction.java` | Observation-only `LogoutAction` impl; client-thread-only; reads logout widget visibility, returns next click target | ~80 LOC |

**Modify (1 file):**

| Path | Change | Estimated size |
|---|---|---|
| `runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPlugin.java` | Remove Phase 2B startup `Artemis artemis` field + construction; add `pilotSequenceManager` / `pilotV21Navigator` / `pilotDispatcher` fields; add `launchCowKillerPilot()` + `stopCowKillerPilot()` methods; call `stopCowKillerPilot()` from `shutDown()` | +90 / -50 LOC |

**Create (1 test file):**

| Path | What it tests |
|---|---|
| `runelite-client/src/test/java/net/runelite/client/plugins/recorder/session/RecorderLogoutActionTest.java` | Four observation cases: (a) inner logout widget visible → returns its id, (b) only side-panel tab visible → returns that tab id, (c) nothing visible → returns `OptionalInt.empty()`, (d) **called off client thread** → throws `IllegalStateException`. Uses Mockito mocks for `Client` + `Widget` (same pattern as existing WalkToZoneStepTest fixtures); off-thread case uses a separate worker thread. |

**Not in scope for 2B.1 (deferred to 2C.1):**

- `RecorderConfig.java` — no new `@ConfigItem` in this slice.
- `RecorderPanel.java` — no buttons, no labels, no listeners in this slice.

**No test file for `RecorderPlugin`** — per `feedback_no_tests_for_bot_scripts`, plugin wiring is verified manually (compile + plugin-lifecycle smoke). `RecorderLogoutAction` gets unit tests because it's shared infrastructure (a small, focused, deterministic class) — same carve-out as `NamedZone`.

---

## 7. Ownership model — exact

| Object | Lifetime | Owner | Constructed where |
|---|---|---|---|
| `Artemis artemis` (Phase 2B startup field) | n/a — **removed** | n/a | n/a |
| `pilotSequenceManager` | Pilot run (Start → Stop) | `RecorderPlugin` field | `launchCowKillerPilot()` |
| `pilotV21Navigator` | Pilot run | `RecorderPlugin` local var (no field needed if stop is via SequenceManager + a separate `stopNavigator()` call) — but holding as a field simplifies `stopCowKillerPilot()` cleanup | `launchCowKillerPilot()` |
| `pilotDispatcher` | Pilot run | `RecorderPlugin` field (for stop-cleanup) | `launchCowKillerPilot()` |
| `RecorderLogoutAction` | Pilot run | local var (stateless; no cleanup needed) | `launchCowKillerPilot()` |
| `ArtemisImpl` for the pilot | Pilot run | local var | `launchCowKillerPilot()` |
| `CowKillerScript` | Pilot run | local var | `launchCowKillerPilot()` |
| `SessionShape` for the pilot | Pilot run | local var inside the `ArtemisDeps` record | `launchCowKillerPilot()` |
| `AccountRng` for the pilot | Pilot run | local var inside the `ArtemisDeps` record (cheap to recreate; tracks login changes itself) | `launchCowKillerPilot()` |

**Why fields vs locals:** anything `stopCowKillerPilot()` needs to release (SequenceManager, V21Navigator's worker thread, the dispatcher's input ownership) must be a field. Everything else lives as a local — the GC reclaims when the launch method's frame pops.

---

## 8. Slicing — single commit vs split

**Decision:** **Split into two commits, 2B.1.a and 2B.1.b.** Each compiles, passes tests, passes grep gate, and is reviewable on its own. **The visible UI (config item + panel button) lands separately in 2C.1, NOT in 2B.1.**

| Slice | Files | Why this granularity |
|---|---|---|
| **2B.1.a — `RecorderLogoutAction` + tests** | Create `RecorderLogoutAction.java` + `RecorderLogoutActionTest.java`. No plugin touch. | Smallest, fully unit-tested, independent — landing it first lets the plugin slice import it as an existing class. The *only* slice with a new test file, so test regressions are scoped here. |
| **2B.1.b — Plugin launch-method wiring (no UI)** | Modify `RecorderPlugin.java` only. Remove Phase 2B startup ArtemisImpl construction. Add per-pilot-run fields (`pilotSequenceManager`, `pilotV21Navigator`, `pilotDispatcher`). Add `launchCowKillerPilot()` + `stopCowKillerPilot()` methods. Defensive stop on `shutDown()`. **No config item. No panel button.** | Lands the launch path as code — `launchCowKillerPilot()` is callable but unreachable from the UI in this slice. Verified by compile + plugin-lifecycle smoke. |

**The 2C.1 UI slice (NOT this plan's scope, sketched for reference):**

- Adds the `@ConfigItem boolean cowKillerPilotEnabled` to `RecorderConfig`.
- Adds Start / Stop buttons + status label to `RecorderPanel`, gated by the config flag.
- Wires button actionListeners to `plugin::launchCowKillerPilot` / `plugin::stopCowKillerPilot`.
- Lands AFTER Phase 2C so the button binds to a `plan()` that returns a real Step, not one that throws.

**Not a single 2B.1 commit because:**
- A failed merge or revert on 2B.1.b should not cost us `RecorderLogoutAction` — it's reusable.
- The unit-test boundary lands cleanly in 2B.1.a; 2B.1.b has no new unit tests (plugin touch).
- Reviewing 2B.1.b's plugin changes against the *already-merged* `RecorderLogoutAction` is easier than reviewing both at once.

**Not three 2B.1 slices** — the launch-method wiring is one cohesive change; splitting fields from methods would land half-broken intermediate states.

---

## 9. Tests / compile checks / grep gate

### Compile

```
:client:compileJava → BUILD SUCCESSFUL after each slice
```

Existing pre-existing deprecation note in `RecorderPanel` continues; no new warnings introduced.

### Tests (per slice)

**Slice 2B.1.a:**

```
:client:test --tests 'net.runelite.client.plugins.recorder.session.RecorderLogoutActionTest'
  → 4 assertions, 0 failures expected
```

Tests:
1. `innerLogoutWidgetVisible_returnsThatWidgetId` — mock `client.isClientThread()` returns true; mock `client.getWidget(InterfaceID.Logout.LOGOUT)` returns a non-hidden Widget; assert `OptionalInt.of(InterfaceID.Logout.LOGOUT)`.
2. `onlyTabWidgetVisible_returnsTabId` — same client-thread mock; mock inner returns null/hidden, one of the three STONE10 candidates returns visible; assert `OptionalInt.of(<that-tab-id>)`.
3. `nothingVisible_returnsEmpty` — same client-thread mock; all reads return null/hidden; assert `OptionalInt.empty()`.
4. `calledOffClientThread_throwsIllegalStateException` — mock `client.isClientThread()` returns false (or invoke from a worker thread so the real check fires); assert `IllegalStateException` with a message mentioning "client thread."

**Slice 2B.1.b:**

No unit tests. Verification:

- `:client:compileJava` BUILD SUCCESSFUL.
- Plugin enable/disable cycle clean: load the plugin in dev-mode RuneLite, disable + re-enable via the plugin manager UI — no exception, `shutDown()` runs `stopCowKillerPilot()` defensively (no-op when nothing is running).
- `launchCowKillerPilot()` is callable from code; verifying it via IDE evaluation or a temporary developer-mode fixture is **optional**, not a Tier 1 acceptance criterion. The method's correctness is observed only after 2C wires the real `plan()` + 2C.1 wires the UI.

### Grep gate

```
./scripts/check-no-direct-engine-reaches.sh → exit 0 after each slice
```

**Slice 2B.1.a affects scan count?** No — `RecorderLogoutAction.java` lives at `recorder/session/`, NOT at `recorder/scripts/`. The gate's `SCRIPTS_DIR` (`scripts/check-no-direct-engine-reaches.sh:15`) is `recorder/scripts/` only. Scan count stays at 16, allow-list stays at 107 rows.

**Slice 2B.1.b affects scan count?** No — modifies `RecorderPlugin`, `RecorderConfig`, `RecorderPanel`, none of which are in `recorder/scripts/`. The pilot file `CowKillerScript.java` is **not modified** in this slice. Scan count stays at 16, allow-list stays at 107.

**Verification each slice:**
```
./scripts/check-no-direct-engine-reaches.sh
# Expected: OK: scanned 16 files, 107 allow-list entries applied (covering 199 hit(s)).
```

If scan count changes, something landed in `recorder/scripts/` that wasn't planned — stop and investigate.

---

## 10. What remains for Phase 2C and 2C.1

After 2B.1 lands, the launch method exists as code but has no UI surface and `script.plan()` still throws. Two follow-up slices land before Phase 2D's session protocol:

**Phase 2C — `CowKillerScript.plan()` body:**

Swap the `UnsupportedOperationException` for the real plan per `2026-05-24-artemis-phase-2-cow-pilot.md` §4:

```
LinearSequence("cow-killer-v1")
 ├─ WalkToZoneStep(LUMBRIDGE_COW_FIELD)
 └─ Selector(
       RepeatStep("cow-killer-loop", body, times=0)
         body = DynamicStep.of("find-and-attack-cow", supplier)
       ,
       LogoutStep
     )
```

2C tasks:
- Implement `CowKillerScript.plan()` using `Artemis`, `DynamicStep`, `FailStep`, existing composites
- Structural test (`CowKillerScriptPlanShapeTest`) per §6 criterion 3 of the cow-pilot plan
- Stale-ref test per §6 criterion 9
- Re-run compile + grep gate (gate stays at 16 files / 107 entries — CowKillerScript already exists)

**Phase 2C.1 — UI wiring (config item + panel button):**

Only lands after 2C, when `plan()` returns a real Step:

- Add `@ConfigItem boolean cowKillerPilotEnabled` to `RecorderConfig`, default `false`, labeled test-only.
- Add Start / Stop buttons + status label to `RecorderPanel`, enabled-state bound to the config flag.
- Wire button actionListeners to `plugin::launchCowKillerPilot` / `plugin::stopCowKillerPilot`.
- Double-start guard in the panel (null-check `pilotSequenceManager` before calling launch).
- No new tests — UI is verified manually via the Tier 1 session.

2B.1 is **prerequisite-complete for 2C** when:
- `launchCowKillerPilot()` exists and compiles (the body wires real Navigator + LogoutAction + ArtemisImpl + SequenceManager + CowKillerScript).
- `RecorderLogoutAction` exists and 4-assertion tests pass.
- Phase 2B's startup `Artemis artemis` field is removed.
- `shutDown()` calls `stopCowKillerPilot()` defensively.

2C is **prerequisite-complete for 2C.1** when:
- `CowKillerScript.plan()` returns a non-throwing Step tree.
- Plan-shape test + stale-ref test pass.

2C.1 is **prerequisite-complete for 2D** when:
- Operator can toggle the config flag and click Start; pilot launches.
- Stop button cleanly stops the run.

---

## 11. What remains for Phase 2D

2D is unchanged from the revised cow-pilot plan (`§9 Slice 2D` of `2026-05-24-artemis-phase-2-cow-pilot.md`):

- Operator-facing playbook at `docs/learnings/2026-05-24-artemis-phase-2-pilot-session-protocol.md`
- 30-min test-profile session run + Tier 1 checklist sign-off
- Updates to the playbook with the 5 decisions from this 2B.1 plan baked in (especially the `SessionShape(Long.MAX_VALUE)` known limitation + the 2-hour v1.0-lock dependency on Phase 0B)

No code; documentation + operator validation only.

---

## 12. Risks + mitigations

| Risk | Probability | Mitigation |
|---|---|---|
| `V21Navigator` worker thread leaks if `stopCowKillerPilot()` is not called (e.g., plugin shuts down before stop) | Medium | Call `stopCowKillerPilot()` from `RecorderPlugin.shutDown()`; null guard handles "no run in flight." |
| `launchCowKillerPilot()` is invoked twice without `stop` in between, two pilots overlap | Low — only programmatic callers in 2B.1 | The method's own null-check on `pilotSequenceManager` logs warn + returns. Panel double-start guard lands in 2C.1. |
| `RecorderLogoutAction.nextLogoutWidgetId()` is called from a worker thread and silently returns wrong data | Eliminated by contract | Method throws `IllegalStateException` immediately when off the client thread. Failure is loud at first off-thread call, not at a downstream symptom. |
| Phase 2B startup-field removal breaks a consumer I missed | Very low — verified no consumers via grep | Pre-edit grep `Artemis artemis` references in RecorderPlugin; reject if any non-trivial reads exist. |
| Pilot `HumanizedInputDispatcher` contends with a running production script's dispatcher | Medium if operator is reckless | Documented as "do not launch while another script is running" in 2D playbook. Could later add an in-flight check, but YAGNI for Tier 1. |
| `SessionShape(Long.MAX_VALUE)` allows a runaway loop past the operator's intended stop | Low — pilot is supervised + 2B.1 has no UI to even start it | Stop button (2C.1). Plus the natural failure modes (dispatcher error, navigator stuck, etc.) terminate the body via `Recovery.Abort`. |
| Operator looks for the Start button after 2B.1 lands and is confused it doesn't exist | Low — slice intent documented | Commit message + this plan explicitly state "UI lands in 2C.1." |

---

## 13. Decision summary table (for the operator)

| # | Question | Answer | Where it lands |
|---|---|---|---|
| 1 | SequenceManager ownership | Plugin-owned, per-pilot-run, constructed at launch | `RecorderPlugin.launchCowKillerPilot()` (2B.1.b) |
| 2 | Pilot launch path | **2B.1: launch method exists in code, no UI. 2C.1: `@ConfigItem` boolean + panel button.** | `RecorderPlugin.launchCowKillerPilot()` (2B.1.b); `RecorderConfig` + `RecorderPanel` (2C.1) |
| 3 | Navigator wiring | Dedicated `V21Navigator` per pilot run via existing `buildV21Navigator(...)` | `RecorderPlugin.launchCowKillerPilot()` (2B.1.b) |
| 4 | LogoutAction wiring | New `RecorderLogoutAction` — **client-thread-only, fail-loud off-thread** | `recorder/session/RecorderLogoutAction.java` (2B.1.a) |
| 5 | SessionShape budget | `Long.MAX_VALUE` OK for Tier 1; Phase 0B blocks v1.0-lock | `RecorderPlugin.launchCowKillerPilot()` (2B.1.b) + 2D playbook documents it |

---

## Self-review

- [x] Plan size within `feedback_compact_plans` 300-700 budget (this is ~580 lines including tables).
- [x] No full Java sources pasted — pseudocode + behavioral specs only.
- [x] APIs verified before referencing: `SequenceManager.run(Step)` at `SequenceManager.java:116`; `SequenceManager.stop()` at `:119`; `SequenceManager.setScheduler(...)` at `:114`; `LogoutHelper.tryLogout()` blocking pattern at `LogoutHelper.java:95-107`; `LogoutAction` interface contract at `LogoutAction.java:50-57`; `buildV21Navigator` signature at `RecorderPlugin.java:823`; `RecorderConfig.cowKillerPilotEnabled` does not yet exist (this slice adds it).
- [x] Decision-only — no implementation, no TDD tasks, no Bash commands run as part of the plan itself.
- [x] Five decisions explicitly answered + grounded in the user's preferred directions.
- [x] Slicing rationale stated; not arbitrary.
- [x] 2C carry-forward enumerated, 2D unchanged.
- [x] Risks list non-trivial — covers the actual threading + contention pitfalls.
- [x] No "convenience tier" in architecture (`feedback_no_convenience_in_architecture`) — RecorderLogoutAction does NOT wrap LogoutHelper; it is the proper observation-only impl.
- [x] No "human-like / evasion" framing (`feedback_no_evasion_framing`) — pilot framing is route robustness + operator-supervised validation.
- [x] No CLAUDE.md edits — script-side import rules unchanged.
- [x] Compact-plan budget honored.

---

## Stop and wait

This plan is **review-only**. Do not begin Slice 2B.1.a until the operator green-lights it.
