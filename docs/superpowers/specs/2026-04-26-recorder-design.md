# Behaviour Recorder Design

> Companion to the Sequence Engine spec (`2026-04-25-sequence-engine-design.md`). The recorder is a sibling RuneLite plugin that captures human gameplay sessions in enough fidelity that an offline analyst (Claude) can later write convincingly imperfect, behaviourally-grounded `Step` implementations.

## Goal

Capture **how** the user plays — not just **what** they do — so future Steps can be written with sampled distributions for: cursor curves, click jitter within widgets, inter-action delays, camera-rotation habits, idle behaviour, hotkey usage, run-energy management, and right-click menu dwell time. Output is a single, self-contained recording bundle that the user shares with the analyst.

The recorder is a **learning artefact**, not a replay engine. Recorded sessions are not directly executable; the analyst reads the bundle, fits distributions, and writes hand-crafted Steps that reproduce the user's style imperfectly.

## Non-Goals

- Playback / replay of recordings. (May be added later as a debugging affordance, not as the primary use case.)
- Realtime humanisation engine. (The Sequence Engine already has hooks for an `InputDispatcher`; humanisation lives there in a future iteration.)
- Multi-user recordings or cloud upload.
- PII redaction beyond what the chat-source filter provides.

## Architecture

### Plugin Boundary

A new `RecorderPlugin` lives at `runelite-client/src/main/java/net/runelite/client/plugins/recorder/`. It is sibling to `SequencerPlugin` and `WalkerPlugin`; it does **not** depend on the engine or the manager. It writes recordings to disk; it does not feed the engine. Future replay tooling can read the bundle independently.

### Threading model

- **ClientThread / EventBus subscribers** capture game events (`GameTick`, `MenuOptionClicked`, `ChatMessage`, inventory deltas, etc.) and append `RecordedEvent` instances to an in-memory ring buffer.
- **AWT / EDT listeners** (registered via `MouseManager` and `KeyManager`) capture cursor moves, button down/up, wheel ticks, key presses, focus changes — also appending to the same ring buffer.
- **Daemon flusher thread** drains the ring buffer every ~500 ms, serialises a batch as JSONL, and appends gzipped to `events.jsonl.gz`. The flusher is the only thread that touches disk.

The ring buffer is the only piece of shared state; it uses a lock-free MPSC queue (`ConcurrentLinkedQueue`). Events carry their own monotonic sequence number assigned at enqueue time so out-of-order delivery (EDT vs ClientThread races) can be reconstructed by the analyst.

### Lifecycle

1. **Plugin startUp** registers the side panel, mouse/key listeners, and event-bus subscribers. The recorder is **idle** by default; nothing is buffered.
2. **User clicks Record** (or presses the configured hotkey). The recorder generates a new session id (`<UTC-timestamp>-<intent-placeholder>`), creates the recording directory, writes a partial `meta.json`, and starts the flusher thread.
3. **While recording**, every captured event is enqueued. The flusher periodically appends batches to `events.jsonl.gz`. The side panel shows live counters: events captured, time elapsed, last marker.
4. **User clicks Stop** (or presses the hotkey again). The recorder prompts for an intent label via a modal. The flusher drains, closes the gzip stream, runs the auto-segmenter, runs the summary generator, writes `phases.json` and `summary.md`, generates `recording.html`, and renames the directory to embed the final intent label. The folder is then opened in the OS file browser.

### Storage layout

Recordings live under the standard RuneLite user dir:

```
~/.runelite/sequencer/recordings/2026-04-26-1442-flax-bank-loop/
├── meta.json
├── events.jsonl.gz
├── phases.json
├── summary.md
└── recording.html
```

`meta.json` (small, human-readable):
```json
{
  "session_id": "2026-04-26-1442-flax-bank-loop",
  "intent_label": "flax-bank-loop",
  "started_at_utc": "2026-04-26T14:42:11Z",
  "ended_at_utc":   "2026-04-26T15:08:33Z",
  "duration_ms": 1582000,
  "runelite_version": "1.12.25-SNAPSHOT",
  "character_name": "Mantas",
  "world": 308,
  "client_dimensions": [1280, 720],
  "fixed_mode": false,
  "event_counts": { "mousemove": 18432, "click": 87, "tick": 2640, ... },
  "marker_count": 4,
  "schema_version": 1
}
```

`events.jsonl.gz` is the raw stream — one event per line, gzipped — described below. `phases.json` is the auto-segmenter output. `summary.md` is the descriptive-stats handoff document. `recording.html` is the standalone scrubber.

## Captured Event Types

Every event has a common envelope: `{ "seq": N, "t_ms": ms-since-record-start, "tick": game-tick, "type": "..." }` followed by type-specific fields.

| Type code            | What it captures                                                                                  | Source                         |
|----------------------|---------------------------------------------------------------------------------------------------|--------------------------------|
| `tick`               | Player world tile, animation, idle flag, run energy, run-on flag, hp/max-hp                      | `GameTick` subscriber          |
| `mousemove`          | Cursor x,y in client coordinates                                                                  | `MouseManager.mouseMoved`      |
| `mousedown` / `mouseup` | Button id, x,y, ms duration since previous matching down (recorded on up)                       | `MouseManager` press/release   |
| `wheel`              | x,y, vertical scroll delta                                                                        | `MouseManager.mouseWheelMoved` |
| `key`                | Key code, modifier mask, down/up flag. Captures everything — F-keys, hotbar digits, Esc, modifiers, **and the four arrow keys (`VK_LEFT`/`VK_RIGHT`/`VK_UP`/`VK_DOWN`) used for camera rotation**. Hold duration falls out of paired down/up timestamps. | `KeyManager`                   |
| `focus`              | Gained / lost                                                                                     | `Window` focus listener        |
| `menu_open`          | Right-click menu opened: x,y, list of `{verb, target, target_id, target_kind}` rows               | `MenuOpened` event             |
| `menu_click`         | Selected option: row index, x,y inside menu, ms dwell since `menu_open`, full menu entry         | `MenuOptionClicked` event      |
| `widget_click`       | Click resolved to a widget: widget id, item id (if inventory/bank), bbox, click-offset within bbox | derived from menu_click + widget hit-test |
| `world_click`        | Click resolved to a world tile/entity: entity kind (npc/object/ground), id, world tile, screen-pixel offset within entity bbox | derived from menu_click |
| `inv_change`         | Snapshot diff of `INVENTORY` container: per-slot before→after (item id + qty)                     | `ItemContainerChanged`         |
| `equip_change`       | Same as inv_change for `EQUIPMENT`                                                                | `ItemContainerChanged`         |
| `chat`               | Chat type (filtered set), sender (or "system"), message                                            | `ChatMessage`                  |
| `xp_change`          | Skill, before, after                                                                               | `StatChanged` (system-driven)  |
| `camera`             | yaw, pitch, zoom — emitted on change above threshold and unconditionally before every `world_click` | thresholded sampler        |
| `marker_dialog_open` / `marker_dialog_close` | Recorder noted the user paused to type a marker label                                | marker hotkey                  |
| `nearby`             | Within 12 tiles of player at click time: list of `{kind, id, name, world_tile}` for npcs/objects/players | computed on each `world_click` |
| `marker`             | User-injected annotation: label string                                                             | hotkey                         |

### Chat filtering

Only system-source chat types pass the filter by default:
- **Kept:** `GAMEMESSAGE`, `MESBOX`, `ENGINE`, `CONSOLE`, `BROADCAST`, plus the trade family (`TRADEREQ`, etc.).
- **Dropped:** `PUBLICCHAT`, `PRIVATECHAT`, `PRIVATECHAT_OUT`, `FRIENDSCHAT`, `CLAN_CHAT`, `CLAN_GUEST_CHAT`, `CLAN_GIM_*`.
- Single config toggle `recorder.captureplayerchat` (default: false) re-enables the dropped set.

This filter is the only privacy mechanism. It runs at write-time, so dropped chat never reaches `events.jsonl.gz`.

### Camera capture detail

To avoid a `camera` event every tick, the recorder records on change beyond a threshold:
- yaw delta > 16 jagex-units, **or**
- pitch delta > 16, **or**
- zoom changed by any amount.
Plus an unconditional emit on every `world_click` so click-time camera state is always present.

The recorder also captures the **camera input source** by recording the underlying inputs that *cause* camera changes — not as a separate event type, but as the union of three already-captured streams:

1. **Arrow-key rotation** — the `key` events for `VK_LEFT`/`VK_RIGHT`/`VK_UP`/`VK_DOWN` (with hold duration from paired down/up timestamps).
2. **Middle-mouse-drag rotation** — `mousedown` (button 2) + a sequence of `mousemove` events + `mouseup`.
3. **Compass / minimap-corner clicks** — surface as `widget_click` on the compass widget.

The analyst correlates a `camera` event window with whichever of those three streams was active in the same window to know *how* the user rotates: arrow-key holders vs middle-drag turners vs compass-clickers all leave very different fingerprints.

### Mousemove rate

Mouse moves arrive at AWT's native rate — typically tens of events per second of active motion, near zero when the cursor is still. The recorder enqueues all of them. After gzip, mouse data is the dominant byte budget but still small (~30 KB/min compressed for a busy session). No downsampling at write-time — analyst-side downsampling is trivial if needed. The `mouseMoveDownsampleHz` config exists as a safety valve.

## Side Panel UI

The plugin contributes its own side-panel nav button (priority 8, sitting next to the Sequencer's). The panel has:

- **Status header**: state (`IDLE` / `RECORDING`), elapsed time, current event count, last marker label.
- **Record / Stop button**: toggles state. Disabled while a previous Stop is finalising the bundle (rare, sub-second).
- **Marker entry**: small text field plus an `Add marker` button. Pressing the configured hotkey is equivalent.
- **Recordings list**: shows recent bundles (read from the recordings dir on panel mount and on Stop). Each row has `Open folder` and `Open viewer` buttons.

All button callbacks marshal through `ClientThread.invoke(...)` for any state read (e.g. `client.getLocalPlayer()`). Disk IO and dialog prompts run on the EDT directly.

## Hotkey

Configurable via `RecorderConfig.markerHotkey` (default unset; the user binds it in the plugin config). When the recorder is **idle**, the hotkey toggles recording on. While **recording**, the hotkey opens an inline marker dialog: a tiny modal with one text field, default-focused, Enter inserts the marker, Esc cancels. The dialog's open/close adds `marker_dialog_open` / `marker_dialog_close` events so the analyst can see the user paused to annotate.

A separate config key `recorder.toggleHotkey` (default unset) toggles record on/off without invoking the marker dialog.

## Auto-Segmenter

On Stop, after the gzip stream is finalised, the recorder performs a single pass over the buffered events (still in memory; the in-memory copy is retained until segmenting completes) and produces `phases.json`:

```json
{
  "phases": [
    { "id": 0, "started_t_ms": 0,       "ended_t_ms": 412000, "label": null, "marker_label": null, "click_count": 24, "walked_tiles": 312 },
    { "id": 1, "started_t_ms": 412000, "ended_t_ms": 480000, "label": "deposit phase starts here", "marker_label": "deposit phase starts here", "click_count": 5, "walked_tiles": 8 },
    ...
  ]
}
```

Segmentation rules:
1. **Hard boundary**: every `marker` event creates a new phase, named with the marker label.
2. **Soft boundary**: if no marker boundary applies, a phase break is inferred when the cursor is stationary AND no clicks fire for **≥ 8 game ticks (~4.8s)**.
3. Phases shorter than 1 tick are merged into their predecessor.

The thresholds are conservative — better to under-segment than to fragment into noise. The analyst can re-segment from the raw stream when needed.

## Summary Generator (`summary.md`)

This is **the artefact the user shares with the analyst**. It must fit comfortably in a chat context window (target: ≤ 400 lines).

Sections:
1. **Overview** — intent label, duration, world, character, tile travelled, total clicks, mean inter-click delay (with stdev), mean cursor velocity.
2. **Phases** — table from `phases.json` with one row per phase (label, duration, click count, walked tiles, dominant interaction).
3. **Interaction inventory** — unique items handled (id + name + count of touches), unique NPCs interacted with, unique objects clicked, list of widgets clicked with hit count.
4. **Click distributions per widget kind** — for each `widget_kind` (inventory slot, bank slot, prayer orb, ...), mean click-offset within bbox, stdev, min/max.
5. **Cursor approach stats** — distribution of (velocity, curvature) over the last 200 ms of cursor path before each click. Quartile summary.
6. **Hotkey use** — counts per key.
7. **Run-energy / camera notes** — when run was toggled, how often camera was rotated, mean rotation magnitude per click.
8. **Markers** — verbatim list of every user marker with its tick.

Generation is pure analytical code over the in-memory event list. ~100 LOC of plugin code.

## Standalone HTML Viewer (`recording.html`)

A single self-contained file the user can double-click to open. Layout:

- **Top:** scrub bar with phase markers and user markers superimposed.
- **Centre:** square canvas overlaying:
  - the cursor trail for the visible window (semi-transparent line with start/end dots)
  - click points (coloured by mouse button)
  - the player's tile path (separate colour) projected onto the same canvas via a fixed scale
- **Right side:** event list filtered to the visible window.
- **Bottom:** play/pause and rate buttons (1×, 4×, 16×).

The viewer bundles a vanilla-JS player (~150 lines) and embeds the recording's events JSON directly in the HTML (so opening the file does not require the gzip — the generator inflates events into the HTML at write-time, capped at a sane size budget; for very long recordings the viewer paginates by phase).

No dependencies. No server. No build step.

## Configuration (`RecorderConfig`)

| Key                          | Type    | Default     | Purpose                                     |
|------------------------------|---------|-------------|---------------------------------------------|
| `markerHotkey`               | Hotkey  | unset       | Open marker dialog while recording          |
| `toggleHotkey`               | Hotkey  | unset       | Toggle record on/off                        |
| `captureplayerchat`          | boolean | false       | Include player-chat sources in stream       |
| `cameraSampleThresholdYaw`   | int     | 16          | Yaw delta (jagex units) above which to emit |
| `cameraSampleThresholdPitch` | int     | 16          | Pitch delta above which to emit             |
| `flushIntervalMs`            | int     | 500         | Daemon flush cadence                        |
| `mouseMoveDownsampleHz`      | int     | 0           | Optional downsample. 0 = keep all.          |
| `recordingsDir`              | path    | `~/.runelite/sequencer/recordings` | Override storage location |

## Performance Budget

For a typical 30-minute flax-and-bank session:

- ~2,000 ticks
- ~50,000–80,000 mouse-move events (AWT-native rate over ~30 min of mixed active and idle phases)
- ~150 clicks (with paired down/up + dwell-time records)
- ~50 chat events (system only)
- ~30 inventory deltas
- ~10 user markers

Raw JSONL size: ~5–8 MB. Gzipped: **~1–2 MB / 30 min**. Memory footprint of the ring buffer mid-session: bounded by the daemon flush cadence (~500 ms of events at peak rate is well under 1 MB).

## Dependencies

- RuneLite plugin SDK (already on classpath)
- `MouseManager`, `KeyManager` from RuneLite (already used by other plugins)
- `Gson` (already on classpath via guice config layer) for JSON encoding
- `java.util.zip.GZIPOutputStream` from JDK
- No new third-party libraries.

## Testing

Unit tests on the analytical pieces:
- Phase auto-segmenter against synthetic event traces
- Summary generator on a fixture recording (pinned bytes-on-disk and pinned summary.md)
- Chat filter against the full `ChatMessageType` enum
- Click resolver (menu_click → widget_click vs world_click)

End-to-end: a `RecorderManager` smoke test that drives synthetic events through the buffer and asserts a complete bundle is produced on Stop, with all five files present and summary.md non-empty.

UI / mouse-listener wiring is not unit-tested; verified by running RuneLite locally and checking that a 60-second test recording produces a sensible bundle.

## Future Extensions (out of scope for v1)

- Replay engine: a `ReplayStep` that consumes a bundle and reproduces the events through the engine's `InputDispatcher`.
- Humanisation library: a separate package that exposes pre-fit distributions extracted from a corpus of recordings, usable by hand-written Steps.
- Per-recording diff against a saved corpus to flag "this session is unusually different from your usual flax run."
- Recording compression of mouse data via polyline simplification (Ramer–Douglas–Peucker) at write-time. Currently deferred since gzip handles the bulk well enough.

## Open Questions

None at spec time. All scope, capture, storage, lifecycle, and filter decisions are pinned above.
