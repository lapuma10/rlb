# Recorder + Humanized Dispatch — system handoff

Date: 2026-04-26
Branch: `worktree-sequence-engine`
Worktree: `/Users/lilbee/Documents/GitHub/rlb/.claude/worktrees/sequence-engine`
Status as of writing: dispatcher smoke-tested in client, recorder
captures full play traces, end-to-end agent loop not yet built.

This doc is written so a fresh Claude (or a future you) can pick this
up cold. Read it top to bottom; cross-reference the file paths.

---

## 0. The end goal in one sentence

Capture human OSRS gameplay with the **Recorder** plugin, then play it
back via a **Humanized Dispatcher** that drives the game with real
synthetic mouse/keyboard events on the canvas — never with engine-level
`menuAction` shortcuts. Use multiple recordings to derive a "corridor"
of behaviour the agent can sample from, so replays don't repeat the
exact same path each run. The first concrete activity is "kill chickens
in Lumbridge, bank at the castle, repeat" — see the sibling plan
`2026-04-26-chicken-routine.md`.

---

## 1. Hard constraints (these are not negotiable)

1. **No magic clicks.** The agent must talk to the game through real
   `MouseEvent` / `KeyEvent` instances dispatched to the AWT canvas.
   `client.menuAction(...)` is forbidden in the humanized path. The
   existing `DirectInputDispatcher` calls it directly — kept only for
   tests, never used at play time.
2. **All cursor movement must exist.** Every click is preceded by a
   humanized cursor path (currently a Bezier with jitter and an
   ease-in/ease-out speed envelope). No teleports.
3. **Behaviour must vary across runs.** Identical path/timing per run
   is a bot tell. Eventually we sample a tile from a corridor heat-map
   built from N recordings; for now we have per-click pixel jitter and
   per-step gaussian noise.
4. **The world tile is the canonical form**, not minimap pixels or
   scene coords. Minimap pixels are ephemeral (re-derived at replay
   time from the player's current position). Recordings store world
   tiles only.

---

## 2. Architecture in three layers

```
┌─────────────────────────────────────────────────────────────────┐
│  PLANNER  (not built yet — activity.json + state machine)       │
│  Decides "next action" based on inventory, HP, location, time.  │
└──────────────────────────────┬──────────────────────────────────┘
                               │ ActionRequest (Kind, target descriptor)
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│  HUMANIZED DISPATCHER  (built — not yet integrated with planner)│
│  HumanizedInputDispatcher → PixelResolver → WindMouse → CanvasInput│
│  Real cursor moves + real clicks fired into client.getCanvas(). │
└──────────────────────────────┬──────────────────────────────────┘
                               │ AWT MouseEvent / KeyEvent
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│  RUNELITE CLIENT  (untouched core — its own MouseListener chain)│
│  Engine resolves clicks → MenuOptionClicked → walk / interact.  │
└──────────────────────────────┬──────────────────────────────────┘
                               │ events captured back
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│  RECORDER  (built + tested)                                     │
│  Captures every input + game-state change to events.jsonl.gz.   │
│  Re-records the agent's own actions identically to a human's —  │
│  useful as a self-test (replay → re-record → diff).             │
└─────────────────────────────────────────────────────────────────┘
```

The dispatcher and the recorder are **independent** — neither imports
the other. They communicate only via game state. This is deliberate;
either can be replaced or tested in isolation.

---

## 3. Recorder — what's built and where

Plugin lives in
`runelite-client/src/main/java/net/runelite/client/plugins/recorder/`.

### File map

| File | Role |
|------|------|
| `RecorderPlugin.java` | Lifecycle + DI. Wires capture + manager + panel + hotkeys. |
| `RecorderManager.java` | Owns runtime state. `start()/stop()/abort()`. Builds `summary.md`/`recording.html` on stop. Exposes `elapsedMs / totalEvents / eventCountsSnapshot / recentEventsSnapshot` for the side panel. |
| `RecorderPanel.java` | Side-panel UI: status, marker, last-50 scrolling event feed, **and** the Test-walk smoke surface. |
| `RecorderConfig.java` / `RecorderState.java` | Config + state enum (IDLE/RECORDING/FINALISING). |
| `events/Events.java` | Sealed-record event hierarchy: Tick, MouseMove, MouseDown/Up, Wheel, Key, Focus, MenuOpen, MenuClick, **WidgetClick**, **WorldClick**, InvChange, EquipChange, **BankChange**, Chat, XpChange, Camera, Nearby, Marker, MarkerDialog. |
| `events/RecordedEvent.java` | Sealed interface (`seq, tMs, tick, type()`). Add new event types here too — the `permits` clause is enforced. |
| `events/EventCodec.java` | Records → JSON line. Renames `tMs` to `t_ms` for spec compatibility. |
| `capture/EventBusCapture.java` | RuneLite event-bus subscriber. Translates GameTick / MenuOptionClicked / ChatMessage / ItemContainerChanged / StatChanged into `Events.*` and enqueues. **Also tracks `getLocalDestinationLocation` per tick → emits `WorldClick entityKind="walk_dest"` on change.** This is the source of truth for "where did the player decide to walk", regardless of how the click was registered. |
| `capture/ClickResolver.java` | Resolves a `MenuOptionClicked` into either a `WidgetClick` or a `WorldClick`. Handles WALK / SET_HEADING / NPC / GAME_OBJECT / PLAYER. Maps widget parent ID → human kind name (`inventory`, `bank`, `ge_offers`, `side_tab`, etc.). For WALK / SET_HEADING uses `client.getSelectedSceneTile().getWorldLocation()` — `param0/param1` are screen pixel echoes, not scene coords. |
| `capture/NearbyResolver.java` | On each world-target click, snapshots NPCs + players within 12 tiles. Lets us reconstruct "what was the user choosing between?". |
| `capture/MouseCapture.java` / `KeyCapture.java` / `FocusCapture.java` | AWT-level input mirrors. |
| `capture/CameraSampler.java` | Throttled camera state sampling. |
| `capture/ChatFilter.java` | Allowlist for chat types. |
| `buffer/RecordingBuffer.java` | Lock-free MPSC queue. Tracks per-type counts and a 50-element "interesting" ring (clicks, world clicks, inv/equip/bank changes, chat, markers, xp drops) for the side panel. |
| `buffer/JsonlGzipWriter.java` | Streaming gzipped JSONL writer. |
| `flush/FlushDaemon.java` | Drains buffer to writer on a daemon thread; retains a copy in-memory for analysis on stop. |
| `analyse/PhaseSegmenter.java` | Splits the event stream into phases. Hard boundary on Marker; soft boundary on `IDLE_TICKS=25` (15s) of no activity. Merges short (<2s) unlabeled phases into the previous phase to reduce GE-style noise. |
| `analyse/SummaryGenerator.java` | Pure analytical pass → `summary.md`. Sections: Overview, **Movement (start/end/bbox/distance/walk targets — split by ground/minimap/engine-observed)**, Phases, Interaction inventory, Inventory deltas, Bank deltas, Chat, Click distributions, Cursor approach, Hotkey use, Run-energy/camera, Markers. Item names resolved via `ItemManager` injected from the manager (graceful fallback to `id=N`). |
| `analyse/HtmlViewerGenerator.java` | Self-contained HTML scrubber. |
| `session/SessionDirectory.java` / `MetaJson.java` / `RecordingSession.java` | Session dirs at `~/.runelite/sequencer/recordings/<timestamp>-<intent>/` with five files: `events.jsonl.gz`, `meta.json`, `phases.json`, `summary.md`, `recording.html`. Auto-renamed on stop with the intent label, opened in Finder. |
| `hotkey/HotkeyHandler.java` / `MarkerDialog.java` | Marker hotkey + toggle-record hotkey. The dialog open/close fires its own `MarkerDialog` event so analysis can detect "user paused to think". |

### Key invariants

- All event records implement `RecordedEvent` (sealed). To add a new
  event type: add the record in `Events.java`, add it to the
  `permits` clause in `RecordedEvent.java`, optionally add it to
  `isInteresting()` in `RecordingBuffer.java`. EventCodec auto-handles
  the JSON via Gson, so usually no codec change is needed.
- `tMs` is monotonically non-decreasing, anchored at recording start.
- `tick` is the OSRS game tick (~600ms), captured at enqueue time.
- Mouse coords are canvas-local, not screen.
- World coords (`worldX/worldY/worldPlane`) are absolute global, plane
  ∈ {0..3}.

### What the recorder catches that wasn't obvious

- Minimap left-clicks in resizable mode that fire **no**
  `MenuOptionClicked` event. Caught via the per-tick destination
  tracker in `EventBusCapture.emitWalkDestIfChanged`.
- Player-trade clicks (`MenuAction.PLAYER_*_OPTION`) — emitted as
  `WorldClick entityKind="player"`.
- Bank container changes — `BankChange` event, distinct from
  `InvChange`/`EquipChange`. Required adding `Events.BankChange` to the
  `RecordedEvent` permits clause (sealed interface).
- HUD tab clicks (resizable client) — children 0x22..0x2b and
  0x34..0x3a of `InterfaceID.TOPLEVEL_PRE_EOC` (164) are tab "stones",
  classified as `side_tab`.

### Tests

`runelite-client/src/test/java/net/runelite/client/plugins/recorder/`:
- `RecorderSmokeTest` — full pipeline (start → marker → stop) writes
  all 5 bundle files.
- `events/EventCodecTest` — every record type produces valid JSON.
- `analyse/PhaseSegmenterTest` — single phase / marker boundary / soft
  idle boundary / short-unlabeled-merge.
- `analyse/SummaryGeneratorTest` — every section present, item-delta
  surfacing, bank section, walk targets, player interaction, chat.
- `analyse/HtmlViewerGeneratorTest` — placeholder substitution.
- `capture/ClickResolverTest` — widget click; NPC click; player trade;
  WALK uses `getSelectedSceneTile`; SET_HEADING tagged minimap_walk;
  GE widget classified as `ge_offers`.
- `capture/ChatFilterTest`, `buffer/{RecordingBufferTest, JsonlGzipWriterTest}`.

All 30 recorder tests pass. Other failures in `:client:test`
(`SpecialCounterPluginTest`) are pre-existing on master and unrelated.

---

## 4. Humanized Dispatcher — what's built and where

Lives in `runelite-client/src/main/java/net/runelite/client/sequence/dispatch/`.

| File | Role |
|------|------|
| `InputDispatcher.java` | Existing interface. `dispatch(req)`, `cancel(req)`, `isBusy()`, `mode()`. |
| `DirectInputDispatcher.java` | Existing. Calls `client.menuAction(...)`. **DO NOT use for humanized play** — only for tests / fallback / mock mode. |
| `WindMouse.java` | **NEW.** Bezier cursor path with jitter + ease-in/ease-out speed envelope. Tunables: `controlOffsetSigma=0.18`, `noiseSigma=0.6`, `stepMs=16` (60Hz), `min/maxDurationMs=120/700`. Output is a list of `Sample(x, y, dtMs)` for the dispatcher to feed into MOUSE_MOVED + Thread.sleep(dtMs). |
| `CanvasInput.java` | **NEW.** Posts `MouseEvent`/`KeyEvent` to `client.getCanvas()` via `dispatchEvent`. Tracks the synth cursor position. Pure mechanical layer — no policy. |
| `PixelResolver.java` | **NEW.** Descriptor → screen pixel. `resolveWalkTarget(WorldPoint)` alternates: distance ≤10 + tile polygon on-canvas + clean of NPC/player hulls → main-view sample (uniform inside the actual tile polygon, never the dead centre); else → minimap pixel via `Perspective.localToMinimap`. 25% randomised override picks minimap even on short hops, for variety. `resolveMinimapOnly(...)` forces minimap (used by the dispatcher's hover-fallback). All returns rejection-checked against a 12-deep recent-clicks history (no two clicks within `MIN_REPEAT_PX=2` of each other). NPC + widget resolvers also avoid centre + edges and re-roll on collision. |
| `HumanizedInputDispatcher.java` | **NEW.** Implements `InputDispatcher`. `mode()` returns `HUMANIZED`. Spawns a daemon thread per dispatch (cursor path + click chain off the EDT). Single-flight via `AtomicBoolean busy` — concurrent dispatches dropped. Handles `WALK`, `CLICK_TILE`, `CLICK_NPC`, `CLICK_WIDGET`, `CLICK_INV_ITEM`, `KEY`. **Two-stage walk safety**: (1) `PixelResolver.resolveWalkTarget` filters candidates against NPC/player convex hulls and biases minimap vs main-view by distance; (2) after the cursor reaches the candidate, the dispatcher reads `client.getMenu().getMenuEntries()` — if the top entry isn't `WALK`/`SET_HEADING` it falls back to a minimap-only resolution via `resolver.resolveMinimapOnly(...)`, which always resolves to a walk regardless of what's on the tile. |

### `ActionRequest` reuse

`runelite-client/src/main/java/net/runelite/client/sequence/internal/ActionRequest.java`
already has every `Kind` we need (`WALK`, `CLICK_TILE`, `CLICK_NPC`,
`CLICK_GAME_OBJECT`, `CLICK_GROUND_ITEM`, `CLICK_WIDGET`, `CLICK_INV_ITEM`,
`KEY`) and a `Channel` enum with `MOUSE`/`KEYBOARD`. The dispatcher
does not yet implement `CLICK_GAME_OBJECT` or `CLICK_GROUND_ITEM` — see
"Open items" below.

### Debug overlay + tile mark

`runelite-client/src/main/java/net/runelite/client/plugins/recorder/debug/DebugOverlay.java`
draws a small floating panel near the cursor every render frame. It
shows everything the engine has computed for the current cursor
position: mouse canvas pixel, hovered tile world coords + plane,
top-of-menu (the left-click action), and the full menu-entry list
(option + target + MenuAction type + identifier — capped at 7 lines).
Read-only, no game side effects. Outlines the currently-selected scene
tile in cyan and the user-marked tile in green.

The side-panel section "Debug + tile mark" gives:

- **Mark tile under cursor** — snapshots `client.getSelectedSceneTile()
  .getWorldLocation()`. The engine updates that per render frame based
  on hover state, so just hover any tile and click the button.
- **Walk to mark** — dispatches a WALK `ActionRequest` to the marked
  tile via `HumanizedInputDispatcher`. Test the full path: mark a
  tile, walk away (so it's behind you / off-screen / out of scene),
  then walk back. The dispatcher's minimap fallback handles the
  out-of-sight case automatically.
- **Clear** — removes the mark.

Plugin lifecycle: `RecorderPlugin.startUp()` constructs `DebugOverlay`
and registers it via `OverlayManager.add(...)`, then hands it to the
panel via `panel.setDebugOverlay(...)`. `shutDown()` removes it.

### Test surface

`RecorderPanel.buildTestWalk` adds a side-panel section "Test walk
(humanized)" with a multiline text area + Walk path / Stop buttons.
Paste world tiles one per line (`x,y` or `x,y,plane`); the panel walks
them in sequence via `HumanizedInputDispatcher`. Status updates per
waypoint. The recorder logs the agent's own clicks identically to a
human's (recorder + dispatcher are loop-closed at the test surface).

This is pure smoke-testing. The dispatcher is not yet wired into the
sequence engine's `Executor` — the panel calls
`dispatcher.dispatch(req)` directly.

### Constraints to remember

- **Single-flight.** `dispatch()` returns immediately; the click chain
  runs on a daemon thread. `isBusy()` is true until the chain
  finishes. The test panel polls this between waypoints.
- **Scene-only walks today.** `LocalPoint.fromWorld` returns null for
  tiles outside the loaded scene (~104×104 around player). The minimap
  fallback in `PixelResolver` handles ≤20-tile hops; further than that
  needs a pathfinder we don't have.
- **No transports.** No stair-climb, gate-open, fairy-ring logic.
  Anything across a building boundary fails silently.
- **Off-EDT.** All sleeps in the dispatcher must be on the worker
  thread, never on the Swing EDT (would freeze the UI).

---

## 5. Coordinate system primer (so you don't re-derive this)

Three coordinate spaces in OSRS / RuneLite. Ship-everywhere libraries
(`runelite-api`) provide all needed conversions:

| Space | Class | Conversion |
|-------|-------|-----------|
| World | `WorldPoint(x, y, plane)` | absolute global, what we record |
| Scene | `LocalPoint(x, y)` | 104×104 region around player; units of `LOCAL_TILE_SIZE`=128 |
| Minimap pixel | `Point(x, y)` | screen pixel inside minimap widget |

Conversions:
- `LocalPoint.fromWorld(client, x, y)` — World → Scene; null if not in
  loaded scene.
- `WorldPoint.fromLocal(client, localPoint)` — Scene → World.
- `Perspective.localToCanvas(client, localPoint, plane)` — Scene → main
  view canvas pixel.
- `Perspective.localToMinimap(client, localPoint)` — Scene → minimap
  pixel; null if outside ~20-tile minimap radius. Already accounts for
  player position, camera yaw, minimap zoom, and active minimap widget
  (fixed vs resizable).
- Minimap pixel → World: not direct. The engine does it for us when a
  human clicks; we read `client.getLocalDestinationLocation()` per tick
  to capture it.

Player position right now: `client.getLocalPlayer().getWorldLocation()`.

Walking destination (what the engine intends to move toward, set on
any walk regardless of click source): `client.getLocalDestinationLocation()`.

---

## 6. How to run / build / launch

JDK17 is at `/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`.
The default `java` on this Mac points elsewhere — always export
`JAVA_HOME` first.

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"

# Tests + jar
./gradlew :client:test --tests "net.runelite.client.plugins.recorder.*" :client:shadowJar

# Launch (mac flags required)
java \
  --add-exports=java.desktop/sun.awt=ALL-UNNAMED \
  --add-exports=java.desktop/com.apple.eawt=ALL-UNNAMED \
  --add-exports=java.desktop/com.apple.eio=ALL-UNNAMED \
  --add-opens=java.desktop/com.apple.eawt=ALL-UNNAMED \
  -ea -jar runelite-client/build/libs/client-1.12.25-SNAPSHOT-shaded.jar
```

The project is a **gradle composite build**. The root project is
`runelite` with subprojects `:client`, `:jshell` and **included
builds** `:cache`, `:runelite-api`, `:runelite-gradle-plugin`. Hence
`:client:*` not `:runelite-client:*` in gradle commands.

Recordings auto-save to `~/.runelite/sequencer/recordings/<timestamp>-<intent>/`.

---

## 7. Reference data: anchors & old script

The user has an OSBot script at
`/Users/lilbee/Documents/GitHub/starter-script/starter-script/`. It uses
`org.osbot.rs07.api.*` (not RuneLite) so most code does NOT port —
notably `method.getWalking().webWalk(area)` is OSBot's WebWalker with
embedded transport graph; we have no equivalent.

What's salvageable: **coordinates**. From `core/Combat/CombatTrainer.java`
and `core/Utils/Utils.java`:

| Anchor | Coords (x1,y1,x2,y2,plane) | Notes |
|--------|---------------------------|-------|
| Lumbridge bank | `(3208, 3217, 3209, 3220, plane=2)` | top floor of castle — needs stair climb |
| Chicken pen | `(3228, 3295, 3231, 3300, plane=0)` | |
| Cow field | `(3253, 3258, 3264, 3295, plane=0)` | |
| Grand Exchange | `(3163, 3487, plane=0)` | single point used as "GE centre" |
| Castle north side | `(3206, 3227, 3212, 3228, plane=0)` | |
| Castle south side | `(3206, 3209, 3214, 3211, plane=0)` | |

Use these for hand-typed test routes in the Test walk panel. They
won't work end-to-end (the bank is on plane 2 and the dispatcher can't
climb stairs), but they're the intended *targets* for later.

---

## 8. Open items in priority order

In the order I'd tackle them:

1. **Record one full chicken loop** — this is the canonical test data
   for the rest of the work. User does this manually. After this we
   know:
   - Whether `bank_change` deltas surface correctly when withdrawing
   - Whether NPC attack clicks (`world_click entityKind="npc"`) +
     XP drops fire as expected
   - The actual cadence/path distribution of a real player on this
     specific route
2. **Verbatim replayer.** Reads a recording, dispatches each
   `world_click`/`menu_click`/`widget_click` in order via the
   `HumanizedInputDispatcher`. No humanization, no corridor — just
   "do what the human did". This is the litmus test for whether
   every action kind we capture is dispatch-able. Live in
   `runelite-client/src/main/java/net/runelite/client/sequence/replay/`
   (new package).
3. **Ground-item snapshot in capture.** When the user clicks "Take
   Feathers", we currently capture the click but not what items were
   on the ground. Mirror `NearbyResolver` for ground items: subscribe
   to `ItemSpawned`/`ItemDespawned`, snapshot tile→item list, emit on
   the click. Needed for combat-loot phases.
4. **Anchor markers.** Activity specs need named anchors
   (`lumbridge_bank`, `chicken_pen`). Either hardcode (using the table
   above), or use the marker hotkey to tag arrival in each recording
   so distillation derives anchor coords automatically.
5. **`CLICK_GAME_OBJECT`, `CLICK_GROUND_ITEM` in dispatcher.**
   `HumanizedInputDispatcher.handle()` doesn't implement these yet
   (returns "unhandled"). For game objects: scan
   `client.getTopLevelWorldView().tiles()[plane][x][y].getGameObjects()`,
   pick the matching id, use `obj.getCanvasTilePoly()`. For ground
   items: similar via `ItemSpawned` event tracking.
6. **Distillation tool.** Reads N recordings; emits `corridor.json`
   (per from→to anchor pair, weighted tile heat-map) and
   `cadence.json` (click-timing distribution bucketed by remaining
   distance to current click target). Java or Python. Probably
   Python — easier to iterate on.
7. **Activity spec + state machine.** `activity.json` with phases
   (`walk_to`, `bank_op`, `combat_loop`, `loot_loop`) and a planner
   that switches phases on inventory state, HP, time elapsed.
8. **Pathfinder.** For routes that cross transports (stairs, gates),
   embed [Shortest Path](https://github.com/Skretzo/shortest-path)'s
   tile graph + transport edges. Large data file (~6 MB). Defer
   until activities need it.

The sibling plan `2026-04-26-chicken-routine.md` walks through 1–2
in more detail and ties them to the user-facing chicken activity.

---

## 9. Gotchas / lessons learned

Things that bit us during this work; don't relearn them.

- **`MenuAction.WALK` `param0/param1` are NOT scene tile coords.** They
  echo the mouse screen pixel. `client.getBaseX() + param0` gives
  garbage. Always use `client.getSelectedSceneTile().getWorldLocation()`
  inside the click handler, or fall back to per-tick destination
  tracking. See `ClickResolver.java` lines around the WALK branch and
  `EventBusCapture.emitWalkDestIfChanged`.
- **Minimap left-clicks fire no `MenuOptionClicked`.** Only right-click
  + Cancel emits a menu event (`actionId=1006`). The walk still
  happens — caught by per-tick `getLocalDestinationLocation()` polling.
- **`RecordedEvent` is a sealed interface.** Adding a new record type
  to `Events.java` requires updating the `permits` clause too or
  compilation fails with "class is not allowed to extend sealed
  class". This happened with `BankChange`.
- **macOS launch flags.** Beyond `--add-exports=java.desktop/sun.awt`,
  you also need `com.apple.eawt` and `com.apple.eio` (exports + opens).
  See section 6.
- **`ClassLoader / sealed class loading order`.** Static initializers in
  records that reference each other can throw NPE if the wrong class
  loads first; not currently a problem but watch for it if reorganising.
- **`git stash` reverts everything in the worktree.** Used it during
  testing once, lost the in-flight work for a moment. `git stash pop`
  brought it back. Be careful.
- **Gradle module name is `:client`, not `:runelite-client`.** It's a
  composite build — the directory is `runelite-client/` but the gradle
  project name is `client`.
- **Test fix discipline.** When changing `IDLE_TICKS` or click
  resolution, re-derive the expected outcome of existing test
  scenarios with pencil-and-paper, not by intuition. The phase merge
  test failed twice before I traced through it correctly.

---

## 10. Onboarding for a new context

If you're a fresh Claude reading this:

1. **Read this whole doc first.** Don't start touching code.
2. **Read the sibling plan** `2026-04-26-chicken-routine.md` for the
   user-facing goal and the order of operations.
3. **Browse the file map in section 3 and section 4.** Skim each file
   listed — they're all under 300 lines.
4. **Read the existing tests** under
   `runelite-client/src/test/java/net/runelite/client/plugins/recorder/`
   — they are the spec.
5. **Build + launch (section 6) before changing anything**, to make
   sure the toolchain works on your machine.
6. **Pick the next open item from section 8.** Start with item 1
   (the user records the chicken loop) only if the user hasn't done
   it yet — that's a manual action they take.
7. **Talk to the user before jumping to step 3 or beyond.** Item 2
   (verbatim replayer) is a solid next coding task that does not
   require new manual recordings.

The user prefers terse, action-oriented responses, gets frustrated by
lectures, and values "build it, then test it, then refine it" over
"design it perfectly first". Match the cadence.
