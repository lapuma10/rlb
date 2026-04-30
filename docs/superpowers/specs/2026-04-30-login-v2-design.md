# Login V2 — Design

**Date:** 2026-04-30
**Status:** Draft, awaiting user review.
**Supersedes:** Partial — leaves V1 (`LoginAssistant`, `LoginRunner`, `LoginStates`,
`IntroScreenDetector`, `LoginButtonDetector`, `WorldSwitcher` stub) intact.
V2 ships alongside V1 as a parallel path.
**Related:** `2026-04-26-login-overhaul-design.md` §7 — picks Option B1
(canvas-pixel humanized world switch) for V2.

---

## 1. Problem summary

V1 login uses canvas-fraction click coordinates for every sprite-rendered
title-screen target (Existing-User, Login-submit, username field, password
field). Constants like `EXISTING_USER_X = 0.59, EXISTING_USER_Y = 0.43` are
fractions of the canvas, but the OSRS title-screen sprite is **always
rendered at fixed 765×503 logical pixels, centered in the canvas**. As the
user resizes the client, the sprite stays the same size and only the
centering offset moves. The fractions drift off the actual button
positions on any non-default canvas size.

Symptoms reported by the user: detection of the login button, the
existing-user button, and the world selector "doesn't work" on the
current client size. The widget-scan fallback path in `IntroScreenDetector`
and `LoginButtonDetector` cannot recover because the title-screen buttons
are sprite-rendered (no widget hierarchy with `getText()` / `getActions()`).

`WorldSwitcher.switchTo()` is also a stub that throws
`UnsupportedOperationException`. There is no working world-switch path.

## 2. Goals

- Replace canvas-fraction clicks with frame-relative offsets anchored to
  the centered 765×503 title sprite. Clicks land correctly at any canvas
  size that is at least 765×503.
- Implement humanized world switching (B1) via the title-screen "Worlds"
  button + the `InterfaceID.WORLD_SWITCHER` widget panel.
- Persist last-used world per account, surface it in the panel UI as a
  pre-filled text field.
- Ship as **Login V2**, parallel to V1. V1 stays untouched so the user
  can keep logging in via the existing button while V2 is in development
  and field-testing.
- Wire a new "Log in V2" button in the Login tab below the existing
  "Log in" button, with a numeric world-id text field that auto-fills
  from the per-account last-world preference.
- Remove the "Debug: dump open widgets" button + handler from the
  panel.

## 3. Non-goals

- No changes to V1 files. V1's bugs are V1's bugs; V2 fixes them via
  rewrite, not retrofit.
- No engine-setter bypass (`setUsername`/`setPassword`/`changeWorld`).
  V2 stays fully humanized (Option B1 from the prior spec).
- No template image matching against canvas pixels. The geometry of the
  centered 765×503 title sprite is sufficient.
- No world-list scrolling. If the user types a world that requires the
  switcher to scroll, V2 fails loudly with `WORLD_SWITCH_FAILED`.
  Follow-up.
- No multi-account batch login automation. One click = one login.
- No removal of the `WidgetDumper` class itself unless it has zero
  remaining callers after the panel button is removed (verified at
  implementation time).

## 4. Architecture

### 4.1 File layout

V1 files (untouched):
```
sequence/login/
  LoginAssistant.java          V1 entry point
  LoginRunner.java             V1 driver
  LoginStates.java             V1 state implementations (canvas-fraction)
  IntroScreenDetector.java     V1 widget scan (returns null on sprites)
  LoginButtonDetector.java     V1 widget scan (returns null on sprites)
  WorldSwitcher.java           V1 stub (UnsupportedOperationException)
```

Shared (used by both V1 and V2):
```
sequence/login/
  WelcomeScreenDetector.java   widget-based, works for both
  CredentialStore.java + impls macOS Keychain / encrypted file
  LoginCredentials.java
  LoginContext.java            V1 context — V2 uses LoginContextV2
  LoginError.java              V2 reuses these enum values
  LoginErrorClassifier.java    V2 reuses
  LoginErrorRecovery.java      V2 reuses (recovery flows error → world switch)
  LoginState.java              V2 uses LoginStateV2 enum
  StateResult.java             generic; both V1 and V2 use it
  HumanizedTyping.java         backspace-hold helper
  WorldPicker.java             V1-only (random F2P pick); V2 takes user-typed id
```

New for V2:
```
sequence/login/
  TitleFrame.java              centered-frame geometry helper
  LoginAssistantV2.java        entry point, parallel to LoginAssistant
  LoginRunnerV2.java           V2 driver
  LoginStateV2.java            enum of V2 states
  LoginStatesV2.java           V2 state implementations (frame-relative)
  LoginContextV2.java          context object — extends/parallels V1
  WorldSwitcherV2.java         B1 humanized world switch
  AccountPrefs.java            per-account last-world persistence
```

### 4.2 Threading

| Code path | Thread | Notes |
|---|---|---|
| Panel button action listener | EDT | Captures world-field text, spawns worker, returns. |
| `Thread("login-v2")` worker | worker | Drives `LoginAssistantV2.login(...)`. |
| `LoginAssistantV2.login()` | worker | Mirrors V1 contract. |
| `LoginRunnerV2.run()` | worker | State loop; sleeps allowed. |
| `LoginStatesV2.*` handlers | worker | Read client state via `dispatcher.runOnClient(...)`. |
| `TitleFrame.current(client)` | client thread (always) | Reads `getCanvasWidth/Height()`. Wrapped via `runOnClient`. |
| `TitleFrame.button(frame, id)` | any | Pure math; no client state. |
| `WorldSwitcherV2.findWorldRow(id)` | client thread | Walks `client.getWidget(WORLD_SWITCHER, ...)`. |
| `dispatcher.clickCanvas(...)` | worker | Dispatcher's internal pre-press hook handles client-thread coord resolution. |
| `dispatcher.clickWidget(id)` | worker | Same. |
| `AccountPrefs.lastWorld(user)` / `setLastWorld(...)` | worker | Backed by `ConfigManager` (thread-safe per RuneLite); disk-bound. Never EDT. |
| Status callback `Consumer<String>` | worker calls; impl `invokeLater`s to EDT | V1 pattern preserved. |
| World-field text read | EDT, captured pre-spawn | `JTextField.getText()` is component access. |

**No frame caching across dispatch.** Per CLAUDE.md §7 ("never hardcode,
never cache while moving"), the frame is recomputed via `runOnClient(...)`
immediately before each `clickCanvas`. The `TitleFrame.current()` helper
is stateless.

### 4.3 Logging contract

V2 uses logger tag `[login-v2]` (parallel to V1's `[login]`). Every state
transition logs `state X → Y`. Every dispatch logs the resolved
`(frameOrigin, buttonOffset, finalCanvasPixel)` for debuggability when
clicks miss.

## 5. State machine

### 5.1 States (`LoginStateV2` enum)

```
WAIT_FOR_LOGIN_SCREEN     game state must be LOGIN_SCREEN
PRECHECK                  bail if already LOGGED_IN
SWITCH_WORLD              optional; only if targetWorldId set and != current
NUDGE_INTRO               click EXISTING_USER button (frame-relative)
RESOLVE_USERNAME          compare current username to target → branch
CLEAR_USERNAME            if mismatched
TYPE_USERNAME             humanized typing
FOCUS_PASSWORD            click password field (frame-relative)
CLEAR_PASSWORD            humanized backspace-hold
TYPE_PASSWORD             humanized typing
CLICK_LOGIN               click LOGIN_SUBMIT button (frame-relative)
WAIT_FOR_AUTH             poll loginIndex / gameState; classify errors
DISMISS_WELCOME           welcome-screen widget click
DONE                      terminal success
FAILED                    terminal failure (carries LoginError)
```

The diff vs V1 is two things:
1. `SWITCH_WORLD` state inserted before `NUDGE_INTRO` (new).
2. Every click in `NUDGE_INTRO`, `CLEAR_USERNAME`, `TYPE_USERNAME` (focus
   click), `FOCUS_PASSWORD`, `CLICK_LOGIN` resolves through `TitleFrame`
   instead of canvas fractions (changed).

### 5.2 Per-state contract

Identical to V1 (see `2026-04-26-login-overhaul-design.md` §5.2):
1. **Read** required client state via `runOnClient`.
2. **Act** (humanized dispatch).
3. **Verify** post-condition; classify failure as `LoginError.*`.
4. Return `StateResult.Continue(NEXT)` or `StateResult.Failure(error)`.

### 5.3 SWITCH_WORLD detail

```
read currentWorld via runOnClient(client::getWorld)
if targetWorldId == null OR currentWorld == targetWorldId:
    return Continue(NUDGE_INTRO)

resolve frame via runOnClient(() -> TitleFrame.current(client))
worldsBtn := TitleFrame.button(frame, WORLDS_BUTTON)
dispatcher.clickCanvas(worldsBtn.x, worldsBtn.y)

// wait for world switcher widget
poll up to 3000ms:
    runOnClient(() -> WidgetActions.isVisible(InterfaceID.WORLD_SWITCHER, ...))
if timed out: log + fail with WORLD_SWITCH_FAILED

// find row
rowChildId := runOnClient(() -> findWorldRow(targetWorldId))
if rowChildId == null: fail with WORLD_SWITCH_FAILED  (world not in visible rows; no scroll v1)

dispatcher.clickWidget(rowChildId)

// wait for world to update
poll up to 3000ms:
    runOnClient(() -> client.getWorld()) == targetWorldId
if timed out: fail with WORLD_SWITCH_FAILED

return Continue(NUDGE_INTRO)
```

The world-row finder walks `WORLD_SWITCHER` children, matches text
beginning with `"World " + worldId` (or whose action equals
`"Switch <world>"`), returns its widget id. If multiple rows match (e.g.,
the world appears in both F2P and Members lists), pick the first visible
one. If none match, return null → caller fails the state.

## 6. TitleFrame helper

```java
public final class TitleFrame {
    public static final int FRAME_W = 765;
    public static final int FRAME_H = 503;

    public record Frame(int x, int y, int w, int h) {}

    public enum ButtonId {
        EXISTING_USER, LOGIN_SUBMIT, WORLDS_BUTTON,
        USERNAME_FIELD, PASSWORD_FIELD
    }

    /** Client thread. */
    public static Frame current(Client client) {
        int cw = client.getCanvasWidth();
        int ch = client.getCanvasHeight();
        int x = Math.max(0, (cw - FRAME_W) / 2);
        int y = Math.max(0, (ch - FRAME_H) / 2);
        return new Frame(x, y, FRAME_W, FRAME_H);
    }

    /** Pure. Returns canvas-pixel coords (logical). */
    public static Point button(Frame f, ButtonId id) {
        Offset o = OFFSETS.get(id);
        return new Point(f.x + o.dx, f.y + o.dy);
    }

    private record Offset(int dx, int dy) {}
    private static final Map<ButtonId, Offset> OFFSETS = Map.of(
        ButtonId.EXISTING_USER,  new Offset(/* dx */, /* dy */),
        ButtonId.WORLDS_BUTTON,  new Offset(/* dx */, /* dy */),
        ButtonId.USERNAME_FIELD, new Offset(/* dx */, /* dy */),
        ButtonId.PASSWORD_FIELD, new Offset(/* dx */, /* dy */),
        ButtonId.LOGIN_SUBMIT,   new Offset(/* dx */, /* dy */)
    );
}
```

The five `Offset` constants are captured during implementation via the
procedure in §10. They are baked in once and do not change at runtime.

**Stretched-mode handling:** `TitleFrame` returns logical-canvas
coordinates. The dispatcher's existing `clickCanvas(x, y)` already
translates logical → physical (it does this for normal widget clicks).
`TitleFrame` does **not** invent its own translation.

**Edge case — canvas smaller than 765×503:** clamping `frameX/Y` to ≥0
keeps the frame anchored at top-left if the canvas is too small. OSRS
enforces a minimum window size that's larger than 765×503 in practice;
this is belt-and-suspenders.

## 7. AccountPrefs

```java
public final class AccountPrefs {
    private static final String GROUP = "recorder.login.v2";
    private static final String KEY_PREFIX = "lastWorld.";
    private final ConfigManager cm;

    public AccountPrefs(ConfigManager cm) { this.cm = cm; }

    /** Worker thread. */
    public Integer lastWorld(String user) {
        String v = cm.getConfiguration(GROUP, KEY_PREFIX + user);
        if (v == null) return null;
        try { return Integer.parseInt(v.trim()); }
        catch (NumberFormatException e) { return null; }
    }

    public void setLastWorld(String user, int worldId) {
        cm.setConfiguration(GROUP, KEY_PREFIX + user, Integer.toString(worldId));
    }
}
```

`ConfigManager` is injected by `RecorderPlugin`'s Guice module (already
provided via `@Inject` elsewhere in the plugin). Storage is the standard
RuneLite per-profile config blob — survives restarts, syncs if the user
has profile sync on.

## 8. UI integration

### 8.1 RecorderPanel.java additions

Field declarations near the existing `loginBtn`:

```java
private final JButton loginV2Btn = new JButton("Log in V2");
private final JTextField worldField = new JTextField(4); // numeric
private LoginAssistantV2 loginAssistantV2;
private AccountPrefs accountPrefs;
```

In `buildLoginTab()`, append a new row directly below the existing
login-row container that holds `loginBtn` + `stopBtn`:

```
[ Log in V2 ]   World: [____]
```

Action listener (EDT — capture, spawn, return):

```java
loginV2Btn.addActionListener(e -> onLoginV2());

private void onLoginV2() {
    if (loginAssistantV2 == null) { setStatus("login v2 unavailable"); return; }
    String user = (String) accountList.getSelectedValue();
    if (user == null) { setStatus("no account selected"); return; }
    if (!loginInFlight.compareAndSet(false, true)) return;

    String worldText = worldField.getText().trim();
    Integer targetWorld;
    if (worldText.isEmpty()) {
        targetWorld = null;
    } else {
        try { targetWorld = Integer.parseInt(worldText); }
        catch (NumberFormatException nfe) {
            loginInFlight.set(false);
            setStatus("world must be a number");
            return;
        }
    }

    LoginCredentials creds = new LoginCredentials(user, credentialStore);
    Thread t = new Thread(() -> {
        try {
            loginAssistantV2.login(creds, targetWorld, this::setStatus);
            if (targetWorld != null) accountPrefs.setLastWorld(user, targetWorld);
        } catch (Exception ex) {
            log.warn("[login-v2] failed", ex);
            setStatus("login v2 failed: " + ex.getMessage());
        } finally {
            loginInFlight.set(false);
            SwingUtilities.invokeLater(this::refreshLoginButtons);
        }
    }, "login-v2");
    t.setDaemon(true);
    t.start();
    loginThread = t;
}
```

The existing `loginInFlight` AtomicBoolean is shared between V1 and V2 so
the user can't kick off both flows simultaneously.

### 8.2 World-field auto-population

Hook into the existing account-selection-changed handler. When the
selected username changes, populate `worldField`:

```java
private void onAccountSelectionChanged() {
    // existing logic ...
    String user = (String) accountList.getSelectedValue();
    if (user != null && accountPrefs != null) {
        Integer w = accountPrefs.lastWorld(user);
        worldField.setText(w == null ? "" : w.toString());
    } else {
        worldField.setText("");
    }
}
```

Empty world field = "no preference, log in to whichever world OSRS
defaults to" → V2 skips `SWITCH_WORLD` entirely.

### 8.3 Button-enable rules

`refreshLoginButtons()` (replaces the existing `loginBtn`-only refresh):

```java
boolean inFlight = loginInFlight.get();
boolean hasSel = accountList.getSelectedValue() != null;
loginBtn.setEnabled(hasSel && !inFlight);
loginV2Btn.setEnabled(hasSel && !inFlight && loginAssistantV2 != null);
```

### 8.4 Wiring (RecorderPlugin)

```java
// in RecorderPlugin's startUp():
panel.setLoginAssistantV2(new LoginAssistantV2(dispatcher, client, clientThread));
panel.setAccountPrefs(new AccountPrefs(configManager));
```

`setLoginAssistantV2` and `setAccountPrefs` are new setters on the panel
parallel to `setLoginAssistant`.

## 9. Removals (debug widgets cleanup)

In `RecorderPanel.java`:
- Delete `dumpBtn` field (~line 145).
- Delete `south.add(dumpBtn, BorderLayout.SOUTH)` (~line 833).
- Delete `dumpBtn.addActionListener(e -> onDumpWidgets())` (~line 841).
- Delete the `onDumpWidgets()` method (~lines 2006-2016).

After removal, `grep -r WidgetDumper runelite-client/`. If
`recorder/debug/WidgetDumper.java` has zero remaining references, delete
it. If it's referenced elsewhere, leave it.

## 10. Offset capture procedure

The five `Offset` constants in `TitleFrame` are captured once during
implementation via a temporary instrumentation:

1. Add a one-screen logger to `ClickInspector` (it already logs menu
   clicks and varbits; one extra branch for raw mouse-press events
   logged with frame-relative deltas).

   ```
   onLeftClick(canvas (cw,ch), pixel (cx,cy)):
       Frame f = TitleFrame.current(client);
       int dx = cx - f.x, dy = cy - f.y;
       log.info("[click-inspector] capture canvas=({},{}) frame=({},{}) rel=({},{})",
                cw, ch, f.x, f.y, dx, dy);
   ```

2. User runs the client at any canvas size, opens the title screen.

3. User left-clicks each of the five targets in order:
   - "Existing User" button on intro card
   - "Worlds" button (top-right of title screen)
   - Username text field (after Existing-User click; on form)
   - Password text field (on form)
   - "Login" submit button (on form)

4. Five log lines appear with `rel=(dx, dy)` values.

5. The dx,dy pairs are baked into `TitleFrame.OFFSETS`.

6. The temporary logger branch is removed. The permanent
   `[click-inspector]` plugin remains untouched.

This procedure replaces the canvas-fraction guesswork with measured
values. If any click ever lands wrong (e.g., Jagex repositions a button
in a future update), re-run steps 1-5 and update the constants.

## 11. Error policy

V2 reuses V1's `LoginError` enum. The new error is:

```
WORLD_SWITCH_FAILED   // SWITCH_WORLD state failed (panel didn't open,
                      // row not found, world didn't update)
```

This already exists in `LoginError.java` per the V1 design (it was
defined for the WorldSwitcher stub failure path). V2 wires it for real.

`LoginErrorRecovery.java` retry behavior: on `WORLD_SWITCH_FAILED`,
**no retry**. The user typed an explicit world; if it can't be reached,
fail the run and surface the error in the panel status. The user can
correct the world id and click again.

On `MEMBER_WORLD` / `WORLD_FULL` (post-login error toasts), V1's
`LoginErrorRecovery` would try to pick a different world via
`WorldPicker`. **V2 disables this fallback**: the user typed an explicit
world, so we don't second-guess them. Surface the error and stop.

## 12. Testing strategy

### 12.1 Unit tests

`TitleFrameTest`:
- `current()` returns origin (0,0) when canvas == 765×503.
- `current()` returns ((cw-765)/2, (ch-503)/2) for larger canvas.
- `current()` clamps to ≥0 when canvas is smaller than 765×503.
- `button()` returns frame.x + dx, frame.y + dy for each ButtonId.

`AccountPrefsTest`:
- Round-trip `setLastWorld(user, n) → lastWorld(user) == n`.
- `lastWorld(unknownUser)` returns null.
- Malformed config value (non-numeric) returns null, doesn't throw.

`LoginStatesV2Test` (per state):
- `WAIT_FOR_LOGIN_SCREEN`: returns Continue(PRECHECK) when LOGIN_SCREEN.
- `SWITCH_WORLD`: skipped when targetWorld null; skipped when current ==
  target; happy path issues frame click + widget click; failure when
  switcher widget never opens.
- `NUDGE_INTRO`: dispatches click at TitleFrame-resolved EXISTING_USER;
  awaits loginIndex == 2.
- (Same shape as V1 unit tests — mock `Client`, `Dispatcher`, capture
  click coordinates, assert on resolved (x,y).)

`WorldSwitcherV2Test`:
- `findWorldRow(301)` returns the matching widget id from a mocked
  `WORLD_SWITCHER` widget tree.
- Returns null when row not visible in tree (forces v1 no-scroll
  fail).

### 12.2 Manual test plan

1. Set canvas to default (765×503-ish), click "Log in V2" with empty
   world field → should log in to default world.
2. Resize canvas to 1200×800, click "Log in V2" → frame click should
   still land on Existing-User button (offset shifts; click still
   correct).
3. Type "301" in world field, click "Log in V2" → V2 should switch
   to world 301 before login.
4. Type "999" (non-existent world), click "Log in V2" →
   `WORLD_SWITCH_FAILED` surfaces in status; no login attempted.
5. Switch account selection → world field auto-populates with last
   used.
6. Verify V1 "Log in" button still works unchanged.

### 12.3 Out-of-scope verification

Stretched mode and HD plugin are out of immediate scope but should be
sanity-checked once: enable stretched mode, confirm clicks land on
buttons. If they don't, that's a bug in the dispatcher's logical→physical
helper, not in `TitleFrame` — file separately.

## 13. Open items / follow-ups

- Scrolling support in the world switcher panel (currently fails loudly
  for worlds not in the visible rows).
- Multi-account batch login (queue multiple usernames).
- If V2 proves stable, retire V1 in a follow-up by deleting
  `LoginAssistant.java` and friends, and renaming V2 → no suffix.
- Stretched-mode and HD-plugin verification, file as a separate ticket
  if `clickCanvas` doesn't translate correctly.

## 14. Acceptance

- [ ] V1 `Log in` button still works exactly as before (no regression).
- [ ] V2 `Log in V2` button drives the new flow end-to-end on a
      non-default canvas size.
- [ ] World text field auto-populates from `AccountPrefs` on account
      selection change.
- [ ] World switch happens when target ≠ current; skipped otherwise.
- [ ] Debug-widgets button + handler removed; `WidgetDumper.java`
      deleted iff no other callers.
- [ ] All five `TitleFrame` offsets captured via the §10 procedure and
      baked in.
- [ ] Unit tests for `TitleFrame`, `AccountPrefs`, V2 states pass.
- [ ] Threading audit: every `client.*` read goes through `runOnClient`;
      no EDT calls into long-running login work.
