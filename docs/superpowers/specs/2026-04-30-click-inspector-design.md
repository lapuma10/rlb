# Click Inspector — design (2026-04-30)

**Goal:** End the "what's the right widget / varc / varbit?" guessing game. A toggleable runtime tool that logs every UI click plus the var-state changes it triggers, so a single in-game session catalogues a flow's signals well enough that follow-up coding is mechanical.

**Why now:** The 2026-04-30 chatbox-input bug took ~30 minutes of grepping `runelite-api/` to find that `MESLAYERMODE` doesn't fire for CS2 numeric prompts and `MESLAYERINPUT` does. With this tool, that would have been one log line.

**Non-goals:** Not a curated catalog file; not a UI overlay; not a state diff against full client snapshots. Just a click → log → grep workflow.

---

## Architecture

One package, four files, ~360 lines total.

```
runelite-client/src/main/java/net/runelite/client/plugins/recorder/inspector/
  ClickInspector.java          ~200 LOC — main subscriber, window state, log emission
  IdConstantResolver.java      ~80 LOC  — reflection-built map: packed id → "Bankmain.NOTE"
runelite-client/src/test/java/net/runelite/client/plugins/recorder/inspector/
  IdConstantResolverTest.java  ~50 LOC

runelite-client/.../recorder/RecorderPanel.java   +~10 LOC  — JCheckBox "Click Inspector"
runelite-client/.../recorder/RecorderPlugin.java  +~5  LOC  — Guice singleton + start/stop hook
```

`ClickInspector` is a Guice singleton owned by `RecorderPlugin`. It subscribes to four EventBus events when enabled, unsubscribes when disabled. State is a single `volatile boolean enabled` plus two `int` tick fields for the active window. No locks beyond that.

## Components

### `ClickInspector`

EventBus subscriptions (registered/unregistered on toggle):
- `MenuOptionClicked` → emit click line, set window
- `VarbitChanged`, `VarClientIntChanged`, `VarClientStrChanged` → emit var line if window open, re-arm window

Window state:
- `windowEndTick` — emit-and-re-arm cap. Set to `currentTick + 3` on click; on each var change inside the window, reset to `currentTick + 3`.
- `hardCapTick` — set to `clickTick + 30`. `windowEndTick` is never advanced past this.
- A var change is "inside the window" iff `currentTick <= windowEndTick`.
- Vars outside the window are silently dropped.

Click filter: only emit for `MenuEntry.getMenuAction()` values that target a widget / inv slot / NPC / item / object. Walk-here, examines, cancel, generic world clicks are skipped.

### `IdConstantResolver`

Built once at plugin start via reflection over the `net.runelite.api.gameval` package:

- `InterfaceID.<Group>.<NAME> = packed int` → map from `packed int` to `"Group.NAME"`.
- `VarbitID.<NAME>` → map from int to `"NAME"`.
- `VarClientID.<NAME>` → map from int to `"NAME"` (used for both varc-int and varc-str — same id space).

Resolver methods:
- `String widget(int packedId)` — returns `"Bankmain.NOTE"` or hex fallback `"0x000c_0036"`.
- `String varbit(int id)` — returns `"BANK_WITHDRAWNOTES"` or `"varbit#4145"`.
- `String varc(int id)` — likewise.

The resolver is a pure data class; no Guice, no client. The reflection runs once, ~50ms at startup.

## Data flow

1. User toggles checkbox on. `ClickInspector.start()` registers listeners.
2. User performs an action in-game (e.g. right-click coins, pick "Withdraw-X").
3. `MenuOptionClicked` fires:
   - Read `menuOption`, `menuTarget`, `param0`, `param1`, `widget`, `getMenuAction()`.
   - If the action is a walk/examine/cancel/etc., return without logging.
   - Resolve widget chain: starting from the leaf widget, walk `getParent()` upward, prepending each named ancestor. Stop at a parent with a named root group/child.
   - Read `widget.getBounds()`, `widget.getText()`, `widget.getItemId()` if present.
   - Emit one click log line.
   - Set `windowEndTick = currentTick + 3`, `hardCapTick = currentTick + 30`.
4. Subsequent ticks: each var change event runs:
   - If `currentTick > windowEndTick`, drop.
   - Otherwise: emit var log line. Reset `windowEndTick = min(currentTick + 3, hardCapTick)`.
5. User toggles off. `ClickInspector.stop()` unregisters listeners; window state cleared.

## Output format

All lines tagged `[click-inspector]` so a single grep extracts the catalogue.

**Click line** (fields omitted when null/zero):
```
[click-inspector] tick=12345 click verb='Withdraw-X' target='<col=ff9040>Coins</col>' widget=Bankmain.ITEM_CONTAINER[3] item=995 bounds=[x=265,y=155,w=36,h=32] text='5,000,000'
```

**Var lines** (one per fired change event — by definition prev != new):
```
[click-inspector] tick=12346 varbit BANK_WITHDRAWNOTES 0->1
[click-inspector] tick=12347 varcInt MESLAYERMODE 11->0
[click-inspector] tick=12347 varcStr MESLAYERINPUT null->''
```

Quoting: `varcStr` single-quotes the value; `null` is printed unquoted as the literal token. `varbit` and `varcInt` print unquoted ints.

## Edge cases (v1)

- **Walks during a long flow.** A buy-prep flow may walk to bank, click a chair, etc. The MenuAction filter handles walks; non-walk widget clicks during the flow each open their own window and log normally.
- **Re-arm storm from gameplay.** A click during combat where animation/HP/run-energy churn could theoretically rearm forever; the 30-tick `hardCap` makes that bounded. No filter list needed for v1 — the user can grep past the noise.
- **Inspector toggled off mid-window.** The window is dropped. Any ongoing var changes after that point are not logged.
- **Repeated clicks within window.** Last click wins for the window timing — `windowEndTick`/`hardCapTick` are recomputed from the new click's tick.
- **Plugin shutdown with inspector on.** Listeners are unregistered in `stop()`; same path as the manual toggle-off.

## Testing

- **`IdConstantResolverTest`** — given a known packed widget id (e.g. `InterfaceID.Bankmain.NOTE`), the resolver returns `"Bankmain.NOTE"`. Same for a varbit and a varc-str id. Unknown ids produce the hex/decimal fallback string.
- **Manual end-to-end** — enable inspector, right-click coins, pick "Withdraw-X", type a number, press Enter. Expect:
  - One click line with `verb='Withdraw-X' widget=Bankmain.ITEM_CONTAINER[<slot>]`.
  - At least one varc line for `MESLAYERINPUT` going `null` → `''`.
  - Optionally a `BANK_WITHDRAWNOTES` line if note mode flipped.

No unit test for `ClickInspector` itself — its behaviour is "subscribe + format + log", and the formatter is covered by reading the manual e2e output.

## Out of scope (deliberate)

- Persistent config (always starts off).
- Filter for noisy varbits — add later if the manual e2e shows the log is unreadable.
- Saving captures to a separate file — explicit user pushback ("I don't want more files").
- A static catalog generated from logs — possible future workflow but not built in v1.
- A "single-shot" / hotkey-armed mode — toggle-on / toggle-off is enough.
