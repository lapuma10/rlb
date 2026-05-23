# Gameplay recorder — bot-watch extension

**Date:** 2026-05-23
**Status:** design — pending user review before any implementation
**Scope:** extend the existing `recorder/` infrastructure with mode tagging, bot-watch dispatcher correlation, unified click attribution, and bot-watch-specific reports. No replay engine. No ML. No runtime behaviour change to the dispatcher or scripts.

## Purpose

Diagnostic telemetry. Capture per-session JSONL streams of every gameplay session (manual or scripted) so an engineer (or me) can audit dispatcher correctness, script state machines, and per-target click behaviour offline. Two specific use cases motivating this:

1. **Dispatcher correctness verification.** Given a script said "click NPC 25085 with verb Bank", did the dispatched OS click land where the engine intended, was the menu it produced what was expected, and did the resulting game state reflect the success the script claims? Today this is opaque; the dispatcher logs to `client.log` in free text and the script logs separately.
2. **Comparison of scripted vs manual sessions for engineering review.** Given an identical activity performed manually and then by a script, surface where the two diverge along measurable axes (click-target distribution, hold-duration distribution, inter-action timing, camera usage). Output is markdown reports for human review.

The recorder is local-only, read-only against game state, and fail-open: a recorder bug must never affect the running client or scripts.

Framing constraint per project conventions: this is engineering diagnostics for stability and correctness review, not anti-detection tooling. No client-spoofing, no input synthesis, no network output.

## Out of scope

Explicitly **not** part of this spec, deferred to separate future specs:

- Runtime replay of recorded paths from the dispatcher.
- Any machine-learning model fitted to recorded data.
- Any change to `HumanizedInputDispatcher` behaviour. The single observability hook in M3 only emits events; the dispatcher's input synthesis is unchanged.
- Any change to existing recorder packages' behaviour. We *extend* `events/`, add new files in `capture/`, and add new reports in `analyse/`.
- Sending data anywhere off-disk.

## What already exists (do not rebuild)

Inventoried from `runelite-client/src/main/java/net/runelite/client/plugins/recorder/`:

| Existing | Where | What it does today |
|---|---|---|
| Plugin lifecycle | `RecorderPlugin`, `RecorderManager` | Start/stop a recording session, wire all capture components, flush daemon, run analysis pipeline at stop. |
| Session model | `session/RecordingSession`, `session/SessionDirectory` | Timestamped session dirs, meta.json manifest, intent label. |
| Login + script tracking | `session/SessionTracker`, `session/ScriptLifecycleListener`, `session/ScriptRun` | Tracks LOGGED_IN/LOGIN_SCREEN transitions, polls for script start/stop at 1 s cadence, persists `LoginSession` graphs to `~/.runelite/recorder/sessions/<account>/<date>.json`. |
| Mouse capture | `capture/MouseCapture` | AWT `MouseAdapter`+wheel listener; mousePressed/Released/Moved/Dragged/Wheel events. Optional Hz downsample. Buffer is volatile. |
| Camera capture | `capture/CameraSampler` | Threshold sampler emitting on yaw/pitch/zoom delta over config thresholds. Per game-tick call. |
| Key capture | `capture/KeyCapture` | AWT `KeyListener`; keyPressed / keyReleased with modifiers. |
| Focus capture | `capture/FocusCapture` | AWT `WindowFocusListener`. |
| Event-bus capture | `capture/EventBusCapture` | `@Subscribe` for GameTick, MenuOpened, MenuOptionClicked, ChatMessage, ItemContainerChanged, StatChanged. |
| Click resolution | `capture/ClickResolver`, `capture/NearbyResolver` | Categorises `MenuOptionClicked` as widget_click vs world_click with kind (npc/player/object/ground/minimap_walk/walk_dest); separately emits a `Nearby` event listing candidates within 12 tiles. |
| Event types | `events/Events` (sealed record union), `events/RecordedEvent` (sealed interface), `events/EventCodec` | 19 existing event records, JSONL serialisation via Gson. Adding new event types is documented as: add a record under `Events`, mark in the sealed permits, codec handles it automatically. |
| Storage | Session dir contains `events.jsonl.gz`, `meta.json`, `phases.json`, `summary.md`, `recording.html`. | `JsonlGzipWriter` (referenced by `RecorderManager`) does the streaming gzip. |
| Analysis | `analyse/PhaseSegmenter`, `analyse/SummaryGenerator`, `analyse/HtmlViewerGenerator` | Phases on Marker events or 25-tick idle; markdown summary; self-contained HTML scrubber. |

What's missing for the use cases above:

- **Mode tagging.** No event distinguishes "manual session" from "automated-input session". `SessionTracker` knows when scripts run, but it doesn't write a mode marker into `events.jsonl.gz`.
- **Auto-rollover on script transitions.** `RecorderManager.start()/stop()` appears to be hotkey-triggered. Manual sessions and script sessions therefore land in one combined dir with no boundary. (M1 will confirm via code-read; if `RecorderManager` is already always-on at login the change is smaller.)
- **Unified click event.** Today, attribution data is split: `MenuClick`, `WidgetClick` / `WorldClick`, plus a separate `Nearby` event. There's no single event carrying `{target, candidates[], confidence}` for downstream reports to consume cleanly.
- **No dispatcher correlation.** Nothing ties an OS click event to the `ActionRequest` the dispatcher fired. No `actionId` field on any existing event. No interface exists on `HumanizedInputDispatcher` for an outside listener to subscribe to action lifecycle.
- **No bot-watch-specific reports.** `SummaryGenerator` is generic. There's no repeated-cycle detector, no repeated-click-position detector, no manual-vs-bot diff.

The whole spec below is closing those gaps.

## Architecture — additions only

### Mode tagging (M1)

A new event type `Events.ScriptMode { String mode, String scriptId | null }`. Emitted by `SessionTracker` (which already implements `ScriptLifecycleListener`) on every transition:

- Session opens (login or recorder start) → emit `mode=live, scriptId=null`.
- Any script start → emit `mode=bot_watch, scriptId=<id>`.
- Any script stop → emit `mode=live, scriptId=null`.
- Session closes → no emission (the session manifest already records end).

Downstream consumers filter events by surrounding `mode` value.

Optional but recommended in M1: also emit a `RecordingSession` rollover at every mode change so each session dir is single-mode. That makes file-level grep + report filtering trivial. If `RecorderManager` is not currently auto-started on login, M1 fixes that as well (config flag `recorderAutoStart`, default off, to be flipped on via panel toggle when the operator is ready).

### Unified click attribution (M2)

A new event `Events.ClickResolved`:

```
ClickResolved {
    long seq, long tMs, int tick,
    int x, int y, String button,
    String mode,                  // copied from current ScriptMode
    Target target,                // best-confidence resolution
    List<Target> candidates,      // including target itself
    String menuVerb, String menuTopRow,
    long preClickHoverMs,
    String rawClickRef            // back-reference to existing MenuClick event seq
}

Target {
    String type,                  // inventory_item | widget | npc | game_object | ground_item | scene_tile | minimap_tile | unknown
    Integer id,                   // itemId, npcIndex, objectId, etc. — null if not applicable
    String name,
    int[] bounds,                 // [x0, y0, x1, y1] at click time
    Double relativeX, Double relativeY,   // (clickX - bounds.x0) / width, same for Y; null if bounds unavailable
    double confidence             // 0.0 - 1.0
}
```

`ClickResolved` is produced by a new `ClickAttributor` component that consumes the existing `MenuClick`, `WidgetClick`/`WorldClick`, and `Nearby` events, then synthesises one unified event with candidates and a confidence score. It runs on the worker writer thread, not the client thread — it reads only from already-recorded events, not live game state. Confidence rules are described in M2 below.

The existing per-component events (`MenuClick`, `WidgetClick`, `WorldClick`, `Nearby`) remain in place; `ClickResolved` is additive. Old reports keep working; new reports use `ClickResolved`.

### Dispatcher correlation (M3)

Two pieces:

1. **Observability hook on `HumanizedInputDispatcher`.** Add `DispatcherActionListener` interface with a no-op default and a single registration slot:
   ```
   interface DispatcherActionListener {
       default void onActionStarted(String actionId, ActionKind kind, Object intendedTarget, int intendedX, int intendedY) {}
       default void onActionFinished(String actionId, String result, int retries, long elapsedMs) {}
       default void onActionFailed(String actionId, String reason) {}
   }
   ```
   The dispatcher posts to the listener at action lifecycle boundaries. `actionId` is stable per `ActionRequest`. The listener must not block; the dispatcher wraps the call in `catch (Throwable)` and continues on any listener error. Behaviour of the dispatcher is unchanged.

2. **`BotWatchCorrelator`** registers as the listener, emits new events:
   ```
   ScriptActionStart { actionId, scriptId, scriptState, intentKind, intendedTarget, intendedX, intendedY }
   ScriptActionEnd   { actionId, result, durationMs, retries, clickResolvedRef }
   ScriptActionFail  { actionId, reason }
   ```
   `clickResolvedRef` is the seq of the `ClickResolved` event produced by the user-visible click that resulted from this action (if any). The correlator links them by time proximity (within 2 s of action start) and pixel proximity (click within the dispatched target bounds).

The two together let a single report answer "when the script wanted X, what did the OS / game actually do?".

### Inspector UI (M4)

New section in `RecorderPanel`: "Gameplay sessions". Reads only from disk, never from live state.

Pages:
1. **Session list** — filter by mode/script/date, show event counts, crashed flag.
2. **Timeline** — swimlanes: mouse activity, camera activity, clicks, script states, idle, errors.
3. **Mouse-path replay** — 2D canvas replaying cursor at variable speed; click events flash. No screenshot overlay in M4.
4. **Click detail** — full `ClickResolved` event, target + candidates rendered as a table.
5. **Camera chart** — yaw/pitch/zoom line plot vs script-state lane.
6. **Script-state timeline** — bot-watch only; transitions + action_start/end markers, failures highlighted.

The existing `HtmlViewerGenerator` already does most of (3); M4 extends or reuses it. The other pages are new.

### Reports (M5)

CLI driver `./gradlew :client:gameplayReport --args="..."`. Each report emits markdown.

| # | Report | Inputs |
|---|---|---|
| 1 | Session summary | one session |
| 2 | Click-location heatmap by target type | one or many sessions |
| 3 | Hold-duration distribution | one or many sessions |
| 4 | Time-between-actions distribution | one session |
| 5 | Mouse path sampler | one session |
| 6 | Camera movement timeline | one session |
| 7 | Script-state timeline | one bot-watch session |
| 8 | Repeated-cycle detector | one bot-watch session |
| 9 | Repeated-click-position detector | one or many sessions |
| 10 | Manual-vs-bot diff | one live + one bot-watch session of the same activity |

The existing `SummaryGenerator` covers part of (1). New reports go in `analyse/` alongside it.

## Threading rules for new components

Existing recorder components stay on their current threads. **New** components added by this spec follow these rules:

1. **Game state reads.** Reads of `Client`, `WorldView`, widgets, NPCs, game objects, etc. happen only on the client thread, only inside an existing `@Subscribe` handler (`GameTick`, `MenuOptionClicked`, etc.) where the client thread is already where we are. No new component calls `clientThread.invoke()` on a periodic timer.

2. **`ClientSnapshot` pattern for any component that needs game state at higher cadence than per-tick.** Build an immutable snapshot record on the client thread once per tick (and on any relevant client event); store in `AtomicReference<ClientSnapshot>`; worker components read the latest snapshot non-blockingly. Stale-snapshot flag if the consumer's wall-clock minus snapshot wall-clock exceeds a configurable threshold (default 800 ms).

   M1 introduces the snapshot record and the per-tick builder even though M1 does not yet have a consumer that needs it. M3 / M5 will be the first consumers.

3. **No new I/O on the client thread.** All file writes, gzip, JSON encoding, report generation, and recovery scans happen on worker threads.

4. **Bounded queue between producers and the writer.** Default 4096 events. On overflow, drop lowest-value events first in order: `MouseMove` → `Camera` → `Tick` → all others preserved. Drop counters per type surfaced in the manifest.

5. **Fail-open.** All new code paths wrapped in `catch (Throwable)` at the boundary back to the engine. A recorder bug must never propagate to script/dispatcher/client. Writer I/O does not call `fsync`; flush is buffered every 50 events or 1 s.

6. **Recovery.** A `RecoveryScanner` runs at plugin startup, finds orphan `events.jsonl[.gz]` files with no `meta.json` next to them, re-derives a manifest from the events, marks `crashed: true`, and gzips. Confirms the user's "ban-time post-mortem" use case: even if the client is killed mid-session, we keep everything up to the last flush.

## Storage — minimal change

Existing layout:
```
~/.runelite/sequencer/recordings/<timestamped-dir>/
  events.jsonl.gz
  meta.json
  phases.json
  summary.md
  recording.html
```

Spec changes:

- Add `mode`, `script`, `endedReason`, and overflow `drops` counters to `meta.json`. Bump `schemaVersion` from 1 to 2. Existing v1 manifests remain readable — every new reader handles both versions, every new writer emits v2.
- Mode-rollover sessions get separate dirs; the dir name embeds mode + script when applicable: `2026-05-23T12-04-18__bot-watch__FletchingScript`.
- No relocation of existing files. The earlier `~/.runelite/recorder/gameplay/...` path in the design sketch is dropped.

## Milestones

Each milestone lands on its own branch, IRL-tested (or synthetic-tested where no live account is available) before the next.

### M1 — Mode tagging + auto-rollover + snapshot infrastructure

**Goal:** every event in the JSONL stream carries enough context to filter by mode; sessions are single-mode; `ClientSnapshot` + `MouseState` infrastructure is in place for later milestones.

**In scope:**
- Read `RecorderManager.start()/stop()` to confirm trigger; if hotkey-only today, add an auto-start path gated by a config flag (default off).
- New event `Events.ScriptMode { mode, scriptId }` emitted by `SessionTracker` on every transition.
- Rollover policy: on mode transition, close current `RecordingSession`, open a new one with the new mode + scriptId in the dir name; the rollover writes the manifest of the closing session and starts a fresh `events.jsonl.gz`.
- `meta.json` schema additions: `mode`, `script`, `endedReason` (`logout` / `mode_change` / `crash`).
- New record types: `ClientSnapshot`, `MouseState`. Built on client thread per tick (snapshot) and on AWT mouse event (mouse state). Stored in `AtomicReference`. Not consumed in M1; written to set up M3.
- `RecoveryScanner` for orphan events files (if not already present in `RecorderManager`).
- Bounded queue + drop-low-value policy + drop counters in manifest. If the existing buffer already has bounding, extend the drop policy there; otherwise wrap it.
- Config flag `recorderEnabled`, default off.
- Panel placeholder ("Gameplay sessions" section visible but inert).

**Out of scope:**
- Click attribution. M2.
- Dispatcher hook. M3.
- Inspector pages 2-6. M4.
- New reports. M5.

**Testing (no live account):**
- Unit-test JSONL writing, flush, gzip on close, manifest writing (use existing `EventCodec`).
- Unit-test recovery: write a synthetic orphan, assert manifest regenerated with `crashed: true`.
- Unit-test queue overflow: synthetic flood; assert `MouseMove` drops first.
- Unit-test `ScriptMode` emission: fake `RecorderState` and `SessionTracker`; assert event sequence and rollover dir names.
- Unit-test path sanitisation: account names with non-`[A-Za-z0-9_-]` chars must not produce illegal filesystem paths.
- Test seed: 10-minute synthetic event stream produces a valid gzipped JSONL + readable manifest.

The existing `RecorderSmokeTest` is the model — extend it.

### M2 — Unified click attribution

**Goal:** every click in the JSONL has a single `ClickResolved` event with target + candidates + confidence.

**In scope:**
- `ClickAttributor` component on the writer thread, consuming the existing event stream.
- `Events.ClickResolved` record + sealed-union entry + codec coverage.
- Confidence rules:
  - 0.98 = `MenuClick` action matches a unique `WidgetClick`/`WorldClick` exactly.
  - 0.85 = single candidate but bounds derived from a stale tick.
  - 0.60 = multiple candidates within 2 tiles, picked by menu verb.
  - < 0.50 = `target.type = unknown`, all candidates still recorded.
- Back-reference to the raw `MenuClick` event by `seq`.
- Click-attribution latency budget: emit within 1 second of `MenuClick`; if no resolution by then, emit with `confidence = 0` and `type = unknown`.

**Out of scope:**
- Dispatcher correlation. M3.

**Testing (no live account):**
- Replay fixture JSONLs from synthetic and prior real recordings; assert `ClickResolved` produced for each click.
- Edge case: `MenuClick` with no following `WidgetClick`/`WorldClick` → unknown with empty candidates.
- Edge case: two candidates within 2 tiles, menu verb matches one → that one wins with 0.60.

### M3 — Dispatcher observability hook + bot-watch correlation

**Goal:** every dispatcher action has a `ScriptActionStart` and `ScriptActionEnd` in the JSONL, joined to the resulting `ClickResolved`.

**In scope:**
- `DispatcherActionListener` interface in `sequence/dispatch/`.
- `HumanizedInputDispatcher` emits to listener at action start, finish, fail. No other behaviour change. Existing log lines stay.
- `BotWatchCorrelator` registers as listener, emits `ScriptActionStart/End/Fail`.
- Join rule for `clickResolvedRef`: the `ClickResolved` with `seq` greater than `ScriptActionStart.seq` and `tMs` within 2 s and click pixel within or adjacent to dispatched target bounds.

**Out of scope:**
- Reports that consume these events. M5.

**Testing (no live account):**
- Mock `HumanizedInputDispatcher` posting listener events from a synthetic test; assert the correlator emits the expected events and the join field is set.
- Edge case: action with no resulting click (dropped by busy-dispatcher) → `ScriptActionEnd` with `clickResolvedRef = null` and `result = dropped`.
- Edge case: action fails before any click → `ScriptActionFail` only, no `End`.

### M4 — Inspector UI pages

**Goal:** offline session inspection from inside `RecorderPanel`.

**In scope:**
- Six pages listed in the architecture section.
- Pure Swing, file-driven, no live-state access.
- Reuse `HtmlViewerGenerator` where possible.

**Out of scope:**
- Embedded canvas thumbnails / screenshots. Defer.
- Live session inspection. Defer.

**Testing (no live account):**
- Unit-test the data loaders for each page against fixture sessions.
- UI smoke-test (manual): open panel, load fixture, click through pages, no exceptions.

### M5 — Reports

**Goal:** ten markdown reports listed in the architecture section.

**In scope:**
- CLI Gradle task `gameplayReport`.
- Each report a separate `analyse/` class consuming the JSONL via existing `EventCodec`.
- Manual-vs-bot diff with statistical distance per measured dimension.

**Out of scope:**
- Continuous integration. The CLI driver is enough.

**Testing (no live account):**
- Each report tested against a fixture session, asserted by output snapshot.

## Risks + edge cases

1. **Touching `HumanizedInputDispatcher` (M3).** Even observability-only changes risk regression. Mitigation: listener is no-op default, single registration slot, all listener calls wrapped in `catch (Throwable)`, existing behaviour is path-untouched. Test plan covers regression.
2. **Mode rollover during a dispatched action.** A script stops while an action is in flight. The action's `End` event ends up in the new "live" session, not the bot-watch one. Mitigation: writer-side rule — when the rollover trigger fires, drain in-flight `ScriptActionStart`s into the closing session before opening the next; `BotWatchCorrelator` queue is flushed inline at rollover.
3. **Account name in path.** Sanitised to `[A-Za-z0-9_-]`; documented in the spec, not a config option.
4. **Disk growth.** Existing sessions go to `~/.runelite/sequencer/recordings/`. We don't change retention; the existing infrastructure manages session dirs. Long-term retention policy is out of scope; add it when needed.
5. **`RecorderManager.start()` currently might not be auto-on-login.** M1 must confirm by reading the code. If hotkey-only, M1 adds an opt-in auto-start with the config flag default off; the flag is flipped on manually by the operator once they want a recording session.
6. **`ClientSnapshot` introduced in M1 with no consumer.** Acceptable — M1 sets it up so M3 doesn't have to do thread-model archaeology under deadline. Cost: a tiny snapshot allocation per tick.
7. **PII on disk.** Account name + world + window size. Local-only, no network. Documented in `meta.json`.
8. **Bot-watch correlation when the dispatcher drops an action.** The action lifecycle still emits `End` with `result = dropped` (no click happens). Handled in M3 test cases.

## First-commit plan (M1)

Each row is one commit. Each commit compiles and the previous ones still pass tests.

| # | Commit | What lands |
|---|---|---|
| 1 | `feat(gameplay-recorder): config flag scaffolding` | `recorderEnabled` config (default off), panel section placeholder, no behaviour. |
| 2 | `feat(gameplay-recorder): ClientSnapshot + MouseState records` | Two records, an `AtomicReference` holder, a per-tick builder hook in `EventBusCapture` (writes the snapshot, no consumer yet). |
| 3 | `feat(gameplay-recorder): ScriptMode event` | New record under `Events`, sealed-permit entry, `SessionTracker` emits it on every transition. Existing recording sessions now show mode transitions in `events.jsonl.gz`. |
| 4 | `feat(gameplay-recorder): mode-based session rollover` | On `ScriptMode` change, `RecorderManager` closes current session and opens a new one with the new mode + script in the dir name. Manifest extended with `mode`, `script`, `endedReason`. |
| 5 | `feat(gameplay-recorder): bounded queue + drop policy + drop counters` | Wrap or extend existing buffer with overflow rule + manifest counters. |
| 6 | `feat(gameplay-recorder): RecoveryScanner for orphan sessions` | Scans existing sessions dir at startup, re-derives manifest from JSONL if missing, sets `crashed: true`, gzips. |
| 7 | `feat(gameplay-recorder): auto-start on login if enabled` | Read RecorderManager.start trigger first; if hotkey-only, add an auto-start path on `LOGGED_IN` gated by the flag. If already auto-on, this commit is a no-op or a tightening. |
| 8 | `test(gameplay-recorder): synthetic harness` | Unit tests per the M1 testing list. Extend `RecorderSmokeTest`. |

After (8), M1 ships. No live testing required to land it.

## Untested assumptions to flag at end of M1

- `RecorderManager`'s actual start trigger (commit 7 confirms or fixes).
- Whether `JsonlGzipWriter` already supports the bounded-queue model or needs to be wrapped (commit 5 confirms).
- Whether `meta.json` schema bump can be done backwards-compatibly (commit 4 — should be, as it's additive fields).

## Open decisions for later milestones (not blocking M1)

- M2 confidence formula (placeholder thresholds in the spec; tune against fixture data).
- M4 page rendering — keep within `RecorderPanel` Swing or detach as a separate window. Decide when M4 starts.
- M5 statistical-distance metric for manual-vs-bot diff — KS test per dimension is the placeholder.

## Review checklist

Before M1 implementation starts, the operator should review:

1. The framing (diagnostic engineering, not anti-detection). Edit the Purpose section if needed.
2. The "what already exists" inventory. Anything I miscategorised?
3. The threading rules for new components. Snapshot pattern OK for M3 use, or do we want it stricter?
4. The M1 scope. Anything to add, anything to cut?
5. The first-commit plan order. Commit boundaries reasonable?
