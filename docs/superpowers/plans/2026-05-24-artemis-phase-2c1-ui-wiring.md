# Artemis Phase 2C.1 — Cow Killer Pilot UI Wiring Plan

> **For agentic workers:** This is a decision-and-design plan only. **Do not begin implementation until the operator approves it.** Implementation, when approved, follows `superpowers:executing-plans`.

**Goal:** Expose `RecorderPlugin.launchCowKillerPilot()` / `stopCowKillerPilot()` through a test-only UI surface in `RecorderPanel`, gated behind a single new `@ConfigItem boolean` on `RecorderConfig`. **Pure UI wiring** — no runtime-behavior changes, no Step / Artemis / DynamicStep edits, no allow-list changes.

**Architecture:** Add `cowKillerPilotEnabled` config flag (default `false`) on `RecorderConfig`. Add Start + Stop buttons + a status label to `RecorderPanel`, with **config gating only the Start button — Stop stays available whenever the pilot is running**, regardless of config state. Buttons schedule the existing plugin launch/stop methods via `clientThread.invoke(...)` so construction work runs on the client thread (eliminates the EDT-vs-Client-thread question entirely; the button handler returns immediately). State refresh hooks into the existing 500 ms `refreshTimer` (`RecorderPanel.java:481`) via a new private `updateCowKillerPilotControls()` method called from `refresh()`.

**Tech Stack:** Java 17, RuneLite `@ConfigItem` (existing pattern across 20+ items in `RecorderConfig`), Swing `JButton` + `JLabel` (existing pattern across `RecorderPanel`), no new test framework or library.

**Scope (in):** One new `@ConfigItem` on `RecorderConfig`, one new public accessor on `RecorderPlugin`, three new fields (Start button, Stop button, status label) on `RecorderPanel`, one new private `updateCowKillerPilotControls()` method on the panel, one new wiring setter `setCowKillerPilotControls(...)` on the panel, plugin `startUp()` adds one call to wire the controls.

**Scope (out):** Any change to `CowKillerScript`, `DynamicStep`, `FailStep`, Artemis interface, ArtemisImpl, V21Navigator, RecorderLogoutAction, SessionShape, NamedZone, the launch/stop method bodies, the grep allow-list, CLAUDE.md, the Phase 2D playbook. No new tests (Swing wiring; manual smoke per existing-script precedent).

---

## 1. Config gate

**Decision:** **One new `@ConfigItem boolean cowKillerPilotEnabled`** on `RecorderConfig`, default `false`. Lives in a new tiny `@ConfigSection("Cow Killer pilot (test)")` so the test-only nature is obvious in the RuneLite plugin settings UI.

Exact shape:

```java
@ConfigSection(
    name = "Cow Killer pilot (test)",
    description = "Phase 2 Artemis cow-killer pilot — test-only launch surface. "
        + "Do not enable on production accounts.",
    position = <next after the last existing section>
)
String cowKillerPilotSection = "cowKillerPilotSection";

@ConfigItem(
    keyName = "cowKillerPilotEnabled",
    name = "Enable Cow Killer pilot (test)",
    description = "When on, the recorder panel shows Start/Stop controls for the "
        + "Phase 2 Artemis cow-killer pilot. Pilot is test-only — do not enable on "
        + "production accounts. Default off.",
    section = "cowKillerPilotSection",
    position = 1
)
default boolean cowKillerPilotEnabled()
{
    return false;
}
```

**Why a dedicated section:**
- The flag's test-only nature is structural, not editorial. A standalone section means a future operator can't conflate it with general overlay/recording config.
- All existing `RecorderConfig` sections (`@ConfigSection` at `:116` "Trail overlay", `:148` "Chicken overlay") use named sections. The plugin manager UI groups them; an unsectioned item appears at the top, which would draw attention before the user has been warned this is test-only.

**No new sub-items.** YAGNI for any additional knobs (run length, query overrides, etc.) — those land if a real need surfaces during Tier 1.

**Wording locked:**
- Name: `"Enable Cow Killer pilot (test)"`.
- Section name: `"Cow Killer pilot (test)"`.
- Description: explicit "test-only" + "do not enable on production accounts."
- No mention of "developer," "burner," "bot," or any production-facing framing. Adheres to `feedback_no_evasion_framing`.

---

## 2. Panel UI shape

### Controls

Three new fields on `RecorderPanel`:

```java
private final JButton cowKillerStartBtn = new JButton("Start Cow Killer (pilot)");
private final JButton cowKillerStopBtn  = new JButton("Stop Cow Killer (pilot)");
private final JLabel  cowKillerStatusLabel = new JLabel("Pilot: idle");
```

Plus a container layout following the V3 pattern (`RecorderPanel.java:1666-1670`):

```java
JPanel cowKillerBox = new JPanel();
cowKillerBox.setLayout(new BoxLayout(cowKillerBox, BoxLayout.Y_AXIS));
cowKillerBox.setBorder(BorderFactory.createTitledBorder("Cow Killer pilot (test)"));
cowKillerBox.setBackground(ColorScheme.DARK_GRAY_COLOR);
cowKillerBox.add(cowKillerStatusLabel);
JPanel cowKillerRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
cowKillerRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
cowKillerRow.add(cowKillerStartBtn);
cowKillerRow.add(cowKillerStopBtn);
cowKillerBox.add(cowKillerRow);
```

### Placement

**End of the existing V3 / scripts area.** Don't interleave with V3's own controls. The V3 block ends around `RecorderPanel.java:1672-1690` (Start/Stop + debug checkboxes). Insert the Cow Killer box immediately after — same tab, same vertical flow.

### Disabled/hidden behavior

**`setEnabled(false)` rather than `setVisible(false)`.** Reasons:
- Operator can see the controls exist (and the section's "test" label) even with the flag off — discoverability without temptation.
- Visibility changes shift the panel layout, which can be jarring across config toggles.
- Matches the V3 pattern (`v3StartBtn.setEnabled(ready)` at `:1886`), which uses enabled-state to gate, not visibility.

### Button + status label state table

**Config gates Start, NOT Stop.** Stop must remain available whenever a pilot is running so an operator can always halt it via the UI — regardless of whether the config flag is on or off.

| Config flag | Pilot running | Start | Stop | Status label |
|---|---|---|---|---|
| off | no | **disabled** | **disabled** | `"Pilot: disabled in config"` |
| on | no | **enabled** | **disabled** | `"Pilot: idle"` |
| on | yes | **disabled** | **enabled** | `"Pilot: running"` |
| off | yes | **disabled** | **enabled** | `"Pilot: running (config disabled — Stop still available)"` |
| (last launch attempt threw) | — | (per row above) | (per row above) | `"Pilot: launch failed (see log)"` — overwritten on next refresh tick |

**Logic:**
- `cowKillerStartBtn.setEnabled(config && !running)`
- `cowKillerStopBtn.setEnabled(running)`  ← **NOT gated by config**

State is computed inside the new `updateCowKillerPilotControls()` method, called from the existing `refresh()` driven by the 500 ms `refreshTimer` (`RecorderPanel.java:481`).

### Config-change refresh

**No `@Subscribe ConfigChanged` listener needed.** The 500 ms `refreshTimer` already polls state. A config toggle takes effect within 500 ms — acceptable for a test-only UI. No new event subscription.

---

## 3. Threading / EDT behavior

**Decision:** **Marshal both launch and stop through `clientThread.invoke(...)`** so the construction work runs on the client thread, never on EDT. The button handler queues the call and returns immediately; ~1 tick later the launch/stop runs.

**Why this is safer than direct EDT** (revised from the prior draft):

The launch method's construction includes `new SessionShape(() -> client.getTickCount(), () -> client.getTickCount(), Long.MAX_VALUE)`, which captures `sessionStartTick = tickSupplier.getAsLong()` at construction time (`SessionShape.java:55`). That calls `client.getTickCount()` synchronously **inside the SessionShape constructor**. Whether that specific Client method is safe from EDT is undocumented in the RuneLite API surface, and the V3 pattern (`RecorderPanel.java:1671-1672`) doesn't directly evidence safety for `getTickCount` specifically.

Rather than argue "is `getTickCount()` EDT-safe in practice across all RuneLite versions," we marshal the entire launch/stop to the client thread. Then every Client read inside the method body is guaranteed to be on the correct thread — including `client.getTickCount()`, including any future Client read someone adds to `launchCowKillerPilot()` later.

### Implementation shape

The plugin's wiring lambdas are constructed in `startUp()` to do the marshaling:

```java
panel.setCowKillerPilotControls(
    () -> clientThread.invoke(this::launchCowKillerPilot),
    () -> clientThread.invoke(this::stopCowKillerPilot),
    this::isCowKillerPilotRunning,
    config::cowKillerPilotEnabled);
```

The panel calls these lambdas from the EDT actionListener; the lambdas marshal to the client thread before invoking the heavy work.

**Why plugin-side, not panel-side:** the panel stays threading-agnostic — it holds four `Runnable` / `BooleanSupplier` lambdas and trusts them. The plugin owns the threading model.

### What runs on which thread

| Code | Thread |
|---|---|
| EDT button click → calls the marshaling lambda | EDT |
| `clientThread.invoke(...)` queues the call, returns immediately | EDT |
| `launchCowKillerPilot()` body (locals, ArtemisDeps, ArtemisImpl, CowKillerScript, script.plan(), SequenceManager construction, mgr.run()) | **Client thread** |
| `stopCowKillerPilot()` body (mgr.stop, nav.cancel, io.release, field nulling) | **Client thread** |
| `mgr.run(root)`'s scheduler-marshalled `engine.start(root)` | Client thread (already marshalled by SequenceManager) |
| Engine ticks via `onGameTick` → `advancePilotEngineOnNextClientTick` | Client thread |

**No engine work, no Step lifecycle, and no Client read runs from EDT.** The button click returns within microseconds; observable Tier 1 latency is the next client tick (≤600 ms).

### `isCowKillerPilotRunning()` from EDT

The accessor is a single field read:
```java
public boolean isCowKillerPilotRunning() { return pilotSequenceManager != null; }
```

Read from EDT by the panel's refresh tick; written from the client thread by launch/stop. Worst-case race is a one-tick stale boolean (cosmetic only). No marshaling needed for the accessor itself.

### What `clientThread.invoke` is NOT used for here

- We do NOT marshal `isCowKillerPilotRunning()` — it's a field read.
- We do NOT marshal `updateCowKillerPilotControls()` — it's a Swing UI update that runs on EDT and must stay there.

---

## 4. Double-start / double-stop behavior

### Double-start protection — plugin already guards

`launchCowKillerPilot()` already has the guard (`RecorderPlugin.java` near the top of the method body):
```java
if (pilotSequenceManager != null)
{
    log.warn("cow-killer pilot launch ignored — a run is already in flight");
    return;
}
```

### Panel-level cosmetic guard

To avoid warn-log spam and a stale status label, the panel disables `cowKillerStartBtn` when a run is in flight. **Stop is enabled whenever a pilot is running, regardless of config** — the safety rule from §2:

```java
boolean configOn = cowKillerIsConfigEnabled.getAsBoolean();
boolean running  = cowKillerIsRunning.getAsBoolean();
cowKillerStartBtn.setEnabled(configOn && !running);
cowKillerStopBtn.setEnabled(running);          // NOT gated by config
```

The button enabled-state is a UI hint; the plugin's own launch guard is still authoritative.

### `isCowKillerPilotRunning()` accessor

**New public method on `RecorderPlugin`:**

```java
public boolean isCowKillerPilotRunning()
{
    return pilotSequenceManager != null;
}
```

Returns the field's current null-state. **Does NOT expose SequenceManager itself** — just a boolean. No mutable reference leaks.

Threading: callable from EDT (the panel's `updateCowKillerPilotControls()`). The `pilotSequenceManager` field is read-then-compared — no IO. The only mutator is `launchCowKillerPilot()` / `stopCowKillerPilot()`, both also EDT-or-shutDown-thread. Some interleaving is theoretically possible across threads (panel reads, shutDown nulls) but the worst case is a one-tick stale boolean — harmless cosmetics.

### Double-stop safety

`stopCowKillerPilot()` is already idempotent via per-field null-guards. A second click on Stop while not running is a no-op. The panel-level guard above also disables the button after a successful stop.

### Status label honesty

The status label NEVER claims "running" when `pilotSequenceManager == null`. It NEVER claims "idle" when `pilotSequenceManager != null`. State derivation is purely from `(config flag, isCowKillerPilotRunning())` per the table in §2.

---

## 5. Error handling

**Decision (revised):** **The try/catch must live INSIDE the marshaled lambda**, on the client thread where launch/stop actually run. The EDT button handler can also catch RuntimeException from the Runnable itself (i.e. exceptions thrown by `clientThread.invoke(...)` synchronously), but it cannot catch exceptions thrown *later* inside the queued client-thread Runnable.

### Why the prior draft was wrong

The prior draft put the try/catch in the EDT actionListener around `cowKillerLaunch.run()`. With the marshaling fix from §3, `cowKillerLaunch` is now `() -> clientThread.invoke(this::launchCowKillerPilot)` — the EDT body returns as soon as `invoke(...)` queues the call. Any exception thrown later inside `launchCowKillerPilot()` lands on the client thread, not on EDT. The EDT try/catch sees nothing.

### Correct shape — try/catch inside the marshaled lambda

The plugin's wiring (in `startUp()`) becomes:

```java
panel.setCowKillerPilotControls(
    () -> clientThread.invoke(() -> {
        try {
            launchCowKillerPilot();
        } catch (RuntimeException ex) {
            log.warn("cow-killer pilot launch threw", ex);
        }
    }),
    () -> clientThread.invoke(() -> {
        try {
            stopCowKillerPilot();
        } catch (RuntimeException ex) {
            log.warn("cow-killer pilot stop threw", ex);
        }
    }),
    this::isCowKillerPilotRunning,
    config::cowKillerPilotEnabled);
```

### Defensive EDT-side try/catch (belt-and-suspenders)

The panel's actionListener still wraps the lambda call in a try/catch (RuntimeException) — this catches exceptions thrown *synchronously* by `clientThread.invoke(...)` itself (e.g. NPE if `clientThread` is null in a misconfigured plugin). It does NOT catch the later launch/stop body exceptions; that's the marshaled lambda's job. The EDT try/catch's only purpose is to prevent a Swing "Internal Error" dialog if the scheduling step itself throws:

```java
cowKillerStartBtn.addActionListener(e -> {
    if (cowKillerLaunch == null) return;  // panel not wired yet
    try {
        cowKillerLaunch.run();
    } catch (RuntimeException ex) {
        log.warn("cow-killer pilot Start scheduling threw", ex);
        cowKillerStatusLabel.setText("Pilot: launch failed (see log)");
    }
});
```

Stop button mirrors the pattern.

### Failure visibility in the status label

The marshaled lambda's catch logs the exception but **does not directly update the status label** — Swing UI mutations must run on EDT, and the catch is on the client thread. Two ways to surface the failure to the operator:

1. **Refresh-tick re-derivation** (preferred, matches existing pattern). After a launch failure, `pilotSequenceManager` remains null (the launch method's own internal cleanup nulls fields before throwing). The next 500 ms refresh tick reads `isCowKillerPilotRunning() == false` and re-derives the label as `"Pilot: idle"` (or `"Pilot: disabled in config"`). The failure is visible in the log; the status label returns to a true state.
2. **Failure-pinning field** (not adopted). We could add a `volatile boolean lastLaunchFailed` field, set from the marshaled lambda, read from EDT in `updateCowKillerPilotControls()`. Adds state-machine complexity for a transient signal that's already in the log. YAGNI for 2C.1.

**Decision:** option 1. The status label after a failed launch returns to `"Pilot: idle"` within 500 ms; the operator sees the warn in `client.log` and can retry. No new state field. The defensive EDT-side label update on a scheduling exception (above) covers the rare case where the failure happens before the marshaling queue even accepts the work.

### Why this still beats no error handling

- `launchCowKillerPilot()` internally try/catches `script.plan()` (Phase 2B.1.b) — but Phase 2C makes `plan()` return a real Step, so that internal catch is mostly dead code today. The *outer* construction (dispatcher, navigator, SessionShape, ArtemisDeps, SequenceManager.withDefaults + setters + run) is still uncaught inside `launchCowKillerPilot()` itself. The marshaled lambda's try/catch is the final safety net.
- The existing V3 pattern at `RecorderPanel.java:1671-1672` doesn't catch at all — but V3's `start()` doesn't do per-launch object construction (the script is held as a long-lived field). Different cost profile, so we explicitly catch.

---

## 6. Scope boundaries

**Files to touch (3):**

| Path | Change | Estimated size |
|---|---|---|
| `runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderConfig.java` | One `@ConfigSection` declaration + one `@ConfigItem boolean cowKillerPilotEnabled` | +20 LOC |
| `runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPanel.java` | 3 new fields (2 buttons, 1 label), 1 new wiring setter, 1 new private `updateCowKillerPilotControls()`, 1 line in existing `refresh()` (or equivalent), ~20 lines of UI layout | +60 LOC |
| `runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPlugin.java` | 1 new public `isCowKillerPilotRunning()` accessor; 1 line in `startUp()` to call `panel.setCowKillerPilotControls(...)` | +12 LOC |

**Total: ~92 LOC across 3 files. No new files.**

**Files NOT to touch in 2C.1:**

- `CowKillerScript.java` — Phase 2C is final.
- `DynamicStep.java`, `FailStep.java`, all of `sequence/composite/*` — Phase 1C is final.
- `Artemis.java`, `ArtemisImpl.java`, `ArtemisDeps.java`, all of `sequence/artemis/*` — Phase 1A is final.
- `V21Navigator.java`, anything under `recorder/nav/*` — engine code.
- `RecorderLogoutAction.java` — Phase 2B.1.a is final.
- `SessionShape.java`, `AccountRng.java` — Phase 0A.
- `NamedZone.java`, `NamedZoneTest.java` — Phase 2A.
- The `launchCowKillerPilot()` / `stopCowKillerPilot()` / `cleanupPilotLocals()` method bodies in `RecorderPlugin.java`. We only ADD `isCowKillerPilotRunning()` plus the `startUp()` wiring call.
- The grep gate allow-list. `RecorderPanel.java` lives at `recorder/RecorderPanel.java`, outside `recorder/scripts/` — not scanned.

---

## 7. Tests / checks

### Compile

```
:client:compileJava → BUILD SUCCESSFUL
```

### Grep gate

```
./scripts/check-no-direct-engine-reaches.sh → exit 0
OK: scanned 16 files, 107 allow-list entries (unchanged)
```

Verifies the script-side untouched.

### Unit tests

**None.** Swing + plugin wiring matches the existing-scripts precedent (`feedback_no_tests_for_bot_scripts`); the V3 panel buttons + V3 status refresh have zero unit tests today and the cow-killer wiring is the same shape.

If a future test-writing slice wants coverage, the candidates are:
- Headless JFC test driving `cowKillerStartBtn.doClick()` and asserting the launch lambda fired — overkill for one button.
- Plugin-level reflection test asserting `isCowKillerPilotRunning()` flips around `launchCowKillerPilot()` — duplicates the field-presence assertion the compiler already gives.

Deferred; not in 2C.1.

### Manual smoke checklist

The operator runs through these in dev-mode RuneLite after enabling the plugin:

1. **Config flag off (default).**
   - Open the Recorder side panel.
   - Cow Killer pilot section visible.
   - Start + Stop buttons present, both disabled (greyed out).
   - Status label reads `"Pilot: disabled in config"`.
2. **Toggle config flag on** via the RuneLite plugin settings panel.
   - Within ~500 ms (one refresh tick), Start button enables, Stop stays disabled.
   - Status label flips to `"Pilot: idle"`.
3. **Click Start.**
   - No exception dialog.
   - Within ~500 ms, Start disables, Stop enables.
   - Status label flips to `"Pilot: running"`.
   - `client.log` shows pilot StepEvents (walkTo, then DynamicStep ticks).
4. **Click Start again** (the panel may or may not have disabled it in time depending on refresh timing).
   - Plugin logs `"cow-killer pilot launch ignored — a run is already in flight"` from the existing guard.
   - No state regression.
5. **Click Stop.**
   - Within ~500 ms, Stop disables, Start enables.
   - Status label flips back to `"Pilot: idle"`.
   - Pilot engine halts (no further StepEvents).
6. **Click Stop again** (button should be disabled, but doing it via accessibility or by mashing fast).
   - Plugin `stopCowKillerPilot()` runs but all null-guards no-op.
   - No log spam, no exception.
7. **Toggle config flag off while pilot is running.**
   - **Start button disables** (config is the gate for Start).
   - **Stop button stays enabled** (config does NOT gate Stop — operator must always be able to halt the pilot from the UI).
   - Status label flips to `"Pilot: running (config disabled — Stop still available)"`.
   - The pilot continues running until the operator clicks Stop (or `shutDown()` fires). Config gates the Start trigger, not the run itself.
7a. **Click Stop while config is off and pilot is running.**
   - `clientThread.invoke(this::stopCowKillerPilot)` queues; runs ~1 tick later.
   - Within the next 500 ms refresh tick, Stop disables, Start stays disabled (config still off), status flips to `"Pilot: disabled in config"`.
   - Confirms the safety rule: config-off does not strand a running pilot in an un-stoppable state.
8. **Disable the recorder plugin via the plugin manager.**
   - `shutDown()` fires.
   - `stopCowKillerPilot()` runs defensively (existing 2B.1.b behavior).
   - No exception, no thread leak.

If any of 1-8 fails, stop and report — don't paper over with hot-fixes.

---

## 8. Implementation boundary

### Fields to add (RecorderPanel)

```java
private final JButton cowKillerStartBtn = new JButton("Start Cow Killer (pilot)");
private final JButton cowKillerStopBtn  = new JButton("Stop Cow Killer (pilot)");
private final JLabel  cowKillerStatusLabel = new JLabel("Pilot: disabled in config");
@Nullable private Runnable cowKillerLaunch;       // set by setCowKillerPilotControls
@Nullable private Runnable cowKillerStop;
@Nullable private java.util.function.BooleanSupplier cowKillerIsRunning;
```

Plus the `RecorderConfig config` reference if the panel doesn't already hold one. Inspection: `RecorderPanel`'s constructor takes `(manager, client, clientThread)` per `RecorderPlugin.java:285`. **Does NOT take config today.** Either:

- (a) Widen the constructor to take `RecorderConfig config` — touches the construction site. Mechanical.
- (b) Use the `Runnable launch / Runnable stop / BooleanSupplier isRunning` triple AND inject a fourth `BooleanSupplier isConfigEnabled` (lambda `() -> config.cowKillerPilotEnabled()`) so the panel never sees the config object directly.

**Decision: option (b).** Panel stays config-agnostic; plugin owns the config-read; clean separation. Four lambdas + one setter:

```java
public void setCowKillerPilotControls(Runnable launch, Runnable stop,
    BooleanSupplier isRunning, BooleanSupplier isConfigEnabled)
{
    this.cowKillerLaunch        = launch;
    this.cowKillerStop          = stop;
    this.cowKillerIsRunning     = isRunning;
    this.cowKillerIsConfigEnabled = isConfigEnabled;
}
```

(Plus the new `cowKillerIsConfigEnabled` field.)

### Methods to add (RecorderPanel)

- Layout block in the panel's existing UI-building method (same place V3 controls are built). ~12 lines.
- ActionListener attachments (Start + Stop) with try/catch. ~12 lines.
- `private void updateCowKillerPilotControls()` reading the four lambdas + status-label table per §2. ~20 lines.
- One-line call from `refresh()` to `updateCowKillerPilotControls()`.

### Methods to add (RecorderPlugin)

```java
public boolean isCowKillerPilotRunning()
{
    return pilotSequenceManager != null;
}
```

### Wiring call (RecorderPlugin.startUp)

After existing `panel = new RecorderPanel(manager, client, clientThread);` (line ~285):

```java
panel.setCowKillerPilotControls(
    () -> clientThread.invoke(() -> {
        try { launchCowKillerPilot(); }
        catch (RuntimeException ex) { log.warn("cow-killer pilot launch threw", ex); }
    }),
    () -> clientThread.invoke(() -> {
        try { stopCowKillerPilot(); }
        catch (RuntimeException ex) { log.warn("cow-killer pilot stop threw", ex); }
    }),
    this::isCowKillerPilotRunning,
    config::cowKillerPilotEnabled);
```

The two `Runnable` lambdas marshal launch/stop to the client thread with a try/catch on the client-thread side (§5 — the EDT try/catch can't catch later exceptions on a different thread). The two `BooleanSupplier` lambdas are direct (cheap field reads).

### Manual smoke checklist (recap)

See §7 — 8 steps. Operator executes against dev-mode RuneLite after the commit.

### Proposed commit message

```
feat(artemis): Phase 2C.1 — UI wiring for cow-killer pilot (test-only @ConfigItem gate + panel controls)

Exposes RecorderPlugin.launchCowKillerPilot() / stopCowKillerPilot()
through a test-only UI surface on RecorderPanel, gated by a new
@ConfigItem boolean.

RecorderConfig:
- Adds @ConfigSection "Cow Killer pilot (test)" and one
  @ConfigItem boolean cowKillerPilotEnabled (default false).
- Description explicitly states test-only + "do not enable on
  production accounts."

RecorderPanel:
- Adds Start / Stop buttons + status label in a titled box, placed
  after the existing V3 controls in the scripts area.
- Buttons use setEnabled() gating (not setVisible) — controls stay
  discoverable when the config is off, just greyed out.
- **Config gates Start, NOT Stop.** Stop stays available whenever the
  pilot is running so the operator can always halt it via the UI:
    config off, idle        → Start disabled, Stop disabled,
                              "Pilot: disabled in config"
    config on,  idle        → Start enabled,  Stop disabled,
                              "Pilot: idle"
    config on,  running     → Start disabled, Stop enabled,
                              "Pilot: running"
    config off, running     → Start disabled, Stop enabled,
                              "Pilot: running (config disabled —
                               Stop still available)"
    last launch threw       → "Pilot: launch failed (see log)"
- New private updateCowKillerPilotControls() hooks into the existing
  500 ms refreshTimer; no @Subscribe ConfigChanged needed.
- New setCowKillerPilotControls(launch, stop, isRunning, isConfigEnabled)
  takes four lambdas — panel stays config-agnostic; plugin owns the
  config read.
- Exception handling lives INSIDE the client-thread marshaled lambda
  (where launch/stop actually run); EDT-side try/catch in the panel
  action listener covers only the rare case where clientThread.invoke
  itself throws synchronously. A failed launch leaves
  pilotSequenceManager null, so the next 500 ms refresh tick
  re-derives the status label as "Pilot: idle".

RecorderPlugin:
- Adds public boolean isCowKillerPilotRunning() — returns
  pilotSequenceManager != null. Does NOT expose SequenceManager
  itself.
- startUp() calls panel.setCowKillerPilotControls(...) with the four
  lambdas right after panel construction.

Threading:
- Both launch and stop are wrapped in clientThread.invoke(...) at
  wiring time. Button click on EDT queues the marshaling lambda;
  the actual work runs on the client thread ≤1 tick later. Eliminates
  the EDT-vs-Client-thread question for any Client reads inside
  launchCowKillerPilot() (notably SessionShape's sessionStartTick
  capture via client.getTickCount() at SessionShape.java:55).
- No engine ticks, Step lifecycle, dispatcher work, or Client reads
  run from EDT.

Known behavior — config off while running:
- Toggling the config off while a pilot is running disables Start
  but leaves Stop enabled. Status flips to "Pilot: running (config
  disabled — Stop still available)". The pilot continues until the
  operator clicks Stop. Config gates the Start trigger, not the
  run itself.

Files:
- runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderConfig.java
- runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPanel.java
- runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPlugin.java

No new tests — Swing/plugin wiring follows existing-script precedent
(per feedback_no_tests_for_bot_scripts). Manual smoke checklist
in docs/superpowers/plans/2026-05-24-artemis-phase-2c1-ui-wiring.md §7.

Grep gate unchanged: 16 files, 107 allow-list entries.

Plan: docs/superpowers/plans/2026-05-24-artemis-phase-2c1-ui-wiring.md
Next: Phase 2D — operator session protocol + Tier 1 acceptance run.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
```

---

## 9. Risks

| Risk | Probability | Mitigation |
|---|---|---|
| Operator forgets to disable the config flag → pilot accidentally runs on a production account | Medium (real concern given the slice's whole point is making it clickable) | Config flag default `false`; section title and description both say "test"; Start button label says "(pilot)"; status label says "Pilot: …". Multiple textual reminders before a click. Cannot prevent willful misuse. |
| Config toggle takes up to 500 ms to refresh button state | Low — acceptable for a test-only UI | Documented in §7 smoke step 2. If the lag is observed as a Tier 1 friction point, add a `@Subscribe ConfigChanged` handler in a follow-up. |
| `isCowKillerPilotRunning()` returns stale value if read mid-state-transition | Very low — field read is atomic-ish, refresh-tick re-derives state every 500 ms | Worst case: status label is one tick wrong. Cosmetic only. The plugin's own launch/stop guards are authoritative. |
| **Config off while pilot is running → Stop button disabled → operator can't halt the pilot via UI** | **Eliminated by §2 button rules: Stop is gated by `running`, NOT by `config`.** | Smoke step 7 + 7a verify the operator can always Stop while a pilot is running. |
| `client.getTickCount()` (or another Client read) from EDT inside `launchCowKillerPilot()` returns wrong value or throws | Eliminated by `clientThread.invoke(...)` marshaling | All launch/stop work runs on the client thread; no Client reads land on EDT. |
| Marshaled launch adds latency the operator notices on Start click | Low — ≤1 client tick (600 ms) before launch begins; comparable to existing V3 behavior | Acceptable for a test-only UI. If observation shows it's confusing, add a "Pilot: starting…" intermediate status label state. |
| Panel widens constructor or pulls config in unexpected ways | Eliminated by lambda-triple setter — panel takes lambdas, not RecorderConfig | Implementation must use the `setCowKillerPilotControls(...)` shape; no widening of RecorderPanel's constructor signature. |
| Tier 1 surfaces a real runtime bug | Medium — that's what Tier 1 IS for | Out of scope for 2C.1. Tier 1 is a 2D concern. |

---

## 10. Slicing — single commit

**Decision:** **One commit.** 2C.1 is small (3 files, ~92 LOC, no tests). Splitting RecorderConfig + RecorderPanel + RecorderPlugin into separate commits would land intermediate states where the config exists with no UI binding, or vice versa — each intermediate is uninteresting and reviewable only together.

---

## Self-review

- [x] Plan size within `feedback_compact_plans` 300-700 budget (~520 lines including tables and code blocks).
- [x] No full Java sources pasted — small code blocks for shape, behavioral specs everywhere else.
- [x] APIs verified before referencing: `RecorderPanel.java:1671-1672` V3 actionListener pattern; `:481` refreshTimer; `:1886` v3StartBtn.setEnabled pattern; `:1883` updateV3Controls; `RecorderConfig.java:116, 148` existing @ConfigSection pattern; `RecorderPlugin.java` launch/stop/`pilotSequenceManager` field from Phase 2B.1.b; `SequenceManager.java:116` run() scheduler pattern.
- [x] No new runtime behavior — pure UI wiring. Step / Artemis / DynamicStep / Navigator / LogoutAction / SessionShape / engine code untouched.
- [x] No new tests required — Swing wiring per existing-script precedent.
- [x] Test-only framing in the config wording (no "developer," "burner," "bot," etc., per `feedback_no_evasion_framing`).
- [x] Config flag default false; controls disabled (not invisible) when flag is off — discoverable but inert.
- [x] Threading verified — button handlers marshal launch/stop to the client thread via `clientThread.invoke(...)`; no launch/stop body runs on EDT. Try/catch lives inside the marshaled lambda where exceptions actually surface, not in the EDT action listener.
- [x] `isCowKillerPilotRunning()` is the only new public plugin accessor; does NOT expose SequenceManager.
- [x] Double-start guard: plugin already has it; panel adds a cosmetic enabled-state guard.
- [x] Error handling: try/catch RuntimeException in button handlers; status-label feedback.
- [x] Grep gate unchanged.
- [x] CLAUDE.md untouched.
- [x] No additions to spec §14 allowed-import list.
- [x] Phase 2D playbook untouched.

---

## Stop and wait

This plan is review-only. Do not begin Phase 2C.1 implementation until the operator green-lights it.
